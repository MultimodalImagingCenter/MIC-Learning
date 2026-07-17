package fr.curie.miclearning.apposeplugin;

import ij.gui.GenericDialog;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Supplier;

/**
 * Reusable contextual-help bar for a {@link GenericDialog}: a single {@link Label} whose
 * text/color changes depending on which control the mouse is hovering.
 * <p>
 * Three kinds of messages:
 * <ul>
 *   <li>hover hints, shown only while the mouse is over the attached component,
 *   and only if no sticky message is active </li>
 *   <li>a "sticky" message that stays visible, until explicitly cleared by whoever set it</li>
 *   <li>transient messages - a one-off message that displays immediately but doesn't block
 *   hover hints and doesn't need to be explicitly cleared; it just gets overwritten by the next hover.</li>
 * </ul>
 */
public class DialogHelpBar {
    private static final Color INFO_COLOR = Color.BLUE;

    /** A hover hint: text + color. Returned by a dynamic hint supplier; {@code null} means "no hint right now". */
    public static final class Hint {
        public final String text;
        public final Color color;

        public Hint(String text, Color color) {
            this.text = text;
            this.color = color;
        }
    }

    private final GenericDialog gd;
    private final Label label;
    private final Panel panel;


    private String stickyText = null;
    private String stickyOwner = null;

    public DialogHelpBar(GenericDialog gd) {
        this.gd = gd;
        this.panel = new Panel(new FlowLayout(FlowLayout.LEFT));
        this.label = new Label(" ");
        this.label.setForeground(INFO_COLOR);
        this.panel.add(label);
    }


    /** The panel to add to the dialog */
    public Panel getPanel() {
        return panel;
    }

    /** Attaches a hover hint to a component. */
    public void attach(Component c, String hint) {
        attach(c, hint, INFO_COLOR);
    }

    /** Attaches a hover hint of the given color to a component. */
    public void attach(Component c, String hint, Color color) {
        attachDynamic(c, () -> new Hint(hint, color));
    }

    /**
     * Attaches a hover hint computed every time the mouse enters {@code c}
     * Use when whether/what to show depends on live state
     */
    public void attachDynamic(Component c, Supplier<Hint> hintSupplier) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                if (stickyText != null) return; // sticky message showing - leave it alone
                Hint hint = hintSupplier.get();
                if (hint != null) show(hint.text, hint.color);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (stickyText != null) return; // sticky message showing - leave it alone
                clear();
            }
        });
    }

    /**
     * Shows a message that stays visible until {@link #clearSticky(String)}
     * is called with the same {@code owner}.
     * Use for warnings tied to a condition that needs to be resolved
     *
     * @param owner arbitrary key identifying who set the message,
     */
    public void sticky(String owner, String text, Color color) {
        stickyOwner = owner;
        stickyText = text;
        show(text, color);
    }

    /** Clears the sticky message, but only if {@code owner} is the one who set it. */
    public void clearSticky(String owner) {
        if (owner != null && owner.equals(stickyOwner)) {
            stickyOwner = null;
            stickyText = null;
            clear();
        }
    }
    /**
     * Shows a one-off message, without engaging the sticky mechanism: it doesn't
     * block hover hints on other components and isn't explicitly cleared (just sits
     * until the next hover event replaces it)
     */
    public void showTransient(String text, Color color) {
        if (stickyText == null) show(text, color);
    }

    /** Directly sets the label text/color */
    public void show(String text, Color color) {
        label.setText(text);
        label.setForeground(color);
        gd.pack();
    }

    /** Reverts the label to its blank default */
    public void clear() {
        label.setText(" ");
        label.setForeground(INFO_COLOR);
        gd.pack();
    }
}
