/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Verifies structure, tabs and content of the Acknowledgements dialog. */
public class AcknowledgementsDialogTest {

    /** Checks the credits window's tabs, resources and close action on the EDT. */
    @Test
    public void dialogContainsTabsAndExpectedAcknowledgements() throws Exception {
        AtomicReference<AcknowledgementsDialog> ref = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                AcknowledgementsDialog dialog = new AcknowledgementsDialog(null);
                ref.set(dialog);
                dialog.pack();

                assertEquals("Credits & Acknowledgements", dialog.getTitle());

                JTabbedPane tabs = findTabbedPane(dialog.getContentPane());
                assertNotNull("Dialog must contain a JTabbedPane", tabs);
                assertEquals(4, tabs.getTabCount());
                assertEquals("Test Data", tabs.getTitleAt(0));
                assertEquals("External Services & APIs", tabs.getTitleAt(1));
                assertEquals("Software & Utilities", tabs.getTitleAt(2));
                assertEquals("Scientific References", tabs.getTitleAt(3));

                // Verify Tab 1 (Test Data) includes the contributors credited in README and MANUAL.
                String tab1Text = extractEditorText(tabs.getComponentAt(0));
                assertTrue(tab1Text.contains("Klangwolke"));
                assertTrue(tab1Text.contains("Austral Kiwi"));
                assertTrue(tab1Text.contains("Duncan Warren"));
                assertTrue(tab1Text.contains("TheStarsabove"));
                assertTrue(tab1Text.contains("Kumar"));
                assertTrue(tab1Text.contains("https://github.com/chvvkumar"));

                // Verify Tab 2 (Services & APIs)
                String tab2Text = extractEditorText(tabs.getComponentAt(1));
                assertTrue(tab2Text.contains("NASA JPL") || tab2Text.contains("Horizons"));
                assertTrue(tab2Text.contains("SkyBoT"));
                assertTrue(tab2Text.contains("Gaia"));
                assertTrue(tab2Text.contains("SIMBAD"));
                assertTrue(tab2Text.contains("VSX"));
                assertTrue(tab2Text.contains("SatChecker"));
                assertTrue(tab2Text.contains("Astrometry.net"));

                // Verify Tab 3 (Software & Utilities)
                String tab3Text = extractEditorText(tabs.getComponentAt(2));
                assertTrue(tab3Text.contains("ASTAP"));
                assertTrue(tab3Text.contains("Siril"));
                assertTrue(tab3Text.contains("Stellarium"));
                assertTrue(tab3Text.contains("FlatLaf"));
                assertTrue(tab3Text.contains("JTransient"));
                assertTrue(tab3Text.contains("JPlateSolve"));
                assertTrue(tab3Text.contains("Claude"));
                assertTrue(tab3Text.contains("Codex"));
                assertTrue(tab3Text.contains("Gemini"));
                assertTrue(tab3Text.contains("Grok"));

                // Verify Tab 4 (Scientific References & Algorithms)
                String tab4Text = extractEditorText(tabs.getComponentAt(3));
                assertTrue(tab4Text.contains("Honeycutt"));
                assertTrue(tab4Text.contains("Stetson"));
                assertTrue(tab4Text.contains("Howell"));
                // Service citations stay with their provider: Astrometry.net's paper is in the services tab.
                // Swing's HTML writer wraps long lines, so compare with the whitespace collapsed.
                assertFalse(tab4Text.replaceAll("\\s+", " ").contains("Lang et al."));
                assertTrue(extractEditorText(tabs.getComponentAt(1)).replaceAll("\\s+", " ").contains("Lang et al. (2010)"));
                assertTrue(tab4Text.contains("Shupe"));
                assertTrue(tab4Text.contains("Calabretta"));
                assertTrue(tab4Text.contains("Greisen"));

                // Verify logo resources exist and load
                for (String logo : new String[]{"nasa.png", "esa.png", "cds.png", "astrometry.png", "astap.png", "siril.png", "stellarium.png"}) {
                    assertNotNull("Logo resource must exist: " + logo,
                            AcknowledgementsDialog.class.getResource("/eu/startales/spacepixels/gui/logos/" + logo));
                }

                // Verify Close button
                JButton close = findButton(dialog.getContentPane(), "Close");
                assertNotNull("Dialog must contain a Close button", close);
                assertSame(close, dialog.getRootPane().getDefaultButton());
            });
        } finally {
            if (ref.get() != null) {
                SwingUtilities.invokeAndWait(ref.get()::dispose);
            }
        }
    }

    /** Finds the tab container in the dialog hierarchy on the EDT. */
    private static JTabbedPane findTabbedPane(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof JTabbedPane pane) {
                return pane;
            }
            if (c instanceof Container child) {
                JTabbedPane found = findTabbedPane(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Returns the HTML rendered by one credits tab on the EDT. */
    private static String extractEditorText(Component tabRoot) {
        if (tabRoot instanceof JScrollPane scroll && scroll.getViewport().getView() instanceof JEditorPane editor) {
            return editor.getText();
        }
        if (tabRoot instanceof JEditorPane editor) {
            return editor.getText();
        }
        return "";
    }

    /** Finds a labelled button in the dialog hierarchy on the EDT. */
    private static JButton findButton(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && text.equals(b.getText())) {
                return b;
            }
            if (c instanceof Container child) {
                JButton found = findButton(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
