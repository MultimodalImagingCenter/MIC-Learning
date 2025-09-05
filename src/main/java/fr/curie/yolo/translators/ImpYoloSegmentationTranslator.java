package fr.curie.yolo.translators;

import ai.djl.modality.cv.output.BoundingBox;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.output.Mask;
import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.DataType;
import ai.djl.translate.ArgumentsUtil;
import ai.djl.translate.TranslatorContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ImpYoloSegmentationTranslator extends ImpYoloV5Translator{

    private static final int[] AXIS_0 = new int[]{0};
    private static final int[] AXIS_1 = new int[]{1};
    private final float threshold;
    private final float nmsThreshold;

    public ImpYoloSegmentationTranslator(Builder builder) {
        super(builder);
        this.threshold = builder.threshold;
        this.nmsThreshold = builder.nmsThreshold;
        System.out.println("Translator initialized with threshold: " + this.threshold + ", nmsThreshold: " + this.nmsThreshold);
    }

    @Override
    public DetectedObjects processOutput(TranslatorContext ctx, NDList list) {
        // retrieve the tensor that contains the main prediction information
        NDArray pred = list.get(0); // Shape : [4 + num_classes + num_mask_coeffs, num_potential_detections]
        // retrieve the tensor that contains the prototype masks
        NDArray protos = list.get(1); // Shape : [num_mask_coeffs, mask_height, mask_width]

        // find starting position of the mask coefficients
        int maskIndex = this.classes.size() + 4;

        // --- Identify candidates based on max class probability ---
        // identify detections whose max proba is higher then threshold
        NDArray candidates = pred.get("4:" + maskIndex).max(AXIS_0).gt(threshold);

        // --- Convert the bounding box coordinates format to xyxy
        pred = pred.transpose();
        // raw bounding box coordinates
        NDArray sub = pred.get("..., :4", new Object[0]);
        // convert bb coordinates
        sub = this.xywh2xyxy(sub);
        // constructs the pred tensor by replacing the original box coordinates with the new format
        pred = sub.concat(pred.get("..., 4:", new Object[0]), -1);

        // --- Get a list of feature  for each candidate ---
        // Filter pred to keep only candidate
        pred = pred.get(candidates);
        if (pred.isEmpty()) {
            System.out.println("pred is empty after candidate filtering. Returning empty results.");
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }
        // separate the different components (bounding box, class proba, mask coeff) of the prediction data into distinct NDArray
        NDList split = pred.split(new long[]{4L, (long) maskIndex}, 1);

        // Extracts the first tensor (the bounding boxes)
        NDArray box = split.get(0);
        // find out exactly how many detections remain after the initial confidence filtering
        int numBox = Math.toIntExact(box.getShape().get(0));
        // get the raw coordinate data out of the DJL NDArray structure and into a standard Java array
        float[] buf = box.toFloatArray();

        // extracts the NDArray containing the class probability scores
        NDArray class_proba = split.get(1);
        // Max confidence across classes for each box
        float[] confidences = class_proba.max(AXIS_1).toFloatArray();
        // Class ID with max confidence for each box
        long[] ids = class_proba.argMax(1).toLongArray();

        // --- Prepare bb and scores for nms ---
        List<Rectangle> boxes = new ArrayList<>(numBox);
        List<Double> scores = new ArrayList<>(numBox);

        for (int i = 0; i < numBox; ++i) {
            float xPos = buf[i * 4];
            float yPos = buf[i * 4 + 1];
            float w = buf[i * 4 + 2] - xPos;
            float h = buf[i * 4 + 3] - yPos;
            Rectangle rect = new Rectangle((double) xPos, (double) yPos, (double) w, (double) h);
            boxes.add(rect);
            scores.add((double) confidences[i]);
        }

        // --- Perform NMS and get results as NDArray ---
        List<Integer> nms = Rectangle.nms(boxes, scores, this.nmsThreshold);
        if (nms.isEmpty()) {
            return new DetectedObjects(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        long[] idx = nms.stream().mapToLong(Integer::longValue).toArray();
        // Creates a new NDArray containing the indices of detections that survived NMS.
        NDArray selected = box.getManager().create(idx);

        // --- Generate masks ---
        // Selects the mask coefficients corresponding to the boxes that survived NMS
        NDArray masks = ((NDArray)split.get(2)).get(selected);

        // Mask processing
        long protoShape0 = protos.getShape().get(0); // number of masks coeff
        long protoShape1 = protos.getShape().get(1); // H
        long protoShape2 = protos.getShape().get(2); // W
        int maskW = Math.toIntExact(protoShape2);
        int maskH = Math.toIntExact(protoShape1);
        long numCoeffsProto = protoShape0;
        // flattens the spatial dimensions (H and W) of each prototype mask into a single long vector (maskH * maskW)
        protos = protos.reshape(new long[]{numCoeffsProto, (long) maskH * (long) maskW});


        // Matrix multiplication: [num_selected_detections, num_coeffs] @ [num_coeffs_proto, H*W]
        // This works if num_coeffs == num_coeffs_proto
        masks = masks.matMul(protos);
        // Reshape and finalize masks
        masks = masks.reshape(new long[]{(long) nms.size(), (long) maskH, (long) maskW});
        masks = masks.gt(0.0F); // Thresholding
        // Convert boolean to float
        masks = masks.toType(DataType.FLOAT32, true);
        // convert to a flat array
        float[] maskArray = masks.toFloatArray();

        // --- Prepare creation of DetectedObjects ---
        // Select final boxes (kept after nms) using NMS indices
        box = box.get(selected);
        buf = box.toFloatArray();

        List<String> retClasses = new ArrayList<>();
        List<Double> retProbs = new ArrayList<>();
        List<BoundingBox> retBB = new ArrayList<>();

        // --- Create DetectedObjets ---
        for (int i = 0; i < idx.length; ++i) {
            // Gets the coordinate for the i-th surviving box + normalize
            float x = buf[i * 4] / (float)this.width;
            float y = buf[i * 4 + 1] / (float)this.height;
            float w = buf[i * 4 + 2] / (float) this.width - x;
            float h = buf[i * 4 + 3] / (float) this.height - y;

            // Get class and score using the original index from the NMS list
            int id_in_original_list = nms.get(i); // Get the original index (before NMS filtering) that was kept
            long classId_long = ids[id_in_original_list];
            double probability = (double) confidences[id_in_original_list];
            String className = (classId_long >= 0 && classId_long < this.classes.size()) ? this.classes.get((int) classId_long) : "unknown_" + classId_long;
            retClasses.add(className);
            retProbs.add(probability);

            // Extract mask for this detection
            float[][] maskFloat = new float[maskH][maskW];

            int maskOffset = i * maskH * maskW; // Offset for the i-th mask in the flat array -> different from the original code !
            for (int j = 0; j < maskH; ++j) {
                int sourcePos = maskOffset + j * maskW;
                System.arraycopy(maskArray, sourcePos, maskFloat[j], 0, maskW);
            }

             /*
             // Original code
            for(int j = 0; j < maskH; ++j) {
                System.arraycopy(maskArray, j * maskW, maskFloat[j], 0, maskW);
            }
             */

            // Create Mask object
            Mask bb = new Mask(x, y, w, h, maskFloat, true);
            retBB.add(bb);
        }

        return new DetectedObjects(retClasses, retProbs, retBB);
    }

    // helper method + prints
    private NDArray xywh2xyxy(NDArray array) {
        NDArray xy = array.get("..., :2", new Object[0]);
        NDArray wh = array.get("..., 2:", new Object[0]).div(2);
        return xy.sub(wh).concat(xy.add(wh), -1);
    }

    public DetectedObjects perform_nms_by_class(
            int imageWidth,
            int imageHeight,
            List<Rectangle> boxes,
            List<Integer> classIds,
            List<Float> scores) {
        return nms(imageWidth, imageHeight, boxes, classIds, scores);
    }

    // --- Builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(Map<String, ?> arguments) {
        Builder builder = new Builder();

        // handle threshold and nms threshold private
        if (arguments != null) {
            builder.optThreshold(ArgumentsUtil.floatValue(arguments, "threshold", builder.threshold));
            builder.optNmsThreshold(ArgumentsUtil.floatValue(arguments, "nmsThreshold", builder.nmsThreshold));
            builder.configCommon(arguments);
        }
        return builder;
    }

    // Inner Builder class
    public static class Builder extends ImpYoloV5Translator.Builder {

        // Default value
        float threshold = 0.5f;
        float nmsThreshold = 0.7f;

        Builder() {
            super();
        }

        @Override
        public Builder optThreshold(float threshold) {
            this.threshold = threshold;
            super.optThreshold(threshold);
            return self();
        }

        @Override
        public Builder optNmsThreshold(float nmsThreshold) {
            this.nmsThreshold = nmsThreshold;
            super.optNmsThreshold(nmsThreshold);
            return self();
        }

        @Override
        protected Builder self() {
            return this;
        }

        // build() method returns an instance of the DebugOriginalYoloSegmentationTranslator
        @Override
        public ImpYoloSegmentationTranslator build() {
            validate();
            return new ImpYoloSegmentationTranslator(this);
        }

        protected void configCommon(Map<String, ?> arguments) {
            if (arguments != null) {
                super.configPreProcess(arguments);
                super.configPostProcess(arguments);
            }
        }
    }
}
