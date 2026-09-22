package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;

/**
 * One selectable entry in a group-id combo box for {@link Sam3VideoPcs_Plugin}: a RoiManager group id
 */
public class Sam3GroupOption {
    private final int groupId;

    public Sam3GroupOption(int groupId) {
        this.groupId = groupId;
    }

    public int getGroupId() {return groupId;}

    @Override
    public String toString() {
        return RoiPromptExtractor.formatGroupLabel(groupId);
    }
}