package fr.curie.detr;

import ai.djl.Model;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.translate.Translator;
import ai.djl.util.Pair;
import fr.curie.basetranslators.ImpObjectDetectionTranslatorFactory;
import fr.curie.yolo.translators.ImpYoloV8Translator;
import ij.ImagePlus;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class DetrTranslatorFactory extends ImpObjectDetectionTranslatorFactory
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    @Override
    public Translator<ImagePlus, DetectedObjects> buildTranslator(
            Model model, Map<String, ?> arguments) {
        return DetrTranslator.builder(arguments).build();
    }

    @Override
    public Set<Pair<Type, Type>> getSupportedTypes() {
        return Collections.singleton(new Pair<>(ImagePlus.class, DetectedObjects.class));
    }
}