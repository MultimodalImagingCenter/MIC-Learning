package fr.curie.bioimage.translators;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.TranslatorContext;
import fr.curie.basetranslators.BaseImagePlusTranslator;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.process.FloatProcessor;

import java.util.Map;



public class BioImageTranslator extends BaseImagePlusTranslator<ImagePlus> {

    /**
     * Constructs an ImagePlusTranslator with the provided builder.
     *
     * @param builder the data to build with
     */
    public BioImageTranslator(BaseImpBuilder<?> builder) {
        super(builder);
    }

    /**
     * Create a stack of image processor with one slice for each channel of the raw output
     *
     */
    @Override
    public ImagePlus processOutput(TranslatorContext ctx, NDList ndList) throws Exception {
        try (NDArray rawOutputs = ndList.get(0).duplicate()) {
            //IJ.log("Raw output shape from model: " + rawOutputs.getShape());

            try (NDArray outputs = get3DTensor(rawOutputs)) {

                Shape shape = outputs.getShape();
                long[] dims = shape.getShape();

                int channels;
                int height;
                int width;
                boolean isCHW;

                //  determine tensor format (CHW vs. HWC)
                //  Assuming that the channel dimension is the smallest of the three.
                if (dims[0] < dims[1] && dims[0] < dims[2]) {
                    isCHW = true;
                    channels = (int) dims[0];
                    height = (int) dims[1];
                    width = (int) dims[2];
                } else {
                    isCHW = false;
                    height = (int) dims[0];
                    width = (int) dims[1];
                    channels = (int) dims[2];
                }
                IJ.log("Interpreted as " + (isCHW ? "CHW" : "HWC") + " with " + channels + " channels.");
                ImageStack stack = new ImageStack(width, height);

                // Iterate through each channel, create a processor, and add it to the stack.
                for (int c = 0; c < channels; c++) {
                    try (
                            // Step 1: Slice the tensor to get the channel view.
                            NDArray channelData = isCHW ? outputs.get(c) : outputs.get("...," + c);
                            NDArray contiguousChannelData = channelData.duplicate()
                    ) {

                        // Convert the 2D channel data into a 1D float array for ImageJ.
                        float[] pixels = contiguousChannelData.toFloatArray();

                        FloatProcessor fp = new FloatProcessor(width, height);
                        fp.setPixels(pixels);

                        // Add the processor to the stack
                        stack.addSlice("Channel " + (c + 1), fp);

                    }
                }
                ImagePlus imp = new ImagePlus("Result Stack", stack);

                // apply post processing macro
                if (postProcessmacroName != null && !postProcessmacroName.isEmpty()) {
                    imp.show();
                    System.out.println("Running post processing macro '" + postProcessmacroName + "' on image: " + imp.getTitle());
                    applyMacro(ctx, imp, postProcessmacroName);
                    imp.close();
                }

                return imp;

            }
        }
    }

    /**
     * Helper function to handle 3D and 4D tensors, returning a 3D tensor view.
     */
    private NDArray get3DTensor(NDArray rawOutputs) {
        long[] dims = rawOutputs.getShape().getShape();
        if (dims.length == 4) {
            if (dims[0] != 1) {
                throw new IllegalArgumentException("Unsupported batch size. Expected 1, got " + dims[0]);
            }
            // Squeeze out the batch dimension. This returns a view, not a copy
            // The original rawOutputs still holds the memory.
            return rawOutputs.squeeze(0);
        } else if (dims.length == 3) {
            // Return the original array itself.
            return rawOutputs;
        } else {
            throw new IllegalArgumentException("Unsupported NDArray shape. Expected 3 or 4 dimensions, got " + dims.length);
        }
    }

    public static Builder builder() {
        return new Builder();
    }


    public static Builder builder(Map<String, ?> arguments) {
        Builder builder = new Builder();
        builder.configPreProcess(arguments);
        builder.configPostProcess(arguments);
        return builder;
    }

    public static class Builder extends BaseImpBuilder<Builder> {
        public Builder() {}

        @Override
        protected Builder self() {
            return this;
        }

        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
        }
        @Override
        public void configPreProcess(Map<String, ?> arguments) {
            super.configPreProcess(arguments);
        }

        public BioImageTranslator build() {
            validate();
            return new BioImageTranslator(this);
        }
    }


}
