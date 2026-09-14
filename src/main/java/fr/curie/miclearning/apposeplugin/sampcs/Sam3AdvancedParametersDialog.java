package fr.curie.miclearning.apposeplugin.sampcs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import ij.IJ;
import ij.Prefs;

import javax.swing.*;
import java.awt.*;
import java.io.File;

/**
 * Modal dialog for the rarely-tuned SAM3 detection parameters, and (for video) the tracking
 * settings and optional tracking model path.
 */
class Sam3AdvancedParametersDialog extends JDialog {

    private static final String PREF_LAST_TRACK_MODEL_KEY = "miclearning.lastmodeldir.videopcs.trackingmodel";

    private final Sam3ModelParameters params;
    private final boolean includeTracking;
    private String trackingModelPath;

    private JTextField maskThresholdField;
    private JTextField maskSideLengthField;
    private JCheckBox coordinateEncodingCB;

    private JTextField trackingModelPathField;
    private JTextField trackThresholdField;
    private JTextField removeAfterField;
    private JTextField trackSideLengthField;
    private JTextField boxIouField;

    /** Shows the dialog with detection settings only ; blocks until the dialog is closed. */
    static void showForImage(Dialog owner, Sam3ModelParameters params, int minWidth) {
        new Sam3AdvancedParametersDialog(owner, params, false, null, minWidth).setVisible(true);
    }

    /**
     * Shows the dialog with detection + tracking settings ; blocks until the dialog is closed.
     *
     * @return the tracking model path chosen, or {@code currentTrackingModelPath} if left
     * unchanged, invalid, or the dialog was cancelled
     */
    static String showForVideo(Dialog owner, Sam3ModelParameters params, String currentTrackingModelPath, int minWidth) {
        Sam3AdvancedParametersDialog dialog = new Sam3AdvancedParametersDialog(owner, params, true, currentTrackingModelPath, minWidth);
        dialog.setVisible(true);
        return dialog.trackingModelPath;
    }

