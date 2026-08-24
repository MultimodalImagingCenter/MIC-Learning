package fr.curie.miclearning.apposeplugin.sam;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.DetectionUtils.DetectionMode;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;
import ij.process.ImageConverter;
import org.apposed.appose.*;

import java.awt.event.ActionListener;
import java.io.IOException;
import java.util.*;
import java.util.List;

import static fr.curie.miclearning.apposeplugin.sam.Sam3Dialogs.*;
import static fr.curie.miclearning.tools.detection.DetectionUtils.createReverseClassIdMap;
import static fr.curie.miclearning.tools.detection.DetectionUtils.generateOutputs;
import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * ImageJ plugin: SAM3 promptable-concept-segmentation over a single image, using
 *  visual prompts (box/point) red from the RoiManager selection.
 */
public class Sam3VisualPromptSingleImgPcs_Plugin implements PlugIn {
    protected static ImagePlus imp;

    private DetectionUtils.OutputOptions outputOptions;
    private String modelPath;
    private Sam3ModelParameters detectionParams;

    private Map<String, Integer> classIdMap; // list to map class name (string) to roi group id (must be integer >0 and <256)

    private Set<Integer> uniqueGroups; // list of Roi group ID in manager
    private int groupNumber; // Number of ROI groups in manager
    private int roiNumber; // Number of selected rectangle ROIs

    private String[] negativeGroupSelection; // list of ROI to display in the genericDialog for the user to choose
    private boolean onlyPositiveGroups;
    private int negativeGroup; // id of the negative group selected (if any)
    private Map<Integer, List<double[]>> positiveRois; // list of Roi to be used as positive prompts, grouped by group ID
    private List<double[]> negativeRois; // list of Roi to be used as negative prompts

    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final String ENV_FILE_PATH = "/fr/curie/miclearning/apposeplugin/sam3m.toml"; // inside the resources folder
    private static final String SCRIPT_PATH = "/fr/curie/miclearning/apposeplugin/sam3-detection-image-geomprompt-m.py"; // inside the resources folder

    @Override
    public void run(String s) {
        // --- 1. get image and geom Rois ---

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

        // 1.2 get selected ROIs list
        RoiManager roiManager = getRoiManager();
        Roi[] selectedRoiList = roiManager.getSelectedRoisAsArray();
        if (selectedRoiList.length == 0) {
            IJ.error("at least one roi in the ROI manager is required to run a sam3 detection with visual prompt(s)");
            return;
        }

        // 1.3 link Roi ID to names
        // get ROI groups IDs list
        uniqueGroups = new TreeSet<>();
        List<Roi> promptRoiList = new ArrayList<>();
        for (Roi roi : selectedRoiList) {
            // check that Roi is a box or point
            if (RoiPromptExtractor.isUsableRoiType(roi)) {
                int effectiveGroupId = RoiPromptExtractor.normalizeGroupZero(roi.getGroup(), MAX_GROUP_VALUE);
                promptRoiList.add(roi);
                uniqueGroups.add(effectiveGroupId);
            }
        }
        groupNumber = uniqueGroups.size();
        roiNumber = promptRoiList.size();

        if (groupNumber == 0){
            IJ.error("No box or point ROIs were found");
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
            String name = Roi.getGroupName(groupId);
            if (name==null) name = String.valueOf(groupId);
            // TODO : if no group name, find class name in ROI name
            classIdMap.put(name, groupId);
            j++;
        }

        // --- 2. retrieve parameters (model path, negative group, output options) ---
        if (Macro.getOptions() != null) {
            parseMacro();
        } else {
            askUser();
        }

        if (outputOptions == null || classIdMap == null || classIdMap.isEmpty() || modelPath == null) {
            return;
        }

        Map<Integer, String> idClassMap = createReverseClassIdMap(classIdMap);
        if (idClassMap == null ) return;

        long startTime = System.nanoTime();

        // 2.2 Prepare input - Sort positive and negative ROIs
        RoiPromptExtractor.GroupedPromptRois groupedPromptRois = roiPromptExtractor.buildGroupedPromptRois(
                promptRoiList, onlyPositiveGroups ? null : negativeGroup, MAX_GROUP_VALUE);
        positiveRois = groupedPromptRois.getPositiveByGroup(); // positive_rois structure: { '1': [[x,y,w,h], [x,y], ...], '2': [[x,y,w,h], [x,y], ...] } (absolute values)
        negativeRois = groupedPromptRois.getNegative(); // negative_rois format: [[x,y,w,h], [x,y], ...] (absolute values)

        if (positiveRois.isEmpty()) {
            IJ.error("No positive ROIs found to process.");
            return;
        }

        recordInMacro();
        IJ.log("\n   --- Starting SAM Promptable Concept Segmentation - on 1 image - with visual prompts ---");
        printParameters();

        Sam3VisualPromptSingleImgRunConfig config;
        try {
            config = new Sam3VisualPromptSingleImgRunConfig.Builder()
                    .modelPath(modelPath)
                    .detectionParams(detectionParams)
                    .outputOptions(outputOptions)
                    .classIdMap(classIdMap)
                    .idClassMap(idClassMap)
                    .positiveRois(positiveRois)
                    .negativeRois(negativeRois)
                    .build();
        } catch (IllegalStateException e) {
            IJ.error("Invalid configuration", e.getMessage());
            return;
        }

        runSam3(config, startTime);
    }

