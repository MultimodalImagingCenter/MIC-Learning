package fr.curie.miclearning.apposeplugin;

import ai.djl.modality.cv.output.BoundingBox;
import ij.IJ;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;

import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MaskByte;
import fr.curie.miclearning.tools.detection.ProcessedDetection;

/**
 * Drains per-frame detection results from a {@link BlockingQueue} (as produced by
 * {@link Sam3PythonRunner}) and registers them into a {@link MultiFrameDataManager}.
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class DetectionResultConsumer implements Callable<Void> {

    // signal pushed onto the queue exactly once, on every terminal python task outcome. */
    public static final Map<String, Object> END_SIGNAL = Collections.unmodifiableMap(new HashMap<>());

    private final BlockingQueue<Map<String, Object>> resultsQueue;
    private final MultiFrameDataManager mfdManager;
    private final Map<String, Integer> classIdMap;
    private final String textPrompt;
    private final ImagePlus imp;

    public DetectionResultConsumer(BlockingQueue<Map<String, Object>> resultsQueue, MultiFrameDataManager mfdManager,
                                   Map<String, Integer> classIdMap, String textPrompt, ImagePlus imp) {
        this.resultsQueue = resultsQueue;
        this.mfdManager = mfdManager;
        this.classIdMap = classIdMap;
        this.textPrompt = textPrompt;
        this.imp = imp;
    }

    @Override
    public Void call() throws InterruptedException {
        while (true) {
            Map<String, Object> info = resultsQueue.take();
            if (info == END_SIGNAL) {
                IJ.log("All frames processed.");
                break;
            }
            try {
                processFrame(info);
            } catch (RuntimeException e) {
                IJ.log("ERROR processing a frame, skipping it: " + e);
            }
        }
        return null;
    }

    private void processFrame(Map<String, Object> info) {
        int numResults = ((Number) info.get("n_results")).intValue();
        int frameIdx = ((Number) info.get("frame_idx")).intValue();

        if (numResults == 0) {
            return; // nothing detected on this frame
        }

        NDArray outputBoxes = (NDArray) info.get("boxes");
        NDArray outputMasks = (NDArray) info.get("masks");
        NDArray outputScores = (NDArray) info.get("scores");
        NDArray outputIds = (NDArray) info.get("object_ids");

        if (outputBoxes == null || outputMasks == null || outputScores == null || outputIds == null) {
            throw new IllegalStateException(
                    "Missing output arrays (boxes, masks, scores or ids) from Python for frame " + (frameIdx + 1));
        }

        double[][] boxes = extractBoxes(outputBoxes, numResults);
        byte[][][] masks = extractMasks(outputMasks, numResults);
        double[] scores = extractScores(outputScores, numResults);
        int[] ids = extractIds(outputIds, numResults);

        List<String> classNames = new ArrayList<>(numResults);
        List<Double> probabilities = new ArrayList<>(numResults);
        List<BoundingBox> boundingBoxes = new ArrayList<>(numResults);
        for (int i = 0; i < numResults; i++) {
            classNames.add(textPrompt);
            double[] coord = boxes[i];
            boundingBoxes.add(new MaskByte(coord[0], coord[1], coord[2], coord[3], masks[i], true));
            probabilities.add(scores[i]);
        }

        DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
        List<ProcessedDetection> processedDetections = DetectionUtils.processDetections(imp, detectedObjects, classIdMap);

        if (processedDetections.isEmpty()) {
            IJ.log(" --- No valid detections were processed for frame " + (frameIdx + 1));
            return;
        }

        for (int i = 0; i < numResults; i++) {
            mfdManager.registerDetection(frameIdx, processedDetections.get(i), ids[i], true);
        }
    }

    private static double[][] extractBoxes(NDArray outputBoxes, int numResults) {
        // Initialize the 2D array [Number of Boxes][4 Coordinates]
        double[][] boxesArray = new double[numResults][4];
        // extract bounding boxes coordinates
        DoubleBuffer buf = outputBoxes.buffer().asDoubleBuffer();
        buf.rewind();
        for (int i = 0; i < numResults; i++) {
            buf.get(boxesArray[i]);
        }
        return boxesArray;
    }

    private static byte[][][] extractMasks(NDArray outputMasks, int numResults) {
        long[] shape = outputMasks.shape().toLongArray();
        int height = shape.length == 2 ? (int) shape[0] : (int) shape[1];
        int width = shape.length == 2 ? (int) shape[1] : (int) shape[2];

        byte[][][] masksArray = new byte[numResults][height][width];
        ByteBuffer buf = outputMasks.buffer();
        buf.rewind();
        for (int i = 0; i < numResults; i++) {
            for (int y = 0; y < height; y++) {
                buf.get(masksArray[i][y]);
            }
        }
        return masksArray;
    }

    private static double[] extractScores(NDArray outputScores, int numResults) {
        double[] probaArray = new double[numResults];
        DoubleBuffer buf = outputScores.buffer().asDoubleBuffer();
        buf.rewind();
        buf.get(probaArray);
        return probaArray;
    }

    private static int[] extractIds(NDArray outputIds, int numResults) {
        int[] idsArray = new int[numResults];
        IntBuffer buf = outputIds.buffer().asIntBuffer();
        buf.rewind();
        buf.get(idsArray);
        return idsArray;
    }
}
