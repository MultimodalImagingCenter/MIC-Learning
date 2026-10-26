package fr.curie.miclearning.apposeplugin.sampvs;

import fr.curie.miclearning.apposeplugin.*;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import org.checkerframework.checker.nullness.qual.NonNull;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * Non-modal Swing dialog for {@link SamPvsVideo_Plugin}: lets the user define an arbitrary
 * number of objects (visual-prompt-only, no text/class), the model path, tracking parameters and
 * output options.
 */
public class PvsVideoDialog extends AbstractPromptDialog {

    private static final String VISUAL_PROMPT_RESET_OWNER = "visualPromptReset";
    private static final String NO_USABLE_ROI_OWNER = "noRoiAtAll";

    // object table
    private final PvsObjectTableModel tableModel = new PvsObjectTableModel();
    private final JTable table = new JTable(tableModel);
    private final PvsGroupIdCellEditor positiveGroupEditor = new PvsGroupIdCellEditor(false);
    private final PvsGroupIdCellEditor negativeGroupEditor = new PvsGroupIdCellEditor(true);

    private final PvsModelParameters modelParams = new PvsModelParameters();

    // interface elements specific to objects (model path/output checkboxes live in the base)
    private JTextField lastFrameField;
    private JCheckBox semanticMaskCB;
    private JCheckBox instanceMaskPerClassCB;

    // populated only once the user presses OK
    private PvsModelParameters finalModelParams;
    private List<PvsObject> objects;
    private int chosenLastFrame; // 1-indexed

    public PvsVideoDialog(ImagePlus imp, int nFrames, int axis) {
        super(imp, nFrames, axis, "SAM Promptable Visual Segmentation on Video", "miclearning.lastmodeldir.sampvs");
        finishInit();
    }

    @Override
    protected JTable getPromptTable() {
        return table;
    }

    @Override
    protected String pluginLogName() {
        return "SAM PVS";
    }

    public PvsModelParameters getFinalModelParams() {return finalModelParams;}
    public List<PvsObject> getObjects() {return objects;}
    public int getChosenLastFrame() {return chosenLastFrame;}

    // ==== UI construction ====

