/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.photometry.PhotometryFlags;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emits the variable-star photometry section: readiness verdict and checks, session diagnostics
 * charts, candidate cards with light curves, the per-frame table, and CSV exports.
 */
final class PhotometryReportSectionWriter {

    /** Most candidate cards rendered with charts and cutouts; the rest are listed in the table. */
    private static final int MAX_CANDIDATE_CARDS = 40;
    private static final int COMPARISON_STARS = 3;
    private static final double COMPARISON_MAG_WINDOW = 0.5;
    private static final int CUTOUT_DISPLAY_SIZE = 150;
    /** Catalogue search radius: this many pixels, kept between the two limits below. */
    private static final double SEARCH_RADIUS_PIXELS = 4.0;
    private static final double MIN_SEARCH_RADIUS_ARCSEC = 10.0;
    private static final double MAX_SEARCH_RADIUS_ARCSEC = 60.0;
    /** Most constant stars drawn in the noise chart; candidates are always drawn. */
    private static final int MAX_CONSTANT_CHART_POINTS = 3000;

    /** What the check is asking, independent of this star. Shown in the mouse-over. */
    static String gateQuestion(String gate) {
        switch (gate) {
            case "DATA":
                return "Are there enough measurements, spread over enough time, to trust a change?";
            case "AMPLITUDE":
                return "Is the brightness change larger than the smallest change this session reports, and larger than the noise?";
            case "PERSISTENCE":
                return "Does the change last for several frames in a row, rather than one odd frame?";
            case "SPLIT_HALF":
                return "Do consecutive measurements tell the same story, or do they jump like noise?";
            case "APERTURE":
                return "Does a smaller measuring circle see the same size of change as a larger one? A difference usually means light from a neighbour.";
            case "SYSTEMATICS":
                return "Does this star fade and brighten together with the whole frame: its transparency, star width, sky background, or alignment shift?";
            case "LOCAL":
                return "Do the stars next to this one fade and brighten the same way? If they do, the change is in that part of the picture, not in this star.";
            case "LINEARITY":
                return "Did the star stay faint enough that the camera recorded it faithfully?";
            default:
                return gate;
        }
    }

    /**
     * How this star did on the check, as a short clause. A local failure says which of the two causes it was:
     * the neighbours share the pattern, or there were not enough neighbours to test.
     */
    static String gateOutcome(String gate, boolean failed, int localComparisonStars) {
        if ("LOCAL".equals(gate)) {
            if (!failed) {
                return "the stars next to it do not follow the same pattern";
            }
            if (localComparisonStars < 3) {
                return "there are not enough steady stars nearby to test whether the pattern is shared";
            }
            return "the stars next to it fade and brighten the same way, so the change is shared with that part of the picture";
        }
        switch (gate) {
            case "DATA":
                return failed ? "too few measurements, or they cover too little time" : "enough measurements over a long enough time";
            case "AMPLITUDE":
                return failed ? "the change is smaller than the minimum, or no larger than the noise" : "the change is above the minimum and above the noise";
            case "PERSISTENCE":
                return failed ? "the change does not last for several frames in a row" : "the change lasts for several frames in a row";
            case "SPLIT_HALF":
                return failed ? "consecutive measurements do not agree" : "consecutive measurements agree";
            case "APERTURE":
                return failed ? "a small and a large measuring circle see different changes" : "a small and a large measuring circle see the same change";
            case "SYSTEMATICS":
                return failed ? "the change follows the transparency, star width, sky or alignment of the frame"
                        : "the change does not follow the transparency, star width, sky or alignment of the frame";
            case "LINEARITY":
                return failed ? "the star was saturated, or brighter than the camera records faithfully, in at least one frame"
                        : "the star stayed below saturation and inside the range the camera records faithfully";
            default:
                return gate;
        }
    }

    private static String gateTooltip(String gate, boolean failed, int localComparisonStars) {
        return gateQuestion(gate) + " This star " + (failed ? "failed" : "passed") + ": "
                + gateOutcome(gate, failed, localComparisonStars) + ".";
    }

    private PhotometryReportSectionWriter() {
    }

    static void writeSection(PrintWriter report,
                             DetectionReportContext context,
                             VariableStarAnalysis analysis) throws IOException {
        if (context.config == null || !context.config.enableVariableStarDetection || analysis == null) {
            report.println("<div class='panel'>");
            report.println("<h2>Variable-Star Photometry</h2>");
            report.println("<p class='compact-note'>Variable-star photometry was disabled for this session. Enable it in the Variable Stars configuration tab.</p>");
            report.println("</div>");
            return;
        }

        PipelineTelemetry.PhotometryTelemetry telemetry = analysis.telemetry;
        writeOverview(report, context, analysis, telemetry);
        writeReadinessChecks(report, context, telemetry);
        if (!"NOT_READY".equals(telemetry.verdict)) {
            if (!analysis.frames.isEmpty()) {
                writeSessionCharts(report, context, analysis);
            }
            writeCandidates(report, context, analysis);
            writeFrameTable(report, telemetry);
        }
        writeCsvExports(context.exportDir, analysis);
    }

    // =================================================================
    // Overview and readiness
    // =================================================================

    private static void writeOverview(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                                      PipelineTelemetry.PhotometryTelemetry t) {
        boolean notReady = "NOT_READY".equals(t.verdict);
        report.println("<div class='panel'>");
        report.println("<h2>Variable Stars</h2>");
        report.println("<p class='section-lede'>Brightness of the isolated stars, compared with all the other measured stars, so a cloud that dims the whole frame cancels out. "
                + "A star is reported when it jumps more than stars of the same brightness, the jumps line up from frame to frame, and it passes the checks on its card. "
                + "<a href='photometry_stars.csv' target='_blank'>[All stars CSV]</a> "
                + "<a href='photometry_lightcurves.csv' target='_blank'>[Candidate light curves CSV]</a> "
                + "<a href='photometry_frames.csv' target='_blank'>[Per-frame CSV]</a></p>");

        report.println("<div class='flex-container'>");
        report.println("<div class='metric-box'" + tipAttr("Whether the pictures look linear enough to trust a brightness change. Ready: the checks found no problem. Limited: only the larger changes are reported. Not ready: nothing was scored.")
                + " style='border-left-color:" + verdictColor(t.verdict) + ";'><span class='metric-value'>"
                + verdictBadge(t.verdict) + "</span><span class='metric-label'>Readiness</span></div>");
        report.println(metricBox(t.framesUsed + " <span style='color:#777; font-size:16px;'>/ " + t.framesAnalyzed + "</span>", "Frames used / looked at",
                "Frames that stayed in the variable-star measurement, out of the frames that passed the earlier quality filter."));
        report.println(metricBox(String.valueOf(t.starsSelected), "Stars measured",
                "Isolated stars whose brightness was measured in every frame."));
        if (!notReady) {
            report.println(metricBox(String.valueOf(t.starsScored), "Stars tested",
                "Measured stars with enough good frames to be compared with stars of the same brightness."));
            report.println(metricBox(String.valueOf(t.highConfidence), "High-confidence variables",
                "Stars that stood out and passed every check."));
            report.println(metricBox(String.valueOf(t.possible), "Possible variables",
                "Stars that stood out but failed exactly one check. The card says which."));
        }
        report.println(metricBox(formatMag(t.medianLinearRangeMag, 1), "Trusted range (mag)",
                "How many magnitudes of star brightness still look linear. A longer range means more of the stars can be trusted."));
        report.println(metricBox(formatMag(t.zeroPointRangeMag, 3), "Transparency change (mag)",
                "How much the whole field got brighter and fainter during the night, ignoring the most extreme frames."));
        report.println("</div>");

        report.println(verdictExplanationHtml(context, t));
        report.println("<div class='astro-note'><strong>Ready</strong> means the checks found no sign that the pictures distort brightness. It does not prove they are perfect: a stretch applied the same way to every pixel can still pass. "
                + "<strong>Limited</strong> sessions only report changes of at least " + formatMag(context.config.variableLimitedMinAmplitudeMag, 2) + " mag. "
                + "<strong>Not ready</strong> sessions are not scored. The measurements are still saved so the cause can be checked.</div>");
        report.println("</div>");
    }

    /** The reason for the verdict in plain words, and for a Not ready session what could help. */
    private static String verdictExplanationHtml(DetectionReportContext context, PipelineTelemetry.PhotometryTelemetry t) {
        StringBuilder html = new StringBuilder("<div style='background:#2b2b2b; border-radius:6px; padding:12px 16px; margin: 4px 0 12px 0; font-size:14px; line-height:1.55; border-left:4px solid "
                + verdictColor(t.verdict) + ";'>");
        if ("READY".equals(t.verdict)) {
            html.append("<strong>Ready:</strong> every readiness check passed, so all scored stars could be reported.");
        } else if ("LIMITED".equals(t.verdict)) {
            List<String> reasons = new ArrayList<>();
            if ("LIMITED".equals(t.quantisationCheck)) {
                reasons.add("sky noise is clipped at zero in " + formatPercent(t.medianFloorClippedFraction) + " of the sky pixels, which biases faint stars");
            }
            if ("LIMITED".equals(t.shapeLinearityCheck)) {
                reasons.add("the verified linear range is only " + formatMag(t.medianLinearRangeMag, 1) + " mag ("
                        + formatMag(context.config.linearityMinRangeMag + 1.0, 1) + " mag is needed for Ready)");
            }
            if ("INCONCLUSIVE".equals(t.responseCheck)) {
                reasons.add(Double.isFinite(t.zeroPointRangeMag)
                        ? "transparency barely changed during the session (" + formatMag(t.zeroPointRangeMag, 3) + " mag, at least "
                        + formatMag(context.config.linearityMinZeroPointRangeMag, 2) + " mag is needed), so the response check could not confirm that bright and faint stars respond alike"
                        : "the response check could not be run");
            }
            html.append("<strong>Limited because</strong> ").append(reasons.isEmpty() ? "a readiness check was inconclusive" : String.join("; ", reasons))
                    .append(". Only variables with an amplitude of at least ").append(formatMag(context.config.variableLimitedMinAmplitudeMag, 2)).append(" mag are reported.");
        } else if ("NOT_READY".equals(t.verdict)) {
            html.append("<strong>Not ready:</strong> no star was scored, because:<ul style='margin:6px 0 4px 18px; padding:0;'>");
            for (String message : t.readinessMessages) {
                html.append("<li>").append(DetectionReportGenerator.escapeHtml(message)).append("</li>");
            }
            html.append("</ul>");
            List<String> actions = new ArrayList<>();
            boolean stretched = false;
            boolean shortRange = false;
            for (String message : t.readinessMessages) {
                stretched |= message.contains("stretched or non-linear");
                shortRange |= message.contains("needed to verify linearity");
            }
            if ("FAIL".equals(t.quantisationCheck)) {
                actions.add("The frames look 8-bit or heavily quantised: use the original 16- or 32-bit data.");
            }
            if (stretched) {
                actions.add("Use the original, unstretched frames. DSLR frames need a linear raw conversion, without a tone curve.");
            }
            if (shortRange) {
                actions.add("Longer exposures or a richer star field give the star-shape check a longer magnitude range to verify.");
            }
            if ("FAIL".equals(t.responseCheck)) {
                actions.add("Bright and faint stars respond differently to transparency changes: check the calibration and the raw conversion.");
            }
            if (t.framesUsed < context.config.variableMinFrames) {
                actions.add("At least " + context.config.variableMinFrames + " usable frames are needed after the checks: capture a longer sequence.");
            }
            if (!actions.isEmpty()) {
                html.append("<strong>What can help:</strong><ul style='margin:6px 0 0 18px; padding:0;'>");
                for (String action : actions) {
                    html.append("<li>").append(DetectionReportGenerator.escapeHtml(action)).append("</li>");
                }
                html.append("</ul>");
            }
        } else {
            html.append("Photometry did not run for this session.");
        }
        return html.append("</div>").toString();
    }

