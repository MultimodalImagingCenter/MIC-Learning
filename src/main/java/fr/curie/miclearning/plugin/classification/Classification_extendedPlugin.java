package fr.curie.miclearning.plugin.classification;

import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.miclearning.prediction.model.DjlModelLoader;
import fr.curie.miclearning.prediction.model.ModelConfig;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelDialogs;
import fr.curie.miclearning.tools.ImageJUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.gui.GenericDialog;
import ij.measure.ResultsTable;
import ij.plugin.filter.ExtendedPlugInFilter;
import ij.plugin.filter.PlugInFilterRunner;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static fr.curie.miclearning.prediction.model.ModelConfigManager.saveConfigToFile;
import static fr.curie.miclearning.prediction.model.ModelDialogs.addInitialDialogFields;
import static fr.curie.miclearning.tools.ClassificationUtils.addSliceResultToTable;

public class Classification_extendedPlugin implements ExtendedPlugInFilter {

    private static final Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS;
    static {
        Map<String, TranslatorConfigurator> tempMap = new LinkedHashMap<>();
        tempMap.put("Image Classification", new ImageClassificationConfigurator());
        KNOWN_CONFIGURATORS = Collections.unmodifiableMap(tempMap);
    }

    private final String[] ENGINE_CHOICES = {"", "PyTorch", "TorchScript"};
    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.classif";

    private static ImagePlus currentImp;
    private static ZooModel<ImagePlus, Classifications> loadedModel;
    private static ModelConfig modelConfig;
    private static boolean needToRewriteServing;
    private static Path newPropertiesFilePath;

    private int errors_count = 0;
    //maximum number of error before stop
    //avoid having 1 error per slice on big stacks
    //but allow 1 punctual error
    private final int MAX_ERROR = 1;

    private static ResultsTable rt;
    private final int flags = DOES_8G | DOES_RGB | DOES_STACKS | NO_CHANGES;
    private int nPasses;
    private int passCounter;


    @Override
    public int showDialog(ImagePlus imagePlus, String s, PlugInFilterRunner plugInFilterRunner) {
        // --- 0. Clear any previous static model --
        if (loadedModel != null) {
            loadedModel.close();
            loadedModel = null;
            modelConfig = null;
        }
        rt = null;

        // --- 1. Prompt user for model repository ---
        GenericDialog gd = new GenericDialog("Model Directory");
        addInitialDialogFields(gd, PREF_LAST_MODEL_KEY);
        //ask if result tables and rois need to be reset
        ModelDialogs.askIfResetResult(gd);

        // Show dialog
        gd.showDialog();
        if (gd.wasCanceled()) {
            return DONE; // User canceled
        }

        // retrieve choices
        ModelDialogs.InitialChoice initialChoice = ModelDialogs.getInitialChoice(gd, PREF_LAST_MODEL_KEY);
        boolean resetPreviousResults = ModelDialogs.getIfResetResult(gd);

        if (initialChoice == null){
            IJ.error("Error with initial dialog", "No InitialChoice was created");
            return DONE;
        }

        if (!Files.isDirectory(initialChoice.modelPath)) {
            IJ.error("Invalid Path", "The selected path is not a valid directory.");
            return DONE;
        }
        Path modelPath = initialChoice.modelPath;
        IJ.log("\n --- Starting prediction");

        // --- 2. Try to Load Model ---
        IJ.log("loading model from path " + modelPath);
        DjlModelLoader<ImagePlus, Classifications> modelLoader =
                new DjlModelLoader<>(ImagePlus.class, Classifications.class, KNOWN_CONFIGURATORS, ENGINE_CHOICES);
        DjlModelLoader.LoadedModel<ImagePlus, Classifications> loadedResult = modelLoader.loadModel(modelPath, initialChoice);


        if (loadedResult.isFail()) {
            if (loadedResult.isCancelled()) {
                IJ.log(" --- Model loading cancelled.");
            } else {
                IJ.log(" --- Model loading failed.");
                IJ.error("Model loading failed.");
            }
            loadedModel = null;
            modelConfig = null;
            return DONE;
        }


        loadedModel = loadedResult.getModel();
        modelConfig = loadedResult.getConfig();
        needToRewriteServing = loadedResult.needToRewriteServing();
        if (needToRewriteServing) {newPropertiesFilePath = loadedResult.getNewPropertiesFilePath();}

        // Initialize ResultsTable
        rt = ResultsTable.getResultsTable();
        if (rt == null) {
            rt = new ResultsTable();
        }

        if (resetPreviousResults) ImageJUtils.resetRMandRT();

        return flags;
    }

