package fr.curie.yolo.translators;

import fr.curie.tools.detection.DetectedObjects;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.ArgumentsUtil;

import java.util.ArrayList;
import java.util.Map;

public class ImpYoloV8Translator extends ImpYoloV5Translator{

    private int maxBoxes;

    /**
     * Constructs an ImageTranslator with the provided builder.
     *
     * @param builder the data to build with
     */
    protected ImpYoloV8Translator(Builder builder) {
        super(builder);
        maxBoxes = builder.maxBox;
    }

    /**
     * Creates a builder to build a {@code YoloV8Translator} with specified arguments.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder to build a {@code YoloV8Translator} with specified arguments.
     *
     * @param arguments arguments to specify builder options
     * @return a new builder
     */
    public static Builder builder(Map<String, ?> arguments) {
        Builder builder = new Builder();
        builder.configPreProcess(arguments);
        builder.configPostProcess(arguments);

        return builder;
    }

    /** {@inheritDoc} */
    @Override
    protected DetectedObjects processFromBoxOutput(int imageWidth, int imageHeight, NDList list) {
        NDArray rawResult = list.get(0);
        NDArray reshapedResult = rawResult.transpose();
        Shape shape = reshapedResult.getShape();
        float[] buf = reshapedResult.toFloatArray();
        int numberRows = Math.toIntExact(shape.get(0));
        int nClasses = Math.toIntExact(shape.get(1));
        int padding = nClasses - classes.size();
        if (padding != 0 && padding != 4) {
            throw new IllegalStateException(
                    "Expected classes: " + (nClasses - 4) + ", got " + classes.size());
        }

        ArrayList<Rectangle> boxes = new ArrayList<>();
        ArrayList<Float> scores = new ArrayList<>();
        ArrayList<Integer> classIds = new ArrayList<>();

        // reverse order search in heap; searches through #maxBoxes for optimization when set
        for (int i = numberRows - 1; i > numberRows - maxBoxes; --i) {
            int index = i * nClasses;
            float maxClassProb = -1f;
            int maxIndex = -1;
            for (int c = 4; c < nClasses; c++) {
                float classProb = buf[index + c];
                if (classProb > maxClassProb) {
                    maxClassProb = classProb;
                    maxIndex = c;
                }
            }
            maxIndex -= padding;

            if (maxClassProb > threshold) {
                float xPos = buf[index]; // center x
                float yPos = buf[index + 1]; // center y
                float w = buf[index + 2];
                float h = buf[index + 3];
                Rectangle rect =
                        new Rectangle(Math.max(0, xPos - w / 2), Math.max(0, yPos - h / 2), w, h);
                boxes.add(rect);
                scores.add(maxClassProb);
                classIds.add(maxIndex);
            }
        }

        return nms(imageWidth, imageHeight, boxes, classIds, scores);
    }

    /** The builder for {@link ImpYoloV8Translator}. */
    public static class Builder extends ImpYoloV5Translator.Builder {

        private int maxBox = 8400;

        /**
         * Builds the translator.
         *
         * @return the new translator
         */
        @Override
        public ImpYoloV8Translator build() {
            if (pipeline == null) {
                addTransform(
                        array -> array.transpose(2, 0, 1).toType(DataType.FLOAT32, false).div(255));
            }
            validate();
            return new ImpYoloV8Translator(this);
        }

        /** {@inheritDoc} */
        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
            maxBox = ArgumentsUtil.intValue(arguments, "maxBox", 8400);
        }
    }
}
