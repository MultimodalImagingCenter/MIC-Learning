package fr.curie.miclearning.apposeplugin.sampcs;

/**
 * One row of the concept table for {@link Sam3SingleImgPcs_Plugin}: a single class defined by
 * an optional text prompt and/or an optional visual prompt (positive/negative), run
 * independently on every frame of the stack.
 */
public class Sam3Concept {

    // RoiManager group id. both the source group for the positive visual prompt
    // (when positiveVisualUsed) and the output group id assigned to this concept's detections
    private int classId;

    private String textPrompt = "";
    private boolean textPromptUsed = false;

    // positive visual prompt is always sourced from the RoiManager group == classId
    private boolean positiveVisualUsed = false;

    private boolean negativeVisualUsed = false;
    private Integer negativeGroupId = null;

    public Sam3Concept(int classId) {
        this.classId = classId;
    }

    public int getClassId() {return classId;}
    public void setClassId(int classId) {this.classId = classId;}

    public String getTextPrompt() {return textPrompt;}
    public void setTextPrompt(String textPrompt) {this.textPrompt = textPrompt;}

    public boolean isTextPromptUsed() {return textPromptUsed;}
    public void setTextPromptUsed(boolean textPromptUsed) {this.textPromptUsed = textPromptUsed;}

    public boolean isPositiveVisualUsed() {return positiveVisualUsed;}
    public void setPositiveVisualUsed(boolean positiveVisualUsed) {this.positiveVisualUsed = positiveVisualUsed;}

    public boolean isNegativeVisualUsed() {return negativeVisualUsed;}
    public void setNegativeVisualUsed(boolean negativeVisualUsed) {this.negativeVisualUsed = negativeVisualUsed;}

    public Integer getNegativeGroupId() {return negativeGroupId;}
    public void setNegativeGroupId(Integer negativeGroupId) {this.negativeGroupId = negativeGroupId;}

    /**
     * Synthetic unique label used as the {@code classIdMap} key sent to
     * {@code DetectionUtils.processDetections}
     */
    public String getLabel() {
        return "concept_" + classId;
    }

    /** At least one positive prompt (text or visual) must be defined. */
    public boolean hasAnyPositivePrompt() {
        return textPromptUsed || positiveVisualUsed;
    }

    /** A concept can't use its own class group as its negative prompt. */
    public boolean isNegativeGroupValid() {
        return !negativeVisualUsed || (negativeGroupId != null && negativeGroupId != classId);
    }
}