    @Override
    public void setNPasses(int nPasses) {
        this.nPasses = nPasses;
        this.passCounter = 0;
    }

    @Override
    public int setup(String s, ImagePlus impSetup) {
        currentImp = impSetup;
        if (currentImp == null) {
            IJ.noImage();
            return DONE;
        }
        return flags;
    }

    @Override
    public void run(ImageProcessor ip) {
        passCounter++;
        IJ.showProgress(passCounter, nPasses);
        IJ.showStatus("Processing slice " + passCounter + "/" + nPasses);


        // --- 2. Make prediction (using the already loaded model) ---
        try (Predictor<ImagePlus, Classifications> predictor = loadedModel.newPredictor()) {
            Classifications classifications = predictor.predict(new ImagePlus("title", ip));

            if (rt == null) {IJ.log("WARNING : result table null before prediction");}

            if (classifications == null) {
                IJ.log(" --- Classification failed or returned null for slice " + passCounter);
                return;
            }

            // --- 3. Add results to ResultsTable ---
            ImageStack stack = currentImp.getStack();
            int sliceNumber =ip.getSliceNumber();

            addSliceResultToTable(currentImp, sliceNumber, stack.getSliceLabel(sliceNumber), classifications, rt);
            IJ.log(currentImp.getTitle() + " - Slice " + ip.getSliceNumber() + " - results: " + classifications.items());

        } catch (TranslateException e) {
            IJ.log(" --- Prediction Failed for slice " + passCounter + ": Error during prediction or translation\n Provided arguments/images are incompatible with model");
            IJ.error("Prediction Failed for slice " + passCounter, "Error during prediction or translation:\n" + e.getMessage());
            IJ.handleException(e);
            if (errors_count >= MAX_ERROR-1) {
                IJ.log("maximum number of error reached, stopping prediction");
                needToRewriteServing = false; // don't rewrite config file if to many errors
                throw new RuntimeException("Too many errors");
            }
            errors_count++;
            IJ.log("error count : " + errors_count);
        } catch (Exception e) {
            IJ.log(" --- Processing Error for slice " + passCounter + " : " + e.getMessage());
            IJ.error("Processing Error for slice " + passCounter, "An unexpected error occurred:\n" + e.getMessage());
            IJ.handleException(e);
            if (errors_count >= MAX_ERROR-1) {
                IJ.log("maximum number of error reached, stopping prediction");
                needToRewriteServing = false; // don't rewrite config file if to many errors
                throw new RuntimeException("Too many errors");
            }
            errors_count++;
            IJ.log("error count : " + errors_count);
        }

        // --- 4. Show results table after the last slice ---
        if (passCounter == nPasses) {
            if (rt != null && rt.getCounter() > 0) {
                rt.show("Results");
            } else if (rt != null) {
                IJ.log("No results to show in the table.");
            }
            IJ.showProgress(1.0); // Complete progress bar
            IJ.showStatus(" --- Processing complete.");
            IJ.log(" --- Prediction done");

            // Save configuration to config.properties if needed
            if (needToRewriteServing && rt != null) {
                try {
                    saveConfigToFile(modelConfig, newPropertiesFilePath);
                    IJ.log("Saved new configuration.");
                } catch (IOException e) {
                    IJ.log("Warning: Failed to save configuration. Error: " + e.getMessage());
                }
            }

            if (loadedModel != null) {
                loadedModel.close();
            }
        }
    }
}
