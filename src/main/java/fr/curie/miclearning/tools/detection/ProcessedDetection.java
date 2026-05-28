package fr.curie.miclearning.tools.detection;

import ij.gui.Roi;

import java.util.List;

/**
 * Holds processed information for a single detected object,
 * including its class, probability, group ID, and generated ROIs.
 */
public class ProcessedDetection {
    private final String className; // Can be null if no data
    private final Double probability; // Can be null if no data
    private final int groupId; // Roi group
    private final Roi boundingBoxRoi; // can be null if no data
    private final Roi shapeRoi; // Can be null if no mask data
    private int id; // one id per object of the same class - can be modified
    private String roiName; // can be changed if id is changed
    private List<Float> allScore; // Can be null if not set


    public static final String ROI_MASK_PREFIX = "mask_";
    public static final String ROI_BB_PREFIX = "box_";


    public ProcessedDetection(String className, Double probability, int groupId, Roi boundingBoxRoi, Roi shapeRoi, int id) {
        this.className = className;
        this.probability = probability;
        this.groupId = groupId;
        this.boundingBoxRoi = boundingBoxRoi;
        this.shapeRoi = shapeRoi;
        this.id = id;
        updateName();
    }


    public void setAllScore(List<Float> allScores) {
        this.allScore = allScores;
    }

    private void updateName() {
        if (probability != null){
            this.roiName = String.format("%s_%d_%.5f", className, id, probability);
        } else {this.roiName = String.format("%s_%d", className, id);}

        if (boundingBoxRoi != null){this.boundingBoxRoi.setName(ROI_BB_PREFIX + roiName);}
        if  (shapeRoi != null){this.shapeRoi.setName(ROI_MASK_PREFIX + roiName);}
    }

    // --- Getters ---
    public String getClassName() { return className; }
    public Double getProbability() { return probability; }
    public int getGroupId() { return groupId; }
    public String getRoiName() { return roiName; }
    public Roi getBoundingBoxRoi() { return boundingBoxRoi; }
    public Roi getShapeRoi() { return shapeRoi; } // May return null
    public boolean hasShapeRoi() { return shapeRoi != null; }
    public List<Float> getAllScore() { return allScore; } // May return null if not set
    public boolean hasAllScore() { return allScore != null; }

    public int getId() {return id;}
    public void setId(int id) {
        this.id = id;
        updateName(); //if id is changed later, need to update name with correct id
    }
}