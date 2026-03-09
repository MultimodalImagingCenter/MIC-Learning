package fr.curie.miclearning.plugin.classification;

import ai.djl.Model;
import ai.djl.modality.Classifications;
import ai.djl.translate.Translator;
import ai.djl.util.Pair;
import fr.curie.miclearning.prediction.translator.basetranslator.BaseImagePlusTranslatorFactory;
import ij.ImagePlus;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class ImpClassificationTranslatorFactory extends BaseImagePlusTranslatorFactory<Classifications> implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public Translator<ImagePlus, Classifications> buildTranslator(Model model, Map<String, ?> arguments) {
        return ImpClassificationTranslator.builder(arguments).build();
    }

    @Override
    public Set<Pair<Type, Type>> getSupportedTypes() {
        return Collections.singleton(new Pair<>(ImagePlus.class, Classifications.class));
    }

}
