package eu.startales.spacepixels.preparation;

import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Validates sensor interpretation and measured light exposures without guessing CFA metadata or relaxing calibration matches. */
public class PreparationSensorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** The metadata mode cannot promote a missing CFA pattern into a default RGGB interpretation. */
    @Test public void missingMetadataRequiresAnExplicitBayerChoice() throws Exception {
        Path image = image(null);
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateSensor(request(PreparationRequest.Bayer.METADATA),
                List.of(image), PreparationInputs.Role.LIGHTS));
        assertNull(SirilHeaderCorrections.imageHeader(image).getStringValue("BAYERPAT"));
    }

    /** An explicit override changes only interpretation cards, leaving original pixel ordering to the external tool. */
    @Test public void explicitOverrideSetsPatternAndOffsetsWithoutFlippingPixels() throws Exception {
        Path image = image("RGGB");
        FramePreparationService.validateSensor(request(PreparationRequest.Bayer.BGGR), List.of(image), PreparationInputs.Role.LIGHTS);
        var header = SirilHeaderCorrections.imageHeader(image);
        assertEquals("BGGR", header.getStringValue("BAYERPAT"));
        assertEquals("TOP-DOWN", header.getStringValue("ROWORDER"));
        assertEquals(1, header.getIntValue("XBAYROFF"));
        try (Fits fits = new Fits(image.toFile())) {
            assertArrayEquals(new short[]{1, 2}, ((short[][]) fits.getHDU(0).getKernel())[0]);
            assertArrayEquals(new short[]{3, 4}, ((short[][]) fits.getHDU(0).getKernel())[1]);
        }
    }

    /** A manual four-letter override cannot disguise metadata identifying an unsupported six-by-six colour mosaic. */
    @Test public void refusesKnownNonBayerMosaicsEvenWithAnOverride() throws Exception {
        Path image = image("RGB".repeat(12));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateSensor(request(PreparationRequest.Bayer.RGGB),
                List.of(image), PreparationInputs.Role.LIGHTS));
    }

    /** A known mismatched dark exposure must not be silently applied with dark optimization disabled. */
    @Test public void rejectsKnownCalibrationExposureMismatch() throws Exception {
        var light = new nom.tam.fits.Header();
        var dark = new nom.tam.fits.Header();
        light.addValue("EXPTIME", 30.0, null);
        dark.addValue("EXPTIME", 60.0, null);
        var inputs = Map.of(PreparationInputs.Role.LIGHTS, light, PreparationInputs.Role.DARKS, dark);
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateCalibrationHeaders(inputs));
        dark.addValue("EXPTIME", 30.0, null);
        FramePreparationService.validateCalibrationHeaders(inputs);
    }

    /** Measured shutter variations use the same tolerance as ordinary import, without rewriting their durations. */
    @Test public void acceptsLightExposureVariationsWithinTheImportGroup() throws Exception {
        var frames = List.of(exposure(30.3), exposure(30.0), exposure(30.5), exposure(30.1));
        FramePreparationService.validateSensor(monoRequest(), frames, PreparationInputs.Role.LIGHTS);
        assertEquals(30.3, SirilHeaderCorrections.imageHeader(frames.getFirst()).getDoubleValue("EXPTIME"), 0.0);
        assertEquals(30.5, SirilHeaderCorrections.imageHeader(frames.get(2)).getDoubleValue("EXPTIME"), 0.0);
        FramePreparationService.validateSensor(monoRequest(), List.of(exposure(1.0), exposure(1.1)), PreparationInputs.Role.LIGHTS);
    }

    /** Full-range comparison rejects a mixed group even when both extremes resemble the first frame. */
    @Test public void rejectsLightExposureDriftAndClearlyDifferentExposures() throws Exception {
        var mixed = assertThrows(java.io.IOException.class, () -> FramePreparationService.validateSensor(monoRequest(),
                List.of(exposure(30.5), exposure(30.0), exposure(31.0)), PreparationInputs.Role.LIGHTS));
        assertTrue(mixed.getMessage().contains("30.0 s to 31.0 s"));
        assertTrue(mixed.getMessage().contains("Choose one exposure group"));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateSensor(monoRequest(),
                List.of(exposure(30.0), exposure(60.0)), PreparationInputs.Role.LIGHTS));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateSensor(monoRequest(),
                List.of(exposure(1.0), exposure(1.102)), PreparationInputs.Role.LIGHTS));
    }

    /** Light-frame grouping must not relax dark cohorts or the exposure match needed for calibration subtraction. */
    @Test public void calibrationExposureChecksRemainStrict() throws Exception {
        var frames = List.of(exposure(30.0), exposure(30.1));
        for (var role : List.of(PreparationInputs.Role.DARKS, PreparationInputs.Role.DARK_FLATS)) {
            assertThrows(java.io.IOException.class, () -> FramePreparationService.validateSensor(monoRequest(), frames, role));
        }
        var light = SirilHeaderCorrections.imageHeader(frames.getFirst());
        var dark = SirilHeaderCorrections.imageHeader(frames.get(1));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateCalibrationHeaders(
                Map.of(PreparationInputs.Role.LIGHTS, light, PreparationInputs.Role.DARKS, dark)));
        assertThrows(java.io.IOException.class, () -> FramePreparationService.validateCalibrationHeaders(
                Map.of(PreparationInputs.Role.FLATS, light, PreparationInputs.Role.DARK_FLATS, dark)));
    }

    /** Creates a monochrome working image with its recorded exposure in seconds. */
    private Path exposure(double seconds) throws Exception {
        Path file = temporary.newFile().toPath();
        try (Fits fits = new Fits()) {
            var image = Fits.makeHDU(new short[][]{{1, 2}, {3, 4}});
            image.getHeader().addValue("EXPTIME", seconds, null);
            fits.addHDU(image); fits.write(file.toFile());
        }
        return file;
    }

    /** Sensor-independent exposure tests need no CFA overrides or calibration input selections. */
    private PreparationRequest monoRequest() {
        return new PreparationRequest(Path.of("siril-cli"), Map.of(), PreparationRequest.Sensor.MONO,
                PreparationRequest.Bayer.METADATA, PreparationRequest.RowOrder.METADATA, 0, 0,
                temporary.getRoot().toPath(), false, false);
    }

    /** Creates a private uncompressed working frame with a known pixel ordering. */
    private Path image(String pattern) throws Exception {
        Path file = temporary.newFile().toPath();
        try (Fits fits = new Fits()) {
            var image = Fits.makeHDU(new short[][]{{1, 2}, {3, 4}});
            if (pattern != null) image.getHeader().addValue("BAYERPAT", pattern, null);
            image.getHeader().addValue("ROWORDER", "TOP-DOWN", null);
            fits.addHDU(image); fits.write(file.toFile());
        }
        return file;
    }

    /** Captures an explicit pattern override for a run-owned FITS copy. */
    private PreparationRequest request(PreparationRequest.Bayer pattern) {
        return new PreparationRequest(Path.of("siril-cli"), Map.of(), PreparationRequest.Sensor.CFA,
                pattern, PreparationRequest.RowOrder.TOP_DOWN, 1, 0, temporary.getRoot().toPath(), false, false);
    }
}
