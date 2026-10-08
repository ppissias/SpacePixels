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
    public void laptopScreenUsesMostOfIt() {
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1366, 728));
        assertEquals(1161, bounds.width);
        assertEquals(619, bounds.height);
    }

    @Test
    public void smallScreenGetsAtLeastTheMinimumButNeverMoreThanTheScreen() {
        Rectangle bounds = WindowPlacement.defaultBounds(new Rectangle(0, 0, 1024, 600));
        assertEquals(WindowPlacement.MIN_WIDTH, bounds.width);
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
