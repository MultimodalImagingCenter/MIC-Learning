package fr.curie.miclearning.plugin.detr;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.miclearning.prediction.model.DjlModelLoader;
import fr.curie.miclearning.prediction.model.ModelConfig;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;

import fr.curie.miclearning.prediction.model.ModelDialogs;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
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

import static fr.curie.miclearning.prediction.model.ModelConfigManager.saveConfigToFile;
import static fr.curie.miclearning.prediction.model.ModelDialogs.addInitialDialogFields;
import static fr.curie.miclearning.tools.detection.DetectionUtils.generateOutputs;
import static fr.curie.miclearning.tools.detection.DetectionUtils.getClassIdMap;

public class Detr_Plugin implements PlugInFilter {
    protected static ImagePlus imp;

    // List of configurators available
    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;
    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("Detr Object Detection", new DetrConfigurator()); // for classical object detection
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }
    private final String[] ENGINE_CHOICES = {"", "PyTorch"};
    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.detr";

    @Override
    public int setup(String s, ImagePlus imagePlus) {
        imp = imagePlus;
        return DOES_RGB + DOES_8G + DOES_16;
    }

    @Override
    public void run(ImageProcessor imageProcessor) {
        // --- 1. initial dialog box ---
        GenericDialog gd = new GenericDialog("Model Directory + Outputs");
        // Prompt user for model repository + config info
        addInitialDialogFields(gd,PREF_LAST_MODEL_KEY);
        gd.addMessage("__________");
        // ask for detr outputs
        DetrDialogs.addOutputDialog(gd);

        gd.showDialog();
        if (gd.wasCanceled()) {
            return; // User canceled
        }

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd, PREF_LAST_MODEL_KEY);
        DetectionUtils.OutputOptions segmentOptions = DetrDialogs.getOutputAnswer(gd);

        // check that model path is valid
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }
        Path modelPath = initialChoice.modelPath;

        // --- 2. Try to Load Model ---
        IJ.log("\n --- Starting DETR prediction");
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


            // --- 4. Make prediction ---
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

            // --- 5. Process Detections
            // Load ClassIdMap
            Map<String, Integer> classIdMap = getClassIdMap(modelConfig, model);

            // Process Detections = create ROI from DetectedObject
            List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectionResult, classIdMap);
            if (processedDetections.isEmpty()) {
                IJ.log(" --- No valid detections were processed.");
                return;
            }

            // --- 6. Generate Outputs, based on user choices
            IJ.log(" --- Generating output... ");
            generateOutputs(imp, processedDetections, segmentOptions, classIdMap);

            IJ.log(" --- DETR detection complete.");

        } catch (Exception e) { // Catch other unexpected errors during prediction/processing
            IJ.log(" --- Processing Error");
            IJ.error("Processing Error", "An unexpected error occurred:\n" + e.getMessage());
            IJ.handleException(e);
        }


    }
}
