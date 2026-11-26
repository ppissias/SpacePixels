package eu.startales.spacepixels.mpc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Tests loading and signed-asinh rendering of saved faint-mover review assets (shift stack + residual cube). */
public class FaintMoverReviewAssetsTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void loadsShiftStackAndDecodesAndRendersResiduals() throws Exception {
        File dir = folder.newFolder("report");
        File fmDir = new File(dir, "faint_movers");
        assertTrue(fmDir.mkdirs());

        // Write a small 11x11 shift stack PNG.
        BufferedImage stack = new BufferedImage(11, 11, BufferedImage.TYPE_BYTE_GRAY);
        stack.getRaster().setSample(5, 5, 0, 240);
        ImageIO.write(stack, "png", new File(fmDir, "fm1_stack.png"));

        // Write a 2-frame 5x5 residual cube in fm1_view.js format.
        int w = 5, h = 5, count = 2;
        ByteBuffer buf = ByteBuffer.allocate(w * h * count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < count; f++) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // Bright positive residual at (1, 1) in frame 1, and at (3, 3) in frame 2; negative corner at (0, 0).
                    float val = 0f;
                    if (f == 0 && x == 1 && y == 1) val = 3.0f;
                    else if (f == 1 && x == 3 && y == 3) val = 3.0f;
                    else if (x == 0 && y == 0) val = -3.0f;
                    buf.putFloat(val);
                }
            }
        }
        String residualB64 = Base64.getEncoder().encodeToString(buf.array());
        String js = "globalThis.SpacePixelsFaintMovers.accept(\"FM1\","
                + "{\"version\":1,\"width\":5,\"height\":5,\"followsPath\":false,"
                + "\"frames\":[{\"number\":1,\"x\":1.0,\"y\":1.0,\"unmasked\":true},"
                + "{\"number\":4,\"x\":3.0,\"y\":3.0,\"unmasked\":false}],"
                + "\"residual\":\"" + residualB64 + "\"});";
        Files.writeString(new File(fmDir, "fm1_view.js").toPath(), js, StandardCharsets.UTF_8);

        FaintMoverReviewAssets assets = FaintMoverReviewAssets.load(dir, "FM1", null);
        assertNotNull(assets);
        assertTrue(assets.hasAny());
        assertNotNull(assets.shiftStack());
        assertEquals(11, assets.shiftStack().getWidth());

        var cube = assets.residuals();
        assertNotNull(cube);
        assertEquals(5, cube.width());
        assertEquals(5, cube.height());
        var f1 = cube.frameForNumber(1);
        var f4 = cube.frameForNumber(4);
        assertNotNull(f1);
        assertNotNull(f4);
        assertTrue(f1.unmasked());
        assertFalse(f4.unmasked());
        assertNull(cube.frameForNumber(2));

        // Star-centred (objectCentred = false): peak stays at (1, 1), zero stays near mid-grey (128), negative near 0.
        BufferedImage starCentred = cube.render(f1, false, 3.0);
        assertEquals(255, starCentred.getRaster().getSample(1, 1, 0));
        assertEquals(0, starCentred.getRaster().getSample(0, 0, 0));
        assertEquals(128, starCentred.getRaster().getSample(2, 2, 0));

        // Object-centred (objectCentred = true): peak at (1, 1) shifts to image centre (2, 2).
        BufferedImage objectCentred = cube.render(f1, true, 3.0);
        assertEquals(255, objectCentred.getRaster().getSample(2, 2, 0));
    }

    @Test
    public void returnsNullWhenNoAssetsExist() throws Exception {
        File dir = folder.newFolder("empty-report");
        assertNull(FaintMoverReviewAssets.load(dir, "FM1", null));
    }
}
