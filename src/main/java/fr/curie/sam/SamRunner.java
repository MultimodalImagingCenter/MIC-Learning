package fr.curie.sam;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import fr.curie.sam.ImpSam2Translator.ImpSam2Input;
import fr.curie.tools.DetectionUtils;
import fr.curie.yolo.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static fr.curie.tools.ImageJUtils.*;

public final class SamRunner {

    private static final Logger logger = LoggerFactory.getLogger(SamRunner.class);
    private static final Path MODELS_BASE_DIR = Paths.get("src", "main", "resources", "models");
    private static final Path IMAGES_BASE_DIR = Paths.get("src", "main", "resources", "images");

    private SamRunner() {}

    public static void main(String[] args) throws Exception {
        predict();

    }

    public static void predict() throws Exception {

        Path imagePath = IMAGES_BASE_DIR.resolve("cell_input3.png");
        ImagePlus image = loadImageJImage(imagePath, "input");
        System.out.println("image loaded");


        ImpSam2Translator translator = ImpSam2Translator.builder()
                .optEncodeMethod("encode")
                .build();

        Path modelPath = MODELS_BASE_DIR.resolve("sam2-hiera-tiny");
        Criteria<ImpSam2Input, DetectedObjects> criteria =
                Criteria.builder()
                        .setTypes(ImpSam2Input.class, DetectedObjects.class)
                        .optModelPath(modelPath)
                        .optTranslator(translator)
                        .optProgress(new ProgressBar())
                        .optEngine("PyTorch")
                        .build();
        System.out.println("criteria created");

        System.out.println("loading model with path " + modelPath);
        try (ZooModel<ImpSam2Input, DetectedObjects> model = criteria.loadModel();
             Predictor<ImpSam2Input, DetectedObjects> predictor = model.newPredictor()) {
            System.out.println("model loaded + predictor created");

            List<String> classNames = new ArrayList<>();
            List<Double> probabilities = new ArrayList<>();
            List<BoundingBox > boundingBoxes = new ArrayList<>();

            //  Create a Session Manager for the features
            try (NDManager sessionManager = model.getNDManager().newSubManager()) {

                // encode image
                System.out.println("Encoding image...");
                NDList encodedImage = translator.encode(model, image, sessionManager);
                System.out.println("image encoded");

                // first detection
                ImpSam2Input input =
                        ImpSam2Input.builder(image).addPoint(315, 131).build();
                input.setFeatures(encodedImage);
                DetectedObjects detection1 = predictor.predict(input);
                System.out.println("prediction 1 done");
                DetectedObjects.DetectedObject item1 = detection1.item(0);
                boundingBoxes.add(item1.getBoundingBox());
                probabilities.add(item1.getProbability());
                classNames.add("1");

                // second detection
                ImpSam2Input input2 =
                        ImpSam2Input.builder(image).addPoint(416, 122).build();
                input2.setFeatures(encodedImage);
                DetectedObjects detection2 = predictor.predict(input2);
                System.out.println("prediction 2 done");

                DetectedObjects.DetectedObject item2 = detection2.item(0);
                boundingBoxes.add(item2.getBoundingBox());
                probabilities.add(item2.getProbability());
                classNames.add("1");

                DetectedObjects detections = new DetectedObjects(classNames, probabilities, boundingBoxes);

                System.out.println("total number of objects detected : " + detections.getNumberOfObjects());

                System.out.println("proba 1" + item1.getProbability());
                System.out.println("class name 1 : " + item1.getClassName());
                System.out.println("classes names 1 : " + detection1.getClassNames());
                System.out.println("classes names 1 empty : " + detection1.getClassNames().isEmpty());
                Rectangle bb1 = item1.getBoundingBox().getBounds();
                System.out.println("bb 1 : [" + bb1.getX() + ", " + bb1.getY() + ", " + bb1.getWidth() + ", " + bb1.getHeight() + "]");
                Rectangle bb2 = item2.getBoundingBox().getBounds();
                System.out.println("bb 2 : [" + bb2.getX() + ", " + bb2.getY() + ", " + bb2.getWidth() + ", " + bb2.getHeight() + "]");


                List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(image, detections, null);
                if (processedDetections.isEmpty()) {
                    IJ.log(" --- No valid detections were processed.");
                    return;
                }


            }

        }
    }

    private static void showMask(ImpSam2Input input, DetectedObjects detection, String fileSuffix) throws IOException {
        Path outputDir = Paths.get("build/output");
        Files.createDirectories(outputDir);

        ImagePlus imp = input.getImage();
        Image img = imagePlusToDjlImage(imp);
        img.drawBoundingBoxes(detection, 0.8f);
        img.drawMarks(input.getPoints());
        for (Rectangle rect : input.getBoxes()) {
            img.drawRectangle(rect, 0xff0000, 6);
        }

        Path imagePath = outputDir.resolve("cell3_test3_" + fileSuffix + ".png");
        img.save(Files.newOutputStream(imagePath), "png");
        System.out.println("Segmentation result image has been saved in: " + imagePath);
        logger.info("Segmentation result image has been saved in: {}", imagePath);
    }

    private static void showMask(ImpSam2Input input, DetectedObjects detection) throws IOException {
        showMask(input, detection, "");
    }
}