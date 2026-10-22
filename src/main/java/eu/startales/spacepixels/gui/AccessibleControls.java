package eu.startales.spacepixels.gui;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Cursor;

/** Shared keyboard-operable links and label associations for application controls. */
final class AccessibleControls {
    /** Prevents construction of the stateless control helpers. */
    private AccessibleControls() {}

    /** Creates a focusable text button with native keyboard activation and a descriptive accessible name. */
    static JButton link(String text, String accessibleName, Runnable action) {
        JButton button = new JButton(text);
        button.putClientProperty("JButton.buttonType", "borderless");
        button.setForeground(DetectionConfigurationPanel.accentColor());
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.getAccessibleContext().setAccessibleName(accessibleName);
        button.addActionListener(event -> action.run());
        return button;
    }

    /** Associates a title with its editor and supplies plain-language descriptions, including nested editors. */
    static void label(JLabel label, JComponent input, String description) {
        label.setLabelFor(input);
        describe(input, label.getText(), plainText(description));
    }

    /** Names editable descendants of composite controls while retaining each control's existing actions. */
    private static void describe(JComponent input, String title, String description) {
        String name = input instanceof AbstractButton button && button.getText() != null && !button.getText().isBlank()
                ? title + ": " + plainText(button.getText()) : title;
        input.getAccessibleContext().setAccessibleName(name);
        input.getAccessibleContext().setAccessibleDescription(description);
        if (input instanceof JSpinner spinner && spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            describe(editor.getTextField(), title, description);
        } else if (!(input instanceof AbstractButton || input instanceof JComboBox<?> || input instanceof JTextComponent)) {
            for (Component child : input.getComponents()) {
                if (child instanceof JComponent component) describe(component, title, description);
            }
        }
    }

    /** Removes presentation markup from text read by assistive technology. */
    static String plainText(String html) {
        return html.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
    }
}
