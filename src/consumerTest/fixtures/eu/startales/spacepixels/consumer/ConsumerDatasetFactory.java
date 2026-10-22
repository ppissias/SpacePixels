package eu.startales.spacepixels.consumer;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;

/** Creates disposable, statistically usable FITS inputs for packaged calibrated Auto-Tune validation. */
public final class ConsumerDatasetFactory {
    private static final int SIZE = 900;

    /** Prevents instances of this fixture-only factory. */
    private ConsumerDatasetFactory() {
    }

    /** Writes six unsigned mono16 frames with fixed Gaussian stars and independent sky/read noise in ADU. */
    public static Path createTuningSequence(Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("tuning"));
        Random random = new Random(3);
        double[][] signal = new double[SIZE][SIZE];
        for (double[] row : signal) Arrays.fill(row, 1000);
        for (int star = 0; star < 400; star++) {
            addStar(signal, 20 + random.nextDouble() * (SIZE - 40), 20 + random.nextDouble() * (SIZE - 40),
                    2000 * Math.pow(100, random.nextDouble()));
        }
        for (int frame = 0; frame < 6; frame++) {
            short[][] pixels = new short[SIZE][SIZE];
            for (int row = 0; row < SIZE; row++) {
                for (int column = 0; column < SIZE; column++) {
                    long adu = Math.round(signal[row][column] + random.nextGaussian() * Math.sqrt(signal[row][column] + 25));
                    pixels[row][column] = (short) (Math.max(0, Math.min(65535, adu)) - 32768);
                }
            }
            try (Fits fits = new Fits()) {
                BasicHDU<?> image = Fits.makeHDU(pixels);
                image.getHeader().addValue("BZERO", 32768, null);
                image.getHeader().addValue("BSCALE", 1, null);
                image.getHeader().addValue("EXPTIME", 60, null);
                image.getHeader().addValue("DATE-OBS", Instant.ofEpochMilli(1_760_000_000_000L + frame * 120_000L).toString(), null);
                fits.addHDU(image);
                fits.write(directory.resolve("frame" + frame + ".fit").toFile());
            }
        }
        return directory;
    }

    /** Adds an isolated three-pixel-FWHM point source of the supplied total flux to the shared ADU model. */
    private static void addStar(double[][] signal, double centerX, double centerY, double flux) {
        double sigma = 3.0 / 2.355;
        int radius = (int) Math.ceil(5 * sigma);
        double peak = flux / (2 * Math.PI * sigma * sigma);
        for (int row = (int) Math.round(centerY) - radius; row <= Math.round(centerY) + radius; row++) {
            for (int column = (int) Math.round(centerX) - radius; column <= Math.round(centerX) + radius; column++) {
                double offsetX = column - centerX;
                double offsetY = row - centerY;
                signal[row][column] += peak * Math.exp(-(offsetX * offsetX + offsetY * offsetY) / (2 * sigma * sigma));
            }
        }
    }
}
