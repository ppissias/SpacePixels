package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPasswordField;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.border.CompoundBorder;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Astrometry Config groups each category in a titled box and keeps editors at the width of their text. */
public class AstrometryConfigLayoutTest {

    /** The three categories are boxed, and short values no longer sit in a field stretched to the status line. */
    @Test
    public void categoriesAreBoxedAndEditorsFitTheirText() throws Exception {
        EventQueue.invokeAndWait(() -> {
            ConfigurationPanel panel = new ConfigurationPanel(null, null, null, null, () -> false);
            try {
                assertEquals(List.of(
                        "Plate Solving",
                        "Sky Catalogue (Annotate)",
                        "Observing Site (for object identification)"), sectionTitles(panel));

                JTextField code = named(panel, JTextField.class, "IAU Observatory Code");
                JTextField latitude = named(panel, JTextField.class, "Site Latitude (N)");
                JTextField longitude = named(panel, JTextField.class, "Site Longitude (E)");
                JPasswordField key = named(panel, JPasswordField.class, "Astrometry.net Account");
                JSpinner starDepth = named(panel, JSpinner.class, "Star Depth (Gaia G)");
                JSpinner solarDepth = named(panel, JSpinner.class, "Asteroid and Comet Depth (V)");
                // Short numeric text fields take the standard number width; the API key keeps its long field.
                for (JTextField field : List.of(code, latitude, longitude)) {
                    assertEquals(UiStyle.space(UiStyle.NUMBER_INPUT_WIDTH), field.getPreferredSize().width);
                }
                assertEquals(UiStyle.space(UiStyle.NUMBER_INPUT_WIDTH), starDepth.getPreferredSize().width);
                assertEquals(18, key.getColumns());

                assertEquals("Plate Solving", sectionTitle(key));
                assertEquals("Sky Catalogue (Annotate)", sectionTitle(starDepth));
                assertEquals("Observing Site (for object identification)", sectionTitle(latitude));
                assertNull(sectionTitle(named(panel, JButton.class, "Save")));
                assertNull(sectionTitle(named(panel, JButton.class, "Revert")));
                assertNull(sectionTitle(named(panel, JButton.class, "Fill Site from FITS Header")));

                panel.setSize(1100, 1700);
                layout(panel);
                assertFits(code);
                assertFits(latitude);
                assertFits(longitude);
                assertFits(key);
                assertFits(starDepth);
                assertFits(solarDepth);
                assertTrue(code.getWidth() < UiStyle.space(120));
                assertTrue(latitude.getWidth() < UiStyle.space(190));
                assertEquals("numeric text fields share one width", latitude.getWidth(), code.getWidth());
                java.awt.Point codeAt = javax.swing.SwingUtilities.convertPoint(code.getParent(), code.getX(), code.getY(), panel);
                java.awt.Point latAt = javax.swing.SwingUtilities.convertPoint(latitude.getParent(), latitude.getX(), latitude.getY(), panel);
                java.awt.Point lonAt = javax.swing.SwingUtilities.convertPoint(longitude.getParent(), longitude.getX(), longitude.getY(), panel);
                java.awt.Point starAt = javax.swing.SwingUtilities.convertPoint(starDepth.getParent(), starDepth.getX(), starDepth.getY(), panel);
                java.awt.Point solarAt = javax.swing.SwingUtilities.convertPoint(solarDepth.getParent(), solarDepth.getX(), solarDepth.getY(), panel);
                int right = latAt.x + latitude.getWidth();
                assertEquals(right, codeAt.x + code.getWidth());
                assertEquals(right, lonAt.x + longitude.getWidth());
                assertEquals(right, starAt.x + starDepth.getWidth());
                assertEquals(right, solarAt.x + solarDepth.getWidth());
                assertEquals(latAt.x, codeAt.x);
                for (String title : sectionTitles(panel)) {
                    JComponent box = section(panel, title);
                    assertTrue(box.getHeight() <= box.getPreferredSize().height + 2);
                }
            } finally {
                panel.close();
            }
        });
    }

    /** An editor keeps the width of its text when the surrounding stack is widened by the status line. */
    private static void assertFits(JComponent editor) {
        assertTrue(editor.getWidth() > 0);
        assertTrue(editor.getWidth() <= editor.getPreferredSize().width + 2);
        Container wider = editor.getParent();
        while (wider != null && wider.getWidth() <= editor.getWidth() + UiStyle.space(40)) {
            wider = wider.getParent();
        }
        assertNotNull(editor.getAccessibleContext().getAccessibleName(), wider);
        assertTrue(editor.getAccessibleContext().getAccessibleName() + " stretched to " + wider.getWidth(),
                editor.getWidth() + UiStyle.space(40) < wider.getWidth());
    }

    /** Visible category titles, in form order. */
    private static List<String> sectionTitles(Component root) {
        List<String> titles = new ArrayList<>();
        collectSections(root, titles);
        return titles;
    }

    private static void collectSections(Component component, List<String> titles) {
        if (component instanceof JComponent control && control.getBorder() instanceof CompoundBorder border
                && border.getOutsideBorder() instanceof TitledBorder titled) {
            titles.add(titled.getTitle());
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectSections(child, titles);
            }
        }
    }

    /** The titled category that contains a control, or null when the control sits outside the form. */
    private static String sectionTitle(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof JComponent control && control.getBorder() instanceof CompoundBorder border
                    && border.getOutsideBorder() instanceof TitledBorder titled) {
                return titled.getTitle();
            }
        }
        return null;
    }

    private static JComponent section(Component root, String title) {
        if (root instanceof JComponent control && title.equals(sectionTitle(control))
                && control.getBorder() instanceof CompoundBorder) {
            return control;
        }
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                JComponent found = section(child, title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends Component> T named(Component root, Class<T> type, String accessibleName) {
        T found = find(root, type, accessibleName);
        assertNotNull(accessibleName, found);
        return found;
    }

    private static <T extends Component> T find(Component component, Class<T> type, String accessibleName) {
        if (type.isInstance(component)
                && accessibleName.equals(component.getAccessibleContext().getAccessibleName())) {
            return type.cast(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type, accessibleName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Lays out real Swing descendants so the measured widths are the widths a window would give them. */
    private static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) {
            if (child instanceof Container container) {
                layout(container);
            }
        }
    }
}
