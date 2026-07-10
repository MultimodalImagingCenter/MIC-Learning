package fr.curie.miclearning.apposeplugin; // TODO: adjust to match your actual package

import ij.gui.Roi;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * ROI-scanning : finding the earliest usable frame, collecting ROI groups,
 * and splitting ROIs into positive/negative prompt coordinates.
 */
public class RoiPromptExtractor {

    /** Only point and rectangle ROIs are usable as SAM3 visual prompts. */
    // TODO : extract a rectangle from any ROI type
    private static boolean isUsableRoiType(Roi roi) {
        return roi.getType() == Roi.RECTANGLE || roi.getType() == Roi.POINT;
    }

    /**
     * Scans the selected ROIs to find the earliest frame (along the given axis) containing
     * at least one usable ROI, and collects the group IDs present on that frame.
     *
     * @param selectedRois ROIs currently selected in the RoiManager (may be empty, not null)
     * @param axis         3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     * @param totalFrames  total number of frames along that axis
     * @return the scan result
     */
    public RoiSelectionResult extract(Roi[] selectedRois, int axis, int totalFrames) {
        if (selectedRois == null || selectedRois.length == 0) {
            return RoiSelectionResult.none(totalFrames);
        }

        int minPosition = totalFrames;
        for (Roi roi : selectedRois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            if (position < minPosition) minPosition = position;
            if (minPosition == 1) break; // earliest possible position
        }

        List<Roi> roisAtFirstFrame = new ArrayList<>();
        TreeSet<Integer> uniqueGroups = new TreeSet<>();
        for (Roi roi : selectedRois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            if (position == minPosition) {
                roisAtFirstFrame.add(roi);
                uniqueGroups.add(roi.getGroup());
            }
        }

        if (uniqueGroups.isEmpty()) {
            return RoiSelectionResult.none(totalFrames);
        }

        int firstUnusedGroupId = findFirstUnusedGroupId(uniqueGroups);
        return new RoiSelectionResult(minPosition, roisAtFirstFrame, uniqueGroups, firstUnusedGroupId);
    }

    /**
     * Finds the smallest group ID in [1, 255] not already used by an existing ROI group,
     * so it can be assigned to the (single) prompt/class used in this run.
     */
    private int findFirstUnusedGroupId(TreeSet<Integer> uniqueGroups) {
        if (uniqueGroups.size() == 1) {
            int onlyGroup = uniqueGroups.first();
            return (onlyGroup % 255) + 1;
        }
        for (int candidate = 1; candidate <= 255; candidate++) {
            if (!uniqueGroups.contains(candidate)) {
                return candidate;
            }
        }
        // All 255 IDs are in use - fall back to 0 (no group)
        return 0;
    }

    /**
     * Splits ROIs at a single frame into positive/negative prompt coordinate lists.
     * Rectangle ROIs: {@code [x, y, w, h]}; point ROIs one {@code [x, y]} per contained point.
     *
     * @param roisAtFrame      the ROIs to split
     * @param positiveGroupIds groups to treat as positive prompts (empty if unused)
     * @param negativeGroupId  group to treat as negative prompt, or {@code null} if unused
     */
    public PromptRois buildPromptRois(List<Roi> roisAtFrame, List<Integer> positiveGroupIds,
                                      Integer negativeGroupId) {
        List<double[]> positive = new ArrayList<>(); // positive_rois format: [[x,y,w,h], [x,y], ...] (absolute values)
        List<double[]> negative = new ArrayList<>(); // negative_rois format: [[x,y,w,h], [x,y], ...] (absolute values)
        boolean positivePromptUsed = positiveGroupIds != null && !positiveGroupIds.isEmpty();
        boolean negativePromptUsed = negativeGroupId != null;

        for (Roi roi : roisAtFrame) {
            int groupId = roi.getGroup();
            boolean isPositiveGroup = positivePromptUsed && positiveGroupIds.contains(groupId);
            boolean isNegativeGroup = negativePromptUsed && groupId == negativeGroupId;
            if (!isPositiveGroup && !isNegativeGroup) continue;

            if (roi.getType() == Roi.RECTANGLE) {
                Rectangle rect = roi.getBounds();
                double[] coord = {rect.x, rect.y, rect.width, rect.height};
                if (isPositiveGroup) positive.add(coord);
                if (isNegativeGroup) negative.add(coord);
            } else if (roi.getType() == Roi.POINT) {
                for (Point point : roi.getContainedPoints()) {
                    double[] coord = {point.getX(), point.getY()};
                    if (isPositiveGroup) positive.add(coord);
                    if (isNegativeGroup) negative.add(coord);
                }
            }
        }

        return new PromptRois(positive, negative);
    }

    /** label for a group, e.g. "3 (nucleus)" or "3" if the group has no name. */
    public static String formatGroupLabel(int groupId) {
        String name = Roi.getGroupName(groupId);
        return name == null ? String.valueOf(groupId) : (groupId + " (" + name + ")");
    }

    /** Result of scanning the RoiManager selection for usable ROIs. */
    public static final class RoiSelectionResult {
        private final int firstFramePosition; // 1-indexed, matches Roi.getZPosition()/getTPosition()
        private final List<Roi> roisAtFirstFrame;
        private final TreeSet<Integer> uniqueGroups;
        private final int firstUnusedGroupId;

        private RoiSelectionResult(int firstFramePosition, List<Roi> roisAtFirstFrame,
                                   TreeSet<Integer> uniqueGroups, int firstUnusedGroupId) {
            this.firstFramePosition = firstFramePosition;
            this.roisAtFirstFrame = Collections.unmodifiableList(roisAtFirstFrame);
            this.uniqueGroups = uniqueGroups;
            this.firstUnusedGroupId = firstUnusedGroupId;
        }

        private static RoiSelectionResult none(int totalFrames) {
            return new RoiSelectionResult(1, Collections.emptyList(), new TreeSet<>(), 1);
        }

        public boolean hasUsableRois() { return !uniqueGroups.isEmpty(); }
        public int getFirstFramePosition() { return firstFramePosition; }
        public List<Roi> getRoisAtFirstFrame() { return roisAtFirstFrame; }
        public TreeSet<Integer> getUniqueGroups() { return uniqueGroups; }
        public int getGroupCount() { return uniqueGroups.size(); }
        public int getFirstUnusedGroupId() { return firstUnusedGroupId; }
    }

    /** Positive/negative ROI coordinates ready to be sent to the Python side. */
    public static final class PromptRois {
        private final List<double[]> positive;
        private final List<double[]> negative;

        private PromptRois(List<double[]> positive, List<double[]> negative) {
            this.positive = Collections.unmodifiableList(positive);
            this.negative = Collections.unmodifiableList(negative);
        }

        public List<double[]> getPositive() { return positive; }
        public List<double[]> getNegative() { return negative; }
    }
}
