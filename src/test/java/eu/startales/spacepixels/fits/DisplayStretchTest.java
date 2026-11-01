package eu.startales.spacepixels.fits;

import org.junit.Test;

import nom.tam.fits.Fits;

import java.awt.image.BufferedImage;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.Assert.assertEquals;

public class DisplayStretchTest {

    @Test
    public void linearDisplayUsesTheFullUnsignedRangeWithoutHistogramScaling() {
        byte[] table = DisplayStretch.linearLookupTable();
        int[] input = {0, 1, 255, 256, 32767, 32768, 65535};
        int[] expected = {0, 0, 0, 1, 127, 128, 255};
        for (int i = 0; i < input.length; i++) {
            assertEquals("Linear display at " + input[i], expected[i], table[input[i]] & 255);
        }
    }

    private static final int WIDTH = 97;
    private static final int HEIGHT = 61;

    /** A sky with noise, a gradient, faint and saturated stars, and a few pixels at both ends of the range. */
    private static short[][] syntheticFrame(long seed, int skyLevel) {
        Random random = new Random(seed);
        short[][] data = new short[HEIGHT][WIDTH];
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                double value = skyLevel + 3 * x + random.nextGaussian() * 40;
                if (random.nextInt(60) == 0) {
                    value += 2000 + random.nextInt(30000);
                }
                data[y][x] = (short) (Math.max(0, Math.min(65535, Math.round(value))) - 32768);
            }
        }
        data[0][0] = Short.MIN_VALUE;
        data[1][1] = Short.MAX_VALUE;
        return data;
    }

    private static short[] flatten(short[][] data) {
        short[] flat = new short[WIDTH * HEIGHT];
        for (int y = 0; y < HEIGHT; y++) {
            System.arraycopy(data[y], 0, flat, y * WIDTH, WIDTH);
        }
        return flat;
    }

    private static void assertSameAsRenderer(short[][] data, StretchAlgorithm algorithm, int primary, int secondary) throws Exception {
        BufferedImage rendered = new FitsVisualizationRenderer().getStretchedImageFullSize(data, WIDTH, HEIGHT, primary, secondary, algorithm);
        byte[] table = DisplayStretch.lookupTable(DisplayStretch.histogram(flatten(data)), algorithm, primary, secondary);
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int expected = rendered.getRGB(x, y) & 0xFF;
                int actual = table[data[y][x] - Short.MIN_VALUE] & 0xFF;
                assertEquals(algorithm + " " + primary + "/" + secondary + " at " + x + "," + y, expected, actual);
            }
        }
    }

    @Test
    public void everyAlgorithmMatchesTheRenderer() throws Exception {
        int[][] skies = {{1, 1200}, {2, 9000}, {3, 40000}};
        for (int[] sky : skies) {
            short[][] data = syntheticFrame(sky[0], sky[1]);
            for (StretchAlgorithm algorithm : StretchAlgorithm.values()) {
                int[] primaries = {algorithm.getPrimaryMinimum(), algorithm.getPrimaryDefault(), algorithm.getPrimaryMaximum()};
                int[] secondaries = {algorithm.getSecondaryMinimum(), algorithm.getSecondaryDefault(), algorithm.getSecondaryMaximum()};
                for (int primary : primaries) {
                    for (int secondary : secondaries) {
                        assertSameAsRenderer(data, algorithm, primary, secondary);
                    }
                }
            }
        }
    }

    @Test
    public void colourChannelsMatchTheRenderer() throws Exception {
        short[][][] colour = {syntheticFrame(4, 1500), syntheticFrame(5, 3000), syntheticFrame(6, 800)};
        for (StretchAlgorithm algorithm : StretchAlgorithm.values()) {
            int primary = algorithm.getPrimaryDefault();
            int secondary = algorithm.getSecondaryDefault();
            BufferedImage rendered = new FitsVisualizationRenderer().getStretchedImageFullSize(colour, WIDTH, HEIGHT, primary, secondary, algorithm);
            byte[][] tables = new byte[3][];
            for (int channel = 0; channel < 3; channel++) {
                tables[channel] = DisplayStretch.lookupTable(DisplayStretch.histogram(flatten(colour[channel])), algorithm, primary, secondary);
            }
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    int red = tables[0][colour[0][y][x] - Short.MIN_VALUE] & 0xFF;
                    int green = tables[1][colour[1][y][x] - Short.MIN_VALUE] & 0xFF;
                    int blue = tables[2][colour[2][y][x] - Short.MIN_VALUE] & 0xFF;
                    if (algorithm == StretchAlgorithm.EXTREME) {
                        red = green = blue = Math.max(red, Math.max(green, blue));
                    }
                    assertEquals(algorithm + " rgb at " + x + "," + y, rendered.getRGB(x, y) & 0xFFFFFF, (red << 16) | (green << 8) | blue);
                }
            }
        }
    }

    /**
     * Batch Stretch writes the same pixels as before the stretch tables: checksums of its output for every algorithm
     * at its smallest, default and largest settings on three mono frames, plus a colour frame at the defaults,
     * recorded from the earlier per-pixel code.
     */
    @Test
    public void batchStretchKeepsItsOutput() throws Exception {
        Map<StretchAlgorithm, Long> expected = Map.of(
                StretchAlgorithm.ASINH, 0x5EEA3BAFL,
                StretchAlgorithm.ENHANCE_LOW, 0xC7BA1E3CL,
                StretchAlgorithm.ENHANCE_HIGH, 0xA35C0846L,
                StretchAlgorithm.EXTREME, 0x9656EB86L);
        int[][] skies = {{1, 1200}, {2, 9000}, {3, 40000}};
        FitsVisualizationRenderer renderer = new FitsVisualizationRenderer();
        for (StretchAlgorithm algorithm : StretchAlgorithm.values()) {
            CRC32 crc = new CRC32();
            int[] primaries = {algorithm.getPrimaryMinimum(), algorithm.getPrimaryDefault(), algorithm.getPrimaryMaximum()};
            int[] secondaries = {algorithm.getSecondaryMinimum(), algorithm.getSecondaryDefault(), algorithm.getSecondaryMaximum()};
            for (int[] sky : skies) {
                for (int primary : primaries) {
                    for (int secondary : secondaries) {
                        update(crc, batchStretch(renderer, syntheticFrame(sky[0], sky[1]), primary, secondary, algorithm));
                    }
                }
            }
            short[][][] colour = {syntheticFrame(4, 1500), syntheticFrame(5, 3000), syntheticFrame(6, 800)};
            update(crc, batchStretch(renderer, colour, algorithm.getPrimaryDefault(), algorithm.getSecondaryDefault(), algorithm));
            assertEquals(algorithm.toString(), (long) expected.get(algorithm), crc.getValue());
        }
    }

    private static Object batchStretch(FitsVisualizationRenderer renderer, Object data, int primary, int secondary,
                                       StretchAlgorithm algorithm) throws Exception {
        try (Fits fits = new Fits()) {
            fits.addHDU(Fits.makeHDU(data));
            renderer.stretchFitsImage(fits, primary, secondary, algorithm);
            return fits.getHDU(0).getKernel();
        }
    }

    private static void update(CRC32 crc, Object kernel) {
        if (kernel instanceof short[][][] colour) {
            for (short[][] channel : colour) {
                update(crc, channel);
            }
            return;
        }
        for (short[] row : (short[][]) kernel) {
            for (short value : row) {
                crc.update(value >> 8);
                crc.update(value);
            }
        }
    }
}