    @Override
    protected void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);
        helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());

        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildModelPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildObjectsPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildParametersPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildOutputPanel());

        content.add(buildButtonsPanel());

        // start with one empty object row
        tableModel.addObject();
    }

    private JPanel buildModelPanel() {
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

        modelPathField = new JTextField(ModelPathDialogs.resolveDefaultModelPath(prefLastModelKey,
                "sam2.1_hiera_tiny.pt", "sam2.1_hiera_small.pt",
                "sam2.1_hiera_base_plus.pt", "sam2.1_hiera_large.pt",
                "sam2.pt", "sam3.pt"), 40);
        c.gridx = 1; c.weightx = 1;
        fieldsPanel.add(modelPathField, c);

        String modelPathHint = "Path to a SAM2 or SAM3 model checkpoint (.pt file).";
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

        JButton instructionsButton = getDownloadIntructionsJButton();
        c.anchor = GridBagConstraints.CENTER; c.fill = GridBagConstraints.NONE;
        c.gridx = 0; c.gridy = 1; c.gridwidth = 3; c.weightx = 0;
        fieldsPanel.add(instructionsButton, c);

        panel.add(fieldsPanel, BorderLayout.CENTER);
        return panel;
    }

    private @NonNull JButton getDownloadIntructionsJButton() {
        JButton instructionsButton = new JButton("Instructions to download SAM2/SAM3 model");
        instructionsButton.addActionListener(e -> ModelPathDialogs.showDownloadInstructions(
                this,
                "Instructions to download a SAM model",
                "SAM3 model\n" +
                        "SAM3 checkpoints are available from Meta's SAM3 HuggingFace repository (https://huggingface.co/facebook/sam3).\n" +
                        "To download them, you need to:\n" +
                        "   1/ Create a Hugging Face account.\n" +
                        "   2/ Inside Meta's SAM3 HuggingFace repository, open the \"Files and versions\" tab.\n" +
                        "   3/ Request access. " +
                        "The authorization process usually takes no more than one hour.\n" +
                        "   4/ Once your access request is approved, you can download the checkpoint file: \"sam3.pt\".\n" +
                        "   5/ If it does not exist, create a \"models\" folder inside the ImageJ directory. " +
                        "Copy the model file inside it.\n"
                        + "\n" +
                        "SAM2 model\n" +
                        "SAM2 checkpoints are available from Meta's SAM2 GitHub repository (https://github.com/facebookresearch/sam2#download-checkpoints).\n" +
                        "To download them, you need to:\n" +
                        "   1/ Inside Meta's SAM3 GitHub repository, find the \"Download Checkpoints\" section. \n" +
                        "   2/ Download one of the sam2.1 checkpoint (\"sam2.1_hiera_tiny.pt\", \"sam2.1_hiera_small.pt\", \"sam2.1_hiera_base_plus.pt\" or \"sam2.1_hiera_large.pt\").\n" +
                        "   3/ If it does not exist, create a \"models\" folder inside the ImageJ directory. " +
                        "Copy the model file inside it."
        ));
        return instructionsButton;
    }

    private JPanel buildObjectsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());

        JLabel titleLabel = createSectionTitle("Objects");
        helpBar.attachHelp(titleLabel,
                "Each row defines what to segment and track, with positive visual prompt(s) (point and/or area ROIs)." +
                        "\nBy default (\"Class\" mode), every ROI of the positive group is tracked as its own independent object." +
                        "\nIn \"Object\" mode, all ROIs of the group describe one single object (see the Mode column).");
        panel.add(titleLabel, BorderLayout.NORTH);

        table.setDefaultEditor(String.class, createSelectAllTextEditor());
        table.setDefaultEditor(Integer.class, createSelectAllIntegerEditor());
        table.setDefaultEditor(PvsObject.Mode.class, new DefaultCellEditor(new JComboBox<>(PvsObject.Mode.values())));

        // Name column : greyed (but still editable) while no ROI output is selected
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_NAME).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected)
                    comp.setForeground(outputWithNameSelected() ? t.getForeground() : PvsGroupIdCellEditor.NOT_USED_COLOR);
                return comp;
            }
        });

        // Positive / negative visual: editable group combo, "not used" == null
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_POSITIVE_VISUAL).setCellEditor(positiveGroupEditor);
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_POSITIVE_VISUAL).setCellRenderer(new PvsGroupIdCellEditor.Renderer(positiveGroupEditor));
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_NEGATIVE_VISUAL).setCellEditor(negativeGroupEditor);
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_NEGATIVE_VISUAL).setCellRenderer(new PvsGroupIdCellEditor.Renderer(negativeGroupEditor));

        // Output value: greyed (but still editable) while no ROI output is selected
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_OUTPUT_GROUP).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                if (!isSelected)
                    comp.setForeground(outputWithRoiGroupSelected() ? t.getForeground() : PvsGroupIdCellEditor.NOT_USED_COLOR);
                return comp;
            }
        });

        table.getColumnModel().getColumn(PvsObjectTableModel.COL_NAME).setPreferredWidth(scaled(100));
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_POSITIVE_VISUAL).setPreferredWidth(scaled(220));
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_NEGATIVE_VISUAL).setPreferredWidth(scaled(220));
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_OUTPUT_GROUP).setPreferredWidth(scaled(90));
        table.getColumnModel().getColumn(PvsObjectTableModel.COL_MODE).setPreferredWidth(scaled(90));
        table.setRowHeight(scaled(22));
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        // hover hints per column header, defined with mouse position
        JTableHeader header = table.getTableHeader();
        helpBar.attachPositionalHelp(header, point -> {
            int viewCol = header.columnAtPoint(point);
            if (viewCol < 0) return null;
            int modelCol = table.convertColumnIndexToModel(viewCol);
            String roiGroupNotUsed = outputWithRoiGroupSelected() ? "" : "\nUnused for instance masks.";
            String nameNotUsed = outputWithNameSelected() ? "" : "\nUnused for instance and semantic masks.";
            String text;
            switch (modelCol) {
                case PvsObjectTableModel.COL_NAME:
                    text = "Name used for this object's outputs (ROI names, logs). Must be unique." + nameNotUsed;
                    break;
                case PvsObjectTableModel.COL_POSITIVE_VISUAL:
                    text = "ROI group used as a positive visual prompt (point and/or area ROIs). Required."
                            + "Can be placed on several frames: "
                            + "\nthe first frame with a usable ROI starts tracking "
                            + "this object, and any later frame refines it going forward.";
                    break;
                case PvsObjectTableModel.COL_NEGATIVE_VISUAL:
                    text = "ROI group used as a negative visual prompt (optional, POINT ROIs ONLY). "
                            + "Must differ from the Positive visual group. "
                            + "\nCan be placed on several frames.";
                    break;
                case PvsObjectTableModel.COL_OUTPUT_GROUP:
                    text = "Id tagging this object's outputs: the ROI group for output ROIs, and the pixel value for the semantic mask."
                            + roiGroupNotUsed;
                    break;
                case PvsObjectTableModel.COL_MODE:
                    text = "Class (default): each ROI in the positive group is its own independently-tracked object, named \"<name>-1\", \"<name>-2\"..."
                            + "\nA ROI on a later frame refines one of them only if it has that object's exact ROI name."
                            + "\nObject: every ROI in the positive group is part of one single tracked object.";
                    break;
                default:
                    return null;
            }
            return new DialogHelpBar.Hint(text, DialogHelpBar.DEFAULT_HELP_COLOR);
        });

        // clicking a disabled positive/negative visual cell explains why (no usable ROI yet)
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = table.columnAtPoint(e.getPoint());
                int row = table.rowAtPoint(e.getPoint());
                if (viewCol < 0 || row < 0) return;
                int modelCol = table.convertColumnIndexToModel(viewCol);
                boolean isVisualColumn = modelCol == PvsObjectTableModel.COL_POSITIVE_VISUAL
                        || modelCol == PvsObjectTableModel.COL_NEGATIVE_VISUAL;
                if (isVisualColumn && !tableModel.isCellEditable(row, modelCol)) {
                    helpBar.showInfo("visualPromptDisabled",
                            "No usable ROI (point or area) in the ROI Manager for the frame(s) being processed. "
                                    + "\nAdd one to enable visual prompts.",
                            DialogHelpBar.DEFAULT_INSTRUCTION_COLOR);
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(scaled(700), scaled(160)));
        panel.add(scrollPane, BorderLayout.CENTER);

        JButton addButton = new JButton("Add object");
        addButton.addActionListener(e -> {
            tableModel.addObject();
            helpBar.clearWarning("promptListEmpty");
            okButton.setEnabled(true);
        });
        helpBar.attachHelp(addButton, "Adds a new object row.");

        JButton removeButton = new JButton("Remove selected");
        removeButton.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().cancelCellEditing();
            int row = table.getSelectedRow();
            if (row >= 0) tableModel.removeObject(row);
            if (tableModel.getObjects().isEmpty()) {
                helpBar.warn("promptListEmpty", "Please define at least one object");
                okButton.setEnabled(false);
            }
        });
        helpBar.attachHelp(removeButton, "Removes the currently selected object row.");

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonsRow.add(addButton);
        buttonsRow.add(removeButton);
        panel.add(buttonsRow, BorderLayout.SOUTH);

        return panel;
    }

    /** Merged frame-range + parameters section. */
    private JPanel buildParametersPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Frame range and Parameters"), BorderLayout.NORTH);

        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;

        lastFrameField = new JTextField(String.valueOf(nFrames), 6);
        String lastFrameHint = "Tracking always starts forward from each object's own first prompt frame, "
                + "up to this frame.";
        addLabeledField(fieldsPanel, c, 0, "Process up to frame:", lastFrameField, lastFrameHint);
        lastFrameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) {refreshGroupOptions();}
            @Override public void removeUpdate(DocumentEvent e) {refreshGroupOptions();}
            @Override public void changedUpdate(DocumentEvent e) {refreshGroupOptions();}
        });

        JButton advancedButton = new JButton("Advanced parameters...");
        advancedButton.addActionListener(e -> PvsAdvancedParametersDialog.show(this, modelParams, getWidth()));
        helpBar.attachHelp(advancedButton, "Mask score threshold, max side length, frames in memory, and tracking score threshold.");
        c.anchor = GridBagConstraints.EAST;
        c.gridx = 2; c.weightx = 1.0;
        fieldsPanel.add(advancedButton, c);

        panel.add(fieldsPanel, BorderLayout.CENTER);
        return panel;
    }

    private void addLabeledField(JPanel panel, GridBagConstraints c, int row, String label, JTextField field, String hint) {
        c.gridx = 0; c.gridy = row;
        JLabel jLabel = new JLabel(label);
        panel.add(jLabel, c);
        c.gridx = 1;
        panel.add(field, c);
        helpBar.attachHelp(jLabel, hint);
        helpBar.attachHelp(field, hint);
    }

    private JPanel buildOutputPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Outputs"), BorderLayout.NORTH);

        JPanel checkboxesPanel = new JPanel();
        checkboxesPanel.setLayout(new BoxLayout(checkboxesPanel, BoxLayout.Y_AXIS));

        addBoxRoisCB = new JCheckBox("Add bounding boxes to ROI Manager", false);
        addShapeRoisCB = new JCheckBox("Add shape ROIs to ROI Manager", true);
        instanceMaskCB = new JCheckBox("Create instance masks (unique value per object)", false);
        semanticMaskCB = new JCheckBox("Create semantic masks (unique value per output group)", false);
        instanceMaskPerClassCB = new JCheckBox("Create instance mask per row (one channel per table row)", false);

        // the "Output group" and "Name" columns are only meaningful for some outputs - repaint them when that changes
        addBoxRoisCB.addItemListener(e -> table.repaint());
        addShapeRoisCB.addItemListener(e -> table.repaint());
        semanticMaskCB.addItemListener(e -> table.repaint());
        instanceMaskPerClassCB.addItemListener(e -> table.repaint());

        helpBar.attachHelp(addBoxRoisCB, "Adds one rectangle ROI per detection, named 'box_' + the object's output name. "
                + "\nRoi placed in the ROI group set by that object's \"Output group\".");
        helpBar.attachHelp(addShapeRoisCB, "Adds one shape ROI per detection, named 'mask_' + the object's output name. "
                + "\nRoi placed in the ROI group set by that object's \"Output group\".");
        helpBar.attachHelp(instanceMaskCB, "One image per frame where each detected instance gets its own unique pixel value.");
        helpBar.attachHelp(semanticMaskCB, "One image per frame where each pixel takes the value of its object's \"Output group\" "
                + "(0 = background).");
        helpBar.attachHelp(instanceMaskPerClassCB, "Like instance masks, but one channel per table row: "
                + "\nall objects resolved from the same row share its channel.");

        checkboxesPanel.add(addBoxRoisCB);
        checkboxesPanel.add(addShapeRoisCB);
        checkboxesPanel.add(instanceMaskCB);
        checkboxesPanel.add(semanticMaskCB);
        checkboxesPanel.add(instanceMaskPerClassCB);

        panel.add(checkboxesPanel, BorderLayout.CENTER);
        return panel;
    }

    // ==== live RoiManager refresh ====

    /** Frames actually processed by the current dialog state: 1 .. the "process up to frame" value. */
    private List<Integer> currentFrameRange() {
        int lastFrame = currentLastFrame();
        List<Integer> frames = new ArrayList<>(lastFrame);
        for (int f = 1; f <= lastFrame; f++) frames.add(f);
        return frames;
    }

    /** Live value of the "process up to frame" field, clamped to [1, nFrames] (falls back to nFrames while invalid/empty). */
    private int currentLastFrame() {
        if (lastFrameField == null) return nFrames;
        try {
            int value = Integer.parseInt(lastFrameField.getText().trim());
            return Math.max(1, Math.min(nFrames, value));
        } catch (NumberFormatException e) {
            return nFrames;
        }
    }

    @Override
    protected void refreshGroupOptions() {
        List<Integer> frames = currentFrameRange();
        Roi[] allRois = getRoiManager().getRoisAsArray();

        // positive: any group with a usable ROI (point or area) on at least one processed frame
        List<RoiGroupOption> positiveOptions = toOptions(roiPromptExtractor.getUsableGroupsWithCount(allRois, axis, frames, false));
        // negative: only point ROIs can be negative prompts
        List<RoiGroupOption> negativeOptions = toOptions(roiPromptExtractor.getUsableGroupsWithCount(allRois, axis, frames, true));

        positiveGroupEditor.setAvailableGroups(positiveOptions);
        negativeGroupEditor.setAvailableGroups(negativeOptions);

        Set<Integer> availablePositiveIds = new HashSet<>();
        for (RoiGroupOption option : positiveOptions) availablePositiveIds.add(option.getGroupId());
        Set<Integer> availableNegativeIds = new HashSet<>();
        for (RoiGroupOption option : negativeOptions) availableNegativeIds.add(option.getGroupId());

        if (tableModel.reconcileVisualGroups(availablePositiveIds, availableNegativeIds)) {
            helpBar.showInfo(VISUAL_PROMPT_RESET_OWNER, "A previously selected ROI group is no longer available - "
                    + "visual prompt turned off for the affected object(s).", DialogHelpBar.DEFAULT_NOTIFICATION_COLOR);
        }

        tableModel.setRoiPromptsAvailable(!positiveOptions.isEmpty());

        // print warning if no ROI on frame
        if (positiveOptions.isEmpty()) helpBar.warn(NO_USABLE_ROI_OWNER,
                "No usable ROI (point or area) in the ROI Manager for the frame(s) being processed. "
                + "\nAdd one to create object and start segmentation.");
        else helpBar.clearWarning(NO_USABLE_ROI_OWNER);
        table.repaint();
    }

    /** one combo box option per group id, labelled with its ROI count */
    private static List<RoiGroupOption> toOptions(Map<Integer, Integer> roiCountByGroup) {
        List<RoiGroupOption> options = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : roiCountByGroup.entrySet()) {
            options.add(new RoiGroupOptionWithCount(entry.getKey(), entry.getValue()));
        }
        return options;
    }

    private boolean outputWithRoiGroupSelected() {
        return (addBoxRoisCB != null && addBoxRoisCB.isSelected()) || (addShapeRoisCB != null && addShapeRoisCB.isSelected())
                || (semanticMaskCB != null && semanticMaskCB.isSelected());
    }

    private boolean outputWithNameSelected() {
        return (addBoxRoisCB != null && addBoxRoisCB.isSelected()) || (addShapeRoisCB != null && addShapeRoisCB.isSelected())
                || (instanceMaskPerClassCB != null && instanceMaskPerClassCB.isSelected());
    }

    // ==== OK / Cancel ====

    @Override
    protected void onOk() {
        if (!validateAndCollect()) return; // invalid input - keep the dialog open so the user can fix it
        approved = true;
        dispose();
    }

    private boolean validateAndCollect() {
        if (imp.getWindow() == null) {
            onImageClosed();
            return false;
        }
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return false;

        String path = ModelPathDialogs.validateAndSaveModelPath(modelPathField.getText().trim(), prefLastModelKey);
        if (path == null) return false; // does not close window - user can try again

        int parsedLastFrame = parseInt(lastFrameField.getText(), nFrames);
        if (parsedLastFrame < 1 || parsedLastFrame > nFrames) {
            IJ.log("\"Process up to frame\" must be between 1 and " + nFrames + ". Using default value: " + nFrames);
            parsedLastFrame = nFrames;
        }

        List<PvsObject> currentObjects = tableModel.getObjects();
        List<String> errors = validateObjects(currentObjects);
        if (!errors.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", errors), "Invalid object(s)", JOptionPane.ERROR_MESSAGE);
            return false;
        }

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
        objects = new ArrayList<>(currentObjects);
        chosenLastFrame = parsedLastFrame;
        finalModelParams = modelParams;

        logWarnings(currentObjects, chosenLastFrame);

        return true;
    }

    private List<String> validateObjects(List<PvsObject> currentObjects) {
        List<String> errors = new ArrayList<>();
        if (currentObjects.isEmpty()) {
            errors.add("At least one object must be defined.");
            return errors;
        }
        Set<String> seenNames = new HashSet<>();
        for (PvsObject object : currentObjects) {
            String label = object.getMode() == PvsObject.Mode.CLASS ? "Class" : "Object";
            if (!object.hasAnyPositivePrompt()) {
                errors.add(label + " " + object.getName() + ": needs a positive visual prompt.");
            }
            if (!object.isNegativeGroupValid()) {
                errors.add(label + " " + object.getName() + ": negative visual can't use the same group as the positive visual.");
            }
            if (!seenNames.add(object.getName())) {
                errors.add("Duplicate name: " + object.getName());
            }
        }
        return errors;
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

    /** Logs, per object, whether its chosen negative group contains area ROIs (ignored - negative prompts are points only). */
    private void logWarnings(List<PvsObject> currentObjects, int lastFrame) {
        Roi[] allRois = getRoiManager().getRoisAsArray();
        for (PvsObject object : currentObjects) {
            if (!object.isNegativeVisualUsed()) continue;
            boolean hasArea = false;
            for (int frame = 1; frame <= lastFrame && !hasArea; frame++) {
                for (Roi roi : roiPromptExtractor.getRoisAtFrame(allRois, axis, frame)) {
                    if (roi.getGroup() == object.getNegativeVisualGroup() && roi.isArea()) {
                        hasArea = true;
                        break;
                    }
                }
            }
            if (hasArea) {
                IJ.log("WARNING: object " + object.getName() + " - negative group " + object.getNegativeVisualGroup()
                        + " contains area ROI(s), which are ignored (only point ROIs are used as negative prompts).");
            }
        }
    }
}