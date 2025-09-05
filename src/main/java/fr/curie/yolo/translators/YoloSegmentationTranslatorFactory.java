package fr.curie.yolo.translators;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.translator.ObjectDetectionTranslatorFactory;
import ai.djl.translate.Translator;

import java.io.Serializable;
import java.util.Map;

/**
 * A translatorFactory that creates a {@link YoloSegmentationTranslator} instance.
 */

public class YoloSegmentationTranslatorFactory extends ObjectDetectionTranslatorFactory
        implements Serializable {

    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    @Override
    protected Translator<Image, DetectedObjects> buildBaseTranslator(
            Model model, Map<String, ?> arguments) {
        return YoloSegmentationTranslator.builder(arguments).build();
    }
}