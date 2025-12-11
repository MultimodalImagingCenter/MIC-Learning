package fr.curie.miclearning.plugin.classification.blockfactory;

import ai.djl.Model;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.*;
import ai.djl.nn.convolutional.Conv2d;
import ai.djl.nn.core.Linear;
import ai.djl.nn.pooling.Pool;
import ai.djl.translate.ArgumentsUtil;

import java.nio.file.Path;
import java.util.Map;

public class LeNetBlockFactory implements BlockFactory {
    private static final long serialVersionUID = 1L;

    /** {@inheritDoc} */
    @Override
    public Block newBlock(Model model, Path modelPath, Map<String, ?> arguments) {
        int output = ArgumentsUtil.intValue(arguments, "output", 10);
        SequentialBlock block = new SequentialBlock();
        block
                .add(Conv2d.builder()
                        .setKernelShape(new Shape(5, 5))
                        .optPadding(new Shape(2, 2))
                        .optBias(false)
                        .setFilters(6)
                        .build())
                .add(Activation::sigmoid)
                .add(Pool.avgPool2dBlock(new Shape(5, 5), new Shape(2, 2), new Shape(2, 2)))
                .add(Conv2d.builder()
                        .setKernelShape(new Shape(5, 5))
                        .setFilters(16).build())
                .add(Activation::sigmoid)
                .add(Pool.avgPool2dBlock(new Shape(5, 5), new Shape(2, 2), new Shape(2, 2)))
                .add(Blocks.batchFlattenBlock())
                .add(Linear
                        .builder()
                        .setUnits(120)
                        .build())
                .add(Activation::sigmoid)
                .add(Linear
                        .builder()
                        .setUnits(84)
                        .build())
                .add(Activation::sigmoid)
                .add(Linear
                        .builder()
                        .setUnits(output)
                        .build());
        return block;
    }
}