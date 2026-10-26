package fr.curie.miclearning.apposeplugin.sampvs;

import ij.gui.Roi;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Table model for the object table of {@link PvsVideoDialog}: one row per {@link PvsObject},
 * one column per editable property.
 */
public class PvsObjectTableModel extends AbstractTableModel {

    public static final int COL_NAME = 0;
    public static final int COL_POSITIVE_VISUAL = 1;
    public static final int COL_NEGATIVE_VISUAL = 2;
    public static final int COL_OUTPUT_GROUP = 3;
    public static final int COL_MODE = 4;

    private static final String[] COLUMN_NAMES = {
            "Output name", "Positive visual prompt", "Negative visual prompt", "Output value", "Mode"
    };

    private static final int MAX_GROUP_ID = 255;

    // list of all objects registered in the table
    private final List<PvsObject> objects = new ArrayList<>();
    private int nextId = 1; // never reused within a session

    // false while the RoiManager has no usable ROI on any processed frame ; driven by the dialog's ROI poll.
    private boolean roiPromptsAvailable = false;

    @Override
    public int getRowCount() {
        return objects.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMN_NAMES.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMN_NAMES[column];
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
            case COL_POSITIVE_VISUAL:
            case COL_NEGATIVE_VISUAL:
            case COL_OUTPUT_GROUP:
                return Integer.class;
            case COL_MODE:
                return PvsObject.Mode.class;
            default:
                return String.class;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        if ((columnIndex == COL_POSITIVE_VISUAL || columnIndex == COL_NEGATIVE_VISUAL) && !roiPromptsAvailable) {
            return false; // no ROI available to use as prompts
        }
        return true; // (the name and output-group columns stay editable even when greyed out)
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        PvsObject object = objects.get(rowIndex);
        switch (columnIndex) {
            case COL_NAME: return object.getName();
            case COL_POSITIVE_VISUAL: return object.getPositiveVisualGroup();
            case COL_NEGATIVE_VISUAL: return object.getNegativeVisualGroup();
            case COL_OUTPUT_GROUP: return object.getOutputGroup();
            case COL_MODE: return object.getMode();
            default: throw new IllegalArgumentException("Unknown column: " + columnIndex);
        }
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        PvsObject object = objects.get(rowIndex);
        switch (columnIndex) {
            case COL_NAME:
                String newName = value == null ? "" : value.toString().trim();
                if (newName.isEmpty()) {
                    // empty name: give control back to automatic names
                    object.clearUserName();
                    object.setName(uniqueName(automaticName(object), object));
                } else if (!newName.equals(object.getName())) {
                    // only count it as "a user rename it" if the cell content actually differs
                    object.setUserName(uniqueName(newName, object));
                }
                break;
            case COL_POSITIVE_VISUAL:
                object.setPositiveVisualGroup((Integer) value);
                // the negative prompt can't reuse the positive group - drop it if it now collides
                if (Objects.equals(object.getNegativeVisualGroup(), object.getPositiveVisualGroup())) {
                    object.setNegativeVisualGroup(null);
                }

                //if the group given as positive prompt has a name (saved in ROI manager settings) and the name is still the default value
                // change the name to the one of the positive prompt
                object.setName(uniqueName(automaticName(object), object));
                // if a group for the visual positive prompt is given
                // if the output group is still the default value (i.e. the id):
                // change output group to input group (even if output group already exist)
                object.setOutputGroup(clampGroupId(automaticOutputGroup(object), object.getOutputGroup()));
                break;
            case COL_NEGATIVE_VISUAL:
                object.setNegativeVisualGroup((Integer) value);
                break;
            case COL_OUTPUT_GROUP:
                int newGroup = value == null ? 0 : (Integer) value;
                if (newGroup == 0){object.clearUserOutputGroup();
                    object.setOutputGroup(clampGroupId(automaticOutputGroup(object), object.getOutputGroup()));}
                else {
                    newGroup = clampGroupId(newGroup, object.getOutputGroup());
                    object.setUserOutputGroup(newGroup);
                }
                break;
            case COL_MODE:
                object.setMode((PvsObject.Mode) value);
                break;
            default:
                throw new IllegalArgumentException("Unknown column: " + columnIndex);
        }
        fireTableRowsUpdated(rowIndex, rowIndex); // repaint the whole row
    }

