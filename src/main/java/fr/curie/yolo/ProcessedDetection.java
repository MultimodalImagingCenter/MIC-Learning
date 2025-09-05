package fr.curie.yolo;

import ij.gui.Roi;

/**
 * Holds processed information for a single detected object,
 * including its class, probability, group ID, and generated ROIs.
 */
public class ProcessedDetection {
    private final String className; // Can be null if no data
    private final Double probability; // Can be null if no data
    private final int groupId; // Roi group
    private final String roiName;
    private final Roi boundingBoxRoi;
    private final Roi shapeRoi; // Can be null if no mask data

    public ProcessedDetection(String className, Double probability, int groupId, String roiName, Roi boundingBoxRoi, Roi shapeRoi) {
        this.className = className;
        this.probability = probability;
        this.groupId = groupId;
        this.roiName = roiName;
        this.boundingBoxRoi = boundingBoxRoi;
        this.shapeRoi = shapeRoi;
    }

    // --- Getters ---
    public String getClassName() { return className; }
    public Double getProbability() { return probability; }
    public int getGroupId() { return groupId; }
    public String getRoiName() { return roiName; }
    public Roi getBoundingBoxRoi() { return boundingBoxRoi; }
    public Roi getShapeRoi() { return shapeRoi; } // May return null
    public boolean hasShapeRoi() { return shapeRoi != null; }

}