    private void runSam3(Sam3VisualPromptSingleImgRunConfig config, long startTime) {
        try (Sam3VisualPromptSingleImgPythonRunner runner = new Sam3VisualPromptSingleImgPythonRunner(SCRIPT_PATH, ENV_FILE_PATH)) {
            // --- 3. load script and create env ---
            // 3.1 load python script
            // 3.2 create environment
            runner.initialize();

            // --- 4. prediction ---
            IJ.log("Executing python script...");
            Map<String, Object> outputs = runner.runAndGetOutputs(config, imp);

            // --- 5. Process results ---
            List<ProcessedDetection> processedDetections =
                    SingleImagePcsResultParser.parse(outputs, imp, config.getIdClassMap(), config.getClassIdMap());

            if (processedDetections.isEmpty()) {
                IJ.log(" --- No detections were found.");
            } else {
                IJ.log(" --- Prediction done - total number of detections= " + processedDetections.size());
            }

            // --- 7. generate Outputs ---
            IJ.log(" --- Generating output... ");
            generateOutputs(imp, processedDetections, config.getOutputOptions(), config.getClassIdMap());

        } catch (IOException | BuildException e) {
            IJ.error("Unable to prepare Python environment", String.valueOf(e.getMessage()));
            IJ.log("ERROR while preparing python environment: " + e);
        } catch (TaskException e) {
            IJ.error("Python task error", String.valueOf(e.getMessage()));
            IJ.log("ERROR while running python script: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IJ.log("Processing was interrupted.");
        } catch (IllegalStateException e) {
            IJ.error("Error while processing results", e.getMessage());
            IJ.log("ERROR parsing python results: " + e);
        } finally {
            long endTime = System.nanoTime();
            double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
            IJ.log(" --- SAM3 PCS complete. Total time= " + totalTimeInSeconds + " sec ---");
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
        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = Boolean.parseBoolean(Macro.getValue(options, "add_box_rois", "false"));
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = false;
        outputOptions.createStackMask = Boolean.parseBoolean(Macro.getValue(options, "create_stack_mask", "false"));
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false"));
        outputOptions.createInstanceMaskPerClass = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false"));

        // check if negative prompt
        negativeGroup = Integer.parseInt(Macro.getValue(options, "negative_group_id", "-1"));
        onlyPositiveGroups = negativeGroup == -1;
        if (!uniqueGroups.contains(negativeGroup)) {
            IJ.log("Warning: negative group id is not in list of selected roi.");
            onlyPositiveGroups = true;
        }
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM3 PCS with Visual Prompts");
        Recorder.recordOption("model_path", modelPath);

        Recorder.recordOption("confidence", String.valueOf(detectionParams.getConfidenceThreshold()));
        Recorder.recordOption("mask_threshold", String.valueOf(detectionParams.getMaskScoreThreshold()));
        Recorder.recordOption("max_side_length", String.valueOf(detectionParams.getMaxSideLengthDetect()));

        if (outputOptions.addToRoiManagerBB) Recorder.recordOption("add_box_rois", String.valueOf(true));
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
        GenericDialog gd = new GenericDialog("SAM3 Promptable Concept Segmentation with visuals prompts");

        // instructions to download model
        ActionListener modelInstructionAction = e -> {
            addDownloadInstruction();
        };
        gd.addMessage("");
        gd.addButton("instructions to download SAM3 model", modelInstructionAction);
        gd.addMessage("");

        // ask for model folder
        addModelPathDialog(gd, PREF_LAST_MODEL_KEY);

        // prompts
        gd.addMessage("__________");
        addBoxGroupInstructions(gd, roiNumber, groupNumber);
        //if multiple ROI groups, ask if one of them corresponds to negative prompts
        addNegativeGroupDialog(gd, groupNumber, negativeGroupSelection);

        // ask for parameters
        gd.addMessage("__________");
        detectionParams = new Sam3ModelParameters();
        addParameterDialog(gd, detectionParams);

        // ask for SAM outputs
        addOutputDialog(gd, DetectionMode.SINGLE_IMAGE);

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        modelPath = getModelPath(gd, PREF_LAST_MODEL_KEY);
        negativeGroup = getNegativeGroup(gd, groupNumber, ONLY_POSITIVE_TXT, GROUP_ZERO_TXT);
        getParameters(gd, detectionParams);
        outputOptions = getOutputAnswer(gd, DetectionMode.SINGLE_IMAGE);

        negativeGroup = negativeGroup == 0 ? MAX_GROUP_VALUE : negativeGroup;
        onlyPositiveGroups = negativeGroup == -1;
    }

    private void printParameters(){
        IJ.log("----------------------");
        IJ.log("image: " + imp.getTitle());
        IJ.log("model: " + modelPath);
        IJ.log("confidence threshold: " + detectionParams.getConfidenceThreshold());
        IJ.log("mask score threshold: " + detectionParams.getMaskScoreThreshold());

        int total_prompts = positiveRois.values().stream()
                .mapToInt(List::size)
                .sum();
        IJ.log("number of positive prompt(s): " + total_prompts + " - number of group(s): "+ positiveRois.size());
        IJ.log("number of negative prompt(s): " + negativeRois.size());

        IJ.log("----------------------");
    }
}