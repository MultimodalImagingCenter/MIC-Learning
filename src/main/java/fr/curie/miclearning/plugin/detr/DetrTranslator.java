package fr.curie.miclearning.plugin.detr;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.translate.ArgumentsUtil;
import ai.djl.translate.TranslatorContext;
import fr.curie.miclearning.tools.detection.DetailedDetectedObjects;
import fr.curie.miclearning.plugin.yolo.translator.ImpYoloV8Translator;
import ij.ImagePlus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class DetrTranslator extends ImpYoloV8Translator {
    private int top_k;

    /**
     * Constructs an ImagePlusTranslator with the provided builder.
     *
     * @param builder the data to build with
     */
    protected DetrTranslator(Builder builder) {
        super(builder);
        top_k = builder.top_k;
    }

    @Override
    public NDList processInput(TranslatorContext ctx, ImagePlus input) throws IOException {
        NDArray pixelValues = super.processInput(ctx, input).get(0);
        // Ensure batch dimension is present
//        if (pixelValues.getShape().dimension() == 3) {
//            pixelValues = pixelValues.expandDims(0);
//        }
        NDArray pixelMask = ctx.getNDManager().ones(new Shape(height, width));
        //NDArray pixelMask = ctx.getNDManager().ones(new Shape(1, height, width));
        //System.out.println("pixelValues shape= " + pixelValues.getShape() + " pixelMask shape=" + pixelMask.getShape());
        //IJ.log("pixelValues shape= " + pixelValues.getShape() + " pixelMask shape=" + pixelMask.getShape());
        NDList finalList = new NDList(pixelValues, pixelMask);
        return new NDList(pixelValues, pixelMask);
    }

    @Override
    protected DetailedDetectedObjects processFromBoxOutput(int imageWidth, int imageHeight, NDList list) {
        NDArray outLogits = list.get(0);
        NDArray outBoxes = list.get(1);

        // Start processing
        NDArray probs = Activation.sigmoid(outLogits);
        NDArray maxProbs = probs.transpose().max(new int[1]);
        NDArray classIndices = probs.transpose().argMax(0);
        // Prepare results
        ArrayList<Rectangle> boxes = new ArrayList<>();
        ArrayList<Float> scores = new ArrayList<>();
        ArrayList<Integer> classIds = new ArrayList<>();
        List<List<Float>> allScores = new ArrayList<>();
        // Prepare loop
        Shape shape = outLogits.getShape();
        int numberRows = Math.toIntExact(shape.get(0));
        // TopK Step : filtering of best detections
        int k_value = Math.min(top_k, numberRows);
        NDList top_k_out = maxProbs.topK(k_value, 0);
        NDArray top_k_scores = top_k_out.get(0);
        NDArray top_k_indices = top_k_out.get(1);
        Shape boxesShape = outBoxes.getShape();
        Shape probsShape = probs.getShape();
        NDArray expandedBoxesIndices = top_k_indices.expandDims(-1).broadcast(top_k_indices.getShape().add(boxesShape.get(boxesShape.dimension()-1)));
        NDArray expandedProbsIndices = top_k_indices.expandDims(-1).broadcast(top_k_indices.getShape().add(probsShape.get(probsShape.dimension()-1)));

        NDArray top_k_boxes = outBoxes.gather(expandedBoxesIndices, 0);
        NDArray top_k_labels = classIndices.gather(top_k_indices, 0);
        NDArray selected_probs = probs.gather(expandedProbsIndices, 0);

        for (int i = 0; i < k_value; i++) {
            int maxIndex = (int) top_k_labels.getLong(i);
            float maxClassProb = top_k_scores.getFloat(i);
            // Also get all scores
            List<Float> allScore = new ArrayList<>();
            float[] allScoreArray = selected_probs.get(i).toFloatArray();
            for (float d : allScoreArray) {
                allScore.add(d);
            }
            if (maxClassProb > threshold) {
                float xPos = top_k_boxes.get(i).getFloat(0);
                float yPos = top_k_boxes.get(i).getFloat(1);
                float w = top_k_boxes.get(i).getFloat(2);
                float h = top_k_boxes.get(i).getFloat(3);
                Rectangle rect =
                        new Rectangle(Math.max(0, xPos - w / 2), Math.max(0, yPos - h / 2), w, h);
                boxes.add(rect);
                scores.add(maxClassProb);
                classIds.add(maxIndex);
                allScores.add(allScore);
            }
        }

        return nms(imageWidth, imageHeight, boxes, classIds, scores, allScores);
    }

    protected DetailedDetectedObjects nms(
            int imageWidth,
            int imageHeight,
            List<Rectangle> boxes,
            List<Integer> classIds,
            List<Float> scores,
            List<List<Float>> allScores) {
        // IJ.log("nms threshold : " + nmsThreshold);
        List<String> retClasses = new ArrayList<>();
        List<Double> retProbs = new ArrayList<>();
        List<BoundingBox> retBB = new ArrayList<>();
        List<List<Float>> retAllScores = new ArrayList<>();

        // Convert scores to Double for NMS
        List<Double> scoreDoubles = new ArrayList<>();
        for (Float score : scores) {
            scoreDoubles.add(score.doubleValue());
        }

        List<Integer> nms = Rectangle.nms(boxes, scoreDoubles, nmsThreshold);
        for (int index : nms) {
            //int pos = map.get(index);
            int classId = classIds.get(index);
            retClasses.add(classes.get(classId));
            retProbs.add(scoreDoubles.get(index));
            retAllScores.add(allScores.get(index));
            Rectangle rect = boxes.get(index);
            if (removePadding) {
                int padW = (width - imageWidth) / 2;
                int padH = (height - imageHeight) / 2;
                rect =
                        new Rectangle(
                                (rect.getX() - padW) / imageWidth,
                                (rect.getY() - padH) / imageHeight,
                                rect.getWidth() / imageWidth,
                                rect.getHeight() / imageHeight);
            } else if (applyRatio) {
                rect =
                        new Rectangle(
                                rect.getX() / width,
                                rect.getY() / height,
                                rect.getWidth() / width,
                                rect.getHeight() / height);
            }
            retBB.add(rect);
        }
        return new DetailedDetectedObjects(retClasses, retProbs, retBB, retAllScores);
    }

    // --- Builder ---

    /**
     * Creates a builder to build a {@code DetrTranslator} with specified arguments.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Creates a builder to build a {@code DetrTranslator} with specified arguments.
     *
     * @param arguments arguments to specify builder options
     * @return a new builder
     */
    public static Builder builder(Map<String, ?> arguments) {
        Builder builder = new Builder();

        if (arguments != null) {
            builder.configPreProcess(arguments);
            builder.configPostProcess(arguments);
        }

        return builder;
    }
    
    /** The builder for {@link DetrTranslator}. */
    public static class Builder extends ImpYoloV8Translator.Builder {
        int top_k = 150;

        /**
         * Builds the translator.
         *
         * @return the new translator
         */
        @Override
        public ImpYoloV8Translator build() {
            if (pipeline == null) {
                addTransform(
                        array -> array.transpose(2, 0, 1).toType(DataType.FLOAT32, false).div(255)
                );
            }
            validate();
            return new DetrTranslator(this);
        }

        public Builder optTopK(int top_k) {
            this.top_k = top_k;
            return this;
        }


        /** {@inheritDoc} */
        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
            top_k = ArgumentsUtil.intValue(arguments, "top_k", 150);
        }
    }
    
    
}
