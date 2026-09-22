package fr.curie.miclearning.apposeplugin;

import ij.ImagePlus;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static fr.curie.miclearning.apposeplugin.DialogHelpBar.DEFAULT_INSTRUCTION_COLOR;
import static fr.curie.miclearning.apposeplugin.DialogHelpBar.DEFAULT_NOTIFICATION_COLOR;

/**
 * Embeds a "First / Prompt / Last frame" widget in a Swing dialog: one numeric
 * {@link JTextField} plus one "Use current frame" button per bound, kept in sync with the
 * image's own position
 */
public class FrameRangeSelector {
    private static final Color ENABLED_COLOR = Color.BLACK;
    private static final Color DISABLED_COLOR = Color.GRAY;

    /** owner key for this widget's temporary info notices (see DialogHelpBar) */
    private static final String INFO_OWNER = "frameRange";

    private final ImagePlus imp;
    private final int nFrames;
    private final DialogHelpBar helpBar;

    private final JPanel panel;
    private final JLabel firstLabel;
    private final JTextField firstField;
    private final JButton firstBtn;
    private final JTextField promptField;
    private final JTextField lastField;
    private final JCheckBox bidirectionalCB;

    private final List<Runnable> promptFrameListeners = new ArrayList<>();

    private boolean updating = false; // re-entrancy guard while a field is being set

    /**
     * @param imp      the image to stay in sync with (may be null to disable image sync)
     * @param nFrames  total number of frames along the relevant axis
     * @param helpBar  optional help bar to attach hover hints / warnings to
     */
    public FrameRangeSelector(ImagePlus imp, int nFrames, DialogHelpBar helpBar) {
        this.imp = imp;
        this.nFrames = nFrames;
        this.helpBar = helpBar;

        panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);

        firstField = new JTextField("1", 5);
        promptField = new JTextField("1", 5);
        lastField = new JTextField(String.valueOf(nFrames), 5);
        bidirectionalCB = new JCheckBox("Bidirectional", false);

        firstBtn = new JButton("Use current frame");
        JButton promptBtn = new JButton("Use current frame");
        JButton lastBtn = new JButton("Use current frame");

        firstLabel = addColumn(c, 0, "First frame:", firstField, firstBtn);
        JLabel promptLabel = addColumn(c, 1, "Prompt frame:", promptField, promptBtn);
        JLabel lastLabel = addColumn(c, 2, "Last frame:", lastField, lastBtn);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        panel.add(bidirectionalCB, c);


        firstBtn.addActionListener(e -> {
            if (!bidirectionalCB.isSelected()) {
                if (helpBar != null) {
                    helpBar.showInfo(INFO_OWNER, "Check \"Bidirectional\" to edit the first frame.", DEFAULT_INSTRUCTION_COLOR);
                }
                return;
            }
            pullCurrentFrameInto(firstField, this::onFirstChanged);
        });

        promptBtn.addActionListener(e -> pullCurrentFrameInto(promptField, this::onPromptChanged));
        lastBtn.addActionListener(e -> pullCurrentFrameInto(lastField, this::onLastChanged));

        addCommitListener(firstField, this::onFirstChanged);
        addCommitListener(promptField, this::onPromptChanged);
        addCommitListener(lastField, this::onLastChanged);

        // live image feedback as the user types
        addLiveNavigateListener(firstField);
        addLiveNavigateListener(promptField);
        addLiveNavigateListener(lastField);

        bidirectionalCB.addItemListener(e -> setFirstFrameEditable(bidirectionalCB.isSelected()));
        setFirstFrameEditable(false); // not bidirectional by default


