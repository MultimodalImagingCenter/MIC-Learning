package fr.curie.miclearning.tools.detection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultiFrameDataManager {
    // Master registry of objects across the video
    private final Map<Integer, TrackedDetection> detectionsRegistry = new HashMap<>(); // list of tracked detection, by id
    private final Map<Integer, List<Integer>> idByFrame = new HashMap<>(); // list of ids on every frame
    private final Map<Integer, List<ProcessedDetection>> detectionsByFrame = new HashMap<>(); // list of detection on every frame

    private final int maxFrameNumber; // number of frames processed

    public MultiFrameDataManager(int maxFrameNumber) {
        this.maxFrameNumber = maxFrameNumber;
    }

    public void registerDetection(int frameIndex, ProcessedDetection processedDet, int instanceId) {
        registerDetection(frameIndex, processedDet, instanceId, false);
    }

    public void registerDetection(int frameIndex, ProcessedDetection processedDet, int instanceId, boolean probaIsTrackingScore) {
        processedDet.setId(instanceId);
        TrackedDetection trackedDetection = detectionsRegistry.computeIfAbsent(instanceId,
                id -> new TrackedDetection(id, processedDet.getClassName(), processedDet.getGroupId(), processedDet.getProbability()));

        detectionsByFrame.computeIfAbsent(frameIndex, k -> new ArrayList<>()).add(processedDet);
        idByFrame.computeIfAbsent(frameIndex, k -> new ArrayList<>()).add(instanceId);

        if (probaIsTrackingScore) trackedDetection.addFrameData(frameIndex, processedDet.getBoundingBoxRoi(), processedDet.getShapeRoi(), processedDet.getProbability());
        else trackedDetection.addFrameData(frameIndex, processedDet.getBoundingBoxRoi(), processedDet.getShapeRoi());
    }

    public Map<Integer, List<Integer>> getIdByFrame() {
        return idByFrame;
    }
    public Map<Integer, TrackedDetection> getDetectionsRegistry() {
        return detectionsRegistry;
    }
    public Map<Integer, List<ProcessedDetection>> getDetectionsByFrame() {
        return detectionsByFrame;
    }
    public int getMaxFrameNumber() { return maxFrameNumber;    }

    @Override
    public String toString() {
        return "MultiFrameDataManager{" +
                "detectionsById= " + detectionsRegistry +
                ", idByFrame= " + idByFrame +
                '}';
    }

}