package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;

/**
 * One selectable entry in a group-id combo box for {@link Sam3ImagePcs_Plugin}: a RoiManager group id + how many of the
 * frames being processed contain at least one usable ROI belonging to that group.
 */
public class Sam3GroupOptionImage extends Sam3GroupOption {
    private final int coveredFrames;
    private final int totalFrames;

    public Sam3GroupOptionImage(int groupId, int coveredFrames, int totalFrames) {
        super(groupId);
        this.coveredFrames = coveredFrames;
        this.totalFrames = totalFrames;
    }

    public int getCoveredFrames() {return coveredFrames;}
    public int getTotalFrames() {return totalFrames;}

    @Override
    public String toString() {
        return RoiPromptExtractor.formatGroupLabel(getGroupId()) + " - " + coveredFrames + "/" + totalFrames + " frames";
    }
}