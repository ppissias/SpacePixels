package eu.startales.spacepixels.preparation;

import org.junit.Test;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import static eu.startales.spacepixels.preparation.PreparationInputs.Role.*;
import static org.junit.Assert.*;

/** Recipe regression checks protect calibration ordering and retaining individual detection exposures. */
public class PreparationRecipeTest {
    /** A dark master already contains bias: the light correction must not subtract both again. */
    @Test public void avoidsDoubleBiasSubtractionAndKeepsRegisteredIndividuals() {
        var request = new PreparationRequest(Path.of("siril-cli"), Map.of(), PreparationRequest.Sensor.CFA,
                PreparationRequest.Bayer.METADATA, PreparationRequest.RowOrder.METADATA, 0, 0, Path.of("."), false, false);
        String script = SirilPreparationRecipe.processing(request, Map.of(LIGHTS, 3, DARKS, 3, BIASES, 3, FLATS, 3), Set.of());
        String lightLine = script.lines().filter(line -> line.startsWith("calibrate lights")).findFirst().orElseThrow();
        assertTrue(lightLine.contains("-dark="));
        assertFalse(lightLine.contains("-bias="));
        assertTrue(lightLine.contains("-cfa -debayer"));
        assertTrue(script.contains("seqapplyreg pp_lights"));
        assertTrue(script.contains("-framing=current"));
        assertFalse(script.contains("-framing=min"));
        assertFalse(script.contains("stack r_pp_lights"));
        assertFalse(script.contains("-cc="));
        assertFalse(script.contains("-equalize_cfa"));
    }

    /** A probe must identify a Siril version instead of accepting a different program's version text. */
    @Test public void checksSirilCliIdentity() throws Exception {
        assertEquals("siril 1.4.4", SirilInstallation.describeProbe("siril 1.4.4\n"));
        assertThrows(java.io.IOException.class, () -> SirilInstallation.describeProbe("different program 1.2"));
    }

    /** Numeric version comparison is shared by installation checks and the recipe's minimum requirement. */
    @Test public void requiresSupportedSirilBeforePreparation() throws Exception {
        for (String version : new String[]{"1.4.0", "1.4.4", "1.10.0", "2.0.0"})
            assertEquals("siril " + version, SirilInstallation.describeProbe("siril " + version));
        for (String version : new String[]{"0.99.12", "1.2.6", "1.3.99"}) {
            var failure = assertThrows(java.io.IOException.class, () -> SirilInstallation.describeProbe("siril " + version));
            assertTrue(failure.getMessage().contains(SirilInstallation.MINIMUM_VERSION));
            assertTrue(failure.getMessage().contains(version));
        }
        assertEquals("Siril version 1.4.4", SirilInstallation.describeProbe("\u001b[32mSiril version 1.4.4\u001b[0m"));
        assertThrows(java.io.IOException.class, () -> SirilInstallation.describeProbe("siril unknown"));
        assertTrue(SirilPreparationRecipe.conversion(Set.of(LIGHTS), Set.of())
                .startsWith("requires " + SirilInstallation.MINIMUM_VERSION + "\n"));
    }
}
