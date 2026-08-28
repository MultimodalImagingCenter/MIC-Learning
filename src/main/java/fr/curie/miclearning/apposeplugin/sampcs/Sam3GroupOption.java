package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;

/**
 * One selectable entry in a group-id combo box: a RoiManager group id + how many of the
 * frames being processed contain at least one usable ROI belonging to that group.
 */
public class Sam3GroupOption {
    private final int groupId;
    private final int coveredFrames;
    private final int totalFrames;

    public Sam3GroupOption(int groupId, int coveredFrames, int totalFrames) {
        this.groupId = groupId;
        this.coveredFrames = coveredFrames;
        this.totalFrames = totalFrames;
    }

    public int getGroupId() {return groupId;}
    public int getCoveredFrames() {return coveredFrames;}
    public int getTotalFrames() {return totalFrames;}

    @Override
    public String toString() {
        return RoiPromptExtractor.formatGroupLabel(groupId) + " - " + coveredFrames + "/" + totalFrames + " frames";
    }
}