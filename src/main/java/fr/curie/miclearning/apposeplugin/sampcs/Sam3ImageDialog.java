package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * Non-modal Swing dialog for {@link Sam3ImagePcs_Plugin}: lets the user define an arbitrary
 * number of concepts, plus model path, detection parameters and output options.
 */
public class Sam3ImageDialog extends Sam3ConceptDialogBase {

    private JCheckBox processStackCB; // only shown/relevant when nFrames > 1

    // populated only once the user presses OK
    private DetectionUtils.DetectionMode stackMode;

    // interface elements
    JTextField confidenceField;

    public Sam3ImageDialog(ImagePlus imp, int nFrames, int axis) {
        super(imp, nFrames, axis, "SAM3 Promptable Concept Segmentation");
    }

    public DetectionUtils.DetectionMode getStackMode() {return stackMode;}

    // ==== UI construction ====
    void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);
        helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());

        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildModelPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildConceptsPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildParametersPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildOutputPanel());
        content.add(Box.createVerticalStrut(8));
        if (nFrames > 1) content.add(buildScopePanel());
        content.add(buildButtonsPanel());

        // start with one empty concept row
        tableModel.addConcept();
    }


    @Override
    JPanel buildConceptsPanel() {
        JPanel panel = super.buildConceptsPanel();
        // hover hints per column header, defined with mouse position
        // hints are specific to this plugin
        JTableHeader header = table.getTableHeader();
        helpBar.attachPositionalHelp(header, point -> {
            int viewCol = header.columnAtPoint(point);
            if (viewCol < 0) return null;
            int modelCol = table.convertColumnIndexToModel(viewCol);
            String text;
            switch (modelCol) {
                case Sam3ConceptTableModel.COL_POSITIVE_VISUAL:
                    text = "ROI group used as a positive visual prompt. Needs one usable ROI (box or point) in the ROI Manager "
                            + "on each processed frame." +
                            "\nEach frame will be processed independently.";
                    break;
                case Sam3ConceptTableModel.COL_NEGATIVE_VISUAL:
                    text = "ROI group used as a negative visual prompt (optional). Needs one usable ROI (box or point) "
                            + "on each processed frame. \nMust differ from the Positive visual group.";
                    break;
                default:
                    return null;
            }
            return new DialogHelpBar.Hint(text, DialogHelpBar.DEFAULT_HELP_COLOR);
        });
        
        // hint specific to image...
        // clicking a disabled positive/negative visual cell explains why (no usable ROI yet)
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int viewCol = table.columnAtPoint(e.getPoint());
                int row = table.rowAtPoint(e.getPoint());
                if (viewCol < 0 || row < 0) return;
                int modelCol = table.convertColumnIndexToModel(viewCol);
                boolean isVisualColumn = modelCol == Sam3ConceptTableModel.COL_POSITIVE_VISUAL
                        || modelCol == Sam3ConceptTableModel.COL_NEGATIVE_VISUAL;
                if (isVisualColumn && !tableModel.isCellEditable(row, modelCol)) {
                    helpBar.showInfo("visualPromptDisabled",
                            "No usable ROI (box or point) in the ROI Manager for the frame(s) being processed. "
                                    + "Add one to enable visual prompts.",
                            DialogHelpBar.DEFAULT_INSTRUCTION_COLOR);
                }
            }
        });

        return panel;
    }


    @Override
    JPanel buildParametersPanel() {
        JPanel panel = super.buildParametersPanel();
        panel.setLayout(new BorderLayout());
        panel.add(createSectionTitle("Detection and segmentation settings"), BorderLayout.NORTH);

        GridBagConstraints c = new GridBagConstraints();
        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        c.insets = new Insets(2, 4, 2, 4);

        c.anchor = GridBagConstraints.WEST;
        confidenceField = new JTextField(String.valueOf(Sam3ModelParameters.DEFAULT_CONFIDENCE), 6);
        String confidenceHint = "Minimum detection probability (0-1) for an object to be added.";
        addLabeledField(fieldsPanel, c, 0, "Confidence threshold:", confidenceField, confidenceHint);

        JButton advancedButton = new JButton("Advanced parameters...");
        advancedButton.addActionListener(e -> Sam3AdvancedParametersDialog.showForImage(this, modelParams, getWidth()));
        helpBar.attachHelp(advancedButton, "Mask score threshold, segmentation mask side length, and coordinate encoding ");
        c.anchor = GridBagConstraints.EAST;
        c.gridx = 2; c.weightx = 1.0;
        fieldsPanel.add(advancedButton, c);

        panel.add(fieldsPanel, BorderLayout.CENTER);
        return panel;
    }

    @Override
    protected JPanel buildScopePanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        processStackCB = new JCheckBox("Process entire stack (" + nFrames + " frames)", true);
        helpBar.attachHelp(processStackCB, "If unchecked, only the current frame is processed." +
                "\nIf checked, each frame of the stack will be processed independently.");
        processStackCB.addItemListener(e -> refreshGroupOptions());
        panel.add(processStackCB);
        return panel;
    }

    // ==== live RoiManager refresh ====

    /** Frames actually processed by the current dialog state: the whole stack, or just the current slice. */
    @Override
    protected List<Integer> currentFrameRange() {
        if (nFrames <= 1 || processStackCB == null || !processStackCB.isSelected()) {
            return Collections.singletonList(imp.getCurrentSlice());
        }
        List<Integer> frames = new ArrayList<>(nFrames);
        for (int f = 1; f <= nFrames; f++) frames.add(f);
        return frames;
    }

    /** One option per roi group with a usable ROI on at least one of the current frames. */
    @Override
    List<Sam3GroupOption> computeGroupOptions() {
        List<Integer> frames = currentFrameRange();
        Map<Integer, Integer> coveredFramesByGroup = roiPromptExtractor.countGroupCoverage(getRoiManager().getRoisAsArray(), axis, frames);

        List<Sam3GroupOption> options = new ArrayList<>();
        int totalFrames = frames.size();
        for (Map.Entry<Integer, Integer> entry : coveredFramesByGroup.entrySet()) {
            options.add(new Sam3GroupOptionImage(entry.getKey(), entry.getValue(), totalFrames));
        }
        return options;
    }

    // ==== OK / Cancel ====

    @Override
    void onOk() {
        if (!validateAndCollect()) return; // invalid input - keep the dialog open so the user can fix it
        stackMode = (nFrames > 1 && processStackCB.isSelected())
                ? DetectionUtils.DetectionMode.MULTI_IMAGE : DetectionUtils.DetectionMode.SINGLE_IMAGE;

        approved = true;
        dispose();
    }

    @Override
    Sam3ModelParameters collectDetectionParams() {
        double confidence = parseDouble(confidenceField.getText(), modelParams.getConfidenceThreshold());
        if (confidence < 0 || confidence > 1) {
            IJ.log("Confidence threshold must be between 0 and 1. Using default value: " + modelParams.getConfidenceThreshold());
            confidence = modelParams.getConfidenceThreshold();
        }
        modelParams.setConfidenceThreshold(confidence);

        return modelParams;
    }

    /** Logs, per concept, which of the currently processed frames are missing a usable ROI for an enabled visual prompt. */
    @Override
    protected void logWarnings(List<Sam3Concept> currentConcepts) {
        Roi[] allRois = getRoiManager().getRoisAsArray();
        List<Integer> frames = currentFrameRange();

        for (Sam3Concept concept : currentConcepts) {
            if (concept.isPositiveVisualUsed()) {
                warnIfPartialCoverage(allRois, frames, concept.getPositiveVisualGroup(), concept, "positive");
            }
            if (concept.isNegativeVisualUsed()) {
                warnIfPartialCoverage(allRois, frames, concept.getNegativeVisualGroup(), concept, "negative");
            }
        }
    }

    private void warnIfPartialCoverage(Roi[] allRois, List<Integer> frames, int groupId, Sam3Concept concept, String kind) {
        List<Integer> missingFrames = roiPromptExtractor.findFramesMissingGroup(allRois, axis, groupId, frames);
        if (!missingFrames.isEmpty()) {
            IJ.log("WARNING: concept " + concept.getName() + " - " + kind + " group " + groupId
                    + " has no ROI on frame(s): " + missingFrames);
        }
    }
}