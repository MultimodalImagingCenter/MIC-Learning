package fr.curie.miclearning.tools.detection;

import ij.gui.Roi;

import java.util.Map;
import java.util.TreeMap;

public class TrackedDetection {
    private final int instanceId; // From python['object_ids']
    private final String className;
    private final int groupId;
    private final double detectionScore; //proba of detection, on first frame

    // Key: frameIndex, Value: The specific Roi for that frame
    private Map<Integer, Roi> frameBoxRois = new TreeMap<>();
    private Map<Integer, Roi> frameShapeRois = new TreeMap<>();
    private Map<Integer, Double> trackingScore = new TreeMap<>();


    public TrackedDetection(int instanceId, String className, int groupId, double detectionScore) {
        this.instanceId = instanceId;
        this.className = className;
        this.groupId = groupId;
        this.detectionScore = detectionScore;
    }

    public void addFrameData(int frameIndex, Roi roi) {
        this.frameBoxRois.put(frameIndex, roi);
    }

    public void addFrameData(int frameIndex, Roi bbRoi, Roi shapeRoi) {
        this.frameBoxRois.put(frameIndex, bbRoi);
        this.frameShapeRois.put(frameIndex, shapeRoi);
    }
    public void addFrameData(int frameIndex, Roi bbRoi, Roi shapeRoi, double score) {
        this.frameBoxRois.put(frameIndex, bbRoi);
        this.frameShapeRois.put(frameIndex, shapeRoi);
        if (this.frameBoxRois.isEmpty()) this.trackingScore.put(frameIndex, null); // no tracking score on first frame
        else if (trackingScore!= null) this.trackingScore.put(frameIndex, score);
    }

    public int getInstanceId() { return instanceId; }
    public Map<Integer, Roi> getFrameBoxRois() { return frameBoxRois; }
    public Roi getFrameRoi(int frameIndex) { return frameShapeRois.get(frameIndex); }
    public int getGroupId() { return groupId; }
    public double getDetectionScore() { return detectionScore; }
}
