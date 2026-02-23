package fr.curie.miclearning.plugin.sam;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.ImageJUtils;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.ZooModel;
import fr.curie.miclearning.prediction.model.DjlModelLoader;
import fr.curie.miclearning.prediction.model.ModelConfig;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelDialogs;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;

import static fr.curie.miclearning.prediction.model.ModelConfigManager.saveConfigToFile;
import static fr.curie.miclearning.prediction.model.ModelDialogs.addInitialDialogFields;
import static fr.curie.miclearning.tools.detection.DetectionUtils.generateOutputs;
import static ij.plugin.frame.RoiManager.getRoiManager;

public class Sam_Plugin implements PlugInFilter {
    protected static ImagePlus imp;
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
    public int setup(String s, ImagePlus imagePlus) {
        imp = imagePlus;
        return DOES_RGB + DOES_8G;
    }

    @Override
    public void run(ImageProcessor imageProcessor) {
        // get ROIs list
        RoiManager roiManager = getRoiManager();
        Roi[] roiList = roiManager.getSelectedRoisAsArray();
        if (roiList.length == 0) {
            IJ.error("at least one roi in the ROI manager is required to run a sam segmentation");
            return;
        }
        // get ROI groups IDs list
        Set<Integer> uniqueGroups = new TreeSet<>();
        for (Roi roi : roiList) {
            uniqueGroups.add(roi.getGroup());
        }

        int groupNumber = uniqueGroups.size();

        // create id-name map list + prepare generic dialog
        Map<String, Integer> classIdMap = new HashMap<>(); // list to map class name (string) to roi group id (must be integer)
        //usefull if roi result from a previous yolo/detr detection

        String[] negativeGroupSelection = new String[uniqueGroups.size() + 1]; // names that will be displayed in generic dialog
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


        // --- 1. initial dialog box ---
        GenericDialog gd = new GenericDialog("Model Directory + Segmentation Outputs");
        // Prompt user for model repository + config info
        addInitialDialogFields(gd, PREF_LAST_MODEL_KEY);

        // ask for SAM outputs
        gd.addMessage("__________");
        SamDialogs.addOutputDialog(gd);

        //ask if result tables and rois need to be reset
        gd.addMessage("__________");
        ModelDialogs.askIfResetResult(gd);

        //if multiple ROI groups, ask if one of them corresponds to negative prompts
        SamDialogs.addNegativeGroupDialog(gd, groupNumber, negativeGroupSelection);

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd, PREF_LAST_MODEL_KEY );
        DetectionUtils.OutputOptions segmentOptions = SamDialogs.getOutputAnswer(gd);
        boolean resetPreviousResults = ModelDialogs.getIfResetResult(gd);

        int negativeGroup = SamDialogs.getNegativeGroup(gd, groupNumber, ONLY_POSITIVE_TXT, GROUP_ZERO_TXT);
        boolean onlyPositiveGroups = negativeGroup == -1;

        if (initialChoice == null){
            IJ.error("Error with initial dialog", "No InitialChoice was created");
            return;
        }

        // check that model path is valid
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }
        Path modelPath = initialChoice.modelPath;



        // --- 2. Try to Load Model ---
        IJ.log("\n --- Starting SAM prediction");
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

        // --- 3. Get model + config ---
        try (ZooModel<ImpSam2Translator.ImpSam2Input, DetectedObjects> model = loadedResult.getModel();
             Predictor<ImpSam2Translator.ImpSam2Input, DetectedObjects> predictor = model.newPredictor()) {
            ModelConfig modelConfig = loadedResult.getConfig();

            // --- 4. Prepare prediction ---
            // Identify all positive and negative ROIs
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

            // prepare results list
            List<String> classNames = new ArrayList<>();
            List<Double> probabilities = new ArrayList<>();
            List<BoundingBox> boundingBoxes = new ArrayList<>();

            // create a Session Manager for the image features
            try (NDManager sessionManager = model.getNDManager().newSubManager()) {

                // encode image
                IJ.log("Encoding image...");
                ImpSam2Translator translator = (ImpSam2Translator) model.getTranslator();
                NDList encodedImage = translator.encode(model, imp, sessionManager);
                IJ.log("image encoded");

                // --- 5. for each object, 1 prediction ---
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
                if (resetPreviousResults) ImageJUtils.resetRMandRT();
                generateOutputs(imp, processedDetections, segmentOptions, classIdMap);


                IJ.log(" --- SAM detection complete.");

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

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
