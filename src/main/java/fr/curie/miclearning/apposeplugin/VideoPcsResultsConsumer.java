package fr.curie.miclearning.apposeplugin;

import ij.IJ;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import fr.curie.miclearning.tools.detection.ProcessedDetection;

/**
 * Collect per-frame detection results from a {@link BlockingQueue}
 * convert them into {@link ProcessedDetection}
 * and save them into a {@link MultiFrameDataManager}
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class VideoPcsResultsConsumer extends AbstractResultsConsumer {

    private final MultiFrameDataManager mfdManager;

    public VideoPcsResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, MultiFrameDataManager mfdManager,
                                   Map<String, Integer> classIdMap, List<String> conceptLabels, ImagePlus imp) {
        super(resultsQueue, classIdMap, conceptLabels, imp);
        this.mfdManager = mfdManager;
    }

    @Override
    protected int[] extractLabelIds(int frameIdx, Map<String, Object> info, int numResults) {
        NDArray outputConceptIds = (NDArray) info.get("prompts_ids");
        if (outputConceptIds != null) return DetectionArrayParsing.extractIntArray(outputConceptIds, numResults);
        return new int[numResults]; // if no concept id received from python, we assume only one concept
    }

    @Override
    protected void storeResults(int frameIdx, List<ProcessedDetection> processedDetections, int[] labelIds, Map<String, Object> info, int numResults) {
        NDArray outputIds = (NDArray) info.get("object_ids");
        if (outputIds == null) {
            throw new IllegalStateException(
                    "Missing output arrays (boxes, masks, scores or ids) from Python for frame " + (frameIdx + 1));
        }
        int[] ids = DetectionArrayParsing.extractIntArray(outputIds, numResults);

        for (int i = 0; i < numResults; i++) {
            mfdManager.registerDetection(frameIdx, processedDetections.get(i), ids[i], true);
        }
    }

    @Override
    protected void onMissingArrays(int frameIdx) {
        throw new IllegalStateException(
                "Missing output arrays (boxes, masks, scores or ids) from Python for frame " + (frameIdx + 1));
    }

    @Override
    protected void onNoValidDetections(int frameIdx) {
        IJ.log(" --- No valid detections were processed for frame " + (frameIdx + 1));
    }
}