package fr.curie.miclearning.prediction.translator.basetranslator;

import ai.djl.Model;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.transform.CenterCrop;
import ai.djl.modality.cv.transform.CenterFit;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.translator.BaseImageTranslator;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.index.NDIndex;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.*;
import ai.djl.util.Utils;
import ij.IJ;
import ij.ImagePlus;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static fr.curie.miclearning.tools.ImageJUtils.ImagePlusToNDArray;
import static ij.IJ.runMacro;

/**
 * A base translator for handling ImageJ's ImagePlus objects.
 *
 * <p>Base translator, provides default image pre-processing
 * based on BaseImageTranslator (<a href="https://github.com/deepjavalibrary/djl/blob/master/api/src/main/java/ai/djl/modality/cv/translator/BaseImageTranslator.java">Base image translator</a>),
 * but with ImagePlus as the input type.
 *
 * @param <T> the output object type
 */
public abstract class BaseImagePlusTranslator<T> implements Translator<ImagePlus, T> {
    private static final Logger log = LoggerFactory.getLogger(BaseImagePlusTranslator.class);
    protected Pipeline pipeline;
    private Batchifier batchifier;
    protected int width;
    protected int height;
    protected Image.Flag flag;
    protected String preProcessmacroName;
    protected String postProcessmacroName;
    protected String engineName;
    protected String modelDir;

    /**
     * Constructs an ImagePlusTranslator with the provided builder.
     *
     * @param builder the data to build with
     */
    public BaseImagePlusTranslator(BaseImpBuilder<?> builder) {
        this.pipeline = builder.getPipeline();
        this.batchifier = builder.getBatchifier();
        this.width = builder.getWidth();
        this.height = builder.getHeight();
        this.flag=builder.getFlag();
        this.preProcessmacroName = builder.getPreProcessMacroName();
        this.postProcessmacroName = builder.getPostProcessMacroName();
    }

    /** {@inheritDoc} */
    @Override
    public NDList processInput(TranslatorContext ctx, ImagePlus input) throws IOException {
        this.engineName = ctx.getNDManager().getEngine().getEngineName();
        log.debug("Translator : input dimensions = {}", Arrays.toString(input.getDimensions()));
        // 1. run pre-processing macro
        if (preProcessmacroName != null && !preProcessmacroName.isEmpty()) {
            System.out.println("Running macro '" + preProcessmacroName + "' on image: " + input.getTitle());
            applyMacro(ctx, input, preProcessmacroName);
        }

        // 2. Convert the ImagePlus to an NDArray
        //IJ.log("converting image of dimensions " + Arrays.toString(input.getDimensions()) + " and bit depth " + input.getBitDepth() +" to NDArray");
        NDArray array = ImagePlusToNDArray(input, ctx.getNDManager());

        // 3. Apply the pre-processing pipeline that was configured by the builder
        // + convert to NDList
        NDList list =  pipeline.transform(new NDList(array));
        Shape shape = list.get(0).getShape();


        // 4. Adjust dimensions if needed
        // if shape = HW, need to add a new dimension : CHW
        if(shape.dimension() == 2){
            NDArray originalArray = list.get(0);
            NDArray unsqueezedArray = originalArray.expandDims(0);
            list = new NDList(unsqueezedArray);
            originalArray.close();
            shape = list.get(0).getShape();
        }

        // if it has not been done inside the pipeline, change shape from HWC to CHW
        // assuming that C < H
        if (shape.get(2) < shape.get(0)){
            NDArray originalArray = list.get(0);
            long[] dims = shape.getShape();
            NDArray reshapedArray = originalArray.reshape(new Shape(dims[2],dims[0],dims[1]));
            list = new NDList(reshapedArray);
            originalArray.close();
            shape = list.get(0).getShape();
        }

        // if grayscale, check that channel dimension = 1
        // (we only consider here the case where we need to change number of channels from 3 to 1)
        if (flag== Image.Flag.GRAYSCALE){
            if (shape.get(0)==3){
                NDArray originalArray = list.get(0);
                NDArray slicedArray = originalArray.get(new NDIndex("0:1"));
                list = new NDList(slicedArray);
            }
        }

        // if color, check that channel dimension = 3
        // we only consider here the case where we need to change number of channels from 1 to 3
        else if (flag == Image.Flag.COLOR){
            if (shape.get(0)==1){
                NDArray originalArray = list.get(0);
                NDArray multpliedArray = originalArray.repeat(0,3);
                list = new NDList(multpliedArray);
            }
        }

        // if engine is tensorflow, input shape must be HWC
        shape = list.get(0).getShape();
        if (engineName.equals("TensorFlow")){
            if (shape.get(0) < shape.get(1)){
                NDArray originalArray = list.get(0);
                long[] dims = shape.getShape();
                NDArray reshapedArray = originalArray.reshape(new Shape(dims[1],dims[2],dims[0] ));
                list = new NDList(reshapedArray);
                originalArray.close();
            }
        }

        shape = list.get(0).getShape();

        // 5. Optional context info for post-process
        ctx.setAttachment("width", input.getWidth());
        ctx.setAttachment("height", input.getHeight());
        ctx.setAttachment("processedWidth", shape.get(shape.dimension() - 1));
        ctx.setAttachment("processedHeight", shape.get(shape.dimension() - 2));

        return list;
    }

    /** {@inheritDoc} */
    @Override
    public Batchifier getBatchifier() {
        return batchifier;
    }

