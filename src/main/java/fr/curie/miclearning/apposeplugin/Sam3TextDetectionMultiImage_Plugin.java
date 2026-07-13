package fr.curie.miclearning.apposeplugin;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.detection.*;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.*;

import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

import static fr.curie.miclearning.apposeplugin.Sam3Dialogs.*;
import static fr.curie.miclearning.tools.appose.ApposeUtils.getResourceAsString;
import static fr.curie.miclearning.tools.appose.ApposeUtils.video2ShmImg;
import static fr.curie.miclearning.tools.detection.DetectionUtils.*;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class Sam3TextDetectionMultiImage_Plugin implements PlugIn {
    protected static ImagePlus imp;
    int nFrames;

    private DetectionMode stackMode = DetectionMode.MULTI_IMAGE;
    private int current_slice = 0; //0 if all slices are processed (even if only 1 slice in original image), position of the processed frame otherwise

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private Sam3Parameters detectionParams;

    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame = new HashMap<>();

    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer >0 and <256)
    private List<String> textPrompts;

    private static final Map<String, Object> END_SIGNAL = new HashMap<>();

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
            IJ.error("Only RGB images are supported.");
            return;
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

        textPrompts = new ArrayList<>();
        textPrompts.addAll(classIdMap.keySet());


        recordInMacro();
        IJ.log("\n   --- SAM Promptable Concept Segmentation - on image stack - with text prompts ---");
        printParameters();

        // --- 2. load script and create env ---
        // 2.1 load python script
        String script = getResourceAsString(SCRIPT_PATH);
        if (script == null) {
            IJ.log("ERROR : Unable to load python script");
            IJ.error("Unable to load script");
            return;
        }

        IJ.log("Python script loaded");

        // 2.2 create environment
        // load toml file
        String envTomlContent = getResourceAsString(ENV_FILE_PATH);
        if (envTomlContent == null) {
            IJ.error("Unable to load environment file");
            return;
        }

        // build env
        try {
            Environment env = Appose.pixi()
                    .content(envTomlContent)
                    //.logDebug()
                    .build();

            IJ.log("Python environment built");

            // --- 3. prediction ---
            // 3.1 copy image stack in shared memory
            try (ShmImg<?> sharedImg = video2ShmImg(imp)) {
                //IJ.log("Image copied to shared memory");

                // 3.2 create python service in env
                try (Service python = env.python()) {

                    // 3.3 store image and prompt into a map of inputs to the Python script.
                    final Map<String, Object> inputs = new HashMap<>();

                    inputs.put("images_input", NDArrays.asNDArray(sharedImg));
                    inputs.put("text_prompts", textPrompts);
                    inputs.put("model_path", modelPath);
                    inputs.put("confidence_threshold", detectionParams.getConfidenceThreshold());
                    inputs.put("mask_threshold", detectionParams.getMaskScoreThreshold());
                    inputs.put("max_side_length", detectionParams.getMaxSideLengthDetect());

                    // 3.4 Execute script by launching task
                    Service.Task task = python.task(script, inputs);

                    // follow task execution and send results to resultsQueue frame by frame
                    BlockingQueue<Map<String, Object>> resultsQueue = new LinkedBlockingQueue<>(); // thread-safe queue for results
                    task.listen(event -> {
                        switch (event.responseType) {
                            case LAUNCH:
                                //IJ.log("Task launched");
                                break;
                            case UPDATE:
                                if (event.message != null && !event.message.isEmpty()) {
                                    IJ.log("   " + event.message);

                                    Map<String, Object> info = event.info;
                                    if(info != null) {
                                        resultsQueue.offer(info); // immediately send result of the frame to the queue
                                    }
                                }
                                break;
                            case CRASH:
                                IJ.error("Python task crashed : ", task.error);
                                IJ.log("ERROR while running python script");
                                return;
                            case FAILURE:
                                IJ.error("Python task failed with error: ", task.error);
                                IJ.log("ERROR while running python script");
                                return;
                            case COMPLETION:
                                resultsQueue.offer(END_SIGNAL); // send signal to stop processing
                                break;
                            case CANCELATION:
                                System.out.println("Task canceled");
                                break;
                            default:
                                //System.out.println("task response type : " + event.responseType);
                                break;
                        }
                    });

                    // --- 4. get  results ---
                    // thread dedicated to result processing (in parallel of python prediction)
                    new Thread(() -> {
                        try {
                            while (true) {

                                Map<String, Object> info = resultsQueue.take();
                                // Check if this is the "End of Stream" signal
                                // meaning that all results have been sent
                                if (info == END_SIGNAL) {
                                    IJ.log("All frames processed.");
                                    break; // Break the infinite loop
                                }

                                // if no end signal, extract results for the frame
                                int numResults = (int) info.get("n_results");
                                int frameIdx = (int) info.get("frame_idx");

                                if (numResults == 0){
                                    detectionsByFrame.put(frameIdx, Collections.emptyList());
                                    continue;
                                }

                                NDArray output_boxes = (NDArray) info.get("boxes");
                                NDArray output_masks = (NDArray) info.get("masks");
                                NDArray output_scores = (NDArray) info.get("scores");
                                NDArray output_prompt_ids = (NDArray) info.get("prompts_ids");

                                if (output_boxes == null || output_masks == null || output_scores == null || output_prompt_ids == null) {
                                    IJ.log("Warning : Missing output arrays (boxes, masks, scores or ids) from Python for frame "+ frameIdx);
                                    detectionsByFrame.put(frameIdx, Collections.emptyList());
                                    continue;
                                }

                                // --- 5. process results --
                                // 5.1 extract bounding boxes
                                // Initialize the 2D array [Number of Boxes][4 Coordinates]
                                double[][] boxesArray = new double[numResults][4];
                                // extract bounding boxes coordinates
                                DoubleBuffer buf_boxes = output_boxes.buffer().asDoubleBuffer();
                                buf_boxes.rewind();
                                for (int i = 0; i < numResults; i++) {
                                    buf_boxes.get(boxesArray[i]);
                                }

                                // 5.2 Extract masks
                                long[] shape = output_masks.shape().toLongArray();
                                int height;
                                int width;
                                if (shape.length == 2) {
                                    height = (int) shape[0];
                                    width = (int) shape[1];
                                } else {
                                    height = (int) shape[1];
                                    width = (int) shape[2];
                                }

                                byte[][][] masksArray = new byte[numResults][height][width];

                                ByteBuffer buf_masks = output_masks.buffer();
                                buf_masks.rewind();
                                for (int i = 0; i < numResults; i++) {
                                    for (int y = 0; y < height; y++) {
                                        buf_masks.get(masksArray[i][y]);
                                    }
                                }

                                // 5.3 Extract probabilities
                                double[] probaArray = new double[numResults];
                                DoubleBuffer buf_scores = output_scores.buffer().asDoubleBuffer();
                                buf_scores.rewind();
                                buf_scores.get(probaArray);

                                // 5.4 Extract prompt ids
                                int[] promptIds = new int[numResults];
                                IntBuffer buf_ids = output_prompt_ids.buffer().asIntBuffer();
                                buf_ids.rewind();
                                buf_ids.get(promptIds);

                                // 5.5 convert results to DetectedObjects
                                List<String> classNames = new ArrayList<>();
                                List<Double> probabilities = new ArrayList<>();
                                List<BoundingBox> boundingBoxes = new ArrayList<>();

                                for (int i = 0; i < numResults; i++) {
                                    int promptIdx = promptIds[i];
                                    classNames.add(textPrompts.get(promptIdx));
                                    double[] coord = boxesArray[i];
                                    MaskByte mask = new MaskByte(coord[0], coord[1], coord[2], coord[3], masksArray[i], true);
                                    boundingBoxes.add(mask);
                                    probabilities.add(probaArray[i]);
                                }

                                DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);

                                // --- 6. process detections ---
                                // 6.1 create Roi from bounding boxes and masks
                                List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectedObjects, classIdMap);
                                if (processedDetections.isEmpty()) {
                                    IJ.log("No valid detections were processed for frame " + frameIdx);
                                }
                                detectionsByFrame.put(frameIdx, processedDetections);

                            }
                            // once all results have been processed (and end_signal received)
                            // --- 7. generate Outputs, based on user choices
                            IJ.log(" --- Generating output... ");
                            generateOutputs();

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            IJ.log("Consumer thread stopped.");
                        } finally {
                            long endTime = System.nanoTime();
                            double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
                            IJ.log(" --- SAM3 segmentation complete. " + detectionsByFrame.size() + " Frame processed. Total time= "+ totalTimeInSeconds + " sec ---");
                        }
                    }).start();

                    // execute task
                    IJ.log("Executing python script...");
                    task.waitFor();

                } catch (TaskException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (BuildException e) {
            throw new RuntimeException(e);
        }
    }

    private void generateOutputs() {
        if (outputOptions.addToRoiManagerBB || outputOptions.addToRoiManagerShapes) {
            RoiManager roiManager = getRoiManager();
            if (current_slice != 0) detectionsByFrame.put( current_slice-1, detectionsByFrame.remove( 0) );
            Detection3dUtils.addTrackedRoisToManager(roiManager, detectionsByFrame, outputOptions.addToRoiManagerBB, outputOptions.addToRoiManagerShapes, Detection3dUtils.GroupingMethod.BY_CLASS);

            roiManager.setVisible(true);
            roiManager.runCommand("Show All"); // Make ROIs visible
        }

        if (outputOptions.createInstanceMask) {
            ImagePlus instanceMaskStack = createInstanceMaskStack(imp, detectionsByFrame);
            if (instanceMaskStack != null) {
                instanceMaskStack.show();
            }
        }

        if (outputOptions.createSemanticMask) {
            ImagePlus semanticMaskStack = createSemanticMaskStack(imp, detectionsByFrame);
            if (semanticMaskStack != null) {
                semanticMaskStack.show();
            }
        }

        if (stackMode == DetectionMode.SINGLE_IMAGE) {
            if (outputOptions.createStackMask) {
                ImagePlus stackMask = createStackMask(imp, detectionsByFrame.values().iterator().next());
                if (stackMask != null) stackMask.show();
            }

            if (outputOptions.createInstanceMaskPerClass) {
                ImagePlus instanceMaskPerClass = createInstanceMaskPerClass(imp, detectionsByFrame.values().iterator().next(), classIdMap);
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
        detectionParams = new Sam3Parameters();
        double confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", String.valueOf(detectionParams.getConfidenceThreshold())));
        if (confidenceThreshold < 0 || confidenceThreshold >1) confidenceThreshold = detectionParams.getConfidenceThreshold();
        detectionParams.setConfidenceThreshold(confidenceThreshold);

        double maskThreshold = Double.parseDouble(Macro.getValue(options, "mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold())));
        detectionParams.setMaskScoreThreshold(maskThreshold);

        int maxSideLength = Integer.parseInt(Macro.getValue(options, "max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect())));
        if (maxSideLength <= 0) maxSideLength = detectionParams.getMaxSideLengthDetect();
        detectionParams.setMaxSideLengthDetect(maxSideLength);

        // outputs
        outputOptions = new DetectionUtils.OutputOptions();
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

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_bounding_boxes");
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
        detectionParams = new Sam3Parameters();
        Sam3Dialogs.addParameterDialog(gd, detectionParams, stackMode);

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
        Sam3Dialogs.getParameters(gd, detectionParams, DetectionMode.SINGLE_IMAGE);
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