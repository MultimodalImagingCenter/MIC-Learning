package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.AbstractPromptDialog;
import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.ModelPathDialogs;
import fr.curie.miclearning.apposeplugin.RoiGroupOption;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.ImagePlus;

import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Non-modal Swing dialog base for {@link Sam3ImageDialog} and {@link Sam3VideoDialog} :
 * lets the user define an arbitrary number of concepts, plus model path, detection parameters and output options.
 */
public abstract class Sam3ConceptDialogBase extends AbstractPromptDialog {
    static final String VISUAL_PROMPT_RESET_OWNER = "visualPromptReset";

   // concept table
    final Sam3ConceptTableModel tableModel = new Sam3ConceptTableModel();
    final JTable table = new JTable(tableModel);
    final Sam3GroupIdCellEditor positiveGroupEditor = new Sam3GroupIdCellEditor(false);
    final Sam3GroupIdCellEditor negativeGroupEditor = new Sam3GroupIdCellEditor(true);

    final Sam3ModelParameters modelParams = new Sam3ModelParameters();

    // interface elements specific to concepts (model path/output checkboxes live in the base)
    JCheckBox semanticMaskCB;
    JCheckBox instanceMaskPerClassCB;

    // populated only once the user presses OK
    Sam3ModelParameters finalModelParam;
    List<PcsConcept> concepts;


    protected Sam3ConceptDialogBase(ImagePlus imp, int nFrames, int axis, String dialogTitle) {
        super(imp, nFrames, axis, dialogTitle, "miclearning.lastmodeldir.sam3");
        finishInit();
    }

    @Override
    protected JTable getPromptTable() {
        return table;
    }

    @Override
    protected String pluginLogName() {
        return "SAM3";
    }

    public Sam3ModelParameters getFinalModelParam() {return finalModelParam;}
    public List<PcsConcept> getConcepts() {return concepts;}

    // ==== UI construction ====

    @Override
    protected abstract void buildUi();

    JPanel buildModelPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Model"), BorderLayout.NORTH);

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0;
        JLabel modelLabel = new JLabel("Model path:");
        fieldsPanel.add(modelLabel, c);

        modelPathField = new JTextField(ModelPathDialogs.resolveDefaultModelPath(prefLastModelKey, "sam3.pt"), 40);
        c.gridx = 1; c.weightx = 1;
        fieldsPanel.add(modelPathField, c);

        String modelPathHint = "Path to the SAM3 model checkpoint (.pt file)";
        helpBar.attachHelp(modelLabel, modelPathHint);
        helpBar.attachHelp(modelPathField, modelPathHint);

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            File current = new File(modelPathField.getText());
            if (current.exists()) chooser.setSelectedFile(current);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                modelPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        c.gridx = 2; c.weightx = 0;
        fieldsPanel.add(browseButton, c);

        JButton instructionsButton = new JButton("Instructions to download SAM3 model");
        instructionsButton.addActionListener(e -> ModelPathDialogs.showDownloadInstructions(
                this,
                "Instructions to download SAM3 model",
                "The SAM3 checkpoints are available on the SAM3 HuggingFace repository (huggingface.co/facebook/sam3).\n" +
                        "To download them, you need to:\n" +
                        "   1/ Create a Hugging Face account.\n" +
                        "   2/ Inside Meta's SAM3 HuggingFace repository, open the \"Files and versions\" tab.\n" +
                        "   3/ Request access. " +
                        "The authorization process usually takes no more than one hour.\n" +
                        "   4/ Once your access request is approved, you can download the checkpoint file: \"sam3.pt\".\n" +
                        "   5/ If it does not exist, create a \"models\" folder inside the ImageJ directory. " +
                        "Copy the model file inside it.\n"));
        c.anchor = GridBagConstraints.CENTER; c.fill = GridBagConstraints.NONE;
        c.gridx = 0; c.gridy = 1; c.gridwidth = 3; c.weightx = 0;
        fieldsPanel.add(instructionsButton, c);

