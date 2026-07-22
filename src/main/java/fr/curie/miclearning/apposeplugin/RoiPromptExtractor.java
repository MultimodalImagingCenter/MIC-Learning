package fr.curie.miclearning.apposeplugin;

import ij.gui.Roi;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.*;

/**
 * ROI-scanning : finding the frames usable as prompt frame, collecting ROI groups,
 * and splitting ROIs into positive/negative prompt coordinates.
 */
public class RoiPromptExtractor {

    /** Only point and rectangle ROIs are usable as SAM3 visual prompts. */
    // TODO : extract a rectangle from any ROI type
    public static boolean isUsableRoiType(Roi roi) {
        return roi.getType() == Roi.RECTANGLE || roi.getType() == Roi.POINT;
    }

    /**
     * ROI group {@code 0} means "no group" in ImageJ and can't be used as a real prompt
     * group id. Returns {@code replacementForZero} in that case, {@code groupId} otherwise.
     */
    public static int normalizeGroupZero(int groupId, int replacementForZero) {
        return groupId == 0 ? replacementForZero : groupId;
    }

    /**
     * Scans the selected ROIs collects the group IDs present on that frame.
     *
     * @param rois list of Roi in the RoiManager to scan (can be all or selected only) (may be empty, not null)
     * @return the scan result
     */
    public RoiGroupIdsScanResult scanGroupsIds(Roi[] rois) {
        if (rois == null || rois.length == 0) {
            return RoiGroupIdsScanResult.none();
        }

        TreeSet<Integer> uniqueGroups = new TreeSet<>();
        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            uniqueGroups.add(roi.getGroup());

        }

        if (uniqueGroups.isEmpty()) {
            return RoiGroupIdsScanResult.none();
        }

