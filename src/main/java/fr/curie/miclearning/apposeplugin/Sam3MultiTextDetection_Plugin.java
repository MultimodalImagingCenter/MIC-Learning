package fr.curie.miclearning.apposeplugin;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MaskByte;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.*;

import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.stream.*;

import static fr.curie.miclearning.apposeplugin.Sam3Dialogs.getClassIdMapFromArrays;
import static fr.curie.miclearning.tools.appose.ApposeUtils.getResourceAsString;
import static fr.curie.miclearning.tools.appose.ApposeUtils.imp2ShmImg;
import static fr.curie.miclearning.tools.detection.DetectionUtils.generateOutputs;

public class Sam3MultiTextDetection_Plugin implements PlugIn {
    protected static ImagePlus imp;

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer <256)
    private double confidenceThreshold;
    private List<String> textPrompts;


    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image-multitextprompt.py";

    @Override
    public void run(String s) {
        // --- 1. get image, model, prompt and parameters ---

        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        // 1.2 retrieve parameters (model path, prompt and output options)
        if (Macro.getOptions() != null) {
            //IJ.log("macro options");
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || classIdMap == null || classIdMap.isEmpty() || modelPath == null) {
            return;
        }

        long startTime = System.nanoTime();
        textPrompts = new ArrayList<>();
        textPrompts.addAll(classIdMap.keySet());


        recordInMacro();
        IJ.log("\n   --- Starting SAM3 detection on 1 image - with text prompts --- ");
        printParameters();

        // --- 2. load script and create env ---
        // 2.1 load python script
        String script = getResourceAsString(SCRIPT_PATH);
        if (script == null) {
            IJ.error("Unable to load script");
            return;
        }

        IJ.log("Loaded python script of length: " + script.length());

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
                    .logDebug()
                    .build();

            IJ.log("Python environment built");

            // --- 3. prediction ---

            // 3.1 copy image in shared memory
            try (ShmImg<?> sharedImg = imp2ShmImg(imp)) {
                //IJ.log("Image copied to shared memory");

                // 3.2 create python service in env
                try (Service python = env.python()) {

                    // 3.3 store image and prompt into a map of inputs to the Python script.
                    final Map<String, Object> inputs = new HashMap<>();

                    inputs.put("image_input", NDArrays.asNDArray(sharedImg));
                    inputs.put("text_prompts", textPrompts);
                    inputs.put("model_path", modelPath);
                    inputs.put("confidence_threshold", confidenceThreshold);

                    // 3.4 Execute script by launching task
                    Service.Task task = python.task(script, inputs);

                    // follow task execution
                    task.listen(event -> {
                        switch (event.responseType) {
                            case LAUNCH:
                                //IJ.log("Task launched");
                                break;
                            case UPDATE:
                                if (event.message != null && !event.message.isEmpty()) {
                                    IJ.log("   " + event.message);
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
                                //System.out.println("Task completed successfully");
                                break;
                            case CANCELATION:
                                System.out.println("Task canceled");
                                break;
                            default:
                                //System.out.println("task response type : " + event.responseType);
                                break;
                        }
                    });

                    // execute task
                    IJ.log("Executing python script...");
                    task.waitFor();

                    // --- 4. get results ---
                    Object resultsObj = task.outputs.get("results_number");
                    if (resultsObj == null) {
                        IJ.error("Error", "Python script did not return 'results_number'.");
                        return;
                    }
                    int numResults = ((Number) resultsObj).intValue();

                    if (numResults == 0) {
                        long endTime = System.nanoTime();
                        double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
                        IJ.log("No objects were detected.");
                        IJ.log(" --- SAM3 detection complete. Total time= "+ totalTimeInSeconds + " sec ---");
                        return;
                    }

                    // extract proba, scores and masks as ND array
                    NDArray output_boxes = (NDArray) task.outputs.get("boxes");
                    NDArray output_masks = (NDArray) task.outputs.get("masks");
                    NDArray output_scores = (NDArray) task.outputs.get("scores");
                    NDArray output_ids = (NDArray) task.outputs.get("prompt_ids");

                    if (output_boxes == null || output_masks == null || output_scores == null || output_ids == null) {
                        IJ.error("Missing output arrays (boxes, masks, scores or ids) from Python.");
                        return;
                    }

                    // --- 5. process results --
                    // 5.1 extract bounding boxes
                    // Initialize the 2D array [Number of Boxes][4 Coordinates]
                    double[][] boxesArray = new double[numResults][4];
                    // extract bounding boxes coordinates
                    DoubleBuffer buf_boxes = output_boxes.buffer().asDoubleBuffer();
                    buf_boxes.rewind();
                    for (int i = 0; i < numResults; i++) {
                        // This reads 4 doubles at a time and moves the pointer forward
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

                    // Get the direct ByteBuffer from Appose
                    // For uint8, Appose provides a java.nio.ByteBuffer
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
                    IntBuffer buf_ids = output_ids.buffer().asIntBuffer();
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
                    IJ.log(" --- Prediction done - total number of detection= " + numResults);

                    // --- 7. process detections ---
                    // create id-name map list
                    List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectedObjects, classIdMap);
                    if (processedDetections.isEmpty()) {
                        IJ.log(" --- No valid detections were processed.");
                    }

                    // --- 8. generate Outputs, based on user choices
                    IJ.log(" --- Generating output... ");
                    generateOutputs(imp, processedDetections, outputOptions, classIdMap);



                } catch (TaskException | InterruptedException e) {
                    throw new RuntimeException(e);
                }

            }

        } catch (BuildException e) {
            throw new RuntimeException(e);
        }

        long endTime = System.nanoTime();
        double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
        IJ.log(" --- SAM3 detection complete. Total time= "+ totalTimeInSeconds + " sec ---");

    }

    private void parseMacro() {
        IJ.log("\nSAM3 detection on macro");
        String options = Macro.getOptions();
        modelPath = Macro.getValue(options, "model_path", null);
        if (modelPath == null || modelPath.trim().isEmpty()){
            IJ.log("No model path. Closing plug-in.\n");
            IJ.error("No model path specified.");
            return;
        }
        confidenceThreshold = Double.parseDouble(Macro.getValue(options, "confidence", "0.5"));
        if (confidenceThreshold < 0 || confidenceThreshold >1) confidenceThreshold = 0.5;

        String prompts = Macro.getValue(options, "text_prompts", null);
        IJ.log("prompts = " + textPrompts);
        if (prompts == null ||prompts.trim().isEmpty()) {
            IJ.log("Prompt empty. Closing plug-in.\n");
            IJ.error("Please enter a valid prompt.");
            return ;
        }
        String[] promptArray = prompts.split(",");

        String roiIDs = Macro.getValue(options, "roi_ids", "1");
        IJ.log("roiIDs = " + roiIDs);
        if (roiIDs.trim().isEmpty()) {
            roiIDs = String.join("", Collections.nCopies(promptArray.length, "0,"));
        }
        String[] roiIDArray = roiIDs.split(",");


        classIdMap = getClassIdMapFromArrays(promptArray,roiIDArray);

        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false"));
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = false;
        outputOptions.createStackMask = Boolean.parseBoolean(Macro.getValue(options, "create_stack_mask", "false"));
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false"));
        outputOptions.createInstanceMaskPerClass = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false"));

    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 Detection with Text Prompt");
        Recorder.recordOption("model_path", modelPath);
        Recorder.recordOption("confidence", String.valueOf(confidenceThreshold));
        String joinedPrompts = textPrompts.stream()
                .map(String::toLowerCase)
                .collect(Collectors.joining(","));
        Recorder.recordOption("text_prompts", joinedPrompts);
        String roiGroupIds = textPrompts.stream()
                .map(classIdMap::get)
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        Recorder.recordOption("roi_ids", roiGroupIds);

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_bounding_boxes", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes)Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createStackMask) Recorder.recordOption("create_stack_mask", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask", String.valueOf(true));
        if (outputOptions.createInstanceMaskPerClass) Recorder.recordOption("create_instance_mask_per_class", String.valueOf(true));
    }

    private void askUser() {
        GenericDialog gd = new GenericDialog("SAM3 detection with text prompt");
        if (!imp.isRGB()) gd.addMessage("Warning : Image is not RGB. This model works better with RGB images.");

        ActionListener modelInstructionAction = e -> {
            Sam3Dialogs.addDownloadInstruction();
        };

        gd.addMessage("");
        gd.addButton("instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // ask for model file
        Sam3Dialogs.addModelPathDialog(gd, PREF_LAST_MODEL_KEY);
        Sam3Dialogs.addThresholdDialog(gd);

        // ask for text prompt
        gd.addMessage("__________");
        Sam3Dialogs.addMultiTextPromptDialog(gd);

        // ask for SAM outputs
        gd.addMessage("__________");
        Sam3Dialogs.addOutputDialogDetectionImage(gd);

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        modelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_MODEL_KEY);
        confidenceThreshold = Sam3Dialogs.getThreshold(gd);
        classIdMap = Sam3Dialogs.getMultiTextPrompt(gd);
        outputOptions = Sam3Dialogs.getOutputAnswerDetectionImage(gd);
    }

    private void printParameters(){
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model: " + modelPath);
        IJ.log("confidence threshold: " + confidenceThreshold);

        String joinedPrompts = textPrompts.stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining(", "));

        IJ.log("text prompts: " + joinedPrompts);

        IJ.log("----------------------");
    }

}