    private static void writeReadinessChecks(PrintWriter report, DetectionReportContext context,
                                             PipelineTelemetry.PhotometryTelemetry t) {
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Variable Stars: Readiness Checks</h2>");
        report.println("<p class='compact-note'>Photometry needs a picture in which a star that is really twice as bright records twice the signal. "
                + "These three checks look for signs that it does not. Hover a row in the full list below for the same idea in a few more words.</p>");
        report.println("<div style='display:grid; grid-template-columns: max-content max-content 1fr; gap: 8px 14px; align-items: baseline; font-size: 13px; margin-bottom: 14px;'>");
        report.println(checkRow(t.quantisationCheck, "A &middot; Data depth",
                "The pixels need many brightness steps, and the sky must not be crushed to zero. A crushed sky makes faint stars look too bright.",
                t.distinctPixelLevels + " different brightness steps; " + formatPercent(t.medianFloorClippedFraction) + " of sky pixels sit at zero."));
        report.println(checkRow(t.shapeLinearityCheck, "B &middot; Star shapes",
                "A star that is too bright for the camera stops growing in the middle and looks flatter. This finds that brightness and sets brighter stars aside.",
                "About " + formatMag(t.medianLinearRangeMag, 2) + " magnitudes stay trustworthy. Stars brighter than instrumental mag "
                        + formatMag(t.medianLinearLimitMag, 2) + " are set aside. " + t.framesFailingShapeLinearity + " of " + t.framesAnalyzed + " frames failed this check."));
        report.println(checkRow(t.responseCheck, "D &middot; Same response",
                "Cloud or haze should dim every star by the same amount. If faint stars change more than bright ones, the magnitudes cannot be trusted.",
                "The night got brighter and fainter by " + formatMag(t.zeroPointRangeMag, 3) + " mag (at least " + formatMag(context.config.linearityMinZeroPointRangeMag, 2)
                        + " mag is needed before this check can judge). " + t.framesFailingResponse + " frames failed."));
        report.println("</div>");
        // A Not ready verdict already lists these messages as its reasons.
        if (!t.readinessMessages.isEmpty() && !"NOT_READY".equals(t.verdict)) {
            report.println("<ul style='margin: 0 0 12px 18px; padding: 0; font-size: 13px; line-height: 1.5;'>");
            for (String message : t.readinessMessages) {
                report.println("<li>" + DetectionReportGenerator.escapeHtml(message) + "</li>");
            }
            report.println("</ul>");
        }
        report.println(starFunnelHtml(t));
        report.println(frameFunnelHtml(t));
        report.println(apertureChoicesHtml(t));
        long flagged = t.measurementsSaturated + t.measurementsNonlinear + t.measurementsCrossing + t.measurementsEdgeOrVoid
                + t.measurementsOutlier + t.measurementsContaminated;
        if (flagged > 0) {
            report.println("<div class='astro-note'>Single measurements set aside: " + t.measurementsSaturated + " saturated, " + t.measurementsNonlinear + " non-linear, "
                    + t.measurementsCrossing + " crossed by a moving object, " + t.measurementsEdgeOrVoid + " at an edge, "
                    + t.measurementsOutlier + " isolated outliers, " + t.measurementsContaminated + " with a distorted shape (hot pixel, cosmic ray).</div>");
        }
        report.println("<details class='foldable-streak-details'><summary>All readiness figures</summary><div class='foldable-streak-body'>");
        report.println("<p class='compact-note'>The short name is what the number is. Hover a row for what it means for the measurements.</p>");
        report.println("<div class='compact-threshold-grid'>");
        report.println(configItem("A · Brightness steps", statusBadge(t.quantisationCheck),
                "Pass means the pixels have enough different brightness values, and the sky is not crushed to zero."));
        report.println(configItem("A · Different brightness values", String.valueOf(t.distinctPixelLevels),
                "How many different pixel values the middle frame uses. A real 16-bit frame has thousands. An 8-bit or heavily rounded frame has few."));
        report.println(configItem("A · Sky pixels at zero", formatPercent(t.medianFloorClippedFraction),
                "Share of the sky that sits on the lowest value the file can store. Those pixels hide the real sky, so faint stars look too bright."));
        report.println(configItem("B · Star shapes", statusBadge(t.shapeLinearityCheck),
                "Pass means bright stars still have the same shape as faint ones. Where bright stars go flat, the camera is no longer recording them faithfully."));
        report.println(configItem("B · Brightest star still trusted", formatMag(t.medianLinearLimitMag, 2),
                "Instrumental magnitude where stars start to flatten. Brighter than this (a smaller number) is set aside. Not a catalogue magnitude."));
        report.println(configItem("B · Trusted range", formatMag(t.medianLinearRangeMag, 2) + " mag",
                "How many magnitudes fainter than that limit still look linear. A longer range means more of the stars can be measured."));
        report.println(configItem("B · Frames that failed the shape check", String.valueOf(t.framesFailingShapeLinearity),
                "Frames where bright stars were already flat across the whole range, so the frame itself does not look linear."));
        report.println(configItem("D · Same response", statusBadge(t.responseCheck),
                "Pass means faint and bright stars dimmed by the same amount when the sky transparency changed."));
        report.println(configItem("D · Transparency change", formatMag(t.zeroPointRangeMag, 3) + " mag",
                "How much the whole field got brighter and fainter during the night, ignoring the most extreme frames. The check needs a real change before it can judge."));
        report.println(configItem("D · Response versus transparency", formatMag(t.slopeZeroPointCorrelation, 2),
                "From -1 to 1. Near 1 or -1 means the faint-versus-bright difference grows when the transparency changes, which a linear picture should not do."));
        report.println(configItem("D · Response versus sky", formatMag(t.slopeSkyCorrelation, 2),
                "From -1 to 1. Near 1 or -1 means the faint-versus-bright difference follows the sky background, a sign of stretching or a sky-subtraction problem."));
        report.println(configItem("D · Frames that failed the response check", String.valueOf(t.framesFailingResponse),
                "Frames where faint stars changed by a different amount than bright stars."));
        report.println(configItem("Typical star width", formatMag(t.sessionFwhm, 2) + " px",
                "How wide a typical star is, in pixels, from the star measurement used for detection. Wider means blurrier seeing or worse focus."));
        report.println(configItem("Saturation level", formatMag(t.saturationLevel, 0) + " ADU",
                "The pixel value where the camera stops getting brighter. Stars near it are left out."));
        report.println(configItem("Stars left out: crowded", String.valueOf(t.starsRejectedCrowded),
                "Stars with a neighbour close enough that the measuring circle would mix their light."));
        report.println(configItem("Stars left out: saturated on the master", String.valueOf(t.starsRejectedSaturated),
                "Stars already too bright on the combined master image."));
        report.println(configItem("Stars left out: edge", String.valueOf(t.starsRejectedEdgeOrVoid),
                "Stars too close to the edge, or inside an area the pipeline leaves empty."));
        report.println(configItem("Stars left out: elongated / on a streak", t.starsRejectedElongated + " / " + t.starsRejectedStreak,
                "Elongated stars, and stars that sit on a moving streak, are not used as steady comparison stars."));
        report.println(configItem("Stars left out: over the cap", String.valueOf(t.starsRejectedByCap),
                "Extra stars beyond the maximum number photometry measures. The brightest isolated ones are kept."));
        report.println(configItem("Stars left out: non-linear in most frames", String.valueOf(t.starsExcludedMostlyNonlinear),
                "Stars that were saturated or past the trusted brightness in more than half of their measurements."));
        report.println(configItem("Stars left out: too faint", String.valueOf(t.starsExcludedLowSnr),
                "Stars whose typical measurement is noisier than the minimum signal-to-noise."));
        report.println(configItem("Frames left out: alignment", String.valueOf(t.framesExcludedRegistration),
                "Frames whose stars did not agree on how far the frame had to be shifted to line up."));
        report.println(configItem("Frames left out: star shape (B)", String.valueOf(t.framesExcludedShapeLinearity),
                "Frames that failed the bright-star shape check."));
        report.println(configItem("Frames left out: response (D)", String.valueOf(t.framesExcludedResponse),
                "Frames where faint stars responded differently from bright stars."));
        report.println(configItem("Frames left out: too few stars", String.valueOf(t.framesExcludedTooFewStars),
                "Frames without enough usable stars to solve the comparison."));
        report.println(configItem("Measurements left out: saturated / too bright", t.measurementsSaturated + " / " + t.measurementsNonlinear,
                "Single measurements, not whole stars. Saturated means the pixel hit the camera limit. Too bright means past the shape-check limit."));
        report.println(configItem("Measurements left out: a mover crossed the star", String.valueOf(t.measurementsCrossing),
                "A moving object passed over the star in that frame, so the brightness is not the star alone."));
        report.println(configItem("Measurements left out: edge", String.valueOf(t.measurementsEdgeOrVoid),
                "The star fell at the edge or in an empty area of that frame."));
        report.println(configItem("Measurements left out: one odd frame", String.valueOf(t.measurementsOutlier),
                "A single frame far from the star's other measurements, with no neighbour frame to support it. A dip that lasts several frames is kept."));
        report.println(configItem("Measurements left out: distorted shape", String.valueOf(t.measurementsContaminated),
                "The star's shape in that frame does not match its usual shape. Often a hot pixel or a cosmic ray."));
        report.println(configItem("Time spent measuring", String.format(Locale.US, "%.2f s", t.processingTimeMs / 1000.0),
                "How long the variable-star measurement took."));
        report.println("</div>");
        report.println("</div></details>");
        if (!t.gateFailureCounts.isEmpty()) {
            StringBuilder gates = new StringBuilder();
            for (Map.Entry<String, Integer> e : t.gateFailureCounts.entrySet()) {
                if (gates.length() > 0) gates.append(", ");
                gates.append(e.getKey()).append(": ").append(e.getValue());
            }
            report.println("<div class='astro-note'" + tipAttr("How many candidate stars failed each check. A star can fail more than one. Hover the checks on a candidate card for what each name asks.")
                    + ">Checks failed by candidates: " + DetectionReportGenerator.escapeHtml(gates.toString()) + "</div>");
        }
        report.println("</div>");
    }

    private static String checkRow(String status, String name, String tests, String result) {
        return "<div>" + statusBadge(status) + "</div><div style='color:#ffffff; font-weight:600; white-space:nowrap;'>" + name + "</div>"
                + "<div><span style='color:#aab4bd;'>" + DetectionReportGenerator.escapeHtml(tests) + "</span><br>"
                + DetectionReportGenerator.escapeHtml(result) + "</div>";
    }

    /** Stars from the master map to the scored set, as proportional bars with the reasons for each drop. */
    private static String starFunnelHtml(PipelineTelemetry.PhotometryTelemetry t) {
        int selectionRejects = t.starsRejectedCrowded + t.starsRejectedSaturated + t.starsRejectedEdgeOrVoid
                + t.starsRejectedElongated + t.starsRejectedStreak + t.starsRejectedByCap;
        int master = Math.max(t.masterStarsConsidered, t.starsSelected + selectionRejects);
        if (master <= 0) {
            return "";
        }
        int otherUnscored = Math.max(0, t.starsSelected - t.starsScored - t.starsExcludedLowSnr - t.starsExcludedMostlyNonlinear);
        StringBuilder selection = new StringBuilder();
        appendReason(selection, t.starsRejectedCrowded, "crowded");
        appendReason(selection, t.starsRejectedElongated, "elongated");
        appendReason(selection, t.starsRejectedStreak, "on a streak");
        appendReason(selection, t.starsRejectedEdgeOrVoid, "at an edge");
        appendReason(selection, t.starsRejectedSaturated, "saturated");
        appendReason(selection, t.starsRejectedByCap, "over the star cap");
        StringBuilder scoring = new StringBuilder();
        if ("NOT_READY".equals(t.verdict)) {
            scoring.append("no star is scored when the session is not ready");
        } else {
            appendReason(scoring, t.starsExcludedLowSnr, "too faint (below the minimum SNR)");
            appendReason(scoring, t.starsExcludedMostlyNonlinear, "mostly non-linear");
            appendReason(scoring, otherUnscored, "too few usable measurements");
        }
        return "<div style='font-size:12px; margin: 4px 0 12px 0;'><div style='color:#e6e6e6; font-weight:600; margin-bottom:6px;'>From the master map to scored stars</div>"
                + funnelBar(master, master, "#5b6670", master + " stars in the master map", "")
                + funnelBar(t.starsSelected, master, "#4f8fd0", t.starsSelected + " isolated enough to measure", selection.toString())
                + funnelBar(t.starsScored, master, PhotometrySvgChart.SERIES_BLUE, t.starsScored + " scored", scoring.toString())
                + "</div>";
    }

