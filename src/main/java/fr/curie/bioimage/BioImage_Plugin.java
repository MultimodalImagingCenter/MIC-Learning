package fr.curie.bioimage;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.modelloading.DjlModelLoader;
import fr.curie.modelloading.ModelConfig;
import fr.curie.modelloading.configurators.BioImageConfigurator;
import fr.curie.modelloading.configurators.TranslatorConfigurator;
import fr.curie.modelloading.dialogs.ModelDialogs;
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
import java.util.Map;


import static fr.curie.modelloading.ModelConfigManager.saveConfigToFile;
import static fr.curie.modelloading.dialogs.ModelDialogs.addInitialDialogFields;


/**
 * Plugin to execute models from the <a href="https://bioimage.io/#/models">BioImage Model Zoo</a>
 * Input and output of the model must be ImagePlus
 *
 */

public class BioImage_Plugin implements PlugInFilter {
    protected static ImagePlus imp;
    // List of configurators available
    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;
    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("BioImage Model Prediction", new BioImageConfigurator());
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }
    // List of engine available
    private static final String[] ENGINE_CHOICES = {"", "TensorFlow",  "PyTorch", "OnnxRuntime"};


    @Override
    public int setup(String s, ImagePlus imagePlus) {
        imp = imagePlus;
        return DOES_8G | DOES_RGB | DOES_16 | DOES_32;
    }

    @Override
    public void run(ImageProcessor ip) {
        // --- 1. Initial dialog box ---
        // ask for model repository + config info
        GenericDialog gd = new GenericDialog("Model Directory");
        addInitialDialogFields(gd);
        gd.showDialog();
        if (gd.wasCanceled()) return; // User canceled

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd);

        // check that model path is valid
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }
        Path modelPath = initialChoice.modelPath;

        // --- 2. Try to Load Model ---
        IJ.log("\n --- Starting BioImage Zoo models prediction");
        DjlModelLoader<ImagePlus, ImagePlus> modelLoader =
                new DjlModelLoader<>(ImagePlus.class, ImagePlus.class, KNOWN_CONFIGURATORS, ENGINE_CHOICES);
        DjlModelLoader.LoadedModel<ImagePlus, ImagePlus> loadedResult = modelLoader.loadModel(modelPath, initialChoice);

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
        try (ZooModel<ImagePlus, ImagePlus> model = loadedResult.getModel()) {
            ModelConfig modelConfig = loadedResult.getConfig();


            // --- 4. Make prediction ---
            try(Predictor<ImagePlus, ImagePlus> predictor = model.newPredictor()) {
                ImagePlus output = predictor.predict(imp);
                IJ.log(" --- Prediction done");

                // Save configuration to serving.properties if needed
                if (loadedResult.needToRewriteServing()) {
                    try {
                        Path newPropertiesFilePath = loadedResult.getNewPropertiesFilePath();
                        saveConfigToFile(modelConfig, newPropertiesFilePath);
                        IJ.log("Saved new configuration.");
                    } catch (IOException e) {
                        IJ.log("Warning: Failed to save configuration. Error: " + e.getMessage());
                    }
                }

                // --- 5. Show output
                output.show();


            } catch (TranslateException e) {
                IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
                IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
                throw new RuntimeException(e);
            }
        }
    }

}