package eu.startales.spacepixels.tasks;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import eu.startales.spacepixels.util.FitsVisualizationRenderer;
import eu.startales.spacepixels.util.StretchAlgorithm;
import nom.tam.fits.Fits;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class GeneratePreviewsTaskTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void stretchOffShowsLinearMonoAndColourWithoutChangingTheFitsFile() throws Exception {
        short[][] mono = {{Short.MIN_VALUE, -32512, 0, Short.MAX_VALUE}};
        short[][][] colour = {{{Short.MAX_VALUE, Short.MIN_VALUE}}, {{0, Short.MAX_VALUE}}, {{Short.MIN_VALUE, 0}}};
        Object[] frames = {mono, colour};
        int[][] expected = {{0x000000, 0x010101, 0x808080, 0xffffff}, {0xff8000, 0x00ff80}};
        FitsVisualizationRenderer renderer = new FitsVisualizationRenderer();
        for (int i = 0; i < frames.length; i++) {
            File file = new File(temporary.getRoot(), "frame" + i + ".fit");
            try (Fits fits = new Fits()) {
                fits.addHDU(Fits.makeHDU(frames[i]));
                fits.write(file);
            }
            byte[] originalFile = Files.readAllBytes(file.toPath());
            BufferedImage[] previews = new GeneratePreviewsTask(renderer, file.toString(), 100, 20,
                    StretchAlgorithm.EXTREME, false).call();
            for (int x = 0; x < expected[i].length; x++) {
                assertEquals(expected[i][x], previews[0].getRGB(x, 0) & 0xffffff);
                assertEquals(expected[i][x], previews[1].getRGB(x, 0) & 0xffffff);
            }
            assertArrayEquals(originalFile, Files.readAllBytes(file.toPath()));
        }
    }

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
