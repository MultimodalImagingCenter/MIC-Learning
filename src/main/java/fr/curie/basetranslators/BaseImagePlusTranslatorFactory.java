package fr.curie.basetranslators;

import ai.djl.Model;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorFactory;
import ij.ImagePlus;

import java.util.Map;

/**
 * A helper to create a {@link TranslatorFactory} with the {@link
 * BaseImagePlusTranslator}.
 * based on <a href="https://github.com/deepjavalibrary/djl/blob/d1a7eb7ceed592c1364c958d4e0768996a9cafdd/api/src/main/java/ai/djl/modality/cv/translator/BaseImageTranslatorFactory.java">BaseImageTranslatorFactory</a>
 *
 * @param <O> the output type for the {@link TranslatorFactory}.
 */

public abstract class BaseImagePlusTranslatorFactory<O> implements TranslatorFactory {

    public Class<ImagePlus> getBaseInputType() {
        return ImagePlus.class;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <I, P> Translator<I, P> newInstance(
            Class<I> input, Class<P> output, Model model, Map<String, ?> arguments) {
        if (!isSupported(input, output)) {
            return null;
        }
        return (Translator<I, P>) buildTranslator(model, arguments);
    }


    /**
     * Builds the specific {@link Translator} instance.
     * This abstract method must be implemented by concrete subclasses.
     *
     * @param model the model the translator will be used with
     * @param arguments the configuration arguments from serving.properties
     * @return a new {@link Translator} instance
     */
    public abstract Translator<ImagePlus, O> buildTranslator(Model model, Map<String, ?> arguments);

}