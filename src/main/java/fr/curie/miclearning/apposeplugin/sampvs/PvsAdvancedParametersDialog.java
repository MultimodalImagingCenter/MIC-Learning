package fr.curie.miclearning.apposeplugin.sampvs;

import fr.curie.miclearning.apposeplugin.DialogHelpBar;
import fr.curie.miclearning.apposeplugin.DialogUiSupport;
import ij.IJ;
import ij.Prefs;

import javax.swing.*;
import java.awt.*;

/**
 * Modal dialog for the rarely-tuned PVS tracking parameters (in this case, all parameters are "advanced")
 */
class PvsAdvancedParametersDialog extends JDialog {

    private final PvsModelParameters params;

    private JTextField maskThresholdField;
    private JTextField maxSideLengthField;
    private JTextField nFramesInMemoryField;
    private JTextField trackingThresholdField;

    /** Shows the dialog; blocks until the dialog is closed. */
    static void show(Dialog owner, PvsModelParameters params, int minWidth) {
        new PvsAdvancedParametersDialog(owner, params, minWidth).setVisible(true);
    }

    private PvsAdvancedParametersDialog(Dialog owner, PvsModelParameters params, int minWidth) {
        super(owner, "Advanced parameters", true);
        this.params = params;

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        DialogHelpBar helpBar = new DialogHelpBar(this, DialogHelpBar.loadSavedMode());
        content.add(helpBar.getPanel());
        content.add(Box.createVerticalStrut(8));

        content.add(buildTrackingSettingsPanel(helpBar));
        content.add(Box.createVerticalStrut(8));
        content.add(buildButtonsPanel());

        // same interface-scale preference as the other plugin dialogs
        double uiScale = Prefs.get(DialogUiSupport.PREF_UI_SCALE_KEY, DialogUiSupport.DEFAULT_UI_SCALE);
        if (uiScale != 1.0) {
            DialogUiSupport.rescaleFonts(getContentPane(), uiScale);
            helpBar.rescaleFont(uiScale);
        }

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        pack();
        helpBar.lockWidth(Math.max(getPreferredSize().width, minWidth));
        pack();
        setLocationRelativeTo(owner);
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

    private JPanel buildTrackingSettingsPanel(DialogHelpBar helpBar) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEtchedBorder());
        panel.add(DialogUiSupport.createSectionTitle("Tracking settings"), BorderLayout.NORTH);

        JPanel fields = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        maskThresholdField = new JTextField(String.valueOf(params.getMaskScoreThreshold()), 6);
        addRow(fields, c, 0, helpBar, "Mask score threshold:", maskThresholdField,
                "Minimum per-pixel mask score (centered on 0) for a pixel to be included in the mask.");

        maxSideLengthField = new JTextField(params.getMaxSideLength() > 0 ? String.valueOf(params.getMaxSideLength()) : "", 6);
        addRow(fields, c, 1, helpBar, "Max side length:", maxSideLengthField,
                "Frames are downsized to at most this side length (pixels) before encoding. \n"
                        + "Lower this value for faster results but coarser segmentation. Leave empty for default value (1024 for sam2, 1008 far sam3)");

        nFramesInMemoryField = new JTextField(String.valueOf(params.getNFramesInMemory()), 6);
        addRow(fields, c, 2, helpBar, "Frames in memory:", nFramesInMemoryField,
                "Number of recent frame encodings kept in memory per tracked object.");

        trackingThresholdField = new JTextField(String.valueOf(params.getTrackingScoreThreshold()), 6);
        addRow(fields, c, 3, helpBar, "Tracking score threshold:", trackingThresholdField,
                "Minimum presence score for an already-tracked object to be kept on a given frame.");

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
        params.setMaskScoreThreshold(parseDouble(maskThresholdField.getText(), params.getMaskScoreThreshold()));

        int maxSideLength = parseInt(maxSideLengthField.getText(), params.getMaxSideLength());
        params.setMaxSideLength(Math.max(maxSideLength, 0));

        int nFramesInMemory = parseInt(nFramesInMemoryField.getText(), params.getNFramesInMemory());
        if (nFramesInMemory <= 0) {
            IJ.log("Frames in memory must be >0. Using default value: " + params.getNFramesInMemory());
            nFramesInMemory = params.getNFramesInMemory();
        }
        params.setNFramesInMemory(nFramesInMemory);

        double trackingThreshold = parseDouble(trackingThresholdField.getText(), params.getTrackingScoreThreshold());
        params.setTrackingScoreThreshold(trackingThreshold);

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
            String trimmed = text.trim();
            if (trimmed.isEmpty()) return 0; // "empty" == no limit for the max side length field
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}