    /** Frames analysed by photometry and the frames each check set aside. */
    private static String frameFunnelHtml(PipelineTelemetry.PhotometryTelemetry t) {
        if (t.framesAnalyzed <= 0) {
            return "";
        }
        StringBuilder reasons = new StringBuilder();
        appendReason(reasons, t.framesExcludedShapeLinearity, "star shapes (B)");
        appendReason(reasons, t.framesExcludedResponse, "response (D)");
        appendReason(reasons, t.framesExcludedRegistration, "registration");
        appendReason(reasons, t.framesExcludedTooFewStars, "too few stars");
        return "<div style='font-size:12px; margin: 4px 0 12px 0;'><div style='color:#e6e6e6; font-weight:600; margin-bottom:6px;'>Frames</div>"
                + funnelBar(t.framesAnalyzed, t.framesAnalyzed, "#5b6670", t.framesAnalyzed + " analysed", "")
                + funnelBar(t.framesUsed, t.framesAnalyzed, PhotometrySvgChart.SERIES_BLUE, t.framesUsed + " used", reasons.toString())
                + "</div>";
    }

    /**
     * The measuring aperture of each brightness range, merged where neighbouring ranges share it, with the scatter
     * reduction against the main aperture.
     */
    private static String apertureChoicesHtml(PipelineTelemetry.PhotometryTelemetry t) {
        if (t.apertureChoices.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int k = 1; k <= t.apertureChoices.size(); k++) {
            boolean end = k == t.apertureChoices.size() || t.apertureChoices.get(k).fwhmFactor != t.apertureChoices.get(start).fwhmFactor;
            if (!end) {
                continue;
            }
            PipelineTelemetry.PhotometryApertureChoice first = t.apertureChoices.get(start);
            PipelineTelemetry.PhotometryApertureChoice last = t.apertureChoices.get(k - 1);
            double best = 0;
            for (int i = start; i < k; i++) {
                best = Math.min(best, t.apertureChoices.get(i).scatterChange);
            }
            parts.add(String.format(Locale.US, "%.2f to %.2f: <strong>%.1f&times; FWHM</strong> (%.1f px)%s", first.magFrom, last.magTo,
                    first.fwhmFactor, first.radiusPixels, best < 0 ? String.format(Locale.US, ", up to %.0f%% less scatter", -100 * best) : ""));
            start = k;
        }
        return "<div class='astro-note'>Measuring aperture by brightness (instrumental mag), chosen from the scatter of the constant stars: "
                + String.join("; ", parts) + ".</div>";
    }

    private static void appendReason(StringBuilder text, int count, String reason) {
        if (count <= 0) {
            return;
        }
        text.append(text.length() == 0 ? "set aside: " : ", ").append(count).append(' ').append(reason);
    }

    private static String funnelBar(int value, int total, String color, String label, String note) {
        double percent = total > 0 ? Math.max(0.6, 100.0 * value / total) : 0;
        return "<div style='display:flex; align-items:center; gap:10px; margin:3px 0;'>"
                + "<div style='flex: 0 0 360px; background:#262626; border-radius:3px; height:14px;'><div style='width:"
                + String.format(Locale.US, "%.1f", percent) + "%; height:100%; background:" + color + "; border-radius:3px;'></div></div>"
                + "<div><span style='color:#ffffff;'>" + DetectionReportGenerator.escapeHtml(label) + "</span>"
                + (note.isEmpty() ? "" : " <span style='color:#99a3ad;'>(" + DetectionReportGenerator.escapeHtml(note) + ")</span>") + "</div></div>";
    }

    // =================================================================
    // Session charts
    // =================================================================

    private static void writeSessionCharts(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis) {
        PipelineTelemetry.PhotometryTelemetry t = analysis.telemetry;
        List<PipelineTelemetry.PhotometryFrameStat> frames = analysis.frames;
        report.println("<div class='panel'>");
        report.println("<h2>Variable Stars: Session Diagnostics</h2>");

        // --- Noise model ---
        if (!t.noiseModel.isEmpty()) {
            report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:4px 0 6px 0;'>Noise model: scatter against brightness</h3>");
            int scored = t.noiseModel.size();
            String sampleNote = scored > MAX_CONSTANT_CHART_POINTS
                    ? " Every candidate is drawn; constant stars are thinned to an evenly spaced sample of about " + MAX_CONSTANT_CHART_POINTS
                    + " of the " + scored + " scored stars (all are in photometry_stars.csv)."
                    : "";
            report.println("<p class='compact-note'>Each dot is one measured star. Left is brighter, right is fainter. Height is how much that star's brightness jumps around, in magnitudes. "
                    + "The dashed line is the jumpiness of an ordinary steady star of that brightness. A real variable sits well above the line. Hover a dot for the star and its numbers." + sampleNote + "</p>");
            report.println(legend(
                    legendDot(PhotometrySvgChart.CONTEXT_GRAY, "Constant"),
                    legendDot(PhotometrySvgChart.SERIES_BLUE, "High confidence"),
                    legendDot(PhotometrySvgChart.SERIES_ORANGE, "Possible"),
                    legendDot(PhotometrySvgChart.SERIES_AQUA, "Rejected candidate"),
                    legendDash(PhotometrySvgChart.REFERENCE_INK, "Expected scatter")));
            report.println(noiseChart(analysis));
        }

        // --- Per-frame small multiples ---
        report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Per-frame diagnostics</h3>");
        report.println("<p class='compact-note'>One chart per question about the frames. The horizontal axis is the frame number. A blue point is a frame that was used. A red cross is a frame left out; hover it for the file name and why. "
                + "The sentence under each title says what the height of the point means.</p>");
        report.println(legend(
                legendDot(PhotometrySvgChart.SERIES_BLUE, "Used frame"),
                legendCross(PhotometrySvgChart.STATUS_CRITICAL, "Frame left out"),
                legendDash(PhotometrySvgChart.REFERENCE_INK, "Limit")));
        report.println("<div class='flex-container' style='gap:14px;'>");
        report.println(frameChart(frames, "Whole-frame brightness",
                "How much this whole frame is brighter or fainter than a typical frame of the night, in magnitudes. Positive means fainter. This is the zero point. Drawn brighter-up.",
                "mag", f -> f.zeroPoint, true, Double.NaN, Double.NaN, null));
        double slopeLimit = context.config.linearityMaxFrameSlope;
        report.println(frameChart(frames, "Faint stars versus bright stars",
                "Whether faint stars changed more than bright stars in this frame. Near zero is what a linear picture does. This is check D's slope, in magnitudes of error per magnitude of brightness. The dashed lines are the limit.",
                "mag/mag", f -> f.responseSlope, false, -slopeLimit, slopeLimit, "limit"));
        report.println(frameChart(frames, "Trusted brightness range",
                "How many magnitudes of stars in this frame still have the right shape. Longer is better. This is check B. The dashed line is the minimum the session wants.",
                "mag", f -> f.linearRangeMag, false, context.config.linearityMinRangeMag, Double.NaN, "min"));
        report.println(frameChart(frames, "Star width",
                "How wide the stars are in this frame, in pixels. A spike is a blurry frame. This is the FWHM.",
                "px", f -> f.fwhm, false, Double.NaN, Double.NaN, null));
        report.println(frameChart(frames, "Alignment disagreement",
                "How far the stars disagreed about the shift needed to line this frame up, in pixels. A large value means a poor alignment. The dashed line is the maximum that is still used.",
                "px", f -> f.registrationSpread, false,
                context.config.photometryMaxRegistrationSpreadPixels, Double.NaN, "max"));
        report.println(frameChart(frames, "Stars a mover crossed",
                "How many measured stars a moving object passed over in this frame. Those measurements are left out, because the brightness is not the star alone.",
                "stars", f -> (double) f.crossing, false, Double.NaN, Double.NaN, null, true));
        report.println("</div>");

        // --- Check B profiles ---
        report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Check B: does a bright star still have the shape of a faint one?</h3>");
        report.println("<p class='compact-note'>Each line is one frame. Left is brighter, right is fainter. Height is how concentrated the star's light is, compared with the faint stars in that same frame. "
                + "A linear frame stays inside the shaded band. A frame that was stretched, or whose bright stars are saturating, bends away at the bright end. Hover a line for that frame's result.</p>");
        report.println(legend(
                legendLine(PhotometrySvgChart.SERIES_BLUE, "Frame passed B"),
                legendLine(PhotometrySvgChart.STATUS_CRITICAL, "Frame failed B")));
        report.println(concentrationChart(frames, context.config.linearityMaxConcentrationDrift));
        report.println("</div>");
    }

