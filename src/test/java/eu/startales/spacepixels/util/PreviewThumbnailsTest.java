package eu.startales.spacepixels.util;

import org.junit.Test;

import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/** Live-view thumbnails are small, keep the image's proportions and never change the pipeline's pixels. */
public class PreviewThumbnailsTest {

    private static short[][] frame(int width, int height) {
        short[][] pixels = new short[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels[y][x] = (short) (1000 + (x * 7 + y * 3) % 200 - 32768);
            }
        }
        // A bright star, as the report would stretch it.
        for (int y = height / 2 - 2; y <= height / 2 + 2; y++) {
            for (int x = width / 2 - 2; x <= width / 2 + 2; x++) {
                pixels[y][x] = (short) (30000 - 32768);
            }
        }
        return pixels;
    }

    @Test
    public void aLargeFrameBecomesAThumbnailWithTheSameProportions() {
        short[][] pixels = frame(3000, 2000);
        BufferedImage thumbnail = PreviewThumbnails.frame(pixels, 0.5, 5.0);
        assertTrue(thumbnail.getWidth() <= PreviewThumbnails.LONG_SIDE);
        assertEquals(3000.0 / 2000.0, thumbnail.getWidth() / (double) thumbnail.getHeight(), 0.02);
        int centre = thumbnail.getRaster().getSample(thumbnail.getWidth() / 2, thumbnail.getHeight() / 2, 0);
        int corner = thumbnail.getRaster().getSample(2, 2, 0);
        assertTrue("the star stands out from the background", centre > corner);
    }

    @Test
    public void theThumbnailLeavesThePipelinePixelsUnchanged() {
        short[][] pixels = frame(900, 600);
        short[][] before = new short[pixels.length][];
        for (int y = 0; y < pixels.length; y++) {
            before[y] = pixels[y].clone();
        }
        PreviewThumbnails.frame(pixels, 0.5, 5.0);
        assertTrue(java.util.Arrays.deepEquals(before, pixels));
    }

    @Test
    public void aSmallFrameIsNotEnlarged() {
        BufferedImage thumbnail = PreviewThumbnails.frame(frame(120, 80), 0.5, 5.0);
        assertEquals(120, thumbnail.getWidth());
        assertEquals(80, thumbnail.getHeight());
    }

    @Test
    public void aReportPictureIsFittedIntoADetachedCopy() {
        BufferedImage large = new BufferedImage(1800, 900, BufferedImage.TYPE_INT_RGB);
        BufferedImage fitted = PreviewThumbnails.fit(large);
        assertEquals(PreviewThumbnails.LONG_SIDE, fitted.getWidth());
        assertEquals(PreviewThumbnails.LONG_SIDE / 2, fitted.getHeight());
        BufferedImage small = new BufferedImage(60, 40, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage copy = PreviewThumbnails.fit(small);
        assertNotSame("a later change to the report picture does not reach the live view", small, copy);
        assertEquals(60, copy.getWidth());
    }

    @Test
    public void theCaptionNamesTheFrameAndItsTimeWhenKnown() {
        assertEquals("Frame 3 of 33 · 2020-06-06 19:03 UTC",
                PreviewThumbnails.frameCaption(3, 33, java.time.Instant.parse("2020-06-06T19:03:26Z").toEpochMilli()));
        assertEquals("Frame 1 of 5", PreviewThumbnails.frameCaption(1, 5, -1));
        assertEquals("Frame 1 of 5", PreviewThumbnails.frameCaption(1, 5, 0));
    }
}
