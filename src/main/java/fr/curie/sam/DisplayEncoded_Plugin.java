package fr.curie.sam;

import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.engine.Engine;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import fr.curie.modelloading.dialogs.ModelDialogs;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.plugin.filter.PlugInFilter;
import ij.process.ImageProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static fr.curie.tools.ImageJUtils.NDArray2ImageStack;

public class DisplayEncoded_Plugin implements PlugInFilter {
    protected static ImagePlus imp;
    private static final String PREF_LAST_MODEL_DIR = "myplugin.lastmodeldir.samencoding";

    @Override
    public int setup(String s, ImagePlus imagePlus) {
        imp = imagePlus;
        return DOES_RGB + DOES_8G;
    }

    @Override
    public void run(ImageProcessor imageProcessor) {
        GenericDialog gd = new GenericDialog("Choose Model Directory");
        String defaultDir = null;
        String lastDir = Prefs.get(PREF_LAST_MODEL_DIR, defaultDir);
        if (lastDir != null && Files.isDirectory(Paths.get(lastDir))) {
            defaultDir = lastDir;
        } else {
            defaultDir = "";
        }
        gd.addDirectoryField("Model_Directory:", defaultDir, 60);
        gd.showDialog();

        String dirPath = gd.getNextString();
        Path modelPath = Paths.get(dirPath);
        if (Files.isDirectory(modelPath)) {
            // Save the selected directory for next time
            Prefs.set(PREF_LAST_MODEL_DIR, dirPath);
            Prefs.savePreferences();
        } else {
            IJ.error("Selection Error", "The selected path is not a valid directory:\n" + dirPath);
        }

        ImpSam2Translator translator = ImpSam2Translator.builder()
                .optEncodeMethod("encode")
                .build();

        Criteria<ImpSam2Translator.ImpSam2Input, DetectedObjects> criteria =
                Criteria.builder()
                        .setTypes(ImpSam2Translator.ImpSam2Input.class, DetectedObjects.class)
                        .optModelPath(modelPath)
                        .optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .optEngine("PyTorch")
                        .build();

        IJ.log("loading model with path " + modelPath);

        try (ZooModel<ImpSam2Translator.ImpSam2Input, DetectedObjects> model = criteria.loadModel()) {
            IJ.log("model loaded");

            // 1. Create a Session Manager for the features
            try (NDManager sessionManager = model.getNDManager().newSubManager()) {

                IJ.log("Encoding image...");
                NDList encodedImage = translator.encode(model, imp, sessionManager);
                IJ.log("image encoded");
                // visualize encoded image
                IJ.log("embeddings size : " + encodedImage.size());
                IJ.log("embeddings dimensions : " + Arrays.toString(encodedImage.getShapes()));
                try (NDArray features = encodedImage.get(0).duplicate()) {
                    ImagePlus imp = NDArray2ImageStack(features, "features 0");
                    imp.show();
                }
                try (NDArray features = encodedImage.get(1).duplicate()) {
                    ImagePlus imp = NDArray2ImageStack(features, "features 1");
                    imp.show();
                }
                try (NDArray features = encodedImage.get(2).duplicate()) {
                    ImagePlus imp = NDArray2ImageStack(features, "features 2");
                    imp.show();
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (ModelNotFoundException | MalformedModelException | IOException e) {
            throw new RuntimeException(e);
        }

    }
}
