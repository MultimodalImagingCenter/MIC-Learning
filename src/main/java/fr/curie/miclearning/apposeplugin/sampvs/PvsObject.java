package fr.curie.miclearning.apposeplugin.sampvs;

import java.util.Objects;

/**
 * One row of the object table for {@link SamPvsVideo_Plugin}: a single object to segment and
 * track, defined by visual prompts
 * - a mandatory positive visual prompt (box and/or point ROIs, possibly on several frames)
 * - and an optional point-only negative prompt
 */
public class PvsObject {

    /* Stable identifier, assigned once by the table model, not displayed in the object table */
    private final int id;

    // name given by the user, by default id, should be unique
    // does not impact the segmentation/tracking
    private String name;

    // RoiManager group for the positive/negative visual prompt. null if unused
    private Integer positiveVisualGroup = null;
    private Integer negativeVisualGroup = null;
    private int positivePromptCount=0;
    private int negativePromptCount=0;

    // output group id assigned to this object's detections, by default input group
    private int outputGroup;

    public PvsObject(int id) {
        this.id = id;
        this.outputGroup = id > 255 ? (id % 255) + 1 : id;
        this.name = id + "";
    }

    public PvsObject(int id, int outputGroup) {
        this.id = id;
        this.outputGroup = outputGroup;
        this.name = id + "";
    }

    public int getId() {return id;}

    public String getName() {return name;}
    public void setName(String name) {this.name = name;}

    public Integer getPositiveVisualGroup() {return positiveVisualGroup;}
    public void setPositiveVisualGroup(Integer positiveVisualGroup) {this.positiveVisualGroup = positiveVisualGroup;}
    public boolean isPositiveVisualUsed() {return positiveVisualGroup != null;}

    public Integer getNegativeVisualGroup() {return negativeVisualGroup;}
    public void setNegativeVisualGroup(Integer negativeVisualGroup) {this.negativeVisualGroup = negativeVisualGroup;}
    public boolean isNegativeVisualUsed() {return negativeVisualGroup != null;}

    public int getOutputGroup() {return outputGroup;}
    public void setOutputGroup(int outputGroup) {this.outputGroup = outputGroup;}

    /** True while the name still holds its default value (the numeric {@link #id}) (i.e. true if the user never renamed it) */
    public boolean isNameDefault() {
        return name.equals(String.valueOf(id));
    }

    /** True while the output value still holds its default value (the numeric {@link #id} ( or id %255 +1 if id>255)) (i.e. true if the user never changed it) */
    public boolean isOutputGroupDefault() {
        return outputGroup == (id > 255 ? (id % 255) + 1 : id);
    }

    /** A positive visual prompt is always required */
    public boolean hasAnyPositivePrompt() {
        return isPositiveVisualUsed();
    }

    /** An object can't use the same RoiManager group as both its positive and its negative visual prompt. */
    public boolean isNegativeGroupValid() {
        return negativeVisualGroup == null || !Objects.equals(negativeVisualGroup, positiveVisualGroup);
    }

    public int getPositivePromptCount() {return positivePromptCount;}
    public void setPositivePromptCount(int positivePromptCount) {this.positivePromptCount = positivePromptCount;}

    public int getNegativePromptCount() {return negativePromptCount;}
    public void setNegativePromptCount(int negativePromptCount) {this.negativePromptCount = negativePromptCount;}
}