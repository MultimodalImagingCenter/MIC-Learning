package fr.curie.miclearning.plugin.classification;

import ai.djl.modality.Classifications;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.translate.ArgumentsUtil;
import ai.djl.translate.TranslatorContext;

import fr.curie.miclearning.prediction.translator.basetranslator.BaseImagePlusTranslator;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class ImpClassificationTranslator extends BaseImagePlusTranslator<Classifications> {

    private SynsetLoader synsetLoader;
    private boolean applySoftmax;
    private int topK;

    private List<String> classes;

    /**
     * Constructs an Image Classification using {@link Builder}.
     *
     * @param builder the data to build with
     */
    public ImpClassificationTranslator(Builder builder) {
        super(builder);
        this.synsetLoader = builder.synsetLoader;
        this.applySoftmax = builder.applySoftmax;
        this.topK = builder.topK;
    }

    /** {@inheritDoc} */
    @Override
    public void prepare(TranslatorContext ctx) throws IOException {
        if (classes == null) {
            classes = synsetLoader.load(ctx.getModel());
        }
    }

    /** {@inheritDoc} */
    @Override
    public Classifications processOutput(TranslatorContext ctx, NDList list) {
        NDArray probabilitiesNd = list.singletonOrThrow();
        if (applySoftmax) {
            probabilitiesNd = probabilitiesNd.softmax(0);
        }
        return new Classifications(classes, probabilitiesNd, topK);
    }


    /**
     * Creates a builder to build a {@code ImageClassificationTranslator}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a builder to build a {@code ImageClassificationTranslator} with specified arguments.
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

    /** A Builder to construct a {@code ImageClassificationTranslator}. */
    public static class Builder extends ImpClassificationBuilder<Builder> {

        private boolean applySoftmax;
        private int topK = 5;

        Builder() {}

        /**
         * Set the topK number of classes to be displayed.
         *
         * @param topK the number of top classes to return
         * @return the builder
         */
        public Builder optTopK(int topK) {
            this.topK = topK;
            return this;
        }

        /**
         * Sets whether to apply softmax when processing output. Some models already include softmax
         * in the last layer, so don't apply softmax when processing model output.
         *
         * @param applySoftmax boolean whether to apply softmax
         * @return the builder
         */
        public Builder optApplySoftmax(boolean applySoftmax) {
            this.applySoftmax = applySoftmax;
            return this;
        }

        /** {@inheritDoc} */
        @Override
        protected Builder self() {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
            applySoftmax = ArgumentsUtil.booleanValue(arguments, "applySoftmax");
            topK = ArgumentsUtil.intValue(arguments, "topK", 5);
        }

        /**
         * Builds the {@link ImpClassificationTranslator} with the provided data.
         *
         * @return an {@link ImpClassificationTranslator}
         */
        public ImpClassificationTranslator build() {
            validate();
            return new ImpClassificationTranslator(this);
        }
    }
}