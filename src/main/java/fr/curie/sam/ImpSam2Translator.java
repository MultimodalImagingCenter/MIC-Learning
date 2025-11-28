package fr.curie.sam;

import ai.djl.Model;
import ai.djl.ModelException;
import ai.djl.inference.Predictor;

import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.*;
import ai.djl.modality.cv.transform.Normalize;
import ai.djl.modality.cv.transform.Resize;
import ai.djl.modality.cv.transform.ToTensor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.DataType;
import ai.djl.ndarray.types.Shape;
import ai.djl.translate.*;
import ai.djl.util.JsonUtils;
import com.google.gson.annotations.SerializedName;
import fr.curie.sam.ImpSam2Translator.ImpSam2Input;
import ij.ImagePlus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static fr.curie.tools.ImageJUtils.ImagePlusToNDArray;

public class ImpSam2Translator implements NoBatchifyTranslator<ImpSam2Input, DetectedObjects> {
    private static final float[] MEAN = new float[]{0.485F, 0.456F, 0.406F};
    private static final float[] STD = new float[]{0.229F, 0.224F, 0.225F};
    private Pipeline pipeline = new Pipeline();
    private Predictor<NDList, NDList> predictor;
    private String encoderPath;
    private String encodeMethod;
    protected String preProcessmacroName;

    public ImpSam2Translator(Builder builder) {
        this.pipeline.add(new Resize(1024, 1024));
        this.pipeline.add(new ToTensor());
        this.pipeline.add(new Normalize(MEAN, STD));
        this.encoderPath = builder.encoderPath;
        this.encodeMethod = builder.encodeMethod;
    }

    /**
     * Helper method to initialize the encoder predictor.
     * Can be called from prepare() (standard flow) or encode() (manual flow).
     */
    private synchronized void ensureEncoderInitialized(Model model) throws IOException, ModelException {
        if (this.predictor != null) {
            return;
        }

        if (encoderPath == null) {
            // PyTorch model
            if (encodeMethod != null) {
                this.predictor = model.newPredictor(new NoopTranslator(null));
                model.getNDManager().attachInternal(NDManager.nextUid(), this.predictor);
            }
            return;
        }

        Path path = Paths.get(encoderPath);
        if (!path.isAbsolute() && Files.notExists(path)) {
            path = model.getModelPath().resolve(encoderPath);
        }
        if (!Files.exists(path)) {
            throw new IOException("encoder model not found: " + encoderPath);
        }

        NDManager manager = model.getNDManager(); // only line changed from original "prepare"
        // Create a new internal model for the encoder
        Model encoder = manager.getEngine().newModel("encoder", manager.getDevice());
        encoder.load(path);

        this.predictor = encoder.newPredictor(new NoopTranslator(null));
        model.getNDManager().attachInternal(NDManager.nextUid(), this.predictor);
        model.getNDManager().attachInternal(NDManager.nextUid(), encoder);
    }

    @Override
    public void prepare(TranslatorContext ctx) throws IOException, ModelException {
        ensureEncoderInitialized(ctx.getModel());
    }

    /**
     * Manually run the encoder.
     */
    public NDList encode(Model model, ImagePlus image, NDManager manager) throws IOException, ModelException, Exception {
        // Ensure initialization
        ensureEncoderInitialized(model);

        if (this.predictor == null) {
            throw new IllegalStateException("Encoder not configured in Translator");
        }

        // Pre-process
        NDArray array = ImagePlusToNDArray(image, manager);
        array = pipeline.transform(new NDList(array)).get(0).expandDims(0);

        // Run Prediction
        NDList embeddings;
        if (encodeMethod == null) {
            embeddings = predictor.predict(new NDList(array));
        } else {
            NDArray placeholder = manager.create("");
            placeholder.setName("module_method:" + encodeMethod);
            embeddings = predictor.predict(new NDList(placeholder, array));
        }

        // Attach to manager to keep alive
        embeddings.attach(manager);
        return embeddings;
    }


    public NDList processInput(TranslatorContext ctx, ImpSam2Input input) throws Exception {
        ImagePlus image = input.getImage();
        int width = image.getWidth();
        int height = image.getHeight();
        ctx.setAttachment("width", width);
        ctx.setAttachment("height", height);

        NDManager manager = ctx.getNDManager();

        // encode locations
        float[] buf = input.toLocationArray(width, height);
        NDArray locations = manager.create(buf, new Shape(new long[]{1L, (long)(buf.length / 2), 2L}));
        NDArray labels = manager.create(input.getLabels());

        NDList embeddings;
        // check for already encoded image
        if (input.getFeatures() != null) {
            embeddings = input.getFeatures();
        } else {
            NDArray array = ImagePlusToNDArray(image, manager);
            array = pipeline.transform(new NDList(array)).get(0).expandDims(0);

            if (predictor == null) {
                return new NDList(array, locations, labels);
            }

            if (encodeMethod == null) {
                embeddings = predictor.predict(new NDList(array));
            } else {
                NDArray placeholder = manager.create("");
                placeholder.setName("module_method:" + encodeMethod);
                embeddings = predictor.predict(new NDList(placeholder, array));
            }
        }


        NDArray mask = manager.zeros(new Shape(new long[]{1L, 1L, 256L, 256L}));
        NDArray hasMask = manager.zeros(new Shape(new long[]{1L}));

        for(NDArray arr : embeddings) {
            arr.setName((String)null);
        }

        return new NDList(new NDArray[]{(NDArray)embeddings.get(2), (NDArray)embeddings.get(0), (NDArray)embeddings.get(1), locations, labels, mask, hasMask});


    }

    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        NDArray logits = (NDArray)list.get(0);
        NDArray scores = ((NDArray)list.get(1)).squeeze(0);

