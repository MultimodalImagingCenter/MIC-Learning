package fr.curie.miclearning.apposeplugin;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.tools.detection.*;
import ij.IJ;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;

/**
 * Collect per-frame detection results from a {@link BlockingQueue},
 * convert them into {@link ProcessedDetection}, and let subclasses store them.
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public abstract class AbstractResultsConsumer implements Callable<Void> {

    // signal pushed onto the queue exactly once, on every terminal python task outcome.
    public static final Map<String, Object> END_SIGNAL = Collections.unmodifiableMap(new HashMap<>());

    protected final BlockingQueue<Map<String, Object>> resultsQueue;
    protected final Map<String, Integer> classIdMap;
    protected final List<String> labels;
    protected final ImagePlus imp;

    protected AbstractResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue,
                                       Map<String, Integer> classIdMap,
                                       List<String> labels,
                                       ImagePlus imp) {
        this.resultsQueue = resultsQueue;
        this.classIdMap = classIdMap;
        this.labels = labels;
        this.imp = imp;
    }

    @Override
    public final Void call() throws InterruptedException {
        while (true) {
            Map<String, Object> info = resultsQueue.take();
            if (info == END_SIGNAL) {
                IJ.log("All frames processed.");
                break;
            }
            try {
                processFrame(info);
            } catch (RuntimeException e) {
                // A single malformed/unexpected frame should not abort the whole run.
                IJ.log("ERROR processing a frame, skipping it: " + e);
            }
        }
        return null;
    }

    private void processFrame(Map<String, Object> info) {
        int numResults = ((Number) info.get("n_results")).intValue();
        int frameIdx = ((Number) info.get("frame_idx")).intValue();

        if (numResults == 0) {
            onEmptyFrame(frameIdx);
            return;
        }

        NDArray outputBoxes = (NDArray) info.get("boxes");
        NDArray outputMasks = (NDArray) info.get("masks");
        NDArray outputScores = (NDArray) info.get("scores");

        if (outputBoxes == null || outputMasks == null || outputScores == null) {
            onMissingArrays(frameIdx);
            return;
        }

        double[][] boxes = DetectionArrayParsing.extractBoxes(outputBoxes, numResults);
        byte[][][] masks = DetectionArrayParsing.extractMasks(outputMasks, numResults);
        double[] scores = DetectionArrayParsing.extractScores(outputScores, numResults);
        int[] labelIds = extractLabelIds(frameIdx, info, numResults);

        List<String> classNames = new ArrayList<>(numResults);
        List<Double> probabilities = new ArrayList<>(numResults);
        List<BoundingBox> boundingBoxes = new ArrayList<>(numResults);
        for (int i = 0; i < numResults; i++) {
            classNames.add(labels.get(labelIds[i]));
            double[] coord = boxes[i];
            boundingBoxes.add(new MaskByte(coord[0], coord[1], coord[2], coord[3], masks[i], true));
            probabilities.add(scores[i]);
        }

        DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
        List<ProcessedDetection> processedDetections =
                DetectionUtils.processDetections(imp, detectedObjects, classIdMap);

        if (processedDetections.isEmpty()) {
            onNoValidDetections(frameIdx);
            return;
        }

        storeResults(frameIdx, processedDetections, labelIds, info, numResults);
    }

    /** Which prompt/concept/object label each of the {@code numResults} detections belongs to. */
    protected abstract int[] extractLabelIds(int frameIdx, Map<String, Object> info, int numResults);

    /**
     * Persist the processed detections for this frame.
     *
     * @param labelIds the array already returned by {@link #extractLabelIds}
     */
    protected abstract void storeResults(int frameIdx, List<ProcessedDetection> processedDetections,
                                          int[] labelIds, Map<String, Object> info, int numResults);

    protected void onEmptyFrame(int frameIdx) {
        // default: nothing to record
    }

    protected void onMissingArrays(int frameIdx) {
        throw new IllegalStateException("Missing output arrays (boxes, masks or scores) from Python for frame " + frameIdx);
    }

    protected void onNoValidDetections(int frameIdx) {
        IJ.log("No valid detections were processed for frame " + frameIdx);
    }
}