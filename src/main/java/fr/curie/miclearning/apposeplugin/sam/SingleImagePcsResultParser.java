package fr.curie.miclearning.apposeplugin.sam;

import ai.djl.modality.cv.output.BoundingBox;
import fr.curie.miclearning.apposeplugin.DetectionArrayParsing;
import fr.curie.miclearning.tools.detection.DetectedObjects;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import fr.curie.miclearning.tools.detection.MaskByte;
import fr.curie.miclearning.tools.detection.ProcessedDetection;
import ij.ImagePlus;
import org.apposed.appose.NDArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Parses the outputs map returned by {@link Sam3VisualPromptSingleImgPythonRunner#runAndGetOutputs}
 *  into a list of {@link ProcessedDetection}
 */
public class SingleImagePcsResultParser {

    private SingleImagePcsResultParser() {}

    public static List<ProcessedDetection> parse(Map<String, Object> outputs, ImagePlus imp,
                                                 Map<Integer, String> idClassMap,
                                                 Map<String, Integer> classIdMap) {
        Object resultsObj = outputs.get("results_number");
        if (resultsObj == null) {
            throw new IllegalStateException("Python script did not return 'results_number'.");
        }
        int numResults = ((Number) resultsObj).intValue();
        if (numResults == 0) {
            return Collections.emptyList();
        }

        NDArray outputBoxes = (NDArray) outputs.get("boxes");
        NDArray outputMasks = (NDArray) outputs.get("masks");
        NDArray outputScores = (NDArray) outputs.get("scores");
        NDArray outputGroupIds = (NDArray) outputs.get("group_ids");

        if (outputBoxes == null || outputMasks == null || outputScores == null || outputGroupIds == null) {
            throw new IllegalStateException("Missing output arrays (boxes, masks, scores or group_ids) from Python.");
        }

        double[][] boxes = DetectionArrayParsing.extractBoxes(outputBoxes, numResults);
        byte[][][] masks = DetectionArrayParsing.extractMasks(outputMasks, numResults);
        double[] scores = DetectionArrayParsing.extractScores(outputScores, numResults);
        int[] groupIds = DetectionArrayParsing.extractIntArray(outputGroupIds, numResults);

        List<String> classNames = new ArrayList<>(numResults);
        List<Double> probabilities = new ArrayList<>(numResults);
        List<BoundingBox> boundingBoxes = new ArrayList<>(numResults);
        for (int i = 0; i < numResults; i++) {
            classNames.add(idClassMap.get(groupIds[i]));
            double[] coord = boxes[i];
            boundingBoxes.add(new MaskByte(coord[0], coord[1], coord[2], coord[3], masks[i], true));
            probabilities.add(scores[i]);
        }

        DetectedObjects detectedObjects = new DetectedObjects(classNames, probabilities, boundingBoxes);
        return DetectionUtils.processDetections(imp, detectedObjects, classIdMap);
    }
}
