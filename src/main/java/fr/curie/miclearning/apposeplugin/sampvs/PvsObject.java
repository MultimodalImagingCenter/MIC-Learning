package fr.curie.miclearning.apposeplugin.sampvs;

import java.util.Objects;

/**
 * One row of the object table for {@link SamPvsVideo_Plugin} an object/group of object to segment and
 * track, defined by visual prompts:
 * <ul>
 *   <li>a mandatory positive visual prompt (box and/or point ROIs, possibly on several frames)</li>
 *   <li>and an optional point-only negative prompt</li>
 * </ul>
 * <p>
 * Can be of 2 types:
 * <ul>
 *   <li>a single object: on a single frame, all ROIs of the group are resolved as parts of the same object ;
 *    across multiple frames, all ROIs of the same group are instances of the same object.</li>
 *   <li>a class: on a single frame, all Rois of the group are resolved as independent objects :
 *   across multiple frames, all ROIs with the same name and group are instances of the same object.</li>
 *   TODO: add a third type, objects defined by 1 single Roi (regardless of group)
 * </ul>
 */
public class PvsObject {

    /**
     * Whether this row's positive-group ROIs are resolved as one tracked object ({@link #OBJECT},)
     * or as one independently-tracked object per distinct ROI name ({@link #CLASS}.
     * In {@link #CLASS} mode, a later ROI refines a specific earlier
     * instance only if it reuses that instance's exact ROI name; any other name starts a new one.
     */
    public enum Mode {
        OBJECT("Object"), CLASS("Class");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /* Stable identifier, assigned once by the table model, not displayed in the object table */
    private final int id;

    // name given by the user, by default id, should be unique
    // does not impact the segmentation/tracking
    private String name;

    private Mode mode = Mode.OBJECT;

    // RoiManager group for the positive/negative visual prompt. null if unused
    private Integer positiveVisualGroup = null;
    private Integer negativeVisualGroup = null;

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

    public Mode getMode() {return mode;}
    public void setMode(Mode mode) {this.mode = mode;}

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
}