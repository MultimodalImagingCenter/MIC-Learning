package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.RoiPromptExtractor;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.Roi;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * Non-modal Swing dialog for {@link Sam3SingleImgPcs_Plugin}: lets the user define an arbitrary
 * number of concepts (each with an optional text prompt and/or optional positive/negative visual
 * prompt), plus model path, detection parameters and output options.
 * <p>
 * Uses a plain {@link JDialog} rather than {@code GenericDialog}/{@code NonBlockingGenericDialog}
 * because the concept table has a dynamic row count that {@code GenericDialog}'s fixed
 * {@code addXxxField} API can't express.
 */
public class Sam3ConceptsDialog extends JDialog {

    private static final String PREF_LAST_MODEL_KEY = "miclearning.lastmodeldir.sam3";
    private static final int ROI_POLL_INTERVAL_MS = 500;

    private final ImagePlus imp;
    private final int nFrames;
    private final int axis; // 3 = Z axis, 4 = Time axis (matches ImagePlus.getDimensions() indexing)
    private final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();

    private final Sam3ConceptTableModel tableModel = new Sam3ConceptTableModel();
    private final JTable table = new JTable(tableModel);
    private final Sam3GroupIdCellEditor positiveGroupEditor = new Sam3GroupIdCellEditor(false);
    private final Sam3GroupIdCellEditor negativeGroupEditor = new Sam3GroupIdCellEditor(true);

    private JTextField modelPathField;
    private JTextField confidenceField;
    private JTextField maskThresholdField;
    private JTextField maxSideLengthField;
    private JCheckBox addBoxRoisCB;
    private JCheckBox addShapeRoisCB;
    private JCheckBox instanceMaskCB;
    private JCheckBox semanticMaskCB;
    private JCheckBox instanceMaskPerClassCB;
    private JCheckBox processStackCB; // only shown/relevant when nFrames > 1

    private DialogHelpBar helpBar;
    private Timer roiPollTimer;
    private String lastRoiSignature = "";

    private final CountDownLatch closedLatch = new CountDownLatch(1);

    // populated only once the user presses OK
    private boolean approved = false;
    private String modelPath;
    private Sam3ModelParameters detectionParams;
    private DetectionUtils.OutputOptions outputOptions;
    private DetectionUtils.DetectionMode stackMode;
    private List<Sam3Concept> concepts;

