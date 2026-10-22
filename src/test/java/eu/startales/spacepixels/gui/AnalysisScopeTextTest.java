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
