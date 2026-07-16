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
 * Collect per-frame detection results from a {@link BlockingQueue}
 * and save them into a {@code Map<Integer, List<ProcessedDetection>>}.
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class MultiImagePcsResultsConsumer implements Callable<Void> {
    // signal pushed onto the queue exactly once, on every terminal python task outcome. */
    public static final Map<String, Object> END_SIGNAL = Collections.unmodifiableMap(new HashMap<>());

    private final BlockingQueue<Map<String, Object>> resultsQueue;
    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame;
    private final Map<String, Integer> classIdMap;
    private final List<String> textPrompts;
    private final ImagePlus imp;

    public MultiImagePcsResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, Map<Integer, List<ProcessedDetection>> detectionsByFrame,
                                        Map<String, Integer> classIdMap, List<String> textPrompts, ImagePlus imp) {
        this.resultsQueue = resultsQueue;
        this.detectionsByFrame = detectionsByFrame;
        this.classIdMap = classIdMap;
        this.textPrompts = textPrompts;
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
            detectionsByFrame.put(frameIdx, Collections.emptyList());
            return;
        }

        NDArray outputBoxes = (NDArray) info.get("boxes");
        NDArray outputMasks = (NDArray) info.get("masks");
        NDArray outputScores = (NDArray) info.get("scores");
        NDArray outputPromptIds = (NDArray) info.get("prompts_ids");

        if (outputBoxes == null || outputMasks == null || outputScores == null || outputPromptIds == null) {
            IJ.log("Warning : Missing output arrays (boxes, masks, scores or ids) from Python for frame " + frameIdx);
            detectionsByFrame.put(frameIdx, Collections.emptyList());
            return;
        }

        double[][] boxes = DetectionArrayParsing.extractBoxes(outputBoxes, numResults);
        byte[][][] masks = DetectionArrayParsing.extractMasks(outputMasks, numResults);
        double[] scores = DetectionArrayParsing.extractScores(outputScores, numResults);
        int[] promptIds = DetectionArrayParsing.extractIntArray(outputPromptIds, numResults);

        List<String> classNames = new ArrayList<>(numResults);
        List<Double> probabilities = new ArrayList<>(numResults);
        List<BoundingBox> boundingBoxes = new ArrayList<>(numResults);
        for (int i = 0; i < numResults; i++) {
            classNames.add(textPrompts.get(promptIds[i]));
            double[] coord = boxes[i];
            boundingBoxes.add(new MaskByte(coord[0], coord[1], coord[2], coord[3], masks[i], true));
            probabilities.add(scores[i]);
        }

        DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
        List<ProcessedDetection> processedDetections =
                DetectionUtils.processDetections(imp, detectedObjects, classIdMap);

        if (processedDetections.isEmpty()) {
            IJ.log("No valid detections were processed for frame " + frameIdx);
        }
        detectionsByFrame.put(frameIdx, processedDetections);
    }
}
