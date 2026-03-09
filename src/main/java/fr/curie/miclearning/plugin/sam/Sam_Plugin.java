package fr.curie.miclearning.plugin.sam;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.ZooModel;
import fr.curie.miclearning.prediction.model.DjlModelLoader;
import fr.curie.miclearning.prediction.model.ModelConfig;
import fr.curie.miclearning.prediction.model.ModelDialogs;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.tools.ImageJUtils;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import ij.Macro;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import ij.plugin.frame.Recorder;
import ij.plugin.frame.RoiManager;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

import static fr.curie.miclearning.prediction.model.ModelConfigManager.saveConfigToFile;
import static fr.curie.miclearning.prediction.model.ModelDialogs.addInitialDialogFields;
import static fr.curie.miclearning.tools.detection.DetectionUtils.generateOutputs;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class Sam_Plugin implements PlugIn, DialogListener {

    protected static ImagePlus imp;
    private int groupNumber; // Number of ROI groups

    // parameters
    private String[] negativeGroupSelection; // list of group to display to select a negative group
    private ModelDialogs.InitialChoice initialChoice;
    private DetectionUtils.OutputOptions outputOptions;
    private int negativeGroup;
    private boolean onlyPositiveGroups;

    // dialog components
    Checkbox addToRoiManagerCheckbox;
    Checkbox resetPreviousRoi;

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam";
    private final int MAX_GROUP_VALUE = 255;
    private final String ONLY_POSITIVE_TXT = "no negative group";
    private final String GROUP_ZERO_TXT = "0 (ROI without group)";

    // List of configurators available
    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;
    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("SAM2 segmentation", new Sam2Configurator());
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }
    private final String[] ENGINE_CHOICES = {"", "PyTorch"};

    @Override
    public void run(String s) {
        // --- 1. set up ---
        // 1.1 get selected image
        imp = IJ.getImage(); // select active image
        // TODO : check that image has correct format
        if (imp == null) {
            IJ.error("no image opened");
            return;
        }

        // 1.2 get ROIs list
        RoiManager roiManager = getRoiManager();
        Roi[] roiList = roiManager.getSelectedRoisAsArray();
        if (roiList.length == 0) {
            IJ.error("at least one roi in the ROI manager is required to run a sam segmentation");
            return;
        }

        // 1.3 link Roi ID to names
        // get ROI groups IDs list
        Set<Integer> uniqueGroups = new TreeSet<>();
        for (Roi roi : roiList) {
            uniqueGroups.add(roi.getGroup());
        }
        groupNumber = uniqueGroups.size();

        // create id-name map list + prepare generic dialog
        Map<String, Integer> classIdMap = new HashMap<>(); // list to map class name (string) to roi group id (must be integer)
        //usefull if roi result from a previous yolo/detr detection

        // create list of group that will be displayed
        negativeGroupSelection = new String[uniqueGroups.size() + 1]; // names that will be displayed in generic dialog
        negativeGroupSelection[0] = ONLY_POSITIVE_TXT;
        int i = 1;
        for (int groupId :  uniqueGroups) {
            if (groupId == 0) {
                negativeGroupSelection[i] = GROUP_ZERO_TXT;
                groupId = MAX_GROUP_VALUE; // ROI group can't be 0 (0 means no group, won't be displayed in masks)
            } else {
                negativeGroupSelection[i] = String.valueOf(groupId);
            }
            //String name = Roi.getGroupName(groupId);
            String name = String.valueOf(groupId);
            // TODO : search for group name in ROI setting, if no group name, find class name in ROI name
            classIdMap.put(name, groupId);
            i++;
        }

        // -- 2. retrieve parameters --
        if (Macro.getOptions() != null) {
            //IJ.log("macro options");
            parseMacro();
        } else {
            askUser();
        }

        if (initialChoice == null){
            //IJ.log("No InitialChoice was created");
            return;
        }

        // check that model path is valid
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }

        Path modelPath = initialChoice.modelPath;

        recordInMacro();

        // -- 3. Try to Load Model ---
        IJ.log("--- Starting SAM prediction");
        DjlModelLoader<ImpSam2Translator.ImpSam2Input, DetectedObjects> modelLoader =
                new DjlModelLoader<>(ImpSam2Translator.ImpSam2Input.class, DetectedObjects.class, KNOWN_CONFIGURATORS, ENGINE_CHOICES);
        DjlModelLoader.LoadedModel<ImpSam2Translator.ImpSam2Input, DetectedObjects> loadedResult = modelLoader.loadModel(modelPath, initialChoice);

        if (loadedResult.isFail()) {
            if (loadedResult.isCancelled()) {
                IJ.log(" --- Model loading cancelled.");
            } else {
                IJ.log(" --- Model loading failed.");
                IJ.error("Model loading failed.");
            }
            return;
        }

        // --- 4. Get model + config ---
        try (ZooModel<ImpSam2Translator.ImpSam2Input, DetectedObjects> model = loadedResult.getModel();
             Predictor<ImpSam2Translator.ImpSam2Input, DetectedObjects> predictor = model.newPredictor()) {
            ModelConfig modelConfig = loadedResult.getConfig();

            // --- 5. Prepare prediction ---
            // 5.1 Identify all positive and negative ROIs
            List<Roi> positiveRois = new ArrayList<>();
            List<Roi> negativeRois = new ArrayList<>();

            for (Roi roi : roiList) {
                int currentGroup = roi.getGroup();
                if (!onlyPositiveGroups && currentGroup == negativeGroup) {
                    if (roi.getType() == 10) { // negative input can only be points
                        negativeRois.add(roi);
                    } else {
                        IJ.log("Negative ROI can only be points : ROI " + roi.getName() + " won't be used.");
                    }
                } else {
                    positiveRois.add(roi);
                }
            }

            if (positiveRois.isEmpty()) {
                IJ.error("No positive ROIs found to process.");
                return;
            }

            // 5.2  prepare results list
            List<String> classNames = new ArrayList<>();
            List<Double> probabilities = new ArrayList<>();
            List<BoundingBox> boundingBoxes = new ArrayList<>();

            // create a Session Manager for the image features
            try (NDManager sessionManager = model.getNDManager().newSubManager()) {

                // 5.3 encode image
                IJ.log("Encoding image...");
                ImpSam2Translator translator = (ImpSam2Translator) model.getTranslator();
                NDList encodedImage = translator.encode(model, imp, sessionManager);
                IJ.log("image encoded");

                // --- 6. for each object, 1 prediction ---
                for (Roi roi : positiveRois){
                    // create input
                    // one input per box/point/group of point
                    ImpSam2Translator.ImpSam2Input.Builder builder =
                            ImpSam2Translator.ImpSam2Input.builder(imp);

                    // Add the current Positive Prompt
                    addRoiToBuilder(builder, roi, true);

                    // Add all negative ROIs
                    for (Roi negRoi : negativeRois) {
                        addRoiToBuilder(builder, negRoi, false);
                    }

                    ImpSam2Translator.ImpSam2Input input = builder.build();

                    // make prediction
                    input.setFeatures(encodedImage);
                    DetectedObjects detection = predictor.predict(input);

                    // add info to DetectedObjects lists
                    DetectedObjects.DetectedObject item = detection.item(0);
                    boundingBoxes.add(item.getBoundingBox());
                    probabilities.add(item.getProbability());

                    int group = roi.getGroup();
                    if (group == 0){
                        group = MAX_GROUP_VALUE; // ROI group can't be 0
                    }

                    String groupName = String.valueOf(group); // pour l'instant, le nom de la classe est juste l'id du groupe

                    classNames.add(groupName);
                }

                // --- 6. Gather detections ---
                // create list of detected objects
                DetectedObjects detections = new DetectedObjects(classNames, probabilities, boundingBoxes);

                IJ.log(" --- Prediction done");
                // Save configuration to config.properties if needed
                if (loadedResult.needToRewriteServing()) {
                    try {
                        Path newPropertiesFilePath = loadedResult.getNewPropertiesFilePath();
                        saveConfigToFile(modelConfig, newPropertiesFilePath);
                        IJ.log("Saved new configuration.");
                    } catch (IOException e) {
                        IJ.log("Warning: Failed to save configuration. Error: " + e.getMessage());
                    }
                }

                // --- 7. process detections ---
                List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detections, classIdMap);
                if (processedDetections.isEmpty()) {
                    IJ.log(" --- No valid detections were processed.");
                }

                // --- 8. generate Outputs, based on user choices
                IJ.log(" --- Generating output... ");
                if (outputOptions.deletePreviousRoi) ImageJUtils.deleteRois(roiList, roiManager);
                generateOutputs(imp, processedDetections, outputOptions, classIdMap);


                IJ.log(" --- SAM detection complete.");

            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }
    }


    private void parseMacro() {
        IJ.log("\nSAM segmentation on macro");
        String options = Macro.getOptions();

        String dirPath = Macro.getValue(options, "model_directory", null);
        if (dirPath == null){
            IJ.error("No model directory specified.");
            return;
        }
        String propsFileName = Macro.getValue(options, "properties_file_name", "serving.properties");
        boolean forceManual = false;

        Path modelPath = Paths.get(dirPath);

        initialChoice = new ModelDialogs.InitialChoice(modelPath, propsFileName, forceManual);

        outputOptions = new DetectionUtils.OutputOptions();
        outputOptions.addToRoiManagerBB = false; // bounding boxes are not an output of SAM
        outputOptions.addToRoiManagerShapes = Boolean.parseBoolean(Macro.getValue(options, "add_shape_rois", "false"));
        outputOptions.deletePreviousRoi = Boolean.parseBoolean(Macro.getValue(options, "replace_roi", "false"));
        outputOptions.createStackMask = Boolean.parseBoolean(Macro.getValue(options, "create_stack_mask", "false"));
        outputOptions.createInstanceMask = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask", "false"));
        outputOptions.createSemanticMask = Boolean.parseBoolean(Macro.getValue(options, "create_semantic_mask", "false"));
        outputOptions.createInstanceMaskPerClass = Boolean.parseBoolean(Macro.getValue(options, "create_instance_mask_per_class", "false"));

        negativeGroup = Integer.parseInt(Macro.getValue(options, "negative_group_id", "-1"));
        onlyPositiveGroups = negativeGroup == -1;
    }

    private void recordInMacro() {
        Recorder.setCommand("SAM segmentation");
        Recorder.recordOption("model_directory", String.valueOf(initialChoice.modelPath));
        Recorder.recordOption("properties_file_name", String.valueOf(initialChoice.propertiesFileName));
        if (outputOptions.addToRoiManagerShapes)Recorder.recordOption("add_shape_rois", String.valueOf(true));
        if (outputOptions.deletePreviousRoi) Recorder.recordOption("replace_roi", String.valueOf(true));
        if (outputOptions.createStackMask) Recorder.recordOption("create_stack_mask", String.valueOf(true));
        if (outputOptions.createInstanceMask) Recorder.recordOption("create_instance_mask", String.valueOf(true));
        if (outputOptions.createSemanticMask) Recorder.recordOption("create_semantic_mask", String.valueOf(true));
        if (outputOptions.createInstanceMaskPerClass) Recorder.recordOption("create_instance_mask_per_class", String.valueOf(true));

        if (!onlyPositiveGroups){
            Recorder.recordOption("negative_group_id", String.valueOf(negativeGroup));
        }
    }

    private void askUser() {
        GenericDialog gd = new GenericDialog("Model Directory + Segmentation Outputs");
        // Prompt user for model repository + config info
        addInitialDialogFields(gd, PREF_LAST_MODEL_KEY);

        // ask for SAM outputs
        gd.addMessage("__________");
        SamDialogs.addOutputDialog(gd);

        //if multiple ROI groups, ask if one of them corresponds to negative prompts
        SamDialogs.addNegativeGroupDialog(gd, groupNumber, negativeGroupSelection);

        gd.addDialogListener(this);

        Vector<?> checkboxesVector = gd.getCheckboxes();
        if (checkboxesVector != null && checkboxesVector.size() > 1) {
            addToRoiManagerCheckbox = (Checkbox) checkboxesVector.get(1);
            resetPreviousRoi = (Checkbox) checkboxesVector.get(2);
        }

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        initialChoice = ModelDialogs.getInitialChoice(gd, PREF_LAST_MODEL_KEY );
        outputOptions = SamDialogs.getOutputAnswer(gd);

        negativeGroup = SamDialogs.getNegativeGroup(gd, groupNumber, ONLY_POSITIVE_TXT, GROUP_ZERO_TXT);
        onlyPositiveGroups = negativeGroup == -1;

        IJ.log("\n");
    }
    @Override
    public boolean dialogItemChanged(GenericDialog genericDialog, AWTEvent e) {
        if (e == null || addToRoiManagerCheckbox == null || resetPreviousRoi == null) {
            return true;
        }
        Object source = e.getSource();
        if (source == addToRoiManagerCheckbox) {
            boolean state = addToRoiManagerCheckbox.getState();
            updateResetRoiBoxVisibility(state);
            return true;
        }
        return true;
    }

    private void updateResetRoiBoxVisibility(boolean roiAddedState){
        if ( addToRoiManagerCheckbox == null || resetPreviousRoi == null) return;
        if (!roiAddedState) resetPreviousRoi.setState(false);
        resetPreviousRoi.setEnabled(roiAddedState);
    }

    /**
     * Add ROI to the SAM builder, depending on ROI type
     * @param isPositive true for positive prompt, false for negative prompt.
     */
    private void addRoiToBuilder(ImpSam2Translator.ImpSam2Input.Builder builder, Roi roi, boolean isPositive) {
        int label = isPositive ? 1 : 0;
        if (roi.getType() == Roi.POINT) { // if point (or list of points)
            Point[] points = roi.getContainedPoints();  // get list of points in Roi
            // add every point of the group to the input
            for (Point point : points){
                int x = point.x;
                int y = point.y;
                builder.addPoint(x,y, label);
            }
        } else { // for polygon roi
            //get coordinates
            int x = (int) roi.getBounds().getX();
            int y = (int) roi.getBounds().getY();
            int right = x + (int) roi.getFloatWidth();
            int bottom = y + (int) roi.getFloatHeight();

            // add box to input
            builder.addBox(x, y, right, bottom);
        }
    }
}
