package fr.curie.yolo;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.modelloading.DjlModelLoader;
import fr.curie.modelloading.ModelConfig;
import fr.curie.modelloading.configurators.TranslatorConfigurator;
import fr.curie.modelloading.configurators.YoloConfigurator;
import fr.curie.modelloading.configurators.YoloSegmentationConfigurator;
import fr.curie.modelloading.dialogs.ModelDialogs;
import fr.curie.tools.SegmentationUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static fr.curie.modelloading.ModelConfigManager.saveConfigToFile;
import static fr.curie.modelloading.dialogs.ModelDialogs.addInitialDialogFields;
import static fr.curie.tools.SegmentationUtils.*;

public class Yolo_Plugin implements PlugInFilter {
    protected static ImagePlus imp;
    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;
    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("Yolo Object Detection", new YoloConfigurator());
        tempMap.put("Yolo Object Detection + Segmentation", new YoloSegmentationConfigurator());
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }
    private final String[] ENGINE_CHOICES = {"", "PyTorch"};

    @Override
    public int setup(String s, ImagePlus imagePlus) {
        imp = imagePlus;
        // For yolo, images has to be greyscale 8bit rgb
        // TODO : if image isn't UNIT8 or RGB, return error and ask user to convert image
        return DOES_8G | DOES_RGB;
    }

    @Override
    public void run(ImageProcessor ip) {

        // --- 1. Prompt user for model repository + Preferences for output ---
        GenericDialog gd = new GenericDialog("Model Loading + Segmentation Outputs");
        addInitialDialogFields(gd);
        gd.addMessage("__________");
        YoloUtils.addYoloOutputDialog(gd);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd);
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }

        Path modelPath = initialChoice.modelPath;

        IJ.log("\n --- Starting YOLO prediction");

        OutputOptions segmentOptions = YoloUtils.getYoloOutputAnswer(gd);

        // --- 2. Try to Load Model ---
        DjlModelLoader<ImagePlus, DetectedObjects> modelLoader =
                new DjlModelLoader<>(ImagePlus.class, DetectedObjects.class, KNOWN_CONFIGURATORS, ENGINE_CHOICES);
        DjlModelLoader.LoadedModel<ImagePlus, DetectedObjects> loadedResult = modelLoader.loadModel(modelPath, initialChoice);

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
        try (ZooModel<ImagePlus, DetectedObjects> model = loadedResult.getModel()) {
            ModelConfig modelConfig = loadedResult.getConfig();

            // --- 5. Make prediction ---
            DetectedObjects detectionResult;
            try (Predictor<ImagePlus, DetectedObjects> predictor = model.newPredictor()) {
                detectionResult = predictor.predict(imp);
            } catch (TranslateException e) {
                IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
                IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
                throw new RuntimeException(e);
            }

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

            if (detectionResult == null ) {
                IJ.log(" --- Segmentation failed or returned null.");
                return;
            } else if (detectionResult.getNumberOfObjects() == 0){
                IJ.log(" --- No objects were detected");
                return;
            }
            IJ.log(" --- Number of objects detected: " + detectionResult.getNumberOfObjects());

            // 5. Process Detections
            // Load ClassIdMap
            Map<String, Integer> classIdMap = null;
            if (modelConfig.getSynsetFilePath() != null && Files.exists(modelConfig.getSynsetFilePath())) {
                classIdMap = loadClassIDsFromModel(model, modelConfig.getSynsetFilePath().getFileName().toString());
                if (classIdMap == null) {
                    IJ.log("Failed to load class IDs from: " + modelConfig.getSynsetFilePath() + ". Using default numeration.");
                } else {
                    IJ.log("Successfully loaded class IDs from: "+ modelConfig.getSynsetFilePath().getFileName().toString());
                }
            } else if (modelConfig.getSynsetFileName() != null) {
                classIdMap = loadClassIDsFromModel(model, modelConfig.getSynsetFileName());
                if (classIdMap == null) {
                    IJ.log("Failed to load class IDs using name: " + modelConfig.getSynsetFileName() + " from serving.properties. Using default numeration.");
                } else {
                    IJ.log("Successfully loaded class IDs using name: " + modelConfig.getSynsetFileName());
                }
            } else {
                IJ.log("No synset/labels file specified in serving.properties.");
            }

            // Process Detections
            List<ProcessedDetection> processedDetections = SegmentationUtils.processDetections(imp, detectionResult, classIdMap);
            if (processedDetections.isEmpty()) {
                IJ.log(" --- No valid detections were processed.");
                return;
            }

            // 7. Generate Outputs
            IJ.log(" --- Generating output");
            generateOutputs(imp, processedDetections, segmentOptions, classIdMap);


            IJ.log(" --- YOLO detection complete.");
        }

    }

}



