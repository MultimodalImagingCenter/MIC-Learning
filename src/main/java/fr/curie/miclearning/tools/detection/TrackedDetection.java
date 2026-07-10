package fr.curie.miclearning.tools.detection;

import ij.gui.Roi;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One tracked object across the video.
 * <p>
 * An object has a single {@code detectionScore} (the model's confidence when it was first
 * detected) and can have a {@code trackingScore} on every subsequent frame where it was
 * tracked (the first frame never has a tracking score, by construction
 */

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

    /**
     * Records this object's ROIs on the frame where it was first detected. Does
     * add an entry to the tracking-score map
     */
    public void recordFirstFrame(int frameIndex, Roi bbRoi, Roi shapeRoi) {
        frameBoxRois.put(frameIndex, bbRoi);
        frameShapeRois.put(frameIndex, shapeRoi);
    }

    /** Records this object's ROIs and tracking score on a subsequent (non-first) frame. */
    public void recordTrackedFrame(int frameIndex, Roi bbRoi, Roi shapeRoi, double score) {
        frameBoxRois.put(frameIndex, bbRoi);
        frameShapeRois.put(frameIndex, shapeRoi);
        trackingScore.put(frameIndex, score);
    }

    public void addFrameData(int frameIndex, Roi roi) {
        this.frameBoxRois.put(frameIndex, roi);
    }

    public void addFrameData(int frameIndex, Roi bbRoi, Roi shapeRoi) {
        this.frameBoxRois.put(frameIndex, bbRoi);
        this.frameShapeRois.put(frameIndex, shapeRoi);
    }
    public void addFrameData(int frameIndex, Roi bbRoi, Roi shapeRoi, double score) {
        boolean isFirstFrame = frameBoxRois.isEmpty();
        frameBoxRois.put(frameIndex, bbRoi);
        frameShapeRois.put(frameIndex, shapeRoi);
        if (!isFirstFrame) {
            trackingScore.put(frameIndex, score);
        }
    }

    public int getInstanceId() { return instanceId; }
    public Map<Integer, Roi> getFrameBoxRois() { return frameBoxRois; }
    public Roi getFrameRoi(int frameIndex) { return frameShapeRois.get(frameIndex); }
    public int getGroupId() { return groupId; }
    public double getDetectionScore() { return detectionScore; }
    public Optional<Double> getTrackingScore(int frameIndex) {return Optional.ofNullable(trackingScore.get(frameIndex));
    }
}
