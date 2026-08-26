package fr.curie.miclearning.apposeplugin.sampcs;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Table model backing the concept table of {@link Sam3SingleImgPcs_Plugin}'s dialog: one row
 * per {@link Sam3Concept}, one column per editable property.
 */
public class Sam3ConceptTableModel extends AbstractTableModel {

    public static final int COL_TEXT_USED = 0;
    public static final int COL_TEXT_PROMPT = 1;
    public static final int COL_POSITIVE_VISUAL_USED = 2;
    public static final int COL_GROUP_ID = 3;
    public static final int COL_NEGATIVE_VISUAL_USED = 4;
    public static final int COL_NEGATIVE_GROUP_ID = 5;

    private static final String[] COLUMN_NAMES = {
            "Text", "Text prompt", "Positive visual", "Group ID", "Negative visual", "Negative group"
    };

    private final List<Sam3Concept> concepts = new ArrayList<>();

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
            case COL_TEXT_USED:
            case COL_POSITIVE_VISUAL_USED:
            case COL_NEGATIVE_VISUAL_USED:
                return Boolean.class;
            case COL_GROUP_ID:
            case COL_NEGATIVE_GROUP_ID:
                return Integer.class;
            default:
                return String.class;
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        Sam3Concept concept = concepts.get(rowIndex);
        switch (columnIndex) {
            case COL_TEXT_PROMPT:
                return concept.isTextPromptUsed();
            case COL_NEGATIVE_GROUP_ID:
                return concept.isNegativeVisualUsed();
            default:
                return true;
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Sam3Concept concept = concepts.get(rowIndex);
        switch (columnIndex) {
            case COL_TEXT_USED: return concept.isTextPromptUsed();
            case COL_TEXT_PROMPT: return concept.getTextPrompt();
            case COL_POSITIVE_VISUAL_USED: return concept.isPositiveVisualUsed();
            case COL_GROUP_ID: return concept.getClassId();
            case COL_NEGATIVE_VISUAL_USED: return concept.isNegativeVisualUsed();
            case COL_NEGATIVE_GROUP_ID: return concept.getNegativeGroupId();
            default: throw new IllegalArgumentException("Unknown column: " + columnIndex);
        }
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        Sam3Concept concept = concepts.get(rowIndex);
        switch (columnIndex) {
            case COL_TEXT_USED: concept.setTextPromptUsed((Boolean) value); break;
            case COL_TEXT_PROMPT: concept.setTextPrompt((String) value); break;
            case COL_POSITIVE_VISUAL_USED: concept.setPositiveVisualUsed((Boolean) value); break;
            case COL_GROUP_ID: concept.setClassId((Integer) value); break;
            case COL_NEGATIVE_VISUAL_USED: concept.setNegativeVisualUsed((Boolean) value); break;
            case COL_NEGATIVE_GROUP_ID: concept.setNegativeGroupId((Integer) value); break;
            default: throw new IllegalArgumentException("Unknown column: " + columnIndex);
        }
        fireTableRowsUpdated(rowIndex, rowIndex);
    }

    /** Appends a new concept row with the given class/group id and fires the insert event. */
    public Sam3Concept addConcept(int classId) {
        Sam3Concept concept = new Sam3Concept(classId);
        concepts.add(concept);
        int row = concepts.size() - 1;
        fireTableRowsInserted(row, row);
        return concept;
    }

    /** Removes the row at {@code rowIndex}, if valid. */
    public void removeConcept(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= concepts.size()) return;
        concepts.remove(rowIndex);
        fireTableRowsDeleted(rowIndex, rowIndex);
    }

    public Sam3Concept getConcept(int rowIndex) {
        return concepts.get(rowIndex);
    }

    /** Read-only view of the current concepts, in row order. */
    public List<Sam3Concept> getConcepts() {
        return Collections.unmodifiableList(concepts);
    }
}