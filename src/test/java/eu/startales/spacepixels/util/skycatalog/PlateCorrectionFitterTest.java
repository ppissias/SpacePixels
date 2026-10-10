package eu.startales.spacepixels.util.skycatalog;

import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic frames: Gaia-like stars, "detected" where a known distortion and a per-frame shift put them, with
 * centroid noise. The fit must find the distortion and bring the stars back within the noise.
 */
public class PlateCorrectionFitterTest {

    private static final int WIDTH = 3000;
    private static final int HEIGHT = 2000;

    @Test
    public void recoversADistortionAndPerFrameShifts() throws IOException {
        WcsCoordinateTransformer header = WcsCoordinateTransformer.fromHeader(header());
        Random random = new Random(7);
        List<SkyCatalogue.Star> stars = stars(header, random, 2500);
        List<PlateCorrectionFitter.FramePairs> frames = new ArrayList<>();
        double[][] shifts = {{0.3, -0.2}, {-0.4, 0.1}, {0.0, 0.5}, {0.2, 0.2}};
        for (int f = 0; f < shifts.length; f++) {
            List<SourceExtractor.DetectedObject> detected = detect(header, stars, shifts[f], random);
            PlateCorrectionFitter.FramePairs pairs = PlateCorrectionFitter.FramePairs.match("frame" + f, stars, header,
                    detected, WIDTH, HEIGHT, 0);
            assertTrue("frame " + f + " matched " + pairs.size(), pairs.size() > 1500);
            frames.add(pairs);
        }
        PlateSolutionCorrection correction = PlateCorrectionFitter.fitPairs(frames, header.solutionKey(), WIDTH, HEIGHT, new ArrayList<>());

        // The distortion reaches several pixels in the corners (median over 1 px); the noise is 0.1 px per axis.
        assertTrue("before " + correction.beforeMedianPx, correction.beforeMedianPx > 1.0);
        assertTrue("after " + correction.afterMedianPx, correction.afterMedianPx < 0.2);
        assertEquals(3, correction.global.order);
        assertEquals(4, correction.frames.size());
        assertEquals(4, correction.framesFitted);

        // The corrected solution puts a star of frame 2 where it was detected.
        correction.enabled = true;
        WcsCoordinateTransformer corrected = correction.apply(header, "frame2", WIDTH, HEIGHT);
        SkyCatalogue.Star star = stars.get(100);
        double[] truth = truePosition(header, star, shifts[2]);
        double[] predicted = corrected.skyToPixel(star.ra, star.dec);
        assertEquals(truth[0], predicted[0], 0.1);
        assertEquals(truth[1], predicted[1], 0.1);
        // And the cursor gets the star's sky position back from that pixel.
        WcsCoordinateTransformer.SkyCoordinate sky = corrected.pixelToSky(truth[0], truth[1]);
        assertEquals(star.ra, sky.getRaDegrees(), 0.1 * 1.5 / 3600);
        assertEquals(star.dec, sky.getDecDegrees(), 0.1 * 1.5 / 3600);
    }

    @Test
    public void aSmallLinearErrorNeedsOnlyALinearCorrection() throws IOException {
        WcsCoordinateTransformer header = WcsCoordinateTransformer.fromHeader(header());
        Random random = new Random(11);
        List<SkyCatalogue.Star> stars = stars(header, random, 1500);
        List<PlateCorrectionFitter.FramePairs> frames = new ArrayList<>();
        for (int f = 0; f < 3; f++) {
            List<SourceExtractor.DetectedObject> detected = new ArrayList<>();
            for (SkyCatalogue.Star star : stars) {
                double[] p = header.skyToPixel(star.ra, star.dec);
                // 0.1 % scale error and a 2 px shift.
                double x = WIDTH / 2.0 + (p[0] - WIDTH / 2.0) * 1.001 + 2 + random.nextGaussian() * 0.1;
                double y = HEIGHT / 2.0 + (p[1] - HEIGHT / 2.0) * 1.001 - 1 + random.nextGaussian() * 0.1;
                detected.add(new SourceExtractor.DetectedObject(x, y, Math.pow(10, -0.4 * star.g) * 1e8, 20));
            }
            frames.add(PlateCorrectionFitter.FramePairs.match("f" + f, stars, header, detected, WIDTH, HEIGHT, 0));
        }
        PlateSolutionCorrection correction = PlateCorrectionFitter.fitPairs(frames, header.solutionKey(), WIDTH, HEIGHT, new ArrayList<>());
        assertEquals(1, correction.global.order);
        assertTrue(correction.afterMedianPx < 0.2);
    }

    private static List<SkyCatalogue.Star> stars(WcsCoordinateTransformer header, Random random, int count) {
        List<SkyCatalogue.Star> stars = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            WcsCoordinateTransformer.SkyCoordinate sky = header.pixelToSky(random.nextDouble() * WIDTH, random.nextDouble() * HEIGHT);
            SkyCatalogue.Star star = new SkyCatalogue.Star();
            star.ra = sky.getRaDegrees();
            star.dec = sky.getDecDegrees();
            star.g = 9 + random.nextDouble() * 6;
            stars.add(star);
        }
        return stars;
    }

    /** Where the star really is: the header position, a 3rd-order distortion and the frame's shift. */
    private static double[] truePosition(WcsCoordinateTransformer header, SkyCatalogue.Star star, double[] shift) {
        double[] p = header.skyToPixel(star.ra, star.dec);
        double u = (p[0] - WIDTH / 2.0) / (WIDTH / 2.0);
        double v = (p[1] - HEIGHT / 2.0) / (WIDTH / 2.0);
        double r2 = u * u + v * v;
        return new double[]{p[0] + 3.0 * u * r2 + 0.8 + shift[0], p[1] + 3.0 * v * r2 - 0.5 + shift[1]};
    }

    private static List<SourceExtractor.DetectedObject> detect(WcsCoordinateTransformer header, List<SkyCatalogue.Star> stars,
                                                                double[] shift, Random random) {
        List<SourceExtractor.DetectedObject> detected = new ArrayList<>();
        for (SkyCatalogue.Star star : stars) {
            double[] p = truePosition(header, star, shift);
            detected.add(new SourceExtractor.DetectedObject(p[0] + random.nextGaussian() * 0.1, p[1] + random.nextGaussian() * 0.1,
                    Math.pow(10, -0.4 * star.g) * 1e8, 20));
        }
        return detected;
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "1500.5");
        header.put("CRPIX2", "1000.5");
        header.put("CRVAL1", "150.0");
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-1.5 / 3600));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(1.5 / 3600));
        return header;
    }
}
