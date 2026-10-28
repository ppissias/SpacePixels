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
        var request = new PreparationRequest(PreparationTool.SIRIL, Path.of("siril-cli"), Map.of(), PreparationRequest.Sensor.CFA,
                PreparationRequest.Bayer.METADATA, PreparationRequest.RowOrder.METADATA, 0, 0, Path.of("."), false);
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

    /** Standard Mode retains each reference-sized registered light and its dark margins for drift analysis. */
    @Test public void dssUsesStandardModeWithoutDrizzle() {
        var request = new PreparationRequest(PreparationTool.DSS, Path.of("DeepSkyStackerCL"), Map.of(), PreparationRequest.Sensor.MONO,
                PreparationRequest.Bayer.METADATA, PreparationRequest.RowOrder.METADATA, 0, 0, Path.of("."), false);
        String list = DssPreparationRecipe.fileList(request, Map.of(LIGHTS, java.util.List.of(Path.of("light.fit"))), null);
        assertTrue(list.contains("#V5WS#Stacking/Mosaic#0\n"));
        assertTrue(list.contains("#V5WS#Stacking/PixelSizeMultiplier#1\n"));
    }

    /** Installation checks must reject graphical launchers, rather than accidentally opening them as CLI jobs. */
    @Test public void checksCliIdentityAndToleratesExpectedHelpExitSemantics() throws Exception {
        assertEquals("siril 1.4.4", PreparationTool.SIRIL.describeProbe("siril 1.4.4\n"));
        assertEquals("DeepSkyStacker 6.2.3 Command Line", PreparationTool.DSS.describeProbe("DeepSkyStacker 6.2.3 Command Line\nUsage..."));
        assertThrows(java.io.IOException.class, () -> PreparationTool.SIRIL.describeProbe("different program 1.2"));
    }
}
