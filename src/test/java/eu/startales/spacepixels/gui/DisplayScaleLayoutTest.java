package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.util.UIScale;
import org.junit.Test;

import javax.swing.JSlider;
import java.awt.EventQueue;
import java.awt.Font;
import javax.swing.UIManager;

import static org.junit.Assert.assertTrue;

/** Checks the notes and viewer sliders that previously used fixed pixel sizes at larger type and display scale. */
public class DisplayScaleLayoutTest {
    private static final String TABLE_NOTE = "One run measures every setting. Each profile then picks the most "
            + "sensitive one within its budget of noise detections per MPix per frame (the ≤ value).";

    /** A larger body font makes the Overview note taller instead of clipping it to one line. */
    @Test public void overviewNoteGrowsWithTheBodyFont() throws Exception {
        EventQueue.invokeAndWait(() -> {
            Font original = UIManager.getFont("Label.font");
            try {
                UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                int small = new WrappingHelpLabel(TABLE_NOTE, 480).getPreferredSize().height;
                UIManager.put("Label.font", new Font(Font.SANS_SERIF, Font.PLAIN, 26));
                WrappingHelpLabel largeNote = new WrappingHelpLabel(TABLE_NOTE, 480);
                int large = largeNote.getPreferredSize().height;
                assertTrue("26 px type needs more than one line", large > largeNote.getFont().getSize() + 8);
                assertTrue("Larger type needs a taller note", large > small);
            } finally {
                UIManager.put("Label.font", original);
            }
        });
    }

    /** Viewer slider widths use the current display scale instead of a raw pixel constant. */
    @Test public void viewerSliderWidthUsesTheDisplayScale() throws Exception {
        EventQueue.invokeAndWait(() -> {
            JSlider slider = new JSlider();
            int naturalHeight = slider.getPreferredSize().height;
            int scaled = UiStyle.space(150);
            slider.setPreferredSize(new java.awt.Dimension(scaled, naturalHeight));
            assertTrue(scaled == UIScale.scale(150));
            assertTrue(slider.getPreferredSize().width == scaled);
            assertTrue(slider.getPreferredSize().height == naturalHeight);
        });
    }
}
