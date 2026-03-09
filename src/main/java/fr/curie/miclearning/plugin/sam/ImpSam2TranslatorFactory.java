package fr.curie.miclearning.plugin.sam;

import ai.djl.Model;
import ai.djl.modality.Input;
import ai.djl.modality.Output;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorFactory;
import ai.djl.util.Pair;

import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ImpSam2TranslatorFactory implements TranslatorFactory, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Set<Pair<Type, Type>> SUPPORTED_TYPES = new HashSet();

    public ImpSam2TranslatorFactory() {
    }

    public <I, O> Translator<I, O> newInstance(Class<I> input, Class<O> output, Model model, Map<String, ?> arguments) {
        if (input == ImpSam2Translator.ImpSam2Input.class && output == DetectedObjects.class) {
            return (Translator<I, O>) ImpSam2Translator.builder(arguments).build();
        } else if (input == Input.class && output == Output.class) {
            ImpSam2Translator translator = ImpSam2Translator.builder(arguments).build();
            return null;
            //return new Sam2ServingTranslator(translator);
        } else {
            throw new IllegalArgumentException("Unsupported input/output types.");
        }
    }

    public Set<Pair<Type, Type>> getSupportedTypes() {
        return SUPPORTED_TYPES;
    }

    static {
        SUPPORTED_TYPES.add(new Pair(ImpSam2Translator.ImpSam2Input.class, DetectedObjects.class));
        SUPPORTED_TYPES.add(new Pair(Input.class, Output.class));
    }
}