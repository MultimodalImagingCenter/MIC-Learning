package fr.curie.miclearning.plugin.sam;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.BoundingBox;
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
            IJ.error("at least one roi is required to run a sam segmentation");
            return;
        }

        // --- 1. initial dialog box ---
        GenericDialog gd = new GenericDialog("Model Directory + Segmentation Outputs");
        // Prompt user for model repository + config info
        addInitialDialogFields(gd, PREF_LAST_MODEL_KEY);
        gd.addMessage("__________");
        // ask for SAM outputs
        SamDialogs.addOutputDialog(gd);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd, PREF_LAST_MODEL_KEY );
        DetectionUtils.OutputOptions segmentOptions = SamDialogs.getOutputAnswer(gd);
        if (initialChoice == null){
            IJ.error("initial choice error", "initial choice is null");
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

            List<String> classNames = new ArrayList<>();
            List<Double> probabilities = new ArrayList<>();
            List<BoundingBox> boundingBoxes = new ArrayList<>();

            Map<String, Integer> classIdMap = new HashMap<>();

            // create a Session Manager for the features
            try (NDManager sessionManager = model.getNDManager().newSubManager()) {

                IJ.log("Encoding image...");
                ImpSam2Translator translator = (ImpSam2Translator) model.getTranslator();
                NDList encodedImage = translator.encode(model, imp, sessionManager);
                IJ.log("image encoded");


                for (Roi roi : roiList){
                    // one input per box/point/group of point
                    ImpSam2Translator.ImpSam2Input.Builder builder =
                            ImpSam2Translator.ImpSam2Input.builder(imp);

                    if (roi.getType() == 10){ // if point (=10)
                        Point[] points = roi.getContainedPoints(); // get list of points in Roi

                        // add every point of the group to the input
                        for (Point point : points){
                            int x = point.x;
                            int y = point.y;
                            builder.addPoint(x,y);
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

                    ImpSam2Translator.ImpSam2Input input = builder.build();

                    // make prediction
                    input.setFeatures(encodedImage);
                    DetectedObjects detection = predictor.predict(input);

                    // add info to DetectedObjects lists
                    DetectedObjects.DetectedObject item = detection.item(0);
                    boundingBoxes.add(item.getBoundingBox());
                    probabilities.add(item.getProbability());

                    int group = roi.getGroup();
                    String groupName = String.valueOf(group);
                    classNames.add(groupName); // pour l'instant, le nom de la classe est juste l'id du groupe
                    classIdMap.putIfAbsent(groupName, group);
                }

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

                // display results
                // process detection
                List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detections, classIdMap);
                if (processedDetections.isEmpty()) {
                    IJ.log(" --- No valid detections were processed.");
                }

                // --- 6. Generate Outputs, based on user choices
                IJ.log(" --- Generating output... ");
                generateOutputs(imp, processedDetections, segmentOptions, classIdMap);


                IJ.log(" --- SAM detection complete.");

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

    }
}
