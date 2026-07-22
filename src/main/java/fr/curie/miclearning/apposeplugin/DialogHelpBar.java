package fr.curie.miclearning.apposeplugin;

import ij.Prefs;
import ij.gui.GenericDialog;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Reusable contextual-help bar for a {@link GenericDialog}: a single {@link Label} whose
 * text/color changes depending on which control the mouse is hovering.
 * <p>
 * Three kinds of messages:
 * <ul>
 *   <li>Help (hover) - {@link #attachHelp}:  shown only while the mouse is over the attached component.
 *   Use for "how to use this" / "why this is currently unavailable" hints.</li>
 *   <li>Info (temporary) - {@link #showInfo}/{@link #clearInfo}:  message tied to an {@code owner} key
 *   that auto-expires after a short delay (default 4s), or can be cleared immediately.
 *   Use for info like "prompt frame moved to N" or "this frame has no usable ROI". </li>
 *   <li>Warning (persistent) - {@link #warn}/{@link #clearWarning}: a message tied to an {@code owner}
 *   key that stays visible until that owner explicitly clears (i.e. until the underlying problem is resolved).</li>
 * </ul>
 * Rendering priority is always help &gt; info &gt;  warning &gt; blank
 */
public class DialogHelpBar {

    public enum HelpBarMode {
        HELP, // print help + info + warning
        INFO, // print info + warning
        WARNING // print only warning
    }
    /** A hover hint: text + color. Returned by a dynamic hint supplier; {@code null} means "no hint right now". */
    public static final class Hint {
        public final String text;
        public final Color color;

        public Hint(String text, Color color) {
            this.text = text;
            this.color = color;
        }
    }

    public static final Color DEFAULT_BLANK_COLOR = Color.decode("#4248e1");
    public static final Color DEFAULT_HELP_COLOR = Color.decode("#4248e1");;
    public static final Color DEFAULT_INSTRUCTION_COLOR = Color.decode("#914fe4"); // explain why option is disabled and how to enable
    public static final Color DEFAULT_NOTIFICATION_COLOR = Color.decode("#f4634f"); // FYIs e.g. when "prompt frame moved to N"
    public static final Color DEFAULT_WARNING_COLOR = Color.decode("#e44f4f");
    private static final int DEFAULT_INFO_DURATION_MS = 3700;
    private static final String PREF_MODE_KEY = "miclearning.helpbarmode";
    private static final HelpBarMode DEFAULT_MODE = HelpBarMode.HELP;

    private static final int MAX_LINES = 2;
    private static final int DEFAULT_BASE_FONT_SIZE = 12; // fallback
    private static final float HELP_BAR_FONT_SCALE = 0.8f; // relative to the dialog's own  font

    private HelpBarMode helpBarMode;

    public void setHelpBarMode(HelpBarMode helpBarMode) {
        this.helpBarMode = helpBarMode;
        hovering = false; // avoid a stuck hover-hint if we just switched away from HELP
        renderCurrent();
    }

    /** Reads the last help-bar level chosen via {@link #openSettingsDialog()} */
    public static HelpBarMode loadSavedMode() {
        String saved = Prefs.get(PREF_MODE_KEY, DEFAULT_MODE.name());
        try {
            return HelpBarMode.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return DEFAULT_MODE;
        }
    }

    private final GenericDialog gd;
    private final List<Label> lines = new ArrayList<>();
    private final Panel panel;
    private final Font helpBarFont;
    private final Font warningFont;
    private final int lineHeightPx;

    private boolean hovering = false;

    private String infoOwner;
    private String infoText;
    private Color infoColor;
    private Timer infoTimer;

    private String warningOwner;
    private String warningText;
    private Color warningColor;

    public DialogHelpBar(GenericDialog gd) {
        this(gd, HelpBarMode.HELP);
    }

    public DialogHelpBar(GenericDialog gd, HelpBarMode helpBarMode) {
        this.gd = gd;
        this.panel = new Panel(new GridLayout(MAX_LINES, 1));
        this.helpBarMode = helpBarMode;

        // define hel bar size and font size so deriving from GuiScale
        Font dialogFont = gd.getFont();
        float baseSize = dialogFont != null ? dialogFont.getSize2D() : DEFAULT_BASE_FONT_SIZE;
        int helpBarSize = Math.round(baseSize * HELP_BAR_FONT_SCALE);
        this.helpBarFont = new Font(Font.SANS_SERIF, Font.PLAIN, helpBarSize);
        this.warningFont = new Font(Font.SANS_SERIF, Font.BOLD, helpBarSize);
        this.lineHeightPx = Toolkit.getDefaultToolkit().getFontMetrics(helpBarFont).getHeight(); // deprecated, but easiest solution I found ?

        initLines();
    }

    private void initLines() {
        for (int i = 0; i < MAX_LINES; i++) {
            Label line = new Label(" ");
            line.setForeground(DEFAULT_BLANK_COLOR);
            line.setFont(helpBarFont);
            panel.add(line);
            lines.add(line);
        }
    }


    /** The panel to add to the dialog */
    public Panel getPanel() {
        return panel;
    }

    /**
     * Caps the help bar's preferred width to {@code width}
     */
    public void lockWidth(int width) {
        panel.setPreferredSize(new Dimension(width, MAX_LINES * lineHeightPx));
    }

    // ==== HELP (hover) ====

    /** Attaches a hover hint to a component. */
    public void attachHelp(Component c, String hint) {
        attachHelp(c, hint, DEFAULT_HELP_COLOR);
    }

    /** Attaches a fixed hover hint of the given color to a component. */
    public void attachHelp(Component c, String hint, Color color) {
        attachHelp(c, () -> new Hint(hint, color));
    }

    /**
     * Attaches a hover hint computed fresh every time the mouse enters {@code c}
     */
    public void attachHelp(Component c, Supplier<Hint> hintSupplier) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (helpBarMode != HelpBarMode.HELP) return; // checked live, so a mode change takes effect immediately
                Hint hint = hintSupplier.get();
                if (hint == null) return;
                hovering = true;
                show(hint.text, hint.color);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hovering = false;
                renderCurrent();
            }
        });
    }

    // ==== INFO (temporary) ====

    /** Shows a temporary info message (default time and color) tied to {@code owner}. */
    public void showInfo(String owner, String text) {
        showInfo(owner, text, DEFAULT_NOTIFICATION_COLOR, DEFAULT_INFO_DURATION_MS);
    }

    /** Shows a temporary info message (default time) tied to {@code owner}. */
    public void showInfo(String owner, String text, Color color) {
        showInfo(owner, text, color, DEFAULT_INFO_DURATION_MS);
    }

    /** Shows a temporary info message tied to {@code owner}, auto-clearing after {@code durationMs}. */
    public void showInfo(String owner, String text, Color color, int durationMs) {
        if (helpBarMode == HelpBarMode.WARNING) return;
        infoOwner = owner;
        infoText = text;
        infoColor = color;
        if (infoTimer != null) infoTimer.stop();
        infoTimer = new Timer(durationMs, e -> {
            infoOwner = null;
            infoText = null;
            renderCurrent();
        });
        infoTimer.setRepeats(false);
        infoTimer.start();
        renderCurrent();
    }

    /**
     * Clears the current info message immediately, only if it belongs to {@code owner}.
     */
    public void clearInfo(String owner) {
        if (helpBarMode == HelpBarMode.WARNING) return;
        if (owner != null && owner.equals(infoOwner)) {
            if (infoTimer != null) infoTimer.stop();
            infoOwner = null;
            infoText = null;
            renderCurrent();
        }
    }

    // ==== WARNING (persistent) ====

    /** Shows a warning (DEFAULT_WARNING_COLOR) tied to {@code owner} that stays visible (beneath info/help) until {@link #clearWarning}. */
    public void warn(String owner, String text) {
        warningOwner = owner;
        warningText = text;
        warningColor = DEFAULT_WARNING_COLOR;
        renderCurrent();
    }
    /** Shows a warning tied to {@code owner} that stays visible (beneath info/help) until {@link #clearWarning}. */
    public void warn(String owner, String text, Color color) {
        warningOwner = owner;
        warningText = text;
        warningColor = color;
        renderCurrent();
    }

    /** Clears the warning, only if it belongs to {@code owner} */
    public void clearWarning(String owner) {
        if (owner != null && owner.equals(warningOwner)) {
            warningOwner = null;
            warningText = null;
            renderCurrent();
        }
    }

    // ==== settings ====

    private static final HelpBarMode[] SETTINGS_MODE_VALUES = {HelpBarMode.HELP, HelpBarMode.INFO, HelpBarMode.WARNING};
    private static final String[] SETTINGS_MODE_LABELS = {
            "Show everything (hover help, notifications, warnings)",
            "Show notifications and warnings (no hover help)",
            "Show warnings only"
    };

    /**
     * Opens a small dialog letting the user pick how much guidance this  help bars should show
     */
    public void openSettingsDialog() {
        int currentIndex = 1; 
        for (int i = 0; i < SETTINGS_MODE_VALUES.length; i++) {
            if (SETTINGS_MODE_VALUES[i] == helpBarMode) currentIndex = i;
        }

        GenericDialog gd = new GenericDialog("Help bar settings");
        gd.addMessage("Choose how much guidance the dialog's help bar should show:");
        gd.addChoice("Help level:", SETTINGS_MODE_LABELS, SETTINGS_MODE_LABELS[currentIndex]);
        gd.showDialog();
        if (gd.wasCanceled()) return;

        String chosenLabel = gd.getNextChoice();
        for (int i = 0; i < SETTINGS_MODE_LABELS.length; i++) {
            if (SETTINGS_MODE_LABELS[i].equals(chosenLabel)) {
                HelpBarMode newMode = SETTINGS_MODE_VALUES[i];
                setHelpBarMode(newMode);
                Prefs.set(PREF_MODE_KEY, newMode.name());
                Prefs.savePreferences();
                break;
            }
        }
    }

    // ==== rendering ====
    /** Redraws the label from current state */
    private void renderCurrent() {

        if (helpBarMode != HelpBarMode.WARNING && infoText != null) { // info text printed over hint when triggered, but under hints after
            show(infoText, infoColor);
        }
        else if (helpBarMode == HelpBarMode.HELP && hovering) {
            return; // a hover hint is currently displayed - don't touch it
        }
        else if (warningText != null) {
            show(warningText, warningColor, warningFont);
        } else {
            show(" ", DEFAULT_BLANK_COLOR);
        }
    }

    private void show(String text, Color color, Font font) {
        setLines(text, color, font);
        gd.pack();
    }
    private void show(String text, Color color) {
        show(text, color, helpBarFont);
    }

    /**
     * Renders {@code text} as one {@link Label} per {@code \n}-separated line,
     */
    private void setLines(String text, Color color, Font font) {
        String[] textLines = text.split("\n", -1);
        for (int i = 0; i < MAX_LINES; i++) {
            String lineText;
            if (i < MAX_LINES - 1) {
                lineText = i < textLines.length ? textLines[i] : " ";
            } else {
                lineText = i < textLines.length ? String.join(" ", Arrays.asList(textLines).subList(i, textLines.length)) : " ";
            }
            Label line = lines.get(i);
            line.setText(lineText.isEmpty() ? " " : lineText);
            line.setFont(font);
            line.setForeground(color);
        }
    }
}
