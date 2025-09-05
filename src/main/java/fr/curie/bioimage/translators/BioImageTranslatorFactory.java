package fr.curie.bioimage.translators;

import ai.djl.Model;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorFactory;
import ai.djl.util.Pair;
import fr.curie.basetranslators.BaseImagePlusTranslator;
import fr.curie.basetranslators.BaseImagePlusTranslatorFactory;
import ij.ImagePlus;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;


/**
 * A helper to create a {@link BioImageTranslator}.
 *
 */

public class BioImageTranslatorFactory extends BaseImagePlusTranslatorFactory<ImagePlus> {

    @Override
    public Set<Pair<Type, Type>> getSupportedTypes() {
        return Collections.singleton(new Pair<>(ImagePlus.class, ImagePlus.class));
    }

    @Override
    public Translator<ImagePlus, ImagePlus> buildTranslator(Model model, Map<String, ?> arguments) {
        return BioImageTranslator.builder(arguments).build();
    }
}