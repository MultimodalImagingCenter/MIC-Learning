package fr.curie.detr;

import ai.djl.Device;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.metric.Metrics;
import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import ai.djl.translate.TranslateException;
import fr.curie.tools.ImageJUtils;
import ij.ImagePlus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class DetrRunner {
    private static final Logger log = LoggerFactory.getLogger(DetrRunner.class);

    private static final Path MODELS_BASE_DIR = Paths.get("src", "main", "resources", "models");
    private static final Path IMAGES_BASE_DIR = Paths.get("src", "main", "resources", "images");

    public static void main(String[] args) throws IOException, ModelException, TranslateException {
        long startTime = System.nanoTime();
        predict();
        long endTime = System.nanoTime();
        double totalTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
        System.out.println("TOTAL TIME = " + totalTimeInSeconds + " sec");
    }

    public static void predict() {
        // --- 1.1 Define Model ---
        Path modelPath = MODELS_BASE_DIR.resolve("detr_cpu2");

        Criteria<ImagePlus, DetectedObjects> criteria =
                Criteria.builder()
                        .setTypes(ImagePlus.class, DetectedObjects.class)
                        .optModelPath(modelPath)
                        .optModelName("def_detr_v1_cpu")
                        .optEngine("PyTorch")
                        .optArgument("width", 1024)
                        .optArgument("height", 1024)
                        .optArgument("resize", true)
                        .optArgument("toTensor", true)
                        //.optArgument("applyRatio", true)
                        .optArgument("threshold", 0.2f)
                        .optArgument("nmsThreshold", 0.9f)
                        //.optArgument("top_k", 100) // Not needed, default to 150
                        //.optArgument("flag", Image.Flag.COLOR)
                        .optTranslatorFactory(new DetrTranslatorFactory())
                        .optProgress(new ProgressBar())
                       .optDevice(Device.cpu())
                       // .optDevice(Device.gpu(0))
                        .build();

        // --- 1.2 Load Model ---
        log.info("Attempting to load model '{}' from: {}", criteria.getModelName(), modelPath);
        System.out.println("criteria device =" + criteria.getDevice());
        try (ZooModel<ImagePlus, DetectedObjects> model = criteria.loadModel()) {
            log.info("Model loaded successfully");
            System.out.println("device =" + model.getNDManager().getDevice());

            // --- 2. Load Image (ImagePlus format) ---
            Path imgPath = IMAGES_BASE_DIR.resolve("detr_input.tif");
            ImagePlus imagePlus = ImageJUtils.loadImageJImage(imgPath, "sample_input");
            if (imagePlus == null) {
                log.error("Failed to load ImageJ image from path: {}", imgPath);
            }

            log.info("Image loaded: '{}'", imagePlus.getTitle());

            Metrics metrics = new Metrics();
            long startTime = System.nanoTime();
            // --- 4. Create the Predictor ---
            try (Predictor<ImagePlus, DetectedObjects> predictor = model.newPredictor()) {
                // --- 5. Make prediction (+ process output, done by translator) ---
                DetectedObjects detection = predictor.predict(imagePlus);

                long endTime = System.nanoTime();
                double inferenceTimeInSeconds = (endTime - startTime) / 1_000_000_000.0;
                metrics.addMetric("total_inference_time", inferenceTimeInSeconds);
                System.out.println("inference time: " + inferenceTimeInSeconds + " sec");
                System.out.println("number of object detected : " + detection.getNumberOfObjects());


                // --- 6. Compute bounding boxes ---
                int imageWidth = imagePlus.getWidth();
                int imageHeight = imagePlus.getHeight();
                System.out.println("image width = " + imageWidth);
                System.out.println("image height = " + imageHeight);

                if (detection.getNumberOfObjects() > 0) {
                    List<DetailedDetectedObjects.DetailedDetectedObject> list = detection.items();
                    for(DetailedDetectedObjects.DetailedDetectedObject result : list) {
                        String className = result.getClassName();
                        BoundingBox box = result.getBoundingBox();

                        if (!className.isEmpty()) {
                            Rectangle rectangle = box.getBounds();
                            int x = (int) (rectangle.getX() * (double) imageWidth);
                            int y = (int) (rectangle.getY() * (double) imageHeight);
                            int width = (int)(rectangle.getWidth() * (double)imageWidth);
                            int height = (int)(rectangle.getHeight() * (double)imageHeight);
                            System.out.println(className + ": x=" + x + " y=" + y + " width=" + width + " height=" + height);
                        }
                    }
                }
            }


        } catch (ModelException | IOException | TranslateException e) {
            throw new RuntimeException(e);
        }
    }
}
