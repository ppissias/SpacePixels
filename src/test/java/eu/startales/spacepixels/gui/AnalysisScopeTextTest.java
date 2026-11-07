package eu.startales.spacepixels.gui;

import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies the visible scope describes existing pipeline switches and tuning selection boundaries. */
public class AnalysisScopeTextTest {
    /** Too-small selection explicitly falls back; the threshold selection is described as the tuning pool. */
    @Test public void tuningTextExplainsFallbackAndSelection() {
        assertEquals("Auto-Tune: pool from all 12 frames (selection below 4)", AnalysisScopeText.autoTune(12, 3));
        assertEquals("Auto-Tune: pool from 4 selected frames", AnalysisScopeText.autoTune(12, 4));
    }

    /** The line says what the selection does now, in Blink's own rule and with proper counts. */
    @Test public void selectionScopeFollowsTheRules() {
        assertEquals("Blink: select 3 or more frames · Preview: sequence from the first frame · Auto-Tune: pool from all 12 frames (selection below 4)",
                AnalysisScopeText.selectionScope(12, 0, 0));
        assertEquals("Blink: select 3 or more frames · Preview: sequence from frame 5 · Auto-Tune: pool from all 12 frames (selection below 4)",
                AnalysisScopeText.selectionScope(12, 1, 5));
        assertEquals("Blink: the 4 selected frames · Preview: sequence from frame 2 · Auto-Tune: pool from 4 selected frames",
                AnalysisScopeText.selectionScope(12, 4, 2));
    }

    /** Captions keep the command's own words, for the manual and the tests, and add what it will act on. */
    @Test public void captionsNameWhatTheCommandActsOn() {
        assertEquals("Blink Selected", AnalysisScopeText.blinkCaption(2));
        assertEquals("Blink Selected (3)", AnalysisScopeText.blinkCaption(3));
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
