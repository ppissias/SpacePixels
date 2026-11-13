package eu.startales.spacepixels.preparation;

import eu.startales.spacepixels.fits.FitsInput;
import io.github.ppissias.jtransient.core.FrameDriftAnalyzer;
import io.github.ppissias.jtransient.engine.ImageFrame;
import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;

/** Scientific output checks exercise a common scale, exact source timing and explicit stale-WCS removal. */
public class PreparedSequenceWriterTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** Registered pixels inherit source solving hints but never the obsolete source WCS. */
    @Test public void preservesOnlyApproximateSourceWcs() throws Exception {
        Path source = temporary.newFile("solved.fit").toPath();
        try (Fits fits = new Fits()) {
            var image = Fits.makeHDU(new short[][]{{1, 2}, {3, 4}});
            var h = image.getHeader();
            h.addValue("CTYPE1", "RA---TAN", null); h.addValue("CTYPE2", "DEC--TAN", null);
            h.addValue("CRPIX1", 1.5, null); h.addValue("CRPIX2", 1.5, null);
            h.addValue("CRVAL1", 120.0, null); h.addValue("CRVAL2", -20.0, null);
            h.addValue("CD1_1", -1.0 / 3600, null); h.addValue("CD1_2", 0.0, null);
            h.addValue("CD2_1", 0.0, null); h.addValue("CD2_2", 1.0 / 3600, null);
            fits.addHDU(image); fits.write(source.toFile());
        }
        Path unsolved = temporary.newFile("unsolved.fit").toPath();
        try (Fits fits = new Fits()) { fits.addHDU(Fits.makeHDU(new short[][]{{1, 2}, {3, 4}})); fits.write(unsolved.toFile()); }
        var frame = new PreparedSequenceWriter.Frame(source, source, source);
        var missing = new PreparedSequenceWriter.Frame(unsolved, unsolved, unsolved);
        var result = PreparedSequenceWriter.write(List.of(missing, frame, missing),
                temporary.getRoot().toPath().resolve("hints"), () -> false, ignored -> { });
        for (Path output : result.files()) try (Fits fits = new Fits(output.toFile())) {
            var h = FitsInput.getImageHDU(fits).getHeader();
            assertFalse(h.containsKey("CTYPE1"));
            assertFalse(h.containsKey("CRVAL1"));
            var hints = eu.startales.spacepixels.fits.PlateSolveHints.read(h, 2);
            assertNotNull(hints);
            assertEquals(120, hints.raDegrees(), 1e-8);
            assertEquals(-20, hints.decDegrees(), 1e-8);
        }
    }

    /** All frames share one transform, so relative flux variation survives conversion and negative values do not clip. */
    @Test public void sharesOneLinearTransformAndRestoresExactSourceMetadata() throws Exception {
        List<PreparedSequenceWriter.Frame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Path input = temporary.newFile("frame" + i + ".fit").toPath();
            try (Fits fits = new Fits()) {
                var hdu = Fits.makeHDU(new float[][]{{-0.1f, 0.1f * (i + 1), 1.2f}});
                hdu.getHeader().addValue("DATE-OBS", "2020-01-01T01:02:03.123456", null);
                hdu.getHeader().addValue("EXPTIME", 30.25, null);
                hdu.getHeader().addValue("CRVAL1", 123.0, null);
                hdu.getHeader().addValue("CD1_1", 0.001, null);
                hdu.getHeader().addValue("A_ORDER", 2, null);
                fits.addHDU(hdu); fits.write(input.toFile());
            }
            frames.add(new PreparedSequenceWriter.Frame(input, input, input));
        }
        var result = PreparedSequenceWriter.write(frames, temporary.getRoot().toPath().resolve("aligned"), () -> false, ignored -> { });
        assertTrue(result.multiplier() > 0);
        assertEquals(0.1, result.addedOffset(), 1e-7);
        for (int i = 0; i < 3; i++) try (Fits fits = new Fits(result.files().get(i).toFile())) {
            var image = FitsInput.getImageHDU(fits);
            assertEquals(16, image.getHeader().getIntValue("BITPIX"));
            assertEquals(32768, image.getHeader().getIntValue("BZERO"));
            assertEquals("2020-01-01T01:02:03.123456", image.getHeader().getStringValue("DATE-OBS"));
            assertFalse(image.getHeader().containsKey("CRVAL1"));
            assertFalse(image.getHeader().containsKey("CD1_1"));
            assertFalse(image.getHeader().containsKey("A_ORDER"));
            short[][] pixels = (short[][]) image.getKernel();
            assertEquals(0, pixels[0][0] + 32768);
            double restored = (pixels[0][1] + 32768) / result.multiplier() - result.addedOffset();
            assertEquals(0.1 * (i + 1), restored, 1.0 / result.multiplier());
        }
    }

    /** A corrupt nonfinite input fails instead of fabricating a zero-valued scientific measurement. */
    @Test public void rejectsNonfiniteDataAndObservesCancellation() throws Exception {
        Path file = temporary.newFile("nan.fit").toPath();
        try (Fits fits = new Fits()) { fits.addHDU(Fits.makeHDU(new float[][]{{Float.NaN}})); fits.write(file.toFile()); }
        var frame = new PreparedSequenceWriter.Frame(file, file, file);
        var three = List.of(frame, frame, frame);
        assertThrows(java.io.IOException.class, () -> PreparedSequenceWriter.write(three, temporary.getRoot().toPath().resolve("bad"), () -> false, ignored -> { }));
        assertThrows(CancellationException.class, () -> PreparedSequenceWriter.write(three, temporary.getRoot().toPath().resolve("cancelled"), () -> true, ignored -> { }));
    }

    /** A common offset for negative mono data must not hide horizontal/vertical registration padding from the engine. */
    @Test public void preservesMonochromeMarginsAndMeasuredDrift() throws Exception { checkMargins(1); }

    /** RGB padding requires zero in every channel; a real zero mean at the footprint edge still receives the offset. */
    @Test public void preservesRgbMarginsAndMeasuredDrift() throws Exception { checkMargins(3); }

    /** Converts translated reference-sized frames and checks both pixel preservation and the actual engine's drift result. */
    private void checkMargins(int channels) throws Exception {
        int width = 64, height = 48;
        List<PreparedSequenceWriter.Frame> frames = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            float[][][] pixels = new float[channels][height][width];
            for (int c = 0; c < channels; c++) {
                for (int y = i * 2; y < height; y++) for (int x = i * 3; x < width; x++) pixels[c][y][x] = 0.4f;
                pixels[c][height / 2][width / 2] = -0.1f;
                pixels[c][20][40] = 0.0f; // An interior zero is data, never registration fill.
                if (channels == 3) pixels[c][height / 2][i * 3] = c == 0 ? 0.2f : c == 1 ? -0.2f : 0.0f;
            }
            Path input = temporary.newFile("shift" + i + ".fit").toPath();
            try (Fits fits = new Fits()) {
                fits.addHDU(Fits.makeHDU(channels == 1 ? pixels[0] : pixels)); fits.write(input.toFile());
            }
            frames.add(new PreparedSequenceWriter.Frame(input, input, input));
        }
        var result = PreparedSequenceWriter.write(frames, temporary.getRoot().toPath().resolve("margins"), () -> false, ignored -> { });
        assertTrue(result.addedOffset() > 0);
        List<ImageFrame> engineFrames = new ArrayList<>();
        for (int i = 0; i < 3; i++) try (Fits fits = new Fits(result.files().get(i).toFile())) {
            short[][] pixels = (short[][]) FitsInput.getImageHDU(fits).getKernel();
            assertEquals(height, pixels.length); assertEquals(width, pixels[0].length);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                if (y < i * 2 || x < i * 3) assertEquals("Registration fill must stay at physical zero", Short.MIN_VALUE, pixels[y][x]);
            }
            assertTrue("Interior zero data must receive the common offset", pixels[20][40] > Short.MIN_VALUE + 8);
            if (channels == 3) assertTrue("Zero RGB mean at an edge is real data", pixels[height / 2][i * 3] > Short.MIN_VALUE + 8);
            engineFrames.add(new ImageFrame(i, "shift" + i, pixels, -1, -1));
        }
        var drift = FrameDriftAnalyzer.analyze(engineFrames);
        assertTrue(drift.failedRegistrationFrames.isEmpty());
        assertEquals(6, drift.maxPaddingPixels);
        for (int i = 0; i < 3; i++) {
            assertEquals(i * 3, drift.driftPoints.get(i).x, 0);
            assertEquals(i * 2, drift.driftPoints.get(i).y, 0);
        }
    }
}
