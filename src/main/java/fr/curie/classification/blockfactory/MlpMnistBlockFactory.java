package fr.curie.classification.blockfactory;

import ai.djl.Model;
import ai.djl.basicmodelzoo.basic.Mlp;
import ai.djl.nn.Block;
import ai.djl.nn.BlockFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

public class MlpMnistBlockFactory implements BlockFactory {

    private static final long serialVersionUID = 1L;

    @Override
    public Block newBlock(Model model, Path path, Map<String, ?> map) throws IOException {
        return new Mlp(28 * 28, 10, new int[]{128, 64});
    }
}
