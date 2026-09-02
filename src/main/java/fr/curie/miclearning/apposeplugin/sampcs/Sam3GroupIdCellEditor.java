package fr.curie.miclearning.apposeplugin.sampcs;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable combo-box cell editor for a visual-prompt group column of the concept table (the
 * positive or the negative visual group).
 *
 * <p>The cell value is an {@link Integer} RoiManager group id, or {@code null} when the prompt is
 * unused - shown as a greyed-out {@value #NOT_USED_LABEL}. The user can pick a group from the
 * dropdown or type an id directly.
 */
public class Sam3GroupIdCellEditor extends DefaultCellEditor {

    static final String NOT_USED_LABEL = "not used";
    static final Color NOT_USED_COLOR = Color.GRAY;

    /** Background for a cell that can't be edited at all (no ROI on any frame) */
    static final Color DISABLED_BG = UIManager.getColor("TextField.inactiveBackground") != null
            ? UIManager.getColor("TextField.inactiveBackground")
            : new Color(0xEC, 0xEC, 0xEC);

    private final JComboBox<String> comboBox;
    private final boolean excludeOwnRowPositiveGroup; // true for the "negative prompt"
    private List<Sam3GroupOption> availableGroups = new ArrayList<>();
    private Integer editingValue; // cell value when editing started (fallback)

    /**
     * @param excludeOwnRowPositiveGroup if true, the row's own positive visual group is left out of
     *                                   the dropdown (used for the "negative visual" column)
     */
    public Sam3GroupIdCellEditor(boolean excludeOwnRowPositiveGroup) {
        super(createComboBox());
        this.comboBox = castComponent();
        this.excludeOwnRowPositiveGroup = excludeOwnRowPositiveGroup;
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

    /** Replaces the candidate groups shown in the dropdown */
    public void setAvailableGroups(List<Sam3GroupOption> options) {
        this.availableGroups = new ArrayList<>(options);
    }

    /** Populate comboBox with all Roi groups available, except the id to exclude, if any */
    @Override
    public Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
        editingValue = (Integer) value;

        Integer excludedGroupId = null;
        if (excludeOwnRowPositiveGroup && table.getModel() instanceof Sam3ConceptTableModel) {
            excludedGroupId = ((Sam3ConceptTableModel) table.getModel()).getConcept(row).getPositiveVisualGroup();
        }

        comboBox.removeAllItems();
        comboBox.addItem(NOT_USED_LABEL);
        for (Sam3GroupOption option : availableGroups) {
            if (excludedGroupId != null && option.getGroupId() == excludedGroupId) continue;
            comboBox.addItem(option.toString());
        }

        Component comp = super.getTableCellEditorComponent(table, findLabel(editingValue), isSelected, row, column);

        // select the whole text
        Component comboEditorComponent = comboBox.getEditor().getEditorComponent();
        if (comboEditorComponent instanceof JTextField) {
            ((JTextField) comboEditorComponent).selectAll();
        }

        return comp;
    }

    @Override
    public Object getCellEditorValue() {
        Object raw = super.getCellEditorValue();
        String text = raw == null ? "" : raw.toString().trim();
        if (text.isEmpty() || NOT_USED_LABEL.equals(text)) return null; // cell seen as empty if content is "not used"

        Integer parsed = parseGroupId(text);
        if (parsed != null && isAvailableGroup(parsed)) return parsed;
        return editingValue; // typed value isn't a known group - keep the previous choice
    }

    private boolean isAvailableGroup(int groupId) {
        for (Sam3GroupOption option : availableGroups) {
            if (option.getGroupId() == groupId) return true;
        }
        return false;
    }

    /** Formats {@code groupId} for display: the full option label if known, {@value #NOT_USED_LABEL} if null. */
    String findLabel(Integer groupId) {
        if (groupId == null) return NOT_USED_LABEL;
        for (Sam3GroupOption option : availableGroups) {
            if (option.getGroupId() == groupId) return option.toString();
        }
        return String.valueOf(groupId);
    }

    /** Reads back the leading integer of a selected/typed entry, e.g. "3 (nucleus) - 8/12 frames" -> 3. */
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
            Integer groupId = (Integer) value;
            Component comp = super.getTableCellRendererComponent(
                    table, editor.findLabel(groupId), isSelected, hasFocus, row, column);
            if (!isSelected) {
                comp.setForeground(groupId == null ? NOT_USED_COLOR : table.getForeground()); // gray text if visual prompt unused
                comp.setBackground(table.isCellEditable(row, column) ? table.getBackground() : DISABLED_BG); // gray prompt if visual prompt not allowed (no ROI on any frame)
            }
            return comp;
        }
    }
}