        panel.add(fieldsPanel, BorderLayout.CENTER);
        return panel;
    }

    JPanel buildConceptsPanel() {
        // create table
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());

        JLabel titleLabel = createSectionTitle("Concepts");
        helpBar.attachHelp(titleLabel, "A concept = one object class to find. Define it with a text prompt, "
                + "a visual prompt (ROI in the ROI Manager), or both.");
        panel.add(titleLabel, BorderLayout.NORTH);

        // replace the default editors so starting to edit a cell selects its whole content
        table.setDefaultEditor(String.class, createSelectAllTextEditor());
        table.setDefaultEditor(Integer.class, createSelectAllIntegerEditor());

        // set each column renderer (and optional editor)
        // Name column : greyed (but still editable) while no ROI output is selected
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NAME).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected)
                    comp.setForeground(outputWithNameSelected() ? t.getForeground() : Sam3GroupIdCellEditor.NOT_USED_COLOR);
                return comp;
            }
        });

        // Positive / negative visual: editable group combo, "not used" == null
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_POSITIVE_VISUAL).setCellEditor(positiveGroupEditor);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_POSITIVE_VISUAL).setCellRenderer(new Sam3GroupIdCellEditor.Renderer(positiveGroupEditor));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_VISUAL).setCellEditor(negativeGroupEditor);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_VISUAL).setCellRenderer(new Sam3GroupIdCellEditor.Renderer(negativeGroupEditor));

        // Output group: greyed (but still editable) while no ROI output is selected
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_OUTPUT_GROUP).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected)
                    comp.setForeground(outputWithRoiGroupSelected() ? t.getForeground() : Sam3GroupIdCellEditor.NOT_USED_COLOR);
                return comp;
            }
        });

        // set each column preferred width scaled
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NAME).setPreferredWidth(scaled(100));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_TEXT_PROMPT).setPreferredWidth(scaled(150));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_POSITIVE_VISUAL).setPreferredWidth(scaled(160));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_VISUAL).setPreferredWidth(scaled(160));
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_OUTPUT_GROUP).setPreferredWidth(scaled(90));
        table.setRowHeight(scaled(22));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);


        // hover hints per column header, defined with mouse position
        // non specific hints
        JTableHeader header = table.getTableHeader();
        helpBar.attachPositionalHelp(header, point -> {
            int viewCol = header.columnAtPoint(point);
            if (viewCol < 0) return null;
            int modelCol = table.convertColumnIndexToModel(viewCol);
            String roiGroupNotUsed = outputWithRoiGroupSelected() ? "" : "\nUnused for instance masks.";
            String nameNotUsed = outputWithNameSelected() ? "" : "\nUnused for instance and semantic masks.";
            String text;
            switch (modelCol) {
                case Sam3ConceptTableModel.COL_NAME:
                    text = "Name used for this concept's outputs (ROI names, logs). Must be unique."
                            + nameNotUsed;
                    break;
                case Sam3ConceptTableModel.COL_TEXT_PROMPT:
                    text = "Text description of the object (e.g. 'green cell'). One prompt applies to every processed frame.";
                    break;
                case Sam3ConceptTableModel.COL_OUTPUT_GROUP:
                    text = "Id tagging this concept's outputs: the ROI group for added ROIs, and the pixel value for the semantic mask."
                            + roiGroupNotUsed;
                    break;
                default:
                    return null;
            }
            return new DialogHelpBar.Hint(text, DialogHelpBar.DEFAULT_HELP_COLOR);
        });


        // create scroll pane for the table
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(scaled(700), scaled(160)));
        panel.add(scrollPane, BorderLayout.CENTER);

        // buttons to add and remove concepts
        JButton addButton = new JButton("Add concept");
        addButton.addActionListener(e -> {
            tableModel.addConcept();
            helpBar.clearWarning("promptListEmpty");
            okButton.setEnabled(true);
        });
        helpBar.attachHelp(addButton, "Adds a new concept row.");

        JButton removeButton = new JButton("Remove selected");
        removeButton.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().cancelCellEditing();
            int row = table.getSelectedRow();
            if (row >= 0) tableModel.removeConcept(row);
            // if no concept (empty table) : unable okButton + display warning
            if (tableModel.getConcepts().isEmpty()) {
                helpBar.warn("promptListEmpty", "Please enter at least one prompt");
                okButton.setEnabled(false);
            }
        });
        helpBar.attachHelp(removeButton, "Removes the currently selected concept row.");

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonsRow.add(addButton);
        buttonsRow.add(removeButton);
        panel.add(buttonsRow, BorderLayout.SOUTH);

        return panel;
    }


    JPanel buildParametersPanel() {
        JPanel panel = new JPanel();
        panel.setBorder(BorderFactory.createEtchedBorder());
        return panel;
    }

    /**
     * create a row with a label and a textfield (e.g. for parameters in {@link Sam3ImageDialog}
     * and {@link Sam3VideoDialog} parameters panel)
     */
    void addLabeledField(JPanel panel, GridBagConstraints c, int row, String label,
                         JTextField field, String hint) {
        c.gridx = 0; c.gridy = row;
        JLabel jLabel = new JLabel(label);
        panel.add(jLabel, c);
        c.gridx = 1;
        panel.add(field, c);
        helpBar.attachHelp(jLabel, hint);
        helpBar.attachHelp(field, hint);
    }

    JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Outputs"), BorderLayout.NORTH);

        JPanel checkboxesPanel = new JPanel();
        checkboxesPanel.setLayout(new BoxLayout(checkboxesPanel, BoxLayout.Y_AXIS));

        addBoxRoisCB = new JCheckBox("Add bounding boxes to ROI Manager", false);
        addShapeRoisCB = new JCheckBox("Add shape ROIs to ROI Manager", true);
        instanceMaskCB = new JCheckBox("Create instance masks (unique value per instance)", false);
        semanticMaskCB = new JCheckBox("Create semantic masks (unique value per class)", false);
        instanceMaskPerClassCB = new JCheckBox("Create instance mask per class (one channel per class; a per-frame hyperstack for stacks)", false);

        // the "Output group" column is only meaningful for ROI outputs - repaint it when that changes
        addBoxRoisCB.addItemListener(e -> table.repaint());
        addShapeRoisCB.addItemListener(e -> table.repaint());

        helpBar.attachHelp(addBoxRoisCB, "Adds one rectangle ROI per detection, named 'box_' + the object's output name. "
                + "\nRoi placed in the ROI group set by that object's \"Output group\".");
        helpBar.attachHelp(addShapeRoisCB, "Adds one shape ROI per detection, named 'mask_' + the object's output name. "
                + "\nRoi placed in the ROI group set by that object's \"Output group\".");
        helpBar.attachHelp(instanceMaskCB, "One image per frame where each detected instance gets its own unique pixel value.");
        helpBar.attachHelp(semanticMaskCB, "One image per frame where each pixel takes the value of its concept's \"Output group\" "
                + "(0 = background).");
        helpBar.attachHelp(instanceMaskPerClassCB, "Like instance masks, but one slice per concept ; "
                + "a per-frame hyperstack for stacks.");

        checkboxesPanel.add(addBoxRoisCB);
        checkboxesPanel.add(addShapeRoisCB);
        checkboxesPanel.add(instanceMaskCB);
        checkboxesPanel.add(semanticMaskCB);
        checkboxesPanel.add(instanceMaskPerClassCB);

        panel.add(checkboxesPanel, BorderLayout.CENTER);
        return panel;
    }

    protected abstract JPanel buildScopePanel();

    // ==== live RoiManager refresh ====

    @Override
    protected void refreshGroupOptions() {
        List<RoiGroupOption> options = computeGroupOptions();
        positiveGroupEditor.setAvailableGroups(options); // updates the groups available in the "positive prompt" columns
        negativeGroupEditor.setAvailableGroups(options);

        // for Roi positive & negative prompts columns : update available ROI groups
        Set<Integer> availableGroupIds = new HashSet<>();
        for (RoiGroupOption option : options) availableGroupIds.add(option.getGroupId());
        if (tableModel.reconcileVisualGroups(availableGroupIds)) {
            helpBar.showInfo(VISUAL_PROMPT_RESET_OWNER, "A previously selected ROI group is no longer available - "
                    + "visual prompt turned off for the affected concept(s).", DialogHelpBar.DEFAULT_NOTIFICATION_COLOR);
        }

        // for Roi positive & negative prompts columns : switch to unabled if no ROI at all
        tableModel.setRoiPromptsAvailable(!options.isEmpty());
        table.repaint();
    }

    /** Return Roi group available (i.e. roi groups on any processed frame for {@link Sam3ImagePcs_Plugin},
     * roi groups on the prompt frame for {@link Sam3VideoPcs_Plugin}*/
    abstract List<RoiGroupOption> computeGroupOptions();

    /** True when at least an output where a concept's output roi group matters is selected (all outputs except instance masks). */
    boolean outputWithRoiGroupSelected() {
        return (addBoxRoisCB != null && addBoxRoisCB.isSelected())
                || (addShapeRoisCB != null && addShapeRoisCB.isSelected()
                || (semanticMaskCB != null && semanticMaskCB.isSelected()));
    }

    /** True when at least an output where a concept's output name matters is selected (roi outputs and instance mask per stack). */
    boolean outputWithNameSelected() {
        return (addBoxRoisCB != null && addBoxRoisCB.isSelected())
                || (addShapeRoisCB != null && addShapeRoisCB.isSelected()
                || (instanceMaskPerClassCB != null && instanceMaskPerClassCB.isSelected()));
    }

    void onScopeChanged() {
        refreshGroupOptions();
    }; // why not refreshGroupOption directly ??

    /**
     * Runs the validation/collection shared by both dialogs, populating {@link #modelPath},
     * {@link #concepts}, {@link #finalModelParam} and {@link #outputOptions} on success.
     * Does NOT close the dialog or set {@link #approved} - that decision belongs to each
     * subclass's {@link #onOk()}, since the caller must know whether validation actually
     * succeeded before disposing the window.
     *
     * @return {@code true} if everything is valid and the fields above were populated,
     *         {@code false} if the dialog should stay open so the user can fix something
     */
    protected boolean validateAndCollect() {
        if (imp.getWindow() == null) {
            onImageClosed();
            return false;
        }
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return false;

        String path = resolveAndValidateModelPath();
        if (path == null) return false; // does not close window, because "dispose()" is not called. Usr can try again

        // check concept validity
        List<PcsConcept> currentConcepts = tableModel.getConcepts();
        List<String> errors = validateConcepts(currentConcepts);

        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors), "Invalid concept(s)", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // if no output is selected, print warning
        outputOptions = collectOutputOptions();
        if (outputOptions.noOutputSelected()) {
            int choice = JOptionPane.showOptionDialog(this,
                    "No output is selected (no ROI, no mask). The plugin will run but produce nothing except logs.\n"
                            + "Do you want to continue anyway?",
                    "No output selected",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null,
                    new Object[]{"Continue", "Return"},
                    "Return");
            if (choice != 0) return false; // 0 = the user clicked on "Continue"
        }

        modelPath = path;
        concepts = new ArrayList<>(currentConcepts);
        finalModelParam = collectDetectionParams();

        logWarnings(currentConcepts);

        return true;
    }

    /** Called when the user presses OK: must call {@link #validateAndCollect()} and only set
     * {@link #approved} + {@link #dispose()} if it returned {@code true}. */
    @Override
    protected abstract void onOk();

    private String resolveAndValidateModelPath() {
        return ModelPathDialogs.validateAndSaveModelPath(modelPathField.getText().trim(), prefLastModelKey);
    }

    List<String> validateConcepts(List<PcsConcept> currentConcepts) {
        List<String> errors = new ArrayList<>();
        if (currentConcepts.isEmpty()) {
            errors.add("At least one concept must be defined.");
            return errors;
        }
        Set<String> seenNames = new HashSet<>();
        for (PcsConcept concept : currentConcepts) {
            if (!concept.hasAnyPositivePrompt()) {
                errors.add("Concept " + concept.getName() + ": needs a text prompt and/or a positive visual prompt.");
            }
            if (!concept.isNegativeGroupValid()) {
                errors.add("Concept " + concept.getName() + ": negative visual can't use the same group as the positive visual.");
            }
            if (!seenNames.add(concept.getName())) {
                errors.add("Duplicate concept name: " + concept.getName());
            }
        }
        return errors;
    }

    abstract Sam3ModelParameters collectDetectionParams();

    double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private DetectionUtils.OutputOptions collectOutputOptions() {
        DetectionUtils.OutputOptions options = new DetectionUtils.OutputOptions();
        options.deletePreviousRoi = false;
        options.addToRoiManagerBB = addBoxRoisCB.isSelected();
        options.addToRoiManagerShapes = addShapeRoisCB.isSelected();
        options.createInstanceMask = instanceMaskCB.isSelected();
        options.createSemanticMask = semanticMaskCB.isSelected();
        options.createInstanceMaskPerClass = instanceMaskPerClassCB.isSelected();
        return options;
    }

    protected abstract void logWarnings(List<PcsConcept> currentConcepts);

    protected abstract List<Integer> currentFrameRange();

}
