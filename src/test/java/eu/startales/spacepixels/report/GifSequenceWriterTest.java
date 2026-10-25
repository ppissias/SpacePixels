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

    @Test public void streamsFramesInOrderAndSavesTheFirstFrameAsItsPoster() throws Exception {
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
    }
}
