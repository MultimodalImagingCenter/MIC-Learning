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
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import net.imglib2.appose.NDArrays;
import net.imglib2.appose.ShmImg;
import org.apposed.appose.*;

import java.awt.*;
import java.awt.event.ActionListener;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.List;

import static fr.curie.miclearning.apposeplugin.Sam3Dialogs.*;
import static fr.curie.miclearning.tools.appose.ApposeUtils.getResourceAsString;
import static fr.curie.miclearning.tools.appose.ApposeUtils.imp2ShmImg;
import static fr.curie.miclearning.tools.detection.DetectionUtils.createReverseClassIdMap;
import static fr.curie.miclearning.tools.detection.DetectionUtils.generateOutputs;
import static ij.plugin.frame.RoiManager.getRoiManager;
@Deprecated
public class Sam3BoxDetectionHg_Plugin implements PlugIn {
    protected static ImagePlus imp;

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private double confidenceThreshold;

    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer <256)

    private Set<Integer> uniqueGroups; // list of Roi group ID in manager
    private int groupNumber; // Number of ROI groups in manager
    private int roiNumber; // Number of selected rectangle ROIs

    private String[] negativeGroupSelection; // list of ROI to display in the genericDialog for the user to chose
    private boolean onlyPositiveGroups;
    private int negativeGroup; // id of the negative group selected (if any)
    private Map<Integer, List<double[]>> positiveRois; // list of Roi to be used as positive prompts, grouped by group ID
    private List<double[]> negativeRois; // list of Roi to be used as negative prompts

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3.hg";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image-hg.toml";
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image-boxprompt-hg.py";

    @Override
    public void run(String s) {
        // --- 1. get image, Rois and groups ---

        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        // 1.2 get selected ROIs list
        RoiManager roiManager = getRoiManager();
        Roi[] roiList = roiManager.getSelectedRoisAsArray();
        if (roiList.length == 0) {
            IJ.error("at least one roi in the ROI manager is required to run a sam3 detection with box prompt");
            return;
        }

        // 1.3 link Roi ID to names
        // get ROI groups IDs list
        uniqueGroups = new TreeSet<>();
        List<Roi> boxRoiList = new ArrayList<>();
        for (Roi roi : roiList) {
            // check that Roi is a box
            if (roi.getType() == 0 ) {
                if (roi.getGroup() == 0) {
                    roi.setGroup(MAX_GROUP_VALUE);
                }
                boxRoiList.add(roi);
                uniqueGroups.add(roi.getGroup());
            } else {
                //IJ.log("Only box Roi are used as prompts. Roi " + roi.getName() + " will be ignored");
            }
        }
        groupNumber = uniqueGroups.size();
        roiNumber = boxRoiList.size();

        if (groupNumber == 0){
            IJ.error("No box Roi were found");
            return;
        }

        // create id-name map list + prepare generic dialog
        classIdMap = new HashMap<>(); // list to map class name (string) to roi group id (must be integer)
        // each group will create one detection

        // create list of group that will be displayed in generic dialog
        negativeGroupSelection = new String[uniqueGroups.size() + 1]; // names that will be displayed in generic dialog
        negativeGroupSelection[0] = ONLY_POSITIVE_TXT;
        int j = 1;
        for (int groupId :  uniqueGroups) {
            if (groupId == 0 || groupId == MAX_GROUP_VALUE) {
                negativeGroupSelection[j] = GROUP_ZERO_TXT;
                groupId = MAX_GROUP_VALUE; // ROI group can't be 0 (0 means no group, won't be displayed in masks)
            } else {
                negativeGroupSelection[j] = String.valueOf(groupId);
            }
            //String name = Roi.getGroupName(groupId);
            String name = String.valueOf(groupId);
            // TODO : search for group name in ROI setting, if no group name, find class name in ROI name
            classIdMap.put(name, groupId);
            j++;
        }

        // --- 2. retrieve parameters (model path, negative group, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || classIdMap.isEmpty() || modelPath == null) {
            return;
        }

        Map<Integer, String> idClassMap = createReverseClassIdMap(classIdMap);
        if (idClassMap == null ) return;

        long startTime = System.nanoTime();

        // 2.2 Prepare input - Sort positive and negative ROIs
        positiveRois = new HashMap<>();
        negativeRois = new ArrayList<>();

        for (Roi roi : boxRoiList) {
            int groupId = roi.getGroup() == 0 ? MAX_GROUP_VALUE : roi.getGroup();
            Rectangle rect = roi.getBounds();
            double[] box = {rect.x, rect.y, rect.width, rect.height};
            if (!onlyPositiveGroups && groupId == negativeGroup) {
                negativeRois.add(box);
            } else {
                positiveRois.computeIfAbsent(groupId, k -> new ArrayList<>()).add(box);
            }
        }

        if (positiveRois.isEmpty()) {
            IJ.error("No positive ROIs found to process.");
            return;
        }

        recordInMacro();
        IJ.log("\n   --- Starting SAM3 detection on 1 image - with box prompts --- ");
        printParameters();

        // --- 3. load script and create env ---
        // 3.1 load python script
        String script = getResourceAsString(SCRIPT_PATH);
        if (script == null) {
            IJ.error("Unable to load script");
            return;
        }

        IJ.log("Python script loaded");

        // 3.2 create environment
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

            // --- 4. prediction ---

            // 4.1 copy image in shared memory
            try (ShmImg<?> sharedImg = imp2ShmImg(imp)) {
                //IJ.log("Image copied to shared memory");

                // 4.2 create python service in env
                try (Service python = env.python()) {

                    // 4.3 store image and prompt into a map of inputs to the Python script.
                    final Map<String, Object> inputs = new HashMap<>();

                    inputs.put("image_input", NDArrays.asNDArray(sharedImg));
                    inputs.put("model_path", modelPath);
                    inputs.put("confidence_threshold", confidenceThreshold);
                    inputs.put("positive_rois", positiveRois);
                    inputs.put("negative_rois", negativeRois);

                    // 4.4 Execute script by launching task
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

                    // --- 5. get results ---
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

                    // 5.2 extract proba, scores and masks as ND array
                    NDArray output_boxes = (NDArray) task.outputs.get("boxes");
                    NDArray output_masks = (NDArray) task.outputs.get("masks");
                    NDArray output_scores = (NDArray) task.outputs.get("scores");
                    NDArray output_group = (NDArray) task.outputs.get("group_ids");

                    if (output_boxes == null || output_masks == null || output_scores == null || output_group == null) {
                        IJ.error("Missing output arrays (boxes, masks, scores or ids) from Python.");
                        return;
                    }

                    // --- 6. process results --
                    // 6.1 extract bounding boxes
                    // Initialize the 2D array [Number of Boxes][4 Coordinates]
                    double[][] boxesArray = new double[numResults][4];
                    // extract bounding boxes coordinates
                    DoubleBuffer buf_boxes = output_boxes.buffer().asDoubleBuffer();
                    buf_boxes.rewind();
                    for (int i = 0; i < numResults; i++) {
                        // This reads 4 doubles at a time and moves the pointer forward
                        buf_boxes.get(boxesArray[i]);
                    }


                    // 6.2 Extract masks
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
                    ByteBuffer buf_masks = output_masks.buffer();
                    buf_masks.rewind();
                    for (int i = 0; i < numResults; i++) {
                        for (int y = 0; y < height; y++) {

                            buf_masks.get(masksArray[i][y]);
                        }
                    }

                    // 6.3 Extract probabilities
                    double[] probaArray = new double[numResults];
                    DoubleBuffer buf_scores = output_scores.buffer().asDoubleBuffer();
                    buf_scores.rewind();
                    buf_scores.get(probaArray);

                    // 6.4 Extract group ids
                    int[] groupIds = new int[numResults];
                    IntBuffer buf_ids = output_group.buffer().asIntBuffer();
                    buf_ids.rewind();
                    buf_ids.get(groupIds);

                    // 6.5 convert results to DetectedObjects
                    List<String> classNames = new ArrayList<>();
                    List<Double> probabilities = new ArrayList<>();
                    List<BoundingBox> boundingBoxes = new ArrayList<>();

                    for (int i = 0; i < numResults; i++) {
                        int groupId = groupIds[i];
                        classNames.add(idClassMap.get(groupId));
                        double[] coord = boxesArray[i];
                        MaskByte mask = new MaskByte(coord[0], coord[1], coord[2], coord[3], masksArray[i], true);
                        boundingBoxes.add(mask);
                        probabilities.add(probaArray[i]);
                    }

                    DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
                    IJ.log(" --- Prediction done - total number of detection= " + numResults);

                    // --- 7. process detections ---
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


        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false"));
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = false;
        outputOptions.createStackMask = Boolean.parseBoolean(Macro.getValue(options, "create_stack_mask", "false"));
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false"));
        outputOptions.createInstanceMaskPerClass = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false"));

        negativeGroup = Integer.parseInt(Macro.getValue(options, "negative_group_id", "-1"));
        onlyPositiveGroups = negativeGroup == -1;
        if (!uniqueGroups.contains(negativeGroup)) {
            IJ.log("Warning: negative group id is not in list of selected roi.");
            onlyPositiveGroups = true;
        }
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 Detection with Box Prompts");
        Recorder.recordOption("model_path", modelPath);
        Recorder.recordOption("confidence", String.valueOf(confidenceThreshold));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_bounding_boxes", String.valueOf(true));
        if (outputOptions.addToRoiManagerShapes)Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.createStackMask) Recorder.recordOption("create_stack_mask", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask", String.valueOf(true));
        if (outputOptions.createInstanceMaskPerClass) Recorder.recordOption("create_instance_mask_per_class", String.valueOf(true));

        if (!onlyPositiveGroups){
            Recorder.recordOption("negative_group_id", String.valueOf(negativeGroup));
        }
    }

    private void askUser() {
        GenericDialog gd = new GenericDialog("SAM3 detection with box prompts");
        if (!imp.isRGB()) gd.addMessage("Warning : Image is not RGB. This model works better with RGB images.");

        ActionListener modelInstructionAction = e -> {
            Sam3Dialogs.addDownloadInstructionHg();
        };

        gd.addButton("instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // ask for model path + threshold
        Sam3Dialogs.addModelDirDialogHg(gd, PREF_LAST_MODEL_KEY);
        Sam3Dialogs.addThresholdDialog(gd);

        // prompts
        gd.addMessage("__________");
        Sam3Dialogs.addBoxGroupInstructions(gd, roiNumber, groupNumber);
        //if multiple ROI groups, ask if one of them corresponds to negative prompts
        Sam3Dialogs.addNegativeGroupDialog(gd, groupNumber, negativeGroupSelection);

        // ask for SAM outputs
        gd.addMessage("__________");
        Sam3Dialogs.addOutputDialogImage(gd);


        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        modelPath = Sam3Dialogs.getModelPath(gd, PREF_LAST_MODEL_KEY);
        confidenceThreshold = Sam3Dialogs.getThreshold(gd);
        negativeGroup = Sam3Dialogs.getNegativeGroup(gd, groupNumber, ONLY_POSITIVE_TXT, GROUP_ZERO_TXT);
        outputOptions = Sam3Dialogs.getOutputAnswerImage(gd);

        negativeGroup = negativeGroup == 0 ? MAX_GROUP_VALUE : negativeGroup;
        onlyPositiveGroups = negativeGroup == -1;
    }

    private void printParameters(){
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model folder: " + modelPath);
        IJ.log("confidence threshold: " + confidenceThreshold);

        int total_prompts = positiveRois.values().stream()
                .flatMap(List::stream)
                .mapToInt(array -> array.length)
                .sum()/4;
        IJ.log("number of positive prompt(s): " + total_prompts + " - number of group(s): "+ positiveRois.size());
        IJ.log("number of negative prompt(s): " + negativeRois.size());
        IJ.log("----------------------");
    }

}
