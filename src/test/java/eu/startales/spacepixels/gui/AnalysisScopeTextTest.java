package eu.startales.spacepixels.gui;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies the visible scope describes existing pipeline switches and tuning selection boundaries. */
public class AnalysisScopeTextTest {
    /** Auto-Tune always builds its pool from the full imported sequence regardless of table selection. */
    @Test public void tuningTextAlwaysUsesAllImportedFrames() {
        assertEquals("Auto-Tune: pool from all 12 frames", AnalysisScopeText.autoTune(12, 0));
        assertEquals("Auto-Tune: pool from all 12 frames", AnalysisScopeText.autoTune(12, 3));
        assertEquals("Auto-Tune: pool from all 12 frames", AnalysisScopeText.autoTune(12, 6));
    }

    /** The line says what the selection does now, in Blink's own rule and with proper counts. */
    @Test public void selectionScopeFollowsTheRules() {
        assertEquals("Blink: all 12 frames · Preview: sequence from the first frame · Auto-Tune: pool from all 12 frames",
                AnalysisScopeText.selectionScope(12, 0, 0));
        assertEquals("Blink: all 12 frames · Preview: sequence from frame 5 · Auto-Tune: pool from all 12 frames",
                AnalysisScopeText.selectionScope(12, 1, 5));
        assertEquals("Blink: the 4 selected frames · Preview: sequence from frame 2 · Auto-Tune: pool from all 12 frames",
                AnalysisScopeText.selectionScope(12, 4, 2));
        assertTrue(AnalysisScopeText.selectionScope(2, 0, 0).startsWith("Blink: needs 3 or more frames · "));
    }

    /** Captions keep the command's own words, for the manual and the tests, and add what it will act on. */
    @Test public void captionsNameWhatTheCommandActsOn() {
        assertEquals("Blink All (12)", AnalysisScopeText.blinkCaption(12, 0));
        assertEquals("Blink All (12)", AnalysisScopeText.blinkCaption(12, 2));
        assertEquals("Blink Selected (3)", AnalysisScopeText.blinkCaption(12, 3));
        assertEquals("Blink Selected", AnalysisScopeText.blinkCaption(0, 0));
        assertEquals("Blink Selected", AnalysisScopeText.blinkCaption(2, 0));
        assertEquals("Preview Frame", AnalysisScopeText.previewCaption(0));
        assertEquals("Preview Frame 7", AnalysisScopeText.previewCaption(7));
        assertEquals("Plate Solve Selected", AnalysisScopeText.plateSolveCaption(0));
        assertEquals("Plate Solve Frame 7", AnalysisScopeText.plateSolveCaption(7));
    }

    /** One run shows its date once; a saved run that is not the settings in use stays a separate fact. */
    @Test public void settingsNoteShowsARunOnce() {
        String saved = "Auto-tune saved: run at 2026-10-29 15:06 (4 min 56 s)";
        assertEquals("High, auto-tuned · run 2026-10-29 15:06 (4 min 56 s)",
                AnalysisScopeText.settingsNote("High, auto-tuned · run 2026-10-29 15:06", saved));
        assertEquals("High, auto-tuned, edited · run 2026-10-29 15:06 (4 min 56 s)",
                AnalysisScopeText.settingsNote("High, auto-tuned, edited · run 2026-10-29 15:06", saved));
        assertEquals("Manual settings<br>" + saved, AnalysisScopeText.settingsNote("Manual settings", saved));
        assertEquals("High, auto-tuned · run 2026-10-01 09:00<br>" + saved,
                AnalysisScopeText.settingsNote("High, auto-tuned · run 2026-10-01 09:00", saved));
        assertEquals("Manual settings", AnalysisScopeText.settingsNote("Manual settings", null));
    }

    /** Residual subanalyses are listed only when enabled, while photometry is identified as readiness-dependent. */
    @Test public void configuredAnalysesDoNotInventReportOrReadinessDecisions() {
        DetectionConfig config = new DetectionConfig();
        config.enableResidualTransientAnalysis = false;
        config.enableLocalRescueCandidates = true;
        config.enableLocalActivityClusters = true;
        config.enableVariableStarDetection = true;
        String text = AnalysisScopeText.analyses(config);
        assertFalse(text.contains("local rescue"));
        assertFalse(text.contains("activity clusters"));
        assertTrue(text.contains("readiness checked during run"));
        config.enableResidualTransientAnalysis = true;
        assertTrue(AnalysisScopeText.analyses(config).contains("local rescue"));
    }
}
