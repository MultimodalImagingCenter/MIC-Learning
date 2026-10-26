package fr.curie.miclearning.apposeplugin;

/**
 * One selectable entry in a group-id combo box: a RoiManager group id + how many
 * ROIs from this group available in the frame range.
 */
public class RoiGroupOptionWithCount extends RoiGroupOption {
    private final int roiCount;

    public RoiGroupOptionWithCount(int groupId, int roiCount) {
        super(groupId);
        this.roiCount = roiCount;
    }

    public int getRoiCount() {return roiCount;}

    @Override
    public String toString() {
        return RoiPromptExtractor.formatGroupLabel(getGroupId()) + " (" + roiCount + " ROI"+ (roiCount ==1 ? "" : "s") +")";
    }
}