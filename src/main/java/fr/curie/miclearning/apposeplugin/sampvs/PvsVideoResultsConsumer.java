package fr.curie.miclearning.apposeplugin.sampvs;

import fr.curie.miclearning.apposeplugin.AbstractResultsConsumer;
import fr.curie.miclearning.apposeplugin.DetectionArrayParsing;
import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Collect per-frame tracking results from a {@link BlockingQueue},
 * convert them into {@link ProcessedDetection},
 * and save them into a {@link MultiFrameDataManager}.
 * <p>
 * Simpler than {@code VideoPcsResultsConsumer}: here object identity is the class
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class PvsVideoResultsConsumer extends AbstractResultsConsumer {

    private final MultiFrameDataManager mfdManager;

    public PvsVideoResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, MultiFrameDataManager mfdManager,
                                    Map<String, Integer> classIdMap, List<String> objectNames, ImagePlus imp) {
        super(resultsQueue, classIdMap, objectNames, imp);
        this.mfdManager = mfdManager;
    }

    @Override
    protected int[] extractLabelIds(int frameIdx, Map<String, Object> info, int numResults) {
        NDArray outputIds = (NDArray) info.get("object_ids");
        if (outputIds == null) {
            throw new IllegalStateException(
                    "Missing output arrays (boxes, masks, scores or object_ids) from Python for frame " + (frameIdx + 1));
        }
        return DetectionArrayParsing.extractIntArray(outputIds, numResults);
    }

    @Override
    protected void storeResults(int frameIdx, List<ProcessedDetection> processedDetections, int[] labelIds, Map<String, Object> info, int numResults) {
        for (int i = 0; i < numResults; i++) {
            mfdManager.registerDetection(frameIdx, processedDetections.get(i), labelIds[i], true);
        }
    }

    @Override
    protected void onMissingArrays(int frameIdx) {
        throw new IllegalStateException(
                "Missing output arrays (boxes, masks, scores or object_ids) from Python for frame " + (frameIdx + 1));
    }

    @Override
    protected void onNoValidDetections(int frameIdx) {
        IJ.log(" --- No valid detections were processed for frame " + (frameIdx + 1));
    }
}