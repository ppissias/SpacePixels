package eu.startales.spacepixels.preparation;

import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Sensor interpretation must not guess an unknown Bayer pattern or reinterpret a known different mosaic. */
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
        return new PreparationRequest(PreparationTool.SIRIL, Path.of("siril-cli"), Map.of(), PreparationRequest.Sensor.CFA,
                pattern, PreparationRequest.RowOrder.TOP_DOWN, 1, 0, temporary.getRoot().toPath(), false);
    }
}
