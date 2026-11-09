package eu.startales.spacepixels.gui;

import org.junit.Test;

import java.awt.Rectangle;

import static org.junit.Assert.assertEquals;

public class WindowPlacementTest {

    @Test
    public void largeScreenIsCappedAndCentred() {
        // 1920 x 1080 with a 40 px taskbar.
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1920, 1040));
        assertEquals(new Rectangle(210, 78, 1500, 884), bounds);
    }

    @Test
    public void laptopScreenUsesAtLeastTheSingleRowWidth() {
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1366, 728));
        assertEquals(WindowPlacement.DEFAULT_MIN_WIDTH, bounds.width);
        assertEquals(619, bounds.height);

        Rectangle scaledLaptop = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1280, 760));
        assertEquals(WindowPlacement.DEFAULT_MIN_WIDTH, scaledLaptop.width);
        assertEquals(646, scaledLaptop.height);
    }

    @Test
    public void scaledWorkflowStripRaisesDefaultWidthWhenScreenAllows() {
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1920, 1040), 1600);
        assertEquals(1600, bounds.width);

        Rectangle clamped = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1440, 900), 1600);
        assertEquals(1440, clamped.width);
    }

    @Test
    public void smallScreenGetsAtLeastTheMinimumButNeverMoreThanTheScreen() {
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1024, 600));
        assertEquals(1024, bounds.width);
        assertEquals(560, bounds.height);

        Rectangle tiny = WindowPlacement.defaultBounds(new Rectangle(0, 0, 800, 500));
        assertEquals(new Rectangle(0, 0, 800, 500), tiny);
    }

    @Test
    public void secondaryScreenOffsetIsKept() {
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(1920, 0, 1920, 1040));
        assertEquals(1920 + 210, bounds.x);
    }
}
