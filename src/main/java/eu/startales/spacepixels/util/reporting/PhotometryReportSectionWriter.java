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

    private static final Map<String, String> GATE_DESCRIPTIONS = new LinkedHashMap<>();

    static {
        GATE_DESCRIPTIONS.put("DATA", "Enough measurements over a long enough time span");
        GATE_DESCRIPTIONS.put("AMPLITUDE", "Amplitude above the floor and above the noise");
        GATE_DESCRIPTIONS.put("PERSISTENCE", "Change spans several consecutive frames");
        GATE_DESCRIPTIONS.put("SPLIT_HALF", "Consecutive measurement pairs agree");
        GATE_DESCRIPTIONS.put("APERTURE", "Small and large apertures give the same amplitude");
        GATE_DESCRIPTIONS.put("SYSTEMATICS", "Not correlated with transparency, FWHM, sky or offsets");
        GATE_DESCRIPTIONS.put("LOCAL", "Nearby constant stars do not share the pattern with a similar amplitude");
        GATE_DESCRIPTIONS.put("LINEARITY", "Never saturated or brighter than the linear limit");
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
        if (!analysis.frames.isEmpty()) {
            writeSessionCharts(report, context, analysis);
        }
        writeCandidates(report, context, analysis);
        writeFrameTable(report, telemetry);
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
        report.println("<p class='section-lede'>Differential photometry of isolated stars in every frame that passed quality control: each star is compared with an ensemble of all measured stars, so changes in transparency cancel out. "
                + "A star is reported when both its scatter and its frame-to-frame coherence stand out from stars of the same brightness and it passes the checks on its card. "
                + "<a href='photometry_stars.csv' target='_blank'>[All stars CSV]</a> "
                + "<a href='photometry_lightcurves.csv' target='_blank'>[Candidate light curves CSV]</a> "
                + "<a href='photometry_frames.csv' target='_blank'>[Per-frame CSV]</a></p>");

        report.println("<div class='flex-container'>");
        report.println("<div class='metric-box' style='border-left-color:" + verdictColor(t.verdict) + ";'><span class='metric-value'>"
                + verdictBadge(t.verdict) + "</span><span class='metric-label'>Readiness Verdict</span></div>");
        report.println(metricBox(t.framesUsed + " <span style='color:#777; font-size:16px;'>/ " + t.framesAnalyzed + "</span>", "Frames Used / Analysed"));
        report.println(metricBox(String.valueOf(t.starsSelected), "Stars Measured"));
        if (!notReady) {
            report.println(metricBox(String.valueOf(t.starsScored), "Stars Scored"));
            report.println(metricBox(String.valueOf(t.highConfidence), "High-Confidence Variables"));
            report.println(metricBox(String.valueOf(t.possible), "Possible Variables"));
        }
        report.println(metricBox(formatMag(t.medianLinearRangeMag, 1), "Linear Range (mag)"));
        report.println(metricBox(formatMag(t.zeroPointRangeMag, 3), "Transparency Range (mag)"));
        report.println("</div>");

        report.println(verdictExplanationHtml(context, t));
        report.println("<div class='astro-note'><strong>Ready</strong> means the checks found no sign of non-linear data, not that linearity is proven (a pure power-law stretch from zero passes every image-based check). "
                + "<strong>Limited</strong> sessions only report amplitudes of at least " + formatMag(context.config.variableLimitedMinAmplitudeMag, 2) + " mag. "
                + "<strong>Not ready</strong> sessions are not scored; their light curves are still exported for diagnosis.</div>");
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
        report.println("<p class='compact-note'>Photometry needs linear data, where a star twice as bright gives twice the signal. These checks look for signs that it is not.</p>");
        report.println("<div style='display:grid; grid-template-columns: max-content max-content 1fr; gap: 8px 14px; align-items: baseline; font-size: 13px; margin-bottom: 14px;'>");
        report.println(checkRow(t.quantisationCheck, "A &middot; Data depth",
                "No 8-bit or heavily quantised data, no sky noise clipped at zero.",
                t.distinctPixelLevels + " distinct pixel levels; " + formatPercent(t.medianFloorClippedFraction) + " of sky pixels at zero."));
        report.println(checkRow(t.shapeLinearityCheck, "B &middot; Star shapes",
                "Bright stars keep the profile of faint ones; where they turn flatter, linearity ends.",
                "Median verified linear range " + formatMag(t.medianLinearRangeMag, 2) + " mag; stars brighter than instrumental mag "
                        + formatMag(t.medianLinearLimitMag, 2) + " are set aside; " + t.framesFailingShapeLinearity + " of " + t.framesAnalyzed + " frames failed."));
        report.println(checkRow(t.responseCheck, "D &middot; Response",
                "When transparency changes, bright and faint stars change by the same amount.",
                "Transparency varied by " + formatMag(t.zeroPointRangeMag, 3) + " mag (at least " + formatMag(context.config.linearityMinZeroPointRangeMag, 2)
                        + " mag is needed to judge); " + t.framesFailingResponse + " frames failed."));
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
        report.println("<div class='compact-threshold-grid'>");
        report.println(configItem("A: Quantisation", statusBadge(t.quantisationCheck)));
        report.println(configItem("A: Distinct Pixel Levels", String.valueOf(t.distinctPixelLevels)));
        report.println(configItem("A: Sky Pixels At Zero", formatPercent(t.medianFloorClippedFraction)));
        report.println(configItem("B: Star Shape", statusBadge(t.shapeLinearityCheck)));
        report.println(configItem("B: Median Linear Limit (inst. mag)", formatMag(t.medianLinearLimitMag, 2)));
        report.println(configItem("B: Median Linear Range (mag)", formatMag(t.medianLinearRangeMag, 2)));
        report.println(configItem("B: Failing Frames", String.valueOf(t.framesFailingShapeLinearity)));
        report.println(configItem("D: Response", statusBadge(t.responseCheck)));
        report.println(configItem("D: Zero-Point Range (mag)", formatMag(t.zeroPointRangeMag, 3)));
        report.println(configItem("D: r(slope, zero point)", formatMag(t.slopeZeroPointCorrelation, 2)));
        report.println(configItem("D: r(slope, sky)", formatMag(t.slopeSkyCorrelation, 2)));
        report.println(configItem("D: Failing Frames", String.valueOf(t.framesFailingResponse)));
        report.println(configItem("Session FWHM (extraction, px)", formatMag(t.sessionFwhm, 2)));
        report.println(configItem("Saturation Level (ADU)", formatMag(t.saturationLevel, 0)));
        report.println(configItem("Stars Rejected: Crowded", String.valueOf(t.starsRejectedCrowded)));
        report.println(configItem("Stars Rejected: Saturated", String.valueOf(t.starsRejectedSaturated)));
        report.println(configItem("Stars Rejected: Edge / Void", String.valueOf(t.starsRejectedEdgeOrVoid)));
        report.println(configItem("Stars Rejected: Elongated / Streak", t.starsRejectedElongated + " / " + t.starsRejectedStreak));
        report.println(configItem("Stars Not Selected (cap)", String.valueOf(t.starsRejectedByCap)));
        report.println(configItem("Stars Mostly Non-Linear", String.valueOf(t.starsExcludedMostlyNonlinear)));
        report.println(configItem("Stars Below Min SNR", String.valueOf(t.starsExcludedLowSnr)));
        report.println(configItem("Frames Excluded: Registration", String.valueOf(t.framesExcludedRegistration)));
        report.println(configItem("Frames Excluded: Star Shape (B)", String.valueOf(t.framesExcludedShapeLinearity)));
        report.println(configItem("Frames Excluded: Response (D)", String.valueOf(t.framesExcludedResponse)));
        report.println(configItem("Frames Excluded: Too Few Stars", String.valueOf(t.framesExcludedTooFewStars)));
        report.println(configItem("Flagged: Saturated / Non-Linear", t.measurementsSaturated + " / " + t.measurementsNonlinear));
        report.println(configItem("Flagged: Crossing Objects", String.valueOf(t.measurementsCrossing)));
        report.println(configItem("Flagged: Edge / Void", String.valueOf(t.measurementsEdgeOrVoid)));
        report.println(configItem("Flagged: Isolated Outliers", String.valueOf(t.measurementsOutlier)));
        report.println(configItem("Flagged: Contaminated Shape", String.valueOf(t.measurementsContaminated)));
        report.println(configItem("Photometry Time", String.format(Locale.US, "%.2f s", t.processingTimeMs / 1000.0)));
        report.println("</div>");
        report.println("</div></details>");
        if (!t.gateFailureCounts.isEmpty()) {
            StringBuilder gates = new StringBuilder();
            for (Map.Entry<String, Integer> e : t.gateFailureCounts.entrySet()) {
                if (gates.length() > 0) gates.append(", ");
                gates.append(e.getKey()).append(": ").append(e.getValue());
            }
            report.println("<div class='astro-note'>Candidate checks failed: " + DetectionReportGenerator.escapeHtml(gates.toString()) + "</div>");
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
            report.println("<p class='compact-note'>Each dot is one scored star. The dashed line is the scatter expected from stars of the same brightness; variable candidates sit well above it. Hover a dot for details." + sampleNote + "</p>");
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
        report.println("<p class='compact-note'>One chart per measurement, frame number on the x axis. Excluded frames are marked with a red cross; hover any mark for the frame name and the reason.</p>");
        report.println(legend(
                legendDot(PhotometrySvgChart.SERIES_BLUE, "Used frame"),
                legendCross(PhotometrySvgChart.STATUS_CRITICAL, "Excluded frame"),
                legendDash(PhotometrySvgChart.REFERENCE_INK, "Configured limit")));
        report.println("<div class='flex-container' style='gap:14px;'>");
        report.println(frameChart(frames, "Zero point Z (mag, brighter up)", f -> f.zeroPoint, true, Double.NaN, Double.NaN, null));
        double slopeLimit = context.config.linearityMaxFrameSlope;
        report.println(frameChart(frames, "D: response slope (mag/mag)", f -> f.responseSlope, false, -slopeLimit, slopeLimit, "limit"));
        report.println(frameChart(frames, "B: linear range (mag)", f -> f.linearRangeMag, false, context.config.linearityMinRangeMag, Double.NaN, "min"));
        report.println(frameChart(frames, "Measured FWHM (px)", f -> f.fwhm, false, Double.NaN, Double.NaN, null));
        report.println(frameChart(frames, "Registration spread (px)", f -> f.registrationSpread, false,
                context.config.photometryMaxRegistrationSpreadPixels, Double.NaN, "max"));
        report.println(frameChart(frames, "Stars flagged as crossed", f -> (double) f.crossing, false, Double.NaN, Double.NaN, null, true));
        report.println("</div>");

        // --- Check B profiles ---
        report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Check B: star concentration against brightness</h3>");
        report.println("<p class='compact-note'>Concentration index of each frame minus that frame's faint-star reference, in magnitude bins. Linear data stays inside the shaded tolerance band; stretched or saturated data bends away at the bright (left) end.</p>");
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
                tips.add(String.format(Locale.US, "%s%s | mag %.2f | scatter %.4f (expected %.4f)",
                        star != null ? "Star #" + star.id + String.format(Locale.US, " (%.0f, %.0f) | ", star.x, star.y) : "",
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

    private static String frameChart(List<PipelineTelemetry.PhotometryFrameStat> frames, String title, FrameValue value,
                                     boolean invert, double limitA, double limitB, String limitLabel) {
        return frameChart(frames, title, value, invert, limitA, limitB, limitLabel, false);
    }

    private static String frameChart(List<PipelineTelemetry.PhotometryFrameStat> frames, String title, FrameValue value,
                                     boolean invert, double limitA, double limitB, String limitLabel, boolean counts) {
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
            String tip = String.format(Locale.US, "Frame %d (%s): %s", f.frameIndex + 1, f.filename, formatMag(v, 4));
            usedY[j] = f.used ? v : Double.NaN;
            excludedY[j] = f.used ? Double.NaN : v;
            usedTips[j] = tip;
            excludedTips[j] = tip + " | excluded: " + f.exclusionReason;
        }
        PhotometrySvgChart chart = new PhotometrySvgChart(360, 190, title, "Frame", "");
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
        return "<div><div style='font-size:12px; color:#cccccc; margin-bottom:4px;'>" + DetectionReportGenerator.escapeHtml(title)
                + "</div>" + chart.render() + "</div>";
    }

    private static String concentrationChart(List<PipelineTelemetry.PhotometryFrameStat> frames, double drift) {
        PhotometrySvgChart chart = new PhotometrySvgChart(760, 300, "Concentration index minus reference against magnitude, one line per frame",
                "Instrumental magnitude (fainter to the right)", "Concentration - reference");
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
                String tip = String.format(Locale.US, "Frame %d (%s): B %s, linear limit %s, range %s mag",
                        f.frameIndex + 1, f.filename, f.shapeLinearityStatus, formatMag(f.linearLimitMag, 2), formatMag(f.linearRangeMag, 2));
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
            writeCandidateTable(report, context, analysis, cards);
            int shown = 0;
            for (StarLightCurve star : cards) {
                if (shown++ >= MAX_CANDIDATE_CARDS) break;
                writeCandidateCard(report, context, analysis, star, shown);
            }
            if (cards.size() > MAX_CANDIDATE_CARDS) {
                report.println("<p class='compact-note'>" + (cards.size() - MAX_CANDIDATE_CARDS) + " more candidates are listed in photometry_stars.csv.</p>");
            }
        }

        if (!rejected.isEmpty()) {
            report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Rejected candidates (" + rejected.size() + ")</h3>");
            report.println("<p class='compact-note'>Stars whose scores stood out but that failed two or more gates. Listed so you can see why a suspected variable was not reported.</p>");
            report.println("<div class='scroll-box compact-table-box'>");
            report.println("<table><thead><tr><th>Star</th><th>Position</th><th>Mean Mag</th><th>Amplitude</th><th>Scatter z</th><th>Stetson J z</th><th>Failed Gates</th><th>Look Up</th></tr></thead><tbody>");
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
                                           StarLightCurve star, int number) throws IOException {
        boolean high = star.tier == VariabilityTier.HIGH_CONFIDENCE;
        String tierColor = high ? PhotometrySvgChart.SERIES_BLUE : PhotometrySvgChart.SERIES_ORANGE;
        report.println("<div class='detection-card' id='variable-v" + number + "' style='border-left-color:" + tierColor + ";'>");
        report.println("<div class='detection-title'>V" + number + " &middot; " + tierLabel(star.tier) + " &middot; star #" + star.id + "</div>");
        report.println("<div class='astro-note' style='margin-top:-8px; margin-bottom:8px;'>"
                + DetectionReportGenerator.escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, star.x, star.y))
                + "</div>");
        report.println("<p style='font-size:14px; color:#e6e6e6; margin: 0 0 10px 0; line-height:1.5;'>" + DetectionReportGenerator.escapeHtml(plainSummary(analysis, star)) + "</p>");
        report.println(catalogueCrossCheckHtml(context, star, number));

        report.println("<details class='foldable-streak-details'><summary>Statistics</summary><div class='foldable-streak-body'>");
        report.println("<div class='flex-container'>");
        report.println(compactMetric(formatMag(star.amplitude, 3), "Amplitude (mag)"));
        report.println(compactMetric(formatMag(star.meanMag, 2), "Mean Inst. Mag"));
        report.println(compactMetric(formatMag(star.excessScatter, 1) + "x", "Scatter / Expected"));
        report.println(compactMetric(formatMag(star.scatterZ, 1), "Scatter z"));
        report.println(compactMetric(formatMag(star.stetsonJZ, 1), "Stetson J z"));
        report.println(compactMetric(String.valueOf(star.usableFrames), "Usable Frames"));
        report.println(compactMetric(Double.isNaN(star.timeSpanMinutes) ? "n/a" : formatMag(star.timeSpanMinutes, 0) + " min", "Time Span"));
        report.println(compactMetric(formatMag(star.splitHalfCorrelation, 2), "Split-Half r"));
        report.println(compactMetric(formatMag(star.apertureAmplitudeDifference, 2), "Aperture Diff"));
        report.println(compactMetric(formatMag(star.maxSystematicsCorrelation, 2)
                + (star.maxSystematicsSource != null ? " <span style='font-size:10px; color:#999;'>" + DetectionReportGenerator.escapeHtml(star.maxSystematicsSource) + "</span>" : ""),
                "Max Systematics r"));
        report.println(compactMetric(formatMag(star.systematicsLimitMag, 3), "Systematics Limit (mag)"));
        report.println(compactMetric(formatMag(star.maxFrameToFrameSystematicsCorrelation, 2), "Frame-to-Frame Systematics r"));
        report.println(compactMetric(formatMag(star.localCorrelation, 2) + " <span style='font-size:10px; color:#999;'>n=" + star.localComparisonStars + "</span>", "Local r"));
        report.println(compactMetric(formatMag(star.localSharedFraction, 2), "Local shared"));
        report.println("</div>");
        report.println("</div></details>");

        // Gates
        report.println("<div style='display:flex; flex-wrap:wrap; gap:6px; margin-bottom:12px;'>");
        for (String gate : VariableStarAnalysis.GATE_NAMES) {
            boolean failed = star.failedGates.contains(gate);
            String color = failed ? PhotometrySvgChart.STATUS_CRITICAL : PhotometrySvgChart.STATUS_GOOD;
            String icon = failed ? "&#10005;" : "&#10003;";
            report.println("<span class='legend-pill' title='" + DetectionReportGenerator.escapeHtml(GATE_DESCRIPTIONS.getOrDefault(gate, gate)) + "'>"
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
     * Compact table of every high-confidence and possible candidate, with an "Identify all in VSX" button
     * that fills the "Known as" column through the lookup proxy (results are saved into the report).
     */
    private static void writeCandidateTable(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                                            List<StarLightCurve> candidates) {
        boolean sky = context.astrometryContext != null && context.astrometryContext.hasAstrometricSolution();
        report.println("<div style='display:flex; flex-wrap:wrap; align-items:center; gap:12px; margin: 8px 0 2px 0;'>");
        if (sky) {
            report.println("<button type='button' class='id-link vsx-identify-all'>Identify all in VSX</button>");
            report.println("<span class='astro-note vsx-identify-status' style='margin:0;'>Looks every candidate up in the AAVSO VSX catalogue. Needs SpacePixels running; the results are saved into this report.</span>");
        } else {
            report.println("<span class='astro-note' style='margin:0;'>Plate-solve the session to identify the candidates in variable-star catalogues.</span>");
        }
        report.println("</div>");
        report.println("<div class='scroll-box compact-table-box' style='max-height: 420px;'>");
        report.println("<table><thead><tr><th>#</th><th>Tier</th><th>Position</th><th>Mean Inst. Mag</th><th>Amplitude</th><th>Time Span</th><th>Change</th><th>Failed Check</th>"
                + (sky ? "<th>Known As (VSX)</th>" : "") + "</tr></thead><tbody>");
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
            if (target != null) {
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
                    .append(gate).append(" (").append(GATE_DESCRIPTIONS.getOrDefault(gate, gate).toLowerCase(Locale.ROOT)).append(")")
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
                    String.format(Locale.US, "Comparison C%d: star #%d, mag %.2f, offset +%.2f", k + 1, comparison.id, comparison.meanMag, offset));
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
            String tip = String.format(Locale.US, "Frame %d (%s)%s: Δmag %s ± %s", f.frameIndex + 1, f.filename,
                    Double.isNaN(f.julianDate) ? "" : ", " + utcFromJulianDate(f.julianDate), formatMag(v, 4), formatMag(star.magError[j], 4));
            usedTips[j] = tip;
            flaggedTips[j] = tip + " | flags: " + String.join(", ", PhotometryFlags.describe(star.flags[j]));
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
        report.println("<p class='compact-note'>FWHM is measured by the photometry stage from bright-star moments. Z is the frame zero point (positive = fainter than the session median frame). "
                + "Slope is check D's residual-against-magnitude slope. Stars counts are those used in the ensemble / measured.</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Frame</th><th>Filename</th><th>Status</th><th>FWHM</th><th>Aperture</th><th>Sky</th>"
                + "<th>Offset X</th><th>Offset Y</th><th>Spread</th><th>Sky At Zero</th><th>B</th><th>Linear Limit</th><th>Linear Range</th>"
                + "<th>Z</th><th>Plane X</th><th>Plane Y</th><th>D</th><th>Slope</th><th>Slope Err</th><th>Stars</th>"
                + "<th>Saturated</th><th>Non-Linear</th><th>Crossed</th><th>Edge/Void</th><th>Outliers</th></tr></thead><tbody>");
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
        return "<div class='metric-box'><span class='metric-value'>" + value + "</span><span class='metric-label'>" + label + "</span></div>";
    }

    private static String compactMetric(String value, String label) {
        return "<div class='metric-box compact'><span class='metric-value'>" + value + "</span><span class='metric-label'>" + label + "</span></div>";
    }

    private static String configItem(String label, String value) {
        return "<div class='config-item'><span>" + label + "</span><span class='val'>" + value + "</span></div>";
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
