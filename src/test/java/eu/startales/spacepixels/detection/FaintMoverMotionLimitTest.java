package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.FaintMoverSearch;
import org.junit.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The settings page warns before a run when the fastest motion needs more trial motions than the engine searches. */
public class FaintMoverMotionLimitTest {
    /** 115 frames over about 283 minutes, as the B1m session of 2022-08-24. */
    private static FitsFileInformation[] session() {
        var files = new FitsFileInformation[115];
        Instant start = Instant.parse("2022-08-24T23:00:00Z");
        for (int i = 0; i < files.length; i++) {
            String time = start.plusMillis(Math.round(i * 283.0 / 114 * 60000)).toString().replace("Z", "");
            files[i] = new FitsFileInformation("f" + i + ".fit", "f" + i + ".fit", true, 2048, 4096, Map.of("DATE-OBS", time, "EXPTIME", "120"));
        }
        return files;
    }

    @Test
    public void theLimitIsTheEngines() throws Exception {
        var field = FaintMoverSearch.class.getDeclaredField("MAX_MOTIONS");
        field.setAccessible(true);
        assertEquals(field.getInt(null), FaintMoverRuntimeEstimate.MAX_MOTIONS);
    }

    @Test
    public void aFastestMotionOverTheLimitIsWarnedWithTheHighestThatFits() {
        var config = new DetectionConfig();
        config.faintMoverMinDisplacementFwhm = 2;
        config.faintMoverMaxSpeedPixelsPerMinute = 7.6242;   // 2″/min at 0.2623″/px
        var limit = FaintMoverRuntimeEstimate.motionLimit(session(), config, 4.14);
        assertTrue("about 190,000 in the run; " + limit.motions(), limit.exceeded() && limit.motions() > 180_000 && limit.motions() < 240_000);
        assertEquals("about 1.4″/min", 5.3, limit.highestSpeedPixelsPerMinute(), 0.3);
        String text = FaintMoverRuntimeEstimate.describe(session(), config, 4.14);
        assertTrue(text, text.contains("probably skip the search") && text.contains("100,000"));

        config.faintMoverMaxSpeedPixelsPerMinute = 3.81211;  // 1″/min, which completed
        limit = FaintMoverRuntimeEstimate.motionLimit(session(), config, 4.14);
        assertFalse(limit.exceeded());
        assertFalse(FaintMoverRuntimeEstimate.describe(session(), config, 4.14).contains("skip"));
        assertNull(FaintMoverRuntimeEstimate.motionLimit(new FitsFileInformation[0], config, 4.14));
    }

    @Test
    public void theFastestMotionsTrailIsShownInStarWidths() {
        var config = new DetectionConfig();
        config.faintMoverMaxSpeedPixelsPerMinute = 7.6242;
        var trail = FaintMoverRuntimeEstimate.trail(session(), config, 4.14);
        assertEquals(2.0, trail.exposureMinutes(), 1e-9);
        assertEquals(15.25, trail.trailPixels(), 0.01);
        assertEquals(3.68, trail.trailStarWidths(), 0.01);
        assertTrue(trail.beyondPoint());
        assertEquals("2 star widths in 2 min", 4.14, trail.pointSpeedPixelsPerMinute(), 1e-9);
        config.faintMoverMaxSpeedPixelsPerMinute = 3.81211;
        assertFalse(FaintMoverRuntimeEstimate.trail(session(), config, 4.14).beyondPoint());
        assertNull(FaintMoverRuntimeEstimate.trail(new FitsFileInformation[0], config, 4.14));
    }

    @Test
    public void gpuModeRaisesMotionCapTimeLimitAndScalesEstimate() {
        var config = new DetectionConfig();
        config.faintMoverMinDisplacementFwhm = 2;
        config.faintMoverMaxSpeedPixelsPerMinute = 7.6242; // ~190k motions: over 100k CPU cap, under 400k GPU cap
        var gpuLimit = FaintMoverRuntimeEstimate.motionLimit(session(), config, 4.14,
                io.github.ppissias.jtransient.core.FaintMoverSearch.MAX_MOTIONS_GPU);
        assertFalse(gpuLimit.exceeded());
        assertEquals(io.github.ppissias.jtransient.core.FaintMoverSearch.MAX_MOTIONS_GPU, gpuLimit.maxMotions());

        String gpuText = FaintMoverRuntimeEstimate.describe(session(), config, 4.14,
                io.github.ppissias.jtransient.core.FaintMoverSearch.MAX_MOTIONS_GPU, config.faintMoverMaxSeconds, 20.0);
        assertFalse(gpuText, gpuText.contains("probably skip"));
        assertTrue(gpuText, gpuText.contains("limit 10.0 min"));
    }

