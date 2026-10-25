package eu.startales.spacepixels.skycatalog;

import nom.tam.fits.Fits;
import nom.tam.fits.NullDataHDU;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;

/** The plate-correction fit reads the image HDU, also when the primary HDU is empty. */
public class PlateCorrectionFitterImageHduTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void readsTheImageFromAnExtensionAfterAnEmptyPrimaryHdu() throws Exception {
        short[][] image = new short[3][5];
        image[1][2] = 1234;
        File file = new File(folder.getRoot(), "extension.fit");
        try (Fits fits = new Fits()) {
            fits.addHDU(new NullDataHDU());
            fits.addHDU(Fits.makeHDU(image));
            fits.write(file);
        }
        assertArrayEquals(image, PlateCorrectionFitter.readMono(file));
    }
}
