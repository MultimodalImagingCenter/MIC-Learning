package fr.curie.miclearning.plugin.yolo.translator;

import ai.djl.Model;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import ai.djl.translate.Translator;
import ai.djl.util.Pair;
import fr.curie.miclearning.prediction.translator.basetranslator.ImpObjectDetectionTranslatorFactory;
import ij.ImagePlus;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A ImageJ translator factory that creates a {@link ImpYoloV8Translator} instance.
 * Adapted from <a href="https://github.com/deepjavalibrary/djl/blob/ed5e096ab49850d10bd47b68e3a1ab36b80e787e/api/src/main/java/ai/djl/modality/cv/translator/YoloV8TranslatorFactory.java">djl/YoloV8TranslatorFactory</a>
 */

public class ImpYoloV8TranslatorFactory extends ImpObjectDetectionTranslatorFactory
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    @Override
    public Translator<ImagePlus, DetectedObjects> buildTranslator(
            Model model, Map<String, ?> arguments) {
        return ImpYoloV8Translator.builder(arguments).build();
    }

    @Override
    public Set<Pair<Type, Type>> getSupportedTypes() {
        return Collections.singleton(new Pair<>(ImagePlus.class, DetectedObjects.class));
    }
}