    @Test
    public void autoMaxSpeedUsesTwoFwhmPerFrameAndClampsBelowMotionCap() {
        var config = new DetectionConfig();
        config.faintMoverMinDisplacementFwhm = 2.0;

        // 120 s (2 min) exposures with 4.14 px FWHM -> 2 FWHM/frame = 4.14 px/min (~63k motions, under 90% of 100k CPU cap).
        var normal = FaintMoverRuntimeEstimate.autoMaxSpeed(session(), config, 4.14, FaintMoverRuntimeEstimate.MAX_MOTIONS);
        assertNotNull(normal);
        assertFalse(normal.cappedByMotionLimit());
        assertEquals(4.14, normal.speedPixelsPerMinute(), 1e-6);
        assertEquals(4.14, normal.pointSpeedPixelsPerMinute(), 1e-6);

        // Short 15 s (0.25 min) exposures over the same 283 min span -> 2 FWHM/frame = 33.12 px/min, clamped below 90% of CPU cap.
        var shortFiles = new FitsFileInformation[115];
        Instant start = Instant.parse("2022-08-24T23:00:00Z");
        for (int i = 0; i < shortFiles.length; i++) {
            String time = start.plusMillis(Math.round(i * 283.0 / 114 * 60000)).toString().replace("Z", "");
            shortFiles[i] = new FitsFileInformation("s" + i + ".fit", "s" + i + ".fit", true, 2048, 4096,
                    Map.of("DATE-OBS", time, "EXPTIME", "15"));
        }
        var cpuCapped = FaintMoverRuntimeEstimate.autoMaxSpeed(shortFiles, config, 4.14, FaintMoverRuntimeEstimate.MAX_MOTIONS);
        assertNotNull(cpuCapped);
        assertTrue(cpuCapped.cappedByMotionLimit());
        assertEquals(33.12, cpuCapped.pointSpeedPixelsPerMinute(), 1e-6);
        var checkCfg = new DetectionConfig();
        checkCfg.faintMoverMaxSpeedPixelsPerMinute = cpuCapped.speedPixelsPerMinute();
        var resultingLimit = FaintMoverRuntimeEstimate.motionLimit(shortFiles, checkCfg, 4.14, FaintMoverRuntimeEstimate.MAX_MOTIONS);
        assertFalse("Capped auto speed must stay safely below the motion cap", resultingLimit.exceeded());
        assertEquals(0.90 * FaintMoverRuntimeEstimate.MAX_MOTIONS, resultingLimit.motions(), 50.0);

        // GPU cap allows roughly twice the speed of CPU cap (4x motions -> 2x speed).
        var gpuCapped = FaintMoverRuntimeEstimate.autoMaxSpeed(shortFiles, config, 4.14, FaintMoverRuntimeEstimate.MAX_MOTIONS_GPU);
        assertNotNull(gpuCapped);
        assertTrue(gpuCapped.cappedByMotionLimit());
        assertTrue(gpuCapped.speedPixelsPerMinute() > cpuCapped.speedPixelsPerMinute() * 1.8);

        assertNull(FaintMoverRuntimeEstimate.autoMaxSpeed(new FitsFileInformation[0], config, 4.14, FaintMoverRuntimeEstimate.MAX_MOTIONS));
    }

    @Test
    public void timeAssessmentFlagsLikelyAndPossibleTimeoutExceedance() {
        var config = new DetectionConfig();
        config.faintMoverMaxSpeedPixelsPerMinute = 4.14;
        config.faintMoverSearchSpeed = 0; // Normal (full resolution) on 115 x 8MP frames takes many minutes on CPU.

        // Very short 0.1 min (6 s) limit -> definitely likelyToExceed.
        var exceeded = FaintMoverRuntimeEstimate.timeAssessment(session(), config, 4.14,
                FaintMoverRuntimeEstimate.MAX_MOTIONS, 6.0, Double.NaN);
        assertNotNull(exceeded);
        assertTrue(exceeded.likelyToExceed());
        assertFalse(exceeded.mayExceed());
        String warnText = FaintMoverRuntimeEstimate.describe(session(), config, 4.14,
                FaintMoverRuntimeEstimate.MAX_MOTIONS, 6.0, Double.NaN);
        assertTrue(warnText, warnText.contains("Warning:") && warnText.contains("likely to exceed"));

        // Limit just above midpoint but below 2x upper bound -> mayExceed.
        double borderlineLimit = exceeded.estimatedSeconds() * 1.5;
        var borderline = FaintMoverRuntimeEstimate.timeAssessment(session(), config, 4.14,
                FaintMoverRuntimeEstimate.MAX_MOTIONS, borderlineLimit, Double.NaN);
        assertNotNull(borderline);
        assertFalse(borderline.likelyToExceed());
        assertTrue(borderline.mayExceed());
        String noteText = FaintMoverRuntimeEstimate.describe(session(), config, 4.14,
                FaintMoverRuntimeEstimate.MAX_MOTIONS, borderlineLimit, Double.NaN);
        assertTrue(noteText, noteText.contains("Note:") && noteText.contains("may approach or exceed"));

        // Generous limit well above 2x upper bound -> neither flag.
        double generousLimit = exceeded.estimatedSeconds() * 5.0;
        var comfortable = FaintMoverRuntimeEstimate.timeAssessment(session(), config, 4.14,
                FaintMoverRuntimeEstimate.MAX_MOTIONS, generousLimit, Double.NaN);
        assertNotNull(comfortable);
        assertFalse(comfortable.likelyToExceed());
        assertFalse(comfortable.mayExceed());
        String okText = FaintMoverRuntimeEstimate.describe(session(), config, 4.14,
                FaintMoverRuntimeEstimate.MAX_MOTIONS, generousLimit, Double.NaN);
        assertFalse(okText, okText.contains("Warning:") || okText.contains("may approach or exceed"));
    }
}
