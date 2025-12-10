package fr.curie.basetranslators;

import fr.curie.tools.detection.DetectedObjects;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.ArgumentsUtil;
import ai.djl.translate.TranslatorContext;

import java.util.List;
import java.util.Map;

/**
 * A {@link BaseImagePlusTranslator} that post-process the {@link NDArray} into {@link DetectedObjects}
 * with boundaries.
 * Based on <a href="https://github.com/deepjavalibrary/djl/blob/master/api/src/main/java/ai/djl/modality/cv/translator/ObjectDetectionTranslator.java">ObjectDetectionTranslator</a>
 * but take ImagePlus as input
 */
public abstract class ImpObjectDetectionTranslator extends BaseImagePlusTranslator<DetectedObjects> {

    protected float threshold;
    private SynsetLoader synsetLoader;
    protected List<String> classes;
    protected boolean applyRatio;
    protected boolean removePadding;

    /**
     * Creates the {@link ImpObjectDetectionTranslator} from the given builder.
     *
     * @param builder the builder for the translator
     */
    protected ImpObjectDetectionTranslator(ImpObjectDetectionBuilder<?> builder) {
        super(builder);
        this.threshold = builder.threshold;
        this.synsetLoader = builder.synsetLoader;
        this.applyRatio = builder.applyRatio;
        this.removePadding = builder.removePadding;
    }

    /** {@inheritDoc} */
    @Override
    public void prepare(TranslatorContext ctx) throws Exception {
        if (classes == null) {
            classes = synsetLoader.load(ctx.getModel());
        }
    }


    @Override
    public DetectedObjects processOutput(TranslatorContext translatorContext, NDList ndList) throws Exception {
        return null;
    }

    /** The base builder for the object detection translator. */
    @SuppressWarnings("rawtypes")
    public abstract static class ImpObjectDetectionBuilder<T extends ImpObjectDetectionBuilder>
            extends ImpClassificationBuilder<T> {

        protected float threshold = 0.2f;
        protected boolean applyRatio;
        protected boolean removePadding;

        /**
         * Sets the threshold for prediction accuracy.
         *
         * <p>Predictions below the threshold will be dropped.
         *
         * @param threshold the threshold for the prediction accuracy
         * @return this builder
         */
        public T optThreshold(float threshold) {
            this.threshold = threshold;
            return self();
        }

        /**
         * Determine Whether to divide output object width/height on the inference result. Default
         * false.
         *
         * <p>DetectedObject value should always bring a ratio based on the width/height instead of
         * actual width/height. Most of the model will produce ratio as the inference output. This
         * function is aimed to cover those who produce the pixel value. Make this to true to divide
         * the width/height in postprocessing in order to get ratio in detectedObjects.
         *
         * @param value whether to apply ratio
         * @return this builder
         */
        public T optApplyRatio(boolean value) {
            this.applyRatio = value;
            return self();
        }

        /** {@inheritDoc} */
        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
            if (ArgumentsUtil.booleanValue(arguments, "optApplyRatio")
                    || ArgumentsUtil.booleanValue(arguments, "applyRatio")) {
                optApplyRatio(true);
            }
            threshold = ArgumentsUtil.floatValue(arguments, "threshold", 0.2f);
            String centerFit = ArgumentsUtil.stringValue(arguments, "centerFit", "false");
            removePadding = "true".equals(centerFit);
        }
    }


}
