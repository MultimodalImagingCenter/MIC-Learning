package fr.curie.sam;

import ai.djl.Model;
import ai.djl.modality.Input;
import ai.djl.modality.Output;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorFactory;
import ai.djl.util.Pair;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Sam2TranslatorFactory implements TranslatorFactory, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Set<Pair<Type, Type>> SUPPORTED_TYPES = new HashSet();

    public Sam2TranslatorFactory() {
    }

    public <I, O> Translator<I, O> newInstance(Class<I> input, Class<O> output, Model model, Map<String, ?> arguments) {
        if (input == Sam2Translator.Sam2Input.class && output == DetectedObjects.class) {
            System.out.println("using normal translator");
            System.out.println("translator arguments " + arguments.toString());
            return (Translator<I, O>) Sam2Translator.builder(arguments).build();
        } else if (input == Input.class && output == Output.class) {
            Sam2Translator translator = Sam2Translator.builder(arguments).build();
            System.out.println("using serving translator");
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
        SUPPORTED_TYPES.add(new Pair(Sam2Translator.Sam2Input.class, DetectedObjects.class));
        SUPPORTED_TYPES.add(new Pair(Input.class, Output.class));
    }
}