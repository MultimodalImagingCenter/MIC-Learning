package fr.curie.miclearning.apposeplugin;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.detection.*;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static fr.curie.miclearning.tools.appose.ApposeUtils.getResourceAsString;
import static fr.curie.miclearning.tools.appose.ApposeUtils.video2ShmImg;
import static fr.curie.miclearning.tools.detection.Detection3dUtils.*;

public class Sam3VideoTextDetection_Plugin implements PlugIn {
    protected static ImagePlus imp;
    int nFrames; // number of frames in the video

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private double confidenceThreshold;

    private String textPrompt;
    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer <256)
    // as we only have one prompt for now, and group id is not relevant, it is only {prompt:1}

    int maxFrameNumber; // number of frames that will be processed
    private MultiFrameDataManager mfdManager; // registry of all detections by frames
    // actually to useful for now, may be if we want to extract stats, or add filter to remove 1 slice only objects...

    private static final Map<String, Object> END_SIGNAL = new HashMap<>();

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3.hg";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image-hg.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-video-textprompt-hg.py";

    @Override
    public void run(String s) {
        // --- 1. get image, model, prompt and parameters ---
        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }
        nFrames = Math.max(imp.getNSlices(), imp.getNFrames());
        if (!imp.isRGB()) {
            IJ.error("Only RGB images are supported.");
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
        textPrompt = classIdMap.keySet().toArray()[0].toString();
        mfdManager = new MultiFrameDataManager(maxFrameNumber);

        recordInMacro();
        IJ.log("\n   --- Starting SAM3 segmentation on video - with text prompt --- ");
        printParameters();

        // --- 2. load script and create env ---
        // 2.1 load python script
        String script = getResourceAsString(SCRIPT_PATH);
        if (script == null) {
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
            // 3.1 copy video in shared memory
            try (ShmImg<?> sharedVid = video2ShmImg(imp)) {

                // 3.2 create python service in env
                try (Service python = env.python()) {

                    // 3.3 store video and prompt into a map of inputs to the Python script.
                    final Map<String, Object> inputs = new HashMap<>();

                    inputs.put("video_input", NDArrays.asNDArray(sharedVid));
                    inputs.put("model_path", modelPath);
                    inputs.put("text_prompt", textPrompt);
                    inputs.put("max_frame_number", maxFrameNumber);
                    inputs.put("confidence_threshold", confidenceThreshold);

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
                                System.out.println("Task completed successfully");
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

                                NDArray output_boxes = (NDArray) info.get("boxes");
                                NDArray output_masks = (NDArray) info.get("masks");
                                NDArray output_scores = (NDArray) info.get("scores");
                                NDArray output_ids = (NDArray) info.get("object_ids");

                                if (output_boxes == null || output_masks == null || output_scores == null || output_ids == null) {
                                    IJ.log("Missing output arrays (boxes, masks, scores or ids) from Python.");
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

                                // 5.4 Extract object ids
                                int[] idsArray = new int[numResults];
                                IntBuffer buf_ids = output_ids.buffer().asIntBuffer();
                                buf_ids.rewind();
                                buf_ids.get(idsArray);

                                // 5.5 convert results to DetectedObjects
                                List<String> classNames = new ArrayList<>();
                                List<Double> probabilities = new ArrayList<>();
                                List<BoundingBox> boundingBoxes = new ArrayList<>();

                                for (int i = 0; i < numResults; i++) {
                                    classNames.add(textPrompt);
                                    double[] coord = boxesArray[i];
                                    MaskByte mask = new MaskByte(coord[0], coord[1], coord[2], coord[3], masksArray[i], true);
                                    boundingBoxes.add(mask);
                                    probabilities.add(probaArray[i]);
                                }

                                DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
                                //IJ.log(" --- Prediction received for frame "+ nFrames +"- total number of detection= " + numResults);

                                // --- 6. process detections ---
                                // 6.1 create Roi from bounding boxes and masks
                                List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectedObjects, classIdMap);
                                if (processedDetections.isEmpty()) {
                                    IJ.log(" --- No valid detections were processed for frame " + frameIdx);
                                    return;
                                }

                                // 6.2 aggregate detection by id and by frame
                                for (int i = 0; i < numResults; i++) {
                                   mfdManager.registerDetection(frameIdx, processedDetections.get(i), idsArray[i]);
                                }
                            }

                            // once all results have been processed (and end_signal received)
                            // --- 7. generate Outputs, based on user choices
                            IJ.log(" --- Generating outputs... ");
                            generate3dOutputs(imp, mfdManager, outputOptions);

                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            IJ.log("Consumer thread stopped.");
                        } finally {
                            long endTime = System.nanoTime();
                            double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
                            IJ.log(" --- SAM3 segmentation complete. Total time= "+ totalTimeInSeconds + " sec ---");
                        }
                    }).start();

                    // execute task
                    IJ.log("Executing python script...");
                    task.waitFor();

                } catch (InterruptedException | TaskException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (BuildException e) {
            throw new RuntimeException(e);
        }
    }

    private void askUser() {
        GenericDialog gd = new GenericDialog("SAM3 segmentation with text prompt");

        // instructions to download model
        ActionListener modelInstructionAction = e -> {
            Sam3Dialogs.addDownloadInstructionHg();
        };
        gd.addMessage("");
        gd.addButton("instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // ask for model folder + threshold
        Sam3Dialogs.addModelDirDialogHg(gd, PREF_LAST_MODEL_KEY);
        Sam3Dialogs.addThresholdDialog(gd);

        // ask for text prompt and max frame
        gd.addMessage("__________");
        Sam3Dialogs.addTextPromptDialog(gd);
        Sam3Dialogs.addMaxFrameDialog(gd);

        // ask for SAM outputs
        gd.addMessage("__________");
        Sam3Dialogs.addOutputDialogVideo(gd);

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        modelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_MODEL_KEY);
        confidenceThreshold = Sam3Dialogs.getThreshold(gd);
        classIdMap = Sam3Dialogs.getTextPrompt(gd);
        maxFrameNumber = Sam3Dialogs.getMaxFrame(gd, nFrames);
        outputOptions = Sam3Dialogs.getOutputAnswerVideo(gd);
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

        String prompt = Macro.getValue(options, "text_prompt", null);
        if (prompt == null ||prompt.trim().isEmpty()) {
            IJ.log("Prompt empty. Closing plug-in.\n");
            IJ.error("Please enter a valid prompt.");
            return ;
        }

        maxFrameNumber = Integer.parseInt(Macro.getValue(options, "number_of_frame", "-1"));
        if (maxFrameNumber < 0) maxFrameNumber = nFrames;

        int groupId = Integer.parseInt(Macro.getValue(options, "group_id", "1"));
        if (groupId <1 || groupId >255) groupId = 1;

        classIdMap = new LinkedHashMap<>();
        classIdMap.put(prompt.trim(), groupId);

        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false"));
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = false;
        outputOptions.createStackMask = false;
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
        outputOptions.createSemanticMask = false;
        outputOptions.createInstanceMaskPerClass = false;
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 Video segmentation");
        Recorder.recordOption("model_path", modelPath);
        Recorder.recordOption("confidence", String.valueOf(confidenceThreshold));
        Recorder.recordOption("number_of_frame", String.valueOf(maxFrameNumber));
        Recorder.recordOption("text_prompt", textPrompt);
        Recorder.recordOption("group_id", String.valueOf(classIdMap.get(textPrompt)));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_bounding_boxes", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes)Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
    }

    private void printParameters() {
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model: " + modelPath);
        IJ.log("confidence threshold: " + confidenceThreshold);
        IJ.log("number of frames to process: " + maxFrameNumber);
        IJ.log("text prompt: " + textPrompt.toUpperCase());
        IJ.log("----------------------");
    }
}