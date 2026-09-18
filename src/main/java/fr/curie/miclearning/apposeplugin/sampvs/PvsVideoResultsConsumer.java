package fr.curie.miclearning.apposeplugin.sampvs;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.apposeplugin.DetectionArrayParsing;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MaskByte;
import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;

/**
 * Collect per-frame tracking results from a {@link BlockingQueue}, convert them into
 * {@link ProcessedDetection}, and save them into a {@link MultiFrameDataManager}.
 * <p>
 * Simpler than {@code VideoPcsResultsConsumer}: object identity IS the class here (the
 * Java-assigned object index echoed back as {@code object_ids} resolves directly to a name via
 * {@link #objectNames}) - there is no separate concept/class id to resolve.
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class PvsVideoResultsConsumer implements Callable<Void> {

    public static final Map<String, Object> END_SIGNAL = Collections.unmodifiableMap(new HashMap<>());

    private final BlockingQueue<Map<String, Object>> resultsQueue;
    private final MultiFrameDataManager mfdManager;
    private final Map<String, Integer> classIdMap;
    private final List<String> objectNames;
    private final ImagePlus imp;

    public PvsVideoResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, MultiFrameDataManager mfdManager,
                                    Map<String, Integer> classIdMap, List<String> objectNames, ImagePlus imp) {
        this.resultsQueue = resultsQueue;
        this.mfdManager = mfdManager;
        this.classIdMap = classIdMap;
        this.objectNames = objectNames;
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
            return; // nothing tracked on this frame
        }

        NDArray outputBoxes = (NDArray) info.get("boxes");
        NDArray outputMasks = (NDArray) info.get("masks");
        NDArray outputScores = (NDArray) info.get("scores");
        NDArray outputIds = (NDArray) info.get("object_ids");

        if (outputBoxes == null || outputMasks == null || outputScores == null || outputIds == null) {
            throw new IllegalStateException(
                    "Missing output arrays (boxes, masks, scores or object_ids) from Python for frame " + (frameIdx + 1));
        }

        double[][] boxes = DetectionArrayParsing.extractBoxes(outputBoxes, numResults);
        byte[][][] masks = DetectionArrayParsing.extractMasks(outputMasks, numResults);
        double[] scores = DetectionArrayParsing.extractScores(outputScores, numResults);
        int[] objectIndices = DetectionArrayParsing.extractIntArray(outputIds, numResults);

        List<String> classNames = new ArrayList<>(numResults);
        List<Double> probabilities = new ArrayList<>(numResults);
        List<BoundingBox> boundingBoxes = new ArrayList<>(numResults);
        for (int i = 0; i < numResults; i++) {
            classNames.add(objectNames.get(objectIndices[i]));
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
            mfdManager.registerDetection(frameIdx, processedDetections.get(i), objectIndices[i], true);
        }
    }
}