    private Sam3AdvancedParametersDialog(Dialog owner, Sam3ModelParameters params, boolean includeTracking,
                                          String currentTrackingModelPath, int minWidth) {
        super(owner, "Advanced parameters", true);
        this.params = params;
        this.includeTracking = includeTracking;
        this.trackingModelPath = currentTrackingModelPath;

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        DialogHelpBar helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());
        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));

        if (includeTracking) {
            content.add(buildTrackingModelPanel(helpBar));
            content.add(Box.createVerticalStrut(8));
        }
        content.add(buildDetectionSettingsPanel(helpBar));
        if (includeTracking) {
            content.add(Box.createVerticalStrut(8));
            content.add(buildTrackingSettingsPanel(helpBar));
        }
        content.add(Box.createVerticalStrut(8));
        content.add(buildButtonsPanel());

        // honor the same interface-scale preference as Sam3ConceptDialogBase's dialogs
        double uiScale = Prefs.get(Sam3ConceptDialogBase.PREF_UI_SCALE_KEY, Sam3ConceptDialogBase.DEFAULT_UI_SCALE);
        if (uiScale != 1.0) {
            Sam3ConceptDialogBase.rescaleFonts(getContentPane(), uiScale);
            helpBar.rescaleFont(uiScale);
        }

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        helpBar.lockWidth(Math.max(getPreferredSize().width, minWidth));
        pack();
        setLocationRelativeTo(owner);
    }

    /** Section-title style matching {@link Sam3ConceptDialogBase#createSectionTitle} */
    private JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        Font base = label.getFont();
        label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1f));
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return label;
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row, DialogHelpBar helpBar,
                         String label, JComponent field, String hint) {
        c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
        JLabel jLabel = new JLabel(label);
        panel.add(jLabel, c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        helpBar.attachHelp(jLabel, hint);
        helpBar.attachHelp(field, hint);
    }

    private JPanel buildTrackingModelPanel(DialogHelpBar helpBar) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(sectionTitle("Tracking model"), BorderLayout.NORTH);

        JPanel inner = new JPanel(new BorderLayout());
        inner.add(new JLabel("Optional: use a different model for tracking (SAM2 or SAM3)."), BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        String defaultPath = trackingModelPath != null ? trackingModelPath
                : Sam3Dialogs.resolveDefaultModelPath(PREF_LAST_TRACK_MODEL_KEY);
        trackingModelPathField = new JTextField(defaultPath, 40);
        String trackingModelHint = "Path to a SAM2 or SAM3 model checkpoint used for tracking instead of the detection model.\n"
                + "Leave empty to use the detection model for both.";

        c.gridx = 0; c.gridy = 0; c.weightx = 0;
        JLabel modelLabel = new JLabel("Model path:");
        fields.add(modelLabel, c);
        c.gridx = 1; c.weightx = 1;
        fields.add(trackingModelPathField, c);
        helpBar.attachHelp(modelLabel, trackingModelHint);
        helpBar.attachHelp(trackingModelPathField, trackingModelHint);

        JButton browseButton = new JButton("Browse...");
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            File current = new File(trackingModelPathField.getText());
            if (current.exists()) chooser.setSelectedFile(current);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                trackingModelPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        c.gridx = 2; c.weightx = 0;
        fields.add(browseButton, c);

        inner.add(fields, BorderLayout.CENTER);
        panel.add(inner, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildDetectionSettingsPanel(DialogHelpBar helpBar) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(sectionTitle("Detection settings"), BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        maskThresholdField = new JTextField(String.valueOf(params.getMaskScoreThreshold()), 6);
        addRow(fields, c, 0, helpBar, "Mask score threshold:", maskThresholdField,
                "Minimum per-pixel mask score (centered on 0) for a pixel to be included in the mask.");

        maskSideLengthField = new JTextField(String.valueOf(params.getMaxSideLengthDetect()), 6);
        addRow(fields, c, 1, helpBar, "Segmentation masks side length:", maskSideLengthField,
                "Masks are downsized to at most this side length (pixels) during detection. \n"
                        + "Lower this value for faster results but coarser segmentation.");

        coordinateEncodingCB = new JCheckBox("Include coordinate encoding", params.isIncludeCoordinateEncoding());
        c.gridx = 0; c.gridy = 2; c.gridwidth = 2; c.weightx = 0;
        fields.add(coordinateEncodingCB, c);

        panel.add(fields, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildTrackingSettingsPanel(DialogHelpBar helpBar) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(sectionTitle("Tracking settings"), BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        trackThresholdField = new JTextField(String.valueOf(params.getTrackingScoreThreshold()), 6);
        addRow(fields, c, 0, helpBar, "Tracking score threshold:", trackThresholdField,
                "Minimum presence score for an already-tracked object to be kept.");

        removeAfterField = new JTextField(String.valueOf(params.getRemoveAfterNMissed()), 6);
        addRow(fields, c, 1, helpBar, "Remove after N missed frames:", removeAfterField,
                "Remove a tracked object from memory after this many consecutive frames without detecting it. \n"
                        + "0 or less: never remove.");

        trackSideLengthField = new JTextField(String.valueOf(params.getMaxSideLengthTrack()), 6);
        addRow(fields, c, 2, helpBar, "Segmentation masks side length for tracking:", trackSideLengthField,
                "Masks are downsized to at most this side length (pixels) during tracking. \n"
                        + "Lower this value for faster results but coarser segmentation.");

        boxIouField = new JTextField(String.valueOf(params.getTrackingBoxIouThreshold()), 6);
        addRow(fields, c, 3, helpBar, "Box IoU threshold:", boxIouField,
                "Minimum IoU between two bounding boxes on two frames to consider them the same object. \n"
                        + "Lower this value if objects move a lot between frames.");

        panel.add(fields, BorderLayout.CENTER);
        return panel;
    }

    private JPanel buildButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("OK");
        okButton.addActionListener(e -> onOk());
        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> dispose());
        panel.add(okButton);
        panel.add(cancelButton);
        return panel;
    }

    private void onOk() {
        if (includeTracking) {
            String validated = Sam3Dialogs.validateAndSaveModelPath(trackingModelPathField.getText().trim(), PREF_LAST_TRACK_MODEL_KEY);
            if (validated != null) trackingModelPath = validated;
        }

        params.setMaskScoreThreshold(parseDouble(maskThresholdField.getText(), params.getMaskScoreThreshold()));

        int maxSideLengthDetect = parseInt(maskSideLengthField.getText(), params.getMaxSideLengthDetect());
        if (maxSideLengthDetect <= 0) {
            IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthDetect());
            maxSideLengthDetect = params.getMaxSideLengthDetect();
        }
        params.setMaxSideLengthDetect(maxSideLengthDetect);

        params.setIncludeCoordinateEncoding(coordinateEncodingCB.isSelected());

        if (includeTracking) {
            double trackThreshold = parseDouble(trackThresholdField.getText(), params.getTrackingScoreThreshold());
            if (trackThreshold < 0) {
                IJ.log("Tracking score threshold must be >0. using default value: " + params.getTrackingScoreThreshold());
                trackThreshold = params.getTrackingScoreThreshold();
            }
            params.setTrackingScoreThreshold(trackThreshold);

            int removeAfterNMissed = parseInt(removeAfterField.getText(), params.getRemoveAfterNMissed());
            if (removeAfterNMissed <= 0) {
                IJ.log("Detected objects will never be removed from memory.");
            }
            params.setRemoveAfterNMissed(removeAfterNMissed);

            int maxSideLengthTrack = parseInt(trackSideLengthField.getText(), params.getMaxSideLengthTrack());
            if (maxSideLengthTrack <= 0) {
                IJ.log("Mask side length of masks must be >0. using default value: " + params.getMaxSideLengthTrack());
                maxSideLengthTrack = params.getMaxSideLengthTrack();
            }
            params.setMaxSideLengthTrack(maxSideLengthTrack);

            double trackingBoxIouThreshold = parseDouble(boxIouField.getText(), params.getTrackingBoxIouThreshold());
            if (trackingBoxIouThreshold < 0) {
                IJ.log("tracking box IoU threshold must be >0. using default value: " + params.getTrackingBoxIouThreshold());
                trackingBoxIouThreshold = params.getTrackingBoxIouThreshold();
            }
            params.setTrackingBoxIouThreshold(trackingBoxIouThreshold);
        }

        dispose();
    }

    private double parseDouble(String text, double fallback) {
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}