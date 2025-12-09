package fr.curie.detr;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDArrays;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Activation;
import ai.djl.translate.ArgumentsUtil;
import ai.djl.translate.TranslatorContext;
import fr.curie.yolo.translators.ImpYoloV8Translator;
import ij.IJ;
import ij.ImagePlus;

import java.io.IOException;
import java.util.*;

public class DetrDomvTranslator extends ImpYoloV8Translator {
    private int top_k;
    private float containmentRatio;

    /**
     * Constructs an ImageTranslator with the provided builder.
     *
     * @param builder the data to build with
     */
    protected DetrDomvTranslator(Builder builder) {
        super(builder);
        top_k = builder.top_k;
        containmentRatio = builder.containmentRatio;
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
        System.out.println("nd list shapes= " + Arrays.toString(finalList.getShapes()));
        return new NDList(pixelValues, pixelMask);
    }

    public double getIntersection(BoundingBox box1, BoundingBox box2) {
        Rectangle rect1 = box1.getBounds();
        Rectangle rect2 = box2.getBounds();
        double left = Math.max(rect1.getX(), rect2.getX());
        double top = Math.max(rect1.getY(), rect2.getY());
        double right = Math.min(rect1.getX() + rect1.getWidth(), rect2.getX() + rect2.getWidth());
        double bottom = Math.min(rect1.getY() + rect1.getHeight(), rect2.getY() + rect2.getHeight());
        if (!(left > right) && !(top > bottom)) {
            //noinspection UnnecessaryLocalVariable
            double intersect = (right - left) * (bottom - top);
            return intersect;
        } else {
            return (double)0.0F;
        }
    }

    public double containmentRatio(Rectangle outer, Rectangle inner) {
        double intersection = getIntersection(outer, inner);
        double innerArea = inner.getWidth() * inner.getHeight();
        return intersection / innerArea;

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

        // Filter out rectangles that are contained in another
        List<Integer> filtered = new ArrayList<>();
        for (int i = 0; i < nms.size(); i++) {
            int idxA = nms.get(i);
            Rectangle boxA = boxes.get(idxA);
            boolean isContained = false;

            for (int j = 0; j < nms.size(); j++) {
                if (i == j) continue;
                int idxB = nms.get(j);
                Rectangle boxB = boxes.get(idxB);

                double containment = containmentRatio(boxB, boxA); // is A inside B?
                if (containment >= containmentRatio) {
                    // IJ.log("Box " + idxA + " is contained in box " + idxB + " with ratio " + containment);
                    isContained = true;
                    break;
                }
            }

            if (!isContained) {
                filtered.add(idxA);
            }
        }

        for (int index : filtered) {
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


    /** The builder for {@link DetrDomvTranslator}. */
    public static class Builder extends ImpYoloV8Translator.Builder {
        int top_k = 150;
        float containmentRatio = 0.8F;

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
            return new DetrDomvTranslator(this);
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
            containmentRatio = ArgumentsUtil.floatValue(arguments, "containmentRatio", 0.8F);
        }
    }
}
