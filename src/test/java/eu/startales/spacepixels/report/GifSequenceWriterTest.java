package eu.startales.spacepixels.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import static org.junit.Assert.*;

public class GifSequenceWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void streamsFramesInOrderAndSavesTheFirstFrameAsItsPosterAndSheet() throws Exception {
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
        Iterator<BufferedImage> frames = new Iterator<>() {
            int next;
            public boolean hasNext() { return next < colors.length; }
            public BufferedImage next() {
                BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics();
                try { graphics.setColor(colors[next++]); graphics.fillRect(0, 0, 8, 8); }
                finally { graphics.dispose(); }
                return image;
            }
        };
        File output = new File(temporary.getRoot(), "stream.gif");
        AnimationRegistry registry = new AnimationRegistry();
        AnimationMetadata returnedMeta = GifSequenceWriter.saveAnimatedGif(frames, output, 300, registry);

        assertNotNull(returnedMeta);
        assertEquals("stream.gif", returnedMeta.fileName());
        assertEquals(3, returnedMeta.frameCount());
        assertEquals(2, returnedMeta.columns());
        assertEquals(2, returnedMeta.rows());
        assertEquals(8, returnedMeta.frameWidth());
        assertEquals(8, returnedMeta.frameHeight());
        assertEquals(300, returnedMeta.delayMs());

        try (var input = ImageIO.createImageInputStream(output)) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                assertEquals(3, reader.getNumImages(true));
                for (int i = 0; i < colors.length; i++) {
                    assertEquals(colors[i].getRGB(), reader.read(i).getRGB(4, 4));
                }
            } finally { reader.dispose(); }
        }
        assertEquals(Color.RED.getRGB(), ImageIO.read(new File(output + ".poster.png")).getRGB(4, 4));

        File sheetFile = new File(output + ".sheet.png");
        assertTrue("Sheet PNG must exist", sheetFile.isFile());
        BufferedImage sheet = ImageIO.read(sheetFile);
        assertEquals(16, sheet.getWidth());
        assertEquals(16, sheet.getHeight());
        assertEquals(Color.RED.getRGB(), sheet.getRGB(4, 4));
        assertEquals(Color.GREEN.getRGB(), sheet.getRGB(12, 4));
        assertEquals(Color.BLUE.getRGB(), sheet.getRGB(4, 12));

        assertTrue(registry.contains("stream.gif"));
        assertEquals(returnedMeta, registry.getAnimations().get("stream.gif"));
    }

    @Test
    public void bufferReuseIteratorProducesDistinctFramesInGifPosterAndSheet() throws Exception {
        Color[] colors = {Color.RED, Color.GREEN, Color.BLUE};
        BufferedImage reusable = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Iterator<BufferedImage> frames = new Iterator<>() {
            int next;
            public boolean hasNext() { return next < colors.length; }
            public BufferedImage next() {
                Graphics2D g = reusable.createGraphics();
                try {
                    g.setColor(colors[next++]);
                    g.fillRect(0, 0, 8, 8);
                } finally {
                    g.dispose();
                }
                return reusable;
            }
        };

        File output = new File(temporary.getRoot(), "reused_buffer.gif");
        AnimationMetadata meta = GifSequenceWriter.saveAnimatedGif(frames, output, 200);
        assertNotNull(meta);

        // Poster must be RED, not BLUE!
        File posterFile = new File(output + ".poster.png");
        assertTrue(posterFile.isFile());
        assertEquals(Color.RED.getRGB(), ImageIO.read(posterFile).getRGB(4, 4));

        // GIF frames must be RED, GREEN, BLUE, not BLUE, BLUE, BLUE!
        try (var input = ImageIO.createImageInputStream(output)) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                assertEquals(3, reader.getNumImages(true));
                assertEquals("Frame 0 must be RED", Color.RED.getRGB(), reader.read(0).getRGB(4, 4));
                assertEquals("Frame 1 must be GREEN", Color.GREEN.getRGB(), reader.read(1).getRGB(4, 4));
                assertEquals("Frame 2 must be BLUE", Color.BLUE.getRGB(), reader.read(2).getRGB(4, 4));
            } finally { reader.dispose(); }
        }

        // Sheet must have RED, GREEN, BLUE cells!
        File sheetFile = new File(output + ".sheet.png");
        assertTrue(sheetFile.isFile());
        BufferedImage sheet = ImageIO.read(sheetFile);
        assertEquals("Sheet cell 0 must be RED", Color.RED.getRGB(), sheet.getRGB(4, 4));
        assertEquals("Sheet cell 1 must be GREEN", Color.GREEN.getRGB(), sheet.getRGB(12, 4));
        assertEquals("Sheet cell 2 must be BLUE", Color.BLUE.getRGB(), sheet.getRGB(4, 12));
    }

    @Test
    public void lazyLargeSequenceStreamsIncrementallyAndBoundsSheetDimensions() throws Exception {
        int totalFrames = 120;
        int frameDim = 512;
        BufferedImage buffer = new BufferedImage(frameDim, frameDim, BufferedImage.TYPE_INT_RGB);

        Iterator<BufferedImage> lazyFrames = new Iterator<>() {
            int count = 0;
            public boolean hasNext() { return count < totalFrames; }
            public BufferedImage next() {
                Graphics2D g = buffer.createGraphics();
                try {
                    g.setColor(new Color((count * 2) % 256, (count * 3) % 256, (count * 5) % 256));
                    g.fillRect(0, 0, frameDim, frameDim);
                } finally {
                    g.dispose();
                }
                count++;
                return buffer;
            }
        };

        File output = new File(temporary.getRoot(), "large_sequence.gif");
        AnimationMetadata meta = GifSequenceWriter.saveAnimatedGif(lazyFrames, output, 100);

        assertNotNull(meta);
        assertEquals(120, meta.frameCount());
        assertTrue("Output GIF must exist", output.isFile());
        assertTrue("Poster must exist", new File(output + ".poster.png").isFile());

        File sheetFile = new File(output + ".sheet.png");
        assertTrue("Sheet must exist", sheetFile.isFile());
        BufferedImage sheet = ImageIO.read(sheetFile);
        assertTrue("Sheet width must not exceed MAX_SHEET_DIMENSION (2048)", sheet.getWidth() <= GifSequenceWriter.MAX_SHEET_DIMENSION);
        assertTrue("Sheet height must not exceed MAX_SHEET_DIMENSION (2048)", sheet.getHeight() <= GifSequenceWriter.MAX_SHEET_DIMENSION);
    }
}
