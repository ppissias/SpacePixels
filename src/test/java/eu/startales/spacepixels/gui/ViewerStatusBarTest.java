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

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.util.List;

import static org.junit.Assert.*;

/** Checks the viewers' one-line status bar, its Shortcuts window and the cursor readout's order. */
public class ViewerStatusBarTest {

    /** The bar offers the Shortcuts window, which lists the viewer's keys with the shared ones. */
    @Test public void statusBarOffersTheShortcuts() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame frame = new JFrame();
            try {
                List<ViewerSupport.Shortcut> shortcuts = new java.util.ArrayList<>(List.of(new ViewerSupport.Shortcut("D", "Raw detections")));
                shortcuts.addAll(ViewerSupport.commonShortcuts());
                JComponent bar = ViewerSupport.statusBar(new JLabel("Frame 1 of 3"), new JLabel(" "), frame.getRootPane(),
                        "Preview Frame", shortcuts);
                assertTrue(hasButton(bar, "? Shortcuts"));
                assertNotNull(frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(KeyStroke.getKeyStroke("typed ?")));
                JDialog dialog = ViewerSupport.shortcutsDialog(frame, "Preview Frame", shortcuts);
                try {
                    assertEquals("Preview Frame · Shortcuts", dialog.getTitle());
                    assertTrue(hasLabel(dialog.getContentPane(), "Raw detections"));
                    assertTrue(hasLabel(dialog.getContentPane(), "Fit the whole frame"));
                } finally {
                    dialog.dispose();
                }
            } finally {
                frame.dispose();
            }
        });
    }

    /** The readout gives the pixel, then the sky position when solved, then the value, as the spec orders them. */
    @Test public void cursorReadoutGivesPixelThenValue() {
        FitsFileInformation info = new FitsFileInformation("a.fit", "a.fit", true, 4, 3);
        short[][] data = new short[3][4];
        data[1][2] = (short) (1204 - 32768);  // 16-bit FITS data are stored with an offset of 32768
        ViewerSupport.CursorReadout readout = new ViewerSupport.CursorReadout();
        readout.clear();
        assertEquals("x 2, y 1 · value 1204", readout.describe(BlinkSequence.fromMono(info, data), new Point(2, 1)));
        assertEquals(" ", readout.describe(BlinkSequence.fromMono(info, data), new Point(9, 1)));
    }

    private static boolean hasButton(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton button && text.equals(button.getText())) return true;
            if (child instanceof Container inner && hasButton(inner, text)) return true;
        }
        return false;
    }

    private static boolean hasLabel(Container container, String text) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) return true;
            if (child instanceof Container inner && hasLabel(inner, text)) return true;
        }
        return false;
    }
}
