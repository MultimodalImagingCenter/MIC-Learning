package fr.curie.miclearning.apposeplugin;

import javax.swing.*;
import java.awt.*;

/**
 * Shared UI-scale and section-title styling for the plugin dialogs in this project
 */
public class DialogUiSupport {

    public static final String PREF_UI_SCALE_KEY = "miclearning.sam3.imagedialog.uiscale";
    public static final double DEFAULT_UI_SCALE = 1;
    public static final double MIN_UI_SCALE = 0.25;
    public static final double MAX_UI_SCALE = 4.0;

    private DialogUiSupport() {}

    /**
     * Recursively derives every font in {@code comp}'s subtree by {@code uiScale}, and scales
     * checkbox icons too. Applied once at construction time (the dialog must be closed and
     * reopened for a new scale to take effect).
     */
    public static void rescaleFonts(Component comp, double uiScale) {
        Font font = comp.getFont();
        if (font != null) comp.setFont(font.deriveFont((float) (font.getSize2D() * uiScale)));
        if (comp instanceof JCheckBox && ((JCheckBox) comp).getIcon() == null) {
            Icon checkIcon = UIManager.getIcon("CheckBox.icon");
            if (checkIcon != null) ((JCheckBox) comp).setIcon(scaledIcon(checkIcon, uiScale));
        }
        if (comp instanceof Container) {
            for (Component child : ((Container) comp).getComponents()) rescaleFonts(child, uiScale);
        }
    }

    /** Wraps {@code icon}, painting it scaled by {@code scale} */
    private static Icon scaledIcon(Icon icon, double scale) {
        int w = (int) Math.round(icon.getIconWidth() * scale);
        int h = (int) Math.round(icon.getIconHeight() * scale);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.translate(x, y);
                g2.scale((double) w / icon.getIconWidth(), (double) h / icon.getIconHeight());
                icon.paintIcon(c, g2, 0, 0);
                g2.dispose();
            }
            @Override public int getIconWidth() {return w;}
            @Override public int getIconHeight() {return h;}
        };
    }

    /** Scales a pixel dimension (row height, column width, ...) by {@code uiScale}. */
    public static int scaled(int pixels, double uiScale) {
        return (int) Math.round(pixels * uiScale);
    }

    /** A section-title label: bold, one size bigger than body text. */
    public static JLabel createSectionTitle(String text) {
        JLabel label = new JLabel(text);
        Font base = label.getFont();
        label.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1f));
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        return label;
    }
}