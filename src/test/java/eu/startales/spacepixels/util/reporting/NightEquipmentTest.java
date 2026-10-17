package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.core.SourceExtractor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NightEquipmentTest {

    @Test public void narrowGaiaRangeCannotPublishUnvalidatedSkyBrightness() {
        var header = new java.util.LinkedHashMap<String, String>();
        header.put("CTYPE1", "RA---TAN"); header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "300.5"); header.put("CRPIX2", "200.5");
        header.put("CRVAL1", "150"); header.put("CRVAL2", "20");
        header.put("CD1_1", "-0.00027777778"); header.put("CD1_2", "0");
        header.put("CD2_1", "0"); header.put("CD2_2", "0.00027777778");
        var transformer = eu.startales.spacepixels.util.WcsCoordinateTransformer.fromHeader(header);
        var catalogue = new eu.startales.spacepixels.util.skycatalog.SkyCatalogue();
        List<SourceExtractor.DetectedObject> stars = masterStars();
        for (var star : stars) {
            var position = transformer.pixelToSky(star.x, star.y);
            var entry = new eu.startales.spacepixels.util.skycatalog.SkyCatalogue.Star();
            entry.ra = position.getRaDegrees(); entry.dec = position.getDecDegrees(); entry.g = 12 + 0.1 * (catalogue.stars.size() % 5);
            catalogue.stars.add(entry);
        }
        NightEquipment measured = NightEquipment.measure(frames(new Field() {
            public double sigma(double x, double y) { return 1.4; }
            public double[] tail(double x, double y) { return new double[]{0, 0}; }
            public double sky(double x, double y) { return 1000; }
        }, 3), List.of(0, 1, 2), null, stars, transformer, catalogue);
        assertTrue(measured.calibrationStars > 20);
        assertFalse(measured.calibrated);
        assertTrue(Double.isNaN(measured.linearity));
        assertTrue(measured.frames.stream().allMatch(f -> Double.isNaN(f.skyBrightness)));
        assertTrue(measured.notes.stream().anyMatch(note -> note.contains("validate linearity")));
    }

    private static final int WIDTH = 600;
    private static final int HEIGHT = 400;
    private static final int SPACING = 30;

    /** How a star at (x, y) looks: its sigma and the shift of a faint tail (pixels, x and y), and the sky there. */
    private interface Field {
        double sigma(double x, double y);

        double[] tail(double x, double y);

        double sky(double x, double y);
    }

    private static List<short[][]> frames(Field field, int count) {
        List<short[][]> frames = new ArrayList<>();
        for (int f = 0; f < count; f++) {
            Random random = new Random(100 + f);
            double[][] image = new double[HEIGHT][WIDTH];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    image[y][x] = field.sky(x, y) + 4 * random.nextGaussian();
                }
            }
            for (double[] star : starPositions()) {
                double sigma = field.sigma(star[0], star[1]);
                double[] tail = field.tail(star[0], star[1]);
                for (int y = (int) star[1] - 14; y <= (int) star[1] + 14; y++) {
                    for (int x = (int) star[0] - 14; x <= (int) star[0] + 14; x++) {
                        double dx = x - star[0];
                        double dy = y - star[1];
                        double core = Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma)) / (2 * Math.PI * sigma * sigma);
                        double tx = dx - tail[0];
                        double ty = dy - tail[1];
                        double wing = Math.exp(-(tx * tx + ty * ty) / (2 * 4 * sigma * sigma)) / (2 * Math.PI * 4 * sigma * sigma);
                        image[y][x] += 40_000 * (0.85 * core + 0.15 * wing);
                    }
                }
            }
            short[][] data = new short[HEIGHT][WIDTH];
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    data[y][x] = (short) (Math.max(0, Math.min(65535, Math.round(image[y][x]))) - 32768);
                }
            }
            frames.add(data);
        }
        return frames;
    }

    private static List<double[]> starPositions() {
        List<double[]> positions = new ArrayList<>();
        for (int y = SPACING; y < HEIGHT - SPACING / 2; y += SPACING) {
            for (int x = SPACING; x < WIDTH - SPACING / 2; x += SPACING) {
                positions.add(new double[]{x + 0.3, y - 0.2});
            }
        }
        return positions;
    }

    private static List<SourceExtractor.DetectedObject> masterStars() {
        List<SourceExtractor.DetectedObject> stars = new ArrayList<>();
        for (double[] p : starPositions()) {
            SourceExtractor.DetectedObject star = new SourceExtractor.DetectedObject(p[0], p[1], 40_000, 20);
            star.fwhm = 3;
            stars.add(star);
        }
        return stars;
    }

    private static NightEquipment measure(Field field) {
        List<Integer> kept = Arrays.asList(0, 1, 2, 3);
        return NightEquipment.measure(frames(field, 4), kept, null, masterStars(), null, null);
    }

    private static double radius(double x, double y) {
        return Math.hypot(x - WIDTH / 2.0, y - HEIGHT / 2.0) / (Math.hypot(WIDTH, HEIGHT) / 2);
    }

    @Test
    public void starsGrowingToTheRightReadAsATilt() {
        NightEquipment ne = measure(new Field() {
            public double sigma(double x, double y) {
                return 1.3 * (1 + 0.4 * x / WIDTH);
            }

            public double[] tail(double x, double y) {
                return new double[]{0, 0};
            }

            public double sky(double x, double y) {
                return 1000;
            }
        });
        assertFalse(ne.isEmpty());
        assertEquals(4, ne.frames.size());
        assertTrue("tilt x " + ne.sizeTiltX, ne.sizeTiltX > 0.2);
        assertEquals(0, ne.sizeTiltY, 0.03);
        assertEquals("right side", NightEquipmentSectionWriter.direction(ne.sizeTiltX, ne.sizeTiltY));
        assertEquals(0, ne.frames.get(0).dimming, 0.01);
    }

    @Test
    public void starsGrowingToTheCornersReadAsCurvature() {
        NightEquipment ne = measure(new Field() {
            public double sigma(double x, double y) {
                double r = radius(x, y);
                return 1.3 * (1 + 0.3 * r * r);
            }

            public double[] tail(double x, double y) {
                return new double[]{0, 0};
            }

            public double sky(double x, double y) {
                return 1000;
            }
        });
        assertTrue("corner growth " + ne.cornerGrowth, ne.cornerGrowth > 0.2);
        assertEquals(0, Math.hypot(ne.sizeTiltX, ne.sizeTiltY), 0.04);
    }

    @Test
    public void tailsPointingOutwardsReadAsComa() {
        NightEquipment ne = measure(new Field() {
            public double sigma(double x, double y) {
                return 1.4;
            }

            public double[] tail(double x, double y) {
                // A tail that grows with the distance from the centre and points away from it.
                return new double[]{(x - WIDTH / 2.0) / 100, (y - HEIGHT / 2.0) / 100};
            }

            public double sky(double x, double y) {
                return 1000;
            }
        });
        assertTrue("coma slope " + ne.comaSlope, ne.comaSlope > 0);
        assertTrue(Math.abs(ne.comaSlope) > 3 * ne.comaSlopeError);
        assertEquals(WIDTH / 2.0, ne.comaZeroX, 30);
        assertEquals(HEIGHT / 2.0, ne.comaZeroY, 30);
    }

    @Test
    public void darkerCornersShowInTheBackgroundMap() {
        NightEquipment ne = measure(new Field() {
            public double sigma(double x, double y) {
                return 1.4;
            }

            public double[] tail(double x, double y) {
                return new double[]{0, 0};
            }

            public double sky(double x, double y) {
                double r = radius(x, y);
                return 1000 * (1 - 0.2 * r * r) + 100 * x / WIDTH;
            }
        });
        assertTrue("background corner " + ne.backgroundCorner, ne.backgroundCorner < -0.12);
        assertTrue("background tilt " + ne.backgroundTiltX, ne.backgroundTiltX > 0.05);
        assertEquals("right side", NightEquipmentSectionWriter.direction(ne.backgroundTiltX, ne.backgroundTiltY));
    }

    @Test
    public void wordsForDirectionsFollowThePicture() {
        assertEquals("bottom side", NightEquipmentSectionWriter.direction(0, 1));
        assertEquals("top left", NightEquipmentSectionWriter.direction(-1, -1));
        assertEquals("left side", NightEquipmentSectionWriter.direction(-1, 0.1));
    }

    @Test
    public void rankCorrelationFollowsTheOrder() {
        assertEquals(1, NightEquipmentSectionWriter.rankCorrelation(Arrays.asList(1.0, 2.0, 3.0, 4.0)), 1e-9);
        assertEquals(-1, NightEquipmentSectionWriter.rankCorrelation(Arrays.asList(4.0, 3.0, 2.0, 1.0)), 1e-9);
    }
}
