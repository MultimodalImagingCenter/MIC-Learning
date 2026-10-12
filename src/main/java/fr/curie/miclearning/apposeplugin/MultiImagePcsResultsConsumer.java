package fr.curie.miclearning.apposeplugin;

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
 * and save them into a {@code Map<Integer, List<ProcessedDetection>>}.
 * With a list of processed detection for each frame
 * <p>
 * Runs on a background thread so it can consume results while the python task is still producing them.
 */
public class MultiImagePcsResultsConsumer extends AbstractResultsConsumer {

    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame;

    public MultiImagePcsResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, Map<Integer, List<ProcessedDetection>> detectionsByFrame,
                                        Map<String, Integer> classIdMap, List<String> textPrompts, ImagePlus imp) {
        super(resultsQueue, classIdMap, textPrompts, imp);
        this.detectionsByFrame = detectionsByFrame;
    }

    public MultiImagePcsResultsConsumer(BlockingQueue<Map<String, Object>> resultsQueue, Map<Integer, List<ProcessedDetection>> detectionsByFrame,
                                        Map<String, Integer> classIdMap, String textPrompt, ImagePlus imp) {
        super(resultsQueue, classIdMap, singleton(textPrompt), imp);
        this.detectionsByFrame = detectionsByFrame;
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
    protected void onEmptyFrame(int frameIdx) {
        detectionsByFrame.put(frameIdx, Collections.emptyList());
    }

    @Override
    protected void onMissingArrays(int frameIdx) {
        IJ.log("Warning: missing output arrays (boxes, masks or scores) from Python for frame " + frameIdx);
        detectionsByFrame.put(frameIdx, Collections.emptyList());
    }

    @Override
    protected void storeResults(int frameIdx, List<ProcessedDetection> processedDetections, int[] labelIds, Map<String, Object> info, int numResults) {
        detectionsByFrame.put(frameIdx, processedDetections);
    }
}