    private static String noiseChart(VariableStarAnalysis analysis) {
        List<PipelineTelemetry.PhotometryNoisePoint> points = analysis.telemetry.noiseModel;
        PhotometrySvgChart chart = new PhotometrySvgChart(760, 360, "Scatter against instrumental magnitude for all scored stars",
                "Mean instrumental magnitude (fainter to the right)", "Scatter (mag, log)").logY();

        int lineStride = Math.max(1, points.size() / 600);
        int linePoints = (points.size() + lineStride - 1) / lineStride;
        double[] expX = new double[linePoints];
        double[] expY = new double[linePoints];
        for (int k = 0; k < linePoints; k++) {
            int i = k * lineStride;
            expX[k] = points.get(i).mag;
            expY[k] = smoothExpected(points, i, Math.max(5, lineStride));
        }

        Map<VariabilityTier, String> colors = new LinkedHashMap<>();
        colors.put(VariabilityTier.CONSTANT, PhotometrySvgChart.CONTEXT_GRAY);
        colors.put(VariabilityTier.REJECTED, PhotometrySvgChart.SERIES_AQUA);
        colors.put(VariabilityTier.POSSIBLE, PhotometrySvgChart.SERIES_ORANGE);
        colors.put(VariabilityTier.HIGH_CONFIDENCE, PhotometrySvgChart.SERIES_BLUE);

        Map<String, StarLightCurve> byKey = new LinkedHashMap<>();
        for (StarLightCurve star : analysis.stars) {
            byKey.put(key(star.meanMag, star.scatter), star);
        }
        int constantCount = 0;
        for (PipelineTelemetry.PhotometryNoisePoint p : points) {
            if (VariabilityTier.CONSTANT.name().equals(p.tier)) constantCount++;
        }
        // Points are sorted by magnitude, so a fixed stride keeps the sample spread over brightness.
        int constantStride = Math.max(1, (int) Math.ceil(constantCount / (double) MAX_CONSTANT_CHART_POINTS));
        for (Map.Entry<VariabilityTier, String> entry : colors.entrySet()) {
            List<double[]> xy = new ArrayList<>();
            List<String> tips = new ArrayList<>();
            int seen = 0;
            for (PipelineTelemetry.PhotometryNoisePoint p : points) {
                if (!entry.getKey().name().equals(p.tier)) continue;
                if (entry.getKey() == VariabilityTier.CONSTANT && (seen++ % constantStride) != 0) continue;
                xy.add(new double[]{p.mag, p.scatter});
                StarLightCurve star = byKey.get(key(p.mag, p.scatter));
                tips.add(String.format(Locale.US, "%s%s. Average brightness %.2f (larger is fainter). Jumpiness %.4f mag; a steady star of this brightness jumps about %.4f mag.",
                        star != null ? "Star #" + star.id + String.format(Locale.US, " at (%.0f, %.0f). ", star.x, star.y) : "",
                        tierLabel(entry.getKey()), p.mag, p.scatter, p.expectedScatter));
            }
            double[] xs = new double[xy.size()];
            double[] ys = new double[xy.size()];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = xy.get(i)[0];
                ys[i] = xy.get(i)[1];
            }
            boolean context = entry.getKey() == VariabilityTier.CONSTANT;
            chart.points(xs, ys, entry.getValue(), context ? 2.5 : 5, PhotometrySvgChart.Marker.CIRCLE, tips.toArray(new String[0]));
        }
        chart.line(expX, expY, PhotometrySvgChart.REFERENCE_INK, 2, true, "Expected scatter of a constant star");
        return chart.render();
    }

    /** Running median of the expected scatter so the reference line is smooth. */
    private static double smoothExpected(List<PipelineTelemetry.PhotometryNoisePoint> points, int index, int half) {
        int from = Math.max(0, index - half);
        int to = Math.min(points.size(), index + half + 1);
        double[] window = new double[to - from];
        for (int i = from; i < to; i++) {
            window[i - from] = points.get(i).expectedScatter;
        }
        return median(window);
    }

    private interface FrameValue {
        double get(PipelineTelemetry.PhotometryFrameStat frame);
    }

    private static String frameChart(List<PipelineTelemetry.PhotometryFrameStat> frames, String title, String explanation,
                                     String unit, FrameValue value, boolean invert, double limitA, double limitB, String limitLabel) {
        return frameChart(frames, title, explanation, unit, value, invert, limitA, limitB, limitLabel, false);
    }

    private static String frameChart(List<PipelineTelemetry.PhotometryFrameStat> frames, String title, String explanation,
                                     String unit, FrameValue value, boolean invert, double limitA, double limitB,
                                     String limitLabel, boolean counts) {
        int n = frames.size();
        double[] x = new double[n];
        double[] usedY = new double[n];
        double[] excludedY = new double[n];
        String[] usedTips = new String[n];
        String[] excludedTips = new String[n];
        for (int j = 0; j < n; j++) {
            PipelineTelemetry.PhotometryFrameStat f = frames.get(j);
            x[j] = f.frameIndex + 1;
            double v = value.get(f);
            String shown = counts ? String.format(Locale.US, "%.0f %s", v, unit) : formatMag(v, 4) + " " + unit;
            String tip = String.format(Locale.US, "Frame %d (%s): %s. %s %s", f.frameIndex + 1, f.filename, shown, explanation,
                    f.used ? "This frame is used." : "This frame is left out: " + f.exclusionReason);
            usedY[j] = f.used ? v : Double.NaN;
            excludedY[j] = f.used ? Double.NaN : v;
            usedTips[j] = tip;
            excludedTips[j] = tip;
        }
        PhotometrySvgChart chart = new PhotometrySvgChart(360, 190, title + ". " + explanation, "Frame", "");
        if (invert) {
            chart.invertY();
        }
        if (counts) {
            double max = 0;
            for (PipelineTelemetry.PhotometryFrameStat f : frames) {
                max = Math.max(max, value.get(f));
            }
            chart.yRange(0, Math.max(1, Math.ceil(max * 1.1)));
        }
        if (!Double.isNaN(limitA)) chart.horizontalLine(limitA, PhotometrySvgChart.REFERENCE_INK, true, limitLabel);
        if (!Double.isNaN(limitB)) chart.horizontalLine(limitB, PhotometrySvgChart.REFERENCE_INK, true, limitLabel);
        chart.line(x, usedY, PhotometrySvgChart.SERIES_BLUE, 2, false, null);
        chart.points(x, usedY, PhotometrySvgChart.SERIES_BLUE, 4, PhotometrySvgChart.Marker.CIRCLE, usedTips);
        chart.points(x, excludedY, PhotometrySvgChart.STATUS_CRITICAL, 4.5, PhotometrySvgChart.Marker.CROSS, excludedTips);
        return "<div><div style='font-size:12px; color:#cccccc;'>" + DetectionReportGenerator.escapeHtml(title) + "</div>"
                + "<div style='font-size:11px; color:#9aa3ab; max-width:360px; margin:0 0 4px 0; line-height:1.35;'>"
                + DetectionReportGenerator.escapeHtml(explanation) + "</div>" + chart.render() + "</div>";
    }

    private static String concentrationChart(List<PipelineTelemetry.PhotometryFrameStat> frames, double drift) {
        PhotometrySvgChart chart = new PhotometrySvgChart(760, 300,
                "How concentrated each star is, compared with the faint stars of the same frame. Left is brighter. A line that stays in the band is linear.",
                "Brightness (fainter to the right)", "Shape compared with faint stars");
        chart.band(-drift, drift, PhotometrySvgChart.REFERENCE_INK, 0.12);
        // Passing frames first so failing ones draw on top.
        for (boolean failingPass : new boolean[]{false, true}) {
            for (PipelineTelemetry.PhotometryFrameStat f : frames) {
                boolean failed = "FAIL".equals(f.shapeLinearityStatus);
                if (failed != failingPass || f.concentrationProfile.isEmpty() || Double.isNaN(f.concentrationReference)) continue;
                int n = f.concentrationProfile.size();
                double[] xs = new double[n];
                double[] ys = new double[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = f.concentrationProfile.get(i).mag;
                    ys[i] = f.concentrationProfile.get(i).concentration - f.concentrationReference;
                }
                String tip = String.format(Locale.US, "Frame %d (%s). Shape check %s. Stars brighter than instrumental mag %s are not trusted in this frame. The trusted range is %s mag. %s",
                        f.frameIndex + 1, f.filename, "FAIL".equals(f.shapeLinearityStatus) ? "failed: bright stars go flat" : f.shapeLinearityStatus.toLowerCase(Locale.ROOT),
                        formatMag(f.linearLimitMag, 2), formatMag(f.linearRangeMag, 2),
                        failed ? "This frame is left out of the variable-star measurement." : "A flat line inside the band is what linear data looks like.");
                chart.line(xs, ys, failed ? PhotometrySvgChart.STATUS_CRITICAL : PhotometrySvgChart.SERIES_BLUE,
                        failed ? 2 : 1.2, false, tip);
            }
        }
        return chart.render();
    }

    // =================================================================
    // Candidates
    // =================================================================

    private static void writeCandidates(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis) throws IOException {
        List<StarLightCurve> cards = new ArrayList<>();
        List<StarLightCurve> rejected = new ArrayList<>();
        for (StarLightCurve star : analysis.candidates) {
            if (star.tier == VariabilityTier.REJECTED) {
                rejected.add(star);
            } else {
                cards.add(star);
            }
        }

        report.println("<div class='panel' id='variable-candidates'>");
        report.println("<h2>Variable Stars: Candidates</h2>");
        if (!analysis.readiness.allowsScoring()) {
            report.println("<p>The session did not pass the readiness checks, so no star was scored. The verdict above explains why.</p>");
            report.println("</div>");
            return;
        }
        if (cards.isEmpty()) {
            report.println("<p>No star passed the variability scoring and the checks in this session.</p>");
        } else {
            report.println("<p class='compact-note'>High-confidence candidates passed every check; possible candidates failed exactly one. "
                    + "Each light curve is drawn with three constant stars of similar brightness offset below it, on the same scale, so you can see what a constant star looks like in this session. "
                    + "Cutouts show the star in its brightest and faintest usable frame with the same display stretch.</p>");
            SessionCatalogue sessionCatalogue = SessionCatalogue.of(context);
            writeCandidateTable(report, context, analysis, cards, sessionCatalogue);
            int shown = 0;
            for (StarLightCurve star : cards) {
                if (shown++ >= MAX_CANDIDATE_CARDS) break;
                writeCandidateCard(report, context, analysis, star, shown, sessionCatalogue);
            }
            if (cards.size() > MAX_CANDIDATE_CARDS) {
                report.println("<p class='compact-note'>" + (cards.size() - MAX_CANDIDATE_CARDS) + " more candidates are listed in photometry_stars.csv.</p>");
            }
        }

        if (!rejected.isEmpty()) {
            report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Rejected candidates (" + rejected.size() + ")</h3>");
            report.println("<p class='compact-note'>Stars whose scores stood out but that failed two or more gates. Listed so you can see why a suspected variable was not reported.</p>");
            report.println("<div class='scroll-box compact-table-box'>");
            report.println("<table><thead><tr>"
                + th("Star", "Star number in this session's measurement.")
                + th("Position", "Where the star is, in pixels and on the sky when the field is solved.")
                + th("Mean Mag", "Average brightness in this session. A larger number is fainter. Not a catalogue magnitude.")
                + th("Amplitude", "Size of the brightness change, in magnitudes.")
                + th("Scatter z", "How unusual the jumpiness is among stars of the same brightness. Larger is more unusual.")
                + th("Stetson J z", "How unusual it is that neighbouring frames move together. One noisy frame does not raise this.")
                + th("Failed Gates", "The checks this star failed. Two or more, and it is listed here instead of as a candidate.")
                + th("Look Up", "Opens the same sky position in a variable-star catalogue.")
                + "</tr></thead><tbody>");
            for (StarLightCurve star : rejected) {
                report.println("<tr><td>#" + star.id + "</td><td>" + DetectionReportGenerator.escapeHtml(
                        DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, star.x, star.y)) + "</td>"
                        + "<td>" + formatMag(star.meanMag, 2) + "</td><td>" + formatMag(star.amplitude, 3) + "</td>"
                        + "<td>" + formatMag(star.scatterZ, 1) + "</td><td>" + formatMag(star.stetsonJZ, 1) + "</td>"
                        + "<td class='alert'>" + DetectionReportGenerator.escapeHtml(String.join(", ", star.failedGates)) + "</td>"
                        + "<td>" + lookUpLinks(context, star) + "</td></tr>");
            }
            report.println("</tbody></table></div>");
        }
        report.println("</div>");
    }

    private static void writeCandidateCard(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                                           StarLightCurve star, int number, SessionCatalogue sessionCatalogue) throws IOException {
        boolean high = star.tier == VariabilityTier.HIGH_CONFIDENCE;
        String tierColor = high ? PhotometrySvgChart.SERIES_BLUE : PhotometrySvgChart.SERIES_ORANGE;
        report.println("<div class='detection-card' id='variable-v" + number + "' style='border-left-color:" + tierColor + ";'>");
        report.println("<div class='detection-title'>V" + number + " &middot; " + tierLabel(star.tier) + " &middot; star #" + star.id + "</div>");
        report.println("<div class='astro-note' style='margin-top:-8px; margin-bottom:8px;'>"
                + DetectionReportGenerator.escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, star.x, star.y))
                + "</div>");
        report.println("<p style='font-size:14px; color:#e6e6e6; margin: 0 0 10px 0; line-height:1.5;'>" + DetectionReportGenerator.escapeHtml(plainSummary(analysis, star)) + "</p>");
        report.println(catalogueEntryHtml(context, star, sessionCatalogue));
        report.println(catalogueCrossCheckHtml(context, star, number));

        report.println("<details class='foldable-streak-details'><summary>Statistics</summary><div class='foldable-streak-body'>");
        report.println("<p class='compact-note'>The numbers behind the checks. Hover a box for what it means.</p>");
        report.println("<div class='flex-container'>");
        report.println(compactMetric(formatMag(star.amplitude, 3), "Size of the change (mag)",
                "How much the brightness changed, from a faint point to a bright point, ignoring the most extreme 5 percent at each end. In magnitudes."));
        report.println(compactMetric(formatMag(star.meanMag, 2), "Average brightness",
                "This star's average brightness in this session. A larger number is fainter. It is an instrumental magnitude, not a catalogue magnitude."));
        report.println(compactMetric(formatMag(star.excessScatter, 1) + "x", "Jumpiness versus a steady star",
                "How much this star jumps around, divided by how much a steady star of the same brightness jumps. 1 is ordinary noise. Well above 1 is a real change or a problem."));
        report.println(compactMetric(formatMag(star.scatterZ, 1), "How unusual the jumpiness is",
                "How unusual that jumpiness is among stars of the same brightness. Larger means more unusual. Both this and the next number have to stand out before a star is a candidate."));
        report.println(compactMetric(formatMag(star.stetsonJZ, 1), "How coherent the change is",
                "How unusual it is that neighbouring frames move in the same direction. One noisy frame does not raise this. This is the Stetson J score."));
        report.println(compactMetric(String.valueOf(star.usableFrames), "Frames kept",
                "Measurements kept for this star. A bad measurement, or a frame left out of the session, is not counted."));
        report.println(compactMetric(Double.isNaN(star.timeSpanMinutes) ? "n/a" : formatMag(star.timeSpanMinutes, 0) + " min", "Time covered",
                "Minutes from the first kept measurement to the last."));
        report.println(compactMetric(formatMag(star.splitHalfCorrelation, 2), "Consecutive frames agree",
                "Agreement between each measurement and the next, from -1 to 1. Near 1 means they move together. Near 0 means they look like noise."));
        report.println(compactMetric(formatMag(star.apertureAmplitudeDifference, 2), "Small versus large circle",
                "How much the size of the change differs between a small measuring circle and a large one, as a fraction of the change. Near 0 means they agree. A large difference usually means light from a neighbour."));
        report.println(compactMetric(formatMag(star.maxSystematicsCorrelation, 2)
                + (star.maxSystematicsSource != null ? " <span style='font-size:10px; color:#999;'>" + DetectionReportGenerator.escapeHtml(star.maxSystematicsSource) + "</span>" : ""),
                "Follows the whole frame",
                "The strongest agreement, from 0 to 1, between this star and something that affects the whole frame: transparency, star width, sky background, or the alignment shift. Near 1 means it follows that."));
        report.println(compactMetric(formatMag(star.systematicsLimitMag, 3), "Change the frame can explain",
                "How large a change that frame property produces in ordinary stars of this brightness, in magnitudes. A real variable should change by more than this."));
        report.println(compactMetric(formatMag(star.maxFrameToFrameSystematicsCorrelation, 2), "Jumps with the frame",
                "Whether this star jumps in the same frames as the frame property above. A blend with a neighbour often jumps when the seeing jumps."));
        report.println(compactMetric(formatMag(star.localCorrelation, 2) + " <span style='font-size:10px; color:#999;'>n=" + star.localComparisonStars + "</span>", "Neighbours follow the shape",
                "How closely the nearby stars follow this star's ups and downs, from 0 to 1. The small number is how many neighbours were used. High means they share the pattern."));
        report.println(compactMetric(formatMag(star.localSharedFraction, 2), "Neighbours share the size",
                "How much of this star's change shows up in the nearby stars. 1 means the same size of change. About 0.3 or more, together with a high shape agreement, fails the local check."));
        report.println("</div>");
        report.println("</div></details>");

        // Gates
        report.println("<p class='compact-note' style='margin-bottom:6px;'>Hover a check to read what it asks, and whether this star passed.</p>");
        report.println("<div style='display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;'>");
        for (String gate : VariableStarAnalysis.GATE_NAMES) {
            boolean failed = star.failedGates.contains(gate);
            String color = failed ? PhotometrySvgChart.STATUS_CRITICAL : PhotometrySvgChart.STATUS_GOOD;
            String icon = failed ? "&#10005;" : "&#10003;";
            report.println("<span class='legend-pill'" + tipAttr(gateTooltip(gate, failed, star.localComparisonStars)) + ">"
                    + "<span style='color:" + color + "; font-weight:bold;'>" + icon + "</span> " + gate + "</span>");
        }
        report.println("</div>");

        // Light curve + cutouts
        List<StarLightCurve> comparisons = comparisonStars(analysis, star);
        report.println(legend(
                legendDot(tierColor, "Candidate"),
                legendHollow(PhotometrySvgChart.CONTEXT_GRAY, "Flagged measurement (not used)"),
                legendLine(PhotometrySvgChart.CONTEXT_GRAY, "Constant comparison stars (offset)")));
        report.println("<div class='image-container' style='flex-wrap:wrap;'>");
        report.println(lightCurveChart(analysis, star, comparisons, tierColor));
        report.println(cutouts(context, analysis, star, number));
        report.println("</div>");
        report.println("</div>");
    }

    /**
     * Compact table of every high-confidence and possible candidate. The "Known as" column is filled at once for
     * candidates matched with the VSX stars of the session's sky catalogue; the "Identify in VSX" button looks the
     * others up in the full VSX through the lookup proxy (results are saved into the report).
     */
    private static void writeCandidateTable(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                                            List<StarLightCurve> candidates, SessionCatalogue sessionCatalogue) {
        boolean sky = context.astrometryContext != null && context.astrometryContext.hasAstrometricSolution();
        int shownRows = 0;
        int matchedRows = 0;
        for (StarLightCurve star : candidates) {
            shownRows++;
            SkyTarget target = sky ? skyTarget(context, star.x, star.y) : null;
            if (target != null && !sessionCatalogue.variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec).isEmpty()) {
                matchedRows++;
            }
        }
        int unmatchedRows = shownRows - matchedRows;
        report.println("<div style='display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin: 8px 0 2px 0;'>");
        if (sky && sessionCatalogue.isAvailable()) {
            if (unmatchedRows > 0) {
                report.println("<button type='button' class='id-link vsx-identify-all'>Look up the " + unmatchedRows
                        + (unmatchedRows == 1 ? " other" : " others") + " in VSX</button>");
            }
            report.println("<span class='astro-note vsx-identify-status' style='margin:0;'>" + matchedRows + " of " + shownRows
                    + " candidates were matched with the AAVSO VSX stars of the session's sky catalogue when this report was written."
                    + (unmatchedRows > 0 ? " The button looks the " + (unmatchedRows == 1 ? "other one" : "other " + unmatchedRows)
                    + " up in the full VSX, which also has the fainter variables. Needs SpacePixels running; the results are saved into this report." : "")
                    + "</span>");
        } else if (sky) {
            report.println("<button type='button' class='id-link vsx-identify-all'>Identify all in VSX</button>");
            report.println("<span class='astro-note vsx-identify-status' style='margin:0;'>Looks every candidate up in the AAVSO VSX catalogue. Needs SpacePixels running; the results are saved into this report.</span>");
        } else {
            report.println("<span class='astro-note' style='margin:0;'>Plate-solve the session to identify the candidates in variable-star catalogues.</span>");
        }
        report.println("</div>");
        report.println("<div class='scroll-box compact-table-box' style='max-height: 420px;'>");
        report.println("<table><thead><tr>"
                + th("#", "Candidate number. The first ones also have a card below.")
                + th("Tier", "High confidence passed every check. Possible failed exactly one.")
                + th("Position", "Where the star is, in pixels and on the sky when the field is solved.")
                + th("Mean Inst. Mag", "Average brightness in this session. A larger number is fainter. Not a catalogue magnitude.")
                + th("Amplitude", "Size of the brightness change, in magnitudes.")
                + th("Time Span", "Minutes from the first kept measurement to the last.")
                + th("Change", "The rough shape of the light curve: a steady fade, a steady rise, a dip, a peak, or up and down.")
                + th("Failed Check", "The one check a possible candidate failed. High-confidence candidates have none.")
                + (sky ? th("Known As (VSX)", "Name in the AAVSO VSX variable-star catalogue, when this session's sky catalogue has a match.") : "")
                + "</tr></thead><tbody>");
        int number = 0;
        for (StarLightCurve star : candidates) {
            number++;
            String label = "V" + number;
            String name = number <= MAX_CANDIDATE_CARDS ? "<a href='#variable-v" + number + "'>" + label + "</a>" : label;
            boolean high = star.tier == VariabilityTier.HIGH_CONFIDENCE;
            report.print("<tr><td><strong>" + name + "</strong></td>"
                    + "<td style='color:" + (high ? PhotometrySvgChart.SERIES_BLUE : PhotometrySvgChart.SERIES_ORANGE) + ";'>" + tierLabel(star.tier) + "</td>"
                    + "<td>" + DetectionReportGenerator.escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, star.x, star.y)) + "</td>"
                    + "<td>" + formatMag(star.meanMag, 2) + "</td>"
                    + "<td>" + formatMag(star.amplitude, 3) + " mag</td>"
                    + "<td>" + (Double.isNaN(star.timeSpanMinutes) ? "n/a" : formatMag(star.timeSpanMinutes, 0) + " min") + "</td>"
                    + "<td>" + DetectionReportGenerator.escapeHtml(describeChange(star)) + "</td>"
                    + "<td" + (star.failedGates.isEmpty() ? "" : " class='alert'") + ">" + (star.failedGates.isEmpty() ? "&ndash;"
                    : DetectionReportGenerator.escapeHtml(String.join(", ", star.failedGates))) + "</td>");
            SkyTarget target = sky ? skyTarget(context, star.x, star.y) : null;
            List<SessionCatalogue.VariableMatch> matches = target == null ? List.of()
                    : sessionCatalogue.variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec);
            if (!matches.isEmpty()) {
                report.print("<td title='Matched with the AAVSO VSX stars of the session&#39;s sky catalogue'>" + knownAsHtml(matches) + "</td>");
            } else if (target != null) {
                String sidecar = "photometry_v" + number + "_vsx.json";
                String hidden = DetectionReportAstrometry.buildLiveRenderButtonHtml("vsx", vsxTapUrl(target), "photometry-v" + number + "-vsx-row",
                        "VSX", sidecar, String.format(Locale.US, "AAVSO VSX within %.0f arcsec of V%d", target.radiusArcsec, number))
                        .replace("<button ", "<button style='display:none' data-identify-all='1' ");
                report.print("<td data-known-as-sidecar='" + sidecar + "'><span style='color:#777;'>not checked</span>" + hidden + "</td>");
            } else if (sky) {
                report.print("<td>&ndash;</td>");
            }
            report.println("</tr>");
        }
        report.println("</tbody></table></div>");
    }

    /** The "Known as" cell of a candidate matched in the session's catalogue, written like the live VSX result. */
    static String knownAsHtml(List<SessionCatalogue.VariableMatch> matches) {
        SkyCatalogue.VariableStar star = matches.get(0).star;
        String name = DetectionReportGenerator.escapeHtml(star.name);
        String link = star.oid > 0 ? "<a class='live-link-inline' href='https://www.aavso.org/vsx/index.php?view=detail.top&amp;oid="
                + star.oid + "' target='_blank' rel='noopener noreferrer'>" + name + "</a>" : name;
        return "<strong>" + link + "</strong> &middot; " + DetectionReportGenerator.escapeHtml(star.type != null ? star.type : "?")
                + " &middot; " + (star.periodDays != null ? SessionCatalogue.formatPeriod(star.periodDays) : "no period")
                + " &middot; " + String.format(Locale.US, "%.1f", matches.get(0).separationArcsec) + "&Prime;"
                + (matches.size() > 1 ? " <span style='color:#888;'>(+" + (matches.size() - 1) + " more)</span>" : "");
    }

    /**
     * What the session's sky catalogue says about a candidate: its VSX entry, explained, with how much of the
     * period the session covers, and its Gaia magnitude and colour. Catalogue values, not measurements. Empty
     * without a catalogue or a sky position.
     */
    static String catalogueEntryHtml(DetectionReportContext context, StarLightCurve star, SessionCatalogue sessionCatalogue) {
        SkyTarget target = sessionCatalogue.isAvailable() ? skyTarget(context, star.x, star.y) : null;
        if (target == null) {
            return "";
        }
        StringBuilder html = new StringBuilder("<div class='astro-note' style='margin: 0 0 10px 0; line-height:1.6;'>");
        List<SessionCatalogue.VariableMatch> matches = sessionCatalogue.variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec);
        if (matches.isEmpty()) {
            html.append("Not among the AAVSO VSX variables of the session's sky catalogue (down to magnitude ")
                    .append(String.format(Locale.US, "%.1f", sessionCatalogue.catalogue.starMagnitudeLimit + 1))
                    .append("). <strong>Check VSX Here</strong> searches the full VSX, which also has fainter variables.");
        } else {
            SessionCatalogue.VariableMatch match = matches.get(0);
            SkyCatalogue.VariableStar variable = match.star;
            String name = DetectionReportGenerator.escapeHtml(variable.name);
            html.append("<strong>Catalogued as ")
                    .append(variable.oid > 0 ? "<a class='live-link-inline' href='https://www.aavso.org/vsx/index.php?view=detail.top&amp;oid="
                            + variable.oid + "' target='_blank' rel='noopener noreferrer'>" + name + "</a>" : name)
                    .append("</strong> in AAVSO VSX, ").append(String.format(Locale.US, "%.1f", match.separationArcsec)).append("&Prime; away: ")
                    .append(DetectionReportGenerator.escapeHtml(SessionCatalogue.describeVsxType(variable.type)));
            String range = vsxRange(variable);
            if (range != null) {
                html.append(" &middot; ").append(DetectionReportGenerator.escapeHtml(range));
            }
            if (variable.periodDays != null && variable.periodDays > 0) {
                html.append(" &middot; period ").append(SessionCatalogue.formatPeriod(variable.periodDays)).append('.');
                String coverage = periodCoverage(variable.periodDays, star.timeSpanMinutes);
                if (coverage != null) {
                    html.append(' ').append(coverage);
                }
            } else {
                html.append('.');
            }
            if (matches.size() > 1) {
                html.append(" <span style='color:#888;'>").append(matches.size() - 1).append(" more VSX ")
                        .append(matches.size() == 2 ? "star" : "stars").append(" within ")
                        .append(String.format(Locale.US, "%.0f", target.radiusArcsec)).append("&Prime;.</span>");
            }
        }
        SkyCatalogue.Star gaia = sessionCatalogue.gaiaStarNear(target.raDegrees, target.decDegrees, Math.min(target.radiusArcsec, 5.0));
        if (gaia != null) {
            html.append("<br>Gaia DR3: G ").append(String.format(Locale.US, "%.2f", gaia.g));
            if (gaia.bpRp != null) {
                html.append(", BP&minus;RP ").append(String.format(Locale.US, "%.2f", gaia.bpRp));
            }
            html.append(" <span style='color:#888;'>(catalogue values, not measured in this session)</span>");
        }
        return html.append("</div>").toString();
    }

    /** "range 12.10–12.80 V", "12.33 G, amplitude 0.03", or null when VSX gives no magnitudes. */
    static String vsxRange(SkyCatalogue.VariableStar variable) {
        if (variable.max == null) {
            return null;
        }
        String band = variable.band == null ? "" : " " + variable.band;
        if (variable.min == null) {
            return String.format(Locale.US, "brightest %.2f%s", variable.max, band);
        }
        if (variable.minIsAmplitude) {
            return String.format(Locale.US, "%.2f%s, amplitude %.2f", variable.max, band, variable.min);
        }
        return String.format(Locale.US, "range %.2f–%.2f%s", variable.max, variable.min, band);
    }

    /** How much of the period the candidate's measurements span, in words; null without a time span. */
    static String periodCoverage(double periodDays, double spanMinutes) {
        if (Double.isNaN(spanMinutes) || spanMinutes <= 0 || periodDays <= 0) {
            return null;
        }
        double cycles = spanMinutes / (periodDays * 1440.0);
        String span = spanMinutes >= 120 ? String.format(Locale.US, "%.1f h", spanMinutes / 60.0) : String.format(Locale.US, "%.0f min", spanMinutes);
        String part;
        if (cycles >= 1.5) {
            part = String.format(Locale.US, "about %.0f cycles", cycles);
        } else if (cycles >= 0.85) {
            part = "about one full cycle";
        } else if (cycles >= 0.1) {
            part = String.format(Locale.US, "about %.0f %% of a cycle", cycles * 100);
        } else {
            part = "less than a tenth of a cycle, so only a small part of the variation can show";
        }
        return "This session covers " + span + ", " + part + ".";
    }

    /** One plain sentence: how the star changed, by how much, how clearly, and which checks it passed. */
    static String plainSummary(VariableStarAnalysis analysis, StarLightCurve star) {
        StringBuilder text = new StringBuilder(describeChange(star));
        text.append(" by ").append(formatMag(star.amplitude, 2)).append(" mag");
        if (!Double.isNaN(star.timeSpanMinutes)) {
            text.append(" over ").append(formatMag(star.timeSpanMinutes, 0)).append(" min");
        }
        if (Double.isFinite(star.excessScatter)) {
            text.append("; its scatter is ").append(formatMag(star.excessScatter, 1)).append("× that of stars of the same brightness");
        }
        text.append('.');
        if (star.failedGates.isEmpty()) {
            text.append(" Passes all ").append(VariableStarAnalysis.GATE_NAMES.size()).append(" checks.");
        } else {
            String gate = star.failedGates.get(0);
            text.append(" Fails ").append(star.failedGates.size() == 1 ? "one check" : star.failedGates.size() + " checks").append(": ")
                    .append(gate).append(" (").append(gateOutcome(gate, true, star.localComparisonStars)).append(")")
                    .append(star.failedGates.size() > 1 ? " and others" : "").append('.');
        }
        return text.toString();
    }

    /**
     * Rough shape of a light curve from its smoothed usable points: a steady fade or rise, a dip that
     * recovers, a peak that fades back, or variation up and down.
     */
    static String describeChange(StarLightCurve star) {
        List<Double> values = new ArrayList<>();
        for (int j = 0; j < star.deltaMag.length; j++) {
            if (star.flags[j] == 0 && Double.isFinite(star.deltaMag[j])) {
                values.add(star.deltaMag[j]);
            }
        }
        int n = values.size();
        if (n < 5) {
            return "Varied";
        }
        double[] smooth = new double[n];
        for (int i = 0; i < n; i++) {
            int from = Math.max(0, i - 2);
            int to = Math.min(n, i + 3);
            double[] window = new double[to - from];
            for (int k = from; k < to; k++) {
                window[k - from] = values.get(k);
            }
            Arrays.sort(window);
            smooth[i] = window[window.length / 2];
        }
        int faintest = 0;
        int brightest = 0;
        for (int i = 1; i < n; i++) {
            if (smooth[i] > smooth[faintest]) faintest = i;
            if (smooth[i] < smooth[brightest]) brightest = i;
        }
        double range = smooth[faintest] - smooth[brightest];
        if (!(range > 0)) {
            return "Varied";
        }
        int edge = Math.max(2, n / 5);
        double start = 0;
        double end = 0;
        for (int i = 0; i < edge; i++) {
            start += smooth[i] / edge;
            end += smooth[n - 1 - i] / edge;
        }
        // Magnitudes: larger is fainter.
        if (Math.abs(end - start) >= 0.6 * range) {
            return end > start ? "Faded steadily" : "Brightened steadily";
        }
        boolean faintInside = faintest >= edge && faintest < n - edge;
        boolean brightInside = brightest >= edge && brightest < n - edge;
        if (faintInside && smooth[faintest] - start > 0.5 * range && smooth[faintest] - end > 0.5 * range) {
            return "Dipped and recovered";
        }
        if (brightInside && start - smooth[brightest] > 0.5 * range && end - smooth[brightest] > 0.5 * range) {
            return "Brightened and faded back";
        }
        return "Varied up and down";
    }

    /** Sky position and catalogue search radius of a star; null without an astrometric solution. */
    static final class SkyTarget {
        final double raDegrees;
        final double decDegrees;
        final double radiusArcsec;

        SkyTarget(double raDegrees, double decDegrees, double radiusArcsec) {
            this.raDegrees = raDegrees;
            this.decDegrees = decDegrees;
            this.radiusArcsec = radiusArcsec;
        }
    }

    static SkyTarget skyTarget(DetectionReportContext context, double x, double y) {
        if (context.astrometryContext == null || !context.astrometryContext.hasAstrometricSolution()) {
            return null;
        }
        WcsCoordinateTransformer transformer = context.astrometryContext.getTransformer();
        WcsCoordinateTransformer.SkyCoordinate centre = transformer.pixelToSky(x, y);
        WcsCoordinateTransformer.SkyCoordinate neighbour = transformer.pixelToSky(x + 1.0, y);
        double pixelScaleArcsec = angularSeparationDegrees(centre.getRaDegrees(), centre.getDecDegrees(),
                neighbour.getRaDegrees(), neighbour.getDecDegrees()) * 3600.0;
        double radius = Math.max(MIN_SEARCH_RADIUS_ARCSEC,
                Math.min(MAX_SEARCH_RADIUS_ARCSEC, SEARCH_RADIUS_PIXELS * pixelScaleArcsec));
        if (!Double.isFinite(radius)) {
            radius = MIN_SEARCH_RADIUS_ARCSEC;
        }
        return new SkyTarget(centre.getRaDegrees(), centre.getDecDegrees(), radius);
    }

    /**
     * Identification helpers for a candidate with a sky position: a live VSX lookup rendered inside the
     * report (through the SpacePixels lookup proxy, cached into the report) plus browser links.
     */
    private static String catalogueCrossCheckHtml(DetectionReportContext context, StarLightCurve star, int number) {
        SkyTarget target = skyTarget(context, star.x, star.y);
        if (target == null) {
            return "<div class='astro-note'>Plate-solve the session to cross-check this star against variable-star catalogues.</div>";
        }
        String slotId = "photometry-v" + number + "-vsx";
        String sidecar = "photometry_v" + number + "_vsx.json";
        StringBuilder html = new StringBuilder();
        html.append("<div class='id-links'>");
        html.append(DetectionReportAstrometry.buildLiveRenderButtonHtml("vsx", vsxTapUrl(target), slotId,
                "Check VSX Here", sidecar,
                String.format(Locale.US, "AAVSO VSX within %.0f arcsec of V%d", target.radiusArcsec, number)));
        html.append(browserLinks(target));
        html.append("</div>");
        html.append(DetectionReportAstrometry.buildLiveRenderContainerHtml(slotId));
        return html.toString();
    }

    /** Compact browser links for table rows; empty without an astrometric solution. */
    private static String lookUpLinks(DetectionReportContext context, StarLightCurve star) {
        SkyTarget target = skyTarget(context, star.x, star.y);
        return target == null ? "-" : "<span style='display:inline-flex; gap:6px;'>" + browserLinks(target) + "</span>";
    }

    private static String browserLinks(SkyTarget target) {
        String coords = String.format(Locale.US, "%.6f %+.6f", target.raDegrees, target.decDegrees);
        String radius = String.format(Locale.US, "%.0f", target.radiusArcsec);
        String vizier = "https://vizier.cds.unistra.fr/viz-bin/VizieR-4?-source=B/vsx/vsx&-c=" + urlEncode(coords) + "&-c.rs=" + radius;
        String simbad = "https://simbad.cds.unistra.fr/simbad/sim-coo?Coord=" + urlEncode(coords) + "&Radius=" + radius + "&Radius.unit=arcsec";
        return "<a class='id-link' href='" + DetectionReportGenerator.escapeHtml(vizier) + "' target='_blank' rel='noopener noreferrer'>VSX in VizieR</a>"
                + "<a class='id-link' href='" + DetectionReportGenerator.escapeHtml(simbad) + "' target='_blank' rel='noopener noreferrer'>SIMBAD</a>";
    }

    /**
     * VizieR TAP query for VSX variables inside the search circle, closest first, as JSON.
     */
    static String vsxTapUrl(SkyTarget target) {
        String point = String.format(Locale.US, "POINT('ICRS', %.6f, %.6f)", target.raDegrees, target.decDegrees);
        String query = "SELECT TOP 10 \"OID\", \"Name\", \"Type\", \"max\", \"n_max\", \"f_min\", \"min\", \"n_min\", \"Period\", "
                + "DISTANCE(POINT('ICRS', \"RAJ2000\", \"DEJ2000\"), " + point + ") * 3600 AS dist_arcsec "
                + "FROM \"" + ReportLookupUpstreamClient.VSX_TABLE + "\" "
                + "WHERE 1 = CONTAINS(POINT('ICRS', \"RAJ2000\", \"DEJ2000\"), "
                + String.format(Locale.US, "CIRCLE('ICRS', %.6f, %.6f, %.6f)", target.raDegrees, target.decDegrees, target.radiusArcsec / 3600.0)
                + ") ORDER BY dist_arcsec";
        return "https://" + ReportLookupUpstreamClient.VSX_TAP_HOST + ReportLookupUpstreamClient.VSX_TAP_PATH
                + "?REQUEST=doQuery&LANG=ADQL&FORMAT=json&QUERY=" + urlEncode(query);
    }

    /** Percent-encodes a query value, using %20 for spaces. */
    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static double angularSeparationDegrees(double ra1, double dec1, double ra2, double dec2) {
        double r1 = Math.toRadians(ra1);
        double d1 = Math.toRadians(dec1);
        double r2 = Math.toRadians(ra2);
        double d2 = Math.toRadians(dec2);
        double sinDd = Math.sin((d2 - d1) / 2);
        double sinDr = Math.sin((r2 - r1) / 2);
        double a = sinDd * sinDd + Math.cos(d1) * Math.cos(d2) * sinDr * sinDr;
        return Math.toDegrees(2 * Math.asin(Math.min(1.0, Math.sqrt(a))));
    }

    private static String lightCurveChart(VariableStarAnalysis analysis, StarLightCurve star,
                                          List<StarLightCurve> comparisons, String color) {
        List<PipelineTelemetry.PhotometryFrameStat> frames = analysis.frames;
        int n = frames.size();
        boolean timeAxis = true;
        double firstJd = Double.NaN;
        for (PipelineTelemetry.PhotometryFrameStat f : frames) {
            if (Double.isNaN(f.julianDate)) {
                timeAxis = false;
                break;
            }
            if (Double.isNaN(firstJd)) firstJd = f.julianDate;
        }
        double[] x = new double[n];
        for (int j = 0; j < n; j++) {
            x[j] = timeAxis ? (frames.get(j).julianDate - firstJd) * 24.0 * 60.0 : frames.get(j).frameIndex + 1;
        }

        PhotometrySvgChart chart = new PhotometrySvgChart(560, 300, "Light curve of star #" + star.id + " with comparison stars",
                timeAxis ? "Minutes since first frame" : "Frame", "Δmag (brighter up)").invertY().rightMargin(34);

        double step = Math.max(0.1, (Double.isNaN(star.amplitude) ? 0.1 : star.amplitude) * 1.4);
        for (int k = 0; k < comparisons.size(); k++) {
            StarLightCurve comparison = comparisons.get(k);
            double offset = (k + 1) * step;
            double[] y = new double[n];
            double lastX = Double.NaN;
            double lastY = Double.NaN;
            for (int j = 0; j < n; j++) {
                y[j] = comparison.flags[j] == 0 ? comparison.deltaMag[j] + offset : Double.NaN;
                if (!Double.isNaN(y[j])) {
                    lastX = x[j];
                    lastY = y[j];
                }
            }
            chart.line(x, y, PhotometrySvgChart.CONTEXT_GRAY, 1.5, false,
                    String.format(Locale.US, "Steady comparison star C%d, star #%d, average brightness %.2f. Drawn %.2f mag below the candidate so the shapes can be compared.",
                            k + 1, comparison.id, comparison.meanMag, offset));
            chart.label(lastX, lastY, "C" + (k + 1));
        }

        double[] used = new double[n];
        double[] flagged = new double[n];
        String[] usedTips = new String[n];
        String[] flaggedTips = new String[n];
        for (int j = 0; j < n; j++) {
            PipelineTelemetry.PhotometryFrameStat f = frames.get(j);
            double v = star.deltaMag[j];
            boolean ok = star.flags[j] == 0;
            used[j] = ok ? v : Double.NaN;
            flagged[j] = ok ? Double.NaN : v;
            String tip = String.format(Locale.US, "Frame %d (%s)%s. Brightness difference %s mag, uncertainty %s mag. Positive is fainter than this star's average.",
                    f.frameIndex + 1, f.filename,
                    Double.isNaN(f.julianDate) ? "" : ", " + utcFromJulianDate(f.julianDate), formatMag(v, 4), formatMag(star.magError[j], 4));
            usedTips[j] = tip + " Used.";
            flaggedTips[j] = tip + " Not used: " + plainFlags(star.flags[j]) + ".";
        }
        chart.line(x, used, color, 2, false, null);
        chart.points(x, used, color, 4, PhotometrySvgChart.Marker.CIRCLE, usedTips);
        chart.points(x, flagged, PhotometrySvgChart.CONTEXT_GRAY, 4, PhotometrySvgChart.Marker.HOLLOW_CIRCLE, flaggedTips);
        return "<div>" + chart.render() + "</div>";
    }

    /**
     * Constant stars within the magnitude window, nearest on the sky first, widening the window if needed.
     */
    private static List<StarLightCurve> comparisonStars(VariableStarAnalysis analysis, StarLightCurve star) {
        for (double window : new double[]{COMPARISON_MAG_WINDOW, 2 * COMPARISON_MAG_WINDOW, 4 * COMPARISON_MAG_WINDOW}) {
            List<StarLightCurve> pool = new ArrayList<>();
            for (StarLightCurve other : analysis.stars) {
                if (other.id == star.id || other.tier != VariabilityTier.CONSTANT || !(other.scatterZ < 1.0)) continue;
                if (Math.abs(other.meanMag - star.meanMag) <= window) pool.add(other);
            }
            if (pool.size() >= COMPARISON_STARS || window == 4 * COMPARISON_MAG_WINDOW) {
                pool.sort(Comparator.comparingDouble(o -> Math.hypot(o.x - star.x, o.y - star.y)));
                return new ArrayList<>(pool.subList(0, Math.min(COMPARISON_STARS, pool.size())));
            }
        }
        return new ArrayList<>();
    }

    /**
     * Crops around the star in its brightest and faintest usable frame, rendered with one shared
     * linear stretch so the brightness difference is visible.
     */
    private static String cutouts(DetectionReportContext context, VariableStarAnalysis analysis, StarLightCurve star, int number) throws IOException {
        int brightest = -1;
        int faintest = -1;
        for (int j = 0; j < star.deltaMag.length; j++) {
            if (star.flags[j] != 0 || Double.isNaN(star.deltaMag[j])) continue;
            if (brightest < 0 || star.deltaMag[j] < star.deltaMag[brightest]) brightest = j;
            if (faintest < 0 || star.deltaMag[j] > star.deltaMag[faintest]) faintest = j;
        }
        if (brightest < 0 || context.rawFrames == null) {
            return "";
        }
        short[][] brightFrame = rawFrame(context, analysis.frames.get(brightest));
        short[][] faintFrame = rawFrame(context, analysis.frames.get(faintest));
        if (brightFrame == null || faintFrame == null) {
            return "";
        }
        double fwhm = analysis.frames.get(brightest).fwhm;
        int size = (int) Math.max(32, Math.round(12 * (Double.isNaN(fwhm) ? 3 : fwhm)));
        int cx = (int) Math.round(star.x);
        int cy = (int) Math.round(star.y);
        short[][] brightCrop = TrackVisualizationRenderer.robustEdgeAwareCrop(brightFrame, cx, cy, size, size);
        short[][] faintCrop = TrackVisualizationRenderer.robustEdgeAwareCrop(faintFrame, cx, cy, size, size);

        double[] values = new double[size * size];
        int k = 0;
        for (short[] row : brightCrop) for (short v : row) values[k++] = PixelEncoding.toShiftedPositiveInt(v);
        double[] sorted = Arrays.copyOf(values, k);
        Arrays.sort(sorted);
        double black = sorted[sorted.length / 2];
        double white = sorted[sorted.length - 1];

        String brightName = "photometry_v" + number + "_brightest.png";
        String faintName = "photometry_v" + number + "_faintest.png";
        TrackVisualizationRenderer.saveLosslessPng(sharedStretch(brightCrop, black, white), new File(context.exportDir, brightName));
        TrackVisualizationRenderer.saveLosslessPng(sharedStretch(faintCrop, black, white), new File(context.exportDir, faintName));

        PipelineTelemetry.PhotometryFrameStat b = analysis.frames.get(brightest);
        PipelineTelemetry.PhotometryFrameStat f = analysis.frames.get(faintest);
        return "<div style='display:flex; gap:12px;'>"
                + cutoutHtml(brightName, String.format(Locale.US, "Brightest: frame %d (%s mag)", b.frameIndex + 1, formatMag(star.deltaMag[brightest], 3)))
                + cutoutHtml(faintName, String.format(Locale.US, "Faintest: frame %d (%s mag)", f.frameIndex + 1, formatMag(star.deltaMag[faintest], 3)))
                + "</div>";
    }

    private static short[][] rawFrame(DetectionReportContext context, PipelineTelemetry.PhotometryFrameStat frame) {
        int index = frame.frameIndex;
        if (index < 0 || index >= context.rawFrames.size()) {
            return null;
        }
        return context.rawFrames.get(index);
    }

    private static BufferedImage sharedStretch(short[][] crop, double black, double white) {
        int h = crop.length;
        int w = crop[0].length;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        double span = Math.max(1.0, white - black);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double t = (PixelEncoding.toShiftedPositiveInt(crop[y][x]) - black) / span;
                t = Math.sqrt(Math.max(0.0, Math.min(1.0, t)));
                int g = (int) Math.round(255 * t);
                image.getRaster().setSample(x, y, 0, g);
            }
        }
        return image;
    }

    private static String cutoutHtml(String file, String caption) {
        return "<div><a href='" + file + "' target='_blank'><img src='" + file + "' style='width:" + CUTOUT_DISPLAY_SIZE + "px; image-rendering:pixelated;' alt='"
                + DetectionReportGenerator.escapeHtml(caption) + "' /></a><br/><center><small>" + DetectionReportGenerator.escapeHtml(caption) + "</small></center></div>";
    }

    // =================================================================
    // Per-frame table
    // =================================================================

    private static void writeFrameTable(PrintWriter report, PipelineTelemetry.PhotometryTelemetry t) {
        if (t.frames.isEmpty()) {
            return;
        }
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Variable Stars: Per-Frame Measurements</h2>");
        report.println("<p class='compact-note'>Each row is one frame. Hover a column name for what the number means.</p>");
        report.println("<p class='compact-note'><strong>Brightness of the frame.</strong> Z is how much the whole frame is fainter (positive) or brighter (negative) than a typical frame. "
                + "Tilt X and Tilt Y are a slope across the frame that was removed, in magnitudes from the centre to the edge. "
                + "A frame is left out when one ninth of it stays at least 0.10 mag brighter or fainter than the centre after that tilt is removed. "
                + "Its status then says Spatial residual and the size of that difference.</p>");
        report.println("<p class='compact-note'><strong>Star shapes.</strong> FWHM is how wide the stars are, in pixels. B says whether bright stars still have the same shape as faint ones. "
                + "Linear limit is the brightness where stars start to flatten, and linear range is how many magnitudes fainter than that still look trustworthy.</p>");
        report.println("<p class='compact-note'><strong>Can the magnitudes be trusted?</strong> D and Slope say whether faint stars changed more than bright stars. Slope err is how uncertain that slope is. "
                + "Sky at zero is the share of sky pixels crushed to zero.</p>");
        report.println("<p class='compact-note'><strong>Alignment.</strong> Offset X and Y are how far the frame was shifted to line up, in pixels. Spread is how much the stars disagreed about that shift.</p>");
        report.println("<p class='compact-note'><strong>Counts.</strong> Stars is how many went into the comparison, then how many were measured. "
                + "Saturated, non-linear, crossed, edge and outliers are single measurements left out of that frame.</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr>"
                + th("Frame", "Frame number in the session, starting at 1.")
                + th("Filename", "Name of the image file.")
                + th("Status", "Used means this frame is in the variable-star measurement. Left out names the reason.")
                + th("FWHM", "How wide the stars are in this frame, in pixels. Larger means blurrier.")
                + th("Aperture", "Radius of the main measuring circle, in pixels.")
                + th("Sky", "Typical sky background around the measured stars, in image counts.")
                + th("Offset X", "How far this frame was shifted sideways to line up with the others, in pixels.")
                + th("Offset Y", "How far this frame was shifted up or down to line up with the others, in pixels.")
                + th("Spread", "How much the stars disagreed about that shift, in pixels. Large means a poor alignment.")
                + th("Sky At Zero", "Share of the sky that sits on the lowest value the file can store. Those pixels hide the real sky.")
                + th("B", "Shape check. Pass means bright stars still have the shape of faint ones.")
                + th("Linear Limit", "Brightness, as an instrumental magnitude, where stars start to flatten. Brighter than this is not trusted.")
                + th("Linear Range", "How many magnitudes fainter than that limit still look trustworthy.")
                + th("Z", "Whole-frame brightness offset, in magnitudes. Positive means this frame is fainter than a typical frame.")
                + th("Tilt X", "Left-to-right brightness slope that was removed, in magnitudes from the centre to the edge.")
                + th("Tilt Y", "Bottom-to-top brightness slope that was removed, in magnitudes from the centre to the edge.")
                + th("D", "Response check. Pass means faint and bright stars changed by the same amount.")
                + th("Slope", "How much more the faint stars changed than the bright ones, in magnitudes per magnitude. Near zero is linear.")
                + th("Slope Err", "Uncertainty on that slope. A slope smaller than its uncertainty is not a real difference.")
                + th("Stars", "Stars used in the comparison, then stars measured in this frame.")
                + th("Saturated", "Measurements that hit the camera's brightest value.")
                + th("Non-Linear", "Measurements brighter than the shape check trusts.")
                + th("Crossed", "Stars a moving object passed over. Those measurements are left out.")
                + th("Edge/Void", "Measurements too close to the edge, or in an area left empty.")
                + th("Outliers", "Single frames far from that star's other measurements, with no neighbouring frame to support them.")
                + "</tr></thead><tbody>");
        for (PipelineTelemetry.PhotometryFrameStat f : t.frames) {
            String status = f.used ? "Used" : "Excluded: " + DetectionReportGenerator.escapeHtml(f.exclusionReason);
            report.println("<tr><td>" + (f.frameIndex + 1) + "</td><td>" + DetectionReportGenerator.escapeHtml(f.filename) + "</td>"
                    + "<td" + (f.used ? "" : " class='alert'") + ">" + status + "</td>"
                    + "<td>" + formatMag(f.fwhm, 2) + "</td><td>" + formatMag(f.apertureRadius, 2) + "</td><td>" + formatMag(f.skyMedian, 1) + "</td>"
                    + "<td>" + formatMag(f.registrationOffsetX, 2) + "</td><td>" + formatMag(f.registrationOffsetY, 2) + "</td><td>" + formatMag(f.registrationSpread, 2) + "</td>"
                    + "<td>" + formatPercent(f.floorClippedFraction) + "</td>"
                    + "<td" + ("FAIL".equals(f.shapeLinearityStatus) ? " class='alert'" : "") + ">" + f.shapeLinearityStatus + "</td>"
                    + "<td>" + formatMag(f.linearLimitMag, 2) + "</td><td>" + formatMag(f.linearRangeMag, 2) + "</td>"
                    + "<td>" + formatMag(f.zeroPoint, 4) + "</td><td>" + formatMag(f.planeX, 4) + "</td><td>" + formatMag(f.planeY, 4) + "</td>"
                    + "<td" + ("FAIL".equals(f.responseStatus) ? " class='alert'" : "") + ">" + f.responseStatus + "</td>"
                    + "<td>" + formatMag(f.responseSlope, 4) + "</td><td>" + formatMag(f.responseSlopeError, 4) + "</td>"
                    + "<td>" + f.starsInEnsemble + " / " + f.starsMeasured + "</td>"
                    + "<td>" + f.saturated + "</td><td>" + f.nonlinear + "</td><td>" + f.crossing + "</td><td>" + f.edgeOrVoid + "</td><td>" + f.outliers + "</td></tr>");
        }
        report.println("</tbody></table></div></div>");
    }

    // =================================================================
    // CSV exports
    // =================================================================

    private static void writeCsvExports(File exportDir, VariableStarAnalysis analysis) {
        try (PrintWriter csv = new PrintWriter(new File(exportDir, "photometry_stars.csv"), StandardCharsets.UTF_8.name())) {
            csv.println("star_id,x,y,mean_inst_mag,tier,usable_frames,scatter,expected_scatter,excess_scatter,scatter_z,stetson_j,stetson_j_z,amplitude,failed_gates,not_scored_reason");
            for (StarLightCurve s : analysis.stars) {
                csv.println(s.id + "," + csvNum(s.x) + "," + csvNum(s.y) + "," + csvNum(s.meanMag) + "," + s.tier + "," + s.usableFrames + ","
                        + csvNum(s.scatter) + "," + csvNum(s.expectedScatter) + "," + csvNum(s.excessScatter) + "," + csvNum(s.scatterZ) + ","
                        + csvNum(s.stetsonJ) + "," + csvNum(s.stetsonJZ) + "," + csvNum(s.amplitude) + ","
                        + csvText(String.join(";", s.failedGates)) + "," + csvText(s.notScoredReason));
            }
        } catch (IOException e) {
            System.err.println("Failed to write photometry_stars.csv: " + e.getMessage());
        }

        try (PrintWriter csv = new PrintWriter(new File(exportDir, "photometry_lightcurves.csv"), StandardCharsets.UTF_8.name())) {
            csv.println("star_id,tier,frame_index,filename,julian_date,delta_mag,mag_error,flags");
            for (StarLightCurve s : analysis.candidates) {
                for (int j = 0; j < analysis.frames.size(); j++) {
                    PipelineTelemetry.PhotometryFrameStat f = analysis.frames.get(j);
                    csv.println(s.id + "," + s.tier + "," + (f.frameIndex + 1) + "," + csvText(f.filename) + "," + csvNum(f.julianDate) + ","
                            + csvNum(s.deltaMag[j]) + "," + csvNum(s.magError[j]) + "," + csvText(String.join(";", PhotometryFlags.describe(s.flags[j]))));
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to write photometry_lightcurves.csv: " + e.getMessage());
        }

        try (PrintWriter csv = new PrintWriter(new File(exportDir, "photometry_frames.csv"), StandardCharsets.UTF_8.name())) {
            csv.println("frame_index,filename,julian_date,used,exclusion_reason,fwhm,aperture_radius,sky,offset_x,offset_y,registration_spread,"
                    + "sky_at_zero_fraction,shape_status,linear_limit_mag,linear_range_mag,concentration_reference,zero_point,plane_x,plane_y,"
                    + "response_status,response_slope,response_slope_error,stars_in_ensemble,stars_measured,saturated,nonlinear,crossing,edge_or_void,outliers,bad_flux");
            for (PipelineTelemetry.PhotometryFrameStat f : analysis.frames) {
                csv.println((f.frameIndex + 1) + "," + csvText(f.filename) + "," + csvNum(f.julianDate) + "," + f.used + "," + csvText(f.exclusionReason) + ","
                        + csvNum(f.fwhm) + "," + csvNum(f.apertureRadius) + "," + csvNum(f.skyMedian) + "," + csvNum(f.registrationOffsetX) + ","
                        + csvNum(f.registrationOffsetY) + "," + csvNum(f.registrationSpread) + "," + csvNum(f.floorClippedFraction) + ","
                        + f.shapeLinearityStatus + "," + csvNum(f.linearLimitMag) + "," + csvNum(f.linearRangeMag) + "," + csvNum(f.concentrationReference) + ","
                        + csvNum(f.zeroPoint) + "," + csvNum(f.planeX) + "," + csvNum(f.planeY) + "," + f.responseStatus + ","
                        + csvNum(f.responseSlope) + "," + csvNum(f.responseSlopeError) + "," + f.starsInEnsemble + "," + f.starsMeasured + ","
                        + f.saturated + "," + f.nonlinear + "," + f.crossing + "," + f.edgeOrVoid + "," + f.outliers + "," + f.badFlux);
            }
        } catch (IOException e) {
            System.err.println("Failed to write photometry_frames.csv: " + e.getMessage());
        }
    }

    // =================================================================
    // Small HTML helpers
    // =================================================================

    private static String metricBox(String value, String label) {
        return metricBox(value, label, null);
    }

    private static String metricBox(String value, String label, String tip) {
        return "<div class='metric-box'" + tipAttr(tip) + "><span class='metric-value'>" + value + "</span><span class='metric-label'>" + label + "</span></div>";
    }

    /** Plain words for the measurement flags, for a light-curve mouse-over. */
    private static String plainFlags(int flags) {
        List<String> plain = new ArrayList<>();
        if ((flags & PhotometryFlags.SATURATED) != 0) plain.add("saturated");
        if ((flags & PhotometryFlags.NONLINEAR) != 0) plain.add("brighter than the camera records faithfully");
        if ((flags & PhotometryFlags.EDGE_OR_VOID) != 0) plain.add("at the edge");
        if ((flags & PhotometryFlags.CROSSING) != 0) plain.add("a moving object crossed the star");
        if ((flags & PhotometryFlags.OUTLIER) != 0) plain.add("one odd frame");
        if ((flags & PhotometryFlags.FRAME_EXCLUDED) != 0) plain.add("the frame was left out");
        if ((flags & PhotometryFlags.BAD_FLUX) != 0) plain.add("the brightness could not be measured");
        if ((flags & PhotometryFlags.CONTAMINATED) != 0) plain.add("the star shape was distorted");
        return plain.isEmpty() ? "left out" : String.join("; ", plain);
    }

    private static String compactMetric(String value, String label) {
        return compactMetric(value, label, null);
    }

    private static String compactMetric(String value, String label, String tip) {
        return "<div class='metric-box compact'" + tipAttr(tip) + "><span class='metric-value'>" + value + "</span><span class='metric-label'>" + label + "</span></div>";
    }

    private static String configItem(String label, String value) {
        return configItem(label, value, null);
    }

    private static String configItem(String label, String value, String tip) {
        return "<div class='config-item'" + tipAttr(tip) + "><span>" + label + "</span><span class='val'>" + value + "</span></div>";
    }

    /** Mouse-over attribute. Apostrophes are escaped because the attribute uses single quotes. */
    private static String tipAttr(String tip) {
        if (tip == null || tip.isEmpty()) {
            return "";
        }
        return " title='" + DetectionReportGenerator.escapeHtml(tip).replace("'", "&#39;") + "'";
    }

    private static String th(String label, String tip) {
        return "<th" + tipAttr(tip) + ">" + label + "</th>";
    }

    private static String legend(String... pills) {
        return "<div class='map-legend' style='margin-bottom:8px;'>" + String.join("", pills) + "</div>";
    }

    private static String legendDot(String color, String label) {
        return "<span class='legend-pill'><svg width='12' height='12'><circle cx='6' cy='6' r='5' fill='" + color + "'/></svg>" + label + "</span>";
    }

    private static String legendHollow(String color, String label) {
        return "<span class='legend-pill'><svg width='12' height='12'><circle cx='6' cy='6' r='4.5' fill='none' stroke='" + color + "' stroke-width='1.5'/></svg>" + label + "</span>";
    }

    private static String legendCross(String color, String label) {
        return "<span class='legend-pill'><svg width='12' height='12'><path d='M2 2 L10 10 M2 10 L10 2' stroke='" + color + "' stroke-width='2.5' stroke-linecap='round'/></svg>" + label + "</span>";
    }

    private static String legendLine(String color, String label) {
        return "<span class='legend-pill'><svg width='18' height='12'><line x1='1' y1='6' x2='17' y2='6' stroke='" + color + "' stroke-width='2'/></svg>" + label + "</span>";
    }

    private static String legendDash(String color, String label) {
        return "<span class='legend-pill'><svg width='18' height='12'><line x1='1' y1='6' x2='17' y2='6' stroke='" + color + "' stroke-width='2' stroke-dasharray='4 3'/></svg>" + label + "</span>";
    }

    private static String verdictColor(String verdict) {
        switch (verdict) {
            case "READY": return PhotometrySvgChart.STATUS_GOOD;
            case "LIMITED": return PhotometrySvgChart.STATUS_WARNING;
            case "NOT_READY": return PhotometrySvgChart.STATUS_CRITICAL;
            default: return "#777777";
        }
    }

    private static String verdictBadge(String verdict) {
        switch (verdict) {
            case "READY": return "<span style='color:" + PhotometrySvgChart.STATUS_GOOD + ";'>&#10003;</span> Ready";
            case "LIMITED": return "<span style='color:" + PhotometrySvgChart.STATUS_WARNING + ";'>!</span> Limited";
            case "NOT_READY": return "<span style='color:" + PhotometrySvgChart.STATUS_CRITICAL + ";'>&#10005;</span> Not ready";
            default: return "Not run";
        }
    }

    private static String statusBadge(String status) {
        switch (status) {
            case "PASS": return "<span style='color:" + PhotometrySvgChart.STATUS_GOOD + ";'>&#10003; PASS</span>";
            case "LIMITED": return "<span style='color:" + PhotometrySvgChart.STATUS_WARNING + ";'>! LIMITED</span>";
            case "INCONCLUSIVE": return "<span style='color:" + PhotometrySvgChart.STATUS_WARNING + ";'>? INCONCLUSIVE</span>";
            case "FAIL": return "<span style='color:" + PhotometrySvgChart.STATUS_CRITICAL + ";'>&#10005; FAIL</span>";
            case "NOT_RUN": return "<span style='color:#8b949c;'>&ndash; NOT RUN</span>";
            default: return status;
        }
    }

    /** Mid-exposure time from a Julian date, as HH:mm:ss UTC. */
    private static String utcFromJulianDate(double julianDate) {
        long millis = Math.round((julianDate - 2440587.5) * 86_400_000.0);
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(millis));
    }

    private static String tierLabel(VariabilityTier tier) {
        switch (tier) {
            case HIGH_CONFIDENCE: return "High confidence";
            case POSSIBLE: return "Possible";
            case REJECTED: return "Rejected candidate";
            case CONSTANT: return "Constant";
            default: return "Not scored";
        }
    }

    private static String formatMag(double value, int decimals) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "n/a" : String.format(Locale.US, "%." + decimals + "f", value);
    }

    private static String formatPercent(double fraction) {
        return Double.isNaN(fraction) ? "n/a" : String.format(Locale.US, "%.2f%%", 100.0 * fraction);
    }

    private static String csvNum(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) ? "" : String.format(Locale.US, "%.6f", value);
    }

    private static String csvText(String value) {
        if (value == null) return "";
        return value.contains(",") || value.contains("\"") ? "\"" + value.replace("\"", "\"\"") + "\"" : value;
    }

    private static String key(double mag, double scatter) {
        return String.format(Locale.US, "%.9f|%.9f", mag, scatter);
    }

    private static double median(double[] values) {
        double[] sorted = Arrays.stream(values).filter(v -> !Double.isNaN(v)).sorted().toArray();
        if (sorted.length == 0) return Double.NaN;
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : 0.5 * (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]);
    }
}
