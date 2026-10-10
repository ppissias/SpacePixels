package eu.startales.spacepixels.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PlateSolutionCorrectionTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void appliesTheSessionAndFrameCorrectionsBothWays() {
        WcsCoordinateTransformer header = WcsCoordinateTransformer.fromHeader(header());
        PlateSolutionCorrection correction = correction(header);
        WcsCoordinateTransformer corrected = correction.apply(header, "a.fit", 2000, 1600);
        assertTrue(corrected.isCorrected());

        double[] plain = header.skyToPixel(150.1, 20.05);
        double[] moved = corrected.skyToPixel(150.1, 20.05);
        // Session: +1 px in x, a 2 px scale term in y; frame a.fit: a further -0.5 px in y.
        double v = (plain[1] - 800) / 1000;
        assertEquals(plain[0] + 1.0, moved[0], 1e-9);
        assertEquals(plain[1] + 2 * v - 0.5, moved[1], 1e-6);
        WcsCoordinateTransformer.SkyCoordinate back = corrected.pixelToSky(moved[0], moved[1]);
        assertEquals(150.1, back.getRaDegrees(), 1e-9);
        assertEquals(20.05, back.getDecDegrees(), 1e-9);

        // Another frame has only the session correction; the session as a whole too.
        assertEquals(plain[1] + 2 * v, correction.apply(header, "b.fit", 2000, 1600).skyToPixel(150.1, 20.05)[1], 1e-6);
        assertEquals(plain[1] + 2 * v, correction.apply(header, null, 2000, 1600).skyToPixel(150.1, 20.05)[1], 1e-6);
    }

    @Test
    public void leavesOtherSolutionsAndFrameSizesAlone() {
        WcsCoordinateTransformer header = WcsCoordinateTransformer.fromHeader(header());
        PlateSolutionCorrection correction = correction(header);
        assertSame(header, correction.apply(header, "a.fit", 2001, 1600));
        Map<String, String> other = header();
        other.put("CRVAL1", "150.01");
        WcsCoordinateTransformer otherSolution = WcsCoordinateTransformer.fromHeader(other);
        assertSame(otherSolution, correction.apply(otherSolution, "a.fit", 2000, 1600));
    }

    @Test
    public void theResolverUsesTheCorrectionOnlyWhileItIsSwitchedOn() throws Exception {
        File session = folder.newFolder("session");
        FitsFileInformation a = frame(session, "a.fit");
        FitsFileInformation b = frame(session, "b.fit");
        FitsFileInformation[] files = {a, b};
        WcsCoordinateTransformer header = WcsCoordinateTransformer.fromHeader(header());
        PlateSolutionCorrection correction = correction(header);

        PlateSolutionCorrection.save(session, correction);
        PlateSolutionCorrection.forget(session);
        assertFalse(WcsSolutionResolver.resolve(a, files).getTransformer().isCorrected());

        correction.enabled = true;
        PlateSolutionCorrection.save(session, correction);
        PlateSolutionCorrection.forget(session);
        WcsSolutionResolver.ResolvedWcsSolution resolved = WcsSolutionResolver.resolve(a, files);
        assertTrue(resolved.getTransformer().isCorrected());
        assertTrue(resolved.getSourceType().contains("corrected"));
        assertTrue(WcsSolutionResolver.resolve(null, files).getTransformer().isCorrected());
        assertFalse(WcsSolutionResolver.resolveUncorrected(a, files).getTransformer().isCorrected());
        // a.fit has its own part, b.fit not.
        double ya = WcsSolutionResolver.resolve(a, files).getTransformer().skyToPixel(150.1, 20.05)[1];
        double yb = WcsSolutionResolver.resolve(b, files).getTransformer().skyToPixel(150.1, 20.05)[1];
        assertEquals(-0.5, ya - yb, 1e-6);
    }

    @Test
    public void savesAndLoadsAndIgnoresDamagedFiles() throws Exception {
        File session = folder.newFolder("session");
        assertNull(PlateSolutionCorrection.load(session));
        PlateSolutionCorrection correction = correction(WcsCoordinateTransformer.fromHeader(header()));
        correction.beforeMedianPx = 4.86;
        correction.afterMedianPx = 0.11;
        PlateSolutionCorrection.save(session, correction);
        PlateSolutionCorrection loaded = PlateSolutionCorrection.load(session);
        assertNotNull(loaded);
        assertEquals(correction.solutionKey, loaded.solutionKey);
        assertEquals(1, loaded.frames.size());
        assertEquals("4.86 → 0.11 px", loaded.improvementText());
        java.nio.file.Files.write(PlateSolutionCorrection.fileIn(session).toPath(), "{\"global\":".getBytes());
        assertNull(PlateSolutionCorrection.load(session));
    }

    private static FitsFileInformation frame(File session, String name) {
        FitsFileInformation info = new FitsFileInformation(new File(session, name).getPath(), name, true, 2000, 1600);
        info.getFitsHeader().putAll(header());
        return info;
    }

    /** Session: +1 px in x, 2 v px in y (v = (y - 800) / 1000); frame a.fit: -0.5 px in y. */
    private static PlateSolutionCorrection correction(WcsCoordinateTransformer header) {
        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.solutionKey = header.solutionKey();
        correction.width = 2000;
        correction.height = 1600;
        correction.global = polynomial(1, new double[]{1, 0, 0}, new double[]{0, 0, 2});
        correction.frames.put("a.fit", polynomial(0, new double[]{0}, new double[]{-0.5}));
        return correction;
    }

    private static PlateSolutionCorrection.Polynomial polynomial(int order, double[] ax, double[] ay) {
        PlateSolutionCorrection.Polynomial polynomial = new PlateSolutionCorrection.Polynomial();
        polynomial.order = order;
        polynomial.cx = 1000;
        polynomial.cy = 800;
        polynomial.scale = 1000;
        polynomial.ax = ax;
        polynomial.ay = ay;
        return polynomial;
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "1000.5");
        header.put("CRPIX2", "800.5");
        header.put("CRVAL1", "150.0");
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-1.5 / 3600));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(1.5 / 3600));
        return header;
    }
}
