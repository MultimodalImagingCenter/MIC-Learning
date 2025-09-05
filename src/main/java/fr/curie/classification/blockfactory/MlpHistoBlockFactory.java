package fr.curie.classification.blockfactory;

import ai.djl.Model;
import ai.djl.basicmodelzoo.basic.Mlp;
import ai.djl.nn.Block;
import ai.djl.nn.BlockFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class MlpHistoBlockFactory implements BlockFactory {

    private static final long serialVersionUID = 1L;

    public Block newBlock(Model model, Path modelPath, Map<String, ?> arguments) throws IOException {
        if (!arguments.containsKey("input") || !arguments.containsKey("output") || !arguments.containsKey("hiddenLayers")) {
            throw new IllegalArgumentException("Missing required arguments for Mlp: input, output, and hiddenLayers must be provided.");
        }

        int inputSize = ((Number) arguments.get("input")).intValue();
        int outputSize = ((Number) arguments.get("output")).intValue();
        List<Integer> hiddenLayerSizesList = (List<Integer>) arguments.get("hiddenLayers");

        int[] hiddenLayerSizes = hiddenLayerSizesList.stream().mapToInt(i -> i).toArray();

        return new Mlp(inputSize, outputSize, hiddenLayerSizes);
    }
}
