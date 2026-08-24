package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.MultiImagePcsResultsConsumer;
import fr.curie.miclearning.tools.detection.*;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;
import org.apposed.appose.*;

import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static fr.curie.miclearning.apposeplugin.sam.Sam3Dialogs.askStackMode;
import static fr.curie.miclearning.apposeplugin.sam.Sam3Dialogs.getClassIdMapFromArrays;
import static fr.curie.miclearning.tools.appose.ApposeUtils.video2ShmImg;
import static fr.curie.miclearning.tools.detection.DetectionUtils.*;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation over an image stack (or single image),
 * with multiple independent text prompts. Detections are independent per frame
 */
public class Sam3TextPromptPcsMultiImg_Plugin implements PlugIn {
    protected static ImagePlus imp;
    int nFrames;

    private DetectionMode stackMode = DetectionMode.MULTI_IMAGE;
    private int current_slice = 0; //0 if all slices are processed (even if only 1 slice in original image), position of the processed frame otherwise

    private OutputOptions outputOptions;
    private String modelPath;
    private Sam3ModelParameters detectionParams;

    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer >0 and <256)
    private List<String> textPrompts;

    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame = new HashMap<>();

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml"; // inside the resources folder
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-multiimage-textprompt-m.py"; // inside the resources folder

    @Override
    public void run(String s) {
        // --- 1. get image, model, prompt and parameters ---

        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        if (!imp.isRGB()){
            ImageConverter impConverter = new ImageConverter(imp);
            impConverter.convertToRGB();
            IJ.log("\nimage " + imp.getTitle() + " converted to RGB");
        }

        // 1.2 retrieve parameters (model path, prompt and output options)
        nFrames = Math.max(imp.getNSlices(), imp.getNFrames());
        if (nFrames == 1) stackMode = DetectionMode.SINGLE_IMAGE;

        if (Macro.getOptions() != null) {
            //IJ.log("macro options");
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || classIdMap == null || classIdMap.isEmpty() || modelPath == null || stackMode == null) {
            return;
        }

        long startTime = System.nanoTime();

        if (nFrames>0 && stackMode == DetectionMode.SINGLE_IMAGE) {
            current_slice = imp.getCurrentSlice();
            imp = new ImagePlus(imp.getTitle(), imp.getProcessor());
        }

        textPrompts = new ArrayList<>(classIdMap.keySet());

        recordInMacro();
        IJ.log("\n   --- SAM Promptable Concept Segmentation - on image stack - with text prompts ---");
        printParameters();


        Sam3TextPromptPcsMultiImgRunConfig config;
        try {
            config = new Sam3TextPromptPcsMultiImgRunConfig.Builder()
                    .modelPath(modelPath)
                    .textPrompts(textPrompts)
                    .classIdMap(classIdMap)
                    .detectionParams(detectionParams)
                    .outputOptions(outputOptions)
                    .stackMode(stackMode)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        runSam3(config, startTime);
    }


    private void runSam3(Sam3TextPromptPcsMultiImgRunConfig config, long startTime) {
        BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>();
        ThreadFactory consumerThreadFactory = r -> {
            Thread t = new Thread(r, "sam3-multiimage-consumer");
            t.setDaemon(true);
            return t;
        };

        ExecutorService executor = Executors.newSingleThreadExecutor(consumerThreadFactory);

        try (Sam3TextPromptPcsMultiImgPythonRunner runner = new Sam3TextPromptPcsMultiImgPythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            runner.initialize();

            MultiImagePcsResultsConsumer consumer = new MultiImagePcsResultsConsumer(
                    resultsQueue, detectionsByFrame, config.getClassIdMap(), config.getTextPrompts(), imp);
            Future<Void> consumerResult = executor.submit(consumer);

            IJ.log("Executing python script...");
            runner.runBlocking(config, imp, resultsQueue);

            consumerResult.get();

            IJ.log(" --- Generating output... ");
            generateOutputs(config);

        }catch (IOException | BuildException e) {
            IJ.error("Unable to prepare Python environment", String.valueOf(e.getMessage()));
            IJ.log("ERROR while preparing python environment: " + e);
        } catch (TaskException e) {
            IJ.error("Python task error", String.valueOf(e.getMessage()));
            IJ.log("ERROR while running python script: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IJ.log("Processing was interrupted.");
        } catch (ExecutionException e) {
            IJ.error("Error while processing results", String.valueOf(e.getCause()));
            IJ.log("ERROR in result consumer: " + e.getCause());
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            long endTime = System.nanoTime();
            double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
            IJ.log(" --- SAM3 segmentation complete. " + detectionsByFrame.size()
                    + " Frame processed. Total time= " + totalTimeInSeconds + " sec ---");
        }

    }

    private void generateOutputs(Sam3TextPromptPcsMultiImgRunConfig config) {
        DetectionUtils.OutputOptions options = config.getOutputOptions();
        DetectionMode mode = config.getStackMode();

        if (options.addToRoiManagerBB || options.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            if (current_slice != 0) detectionsByFrame.put(current_slice - 1, detectionsByFrame.remove(0));
            Detection3dUtils.addTrackedRoisToManager(roiManager, detectionsByFrame,
                    options.addToRoiManagerBB, options.addToRoiManagerShapes, Detection3dUtils.GroupingMethod.BY_CLASS);
            roiManager.setVisible(true);
            roiManager.runCommand("Show All");
        }

        if (options.createInstanceMask) {
            ImagePlus instanceMaskStack = DetectionUtils.createInstanceMaskStack(imp, detectionsByFrame);
            if (instanceMaskStack != null) instanceMaskStack.show();
        }

        if (options.createSemanticMask) {
            ImagePlus semanticMaskStack = DetectionUtils.createSemanticMaskStack(imp, detectionsByFrame);
            if (semanticMaskStack != null) semanticMaskStack.show();
        }

        if (mode == DetectionMode.SINGLE_IMAGE) {
            if (options.createStackMask) {
                ImagePlus stackMask = DetectionUtils.createStackMask(imp, detectionsByFrame.values().iterator().next());
                if (stackMask != null) stackMask.show();
            }
            if (options.createInstanceMaskPerClass) {
                ImagePlus instanceMaskPerClass = DetectionUtils.createInstanceMaskPerClass(
                        imp, detectionsByFrame.values().iterator().next(), config.getClassIdMap());
                if (instanceMaskPerClass != null) instanceMaskPerClass.show();
            }
        }
    }

    private void parseMacro() {
        IJ.log("\nSAM3 pcs on macro");
        String options = Macro.getOptions();

        // model path
        modelPath = Macro.getValue(options, "model_path", null);
        if (modelPath == null || modelPath.trim().isEmpty()){
            IJ.log("No model path. Closing plug-in.\n");
            IJ.error("No model path specified.");
            return;
        }

        // prompts and associated id groups
        String prompts = Macro.getValue(options, "text_prompts", null);
        if (prompts == null ||prompts.trim().isEmpty()) {
            IJ.log("Prompt empty. Closing plug-in.\n");
            IJ.error("Please enter a valid prompt.");
            return ;
        }
        String[] promptArray = prompts.split(",");

        if (options.contains("stack ")) stackMode = DetectionMode.MULTI_IMAGE;
        else stackMode = DetectionMode.SINGLE_IMAGE;

        String roiIDs = Macro.getValue(options, "roi_ids", "1");
        if (roiIDs.trim().isEmpty()) {
            roiIDs = String.join("", Collections.nCopies(promptArray.length, "0,"));
        }
        String[] roiIDArray = roiIDs.split(",");
        classIdMap = getClassIdMapFromArrays(promptArray,roiIDArray);

        // parameters
        detectionParams = new Sam3ModelParameters();
        double confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", String.valueOf(detectionParams.getConfidenceThreshold())));
        if (confidenceThreshold < 0 || confidenceThreshold >1) confidenceThreshold = detectionParams.getConfidenceThreshold();
        detectionParams.setConfidenceThreshold(confidenceThreshold);

        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold())));
        detectionParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLength = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect())));
        if (maxSideLength <= 0) maxSideLength = detectionParams.getMaxSideLengthDetect();
        detectionParams.setMaxSideLengthDetect(maxSideLength);

        // outputs
        outputOptions = new OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false")) || options.contains("add_box_rois ");
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false")) || options.contains("add_shape_rois ");
        outputOptions.deletePreviousRoi = false;
        outputOptions.createStackMask = (Boolean.parseBoolean(Macro.getValue(options, "create_stack_mask", "false")) || options.contains("create_stack_mask "))  && stackMode == DetectionMode.SINGLE_IMAGE;
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false")) || options.contains("create_instance_mask ");
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false")) || options.contains("create_semantic_mask ");
        outputOptions.createInstanceMaskPerClass = (Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false"))  || options.contains("create_instance_mask_per_class "))  && stackMode == DetectionMode.SINGLE_IMAGE;
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 PCS with Text Prompts");
        Recorder.recordOption("model_path", modelPath);

        String joinedPrompts = textPrompts.stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining(","));
        Recorder.recordOption("text_prompts", joinedPrompts);
        String roiGroupIds = textPrompts.stream()
                .map(classIdMap::get)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        Recorder.recordOption("roi_ids", roiGroupIds);

        Recorder.recordOption("confidence", String.valueOf(detectionParams.getConfidenceThreshold()));
        Recorder.recordOption("mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois");
        if (outputOptions.addToRoiManagerShapes)Recorder.recordOption("add_shape_rois");
        if (outputOptions.createStackMask && stackMode == DetectionMode.SINGLE_IMAGE) Recorder.recordOption("create_stack_mask");
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask");
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask");
        if (outputOptions.createInstanceMaskPerClass && stackMode == DetectionMode.SINGLE_IMAGE) Recorder.recordOption("create_instance_mask_per_class");

        if (stackMode == DetectionMode.SINGLE_IMAGE) Recorder.recordOption("slice");
        else Recorder.recordOption("stack");
    }



    private void askUser() {
        if (nFrames >1) stackMode= askStackMode(nFrames);
        if (stackMode == null) return;
        GenericDialog gd = new GenericDialog("SAM3 Promptable Concept Segmentation with text prompts");

        // instructions to download model
        ActionListener modelInstructionAction = e -> {
            Sam3Dialogs.addDownloadInstruction();
        };
        gd.addMessage("");
        gd.addButton("instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // ask for model folder
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_MODEL_KEY);

        // ask for text prompt
        gd.addMessage("__________");
        Sam3Dialogs.addMultiTextPromptDialog(gd);

        // ask for parameters
        gd.addMessage("__________");
        detectionParams = new Sam3ModelParameters();
        Sam3Dialogs.addParameterDialog(gd, detectionParams);

        // ask for SAM outputs
        Sam3Dialogs.addOutputDialog(gd, stackMode);

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        modelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_MODEL_KEY);
        classIdMap = Sam3Dialogs.getMultiTextPrompt(gd);
        Sam3Dialogs.getParameters(gd, detectionParams);
        outputOptions = Sam3Dialogs.getOutputAnswer(gd, stackMode);
    }

    private void printParameters(){
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("number of slice(s): " + (stackMode ==  DetectionMode.SINGLE_IMAGE ? 1 : nFrames));
        IJ.log("model: " + modelPath);
        IJ.log("confidence threshold: " + detectionParams.getConfidenceThreshold());
        IJ.log("mask score threshold: " + detectionParams.getMaskScoreThreshold());

        String joinedPrompts = textPrompts.stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining(", "));

        IJ.log("text prompt(s): " + joinedPrompts);

        IJ.log("----------------------");
    }
}