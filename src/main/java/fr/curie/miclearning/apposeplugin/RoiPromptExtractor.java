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
    // TODO : extract a rectangle from any ROI type ??
    public static boolean isUsableRoiType(Roi roi) {
        //return roi.getType() == Roi.RECTANGLE || roi.getType() == Roi.POINT;
        return roi.isArea() || roi.getType() == Roi.POINT;
    }

    /**
     * Groups the usable ROIs (rectangle or point) in {@code rois} by frame position,
     *
     * @param rois ROIs list
     * @param axis         3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     */
    public Map<Integer, List<Roi>> groupRoisByFrame(Roi[] rois, int axis) {
        Map<Integer, List<Roi>> byFrame = new HashMap<>();
        if (rois == null) return byFrame;
        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition(); // position le long de l'axe choisi
            if (position==0) position=1;
            byFrame.computeIfAbsent(position, k -> new ArrayList<>()).add(roi);
        }
        return byFrame;
    }

    /**
     * For each ROI group present in {@code rois}, counts how many of {@code frames} contain at
     * least one usable ROI belonging to that group
     *
     * @param rois   ROIs to scan
     * @param axis   3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     * @param frames the frames to check coverage against (1-indexed)
     */
    public Map<Integer, Integer> countGroupCoverage(Roi[] rois, int axis, Collection<Integer> frames) {
        Map<Integer, List<Roi>> roisByFrame = groupRoisByFrame(rois, axis);
        Map<Integer, Integer> coveredFramesByGroup = new TreeMap<>();
        for (int frame : frames) {
            Set<Integer> groupsAtFrame = new TreeSet<>();
            for (Roi roi : roisByFrame.getOrDefault(frame, Collections.emptyList())) groupsAtFrame.add(roi.getGroup());
            for (int groupId : groupsAtFrame) coveredFramesByGroup.merge(groupId, 1, Integer::sum);
        }
        return coveredFramesByGroup;
    }

    /**
     * Which of {@code frames} have no usable ROI belonging to {@code groupId} - e.g. to warn the
     * user before running that a concept's visual prompt won't fire on some frames.
     *
     * @param rois    ROIs to scan (typically the full RoiManager content)
     * @param axis    3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     * @param groupId the group to check
     * @param frames  the frames to check (1-indexed)
     */
    public List<Integer> findFramesMissingGroup(Roi[] rois, int axis, int groupId, Collection<Integer> frames) {
        Map<Integer, List<Roi>> roisByFrame = groupRoisByFrame(rois, axis);
        List<Integer> missing = new ArrayList<>();
        for (int frame : frames) {
            boolean present = roisByFrame.getOrDefault(frame, Collections.emptyList()).stream()
                    .anyMatch(roi -> roi.getGroup() == groupId);
            if (!present) missing.add(frame);
        }
        return missing;
    }

    /**
     * Collects the usable ROIs (rectangle or point) on the given frame - 3D image,
     * only one axis used
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
            if (position == frame || position == 0) result.add(roi); // position == 0 means no specific position, roi defined on all frames of this axis
        }
        return result;
    }

    /**
     * Collects the groups of the usable ROIs (rectangle or point) on the given frame - 3D image,
     * only one axis used
     *
     * @param rois list of Roi in the RoiManager to scan (can be all or selected only) (may be empty, not null)
     * @param axis         3 for Z axis, 4 for Time axis (matches ImagePlus.getDimensions() indexing)
     * @param frame        1-indexed frame position to collect ROIs from
     */
    public TreeSet<Integer> getRoiGroupAtFrame(Roi[] rois, int axis, int frame) {
        TreeSet<Integer> groups = new TreeSet<>();
        if (rois == null) return groups;
        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            int position = axis == 3 ? roi.getZPosition() : roi.getTPosition();
            if (position == frame || position == 0) groups.add(roi.getGroup()); // position == 0 means no specific position, roi defined on all frames of this axis
        }
        return groups;
    }

    /**
     * Collects the usable ROIs (rectangle or point) on the given frame - 4D image,
     * only one axis used
     *
     * @param rois list of Roi in the RoiManager to scan (can be all or selected only) (may be empty, not null)
     * @param axis         0
     * @param slice        1-indexed position along Z axis = Z-position
     * @param frame        1-indexed position along time axis = T-position
     */
    public List<Roi> getRoisAtFrame(Roi[] rois, int axis, int slice, int frame) {
        List<Roi> result = new ArrayList<>();
        if (rois == null) return result;
        for (Roi roi : rois) {
            if (!isUsableRoiType(roi)) continue;
            if ((roi.getTPosition() == frame || roi.getTPosition() == 0) && (roi.getZPosition() == slice || roi.getZPosition() == 0)) result.add(roi); // position == 0 means no specific position, roi defined on all frames of this axis
        }
        return result;
    }

    /**
     * Splits the given ROIs into positive/negative prompt coordinate lists.
     * Rectangle ROIs: {@code [x, y, w, h]}; point ROIs: one {@code [x, y]} per contained point.
     * <p>
     *
     * @param roisAtFrame      the ROIs to split
     * @param positiveGroupId group to treat as positive prompts ({@code null} if unused)
     * @param negativeGroupId  group to treat as negative prompt ({@code null} if unused)
     */
    public PromptRois buildPromptRois(List<Roi> roisAtFrame, Integer positiveGroupId,
                                      Integer negativeGroupId) {
        List<double[]> positive = new ArrayList<>(); // positive_rois format: [[x,y,w,h], [x,y], ...] (absolute values)
        List<double[]> negative = new ArrayList<>(); // negative_rois format: [[x,y,w,h], [x,y], ...] (absolute values)
        boolean positivePromptUsed = positiveGroupId != null;
        boolean negativePromptUsed = negativeGroupId != null;

        for (Roi roi : roisAtFrame) {
            int groupId = roi.getGroup();
            boolean isPositiveGroup = positivePromptUsed && groupId == positiveGroupId;
            boolean isNegativeGroup = negativePromptUsed && groupId == negativeGroupId;
            if (!isPositiveGroup && !isNegativeGroup) continue;

            //if (roi.getType() == Roi.RECTANGLE) {
            if (roi.isArea()){
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
