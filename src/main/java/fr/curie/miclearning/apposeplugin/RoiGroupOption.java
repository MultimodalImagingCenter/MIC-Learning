package fr.curie.miclearning.apposeplugin;

/**
 * One selectable entry in a group-id combo box: a RoiManager group id.
 */
public class RoiGroupOption {
    private final int groupId;

    public RoiGroupOption(int groupId) {
        this.groupId = groupId;
    }

    public int getGroupId() {return groupId;}

    @Override
    public String toString() {
        return RoiPromptExtractor.formatGroupLabel(groupId);
    }
}