package fr.curie.miclearning.apposeplugin;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.awt.Button;
import java.awt.Checkbox;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Label;
import java.awt.Panel;
import java.awt.TextField;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Embeds a "First / Prompt / Last frame" widget in a {@link GenericDialog}: one numeric
 * {@link TextField} plus one "Use current frame" button per bound, kept in sync with the
 * image's own position
 */
public class FrameRangeSelector {
    private static final Color ENABLED_COLOR = Color.BLACK;
    private static final Color DISABLED_COLOR = Color.GRAY;
    private static final Color NOTICE_COLOR = Color.ORANGE;

    private final ImagePlus imp;
    private final int nFrames;
    private final DialogHelpBar helpBar;

    private final Panel panel;
    private final Label firstLabel;
    private final TextField firstField;
    private final Button firstBtn;
    private final TextField promptField;
    private final TextField lastField;
    private final Checkbox bidirectionalCB;

    private final List<Runnable> promptFrameListeners = new ArrayList<>();

    private boolean updating = false;

    /**
     * @param imp      the image to stay in sync with (may be null to disable image sync)
     * @param nFrames  total number of frames along the relevant axis
     * @param helpBar  optional help bar to attach hover hints / warnings to
     */
    public FrameRangeSelector(ImagePlus imp, int nFrames, DialogHelpBar helpBar) {
        this.imp = imp;
        this.nFrames = nFrames;
        this.helpBar = helpBar;

        panel = new Panel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 4, 2, 4);

        firstField = new TextField("1", 5);
        promptField = new TextField("1", 5);
        lastField = new TextField(String.valueOf(nFrames), 5);
        bidirectionalCB = new Checkbox("Bidirectional", false);

        firstBtn = new Button("Use current frame");
        Button promptBtn = new Button("Use current frame");
        Button lastBtn = new Button("Use current frame");

        firstLabel = addRow(c, 0, "First frame:", firstField, firstBtn);
        Label promptLabel = addRow(c, 1, "Prompt frame:", promptField, promptBtn);
        Label lastLabel = addRow(c, 2, "Last frame:", lastField, lastBtn);

        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 3;
        c.anchor = GridBagConstraints.WEST;
        panel.add(bidirectionalCB, c);


        firstBtn.addActionListener(e -> {
            if (!bidirectionalCB.getState()) {
                if (helpBar != null) {
                    helpBar.showTransient("Check \"Bidirectional\" to edit the first frame.", Color.ORANGE);
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

        bidirectionalCB.addItemListener(e -> setFirstFrameEditable(bidirectionalCB.getState()));
        setFirstFrameEditable(false); // not bidirectional by default


        if (helpBar != null) {
            String firstHint = "First frame to segment when going backward. Only used if \"Bidirectional\" is checked.";
            String promptHint = "Frame where the prompt (text and/or ROIs) is defined - segmentation starts here.";
            String lastHint = "Last frame to segment, going forward from the prompt frame.";
            helpBar.attach(firstField, firstHint);
            helpBar.attach(firstLabel, firstHint);
            helpBar.attach(promptField, promptHint);
            helpBar.attach(promptLabel, promptHint);
            helpBar.attach(lastField, lastHint);
            helpBar.attach(lastLabel, lastHint);
            helpBar.attach(bidirectionalCB, "If checked, segmentation also runs backward from the prompt frame down to \"First frame\".");
            helpBar.attach(firstBtn, "Fill in the frame currently displayed on the image. Requires \"Bidirectional\".");
            helpBar.attach(promptBtn, "Fill in the frame currently displayed on the image.");
            helpBar.attach(lastBtn, "Fill in the frame currently displayed on the image.");
        }
    }

    /** The panel to add to the dialog  */
    public Panel getPanel() {
        return panel;
    }

    private Label addRow(GridBagConstraints c, int row, String labelText, TextField field, Button button) {
        c.gridy = row;
        c.gridwidth = 1;
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        Label label = new Label(labelText);
        panel.add(label, c);
        c.gridx = 1;
        panel.add(field, c);
        c.gridx = 2;
        panel.add(button, c);
        return label;
    }

    private void setFirstFrameEditable(boolean editable) {
        firstField.setEditable(editable); // blocks typing without disabling the component
        firstField.setForeground(editable ? ENABLED_COLOR : DISABLED_COLOR);
        firstLabel.setForeground(editable ? ENABLED_COLOR : DISABLED_COLOR);
        firstBtn.setForeground(editable ? ENABLED_COLOR : DISABLED_COLOR);
        if (!editable) {
            firstField.setText(promptField.getText());
        }
    }


    private void addCommitListener(TextField field, Runnable onCommit) {
        field.addActionListener(e -> onCommit.run()); // Enter key
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                onCommit.run();
            }
        });
    }

    private void pullCurrentFrameInto(TextField field, Runnable onCommit) {
        if (imp != null) {
            field.setText(String.valueOf(imp.getCurrentSlice()));
        }
        onCommit.run();
    }

    private int readInt(TextField field, int fallback) {
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
        if (helpBar != null) helpBar.showTransient(message, NOTICE_COLOR);
    }

    private void onFirstChanged() {
        if (updating) return;
        updating = true;
        if (!bidirectionalCB.getState()) {
            if (helpBar != null) {
                helpBar.showTransient("Check \"Bidirectional\" to edit the first frame.", Color.ORANGE);
            }
            updating = false;
            return;
        }
        try {
            int value = clamp(readInt(firstField, 1));
            firstField.setText(String.valueOf(value));

            int prompt = readInt(promptField, 1);
            if (value > prompt) {
                promptField.setText(String.valueOf(value));
                notice("Prompt frame moved to " + value + " to stay \u2265 first frame.");
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
            int value = clamp(readInt(promptField, 1));
            promptField.setText(String.valueOf(value));

            if (bidirectionalCB.getState()) {
                int first = readInt(firstField, 1);
                if (first > value) {
                    firstField.setText(String.valueOf(value));
                    notice("First frame moved to " + value + " to stay \u2264 prompt frame.");
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
            int value = clamp(readInt(lastField, nFrames));
            lastField.setText(String.valueOf(value));

            int prompt = readInt(promptField, 1);
            if (prompt > value) {
                promptField.setText(String.valueOf(value));
                notice("Prompt frame moved to " + value + " to stay \u2264 last frame.");
                if (!bidirectionalCB.getState()) firstField.setText(String.valueOf(value));
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
            notice("Last frame moved to " + promptValue + " to stay \u2265 prompt frame.");
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
        bidirectionalCB.setState(bidirectional);
        firstField.setEnabled(bidirectional);
    }

    public void setLastFrame(int frame) {
        lastField.setText(String.valueOf(clamp(frame)));
        onLastChanged();
    }

    // --- live, read-only accessors, all 1-indexed ---
    public int getFirstFrame() {
        return bidirectionalCB.getState() ? readInt(firstField, 1) : readInt(promptField, 1);
    }

    public int getPromptFrame() {
        return readInt(promptField, 1);
    }

    public int getLastFrame() {
        return readInt(lastField, nFrames);
    }

    public boolean isBidirectional() {
        return bidirectionalCB.getState();
    }

}
