package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.FrameRangeSelector;
import ij.IJ;
import ij.ImagePlus;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;

import static ij.plugin.frame.RoiManager.getRoiManager;

public class Sam3VideoDialog extends Sam3ConceptDialogBase {

    private FrameRangeSelector frameSelector;
    private String trackingModelPath;

    // interface elements
    JTextField confidenceField;
    JTextField frameBtwField;

    public Sam3VideoDialog(ImagePlus imp, int nFrames, int axis) {
        super(imp, nFrames, axis, "SAM3 Promptable Concept Segmentation on Video");
    }

    // ==== UI construction ====

    @Override
    void buildUi() {
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);
        helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());
        modelParams.setNFrameToProcess(nFrames);
        modelParams.setIncludeCoordinateEncoding(false);
        frameSelector = new FrameRangeSelector(imp, nFrames, helpBar);
        frameSelector.setPromptFrame(imp.getCurrentSlice());
        frameSelector.addPromptFrameListener(this::onScopeChanged);

        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildModelPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildConceptsPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildScopePanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildParametersPanel());
        content.add(Box.createVerticalStrut(8));
        content.add(buildOutputPanel());

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
                    text = "ROI group used as a positive visual prompt. Needs at least one usable ROI (box or point) in " +
                            "\nthe ROI Manager on the prompt frame only. " +
                            "Rois on other frames won't be processed.";
                    break;
                case Sam3ConceptTableModel.COL_NEGATIVE_VISUAL:
                    text = "ROI group used as a negative visual prompt (optional). Needs one usable ROI (box or point) "
                            + "\nthe ROI Manager on the prompt frame only. Must differ from the Positive visual group.";
                    break;
                default:
                    return null;
            }
            return new DialogHelpBar.Hint(text, DialogHelpBar.DEFAULT_HELP_COLOR);
        });

        // hint texts specific to video
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
                            "No usable ROI (box or point) in the ROI Manager on the current prompt frame."
                                    + "\nTo enable visual prompts, add point/box ROI(s) to the ROI manager "
                                    + "or pick a different prompt frame.",
                            DialogHelpBar.DEFAULT_INSTRUCTION_COLOR);
                }
            }
        });

        return panel;
    }

    @Override
    protected JPanel buildScopePanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(createSectionTitle("Frame range"), BorderLayout.NORTH);

        panel.add(frameSelector.getPanel(), BorderLayout.CENTER);

        return panel;
    }

    @Override
    JPanel buildParametersPanel() {
        JPanel outer = super.buildParametersPanel();
        outer.setLayout(new BoxLayout(outer, BoxLayout.Y_AXIS));

        // detection settings
        JPanel detectionSection = new JPanel(new BorderLayout());
        JLabel detectionTitleLabel = createSectionTitle("Detection settings");
        helpBar.attachHelp(detectionTitleLabel, "Settings for the detection of new objects.");
        detectionSection.add(detectionTitleLabel, BorderLayout.NORTH);

        JPanel detectionFieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c1 = new GridBagConstraints();
        c1.insets = new Insets(2, 4, 2, 4);
        c1.anchor = GridBagConstraints.WEST;

        confidenceField = new JTextField(String.valueOf(Sam3ModelParameters.DEFAULT_CONFIDENCE), 6);
        String confidenceHint = "Minimum detection probability (0-1) for an object to be added.";
        addLabeledField(detectionFieldsPanel, c1, 0, "Confidence threshold:", confidenceField, confidenceHint);

        detectionSection.add(detectionFieldsPanel, BorderLayout.WEST);
        outer.add(detectionSection);

        // tracking settings
        JPanel trackingSection = new JPanel(new BorderLayout());
        JLabel trackingTitleLabel = createSectionTitle("Tracking settings");
        helpBar.attachHelp(trackingTitleLabel, "Settings for the tracking of already detected objects.");
        trackingSection.add(trackingTitleLabel, BorderLayout.NORTH);

        JPanel trackingFieldsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c2 = new GridBagConstraints();
        c2.insets = new Insets(2, 4, 2, 4);
        c2.anchor = GridBagConstraints.WEST;

        frameBtwField = new JTextField(String.valueOf(modelParams.getNFrameBtwDetections()), 6);
        String frameBtwHint = "A full detection in ran every N frames; tracking carries objects through the frames in between.";
        addLabeledField(trackingFieldsPanel, c2, 0, "Frames between detections:", frameBtwField, frameBtwHint);

        JButton advancedButton = new JButton("Advanced parameters...");
        advancedButton.addActionListener(e -> trackingModelPath = Sam3AdvancedParametersDialog.showForVideo(this, modelParams, trackingModelPath, getWidth()));
        c2.anchor = GridBagConstraints.EAST;
        c2.gridx = 2; c2.weightx = 1.0;
        trackingFieldsPanel.add(advancedButton, c2);

        trackingSection.add(trackingFieldsPanel, BorderLayout.CENTER);
        outer.add(trackingSection);

        return outer;
    }

    // ==== live RoiManager refresh ====

    @Override
    protected List<Integer> currentFrameRange() {
        return Collections.singletonList(frameSelector.getPromptFrame());
    }

    @Override
    List<Sam3GroupOption> computeGroupOptions() {
        TreeSet<Integer> currentGroupsAtFrame = roiPromptExtractor.getRoiGroupAtFrame(getRoiManager().getRoisAsArray(), axis, currentFrameRange().get(0));

        List<Sam3GroupOption> options = new ArrayList<>();
        for (int groupId : currentGroupsAtFrame) {
            options.add(new Sam3GroupOption(groupId));
        }
        return options;
    }

    // ==== OK / Cancel ====

    @Override
    void onOk() {
        if (!validateAndCollect()) return; // invalid input - keep the dialog open so the user can fix it

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

        int nFrameBtwDetec = parseInt(frameBtwField.getText(), modelParams.getNFrameBtwDetections());
        if (nFrameBtwDetec <= 0) {nFrameBtwDetec = modelParams.getNFrameToProcess()+1;}
        modelParams.setNFrameBtwDetections(nFrameBtwDetec);

        return modelParams;
    }

    @Override
    protected void logWarnings(List<Sam3Concept> currentConcepts) {

    }

    public String getTrackingModelPath() {return trackingModelPath;}
    public int getChosenFirstFrame() {return frameSelector.getFirstFrame();}
    public int getChosenLastFrame() {return frameSelector.getLastFrame();}
    public int getChosenPromptFrame() {return frameSelector.getPromptFrame();}
    public boolean isBidirectional() {return frameSelector.isBidirectional();}
}