    /** Appends a new object row with a fresh id */
    public void addObject() {
        PvsObject object = new PvsObject(nextId++);
        objects.add(object);
        int row = objects.size() - 1;
        fireTableRowsInserted(row, row);
    }

    /** Removes the row at {@code rowIndex} */
    public void removeObject(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= objects.size()) return;
        objects.remove(rowIndex);
        fireTableRowsDeleted(rowIndex, rowIndex);
    }

    public PvsObject getObject(int rowIndex) {
        return objects.get(rowIndex);
    }

    /** Read-only view of the current objects, in row order. */
    public List<PvsObject> getObjects() {
        return Collections.unmodifiableList(objects);
    }

    /**
     * Whether the visual-prompt columns accept input (i.e. at least one usable ROI exists on a
     * processed frame). Fires a table update only when the value actually changes.
     */
    public void setRoiPromptsAvailable(boolean available) {
        if (this.roiPromptsAvailable == available) return;
        this.roiPromptsAvailable = available;
        fireTableDataChanged();
    }

    /**
     * Clears any visual-prompt group that is no longer selectable: a positive group absent from
     * {@code availablePositiveGroupIds}, or a negative group absent from
     * {@code availableNegativeGroupIds} or that now equals its row's positive group. Fires a
     * table update only when something changed.
     *
     * @return {@code true} if at least one object's positive or negative visual group was cleared
     */
    public boolean reconcileVisualGroups(Set<Integer> availablePositiveGroupIds, Set<Integer> availableNegativeGroupIds) {
        boolean changed = false;
        for (PvsObject object : objects) {
            if (object.getPositiveVisualGroup() != null
                    && !availablePositiveGroupIds.contains(object.getPositiveVisualGroup())) {
                object.setPositiveVisualGroup(null);
                changed = true;
            }
            if (object.getNegativeVisualGroup() != null
                    && (!availableNegativeGroupIds.contains(object.getNegativeVisualGroup())
                        || Objects.equals(object.getNegativeVisualGroup(), object.getPositiveVisualGroup()))) {
                object.setNegativeVisualGroup(null);
                changed = true;
            }
        }
        if (changed) fireTableDataChanged();
        return changed;
    }

    /** Trims {@code desired} (blank -> the object's numeric id), then appends "-1", "-2", ... until unique among the other rows. */
    private String uniqueName(String desired, PvsObject self) {
        String base = desired == null ? "" : desired.trim();
        if (base.isEmpty()) base = String.valueOf(self.getId());
        String candidate = base;
        int suffix = 1;
        while (nameTaken(candidate, self)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** Name an object gets when the user did not choose one: user name > positive group name > id */
    private static String automaticName(PvsObject object) {
        String objectName = object.getName();
        if (!object.isNameDefault() && objectName!= null && !objectName.isEmpty()) return objectName;
        Integer group = object.getPositiveVisualGroup();
        if (group != null && group != 0) {
            String groupName = Roi.getGroupName(group);
            if (groupName != null && !groupName.isEmpty()) return groupName;
            return group.toString();
        }
        return "";
    }

    /** output group an object gets when the user did not choose one: user output group > positive visual group  > id. */
    private static int automaticOutputGroup(PvsObject object) {
        int objectOutputGroup= object.getOutputGroup();
        if (!object.isOutputGroupDefault() && objectOutputGroup!= 0) return objectOutputGroup;
        if (object.isPositiveVisualUsed()){
            int positiveGroup = object.getPositiveVisualGroup();
            if(positiveGroup!=0) return positiveGroup;}
        int id = object.getId();
        return id>255 ? (id%255)+1 : id;
    }

    private boolean nameTaken(String name, PvsObject self) {
        for (PvsObject object : objects) {
            if (object != self && object.getName().equals(name)) return true;
        }
        return false;
    }

    private static int clampGroupId(Integer value, int fallback) {
        if (value == null) return fallback;
        if (value < 1 || value > MAX_GROUP_ID) return fallback;
        return value;
    }
}