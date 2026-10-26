package fr.curie.miclearning.apposeplugin.sampcs;

import java.util.Objects;

/**
 * One row of the concept table for {@link Sam3ImagePcs_Plugin} and
 * {@link Sam3VideoPcs_Plugin}: a single class defined by
 * an optional text prompt and/or an optional visual prompt (positive/negative), run
 * independently on every frame of the stack.
 */
public class PcsConcept {

    /* Stable identifier, assigned once by the table model, not displayed in the conceptTable*/
    private final int id;

    // name given by the user, by default id, should be unique
    // does not impact the segmentation/tracking
    private String name;
    // true once the user typed a name in the table; automatic renames (text prompt, group name) never set it
    private boolean nameSetByUser = false;

    //Prompts
    private String textPrompt = "";
    // RoiManager group feeding the positive/negative visual prompt; null == that prompt is unused
    private Integer positiveVisualGroup = null;
    private Integer negativeVisualGroup = null;
    private int positivePromptCount=0;
    private int negativePromptCount=0;

    // output group id assigned to this concept's detections (independent of the input groups)
    private int outputGroup;
    // true once the user typed a group in the table
    private boolean outputGroupSetByUser = false;

    public PcsConcept(int id, int outputGroup) {
        this.id = id;
        this.outputGroup = outputGroup;
        this.name = id + "";
    }

    public PcsConcept(int id) {
        this.id = id;
        this.outputGroup = id>255 ? (id%255)+1 : id;
        this.name = id + "";
    }

    public int getId() {return id;}

    public String getName() {return name;}
    public void setName(String name) {this.name = name;}
    /** Name explicitly chosen by the user: automatic renames are disabled from then on. */
    public void setUserName(String name) {this.name = name; this.nameSetByUser = true;}
    /** Gives control of the name back to automatic renames. */
    public void clearUserName() {this.nameSetByUser = false;}

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
    /** output group explicitly chosen by the user: automatic group setting are disabled from then on. */
    public void setUserOutputGroup(int outputGroup) {this.outputGroup = outputGroup; this.outputGroupSetByUser = true;}
    /** Gives control of the output group back to automatic group setting. */
    public void clearUserOutputGroup() {
        this.outputGroupSetByUser = false;
    }


    /** True while the name was not set by the user (it then holds the id, the text prompt or the positive group name, possibly with a "-x" suffix) */
    public boolean isNameDefault() {
        return !nameSetByUser;
    }

    /** True while the output value still holds its default value (the numeric {@link #id} ( or id %255 +1 if id>255)) (i.e. true if the user never changed it) */
    public boolean isOutputGroupDefault() {
        return !outputGroupSetByUser;
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
    public void increasePositivePromptCount(int positivePromptIncrease){this.positivePromptCount+=positivePromptIncrease;}

    public int getNegativePromptCount() {return negativePromptCount;}
    public void setNegativePromptCount(int negativePromptCount) {this.negativePromptCount = negativePromptCount;}
    public void increaseNegativePromptCount(int negativePromptIncrease){this.negativePromptCount+=negativePromptIncrease;}
}