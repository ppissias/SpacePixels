package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BayerMosaic;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.preparation.PreparationRequest;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

/** The Seestar presets take the pattern from the file, supply top-down rows, and are found from the camera's cards. */
public class CameraBayerPresetTest {

    /** Each Seestar keeps the file's pattern and states top-down; other presets keep their nominal values. */
    @Test public void seestarPresetsKeepThePatternAndSupplyTheRows() {
        for (CameraBayerPreset preset : new CameraBayerPreset[]{CameraBayerPreset.SEESTAR_S30, CameraBayerPreset.SEESTAR_S30_PRO,
                CameraBayerPreset.SEESTAR_S50, CameraBayerPreset.SEESTAR_S50_PRO}) {
            assertEquals(PreparationRequest.Bayer.METADATA, preset.pattern());
            assertEquals(PreparationRequest.RowOrder.TOP_DOWN, preset.rowOrder());
            assertTrue(preset.camera());
            assertEquals(preset.label() + " — pattern from file, top-down", preset.toString());
        }
        assertEquals("ZWO Seestar S30 Pro", CameraBayerPreset.SEESTAR_S30_PRO.label());
        assertEquals(PreparationRequest.RowOrder.METADATA, CameraBayerPreset.METADATA.rowOrder());
        assertEquals(PreparationRequest.RowOrder.METADATA, CameraBayerPreset.CANON_RAW.rowOrder());
        assertEquals("Canon EOS RAW (CR2 / CR3) — file metadata", CameraBayerPreset.CANON_RAW.toString());
        assertNull(CameraBayerPreset.CUSTOM.rowOrder());
        assertFalse(CameraBayerPreset.CUSTOM.camera());
        assertFalse(CameraBayerPreset.METADATA.camera());
        assertEquals("ZWO ASI1600MC / Pro — GRBG, top-down", CameraBayerPreset.ZWO_ASI1600MC.toString());
    }

    /** The whole model must match, in CREATOR (as the S30 Pro writes it) or INSTRUME (as the S50 does). */
    @Test public void findsTheSeestarFromItsCards() {
        assertSame(CameraBayerPreset.SEESTAR_S30_PRO, CameraBayerPreset.forCamera(mosaic("ZWO Seestar S30 Pro", "imx585")));
        assertSame(CameraBayerPreset.SEESTAR_S30_PRO, CameraBayerPreset.forCamera(mosaic("ZWO Seestar S30 Pro", null)));
        assertSame(CameraBayerPreset.SEESTAR_S30, CameraBayerPreset.forCamera(mosaic("ZWO Seestar S30", null)));
        assertSame(CameraBayerPreset.SEESTAR_S50, CameraBayerPreset.forCamera(mosaic("ZWO Seestar S50", "Seestar S50")));
        assertSame(CameraBayerPreset.SEESTAR_S50, CameraBayerPreset.forCamera(mosaic(null, "Seestar S50")));
        assertSame(CameraBayerPreset.SEESTAR_S50_PRO, CameraBayerPreset.forCamera(mosaic(null, "seestar s50 pro")));
        assertNull(CameraBayerPreset.forCamera(mosaic("N.I.N.A.", "ZWO ASI2600MC Pro")));
        assertNull(CameraBayerPreset.forCamera(mosaic("ZWO Seestar S30 Pro Max", null)));
        assertNull(CameraBayerPreset.forCamera(null));
    }

    /** Align with Siril reads raw colour frames as colour, with rows from the files or the camera's preset, or not at all. */
    @Test public void alignmentInterpretsRawColourFrames() {
        var mono = GuiFitsImportInteraction.interpretation(new FitsFileInformation[]{frame(Map.of())});
        assertEquals(PreparationRequest.Sensor.MONO, mono.sensor());
        var seestar = GuiFitsImportInteraction.interpretation(new FitsFileInformation[]{frame(Map.of("BAYERPAT", "GRBG", "CREATOR", "ZWO Seestar S30 Pro"))});
        assertEquals(PreparationRequest.Sensor.CFA, seestar.sensor());
        assertEquals(PreparationRequest.RowOrder.TOP_DOWN, seestar.rowOrder());
        assertEquals("ZWO Seestar S30 Pro", seestar.preset());
        var stated = GuiFitsImportInteraction.interpretation(new FitsFileInformation[]{frame(Map.of("BAYERPAT", "RGGB", "ROWORDER", "BOTTOM-UP"))});
        assertEquals(PreparationRequest.RowOrder.METADATA, stated.rowOrder());
        assertNull(stated.preset());
        assertNull("An unknown camera without ROWORDER cannot be aligned",
                GuiFitsImportInteraction.interpretation(new FitsFileInformation[]{frame(Map.of("BAYERPAT", "RGGB"))}));
    }

    private static BayerMosaic mosaic(String creator, String instrument) {
        return new BayerMosaic("GRBG", null, null, null, creator, instrument);
    }

    private static FitsFileInformation frame(Map<String, String> cards) {
        return new FitsFileInformation("frame.fit", "frame.fit", true, 4, 4, cards);
    }
}
