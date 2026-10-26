package fr.curie.miclearning.apposeplugin.sampcs;

import ij.gui.Roi;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Table model backing the concept table of {@link Sam3ImagePcs_Plugin} and 's dialog: one row
 * per {@link PcsConcept}, one column per editable property.
 */
public class Sam3ConceptTableModel extends AbstractTableModel {

    public static final int COL_NAME = 0;
    public static final int COL_TEXT_PROMPT = 1;
    public static final int COL_POSITIVE_VISUAL = 2;
    public static final int COL_NEGATIVE_VISUAL = 3;
    public static final int COL_OUTPUT_GROUP = 4;

    private static final String[] COLUMN_NAMES = {
            "Output name", "Text prompt", "Positive visual prompt", "Negative visual prompt", "Output value"
    };

    private static final int MAX_GROUP_ID = 255;

    // list of all concepts registered in the table
    private final List<PcsConcept> concepts = new ArrayList<>();
    private int nextId = 1; // never reused within a session

    // false while the RoiManager holds no usable ROI on any processed frame ; driven by the dialog's ROI poll.
    private boolean roiPromptsAvailable = false;

    @Override
    public int getRowCount() {
        return concepts.size();
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
            default:
                return String.class;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        if ((columnIndex == COL_POSITIVE_VISUAL || columnIndex == COL_NEGATIVE_VISUAL) && !roiPromptsAvailable) {
            return false; // no ROI to use as prompts
        }
        return true; // (the name and output-group columns stay editable even when greyed out)
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        PcsConcept concept = concepts.get(rowIndex);
        switch (columnIndex) {
            case COL_NAME: return concept.getName();
            case COL_TEXT_PROMPT: return concept.getTextPrompt();
            case COL_POSITIVE_VISUAL: return concept.getPositiveVisualGroup();
            case COL_NEGATIVE_VISUAL: return concept.getNegativeVisualGroup();
            case COL_OUTPUT_GROUP: return concept.getOutputGroup();
            default: throw new IllegalArgumentException("Unknown column: " + columnIndex);
        }
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        PcsConcept concept = concepts.get(rowIndex);
        switch (columnIndex) {
            case COL_NAME: {
                String newName = value == null ? "" : value.toString().trim();
                if (newName.isEmpty()) {
                    // empty name: give control back to automatic names
                    concept.clearUserName();
                    concept.setName(uniqueName(automaticName(concept), concept));
                } else if (!newName.equals(concept.getName())) {
                    // only count it as "a user rename it" if the cell content actually differs
                    concept.setUserName(uniqueName(newName, concept));
                }
                break;
            }
            case COL_TEXT_PROMPT: {
                String newText = value == null ? "" : value.toString();
                concept.setTextPrompt(newText);
                // if a text prompt is given and the user never renamed the concept: change name to prompt
                concept.setName(uniqueName(automaticName(concept), concept));
                break;
            }
            case COL_POSITIVE_VISUAL:
                concept.setPositiveVisualGroup((Integer) value);
                // the negative prompt can't reuse the positive group - drop it if it now collides
                if (Objects.equals(concept.getNegativeVisualGroup(), concept.getPositiveVisualGroup())) {
                    concept.setNegativeVisualGroup(null);
                }
                //if the group given as positive prompt has a name & the user never renamed the concept
                // & there is no text prompt : change the name to the one of the positive prompt group
                concept.setName(uniqueName(automaticName(concept), concept));
                concept.setOutputGroup(clampGroupId(automaticOutputGroup(concept), concept.getOutputGroup()));
                break;
            case COL_NEGATIVE_VISUAL:
                concept.setNegativeVisualGroup((Integer) value);
                break;
            case COL_OUTPUT_GROUP:
                int newGroup = value == null ? 0 : (Integer) value;
                if (newGroup == 0){concept.clearUserOutputGroup();
                    concept.setOutputGroup(clampGroupId(automaticOutputGroup(concept), concept.getOutputGroup()));}
                else {
                    newGroup = clampGroupId(newGroup, concept.getOutputGroup());
                    concept.setUserOutputGroup(newGroup);
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown column: " + columnIndex);
        }
        fireTableRowsUpdated(rowIndex, rowIndex); // repaint the whole row
    }

    /** Appends a new concept row with a fresh id  */
    public void addConcept() {
        PcsConcept concept = new PcsConcept(nextId++);
        concepts.add(concept);
        int row = concepts.size() - 1;
        fireTableRowsInserted(row, row);
    }

    /** Removes the row at {@code rowIndex} */
    public void removeConcept(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= concepts.size()) return;
        concepts.remove(rowIndex);
        fireTableRowsDeleted(rowIndex, rowIndex);
    }

