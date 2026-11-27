/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.telemetry.FaintMoverSearchTelemetry;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The report says where the star width came from and what set the faint-mover search's fastest motion. */
public class StarWidthPresentationTest {

    @Test
    public void theStarWidthSaysWhereItCameFrom() {
        PipelineTelemetry telemetry = new PipelineTelemetry();
        assertNull("before quality control", StarWidthPresentation.data(telemetry));
        telemetry.starWidth.fwhmPixels = 2.87;
        telemetry.starWidth.source = "MEASURED";
        telemetry.starWidth.frames = 98;
        telemetry.starWidth.stars = 12345;
        assertEquals("measured on 12,345 stars in 98 kept frames", StarWidthPresentation.source(telemetry));
        var data = StarWidthPresentation.data(telemetry);
        assertEquals(2.87, (Double) data.get("fwhmPixels"), 0.0);
        assertEquals("MEASURED", data.get("source"));
        assertEquals(98, data.get("frames"));
        telemetry.starWidth.source = "SETTING";
        assertTrue(StarWidthPresentation.source(telemetry).contains("Auto-Tune"));
        telemetry.starWidth.source = "ASSUMED";
        assertTrue(StarWidthPresentation.source(telemetry).startsWith("assumed"));
    }

    @Test
    public void theFastestMotionSaysWhatSetIt() {
        FaintMoverSearchTelemetry t = new FaintMoverSearchTelemetry();
        t.maxSpeedPixelsPerMinute = 3.1;
        t.pointLikeMaxSpeedPixelsPerMinute = 4.68;
        t.maxMotionsApplied = 100_000;
        t.timeLimitSecondsApplied = 600;
        assertTrue(StarWidthPresentation.fastestMotion(t).contains("automatic fastest motion off"));
        t.maxSpeedLimit = "POINT_LIKE";
        assertTrue(StarWidthPresentation.fastestMotion(t).contains("2 star widths per exposure"));
        t.maxSpeedLimit = "MOTION_LIMIT";
        assertTrue(StarWidthPresentation.fastestMotion(t).contains("90 % of the 100,000-motion limit"));
        t.maxSpeedLimit = "MEMORY";
        assertTrue(StarWidthPresentation.fastestMotion(t).contains("quarter of the memory"));
        t.maxSpeedLimit = "TIME_LIMIT";
        t.speedBeforeTimeLimitPixelsPerMinute = 4.68;
        t.estimatedSeconds = 470;
        t.sampleMs = 2500;
        String time = StarWidthPresentation.fastestMotion(t);
        assertTrue(time, time.contains("lowered from 4.680 px/min") && time.contains("Estimated search time 470 s of the 600 s limit")
                && time.contains("2.5 s timed sample"));
        t.maxSpeedLimit = "NO_EXPOSURE_TIMES";
        assertTrue(StarWidthPresentation.fastestMotion(t).contains("no exposure times"));
        t.fwhmSource = "SETTING";
        assertEquals("from the settings", StarWidthPresentation.source(t));
    }
}
