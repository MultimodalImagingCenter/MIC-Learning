package fr.curie.miclearning.tools.detection;

import ai.djl.modality.cv.output.Rectangle;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.types.Shape;
import ai.djl.util.JsonUtils;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

public class MaskByte extends Rectangle {
    private static final long serialVersionUID = 1L;
    private byte[][] binaryMask;
    private boolean fullImageMask;

    public MaskByte(double x, double y, double width, double height, byte[][] binaryMask) {
        this(x, y, width, height, binaryMask, false);
    }


    public MaskByte(double x, double y, double width, double height, byte[][] binaryMask, boolean fullImageMask) {
        super(x, y, width, height);
        this.binaryMask = binaryMask;
        this.fullImageMask = fullImageMask;
    }

    public byte[][] getMask() {
        return this.binaryMask;
    }

    public boolean isFullImageMask() {
        return this.fullImageMask;
    }

    public JsonObject serialize() {
        JsonObject ret = super.serialize();
        if (this.fullImageMask) {
            ret.add("fullImageMask", new JsonPrimitive(true));
        }

        ret.add("mask", JsonUtils.GSON.toJsonTree(this.binaryMask));
        return ret;
    }

    public static byte[][] toBinaryMask(NDArray array) {
        Shape maskShape = array.getShape();
        int height = (int)maskShape.get(0);
        int width = (int)maskShape.get(1);
        byte[] flattened = array.toByteArray();
        byte[][] mask = new byte[height][width];

        for(int i = 0; i < height; ++i) {
            System.arraycopy(flattened, i * width, mask[i], 0, width);
        }
        return mask;
    }


}