        long best = scores.argMax().getLong(new long[0]);

        int width = (Integer)ctx.getAttachment("width");
        int height = (Integer)ctx.getAttachment("height");
        long[] size = new long[]{(long)height, (long)width};

        int mode = Image.Interpolation.BILINEAR.ordinal();

        logits = logits.getNDArrayInternal().interpolation(size, mode, false);
        NDArray masks = logits.gt(0.0F).squeeze(0);

        float[][] dist = Mask.toMask(masks.get(new long[]{best}).toType(DataType.FLOAT32, true));
        Mask mask = new Mask((double)0.0F, (double)0.0F, (double)width, (double)height, dist, true);
        double probability = (double)scores.getFloat(new long[]{best});
        List<String> classes = Collections.singletonList("");
        List<Double> probabilities = Collections.singletonList(probability);
        List<BoundingBox> boxes = Collections.singletonList(mask);
        return new DetectedObjects(classes, probabilities, boxes);
    }

    public static Builder builder() {
        return builder(Collections.emptyMap());
    }

    public static Builder builder(Map<String, ?> arguments) {
        return new Builder(arguments);
    }

    public static class Builder {
        String encoderPath;
        String encodeMethod;

        Builder(Map<String, ?> arguments) {
            this.encoderPath = ArgumentsUtil.stringValue(arguments, "encoder");
            this.encodeMethod = ArgumentsUtil.stringValue(arguments, "encode_method");
        }

        public Builder optEncoderPath(String encoderPath) {
            this.encoderPath = encoderPath;
            return this;
        }

        public Builder optEncodeMethod(String encodeMethod) {
            this.encodeMethod = encodeMethod;
            return this;
        }

        public ImpSam2Translator build() {
            return new ImpSam2Translator(this);
        }
    }

    public static final class ImpSam2Input {
        private ImagePlus image;
        private Point[] points;
        private int[] labels;
        private boolean visualize;
        private NDList features;

        public ImpSam2Input(ImagePlus image, Point[] points, int[] labels) {
            this(image, points, labels, false);
        }

        public ImpSam2Input(ImagePlus image, Point[] points, int[] labels, boolean visualize) {
            this.image = image;
            this.points = points;
            this.labels = labels;
            this.visualize = visualize;
        }

        public ImagePlus getImage() {
            return this.image;
        }

        public boolean isVisualize() {
            return this.visualize;
        }

        public List<Point> getPoints() {
            List<Point> list = new ArrayList();

            for(int i = 0; i < this.labels.length; ++i) {
                if (this.labels[i] < 2) {
                    list.add(this.points[i]);
                }
            }

            return list;
        }

        public List<Rectangle> getBoxes() {
            List<Rectangle> list = new ArrayList();

            for(int i = 0; i < this.labels.length; ++i) {
                if (this.labels[i] == 2) {
                    double width = this.points[i + 1].getX() - this.points[i].getX();
                    double height = this.points[i + 1].getY() - this.points[i].getY();
                    list.add(new Rectangle(this.points[i], width, height));
                }
            }

            return list;
        }

        float[] toLocationArray(int width, int height) {
            float[] ret = new float[this.points.length * 2];
            int i = 0;

            for(Point point : this.points) {
                ret[i++] = (float)point.getX() / (float)width * 1024.0F;
                ret[i++] = (float)point.getY() / (float)height * 1024.0F;
            }

            return ret;
        }

        float[][] getLabels() {
            float[][] buf = new float[1][this.labels.length];

            for(int i = 0; i < this.labels.length; ++i) {
                buf[0][i] = (float)this.labels[i];
            }

            return buf;
        }

        public void setFeatures(NDList features) {
            this.features = features;
        }

        public NDList getFeatures() {
            return features;
        }

        public boolean hasFeatures() {
            return features != null;
        }



        public static Builder builder(ImagePlus image) {
            return new Builder(image);
        }

        public static final class Builder {
            private ImagePlus image;
            private List<Point> points;
            private List<Integer> labels;
            private boolean visualize;

            Builder(ImagePlus image) {
                this.image = image;
                this.points = new ArrayList();
                this.labels = new ArrayList();
            }

            public Builder addPoint(int x, int y) {
                return this.addPoint(x, y, 1);
            }

            public Builder addPoint(int x, int y, int label) {
                return this.addPoint(new Point((double)x, (double)y), label);
            }

            public Builder addPoint(Point point, int label) {
                this.points.add(point);
                this.labels.add(label);
                return this;
            }

            public Builder addBox(int x, int y, int right, int bottom) {
                this.addPoint(new Point((double)x, (double)y), 2);
                this.addPoint(new Point((double)right, (double)bottom), 3);
                return this;
            }

            public Builder visualize() {
                this.visualize = true;
                return this;
            }

            public ImpSam2Input build() {
                Point[] location = (Point[])this.points.toArray(new Point[0]);
                int[] array = this.labels.stream().mapToInt(Integer::intValue).toArray();
                return new ImpSam2Input(this.image, location, array, this.visualize);
            }
        }

        private static final class Location {
            String type;
            int[] data;
            int label;

            private Location() {
            }

            public void setType(String type) {
                this.type = type;
            }

            public void setData(int[] data) {
                this.data = data;
            }

            public void setLabel(int label) {
                this.label = label;
            }
        }

        private static final class Prompt {
            @SerializedName("image_url")
            String image;
            Location[] prompt;
            boolean visualize;

            private Prompt() {
            }

            public void setImage(String image) {
                this.image = image;
            }

            public void setPrompt(Location[] prompt) {
                this.prompt = prompt;
            }

            public void setVisualize(boolean visualize) {
                this.visualize = visualize;
            }
        }}
}
