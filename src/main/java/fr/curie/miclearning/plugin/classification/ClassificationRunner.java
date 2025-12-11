package fr.curie.miclearning.plugin.classification;

import ai.djl.MalformedModelException;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.Classifications;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import fr.curie.miclearning.prediction.model.DjlModelLoader;
import fr.curie.miclearning.prediction.translator.configurator.TranslatorConfigurator;
import fr.curie.miclearning.prediction.model.ModelDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;

public class ClassificationRunner {
    private static final Logger log = LoggerFactory.getLogger(ClassificationRunner.class);

    private static final Path MODELS_BASE_DIR = Paths.get("src", "main", "resources", "models");
    private static final Path IMAGES_BASE_DIR = Paths.get("src", "main", "resources", "images");

    public static void main(String[] args) throws IOException, ModelException, TranslateException {
        long startTime = System.nanoTime();
        predict();
        long endTime = System.nanoTime();
        double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("TOTAL TIME = " + totalTimeInSeconds + " sec");
        //log.info("{}", detection);
    }

    public static void predict() throws ModelNotFoundException, MalformedModelException, IOException {
        // --- 1.1 Define Model ---
        Path modelPath = MODELS_BASE_DIR.resolve("mlp-histo-cells");

        Criteria<Image, Classifications> criteria =
                Criteria.builder()
                        .setTypes(Image.class, Classifications.class)
                        .optModelPath(modelPath)
                        .build();

        String propertiesFileName="serving.properties";
        ModelDialogs.InitialChoice initialChoice = new ModelDialogs.InitialChoice(modelPath, propertiesFileName, false);

        Map<String, TranslatorConfigurator> KNOWN_CONFIGURATORS = Collections.emptyMap();
        String[] ENGINE_CHOICES = {"", "PyTorch", "TorchScript"};

        DjlModelLoader<Image, Classifications> modelLoader =
                new DjlModelLoader<>(Image.class, Classifications.class, KNOWN_CONFIGURATORS, ENGINE_CHOICES);

        // --- 1.2 Load Model ---
        log.info("Attempting to load model '{}' from: {}", criteria.getModelName(), modelPath);
        DjlModelLoader.LoadedModel<Image, Classifications> loadedResult = modelLoader.loadModel(modelPath, initialChoice);

        ZooModel<Image, Classifications> loadedModel = loadedResult.getModel();

        // --- 2. Load Image (DJL Image format) ---
        String inputImageName = "adi_input.png";
        Path imagePath = IMAGES_BASE_DIR.resolve(inputImageName);
        Image img = ImageFactory.getInstance().fromFile(imagePath);
        System.out.println("image loaded");

        // --- 3. Create predictor ---
        try (Predictor<Image, Classifications> predictor = loadedModel.newPredictor()) {
            log.info("predictor created");
            // --- 4. Make prediction (+ process output, done by translator) ---
            Classifications classifications = predictor.predict(img);
            System.out.println("prediction for image " + inputImageName + " :");
            System.out.println(classifications);

        } catch (TranslateException e) {
            throw new RuntimeException(e);
        }

    }
}
