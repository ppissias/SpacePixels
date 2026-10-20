package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.List;

/** Finds a showing control by the words on it. AssertJ's {@code button(String)} is a component name, not this text. */
final class VisibleText {

    private VisibleText() {
    }

    static GenericTypeMatcher<JButton> button(String text) {
        return new GenericTypeMatcher<>(JButton.class, true) {
            @Override
            protected boolean isMatching(JButton component) {
                return text.equals(component.getText());
            }
        };
    }

    static GenericTypeMatcher<JLabel> labelContaining(String text) {
        return new GenericTypeMatcher<>(JLabel.class, true) {
            @Override
            protected boolean isMatching(JLabel component) {
                String shown = component.getText();
                return shown != null && shown.contains(text);
            }
        };
    }

    /** The spinner in the same settings row as a label. The row is the nearest parent that holds one spinner. */
    static JSpinner spinnerByLabel(Component root, String labelText) {
        JLabel label = findLabel(root, labelText);
        if (label == null) {
            throw new AssertionError("No showing label contains \"" + labelText + "\"");
        }
        Container parent = label.getParent();
        if (parent != null && parent.getLayout() instanceof GridBagLayout layout) {
            int row = layout.getConstraints(label).gridy;
            for (Component child : parent.getComponents()) {
                if (child instanceof JSpinner spinner && layout.getConstraints(spinner).gridy == row) {
                    return spinner;
                }
            }
        }
        for (Container current = parent; current != null; current = current.getParent()) {
            List<JSpinner> spinners = new ArrayList<>();
            collectSpinners(current, spinners);
            if (spinners.size() == 1) {
                return spinners.get(0);
            }
            if (spinners.size() > 1) {
                break;
            }
        }
        throw new AssertionError("No single spinner beside \"" + labelText + "\"");
    }

    private static JLabel findLabel(Component component, String labelText) {
        if (component instanceof JLabel label) {
            String shown = label.getText();
            if (label.isShowing() && shown != null && shown.contains(labelText)) {
                return label;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JLabel found = findLabel(child, labelText);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void collectSpinners(Component component, List<JSpinner> spinners) {
        if (component instanceof JSpinner spinner) {
            spinners.add(spinner);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectSpinners(child, spinners);
            }
        }
    }
}
