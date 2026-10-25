package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.engine.ImageFrame;
import nom.tam.fits.Fits;
import nom.tam.fits.NullDataHDU;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

/** The viewers and tools read the image HDU like import and detection, also when the primary HDU is empty. */
public class SessionFrameLoaderTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsTheImageFromAnExtensionAfterAnEmptyPrimaryHdu() throws Exception {
        short[][] image = image();
        File file = new File(folder.getRoot(), "extension.fit");
        try (Fits fits = new Fits()) {
            fits.addHDU(new NullDataHDU());
            fits.addHDU(Fits.makeHDU(image));
            fits.write(file);
        }
        assertLoads(file, image);
    }

    @Test
    public void readsTheImageFromThePrimaryHdu() throws Exception {
        short[][] image = image();
        File file = new File(folder.getRoot(), "primary.fit");
        try (Fits fits = new Fits()) {
            fits.addHDU(Fits.makeHDU(image));
            fits.write(file);
        }
        assertLoads(file, image);
    }

    static short[][] image() {
        short[][] image = new short[4][6];
        for (int y = 0; y < image.length; y++) {
            for (int x = 0; x < image[y].length; x++) {
                image[y][x] = (short) (100 + 10 * y + x);
            }
        }
        return image;
    }

    private static void assertLoads(File file, short[][] expected) throws Exception {
        FitsFileInformation info = new FitsFileInformation(file.getAbsolutePath(), file.getName(), true, 6, 4);
        List<ImageFrame> frames = SessionFrameLoader.loadAll(new FitsFileInformation[]{info}, null, 100);
        assertEquals(1, frames.size());
        assertArrayEquals(expected, frames.get(0).pixelData);
    }
}
