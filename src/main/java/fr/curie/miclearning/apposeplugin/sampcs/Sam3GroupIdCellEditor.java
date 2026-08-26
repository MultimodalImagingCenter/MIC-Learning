package fr.curie.miclearning.apposeplugin.sampcs;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable combo-box cell editor for a "group id" column of the concept table (either the
 * positive/class group id, or the negative group id)
 */
public class Sam3GroupIdCellEditor extends DefaultCellEditor {

    private final JComboBox<String> comboBox;
    private final boolean excludeOwnRowGroup;
    private List<Sam3GroupOption> availableGroups = new ArrayList<>();
    private Integer editingValue; // value the cell held when editing started - fallback if the typed text can't be parsed

    /**
     * @param excludeOwnRowGroup if true, the row's own class/group id (from {@link Sam3ConceptTableModel})
     *                           is left out of the dropdown (used for the "negative group" column, since
     *                           a concept can't use its own group as its negative prompt)
     */
    public Sam3GroupIdCellEditor(boolean excludeOwnRowGroup) {
        super(createComboBox());
        this.comboBox = castComponent();
        this.excludeOwnRowGroup = excludeOwnRowGroup;
    }

    private static JComboBox<String> createComboBox() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        return combo;
    }

    @SuppressWarnings("unchecked")
    private JComboBox<String> castComponent() {
        return (JComboBox<String>) getComponent();
    }

    /** Replaces the candidate groups shown in the dropdown (does not affect an edit already in progress). */
    public void setAvailableGroups(List<Sam3GroupOption> options) {
        this.availableGroups = new ArrayList<>(options);
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        editingValue = (Integer) value;

        Integer ownRowGroupId = null;
        if (excludeOwnRowGroup && table.getModel() instanceof Sam3ConceptTableModel) {
            ownRowGroupId = ((Sam3ConceptTableModel) table.getModel()).getConcept(row).getClassId();
        }

        comboBox.removeAllItems();
        for (Sam3GroupOption option : availableGroups) {
            if (ownRowGroupId != null && option.getGroupId() == ownRowGroupId) continue;
            comboBox.addItem(option.toString());
        }

        return super.getTableCellEditorComponent(table, findLabel(editingValue), isSelected, row, column);
    }

    @Override
    public Object getCellEditorValue() {
        Object raw = super.getCellEditorValue();
        Integer parsed = parseGroupId(raw == null ? null : raw.toString());
        return parsed != null ? parsed : editingValue;
    }

    /** Matches {@code groupId} against the current dropdown entries; falls back to the bare number if unregistered. */
    private String findLabel(Integer groupId) {
        if (groupId == null) return "";
        for (Sam3GroupOption option : availableGroups) {
            if (option.getGroupId() == groupId) return option.toString();
        }
        return String.valueOf(groupId);
    }

    /** Reads back the leading integer of a selected/typed entry, e.g. "3 (nucleus) — 8/12 frames" -> 3. */
    private static Integer parseGroupId(String text) {
        if (text == null) return null;
        text = text.trim();
        if (text.isEmpty()) return null;
        try {
            return Integer.parseInt(text.split(" ")[0]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Renders the same formatted label as the editor when the cell isn't being edited. */
    public static class Renderer extends DefaultTableCellRenderer {
        private final Sam3GroupIdCellEditor editor;

        public Renderer(Sam3GroupIdCellEditor editor) {
            this.editor = editor;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                         boolean hasFocus, int row, int column) {
            return super.getTableCellRendererComponent(table, editor.findLabel((Integer) value), isSelected, hasFocus, row, column);
        }
    }
}