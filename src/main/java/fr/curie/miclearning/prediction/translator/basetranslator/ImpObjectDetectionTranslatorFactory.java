package fr.curie.miclearning.prediction.translator.basetranslator;

import fr.curie.miclearning.tools.detection.DetectedObjects;

/**
 * An abstract {@link BaseImagePlusTranslatorFactory} that creates a {@link ImpObjectDetectionTranslator} instance.
 * Based on <a href="https://github.com/deepjavalibrary/djl/blob/d1a7eb7ceed592c1364c958d4e0768996a9cafdd/api/src/main/java/ai/djl/modality/cv/translator/ObjectDetectionTranslatorFactory.java">ObjectDetectionTranslatorFactory</a>
 */

public abstract class ImpObjectDetectionTranslatorFactory extends BaseImagePlusTranslatorFactory<DetectedObjects> {

    public Class<DetectedObjects> getBaseOutputType() {
        return DetectedObjects.class;
    }
}
