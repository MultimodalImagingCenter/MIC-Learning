package fr.curie.sam;

import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.modality.cv.translator.Sam2Translator;
import ai.djl.modality.cv.translator.Sam2Translator.Sam2Input;
import ai.djl.modality.cv.translator.Sam2TranslatorFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Sam2Test1 {

    private static final Logger logger = LoggerFactory.getLogger(Sam2Test1.class);

    private Sam2Test1() {}

    public static void main(String[] args) throws IOException, ModelException, TranslateException {
        DetectedObjects detection = predict();
        //logger.info("{}", detection);
    }

    public static DetectedObjects predict() throws IOException, ModelException, TranslateException {
        // String url = "https://raw.githubusercontent.com/facebookresearch/segment-anything-2/main/notebooks/images/truck.jpg";
        //Image image = ImageFactory.getInstance().fromUrl(url);

        String path = "/home/noemie/Documents/code/MicLearning/src/main/resources/images/cell_input3.png";
        Image image = ImageFactory.getInstance().fromFile(Paths.get(path));
        System.out.println("image loaded");

        Sam2Input input =
                Sam2Input.builder(image).addBox(326,315,426,3680).build();
        System.out.println("sam2input created");

        Sam2Translator translator = Sam2Translator.builder()
                .build();


        Criteria<Sam2Input, DetectedObjects> criteria =
                Criteria.builder()
                        .setTypes(Sam2Input.class, DetectedObjects.class)
                        //.optModelUrls("djl://ai.djl.onnxruntime/sam2-hiera-tiny")
                        .optModelUrls("djl://ai.djl.pytorch/sam2-hiera-tiny") // for PyTorch
                        .optTranslatorFactory(new Sam2TranslatorFactory())
                        //.optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .build();
        System.out.println("criteria created");

        System.out.println("loading model");
        try (ZooModel<Sam2Input, DetectedObjects> model = criteria.loadModel();
             Predictor<Sam2Input, DetectedObjects> predictor = model.newPredictor()) {
            System.out.println("model loaded + predictor created");
            DetectedObjects detection = predictor.predict(input);
            System.out.println("prediction done");
            System.out.println("number of object detected  : " + detection.getNumberOfObjects());
            showMask(input, detection);
            return detection;
        }
    }

    private static void showMask(Sam2Input input, DetectedObjects detection) throws IOException {
        Path outputDir = Paths.get("build/output");
        Files.createDirectories(outputDir);

        Image img = input.getImage();
        img.drawBoundingBoxes(detection, 0.8f);
        img.drawMarks(input.getPoints());
        for (Rectangle rect : input.getBoxes()) {
            img.drawRectangle(rect, 0xff0000, 6);
        }

        Path imagePath = outputDir.resolve("cell3_7.png");
        img.save(Files.newOutputStream(imagePath), "png");
        System.out.println("Segmentation result image has been saved in: " + imagePath);
        logger.info("Segmentation result image has been saved in: {}", imagePath);
    }
}