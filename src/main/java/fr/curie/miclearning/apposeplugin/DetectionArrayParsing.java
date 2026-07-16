package fr.curie.miclearning.apposeplugin;

import org.apposed.appose.NDArray;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

/**
 * Helpers for turning Appose {@link NDArray} outputs (boxes/masks/scores/ids) coming back
 * from the SAM3 python scripts into plain Java arrays
 */
public class DetectionArrayParsing {
    private DetectionArrayParsing() {}

    public static double[][] extractBoxes(NDArray boxes, int numResults) {
        double[][] boxesArray = new double[numResults][4];
        DoubleBuffer buf = boxes.buffer().asDoubleBuffer();
        buf.rewind();
        for (int i = 0; i < numResults; i++) {
            buf.get(boxesArray[i]);
        }
        return boxesArray;
    }

    public static byte[][][] extractMasks(NDArray masks, int numResults) {
        long[] shape = masks.shape().toLongArray();
        int height = shape.length == 2 ? (int) shape[0] : (int) shape[1];
        int width = shape.length == 2 ? (int) shape[1] : (int) shape[2];

        byte[][][] masksArray = new byte[numResults][height][width];
        ByteBuffer buf = masks.buffer();
        buf.rewind();
        for (int i = 0; i < numResults; i++) {
            for (int y = 0; y < height; y++) {
                buf.get(masksArray[i][y]);
            }
        }
        return masksArray;
    }

    public static double[] extractScores(NDArray scores, int numResults) {
        double[] probaArray = new double[numResults];
        DoubleBuffer buf = scores.buffer().asDoubleBuffer();
        buf.rewind();
        buf.get(probaArray);
        return probaArray;
    }

    // for prompt ids and object ids
    public static int[] extractIntArray(NDArray ids, int numResults) {
        int[] idsArray = new int[numResults];
        IntBuffer buf = ids.buffer().asIntBuffer();
        buf.rewind();
        buf.get(idsArray);
        return idsArray;
    }

}