package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.config.AppConfig;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Checks default selection, object naming, the details from the settings and the straight-line residuals. */
public class MpcExportTest {
    private static final long START = 1_661_317_942_149L;

    /** Six observations spread over the arc, always the first and the last. */
    @Test
    public void selectsObservationsSpreadOverTheArc() {
        assertEquals(List.of(0, 1, 2), MpcExport.spreadSelection(3, 6));
        assertEquals(List.of(0, 10, 20, 31, 41, 51), MpcExport.spreadSelection(52, 6));
        assertEquals(List.of(), MpcExport.spreadSelection(0, 6));
    }

    /** Known numbered objects go by number, known unnumbered ones by provisional designation, others by tracklet ID. */
    @Test
    public void namesObjectsAsTheMpcExpects() {
        var numbered = MpcExport.tracklet(object("known", List.of("649341 (2011 DK30)")), List.of(), START, 0);
        assertEquals("649341", numbered.permID());
        assertNull(numbered.trkSub());
        assertEquals("194", MpcExport.tracklet(object("known", List.of("194 Prokne")), List.of(), START, 0).permID());
        var provisional = MpcExport.tracklet(object("known", List.of("(2022 QJ95)")), List.of(), START, 0);
        assertEquals("2022 QJ95", provisional.provID());
        assertEquals("S2082405", MpcExport.tracklet(object("no-known-match", List.of()), List.of(), START, 5).trkSub());
        assertEquals("an ambiguous match is not an identity", "S2082401",
                MpcExport.tracklet(object("check", List.of("A", "B")), List.of(), START, 1).trkSub());
    }

    /** The details come from the settings; names split at commas; the code is upper case. */
    @Test
    public void detailsComeFromTheSettings() {
        var config = new AppConfig();
        config.observatoryCode = " w84 ";
        config.mpcSubmitter = "P. Smith";
        config.mpcObservers = "A. One, B. Two;C. Three";
        config.mpcMeasurers = "P. Smith";
        config.mpcTelescopeDesign = "Reflector";
        config.mpcAperture = "0,35";
        config.mpcDetector = "CMOS";
        config.mpcMode = "CMO";
        var context = MpcExport.context(config);
        assertEquals("W84", context.stationCode());
        assertEquals(List.of("A. One", "B. Two", "C. Three"), context.observers());
        assertEquals(0.35, context.apertureMeters(), 0);
        assertEquals("CMO", context.mode());
        config.mpcAperture = "large";
        assertEquals(0, MpcExport.context(config).apertureMeters(), 0);
    }

    /** Positions on a straight line have no residual; one displaced position stands out by its displacement. */
    @Test
    public void residualsFromStraightLineMotion() {
        var observations = new ArrayList<MpcObservation>();
        for (int i = 0; i < 6; i++) {
            observations.add(new MpcObservation(i + 1, START + i * 600_000L, 120_000, 0, 0, 357.2 - i * 0.001,
                    -1.8 - i * 0.0003 + (i == 3 ? 1.0 / 3600 : 0), 0.1, 0.1, "Gaia3", ""));
        }
        double[][] residuals = MpcExport.linearResiduals(observations);
        assertEquals(6, residuals.length);
        assertTrue("the displaced one has the largest Dec residual", Math.abs(residuals[3][1]) > 0.6);
        for (double[] r : residuals) assertEquals(0, r[0], 1e-6);
        assertEquals(0, MpcExport.linearResiduals(observations.subList(0, 2)).length);
        assertTrue(Double.isNaN(MpcExport.rms(residuals, List.of())));
    }

    /** Only measured tracks that are known or match nothing are offered by default. */
    @Test
    public void defaultExportScope() {
        assertTrue(MpcExport.includedByDefault(object("known", List.of("1 Ceres"))));
        assertTrue(MpcExport.includedByDefault(object("no-known-match", List.of())));
        assertFalse(MpcExport.includedByDefault(object("check", List.of())));
        assertFalse(MpcExport.includedByDefault(new ReportedObjects.ReportedObject("FM1", ReportedObjects.FAINT_MOVER,
                "no-known-match", List.of(), "", List.of(), null, null)));
    }

    private static ReportedObjects.ReportedObject object(String status, List<String> names) {
        return new ReportedObjects.ReportedObject("T1", ReportedObjects.TRACK, status, names, "", List.of(), null, null);
    }
}
