package fr.curie.miclearning.apposeplugin;

import fr.curie.miclearning.tools.detection.DetectionUtils;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.GenericDialog;

import javax.swing.*;
import javax.swing.table.TableCellEditor;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;

import static ij.plugin.frame.RoiManager.getRoiManager;

/**
 * Shared Swing base for the SAM prompt-definition dialogs ({@code PvsVideoDialog},
 * {@code Sam3ConceptDialogBase}): window lifecycle, ROI-poll timer, click-away cell-edit commit,
 * UI scaling, the shared cell editors, and the buttons/settings panel.
 */
public abstract class AbstractPromptDialog extends JDialog {

    protected static final int ROI_POLL_INTERVAL_MS = 500;

    protected final String prefLastModelKey;
    protected final double uiScale = Prefs.get(DialogUiSupport.PREF_UI_SCALE_KEY, DialogUiSupport.DEFAULT_UI_SCALE);

    protected final ImagePlus imp;
    protected final int nFrames;
    protected final int axis; // 3 = Z axis, 4 = Time axis (matches ImagePlus.getDimensions() indexing)

    protected final RoiPromptExtractor roiPromptExtractor = new RoiPromptExtractor();
    protected Timer roiPollTimer;
    protected String lastRoiSignature = "";

    protected DialogHelpBar helpBar;
    protected JTextField modelPathField;
    protected JCheckBox addBoxRoisCB;
    protected JCheckBox addShapeRoisCB;
    protected JCheckBox instanceMaskCB;
    protected JButton okButton;

    protected final CountDownLatch closedLatch = new CountDownLatch(1); // blocks the calling thread until the user closes it
    protected boolean approved = false; // true = the user pressed OK with a valid configuration

    protected String modelPath;
    protected DetectionUtils.OutputOptions outputOptions;

