package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.UIManager;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.SpinnerModel;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

/** Shared theme-derived typography, readable help color and logical-pixel scaling for application forms. */
final class UiStyle {
    /** Prevents construction of the stateless style helpers. */
    private UiStyle() {}

    /** Returns current look-and-feel body typography, including the configured user font scale. */
    static Font bodyFont() {
        Font font = UIManager.getFont("Label.font");
        return font == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : font;
    }

    /** Distinguishes headings through weight and a relative, scaled increase over body text. */
    static Font sectionFont() {
        return bodyFont().deriveFont(Font.BOLD, bodyFont().getSize2D() + UIScale.scale(3f));
    }

    /** Keeps enabled guidance readable; themes may supply a dedicated application help color. */
    static Color helpColor() {
        Color color = UIManager.getColor("SpacePixels.helpForeground");
        if (color == null) color = UIManager.getColor("Label.foreground");
        return color == null ? Color.LIGHT_GRAY : color;
    }

    /** Readable link text for dark and light surfaces, with an optional appearance-file override. */
    static Color linkColor() {
        Color color = UIManager.getColor("SpacePixels.linkForeground");
        if (color == null) color = UIManager.getColor("Component.linkColor");
        return color == null ? helpColor() : color;
    }

    /** Image canvases stay dark in every theme, so their placeholder text must not inherit a light theme's black text. */
    static Color imageTextColor() {
        Color color = UIManager.getColor("SpacePixels.imageForeground");
        return color == null ? Color.WHITE : color;
    }

    /** Converts logical application spacing to the current FlatLaf user scale. */
    static int space(int logicalPixels) {
        return UIScale.scale(logicalPixels);
    }

    /** Sets a scaled minimum content width while keeping the native editor height for the current font. */
    static void inputWidth(JComponent control, int logicalWidth) {
        Dimension natural = control.getPreferredSize();
        if (control instanceof JTextField field) field.setColumns(0);
        control.setPreferredSize(new Dimension(Math.max(space(logicalWidth), natural.width), natural.height));
    }

    /**
     * A spinner whose text keeps the caret where the user puts it when it gains the focus. Since FlatLaf 3.6, focusing
     * a spinner by Tab or by a click selects its whole value; SpacePixels keeps the earlier behaviour.
     */
    static JSpinner spinner(SpinnerModel model) {
        JSpinner spinner = new JSpinner(model);
        spinner.putClientProperty(FlatClientProperties.SELECT_ALL_ON_FOCUS_POLICY,
                FlatClientProperties.SELECT_ALL_ON_FOCUS_POLICY_NEVER);
        return spinner;
    }
}
