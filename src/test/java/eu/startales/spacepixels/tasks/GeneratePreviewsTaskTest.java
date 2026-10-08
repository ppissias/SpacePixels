package eu.startales.spacepixels.tasks;

import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class GeneratePreviewsTaskTest {

    @Test
    public void largeFramesAreReducedToTheMaximumSide() {
        short[][] frame = new short[3000][5000];
        frame[0][3] = 1234;
        short[][] reduced = (short[][]) GeneratePreviewsTask.reduce(frame, 2400);
        // Every third pixel: 5000 -> 1667, 3000 -> 1000.
        assertArrayEquals(new int[]{1667, 1000}, GeneratePreviewsTask.size(reduced));
        assertEquals(1234, reduced[0][1]);
    }

    @Test
    public void smallFramesAndUnsupportedTypesAreLeftAlone() {
        short[][] small = new short[100][200];
        assertSame(small, GeneratePreviewsTask.reduce(small, 2400));
        assertNull(GeneratePreviewsTask.reduce(new float[10][10], 2400));
    }

    @Test
    public void colourFramesKeepTheirChannels() {
        short[][][] colour = new short[3][2000][6000];
        short[][][] reduced = (short[][][]) GeneratePreviewsTask.reduce(colour, 2400);
        assertEquals(3, reduced.length);
        assertArrayEquals(new int[]{2000, 667}, GeneratePreviewsTask.size(reduced));
        BufferedImage image = GeneratePreviewsTask.linearImage(reduced);
        assertEquals(2000, image.getWidth());
    }
}