        if (helpBar != null) {
            String firstHint = "First frame to segment when going backward. Only used if \"Bidirectional\" is checked.";
            String promptHint = "Frame where the prompt (text and/or ROIs) is defined - segmentation starts here.";
            String lastHint = "Last frame to segment, going forward from the prompt frame.";
            helpBar.attachHelp(firstField, firstHint);
            helpBar.attachHelp(firstLabel, firstHint);
            helpBar.attachHelp(promptField, promptHint);
            helpBar.attachHelp(promptLabel, promptHint);
            helpBar.attachHelp(lastField, lastHint);
            helpBar.attachHelp(lastLabel, lastHint);
            helpBar.attachHelp(bidirectionalCB, "If checked, segmentation also runs backward from the prompt frame down to \"First frame\".");
            helpBar.attachHelp(firstBtn, "Fill in the frame currently displayed on the image. Requires \"Bidirectional\".");
            helpBar.attachHelp(promptBtn, "Fill in the frame currently displayed on the image.");
            helpBar.attachHelp(lastBtn, "Fill in the frame currently displayed on the image.");
        }
    }

    /** The panel to add to the dialog  */
    public JPanel getPanel() {
        return panel;
    }

    private JLabel addColumn(GridBagConstraints c, int col, String labelText, JTextField field, JButton button) {
        c.gridx = col;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.CENTER;
        c.gridy = 0;
        JLabel label = new JLabel(labelText);
        panel.add(label, c);
        c.gridy = 1;
        panel.add(field, c);
        c.gridy = 2;
        panel.add(button, c);
        return label;
    }

    private void setFirstFrameEditable(boolean editable) {
        if (helpBar != null) helpBar.clearInfo(INFO_OWNER);
        firstField.setEditable(editable); // blocks typing without disabling the component
        firstField.setForeground(editable ? ENABLED_COLOR : DISABLED_COLOR);
        firstLabel.setForeground(editable ? ENABLED_COLOR : DISABLED_COLOR);
        firstBtn.setForeground(editable ? ENABLED_COLOR : DISABLED_COLOR);
        if (!editable) {
            firstField.setText(promptField.getText());
        }
    }


    private void addCommitListener(JTextField field, Runnable onCommit) {
        field.addActionListener(e -> onCommit.run()); // Enter key
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                onCommit.run();
            }
        });
    }

    /**
     * Jumps the image to whatever frame {@code field} currently parses to, on every keystroke
     * (and on a plain click into the field).
     */
    private void addLiveNavigateListener(JTextField field) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { navigate(); }
            @Override public void removeUpdate(DocumentEvent e) { navigate(); }
            @Override public void changedUpdate(DocumentEvent e) { navigate(); }

            private void navigate() {
                try {
                    navigateImageTo(clamp(Integer.parseInt(field.getText().trim())));
                } catch (NumberFormatException ignored) {
                    // incomplete/invalid input while typing - leave the image where it is
                }
            }
        });

        field.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                try {
                    navigateImageTo(clamp(Integer.parseInt(field.getText().trim())));
                } catch (NumberFormatException ignored) {
                    // incomplete/invalid input while typing - leave the image where it is
                }
            }
        });
    }

    private void pullCurrentFrameInto(JTextField field, Runnable onCommit) {
        if (imp != null) {
            field.setText(String.valueOf(imp.getCurrentSlice()));
        }
        onCommit.run();
    }

    private int readInt(JTextField field, int fallback) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(nFrames, value));
    }

    private void navigateImageTo(int frame) {
        if (imp != null) imp.setPosition(frame);
    }

    private void notice(String message) {
        if (helpBar != null) helpBar.showInfo(INFO_OWNER, message, DEFAULT_NOTIFICATION_COLOR);
    }

    private void onFirstChanged() {
        if (updating) return;
        updating = true;
        try {
            if (helpBar != null) helpBar.clearInfo(INFO_OWNER);
            int value = clamp(readInt(firstField, 1));
            firstField.setText(String.valueOf(value));

            int prompt = readInt(promptField, 1);
            if (value > prompt) {
                promptField.setText(String.valueOf(value));
                notice("Prompt frame moved to " + value + " to stay ≥ first frame.");
                fireLastBound(value);
                notifyPromptFrameChanged();
            }
            navigateImageTo(value);
        } finally {
            updating = false;
        }
    }

    private void onPromptChanged() {
        if (updating) return;
        updating = true;
        try {
            if (helpBar != null) helpBar.clearInfo(INFO_OWNER);
            int value = clamp(readInt(promptField, 1));
            promptField.setText(String.valueOf(value));

            if (bidirectionalCB.isSelected()) {
                int first = readInt(firstField, 1);
                if (first > value) {
                    firstField.setText(String.valueOf(value));
                    notice("First frame moved to " + value + " to stay ≤ prompt frame.");
                }
            } else {
                firstField.setText(String.valueOf(value));
            }

            fireLastBound(value);

            navigateImageTo(value);
            notifyPromptFrameChanged();
        } finally {
            updating = false;
        }
    }

    private void onLastChanged() {
        if (updating) return;
        updating = true;
        try {
            if (helpBar != null) helpBar.clearInfo(INFO_OWNER);
            int value = clamp(readInt(lastField, nFrames));
            lastField.setText(String.valueOf(value));

            int prompt = readInt(promptField, 1);
            if (prompt > value) {
                promptField.setText(String.valueOf(value));
                notice("Prompt frame moved to " + value + " to stay ≤ last frame.");
                if (!bidirectionalCB.isSelected()) firstField.setText(String.valueOf(value));
                notifyPromptFrameChanged();
            }
            navigateImageTo(value);
        } finally {
            updating = false;
        }
    }

    /** After prompt frame settles on {@code promptValue}, pulls last frame up if it's now invalid. */
    private void fireLastBound(int promptValue) {
        int last = readInt(lastField, nFrames);
        if (last < promptValue) {
            lastField.setText(String.valueOf(promptValue));
            notice("Last frame moved to " + promptValue + " to stay ≥ prompt frame.");
        }
    }

    /**
     * Registered callbacks fire whenever the prompt frame settles on a new value - whether
     * typed directly, pulled from "Use current frame", or dragged along by a first/last edit.
     */
    public void addPromptFrameListener(Runnable listener) {
        promptFrameListeners.add(listener);
    }

    private void notifyPromptFrameChanged() {
        for (Runnable r : promptFrameListeners) r.run();
    }

    /** Programmatically sets the prompt frame and fires listeners. */
    public void setPromptFrame(int frame) {
        promptField.setText(String.valueOf(clamp(frame)));
        onPromptChanged();
    }

    public void setBidirectional(boolean bidirectional) {
        bidirectionalCB.setSelected(bidirectional);
        firstField.setEnabled(bidirectional);
    }

    public void setLastFrame(int frame) {
        lastField.setText(String.valueOf(clamp(frame)));
        onLastChanged();
    }

    // --- live, read-only accessors, all 1-indexed ---
    public int getFirstFrame() {
        return bidirectionalCB.isSelected() ? readInt(firstField, 1) : readInt(promptField, 1);
    }

    public int getPromptFrame() {
        return readInt(promptField, 1);
    }

    public int getLastFrame() {
        return readInt(lastField, nFrames);
    }

    public boolean isBidirectional() {
        return bidirectionalCB.isSelected();
    }

}