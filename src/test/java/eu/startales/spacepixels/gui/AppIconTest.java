package eu.startales.spacepixels.gui;

import org.junit.Test;

import java.awt.Image;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AppIconTest {

    @Test
    public void loadsEverySizeSmallestFirst() {
        List<Image> images = AppIcon.images();

        int[] expected = {16, 20, 24, 32, 40, 48, 64, 128, 256, 512};
        assertEquals(expected.length, images.size());
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], images.get(i).getWidth(null));
            assertEquals(expected[i], images.get(i).getHeight(null));
        }
    }
}