    protected AbstractPromptDialog(ImagePlus imp, int nFrames, int axis, String dialogTitle, String prefLastModelKey) {
        super(IJ.getInstance(), dialogTitle, false);
        this.imp = imp;
        this.nFrames = nFrames;
        this.axis = axis;
        this.prefLastModelKey = prefLastModelKey;

        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                if (roiPollTimer != null) roiPollTimer.stop();
                closedLatch.countDown();
            }
        });
    }

    /**
     * Finishes construction: builds the UI, wires click-away cell-edit commit, applies the UI scale,
     * and packs/positions the window.
     */
    protected final void finishInit() {
        buildUi();
        installClickAwayCommit();
        if (uiScale != 1.0) {
            DialogUiSupport.rescaleFonts(getContentPane(), uiScale);
            DialogUiSupport.rescaleFonts(getPromptTable().getTableHeader(), uiScale);
            helpBar.rescaleFont(uiScale);
        }
        pack();
        setLocationRelativeTo(null);
    }

    /** Any click (even on non focusable area) commits changes made on cells. */
    private void installClickAwayCommit() {
        JTable table = getPromptTable();
        AWTEventListener listener = event -> {
            if (!(event instanceof MouseEvent) || event.getID() != MouseEvent.MOUSE_PRESSED) return;
            if (!table.isEditing()) return;
            if (MenuSelectionManager.defaultManager().getSelectedPath().length > 0) return; // a popup (e.g. combo box dropdown) is open

            Component clicked = ((MouseEvent) event).getComponent();
            Component editorComp = table.getEditorComponent();
            if (clicked == null || editorComp == null) return;
            if (clicked == editorComp || SwingUtilities.isDescendingFrom(clicked, editorComp)) return;

            TableCellEditor editor = table.getCellEditor();
            if (editor != null) editor.stopCellEditing();
        };
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
            }
        });
    }

    protected int scaled(int pixels) {
        return DialogUiSupport.scaled(pixels, uiScale);
    }

    protected JLabel createSectionTitle(String text) {
        return DialogUiSupport.createSectionTitle(text);
    }

    /**
     * Shows the dialog and blocks the calling thread until the user closes it (OK, Cancel, or the
     * window's own close button).
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

    public String getModelPath() { return modelPath; }
    public DetectionUtils.OutputOptions getOutputOptions() { return outputOptions; }

    protected abstract void buildUi();

    /** The dialog's prompt table (object table for PVS, concept table for PCS). */
    protected abstract JTable getPromptTable();

    /** Text-column editor that selects all existing text as soon as editing starts. */
    protected DefaultCellEditor createSelectAllTextEditor() {
        JTextField textField = new JTextField();
        return new DefaultCellEditor(textField) {
            @Override
            public Component getTableCellEditorComponent(JTable t, Object value, boolean isSelected, int row, int column) {
                Component c = super.getTableCellEditorComponent(t, value, isSelected, row, column);
                textField.selectAll();
                return c;
            }
        };
    }

    /**
     * Like {@link #createSelectAllTextEditor()}, but for Integer columns (the "Output group" column):
     * parses the text back to an Integer, returning {@code null} on invalid input.
     */
    protected DefaultCellEditor createSelectAllIntegerEditor() {
        JTextField textField = new JTextField();
        textField.setHorizontalAlignment(JTextField.RIGHT);
        return new DefaultCellEditor(textField) {
            @Override
            public Component getTableCellEditorComponent(JTable t, Object value, boolean isSelected, int row, int column) {
                Component c = super.getTableCellEditorComponent(t, value, isSelected, row, column);
                textField.selectAll();
                return c;
            }

            @Override
            public Object getCellEditorValue() {
                try {
                    return Integer.valueOf(textField.getText().trim());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        };
    }

    protected JPanel buildButtonsPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));

        JButton settingsButton = new JButton("Settings...");
        settingsButton.addActionListener(e -> openSettingsDialog());

        okButton = new JButton("OK");
        okButton.addActionListener(e -> onOk());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.addActionListener(e -> onCancel());

        panel.add(settingsButton);
        panel.add(okButton);
        panel.add(cancelButton);
        return panel;
    }

    /** Combined settings dialog: help-bar level and interface scale. */
    private void openSettingsDialog() {
        String[] helpLabels = DialogHelpBar.helpLevelLabels();
        int currentHelpIndex = DialogHelpBar.helpLevelIndex(helpBar.getHelpBarMode());

        GenericDialog gd = new GenericDialog("Settings");
        gd.addMessage("Choose how much guidance the dialog's help bar should show:");
        gd.addChoice("Help level:", helpLabels, helpLabels[currentHelpIndex]);

        gd.addMessage("");
        gd.addNumericField("Interface_scale (%):", uiScale * 100, 0);
        gd.addMessage("Close and reopen this plugin for a new interface scale to take effect.");

        gd.showDialog();
        if (gd.wasCanceled()) return;

        helpBar.applyHelpLevelChoice(gd.getNextChoiceIndex());

        double scalePercent = gd.getNextNumber();
        if (Double.isNaN(scalePercent) || scalePercent < DialogUiSupport.MIN_UI_SCALE * 100 || scalePercent > DialogUiSupport.MAX_UI_SCALE * 100) {
            IJ.log("Interface scale must be between " + (int) (DialogUiSupport.MIN_UI_SCALE * 100) + "% and "
                    + (int) (DialogUiSupport.MAX_UI_SCALE * 100) + "%. Keeping the previous value.");
        } else {
            Prefs.set(DialogUiSupport.PREF_UI_SCALE_KEY, scalePercent / 100.0);
            Prefs.savePreferences();
        }
    }

    // ==== live RoiManager refresh ====

    protected void startRoiPolling() {
        lastRoiSignature = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis) + scopeSignature();
        roiPollTimer = new Timer(ROI_POLL_INTERVAL_MS, e -> {
            if (imp.getWindow() == null) { // source image was closed - nothing left to configure
                onImageClosed();
                return;
            }
            String signature = roiPromptExtractor.signature(getRoiManager().getRoisAsArray(), axis) + scopeSignature();
            if (!signature.equals(lastRoiSignature)) {
                lastRoiSignature = signature;
                refreshGroupOptions();
            }
        });
        roiPollTimer.start();
    }

    protected abstract void refreshGroupOptions();

    /** Extra state (besides the RoiManager content) whose change must refresh the group options; "" if none. */
    protected String scopeSignature() { return ""; }

    // ==== OK / Cancel ====

    protected void onCancel() {
        approved = false;
        dispose();
    }

    /** Called when the source image is closed while the dialog is still open: cancel like {@link #onCancel()}. */
    protected void onImageClosed() {
        approved = false;
        IJ.showStatus(pluginLogName() + ": source image was closed - dialog cancelled");
        dispose();
    }

    /** Short name used in the "source image was closed" status message (e.g. "SAM PVS", "SAM3"). */
    protected abstract String pluginLogName();

    protected abstract void onOk();

    protected int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}