    protected void applyMacro(TranslatorContext ctx, ImagePlus imp, String macroName) throws IOException {
        String macroContent = ctx.getModel().getArtifact(macroName, is -> {
            try {
                return IOUtils.toString(is, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        IJ.log("running macro : " + macroName);
        runMacro(macroContent);
    }


    /**
     * A builder to extend for all classes extending the {@link BaseImagePlusTranslator}.
     *
     * @param <T> the concrete builder type
     */
    @SuppressWarnings("rawtypes")
    public abstract static class BaseImpBuilder<T extends BaseImpBuilder>
            extends BaseImageTranslator.BaseBuilder<T> {

        protected String preProcessMacroName;
        protected String postProcessMacroName;

        /**
         * Sets the name of the ImageJ macro to run during pre-processing.
         *
         * @param preProcessMacroName the name of the macro
         * @return this builder
         */
        public T optPreProcessMacro(String preProcessMacroName) {
            this.preProcessMacroName = preProcessMacroName;
            return self();
        }

        /**
         * Sets the name of the ImageJ macro to run during post-processing.
         *
         * @param postProcessMacroName the name of the macro
         * @return this builder
         */
        public T optPostProcessMacro(String postProcessMacroName) {
            this.postProcessMacroName = postProcessMacroName;
            return self();
        }

        @Override
        public void configPreProcess(Map<String, ?> arguments) {
            super.configPreProcess(arguments);
            if (arguments.containsKey("preProcessingMacro")) {
                this.preProcessMacroName = (String) arguments.get("preProcessingMacro");
            }
        }
        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
            if (arguments.containsKey("postProcessingMacro")) {
                this.postProcessMacroName = (String) arguments.get("postProcessingMacro");
            }
        }

        @Override
        protected abstract T self();

        public Pipeline getPipeline() {
            return pipeline;
        }

        public Batchifier getBatchifier() {
            return batchifier;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public String getPreProcessMacroName() {
            return preProcessMacroName;
        }
        public String getPostProcessMacroName() {
            return postProcessMacroName;
        }

        public Image.Flag getFlag(){
            return flag;
        }

    }

    // directly copied from https://github.com/deepjavalibrary/djl/blob/9d720beeac2935da352369c6b2c45486ed3b1996/api/src/main/java/ai/djl/modality/cv/translator/BaseImageTranslator.java#L289
    /** A Builder to construct a {@code ImpImageClassificationTranslator}. */
    @SuppressWarnings("rawtypes")
    public abstract static class ImpClassificationBuilder<T extends BaseImpBuilder>
            extends BaseImpBuilder<T> {

        public SynsetLoader synsetLoader;

        /**
         * Sets the name of the synset file listing the potential classes for an image.
         *
         * @param synsetArtifactName a file listing the potential classes for an image
         * @return the builder
         */
        public T optSynsetArtifactName(String synsetArtifactName) {
            synsetLoader = new SynsetLoader(synsetArtifactName);
            return self();
        }

        /**
         * Sets the URL of the synset file.
         *
         * @param synsetUrl the URL of the synset file
         * @return the builder
         */
        public T optSynsetUrl(String synsetUrl) {
            try {
                this.synsetLoader = new SynsetLoader(new URL(synsetUrl));
            } catch (MalformedURLException e) {
                throw new IllegalArgumentException("Invalid synsetUrl: " + synsetUrl, e);
            }
            return self();
        }

        /**
         * Sets the potential classes for an image.
         *
         * @param synset the potential classes for an image
         * @return the builder
         */
        public T optSynset(List<String> synset) {
            synsetLoader = new SynsetLoader(synset);
            return self();
        }

        /** {@inheritDoc} */
        @Override
        protected void validate() {
            super.validate();
            if (synsetLoader == null) {
                synsetLoader = new SynsetLoader("synset.txt");
            }
            boolean hasCrop = false;
            boolean sizeMismatch = false;
            for (Transform transform : pipeline.getTransforms()) {
                if (transform instanceof Resize) {
                    Resize resize = (Resize) transform;
                    if (width != resize.getWidth() || height != resize.getHeight()) {
                        sizeMismatch = true;
                    }
                } else if (transform instanceof CenterCrop || transform instanceof CenterFit) {
                    hasCrop = true;
                }
            }
            if (sizeMismatch && !hasCrop) {
                throw new IllegalArgumentException("resized image has mismatched target size");
            }
        }

        /** {@inheritDoc} */
        @Override
        public void configPostProcess(Map<String, ?> arguments) {
            super.configPostProcess(arguments);
            String synset = (String) arguments.get("synset");
            if (synset != null) {
                optSynset(Arrays.asList(synset.split(",")));
            }
            String synsetUrl = (String) arguments.get("synsetUrl");
            if (synsetUrl != null) {
                optSynsetUrl(synsetUrl);
            }
            String synsetFileName = (String) arguments.get("synsetFileName");
            if (synsetFileName != null) {
                optSynsetArtifactName(synsetFileName);
            }

        }
    }

    protected static final class SynsetLoader {

        private String synsetFileName;
        private URL synsetUrl;
        private List<String> synset;

        public SynsetLoader(List<String> synset) {
            this.synset = synset;
        }
        public SynsetLoader(URL synsetUrl) {
            this.synsetUrl = synsetUrl;
        }
        public SynsetLoader(String synsetFileName) {
            this.synsetFileName = synsetFileName;
        }

        public List<String> load(Model model) throws IOException {
            // if class names are directly given
            if (synset != null) {
                return synset;

            // else : try to load from sysnsetUrl
            } else if (synsetUrl != null) {
                try (InputStream is = synsetUrl.openStream()) {
                    return Utils.readLines(is);
                }
            }

            // else : try to load from file inside model folder
            return model.getArtifact(synsetFileName, Utils::readLines);
        }
    }


}
