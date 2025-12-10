package fr.curie.bioimage;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.modelloading.DjlModelLoader;
import fr.curie.modelloading.ModelConfig;
import fr.curie.modelloading.configurators.BioImageConfigurator;
import fr.curie.modelloading.configurators.TranslatorConfigurator;
import fr.curie.modelloading.dialogs.ModelDialogs;
import fr.curie.tools.tiling.TilingDialogs;
import fr.curie.tools.tiling.TilingOptions;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.gui.Roi;
import ij.plugin.filter.PlugInFilter;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static fr.curie.modelloading.ModelConfigManager.saveConfigToFile;
import static fr.curie.modelloading.dialogs.ModelDialogs.addInitialDialogFields;


/**
 * Plugin to execute models from the <a href="https://bioimage.io/#/models">BioImage Model Zoo</a>
 * Input and output of the model must be ImagePlus
 *
 */

public class BioImage_TilePlugin implements PlugInFilter {
    protected static ImagePlus imp;

    // List of configurators available
    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;

    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("BioImage Model Prediction", new BioImageConfigurator());
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }

    // List of engine available
    private static final String[] ENGINE_CHOICES = {"", "TensorFlow", "PyTorch", "OnnxRuntime"};
    // key to fetch last model path in preferences
    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.bioimage";

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
        // ask for Tiling preferences
        addInitialDialogFields(gd, PREF_LAST_MODEL_KEY);
        gd.addMessage("__________");
        TilingDialogs.addTilingDialog(gd);

        gd.showDialog();
        if (gd.wasCanceled()) return; // User canceled

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd, PREF_LAST_MODEL_KEY);
        TilingOptions tileOptions = TilingDialogs.getTilingAnswer(gd);

        // check that model path is valid
        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return;
        }
        Path modelPath = initialChoice.modelPath;

        // --- 2. Try to Load Model ---
        IJ.log("\n --- Starting Unet models prediction");
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
            ImagePlus output;

            // using tiles
            if (tileOptions.useTiling) {
                // configure tile size as default model size if no size was given by the user
                if (tileOptions.defaultTileSize) {
                    tileOptions.setWidthAndHeight(modelConfig.getDefaultWidth(), modelConfig.getDefaultHeight());
                }
                output = runTiledDetection(model, tileOptions, modelConfig);

            // not using tiles
            } else {
                IJ.log("Using non tiled detection");
                try (Predictor<ImagePlus, ImagePlus> predictor = model.newPredictor()) {
                    output = predictor.predict(imp);
                    IJ.log(" --- Prediction done");
                } catch (TranslateException e) {
                    IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
                    IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
                    throw new RuntimeException(e);
                }
            }

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
            if (output == null) {
                IJ.error("no output generated", "no output was generated");
                return;
            }
            output.show();


        }
    }


    private ImagePlus runTiledDetection(ZooModel<ImagePlus, ImagePlus> model, TilingOptions tileOptions, ModelConfig config) {
        IJ.log("Using tiled detection");

        //get image tiles parameters
        int image_width = imp.getWidth();
        int image_height = imp.getHeight();
        int tile_width = tileOptions.tileWidth;
        int tile_height = tileOptions.tileHeight;
        double overlap = tileOptions.overlap;
        //IJ.log("orginal image dimensions : w=" + image_width + " h=" + image_height);

        // compute tiles parameters
        // number of pixels of a step
        int x_step = (int) Math.floor(tile_width * (1 - overlap));
        int y_step = (int) Math.floor(tile_height * (1 - overlap));
        // maximum last x and y position
        int x_max = Math.max(0, image_width - tile_width);
        int y_max = Math.max(0, image_height - tile_height);
        tile_width = Math.min(tile_width, image_width);
        tile_height = Math.min(tile_height, image_height);

        //IJ.log("tiles dimensions : w="+ tile_width + " h=" + tile_height);

        int tiles_number = 0;
        int total_tiles_estimate = ((y_max + y_step) / y_step + 1) * ((x_max + x_step) / x_step + 1);

        // prepare the final result
        // for now, black float processor
        FloatProcessor globalProcessor = new FloatProcessor(image_width, image_height);
        float[] globalPixels = (float[]) globalProcessor.getPixels();

        try (Predictor<ImagePlus, ImagePlus> predictor = model.newPredictor()) {
            for (int y = 0; y < y_max + y_step; y += y_step) {
                for (int x = 0; x < x_max + x_step; x += x_step) {
                    int x_eff = Math.min(x, x_max);
                    int y_eff = Math.min(y, y_max);

                    tiles_number++;
                    IJ.showStatus("Processing tile " + tiles_number + " / " + total_tiles_estimate);

                    // crop the tile
                    Roi roi = new Roi(x_eff, y_eff, tile_width, tile_height);
                    imp.setRoi(roi);
                    ImagePlus inputTile = imp.crop("stack"); // takes every slice/channel of the input

                    // make predictions
                    ImagePlus detectionTemp = predictor.predict(inputTile);

                    // merge prediction on global result
                    ImageProcessor tileProc = detectionTemp.getProcessor();
                    if (!(tileProc instanceof FloatProcessor)) {
                        tileProc = tileProc.convertToFloat();
                    }

                    float[] tilePixels = (float[]) tileProc.getPixels();
                    int outputWidth = tileProc.getWidth(); // in case tile output isn't the
                    int outputHeight = tileProc.getHeight();

                    for (int ty = 0; ty < outputHeight; ty++) {
                        for (int tx = 0; tx < outputWidth; tx++) {
                            // Calculate index in the global image
                            int globalX = x_eff + tx;
                            int globalY = y_eff + ty;

                            // Safety check
                            if (globalX < image_width && globalY < image_height) {
                                int globalIndex = globalY * image_width + globalX;
                                int tileIndex = ty * outputWidth + tx;

                                float newVal = tilePixels[tileIndex];
                                float oldVal = globalPixels[globalIndex];

                                // take only max value
                                if (newVal > oldVal) {
                                    globalPixels[globalIndex] = newVal;
                                }
                            }
                        }
                    }
                    inputTile.close();
                    detectionTemp.close();
                }
            }

            IJ.log("number of tiles processed: " + tiles_number);

            imp.deleteRoi();
            // Return the reconstructed image
            return new ImagePlus("Result_Tiled", globalProcessor);

        } catch (TranslateException e) {
            IJ.log(" --- Prediction Failed : Error during prediction or translation\n Provided arguments are incompatible with model");
            IJ.error("Prediction Failed", "Error during prediction or translation:\n" + e.getMessage());
            IJ.handleException(e);
        }

        return null;

    }
}