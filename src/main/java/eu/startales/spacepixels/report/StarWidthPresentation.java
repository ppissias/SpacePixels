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

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The session's star width (Gaussian FWHM) for the faint searches and the registration check, and the fastest motion
 * the faint-mover search chose, in the words the report and report-data.json use.
 */
final class StarWidthPresentation {
    private StarWidthPresentation() {
    }

    /** The {@code starWidth} key of report-data.json, or null before quality control ran. */
    static Map<String, Object> data(PipelineTelemetry telemetry) {
        if (telemetry == null || telemetry.starWidth == null || telemetry.starWidth.source.isEmpty()) {
            return null;
        }
        var width = telemetry.starWidth;
        var data = new LinkedHashMap<String, Object>();
        data.put("fwhmPixels", Double.isFinite(width.fwhmPixels) ? width.fwhmPixels : null);
        data.put("source", width.source);
        data.put("frames", width.frames);
        data.put("stars", width.stars);
        return data;
    }

    /** Where the session's star width came from, such as "measured on 1,204 stars in 98 kept frames". */
    static String source(PipelineTelemetry telemetry) {
        if (telemetry == null || telemetry.starWidth == null) return "";
        var width = telemetry.starWidth;
        return switch (width.source) {
            case "SETTING" -> "from the settings (Star Width, which Auto-Tune sets)";
            case "MEASURED" -> String.format(Locale.US, "measured on %,d stars in %d kept frames", width.stars, width.frames);
            case "ASSUMED" -> "assumed: no star could be measured";
            default -> "";
        };
    }

    /** The faint-mover search's star width source, from its own telemetry. */
    static String source(FaintMoverSearchTelemetry telemetry) {
        return switch (telemetry.fwhmSource == null ? "" : telemetry.fwhmSource) {
            case "SETTING" -> "from the settings";
            case "MEASURED" -> "measured";
            case "ASSUMED" -> "assumed";
            default -> telemetry.fwhmFromStars ? "measured" : "assumed";
        };
    }

    /**
     * What set the faint-mover search's fastest motion, as a sentence, or null for a run that predates the automatic
     * choice in the engine.
     */
    static String fastestMotion(FaintMoverSearchTelemetry t) {
        if (t.maxSpeedLimit == null) return null;
        String speed = String.format(Locale.US, "%.3f px/min", t.maxSpeedPixelsPerMinute);
        String point = Double.isFinite(t.pointLikeMaxSpeedPixelsPerMinute)
                ? String.format(Locale.US, "%.3f px/min", t.pointLikeMaxSpeedPixelsPerMinute) : "unknown";
        String estimate = Double.isFinite(t.estimatedSeconds)
                ? String.format(Locale.US, " Estimated search time %.0f s of the %.0f s limit, from a %.1f s timed sample.",
                        t.estimatedSeconds, t.timeLimitSecondsApplied, t.sampleMs / 1000.0) : "";
        return switch (t.maxSpeedLimit) {
            case "POINT_LIKE" -> "Fastest motion " + speed + ", chosen automatically: 2 star widths per exposure, the fastest a "
                    + "mover stays point-like at." + estimate;
            case "MOTION_LIMIT" -> String.format(Locale.US, "Fastest motion %s, chosen automatically: 2 star widths per exposure "
                    + "would be %s, lowered to 90 %% of the %,d-motion limit of the first pass.", speed, point, t.maxMotionsApplied) + estimate;
            case "MEMORY" -> "Fastest motion " + speed + ", chosen automatically: 2 star widths per exposure would be " + point
                    + ", lowered so that one search tile fits in a quarter of the memory." + estimate;
            case "TIME_LIMIT" -> String.format(Locale.US, "Fastest motion %s, chosen automatically: lowered from %.3f px/min "
                    + "(2 star widths per exposure would be %s) to finish within the time limit; the time limit never lowers it "
                    + "below Fastest motion.", speed, t.speedBeforeTimeLimitPixelsPerMinute, point) + estimate;
            case "NO_EXPOSURE_TIMES" -> "Fastest motion " + speed + ", the Fastest motion setting: the frames have no exposure "
                    + "times for the automatic choice.";
            default -> "Fastest motion " + speed + ", the Fastest motion setting (automatic fastest motion off).";
        };
    }
}