    public PcsConcept getConcept(int rowIndex) {
        return concepts.get(rowIndex);
    }

    /** Read-only view of the current concepts, in row order. */
    public List<PcsConcept> getConcepts() {
        return Collections.unmodifiableList(concepts);
    }

    /**
     * Whether the visual-prompt columns accept input (i.e. at least one usable ROI exists on a
     * processed frame/on the prompt frame). Fires a table update only when the value actually changes.
     */
    public void setRoiPromptsAvailable(boolean available) {
        if (this.roiPromptsAvailable == available) return;
        this.roiPromptsAvailable = available;
        fireTableDataChanged();
    }

    /**
     * Clears any visual-prompt group that is no longer selectable: a positive or negative group
     * absent from {@code availableGroupIds}, or a negative group that now equals its row's positive
     * group. Fires a table update only when something changed.
     *
     * @return {@code true} if at least one concept's positive or negative visual group was cleared
     */
    public boolean reconcileVisualGroups(Set<Integer> availableGroupIds) {
        boolean changed = false;
        for (PcsConcept concept : concepts) {
            if (concept.getPositiveVisualGroup() != null
                    && !availableGroupIds.contains(concept.getPositiveVisualGroup())) {
                concept.setPositiveVisualGroup(null);
                changed = true;
            }
            if (concept.getNegativeVisualGroup() != null
                    && (!availableGroupIds.contains(concept.getNegativeVisualGroup())
                        || Objects.equals(concept.getNegativeVisualGroup(), concept.getPositiveVisualGroup()))) {
                concept.setNegativeVisualGroup(null);
                changed = true;
            }
        }
        if (changed) fireTableDataChanged();
        return changed;
    }

    /** Trims {@code desired} (blank -> the concept's numeric id), then appends "-1", "-2", ... until unique among the other rows. */
    private String uniqueName(String desired, PcsConcept self) {
        String base = desired == null ? "" : desired.trim();
        if (base.isEmpty()) base = String.valueOf(self.getId());
        String candidate = base;
        int suffix = 1;
        while (nameTaken(candidate, self)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    /** Name a concept gets when the user did not choose one: user name > text prompt > positive group name > id. */
    private static String automaticName(PcsConcept concept) {
        String conceptName = concept.getName();
        if (!concept.isNameDefault() && conceptName!= null && !conceptName.isEmpty()) return conceptName;
        String textPrompt = concept.getTextPrompt();
        if (concept.isTextPromptUsed() && textPrompt!=null && !textPrompt.isEmpty()) return textPrompt;
        Integer group = concept.getPositiveVisualGroup();
        if (group != null && group != 0) {
            String groupName = Roi.getGroupName(group);
            if (groupName != null && !groupName.isEmpty()) return groupName;
            return group.toString();
        }
        return "";
    }

    /** output group a concept gets when the user did not choose one: user output group > positive visual group  > id. */
    private static int automaticOutputGroup(PcsConcept concept) {
        int conceptOutputGroup= concept.getOutputGroup();
        if (!concept.isOutputGroupDefault() && conceptOutputGroup!= 0) return conceptOutputGroup;
        if (concept.isPositiveVisualUsed()){
            int positiveGroup = concept.getPositiveVisualGroup();
            if(positiveGroup!=0) return positiveGroup;}
        int id = concept.getId();
        return id>255 ? (id%255)+1 : id;
    }

    private boolean nameTaken(String name, PcsConcept self) {
        for (PcsConcept concept : concepts) {
            if (concept != self && concept.getName().equals(name)) return true;
        }
        return false;
    }

    private static int clampGroupId(Integer value, int fallback) {
        if (value == null) return fallback;
        if (value < 1 || value > MAX_GROUP_ID) return fallback;
        return value;
    }
}