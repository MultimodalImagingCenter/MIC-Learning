package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.detection.MultiFrameDataManager;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.IJ;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

/**
 * Collect per-frame detection results from a {@link BlockingQueue}
 * and save them into a {@code MultiFrameDataManager}.
 * With a list of processed detection for each frame
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class MultiImagePcsResultsConsumer extends AbstractResultsConsumer {

    private final MultiFrameDataManager mfdManager;

    public MultiImagePcsResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, MultiFrameDataManager mfdManager,
                                        Map<String, Integer> classIdMap, List<String> textPrompts, ImagePlus imp) {
        super(resultsQueue, classIdMap, textPrompts, imp);
        this.mfdManager = mfdManager;
    }

    public MultiImagePcsResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, MultiFrameDataManager mfdManager,
                                        Map<String, Integer> classIdMap, String textPrompt, ImagePlus imp) {
        super(resultsQueue, classIdMap, singleton(textPrompt), imp);
        this.mfdManager = mfdManager;
    }

    private static List<String> singleton(String textPrompt) {
        List<String> textPrompts = new ArrayList<>(1);
        textPrompts.add(textPrompt);
        return textPrompts;
    }

    @Override
    protected int[] extractLabelIds(int frameIdx, Map<String, Object> info, int numResults) {
        NDArray outputPromptIds = (NDArray) info.get("prompts_ids");
        if (outputPromptIds != null) return DetectionArrayParsing.extractIntArray(outputPromptIds, numResults);
        return new int[numResults]; // if no prompt id received from python, we assume only one text prompt
    }


    @Override
    protected void onMissingArrays(int frameIdx) {
        IJ.log("Warning: missing output arrays (boxes, masks or scores) from Python for frame " + frameIdx);
    }

    @Override
    protected void storeResults(int frameIdx, List<ProcessedDetection> processedDetections, int[] labelIds, Map<String, Object> info, int numResults) {
        for (int i = 0; i < numResults; i++) {
            mfdManager.registerDetection(frameIdx, processedDetections.get(i), labelIds[i], true);
        }
    }
}