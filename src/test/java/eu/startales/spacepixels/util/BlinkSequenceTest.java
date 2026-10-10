package eu.startales.spacepixels.util;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BlinkSequenceTest {

    @Test
    public void halfSizeCopyAveragesEachTwoByTwoBlockAndKeepsAnOddEdge() {
        // 3 x 3 frame: the last row and column have no partner and are averaged with themselves.
        short[] values = {
                0, 4, 10,
                8, 12, 20,
                -6, -2, 7};
        BlinkSequence.Level half = BlinkSequence.half(new BlinkSequence.Level(3, 3, new short[][]{values}));

        assertEquals(2, half.width);
        assertEquals(2, half.height);
        assertEquals(6, half.channels[0][0]);   // (0 + 4 + 8 + 12) / 4
        assertEquals(15, half.channels[0][1]);  // (10 + 10 + 20 + 20) / 4
        assertEquals(-4, half.channels[0][2]);  // (-6 - 2 - 6 - 2) / 4
        assertEquals(7, half.channels[0][3]);
    }

    @Test
    public void reducedCopiesStopAtTheSmallestSide() {
        int width = 2100;
        int height = 1300;
        BlinkSequence.Frame frame = BlinkSequence.fromPlanes(
                new FitsFileInformation("a.fit", "a.fit", true, width, height), new short[][][]{new short[height][width]});

        // 2100 -> 1050 -> 525 -> 263: the last copy is the first at or below 512.
        assertEquals(4, frame.getLevelCount());
        assertEquals(263, frame.getLevel(3).width);
        assertEquals(163, frame.getLevel(3).height);
        assertEquals(width * height, frame.getHistogram(0)[-Short.MIN_VALUE]);
        assertEquals(32768, frame.valueAt(5, 5));
    }

    @Test
    public void memoryEstimateCoversTheDataTheReducedCopiesAndOneFrameBeingRead() {
        FitsFileInformation mono = new FitsFileInformation("a.fit", "a.fit", true, 1000, 1000);
        long expected = 1000L * 1000 * 2 * 4 / 3 + DisplayStretch.VALUES * 4L + 1000L * 1000 * 8;
        assertEquals(expected, BlinkSequence.estimateBytes(new FitsFileInformation[]{mono}));
    }
}
