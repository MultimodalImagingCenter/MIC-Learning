package fr.curie.yolo.translators;

import ai.djl.Model;
import fr.curie.tools.detection.DetectedObjects;
import ai.djl.translate.Translator;
import ai.djl.util.Pair;
import fr.curie.basetranslators.ImpObjectDetectionTranslatorFactory;
import ij.ImagePlus;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

public class ImpYoloSegmentationTranslatorFactory extends ImpObjectDetectionTranslatorFactory
        implements Serializable {

    private static final long serialVersionUID = 1L;

    @Override
    public Translator<ImagePlus, DetectedObjects> buildTranslator(Model model, Map<String, ?> arguments) {
        return ImpYoloSegmentationTranslator.builder(arguments).build();
    }

    @Override
    public Set<Pair<Type, Type>> getSupportedTypes() {
        return Collections.singleton(new Pair<>(ImagePlus.class, DetectedObjects.class));
    }
}
