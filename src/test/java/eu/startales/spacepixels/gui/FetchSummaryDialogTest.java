package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.skycatalog.FetchStep;
import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The fetch-summary window: result words, colours, and actions that stay usable. */
public class FetchSummaryDialogTest {

    /** Builds the rows, checks the words and colours, and copies a query, on the EDT. */
    @Test
    public void showsEachResultAndKeepsTheActionsUsable() throws Exception {
        AtomicReference<FetchSummaryDialog> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                FetchSummaryDialog dialog = new FetchSummaryDialog(null);
                ref.set(dialog);
                assertEquals("fetchSummary", dialog.getName());
                assertFalse(dialog.isModal());
                assertEquals(Dialog.ModalityType.MODELESS, dialog.getModalityType());
                assertNotNull(dialog.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                        .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)));

                dialog.present("", List.of(), true, List.of(), true);
                assertEquals("Fetch summary", dialog.getTitle());
                assertNotNull(findLabel(dialog.getContentPane(), "No step has finished yet."));

                FetchStep succeeded = FetchStep.of("SIMBAD", "Deep-sky catalogue names", FetchStep.Status.SUCCEEDED,
                        "0 rows", "", "https://simbad.cds.unistra.fr/simbad/sim-tap/sync", "SELECT b.main_id FROM basic AS b");
                FetchStep failed = FetchStep.of("AAVSO VSX", "Variable stars", FetchStep.Status.FAILED,
                        "", "answered with HTTP 503.", "https://tapvizier.cds.unistra.fr/TAPVizieR/tap/sync",
                        "SELECT Name FROM \"B/vsx/vsx\"");
                FetchStep skipped = FetchStep.of("Gaia", "Field depth", FetchStep.Status.NOT_ASKED,
                        "", "The star query produced no stars.", null, null);
                FetchStep jpl = FetchStep.of("JPL SB-Ident", "Field search", FetchStep.Status.FAILED,
                        "", "JPL answered HTTP 404.",
                        "https://ssd-api.jpl.nasa.gov/sb_ident.api?lat=37.900000&lon=23.700000&vmag-lim=19.0", null);
                dialog.present("M81", List.of(succeeded, failed, skipped), true, List.of(jpl), false);
                assertEquals("Fetch summary — M81", dialog.getTitle());
                assertEquals(FetchSummaryDialog.warningColor(), findLabel(dialog.getContentPane(), "Failed").getForeground());
                assertEquals(UiStyle.helpColor(), findLabel(dialog.getContentPane(), "Not asked").getForeground());
                assertNotNull(findLabel(dialog.getContentPane(), "Succeeded"));
                assertNotNull(findLabelContaining(dialog.getContentPane(), "Still asking the services."));
                JButton copy = findButton(dialog.getContentPane(), "Copy query");
                assertNotNull(copy);
                assertTrue(copy.isEnabled());
                copy.doClick();
                assertTrue(copy.isEnabled());
                JLabel note = null;
                for (Component child : dialog.getContentPane().getComponents()) {
                    if (child instanceof JLabel label) {
                        note = label;
                    }
                }
                assertNotNull(note);
                assertTrue(note.getText(), note.getText().contains("Copied") || note.getText().contains("Could not copy"));
                JButton open = findButton(dialog.getContentPane(), "Open");
                assertNotNull(open);
                assertTrue(open.isEnabled());

                dialog.present("M81", FetchStep.rowsFor(List.of(),
                        List.of("Variable stars (AAVSO VSX): answered with HTTP 503.")), false, List.of(), false);
                assertNotNull(findLabelContaining(dialog.getContentPane(), "Variable stars (AAVSO VSX): answered with HTTP 503."));
                assertNotNull(findLabel(dialog.getContentPane(), "Not fetched."));
                assertNull(findButton(dialog.getContentPane(), "Open"));
                assertNull(findButton(dialog.getContentPane(), "Copy query"));
            });
        } finally {
            if (ref.get() != null) {
                SwingUtilities.invokeAndWait(ref.get()::dispose);
            }
        }
    }

    /** The first plain label with this exact text. */
    private static JLabel findLabel(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = findLabel(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The first label whose text contains these words, including an HTML label. */
    private static JLabel findLabelContaining(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText() != null && label.getText().contains(text)) {
                return label;
            }
            if (child instanceof Container container) {
                JLabel found = findLabelContaining(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The first button with this caption. */
    private static JButton findButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return button;
            }
            if (child instanceof Container container) {
                JButton found = findButton(container, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
