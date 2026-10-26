package eu.startales.spacepixels.report;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import static org.junit.Assert.*;

public class GifSequenceWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void streamsFramesInOrderAndSavesTheFirstFrameAsItsPosterAndSheet() throws Exception {
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
        GifSequenceWriter.saveAnimatedGif(frames, output, 300);
        try (var input = ImageIO.createImageInputStream(output)) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                assertEquals(3, reader.getNumImages(true));
                for (int i = 0; i < colors.length; i++) assertEquals(colors[i].getRGB(), reader.read(i).getRGB(4, 4));
            } finally { reader.dispose(); }
        }
        assertEquals(Color.RED.getRGB(), ImageIO.read(new File(output + ".poster.png")).getRGB(4, 4));

        File sheetFile = new File(output + ".sheet.png");
        assertTrue("Sheet PNG must exist", sheetFile.isFile());
        BufferedImage sheet = ImageIO.read(sheetFile);
        // 3 frames: columns = ceil(sqrt(3)) = 2, rows = ceil(3/2) = 2 -> 16x16
        assertEquals(16, sheet.getWidth());
        assertEquals(16, sheet.getHeight());
        assertEquals(Color.RED.getRGB(), sheet.getRGB(4, 4));
        assertEquals(Color.GREEN.getRGB(), sheet.getRGB(12, 4));
        assertEquals(Color.BLUE.getRGB(), sheet.getRGB(4, 12));

        var metaMap = AnimationRegistry.getAnimations(temporary.getRoot());
        assertTrue(metaMap.containsKey("stream.gif"));
        AnimationMetadata meta = metaMap.get("stream.gif");
        assertEquals("stream.gif", meta.fileName());
        assertEquals(3, meta.frameCount());
        assertEquals(2, meta.columns());
        assertEquals(2, meta.rows());
        assertEquals(8, meta.frameWidth());
        assertEquals(8, meta.frameHeight());
        assertEquals(300, meta.delayMs());
    }
}
