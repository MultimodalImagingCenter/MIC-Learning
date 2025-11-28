package fr.curie.sam;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import fr.curie.sam.Sam2Translator.Sam2Input;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Sam2Test4 {

    private static final Logger logger = LoggerFactory.getLogger(Sam2Test4.class);

    private Sam2Test4() {}

    public static void main(String[] args) throws Exception {
        predict();

    }

    public static void predict() throws Exception {
        // String url = "https://raw.githubusercontent.com/facebookresearch/segment-anything-2/main/notebooks/images/truck.jpg";
        //Image image = ImageFactory.getInstance().fromUrl(url);

        String path = "/home/noemie/Documents/code/MicLearning/src/main/resources/images/cell_input3.png";
        Image image = ImageFactory.getInstance().fromFile(Paths.get(path));
        System.out.println("image loaded");



        Sam2Translator translator = Sam2Translator.builder()
                .optEncodeMethod("encode")
                .build();

        Criteria<Sam2Input, DetectedObjects> criteria =
                Criteria.builder()
                        .setTypes(Sam2Input.class, DetectedObjects.class)
                        //.optModelUrls("djl://ai.djl.onnxruntime/sam2-hiera-tiny")
                        .optModelUrls("djl://ai.djl.pytorch/sam2-hiera-tiny") // for PyTorch
                        //.optTranslatorFactory(new Sam2TranslatorTest0Factory())
                        .optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .optEngine("PyTorch")
                        .build();
        System.out.println("criteria created");

        System.out.println("loading model");
        try (ZooModel<Sam2Input, DetectedObjects> model = criteria.loadModel();
             Predictor<Sam2Input, DetectedObjects> predictor = model.newPredictor()) {
            System.out.println("model loaded + predictor created");
            // 1. Create a Session Manager for the features
            try (NDManager sessionManager = model.getNDManager().newSubManager()) {

                System.out.println("Encoding image...");
                NDList encodedImage = translator.encode(model, image, sessionManager);
                System.out.println("image encoded");

                Sam2Input input =
                        Sam2Input.builder(image).addPoint(315, 131).build();
                input.setFeatures(encodedImage);
                System.out.println("sam2input 1 created");


                DetectedObjects detection = predictor.predict(input);
                System.out.println("prediction 1 done");
                System.out.println("number of object detected  : " + detection.getNumberOfObjects());
                showMask(input, detection, "1");

                Sam2Input input2 =
                        Sam2Input.builder(image).addPoint(416, 122).build();
                input2.setFeatures(encodedImage);
                System.out.println("sam2input 2 created");

                DetectedObjects detection2 = predictor.predict(input2);
                System.out.println("prediction 2 done");
                System.out.println("number of object detected  : " + detection2.getNumberOfObjects());
                showMask(input2, detection2, "");
            }



        }
    }

    private static void showMask(Sam2Input input, DetectedObjects detection, String fileSuffix) throws IOException {
        Path outputDir = Paths.get("build/output");
        Files.createDirectories(outputDir);

        Image img = input.getImage();
        img.drawBoundingBoxes(detection, 0.8f);
        img.drawMarks(input.getPoints());
        for (Rectangle rect : input.getBoxes()) {
            img.drawRectangle(rect, 0xff0000, 6);
        }

        Path imagePath = outputDir.resolve("cell3_test2_" + fileSuffix + ".png");
        img.save(Files.newOutputStream(imagePath), "png");
        System.out.println("Segmentation result image has been saved in: " + imagePath);
        logger.info("Segmentation result image has been saved in: {}", imagePath);
    }

    private static void showMask(Sam2Input input, DetectedObjects detection) throws IOException {
        showMask(input, detection, "");
    }
}