        int firstUnusedGroupId = findFirstUnusedGroupId(uniqueGroups);
        return new RoiGroupIdsScanResult(uniqueGroups, firstUnusedGroupId);
    }

    /**
     * Finds the smallest group ID in [1, 255] not already used by an existing ROI group,
     * so it can be assigned to the prompt/class used in this run.
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
        // All 255 IDs are in use : fall back to 0 (no group)
        return 0;
    }

    /**
     * Groups the usable ROIs (rectangle or point) in {@code selectedRois} by frame position,
     * in a single pass.
     *
     * @param selectedRois ROIs currently selected in the RoiManager
     * @param axis         3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     */
    public Map<Integer, List<Roi>> groupRoisByFrame(Roi[] selectedRois, int axis) {
        Map<Integer, List<Roi>> byFrame = new HashMap<>();
        if (selectedRois == null) return byFrame;
        for (Roi roi : selectedRois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            byFrame.computeIfAbsent(position, k -> new ArrayList<>()).add(roi);
        }
        return byFrame;
    }

    /**
     * Checks whether at least one usable ROI (rectangle or point) sits on the given frame.
     *
     * @param roiByFrame map of usable ROIs, grouped by frame
     * @param frame        1-indexed position of the frame to test
     */
    public boolean hasUsableRoiAtFrame(Map<Integer, List<Roi>> roiByFrame, int frame) {
        if (roiByFrame == null || roiByFrame.isEmpty()) return false;
        List<Roi> roiAtFrame = roiByFrame.getOrDefault(frame, Collections.emptyList());
        return !roiAtFrame.isEmpty();
    }


    /**
     * Checks whether at least one usable ROI (rectangle or point) sits on the given frame.
     *
     * @param selectedRois ROIs currently selected in the RoiManager
     * @param axis         3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     * @param frame        1-indexed position of the frame to test
     */
    public boolean hasUsableRoiAtFrame(Roi[] selectedRois, int axis, int frame) {
        if (selectedRois == null) return false;
        for (Roi roi : selectedRois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            if (position == frame) return true;
        }
        return false;
    }

    /**
     * Collects the usable ROIs (rectangle or point) on the given frame.
     *
     * @param rois list of Roi in the RoiManager to scan (can be all or selected only) (may be empty, not null)
     * @param axis         3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     * @param frame        1-indexed frame position to collect ROIs from
     */
    public List<Roi> getRoisAtFrame(Roi[] rois, int axis, int frame) {
        List<Roi> result = new ArrayList<>();
        if (rois == null) return result;
        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            if (position == frame) result.add(roi);
        }
        return result;
    }

    /**
     * Splits ROIs at a single frame into positive/negative prompt coordinate lists.
     * Rectangle ROIs: {@code [x, y, w, h]}; point ROIs: one {@code [x, y]} per contained point.
     * <p>
     * Note: unlike {@link #buildGroupedPromptRois}, this does NOT normalize group {@code 0}
     *
     * @param roisAtFrame      the ROIs to split
     * @param positiveGroupIds groups to treat as positive prompts (empty if unused)
     * @param negativeGroupId  group to treat as negative prompt ({@code null} if unused)
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

    /**
     * Splits ROIs into positive prompt coordinates grouped by each ROI's own group id
     * Rectangle ROIs: {@code [x, y, w, h]}; point ROIs: one {@code [x, y]} per contained point.
     * Note: Group {@code 0} is normalized to {@code groupZeroReplacement}
     *
     * @param rois               the ROIs to split
     * @param negativeGroupId    group to treat as negative prompt ({@code null} if unused)
     * @param groupZeroReplacement replacement group id to use in place of group 0
     */
    public GroupedPromptRois buildGroupedPromptRois(List<Roi> rois, Integer negativeGroupId,
                                                     int groupZeroReplacement) {
        Map<Integer, List<double[]>> positiveByGroup = new HashMap<>(); // positive_rois format: {group_id_2: [[x,y,w,h], [x,y], ...], group_id_2: ... } (absolute values)
        List<double[]> negative = new ArrayList<>(); // negative_rois format: [[x,y,w,h], [x,y], ...] (absolute values)

        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            int effectiveGroupId = normalizeGroupZero(roi.getGroup(), groupZeroReplacement);
            boolean isNegativeGroup = negativeGroupId != null && effectiveGroupId == negativeGroupId;

            if (roi.getType() == Roi.RECTANGLE) {
                Rectangle rect = roi.getBounds();
                double[] coord = {rect.x, rect.y, rect.width, rect.height};
                if (isNegativeGroup) negative.add(coord);
                else positiveByGroup.computeIfAbsent(effectiveGroupId, k -> new ArrayList<>()).add(coord);
            } else if (roi.getType() == Roi.POINT) {
                for (Point point : roi.getContainedPoints()) {
                    double[] coord = {point.getX(), point.getY()};
                    if (isNegativeGroup) negative.add(coord);
                    else positiveByGroup.computeIfAbsent(effectiveGroupId, k -> new ArrayList<>()).add(coord);
                }
            }
        }

        return new GroupedPromptRois(positiveByGroup, negative);
    }

    /**
     * fingerprint of the ROI state that matters for prompt selection (group + frame per
     * usable ROI), for detecting whether a live-refresh poll should recompute anything
     *
     * @param rois ROIs to fingerprint (typically the full RoiManager content)
     * @param axis 3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     */
    public String signature(Roi[] rois, int axis) {
        StringBuilder sb = new StringBuilder();
        if (rois == null) return sb.toString();
        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            sb.append(roi.getGroup()).append(':').append(position).append(';');
        }
        return sb.toString();
    }

    /** label for a group, e.g. "3 (nucleus)" or "3" if the group has no name. */
    public static String formatGroupLabel(int groupId) {
        String name = Roi.getGroupName(groupId);
        return name == null ? String.valueOf(groupId) : (groupId + " (" + name + ")");
    }

    /** Result of scanning the RoiManager selection for usable ROIs. */
    public static final class RoiGroupIdsScanResult {
        private final TreeSet<Integer> uniqueGroups;
        private final int firstUnusedGroupId;

        private RoiGroupIdsScanResult(TreeSet<Integer> uniqueGroups, int firstUnusedGroupId) {
            this.uniqueGroups = uniqueGroups;
            this.firstUnusedGroupId = firstUnusedGroupId;
        }

        private static RoiGroupIdsScanResult none() {
            return new RoiGroupIdsScanResult(new TreeSet<>(), 1);
        }

        public boolean hasUsableRois() { return !uniqueGroups.isEmpty(); }
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

    /** Positive ROI coordinates grouped by group id, plus a negative list. */
    public static final class GroupedPromptRois {
        private final Map<Integer, List<double[]>> positiveByGroup;
        private final List<double[]> negative;

        private GroupedPromptRois(Map<Integer, List<double[]>> positiveByGroup, List<double[]> negative) {
            Map<Integer, List<double[]>> positiveByGroupCopy = new HashMap<>();
            for (Map.Entry<Integer, List<double[]>> entry : positiveByGroup.entrySet()) {
                positiveByGroupCopy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
            this.positiveByGroup = Collections.unmodifiableMap(positiveByGroupCopy);
            this.negative = Collections.unmodifiableList(negative);
        }

        public Map<Integer, List<double[]>> getPositiveByGroup() { return positiveByGroup; }
        public List<double[]> getNegative() { return negative; }
    }
}
