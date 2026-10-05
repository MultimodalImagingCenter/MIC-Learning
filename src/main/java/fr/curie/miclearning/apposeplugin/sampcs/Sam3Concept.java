package fr.curie.miclearning.apposeplugin.sampcs;

import java.util.Objects;

/**
 * One row of the concept table for {@link Sam3ImagePcs_Plugin} and
 * {@link Sam3VideoPcs_Plugin}: a single class defined by
 * an optional text prompt and/or an optional visual prompt (positive/negative), run
 * independently on every frame of the stack.
 */
public class Sam3Concept {

    /* Stable identifier, assigned once by the table model, not displayed in the conceptTable*/
    private final int id;

    // name given by the user, by default id, should be unique
    private String name;

    //Prompts
    private String textPrompt = "";
    // RoiManager group feeding the positive/negative visual prompt; null == that prompt is unused
    private Integer positiveVisualGroup = null;
    private Integer negativeVisualGroup = null;
    private int positivePromptCount;
    private int negativePromptCount;



    // output group id assigned to this concept's detections (independent of the input groups)
    private int outputGroup;

    public Sam3Concept(int id, int outputGroup) {
        this.id = id;
        this.outputGroup = outputGroup;
        this.name = id + "";
    }

    public Sam3Concept(int id) {
        this.id = id;
        this.outputGroup = id>255 ? (id%255)+1 : id;
        this.name = id + "";
    }

    public int getId() {return id;}

    public String getName() {return name;}
    public void setName(String name) {this.name = name;}

    public String getTextPrompt() {return textPrompt;}
    public void setTextPrompt(String textPrompt) {this.textPrompt = textPrompt == null ? "" : textPrompt;}
    public boolean isTextPromptUsed() {return !textPrompt.trim().isEmpty();}

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
        return outputGroup == (id>255 ? (id%255)+1 : id);
    }

    /** At least one positive prompt (text or positive visual) must be defined. */
    public boolean hasAnyPositivePrompt() {
        return isTextPromptUsed() || isPositiveVisualUsed();
    }

    /** A concept can't use the same RoiManager group as both its positive and its negative visual prompt. */
    public boolean isNegativeGroupValid() {
        return negativeVisualGroup == null || !Objects.equals(negativeVisualGroup, positiveVisualGroup);
    }
    public int getPositivePromptCount() {return positivePromptCount;}
    public void setPositivePromptCount(int positivePromptCount) {this.positivePromptCount = positivePromptCount;}

    public int getNegativePromptCount() {return negativePromptCount;}
    public void setNegativePromptCount(int negativePromptCount) {this.negativePromptCount = negativePromptCount;}
}