    public Sam3ConceptsDialog(ImagePlus imp, int nFrames, int axis) {
        super(IJ.getInstance(), "SAM3 Promptable Concept Segmentation", false);
        this.imp = imp;
        this.nFrames = nFrames;
        this.axis = axis;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (roiPollTimer != null) roiPollTimer.stop();
                closedLatch.countDown();
            }
        });

        buildUi();
        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Shows the dialog and blocks the calling thread until the user closes it (OK, Cancel, or the
     * window's own close button) - safe to call from {@link ij.plugin.PlugIn#run}, which normally
     * runs off the AWT event thread. Must NOT be called from the event dispatch thread itself.
     *
     * @return {@code true} if the user pressed OK with a valid configuration, {@code false} otherwise
     */
    public boolean showDialogAndWait() {
        SwingUtilities.invokeLater(() -> {
            refreshGroupOptions();
            startRoiPolling();
            setVisible(true);
        });
        try {
            closedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            approved = false;
        }
        return approved;
    }

    public String getModelPath() {return modelPath;}
    public Sam3ModelParameters getDetectionParams() {return detectionParams;}
    public DetectionUtils.OutputOptions getOutputOptions() {return outputOptions;}
    public DetectionUtils.DetectionMode getStackMode() {return stackMode;}
    public List<Sam3Concept> getConcepts() {return concepts;}

    // ==== UI construction ====

    private void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setContentPane(content);

        helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());

        content.add(buildModelPanel());
        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildConceptsPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildParametersPanel());
        content.add(buildOutputPanel());
        if (nFrames > 1) content.add(buildStackModePanel());
        content.add(buildButtonsPanel());

        // start with one empty concept row so the table isn't blank on first open
        tableModel.addConcept(firstUnusedClassId());
    }

    private JPanel buildModelPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Model"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridx = 0; c.gridy = 0;
        panel.add(new JLabel("Model path:"), c);

        modelPathField = new JTextField(Sam3Dialogs.resolveDefaultModelPath(PREF_LAST_MODEL_KEY), 40);
        c.gridx = 1; c.weightx = 1;
        panel.add(modelPathField, c);

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
        panel.add(browseButton, c);

        JButton instructionsButton = new JButton("Instructions to download SAM3 model");
        instructionsButton.addActionListener(e -> Sam3Dialogs.addDownloadInstruction());
        c.gridx = 0; c.gridy = 1; c.gridwidth = 3;
        panel.add(instructionsButton, c);

        return panel;
    }

    private JPanel buildConceptsPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Concepts"));

        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_GROUP_ID).setCellEditor(positiveGroupEditor);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_GROUP_ID).setCellRenderer(new Sam3GroupIdCellEditor.Renderer(positiveGroupEditor));

        Sam3GroupIdCellEditor.Renderer negativeGroupRenderer = new Sam3GroupIdCellEditor.Renderer(negativeGroupEditor) {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                comp.setForeground(tableModel.getConcept(row).isNegativeVisualUsed() ? Color.BLACK : Color.GRAY);
                return comp;
            }
        };
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_GROUP_ID).setCellEditor(negativeGroupEditor);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_GROUP_ID).setCellRenderer(negativeGroupRenderer);

        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_TEXT_PROMPT).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component comp = super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                comp.setForeground(tableModel.getConcept(row).isTextPromptUsed() ? Color.BLACK : Color.GRAY);
                return comp;
            }
        });

        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_TEXT_PROMPT).setPreferredWidth(150);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_GROUP_ID).setPreferredWidth(160);
        table.getColumnModel().getColumn(Sam3ConceptTableModel.COL_NEGATIVE_GROUP_ID).setPreferredWidth(160);
        table.setRowHeight(22);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(700, 160));
        panel.add(scrollPane, BorderLayout.CENTER);

        JButton addButton = new JButton("Add concept");
        addButton.addActionListener(e -> tableModel.addConcept(firstUnusedClassId()));
        helpBar.attachHelp(addButton, "Adds a new concept row, defaulting to the first unused RoiManager group id.");

        JButton removeButton = new JButton("Remove selected");
        removeButton.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().cancelCellEditing();
            int row = table.getSelectedRow();
            if (row >= 0) tableModel.removeConcept(row);
        });
        helpBar.attachHelp(removeButton, "Removes the currently selected concept row.");

        JPanel buttonsRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonsRow.add(addButton);
        buttonsRow.add(removeButton);
        panel.add(buttonsRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildParametersPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Detection and segmentation settings"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.anchor = GridBagConstraints.WEST;

        confidenceField = new JTextField(String.valueOf(Sam3ModelParameters.DEFAULT_CONFIDENCE), 6);
        maskThresholdField = new JTextField(String.valueOf(Sam3ModelParameters.DEFAULT_MASK_THRESHOLD), 6);
        maxSideLengthField = new JTextField(String.valueOf(Sam3ModelParameters.DEFAULT_MAX_SIDE_LENGTH_DETECT), 6);

        addLabeledField(panel, c, 0, "Confidence threshold:", confidenceField,
                "Minimum detection probability (0-1) for an object to be added.");
        addLabeledField(panel, c, 1, "Mask score threshold:", maskThresholdField,
                "Minimum per-pixel mask score (centered on 0) for a pixel to be included in the mask.");
        addLabeledField(panel, c, 2, "Segmentation mask side length:", maxSideLengthField,
                "Masks are downsized to at most this side length (pixels) during detection.");

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
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder("Outputs"));

        addBoxRoisCB = new JCheckBox("Add bounding boxes to ROI Manager", false);
        addShapeRoisCB = new JCheckBox("Add shape ROIs to ROI Manager", true);
        instanceMaskCB = new JCheckBox("Create instance masks (unique value per instance)", false);
        semanticMaskCB = new JCheckBox("Create semantic masks (unique value per class)", false);
        instanceMaskPerClassCB = new JCheckBox("Create instance mask per class (one slice per class)", false);

        panel.add(addBoxRoisCB);
        panel.add(addShapeRoisCB);
        panel.add(instanceMaskCB);
        panel.add(semanticMaskCB);
        panel.add(instanceMaskPerClassCB);

        return panel;
    }

    private JPanel buildStackModePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        processStackCB = new JCheckBox("Process entire stack (" + nFrames + " frames)", true);
        helpBar.attachHelp(processStackCB, "If unchecked, only the current slice is processed.");
        processStackCB.addItemListener(e -> refreshGroupOptions());
        panel.add(processStackCB);
        return panel;
    }

    private JPanel buildButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton settingsButton = new JButton("Help bar settings...");
        settingsButton.addActionListener(e -> helpBar.openSettingsDialog());

        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> onOk());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> onCancel());

        panel.add(settingsButton);
        panel.add(okButton);
        panel.add(cancelButton);
        return panel;
    }

    // ==== live RoiManager refresh ====

    private void startRoiPolling() {
        lastRoiSignature = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis);
        roiPollTimer = new Timer(ROI_POLL_INTERVAL_MS, e -> {
            String signature = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis);
            if (!signature.equals(lastRoiSignature)) {
                lastRoiSignature = signature;
                refreshGroupOptions();
            }
        });
        roiPollTimer.start();
    }

    private void refreshGroupOptions() {
        List<Sam3GroupOption> options = computeGroupOptions();
        positiveGroupEditor.setAvailableGroups(options);
        negativeGroupEditor.setAvailableGroups(options);
        table.repaint();
    }

    /** Frames actually processed by the current dialog state: the whole stack, or just the current slice. */
    private List<Integer> currentFrameRange() {
        if (nFrames <= 1 || processStackCB == null || !processStackCB.isSelected()) {
            return Collections.singletonList(imp.getCurrentSlice());
        }
        List<Integer> frames = new ArrayList<>(nFrames);
        for (int f = 1; f <= nFrames; f++) frames.add(f);
        return frames;
    }

    /** One option per RoiManager group with a usable ROI on at least one of the current frames, with coverage. */
    private List<Sam3GroupOption> computeGroupOptions() {
        List<Integer> frames = currentFrameRange();
        Map<Integer, Integer> coveredFramesByGroup = roiPromptExtractor.countGroupCoverage(getRoiManager().getRoisAsArray(), axis, frames);

        List<Sam3GroupOption> options = new ArrayList<>();
        int totalFrames = frames.size();
        for (Map.Entry<Integer, Integer> entry : coveredFramesByGroup.entrySet()) {
            options.add(new Sam3GroupOption(entry.getKey(), entry.getValue(), totalFrames));
        }
        return options;
    }

    /** First group id (1-255) not already claimed by a RoiManager group or by an existing concept row. */
    private int firstUnusedClassId() {
        Set<Integer> used = new TreeSet<>();
        for (Roi roi : getRoiManager().getRoisAsArray()) {
            if (RoiPromptExtractor.isUsableRoiType(roi)) used.add(roi.getGroup());
        }
        for (Sam3Concept concept : tableModel.getConcepts()) used.add(concept.getClassId());
        for (int candidate = 1; candidate <= 255; candidate++) {
            if (!used.contains(candidate)) return candidate;
        }
        return 1;
    }

    // ==== OK / Cancel ====

    private void onCancel() {
        approved = false;
        dispose();
    }

    private void onOk() {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) return;

        String path = resolveAndValidateModelPath();
        if (path == null) return;

        List<Sam3Concept> currentConcepts = tableModel.getConcepts();
        List<String> errors = validateConcepts(currentConcepts);
        if (!errors.isEmpty()) {
            IJ.error("Invalid concept(s)", String.join("\n", errors));
            return;
        }

        modelPath = path;
        concepts = new ArrayList<>(currentConcepts);
        detectionParams = collectDetectionParams();
        outputOptions = collectOutputOptions();
        stackMode = (nFrames > 1 && processStackCB.isSelected())
                ? DetectionUtils.DetectionMode.MULTI_IMAGE : DetectionUtils.DetectionMode.SINGLE_IMAGE;

        logCoverageWarnings(currentConcepts);

        approved = true;
        dispose();
    }

    private List<String> validateConcepts(List<Sam3Concept> currentConcepts) {
        List<String> errors = new ArrayList<>();
        if (currentConcepts.isEmpty()) {
            errors.add("At least one concept must be defined.");
            return errors;
        }
        for (Sam3Concept concept : currentConcepts) {
            if (!concept.hasAnyPositivePrompt()) {
                errors.add("Concept (group " + concept.getClassId() + "): needs a text prompt and/or a positive visual prompt.");
            }
            if (!concept.isNegativeGroupValid()) {
                errors.add("Concept (group " + concept.getClassId() + "): negative group can't be its own group.");
            }
        }
        return errors;
    }

    private String resolveAndValidateModelPath() {
        String path = modelPathField.getText().trim();
        if (Files.exists(Paths.get(path))) {
            Prefs.set(PREF_LAST_MODEL_KEY, path);
            Prefs.savePreferences();
            return path;
        }
        IJ.error("Selection Error", "The selected path is not valid:\n" + path);
        return null;
    }

    private Sam3ModelParameters collectDetectionParams() {
        Sam3ModelParameters params = new Sam3ModelParameters();

        double confidence = parseDouble(confidenceField.getText(), params.getConfidenceThreshold());
        if (confidence < 0 || confidence > 1) {
            IJ.log("Confidence threshold must be between 0 and 1. Using default value: " + params.getConfidenceThreshold());
            confidence = params.getConfidenceThreshold();
        }
        params.setConfidenceThreshold(confidence);

        params.setMaskScoreThreshold(parseDouble(maskThresholdField.getText(), params.getMaskScoreThreshold()));

        int maxSideLength = (int) parseDouble(maxSideLengthField.getText(), params.getMaxSideLengthDetect());
        if (maxSideLength <= 0) {
            IJ.log("Mask side length must be > 0. Using default value: " + params.getMaxSideLengthDetect());
            maxSideLength = params.getMaxSideLengthDetect();
        }
        params.setMaxSideLengthDetect(maxSideLength);

        return params;
    }

    private double parseDouble(String text, double fallback) {
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
        options.createInstanceMaskPerClass = instanceMaskPerClassCB.isSelected() && stackModeIsSingleImage();
        return options;
    }

    private boolean stackModeIsSingleImage() {
        return nFrames <= 1 || !processStackCB.isSelected();
    }

    /** Logs, per concept, which of the currently processed frames are missing a usable ROI for an enabled visual prompt. */
    private void logCoverageWarnings(List<Sam3Concept> currentConcepts) {
        Roi[] allRois = getRoiManager().getRoisAsArray();
        List<Integer> frames = currentFrameRange();

        for (Sam3Concept concept : currentConcepts) {
            if (concept.isPositiveVisualUsed()) {
                warnIfPartialCoverage(allRois, frames, concept.getClassId(), concept, "positive");
            }
            if (concept.isNegativeVisualUsed() && concept.getNegativeGroupId() != null) {
                warnIfPartialCoverage(allRois, frames, concept.getNegativeGroupId(), concept, "negative");
            }
        }
    }

    private void warnIfPartialCoverage(Roi[] allRois, List<Integer> frames, int groupId, Sam3Concept concept, String kind) {
        List<Integer> missingFrames = roiPromptExtractor.findFramesMissingGroup(allRois, axis, groupId, frames);
        if (!missingFrames.isEmpty()) {
            IJ.log("WARNING: concept (group " + concept.getClassId() + ") - " + kind + " group " + groupId
                    + " has no ROI on frame(s): " + missingFrames);
        }
    }
}