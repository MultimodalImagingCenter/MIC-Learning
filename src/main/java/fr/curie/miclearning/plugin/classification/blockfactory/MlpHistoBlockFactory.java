package fr.curie.miclearning.plugin.classification.blockfactory;

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
    private static final int IMAGE_WIDTH = 224;
    private static final int IMAGE_HEIGHT = 224;
    private static final int CHANNELS = 3;
    static final int num_of_classes = 3 ;

    @Override
    public Block newBlock(Model model, Path path, Map<String, ?> map) throws IOException {
        return new Mlp(IMAGE_HEIGHT*IMAGE_WIDTH*CHANNELS, num_of_classes, new int[]{128, 64});
    }
}
