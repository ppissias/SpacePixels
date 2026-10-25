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

import eu.startales.spacepixels.util.Statistics;
import eu.startales.spacepixels.util.SkyMath;
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
    private static final int COMPARISON_STARS = 3;
    private static final double COMPARISON_MAG_WINDOW = 0.5;
    private static final int CUTOUT_DISPLAY_SIZE = 150;
    /** Catalogue search radius: this many pixels, kept between the two limits below. */
    private static final double SEARCH_RADIUS_PIXELS = 4.0;
    private static final double MIN_SEARCH_RADIUS_ARCSEC = 10.0;
    private static final double MAX_SEARCH_RADIUS_ARCSEC = 60.0;
    /** Most constant stars drawn in the noise chart; candidates are always drawn. */
    private static final int MAX_CONSTANT_CHART_POINTS = 3000;

    /**
     * What the check measures, independent of this star. The mouse-over follows it with the pass or fail result.
     * SPLIT_HALF pairs consecutive usable points into non-overlapping pairs. LINEARITY looks at every
     * measurement flag on the star, including frames that are not in the light curve.
     */
    static String gateQuestion(String gate) {
        switch (gate) {
            case "DATA":
                return "Checks that enough usable measurements cover a sufficient time span.";
            case "AMPLITUDE":
                return "Checks that the measured amplitude exceeds both the session's reporting threshold and the noise threshold.";
            case "PERSISTENCE":
                return "Checks that the variation persists across several consecutive frames.";
            case "SPLIT_HALF":
                return "Checks agreement between the two sequences formed by pairing consecutive usable measurements into non-overlapping pairs.";
            case "APERTURE":
                return "Compares amplitudes measured with small and large photometric apertures. A discrepancy can indicate contamination or other aperture-dependent effects.";
            case "SYSTEMATICS":
                return "Checks for associations with transparency, stellar-image width, sky background, and registration offsets.";
            case "LOCAL":
                return "Checks whether nearby comparison stars share the candidate's variation with a similar amplitude.";
            case "LINEARITY":
                return "Checks for saturation and measurements brighter than the estimated linear limit.";
            default:
                return gate;
        }
    }

    /**
     * Pass or fail sentence for this star. A LOCAL failure with fewer than three neighbours is insufficient
     * data, not evidence that the neighbours share the pattern.
     */
    static String gateOutcome(String gate, boolean failed, int localComparisonStars) {
        if ("LOCAL".equals(gate)) {
            if (!failed) {
                return "No sufficiently similar variation was found among the usable nearby comparison stars";
            }
            if (localComparisonStars < 3) {
                return "Too few usable nearby comparison stars to assess whether the variation is shared; at least three are required";
            }
            return "Nearby comparison stars share a similar pattern and amplitude, suggesting a local systematic effect";
        }
        switch (gate) {
            case "DATA":
                return failed ? "Too few usable measurements or insufficient time coverage"
                        : "Sufficient usable measurements and time coverage";
            case "AMPLITUDE":
                return failed ? "Amplitude does not exceed the reporting threshold or the noise threshold"
                        : "Amplitude exceeds both thresholds";
            case "PERSISTENCE":
                return failed ? "Variation does not persist across enough consecutive frames"
                        : "Variation persists across several consecutive frames";
            case "SPLIT_HALF":
                return failed ? "The paired measurement sequences show insufficient agreement"
                        : "The paired measurement sequences agree sufficiently";
            case "APERTURE":
                return failed ? "Amplitudes differ by more than the allowed tolerance"
                        : "Amplitudes agree within the allowed tolerance";
            case "SYSTEMATICS":
                return failed ? "The measured variation exceeds the allowed association with one or more monitored frame properties"
                        : "Associations with the monitored frame properties remain within the allowed limits";
            case "LINEARITY":
                return failed ? "At least one measurement was flagged as saturated or brighter than the estimated linear limit"
                        : "No measurement was flagged as saturated or brighter than the estimated linear limit";
            default:
                return gate;
        }
    }

    private static String gateTooltip(String gate, boolean failed, int localComparisonStars) {
        return gateQuestion(gate) + " " + gateOutcome(gate, failed, localComparisonStars) + ".";
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
        writeCsvExports(context, analysis);
    }

    // =================================================================
    // Overview and readiness
    // =================================================================

    private static void writeOverview(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                                      PipelineTelemetry.PhotometryTelemetry t) {
        boolean notReady = "NOT_READY".equals(t.verdict);
        report.println("<div class='panel'>");
        report.println("<h2>Variable Stars</h2>");
        String limitedAmplitude = formatMag(context.config.variableLimitedMinAmplitudeMag, 2);
        report.println("<p class='section-lede'>Differential photometry measures the brightness of isolated stars in frames that passed quality control. "
                + "Each star is compared with an ensemble of measured stars to correct for transparency changes shared across the field. "
                + "A star is reported as a variable candidate when its brightness scatter and the coherence of its changes between successive frames stand out relative to stars of similar brightness, and it meets the checks shown on its card. "
                + "<a href='photometry_stars.csv' target='_blank'>[All stars CSV]</a> "
                + "<a href='photometry_lightcurves.csv' target='_blank'>[Candidate light curves CSV]</a> "
                + "<a href='photometry_frames.csv' target='_blank'>[Per-frame CSV]</a></p>");

        report.println("<div class='flex-container'>");
        report.println("<div class='metric-box'" + tipAttr("Result of the checks for photometric suitability. Ready: no evidence of nonlinearity found. Limited: only amplitudes of at least "
                + limitedAmplitude + " mag are reported. Not ready: stars are not scored.")
                + " style='border-left-color:" + verdictColor(t.verdict) + ";'><span class='metric-value'>"
                + verdictBadge(t.verdict) + "</span><span class='metric-label'>Readiness</span></div>");
        report.println(metricBox(t.framesUsed + " <span style='color:#777; font-size:16px;'>/ " + t.framesAnalyzed + "</span>", "Frames used / analysed",
                "Frames retained for photometry, out of the frames analysed after the initial quality filter."));
        if (t.framesExcludedSpatial > 0) {
            report.println(metricBox(String.valueOf(t.framesExcludedSpatial), "Frames excluded: spatial residuals",
                    "Frames omitted from variable-star photometry because a region of the field retains a brightness offset relative to the centre after ensemble correction."));
        }
        report.println(metricBox(String.valueOf(t.starsSelected), "Stars measured",
                "Isolated stars included in the photometric measurements. Individual measurements may later be excluded."));
        if (!notReady) {
            report.println(metricBox(String.valueOf(t.starsScored), "Stars scored",
                "Measured stars with enough usable data for variability scoring against stars of similar brightness."));
            report.println(metricBox(String.valueOf(t.highConfidence), "High-confidence candidates",
                "Variable candidates that met the detection criteria and passed all eight checks."));
            report.println(metricBox(String.valueOf(t.possible), "Possible candidates",
                "Variable candidates that met the detection criteria but failed exactly one check."));
        }
        report.println(metricBox(formatMag(t.medianLinearRangeMag, 1), "Estimated linear range (mag)",
                "Width of the stellar brightness range estimated to remain linear by the star-shape check."));
        report.println(metricBox(formatMag(t.zeroPointRangeMag, 3), "Transparency range (mag)",
                "Range of the ensemble zero-point changes, excluding the most extreme frames. This indicates changes in transparency across the session."));
        report.println("</div>");

        report.println(verdictExplanationHtml(context, t));
        report.println("<div class='astro-note'><strong>Ready</strong> means the checks found no evidence of nonlinearity; it does not prove that the data are linear. "
                + "Some nonlinear transformations, such as a pure power-law stretch from zero, can escape these checks. "
                + "<strong>Limited</strong> sessions report only candidates with measured amplitudes of at least " + limitedAmplitude + " mag. "
                + "<strong>Not ready</strong> sessions are not scored, but their light curves are still exported for diagnosis.</div>");
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
                reasons.add("sky background is clipped at zero in " + formatPercent(t.medianFloorClippedFraction) + " of the sky pixels, which can bias photometry of faint stars");
            }
            if ("LIMITED".equals(t.shapeLinearityCheck)) {
                reasons.add("the estimated linear range is only " + formatMag(t.medianLinearRangeMag, 1) + " mag ("
                        + formatMag(context.config.linearityMinRangeMag + 1.0, 1) + " mag is needed for Ready)");
            }
            if ("INCONCLUSIVE".equals(t.responseCheck)) {
                reasons.add(Double.isFinite(t.zeroPointRangeMag)
                        ? "the ensemble zero point changed by only " + formatMag(t.zeroPointRangeMag, 3) + " mag (at least "
                        + formatMag(context.config.linearityMinZeroPointRangeMag, 2) + " mag is needed), so the brightness-response check could not judge whether bright and faint stars change by the same amount in magnitudes"
                        : "the brightness-response check could not be run");
            }
            html.append("<strong>Limited because</strong> ").append(reasons.isEmpty() ? "a readiness check was inconclusive" : String.join("; ", reasons))
                    .append(". Only candidates with a measured amplitude of at least ").append(formatMag(context.config.variableLimitedMinAmplitudeMag, 2)).append(" mag are reported.");
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
        report.println("<p class='compact-note'>Accurate photometry requires a linear response: twice the incoming light should produce twice the measured signal. "
                + "These checks look for departures from that relationship.</p>");
        report.println("<div style='display:grid; grid-template-columns: max-content max-content 1fr; gap: 8px 14px; align-items: baseline; font-size: 13px; margin-bottom: 14px;'>");
        report.println(checkRow(t.quantisationCheck, "A &middot; Data depth",
                "Checks for insufficient pixel-value precision, including 8-bit or heavily quantised data, and for sky-background values clipped at zero. These effects can bias photometry, especially for faint stars.",
                t.distinctPixelLevels + " distinct pixel levels; " + formatPercent(t.medianFloorClippedFraction) + " of sky pixels at zero."));
        report.println(checkRow(t.shapeLinearityCheck, "B &middot; Star shapes",
                "Compares the profiles of bright and faint stars. A change in concentration at the bright end can indicate saturation or nonlinearity and is used to estimate the bright limit for photometry.",
                "Median estimated linear range: " + formatMag(t.medianLinearRangeMag, 2) + " mag. Stars brighter than instrumental magnitude "
                        + formatMag(t.medianLinearLimitMag, 2) + " are set aside. " + t.framesFailingShapeLinearity + " of " + t.framesAnalyzed + " frames failed this check."));
        report.println(checkRow(t.responseCheck, "D &middot; Brightness response",
                "When transparency changes uniformly across the field, bright and faint stars should change by the same amount in magnitudes. A brightness-dependent response can indicate nonlinearity.",
                "Ensemble zero-point range: " + formatMag(t.zeroPointRangeMag, 3) + " mag (at least " + formatMag(context.config.linearityMinZeroPointRangeMag, 2)
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
            report.println("<div class='astro-note'>Measurements flagged: " + t.measurementsSaturated + " saturated, " + t.measurementsNonlinear + " nonlinear, "
                    + t.measurementsCrossing + " moving-object crossings, " + t.measurementsEdgeOrVoid + " at an edge or in a void, "
                    + t.measurementsOutlier + " isolated outliers, " + t.measurementsContaminated + " shape contamination. These measurements are omitted from the ensemble.</div>");
        }
        report.println("<details class='foldable-streak-details'><summary>All readiness diagnostics</summary><div class='foldable-streak-body'>");
        report.println("<p class='compact-note'>Hover a row for the definition of that diagnostic.</p>");
        report.println("<div class='compact-threshold-grid'>");
        report.println(configItem("A · Quantisation", statusBadge(t.quantisationCheck),
                "Pixel values are restricted to discrete levels. Too few levels can limit photometric precision. This check also counts sky pixels clipped at zero."));
        report.println(configItem("A · Distinct pixel levels", String.valueOf(t.distinctPixelLevels),
                "Number of distinct pixel values in the middle frame. A 16-bit frame has thousands. An 8-bit or heavily quantised frame has few."));
        report.println(configItem("A · Sky pixels at zero (%)", formatPercent(t.medianFloorClippedFraction),
                "Share of sky pixels at the lowest stored value. Clipping the sky background can bias photometry, especially for faint stars. The direction of the bias depends on the background estimate."));
        report.println(configItem("B · Star-shape check", statusBadge(t.shapeLinearityCheck),
                "Compares the profiles of bright and faint stars. A change in concentration at the bright end can indicate saturation or nonlinearity."));
        report.println(configItem("B · Median estimated linear limit (inst. mag)", formatMag(t.medianLinearLimitMag, 2),
                "Median instrumental-magnitude boundary estimated by the star-shape check. Brighter measurements fall outside the estimated linear range. This is not a calibrated catalogue magnitude."));
        report.println(configItem("B · Median estimated linear range (mag)", formatMag(t.medianLinearRangeMag, 2) + " mag",
                "Width of the stellar brightness range estimated to remain linear by the star-shape check."));
        report.println(configItem("B · Frames failing the star-shape check", String.valueOf(t.framesFailingShapeLinearity),
                "Frames in which the bright-star profiles depart from the faint-star reference across the measured range."));
        report.println(configItem("D · Brightness-response check", statusBadge(t.responseCheck),
                "When transparency changes uniformly, bright and faint stars should change by the same amount in magnitudes. A brightness-dependent response can indicate nonlinearity."));
        report.println(configItem("D · Zero-point range (mag)", formatMag(t.zeroPointRangeMag, 3) + " mag",
                "Range of the ensemble zero-point changes, excluding the most extreme frames. The check needs a real change in transparency before it can judge the response."));
        report.println(configItem("D · Response-slope / zero-point correlation (r)", formatMag(t.slopeZeroPointCorrelation, 2),
                "Correlation between the brightness-response slope and the ensemble zero point, from -1 to 1. A value near 1 or -1 means the slope grows as transparency changes."));
        report.println(configItem("D · Response-slope / sky correlation (r)", formatMag(t.slopeSkyCorrelation, 2),
                "Correlation between the brightness-response slope and the sky background, from -1 to 1. A value near 1 or -1 can indicate a stretch or a sky-subtraction problem."));
        report.println(configItem("D · Frames failing the brightness-response check", String.valueOf(t.framesFailingResponse),
                "Frames in which the photometric residuals depend on instrumental magnitude."));
        report.println(configItem("Session FWHM from source extraction (px)", formatMag(t.sessionFwhm, 2) + " px",
                "Full width at half maximum, a measure of the width of stellar images, from the source extraction used for detection."));
        report.println(configItem("Saturation level", formatMag(t.saturationLevel, 0) + " ADU",
                "Pixel value treated as saturated. Measurements at or above it are flagged and omitted from the ensemble."));
        report.println(configItem("Stars rejected: crowding", String.valueOf(t.starsRejectedCrowded),
                "Stars with a neighbour close enough that the photometric aperture would mix their light."));
        report.println(configItem("Stars rejected: saturated on the master", String.valueOf(t.starsRejectedSaturated),
                "Stars already saturated on the combined master image."));
        report.println(configItem("Stars rejected: edge or void", String.valueOf(t.starsRejectedEdgeOrVoid),
                "Stars too close to the edge, or inside an area the pipeline leaves empty."));
        report.println(configItem("Stars rejected: elongated / on a streak", t.starsRejectedElongated + " / " + t.starsRejectedStreak,
                "Elongated stars, and stars that sit on a moving streak, are not used as comparison stars."));
        report.println(configItem("Stars not selected: star-count limit", String.valueOf(t.starsRejectedByCap),
                "Isolated stars beyond the maximum number photometry measures. The brightest isolated stars are kept."));
        report.println(configItem("Stars excluded: mostly saturated or nonlinear", String.valueOf(t.starsExcludedMostlyNonlinear),
                "Stars flagged as saturated or brighter than the estimated linear limit in more than half of their measurements."));
        report.println(configItem("Stars below minimum signal-to-noise ratio", String.valueOf(t.starsExcludedLowSnr),
                "Stars whose typical measurement is below the minimum signal-to-noise ratio, so they are not scored."));
        report.println(configItem("Frames excluded: registration", String.valueOf(t.framesExcludedRegistration),
                "Frames whose registration offsets disagree by more than the configured spread."));
        report.println(configItem("Frames excluded: star-shape check (B)", String.valueOf(t.framesExcludedShapeLinearity),
                "Frames excluded because the star-shape check failed."));
        report.println(configItem("Frames excluded: spatial residuals", String.valueOf(t.framesExcludedSpatial),
                "Frames omitted from variable-star photometry because a region of the field retains a brightness offset relative to the centre after ensemble correction."));
        report.println(configItem("Frames excluded: brightness-response check (D)", String.valueOf(t.framesExcludedResponse),
                "Frames excluded because bright and faint stars did not change by the same amount in magnitudes."));
        report.println(configItem("Frames excluded: too few stars", String.valueOf(t.framesExcludedTooFewStars),
                "Frames without enough usable stars to solve the ensemble."));
        report.println(configItem("Measurements flagged: saturation / nonlinearity", t.measurementsSaturated + " / " + t.measurementsNonlinear,
                "Measurements omitted from the ensemble. Saturation is the camera limit. Nonlinearity is brighter than the estimated linear limit from the star-shape check."));
        report.println(configItem("Measurements flagged: moving-object crossings", String.valueOf(t.measurementsCrossing),
                "Measurements omitted because a moving object crossed the measurement region."));
        report.println(configItem("Measurements flagged: edge or void", String.valueOf(t.measurementsEdgeOrVoid),
                "Measurements omitted because the star fell at the edge or in an empty area of that frame."));
        report.println(configItem("Measurements flagged: isolated outliers", String.valueOf(t.measurementsOutlier),
                "Measurements omitted as a single frame far from the star's other measurements, with no neighbouring frame to support it. A change that lasts several frames is kept."));
        report.println(configItem("Measurements flagged: shape contamination", String.valueOf(t.measurementsContaminated),
                "Measurements omitted because the stellar profile in that frame does not match the star's usual profile, often from a hot pixel or a cosmic ray."));
        report.println(configItem("Photometry processing time", String.format(Locale.US, "%.2f s", t.processingTimeMs / 1000.0),
                "Time taken by the variable-star photometry stage."));
        report.println("</div>");
        report.println("</div></details>");
        if (!t.gateFailureCounts.isEmpty()) {
            StringBuilder gates = new StringBuilder();
            for (Map.Entry<String, Integer> e : t.gateFailureCounts.entrySet()) {
                if (gates.length() > 0) gates.append(", ");
                gates.append(e.getKey()).append(": ").append(e.getValue());
            }
            report.println("<div class='astro-note'" + tipAttr("How many candidate stars failed each check. A star can fail more than one. Hover the checks on a candidate card for the explanation and the result.")
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
        return "<div style='display:flex; flex-wrap:wrap; align-items:center; gap:10px; margin:3px 0;'>"
                + "<div style='flex: 0 1 360px; max-width:100%; background:#262626; border-radius:3px; height:14px;'><div style='width:"
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
            report.println("<p class='compact-note'>Each dot represents one scored star. Brighter stars are on the left and fainter stars on the right. "
                    + "The vertical axis shows the scatter of the measured brightness in magnitudes. The dashed line shows the expected scatter for stars of similar brightness. "
                    + "Stars well above it have excess scatter and may be variable; the other checks help assess whether that excess is likely to be intrinsic." + sampleNote + "</p>");
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
        report.println("<p class='compact-note'>The horizontal axis is the frame number. A blue point is a frame that was used. A red cross is an excluded frame; hover it for the file name and the reason. "
                + "The sentence under each title says what the vertical position means.</p>");
        report.println(legend(
                legendDot(PhotometrySvgChart.SERIES_BLUE, "Used frame"),
                legendCross(PhotometrySvgChart.STATUS_CRITICAL, "Excluded frame"),
                legendDash(PhotometrySvgChart.REFERENCE_INK, "Configured threshold")));
        report.println("<div class='flex-container' style='gap:14px;'>");
        report.println(frameChart(frames, "Frame zero point, Z (mag)",
                "Ensemble brightness offset relative to the session median frame; positive values indicate a fainter frame. The axis is drawn with brighter frames higher.",
                "mag", f -> f.zeroPoint, true, Double.NaN, Double.NaN, null));
        double slopeLimit = context.config.linearityMaxFrameSlope;
        report.println(frameChart(frames, "Check D · Response slope (mag/mag)",
                "Slope of photometric residuals against instrumental magnitude, used to assess brightness-dependent response.",
                "mag/mag", f -> f.responseSlope, false, -slopeLimit, slopeLimit, "threshold"));
        report.println(frameChart(frames, "Check B · Estimated linear range (mag)",
                "Width of the stellar brightness range estimated to remain linear in each frame.",
                "mag", f -> f.linearRangeMag, false, context.config.linearityMinRangeMag, Double.NaN, "threshold"));
        report.println(frameChart(frames, "Stellar-image width, FWHM (px)",
                "Width of stellar images measured by the photometry stage from bright-star moments.",
                "px", f -> f.fwhm, false, Double.NaN, Double.NaN, null));
        report.println(frameChart(frames, "Registration spread (px)",
                "Spread of the registration offsets used to assess alignment consistency.",
                "px", f -> f.registrationSpread, false,
                context.config.photometryMaxRegistrationSpreadPixels, Double.NaN, "threshold"));
        report.println(frameChart(frames, "Measurements flagged for moving-object crossings",
                "Number of stellar measurements flagged because a moving object crossed the measurement region.",
                "stars", f -> (double) f.crossing, false, Double.NaN, Double.NaN, null, true));
        report.println("</div>");

        // --- Check B profiles ---
        report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Check B · Stellar concentration versus brightness</h3>");
        report.println("<p class='compact-note'>Each line represents one frame. The plot shows the concentration index in brightness bins, relative to that frame's faint-star reference. "
                + "Brighter stars are on the left and fainter stars on the right. Values within the shaded tolerance band are consistent with the reference profile. "
                + "Departures at the bright end can indicate saturation or nonlinearity.</p>");
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
                tips.add(String.format(Locale.US, "%s%s. Mean instrumental magnitude: %.2f; larger values indicate fainter stars. Measured scatter: %.4f mag. Expected scatter at this brightness: %.4f mag.",
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
        return Statistics.medianIgnoringNaN(window);
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
                    f.used ? "This frame is used." : "This frame is excluded: " + f.exclusionReason);
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
                "Stellar concentration versus brightness, relative to each frame's faint-star reference. Brighter stars are on the left. Values inside the band are consistent with that reference.",
                "Instrumental magnitude (fainter to the right)", "Concentration relative to faint stars");
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
                String tip = String.format(Locale.US, "Frame %d (%s). Star-shape check %s. Estimated linear limit: instrumental magnitude %s. Estimated linear range: %s mag. %s",
                        f.frameIndex + 1, f.filename, "FAIL".equals(f.shapeLinearityStatus) ? "failed" : f.shapeLinearityStatus.toLowerCase(Locale.ROOT),
                        formatMag(f.linearLimitMag, 2), formatMag(f.linearRangeMag, 2),
                        failed ? "Departures at the bright end can indicate saturation or nonlinearity. This frame is excluded from photometry."
                                : "Values within the shaded band are consistent with this frame's faint-star reference.");
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
        VariableStarPresentation presentation = context.variablePresentation(analysis);
        List<StarLightCurve> cards = presentation.ordered;
        List<StarLightCurve> rejected = new ArrayList<>();
        for (StarLightCurve star : analysis.candidates) {
            if (star.tier == VariabilityTier.REJECTED) {
                rejected.add(star);
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
            report.println("<p class='compact-note'><strong>Observed variation in this session</strong> is the 95th minus the 5th percentile of the usable differential magnitudes. "
                    + "It describes the observed time span, rather than a full-cycle amplitude. Catalogue ranges and amplitudes use the listed passbands; "
                    + "differences in filter, time coverage and phase limit direct comparisons.</p>");
            report.println("<p class='compact-note'>Presentation: high confidence first; "
                    + DetectionReportGenerator.escapeHtml(context.settings.getVariableStarReportOptions().sortOrder().toString())
                    + ". V numbers stay fixed across the table, maps, CSVs and viewers. Showing " + presentation.cards.size()
                    + " of " + cards.size() + " detailed cards (limit "
                    + context.settings.getVariableStarReportOptions().maxCandidateCards()
                    + ", set under Variable Stars in Detection Settings). All candidates remain in the table, maps and CSVs. "
                    + (context.settings.getVariableStarReportOptions().sortOrder() == eu.startales.spacepixels.config.VariableStarReportOptions.SortOrder.SHORTEST_PERIOD
                    ? "Periods come from the nearest saved VSX match; unknown periods follow known periods within each confidence tier." : "") + "</p>");
            VariableStarMapWriter.write(report, context, analysis, presentation.identities);
            SessionCatalogue sessionCatalogue = SessionCatalogue.of(context);
            writeCandidateTable(report, context, analysis, cards, sessionCatalogue);
            CatalogueComparisonWriter.writeData(report, context, analysis, presentation.identities, sessionCatalogue);
            writeFollowUpGuidance(report);
            for (StarLightCurve star : presentation.cards) {
                writeCandidateCard(report, context, analysis, star, presentation.number(star), sessionCatalogue);
            }
            if (cards.size() > presentation.cards.size()) {
                report.println("<p class='compact-note'>" + (cards.size() - presentation.cards.size()) + " more candidates are listed in the table, maps and photometry_stars.csv.</p>");
            }
        }

        if (!rejected.isEmpty()) {
            report.println("<h3 style='color:#e6e6e6; font-size:15px; margin:18px 0 6px 0;'>Rejected candidates (" + rejected.size() + ")</h3>");
            report.println("<p class='compact-note'>Stars whose scores stood out but that failed two or more gates. Listed so you can see why a suspected variable was not reported.</p>");
            report.println("<div class='scroll-box compact-table-box'>");
            report.println("<table><thead><tr>"
                + th("Star", "Star number in this session's measurement.")
                + th("Position", "Where the star is, in pixels and on the sky when the field is solved.")
                + th("Mean Mag", "Mean instrumental magnitude. Larger values indicate fainter stars; this is not a calibrated catalogue magnitude.")
                + th("Observed variation in this session (mag)", "95th minus the 5th percentile of the usable differential magnitudes within this session; not a full-cycle amplitude.")
                + th("Scatter z", "Standardised score indicating how unusual the scatter is relative to stars of similar brightness.")
                + th("Stetson J z", "Standardised Stetson J score indicating how unusual the coherence between successive measurements is relative to stars of similar brightness.")
                + th("Failed Gates", "The checks this star failed. Two or more, and it is listed here instead of as a candidate.")
                + th("Look Up", "Searches the same sky position in VSX or SIMBAD.")
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
        report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.VARIABLE_STAR, number) + "' style='border-left-color:" + tierColor + ";'>");
        report.println("<div class='detection-title'>" + FindingCategory.VARIABLE_STAR.id(number) + " &middot; " + tierLabel(star.tier) + " &middot; star #" + star.id + "</div>");
        report.println("<div class='astro-note' style='margin-top:-8px; margin-bottom:8px;'>"
                + DetectionReportGenerator.escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, star.x, star.y))
                + "</div>");
        report.println("<p style='font-size:14px; color:#e6e6e6; margin: 0 0 10px 0; line-height:1.5;'>" + DetectionReportGenerator.escapeHtml(plainSummary(analysis, star)) + "</p>");
        report.println(catalogueEntryHtml(context, star, sessionCatalogue));
        SkyTarget comparisonTarget = skyTarget(context, star.x, star.y);
        boolean comparisonMatched = comparisonTarget != null && !sessionCatalogue.variablesNear(
                comparisonTarget.raDegrees, comparisonTarget.decDegrees, comparisonTarget.radiusArcsec).isEmpty();
        report.println(CatalogueComparisonWriter.slot(number, false, comparisonMatched));
        report.println(catalogueCrossCheckHtml(context, star, number));

        report.println("<details class='foldable-streak-details'><summary>Statistics</summary><div class='foldable-streak-body'>");
        report.println("<p class='compact-note'>The numbers behind the checks. Hover a box for what it means.</p>");
        report.println("<div class='flex-container'>");
        report.println(compactMetric(formatMag(star.amplitude, 3), "Observed variation in this session (mag)",
                "95th minus the 5th percentile of the usable differential magnitudes within this session; not a full-cycle amplitude."));
        report.println(compactMetric(formatMag(star.meanMag, 2), "Mean instrumental magnitude",
                "Mean brightness on the instrumental magnitude scale. Larger values indicate fainter stars; this is not a calibrated catalogue magnitude."));
        report.println(compactMetric(formatMag(star.excessScatter, 1) + "x", "Scatter ratio",
                "Measured brightness scatter divided by the expected scatter for stars of similar brightness. A value near 1 matches the scatter of those stars."));
        report.println(compactMetric(formatMag(star.scatterZ, 1), "Scatter significance (z)",
                "Standardised score indicating how unusual the scatter is relative to stars of similar brightness. A candidate needs both this score and the Stetson J score to stand out."));
        report.println(compactMetric(formatMag(star.stetsonJZ, 1), "Coherence significance (Stetson J z)",
                "Standardised Stetson J score indicating how unusual the coherence between successive measurements is relative to stars of similar brightness."));
        report.println(compactMetric(String.valueOf(star.usableFrames), "Usable frames",
                "Number of frames with measurements retained for this star."));
        report.println(compactMetric(Double.isNaN(star.timeSpanMinutes) ? "n/a" : formatMag(star.timeSpanMinutes, 0) + " min", "Time span",
                "Time between the first and last usable measurements."));
        report.println(compactMetric(formatMag(star.splitHalfCorrelation, 2), "Paired-sequence correlation (r)",
                "Correlation between the first and second points of consecutive, non-overlapping pairs of usable measurements. This is the SPLIT_HALF statistic. White noise is near zero; a change spanning several frames is high."));
        report.println(compactMetric(formatMag(star.apertureAmplitudeDifference, 2), "Aperture amplitude difference",
                "Absolute difference between the small-aperture and large-aperture amplitudes, divided by the candidate amplitude. This is a fraction of the amplitude, not a difference in magnitudes. A discrepancy can indicate contamination or other aperture-dependent effects."));
        report.println(compactMetric(formatMag(star.maxSystematicsCorrelation, 2)
                + (star.maxSystematicsSource != null ? " <span style='font-size:10px; color:#999;'>" + DetectionReportGenerator.escapeHtml(star.maxSystematicsSource) + "</span>" : ""),
                "Maximum systematics correlation (r)",
                "Largest absolute correlation between the light curve and the monitored frame properties: ensemble zero point, stellar-image width, sky background, and registration offsets."));
        report.println(compactMetric(formatMag(star.systematicsLimitMag, 3), "Systematics limit (mag)",
                "Response expected from the most correlated monitored frame property for ordinary stars of this brightness, multiplied by the response factor. The systematics check fails a correlated candidate when its amplitude does not exceed this limit."));
        report.println(compactMetric(formatMag(star.maxFrameToFrameSystematicsCorrelation, 2), "Frame-to-frame systematics correlation (r)",
                "Largest correlation between successive changes in the light curve and successive changes in a monitored frame property. A high value means the star and that property change together from frame to frame."));
        report.println(compactMetric(formatMag(star.localCorrelation, 2) + " <span style='font-size:10px; color:#999;'>n=" + star.localComparisonStars + "</span>", "Local correlation (r)",
                "Median absolute correlation with the usable nearby comparison stars. n is the number of neighbours used."));
        report.println(compactMetric(formatMag(star.localSharedFraction, 2), "Local shared fraction",
                "Median absolute regression slope of the nearby comparison stars on this star: the fraction of this star's change that appears in those neighbours, not an amplitude in magnitudes. About 0.3 or more, together with a high local correlation, fails the LOCAL check."));
        report.println("</div>");
        report.println("</div></details>");

        // Gates
        report.println("<p class='compact-note' style='margin-bottom:6px;'>Hover a check for its explanation and this star's result.</p>");
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
                legendHollow(PhotometrySvgChart.CONTEXT_GRAY, "Excluded from analysis"),
                legendLine(PhotometrySvgChart.CONTEXT_GRAY, "Constant comparison stars (offset)")));
        report.println("<div class='image-container' style='flex-wrap:wrap;'>");
        report.println(lightCurveChart(analysis, star, comparisons, tierColor));
        report.println(cutouts(context, analysis, star, number));
        report.println("</div>");
        report.println("</div>");
    }

    /**
     * Compact table of every high-confidence and possible candidate. The "Known as" column is filled at once for
     * candidates matched with the VSX stars of the session's sky catalogue, with ordinary catalogue-star identities
     * as a fallback. The "Identify in VSX" button still looks up those without a VSX match through the lookup proxy.
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
        if (sky && sessionCatalogue.isAvailable() && !sessionCatalogue.catalogue.stars.isEmpty()
                && !sessionCatalogue.catalogue.hasStarIdentifiers()) {
            report.println("<p class='compact-note'>This saved sky catalogue has no Gaia star identifiers. "
                    + "Use <strong>Fetch Sky Catalogue</strong> again, then regenerate the report to name candidates "
                    + "without a VSX match. A star must be within the fetched Gaia magnitude limit and matching radius.</p>");
        }
        report.println("<div class='scroll-box compact-table-box' style='max-height: 420px;'>");
        report.println("<table><thead><tr>"
                + th("#", "Stable candidate number, shared with maps, CSVs and viewers. A linked number has a detailed card below.")
                + th("Tier", "High confidence passed every check. Possible failed exactly one.")
                + th("Position", "Where the star is, in pixels and on the sky when the field is solved.")
                + th("Mean Inst. Mag", "Mean instrumental magnitude. Larger values indicate fainter stars; this is not a calibrated catalogue magnitude.")
                + th("Observed variation in this session", "95th minus the 5th percentile of the usable differential magnitudes within this session; not a full-cycle amplitude.")
                + th("Time Span", "Time between the first and last usable measurements.")
                + th("Change", "The rough shape of the light curve: a steady fade, a steady rise, a dip, a peak, or up and down.")
                + th("Failed Check", "The one check a possible candidate failed. High-confidence candidates have none.")
                + (sky ? th("Known As", "VSX variable-star identification when matched; otherwise a proper name or Gaia DR3 designation from the session catalogue. A catalogue-star match does not establish known variability.") : "")
                + "</tr></thead><tbody>");
        VariableStarPresentation presentation = context.variablePresentation(analysis);
        for (StarLightCurve star : candidates) {
            int number = presentation.number(star);
            String label = FindingCategory.VARIABLE_STAR.id(number);
            String name = presentation.hasCard(star) ? "<a href='#" + context.findingAnchor(FindingCategory.VARIABLE_STAR, number) + "'>" + label + "</a>" : label;
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
                report.print("<td title='Matched with the AAVSO VSX stars of the session&#39;s sky catalogue'><span data-known-as-sidecar='"
                        + CatalogueComparisonWriter.key(number) + "'>" + knownAsHtml(matches) + "</span>"
                        + CatalogueComparisonWriter.slot(number, true, true) + "</td>");
            } else if (target != null) {
                String fallback = catalogueStarHtml(sessionCatalogue, target);
                String sidecar = "photometry_v" + number + "_vsx.json";
                String hidden = DetectionReportAstrometry.buildLiveRenderButtonHtml("vsx", vsxTapUrl(target), "photometry-v" + number + "-vsx-row",
                        "VSX", sidecar, String.format(Locale.US, "AAVSO VSX within %.0f arcsec of %s", target.radiusArcsec, FindingCategory.VARIABLE_STAR.id(number)))
                        .replace("<button ", "<button style='display:none' data-identify-all='1' ");
                report.print("<td><span data-known-as-sidecar='" + sidecar + "' data-catalogue-star='"
                        + DetectionReportGenerator.escapeHtml(fallback) + "'>" + (fallback.isEmpty() ? "" : fallback + "<br>")
                        + "<span style='color:#777;'>VSX: not checked</span>" + hidden
                        + "</span>" + CatalogueComparisonWriter.slot(number, true, false) + "</td>");
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

    /** Ordinary catalogue identity, distinct from a VSX match or variability classification. */
    static String catalogueStarHtml(SessionCatalogue catalogue, SkyTarget target) {
        SessionCatalogue.StarIdentity identity = target == null ? null
                : catalogue.starIdentityNear(target.raDegrees, target.decDegrees, Math.min(target.radiusArcsec, 5.0));
        if (identity == null) return "";
        return "Catalogue star: <strong>" + DetectionReportGenerator.escapeHtml(identity.name()) + "</strong>"
                + " <span style='color:#888;'>(" + DetectionReportGenerator.escapeHtml(identity.source()) + ", "
                + String.format(Locale.US, "%.1f", identity.separationArcsec()) + "&Prime; away)</span>";
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
            String fallback = catalogueStarHtml(sessionCatalogue, target);
            if (!fallback.isEmpty()) html.append(fallback).append(".<br>");
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

    /** Labels catalogue magnitudes separately from this session's observed variation. */
    static String vsxRange(SkyCatalogue.VariableStar variable) {
        if (variable.max == null) {
            return null;
        }
        String band = variable.band == null ? "" : " " + variable.band;
        if (variable.min == null) {
            return String.format(Locale.US, "Catalogue brightest magnitude: %.2f%s", variable.max, band);
        }
        if (variable.minIsAmplitude) {
            return String.format(Locale.US, "Catalogue amplitude: %.2f mag (listed magnitude %.2f%s)", variable.min, variable.max, band);
        }
        return String.format(Locale.US, "Catalogue range: %.2f–%.2f%s", variable.max, variable.min, band);
    }

    /** How much of the period the candidate's measurements span, in words; null without a time span. */
    static String periodCoverage(double periodDays, double spanMinutes) {
        if (!Double.isFinite(spanMinutes) || !Double.isFinite(periodDays) || spanMinutes <= 0 || periodDays <= 0) {
            return null;
        }
        double cycles = spanMinutes / (periodDays * 1440.0);
        String span = spanMinutes >= 120 ? String.format(Locale.US, "%.1f h", spanMinutes / 60.0) : String.format(Locale.US, "%.0f min", spanMinutes);
        String part;
        if (cycles >= 1.5) {
            part = String.format(Locale.US, "%.1f times the catalogued period", cycles);
        } else if (cycles >= 0.001) {
            part = String.format(Locale.US, "%.1f%% of the catalogued period", cycles * 100);
        } else {
            part = "less than 0.1% of the catalogued period";
        }
        return "This session spans " + span + ", " + part + ".";
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
        double pixelScaleArcsec = SkyMath.separationArcsec(centre.getRaDegrees(), centre.getDecDegrees(),
                neighbour.getRaDegrees(), neighbour.getDecDegrees());
        double radius = Math.max(MIN_SEARCH_RADIUS_ARCSEC,
                Math.min(MAX_SEARCH_RADIUS_ARCSEC, SEARCH_RADIUS_PIXELS * pixelScaleArcsec));
        if (!Double.isFinite(radius)) {
            radius = MIN_SEARCH_RADIUS_ARCSEC;
        }
        return new SkyTarget(centre.getRaDegrees(), centre.getDecDegrees(), radius);
    }

    /**
     * Identification helpers for a candidate with a sky position: a live VSX lookup rendered inside the
     * report (through the SpacePixels lookup proxy, cached into the report) plus general-star research links.
     */
    private static String catalogueCrossCheckHtml(DetectionReportContext context, StarLightCurve star, int number) {
        SkyTarget target = skyTarget(context, star.x, star.y);
        if (target == null) {
            return "<div class='astro-note'>Plate-solve the session to search variable-star and general star catalogues for this position.</div>";
        }
        String slotId = "photometry-v" + number + "-vsx";
        String sidecar = "photometry_v" + number + "_vsx.json";
        StringBuilder html = new StringBuilder();
        html.append("<div class='id-links'>");
        html.append(DetectionReportAstrometry.buildLiveRenderButtonHtml("vsx", vsxTapUrl(target), slotId,
                "Check VSX Here", sidecar,
                String.format(Locale.US, "AAVSO VSX within %.0f arcsec of %s", target.radiusArcsec, FindingCategory.VARIABLE_STAR.id(number))));
        html.append(vsxBrowserLink(target));
        html.append("</div>");
        html.append(DetectionReportAstrometry.buildLiveRenderContainerHtml(slotId));
        html.append(researchLinksHtml(target));
        return html.toString();
    }

    /** Compact browser links for table rows; empty without an astrometric solution. */
    private static String lookUpLinks(DetectionReportContext context, StarLightCurve star) {
        SkyTarget target = skyTarget(context, star.x, star.y);
        return target == null ? "-" : "<span style='display:inline-flex; gap:6px;'>" + browserLinks(target) + "</span>";
    }

    private static String browserLinks(SkyTarget target) {
        return vsxBrowserLink(target) + simbadLink(target);
    }

    private static String vsxBrowserLink(SkyTarget target) {
        return externalCatalogueLink(vizierUrl(target, "B/vsx/vsx"), "VSX in VizieR",
                "Search the VSX variable-star catalogue around this measured position.");
    }

    private static String vizierUrl(SkyTarget target, String catalogue) {
        String coords = String.format(Locale.US, "%.6f %+.6f", target.raDegrees, target.decDegrees);
        String radius = String.format(Locale.US, "%.0f", target.radiusArcsec);
        return "https://vizier.cds.unistra.fr/viz-bin/" + (catalogue == null ? "VizieR?" : "VizieR-4?-source=" + urlEncode(catalogue) + "&")
                + "-c=" + urlEncode(coords) + "&-c.rs=" + radius
                + (catalogue == null ? "" : "&-out.add=_r&-sort=_r");
    }

    private static String simbadLink(SkyTarget target) {
        String coords = String.format(Locale.US, "%.6f %+.6f", target.raDegrees, target.decDegrees);
        String radius = String.format(Locale.US, "%.0f", target.radiusArcsec);
        String simbad = "https://simbad.cds.unistra.fr/simbad/sim-coo?Coord=" + urlEncode(coords) + "&Radius=" + radius + "&Radius.unit=arcsec";
        return externalCatalogueLink(simbad, "SIMBAD", "Search for names, object information and published papers around this position.");
    }

    private static String externalCatalogueLink(String url, String label, String description) {
        return "<a class='id-link' href='" + DetectionReportGenerator.escapeHtml(url)
                + "' target='_blank' rel='noopener noreferrer' title='" + DetectionReportGenerator.escapeHtml(description)
                + "'>" + DetectionReportGenerator.escapeHtml(label) + "</a>";
    }

    /** Coordinate searches work even when the downloaded catalogue contains no entry for the star. */
    static String researchLinksHtml(SkyTarget target) {
        if (target == null) return "";
        return "<div class='star-research' style='margin:10px 0;'>"
                + "<p class='compact-note' style='margin:0 0 4px;'><strong>Research this star</strong> &middot; "
                + String.format(Locale.US, "search within %.0f&Prime; of the measured position", target.radiusArcsec) + "</p>"
                + "<div class='id-links'>"
                + externalCatalogueLink(vizierUrl(target, "I/355/gaiadr3"), "Gaia DR3",
                "Search the general Gaia star catalogue, without the downloaded catalogue's magnitude limit; entries may include brightness, colour, parallax and proper motion.")
                + externalCatalogueLink(vizierUrl(target, null), "Other catalogues — VizieR",
                "Search published catalogues around this position, without restricting the search to VSX.")
                + simbadLink(target) + "</div>"
                + "<p class='compact-note' style='margin:4px 0 0;'>Opens online in your browser; no downloaded catalogue or running SpacePixels is needed. "
                + "Nearby entries are possible associations: confirm the same star using separation, brightness and images. "
                + "<a href='#variable-follow-up'>Following up an unmatched candidate</a>.</p></div>";
    }

    private static void writeFollowUpGuidance(PrintWriter report) {
        report.println("<details class='foldable-streak-details' id='variable-follow-up'><summary>Following up an unmatched candidate</summary>"
                + "<div class='foldable-streak-body'><p>High confidence means the candidate passed the SpacePixels checks; "
                + "it does not establish a new variable-star discovery. No VSX match means only that the search found none within its radius.</p>"
                + "<ol><li><strong>Identify the star.</strong> Use the coordinate links on its card to search Gaia DR3, other VizieR catalogues and SIMBAD. "
                + "Check the images, neighbouring stars, angular separation and brightness; catalogue epochs and proper motion can affect positions.</li>"
                + "<li><strong>Check the variability.</strong> Inspect the light curve, uncertainties, comparison stars, flags and raw cutouts. "
                + "Look for blends, saturation, changing seeing, tracking or background effects. More nights and independent survey light curves "
                + "(such as ASAS-SN or ZTF, where available) can strengthen or challenge the finding. "
                + "A missing Gaia variability flag does not establish that the star is constant.</li>"
                + "<li><strong>Prepare evidence before reporting.</strong> Keep the catalogue identifier, coordinates and their epoch, "
                + "observation times, filter, measurements, uncertainties and comparison-star information. The exported instrumental/differential magnitudes "
                + "are not automatically a calibrated submission. Read the "
                + "<a href='https://vsx.aavso.org/index.php?view=about.notice' target='_blank' rel='noopener noreferrer'>AAVSO VSX submission guidance</a> "
                + "and check the literature and relevant survey data before submitting a possible new variable.</li></ol>"
                + "<p>Gaia and SIMBAD identify general objects as well as variables. These research links do not change this candidate's confidence, "
                + "photometry or saved VSX identification.</p></div></details>");
    }

    /**
     * VizieR TAP query for VSX variables inside the search circle, closest first, as JSON.
     */
    static String vsxTapUrl(SkyTarget target) {
        String point = String.format(Locale.US, "POINT('ICRS', %.6f, %.6f)", target.raDegrees, target.decDegrees);
        String query = "SELECT TOP 10 \"OID\", \"Name\", \"Type\", \"max\", \"n_max\", \"f_min\", \"min\", \"n_min\", "
                + "\"l_max\", \"u_max\", \"l_min\", \"u_min\", \"Period\", \"l_Period\", \"u_Period\", \"n_OID\", "
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
                timeAxis ? "Minutes since first frame" : "Frame", "Δmag (brighter up)").invertY().rightMargin(34).interactive();

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
            flaggedTips[j] = tip + " Excluded from analysis: " + plainFlags(star.flags[j]) + ".";
        }
        chart.line(x, used, color, 2, false, null);
        chart.errorBars(x, used, star.magError, color);
        chart.points(x, used, color, 4, PhotometrySvgChart.Marker.CIRCLE, usedTips);
        chart.points(x, flagged, PhotometrySvgChart.CONTEXT_GRAY, 4, PhotometrySvgChart.Marker.HOLLOW_CIRCLE, flaggedTips);
        return "<div>" + chart.render() + "<p class='compact-note'>Error bars show estimated sky-noise uncertainty, a lower bound on total uncertainty. Zoom in to inspect points; drag to pan and Reset to see the full curve.</p></div>";
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
                + "</div><p class='compact-note'>These are raw frame cutouts with one shared stretch. Brightest and faintest refer to the transparency-corrected light curve; clouds, exposure and seeing can change the appearance of the raw images.</p>";
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
        return "<div><a href='" + file + "' target='_blank'><img loading='lazy' decoding='async' src='" + file + "' style='width:" + CUTOUT_DISPLAY_SIZE + "px; image-rendering:pixelated;' alt='"
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
        report.println("<p class='compact-note'>This table summarises the photometric and registration diagnostics for each frame. "
                + "FWHM is measured by the photometry stage from bright-star moments. "
                + "Z is the ensemble zero-point offset relative to the session median frame; positive values indicate a fainter frame. "
                + "The response slope is the slope of photometric residuals against instrumental magnitude used by check D. "
                + "Star counts show the number used in the comparison ensemble and the number measured. "
                + "Hover a column name for its definition.</p>");
        report.println("<p class='compact-note'>After removing the fitted spatial brightness gradient, the frame is excluded if any region in the 3 &times; 3 grid differs from the central region by at least 0.10 mag. "
                + "The frame status shows &ldquo;Spatial residual&rdquo; and the magnitude of the difference. "
                + "The difference is between cell medians, and both the cell and the centre need at least 30 usable stars.</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr>"
                + th("Frame", "Frame number in the session, starting at 1.")
                + th("Filename", "Name of the image file.")
                + th("Status", "Used means this frame was retained for photometry. Excluded names the reason. A spatial residual is the largest absolute difference between a 3 by 3 cell median and the centre-cell median, after the spatial gradient is removed. The frame is excluded when that difference is at least 0.10 mag and both cells have at least 30 usable stars.")
                + th("FWHM", "Full width at half maximum of the stellar images in this frame, in pixels, measured by the photometry stage from bright-star moments.")
                + th("Aperture", "Radius of the main photometric aperture, in pixels.")
                + th("Sky", "Median sky background around the measured stars, in ADU.")
                + th("Offset X", "Registration offset along X, in pixels.")
                + th("Offset Y", "Registration offset along Y, in pixels.")
                + th("Spread", "Spread of the registration offsets, in pixels, used to assess alignment consistency.")
                + th("Sky At Zero", "Share of sky pixels at the lowest stored value. Clipping can bias photometry, especially for faint stars.")
                + th("B", "Star-shape check. Pass means the bright-star profiles are consistent with the faint-star reference.")
                + th("Linear Limit", "Instrumental-magnitude boundary estimated by the star-shape check. Brighter measurements, which have smaller magnitudes, fall outside the estimated linear range.")
                + th("Linear Range", "Width of the stellar brightness range estimated to remain linear in this frame, in magnitudes.")
                + th("Z", "Ensemble zero-point offset relative to the session median frame, in magnitudes. Positive values indicate a fainter frame.")
                + th("Spatial gradient X", "Coefficient of the fitted spatial photometric correction along the image's X direction, in magnitudes from the centre to the edge.")
                + th("Spatial gradient Y", "Coefficient of the fitted spatial photometric correction along the image's Y direction, in magnitudes from the centre to the edge.")
                + th("D", "Brightness-response check. Pass means bright and faint stars change by the same amount in magnitudes.")
                + th("Slope", "Slope of photometric residuals against instrumental magnitude, in magnitudes per magnitude. Near zero is consistent with a brightness-independent response.")
                + th("Slope Err", "Uncertainty of the response slope. A slope smaller than its uncertainty is not distinguished from zero.")
                + th("Stars", "Stars used in the comparison ensemble, then stars measured in this frame.")
                + th("Saturated", "Measurements flagged as saturated and omitted from the ensemble.")
                + th("Non-Linear", "Measurements flagged as brighter than the estimated linear limit and omitted from the ensemble.")
                + th("Crossed", "Measurements flagged because a moving object crossed the measurement region.")
                + th("Edge/Void", "Measurements flagged at the edge or in an empty area and omitted from the ensemble.")
                + th("Outliers", "Measurements flagged as isolated outliers and omitted from the ensemble.")
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

    private static void writeCsvExports(DetectionReportContext context, VariableStarAnalysis analysis) throws IOException {
        File exportDir = context.exportDir;
        Map<Integer, String> labels = new LinkedHashMap<>();
        var presentation = context.variablePresentation(analysis);
        for (StarLightCurve star : presentation.identities) labels.put(star.id, presentation.label(star));
        try (AtomicReportFile output = new AtomicReportFile(new File(exportDir, "photometry_stars.csv"))) {
            PrintWriter csv = output.writer();
            csv.println("star_id,x,y,mean_inst_mag,tier,usable_frames,scatter,expected_scatter,excess_scatter,scatter_z,stetson_j,stetson_j_z,amplitude,failed_gates,not_scored_reason,candidate_label,ra_deg,dec_deg,vsx_matches,nearest_vsx_arcsec");
            for (StarLightCurve s : analysis.stars) {
                csv.println(s.id + "," + csvNum(s.x) + "," + csvNum(s.y) + "," + csvNum(s.meanMag) + "," + s.tier + "," + s.usableFrames + ","
                        + csvNum(s.scatter) + "," + csvNum(s.expectedScatter) + "," + csvNum(s.excessScatter) + "," + csvNum(s.scatterZ) + ","
                        + csvNum(s.stetsonJ) + "," + csvNum(s.stetsonJZ) + "," + csvNum(s.amplitude) + ","
                        + csvText(String.join(";", s.failedGates)) + "," + csvText(s.notScoredReason) + csvIdentification(context, s, labels.get(s.id)));
            }
            output.commit();
        }

        try (AtomicReportFile output = new AtomicReportFile(new File(exportDir, "photometry_lightcurves.csv"))) {
            PrintWriter csv = output.writer();
            csv.println("star_id,tier,frame_index,filename,julian_date,delta_mag,mag_error,flags,candidate_label");
            for (StarLightCurve s : analysis.candidates) {
                for (int j = 0; j < analysis.frames.size(); j++) {
                    PipelineTelemetry.PhotometryFrameStat f = analysis.frames.get(j);
                    csv.println(s.id + "," + s.tier + "," + (f.frameIndex + 1) + "," + csvText(f.filename) + "," + csvNum(f.julianDate) + ","
                            + csvNum(s.deltaMag[j]) + "," + csvNum(s.magError[j]) + "," + csvText(String.join(";", PhotometryFlags.describe(s.flags[j]))) + "," + csvText(labels.get(s.id)));
                }
            }
            output.commit();
        }

        try (AtomicReportFile output = new AtomicReportFile(new File(exportDir, "photometry_frames.csv"))) {
            PrintWriter csv = output.writer();
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
            output.commit();
        }
    }

    private static String csvIdentification(DetectionReportContext context, StarLightCurve star, String label) {
        SkyTarget target = skyTarget(context, star.x, star.y);
        List<SessionCatalogue.VariableMatch> matches = target == null || label == null ? List.of()
                : SessionCatalogue.of(context).variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec);
        String names = matches.stream().map(m -> m.star.name).collect(java.util.stream.Collectors.joining(";"));
        return "," + csvText(label) + "," + (target == null ? "," : csvNum(target.raDegrees) + "," + csvNum(target.decDegrees))
                + "," + csvText(names) + "," + (matches.isEmpty() ? "" : csvNum(matches.get(0).separationArcsec));
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

    /** Readable reasons for the measurement flags actually set, for a light-curve mouse-over. */
    private static String plainFlags(int flags) {
        List<String> plain = new ArrayList<>();
        if ((flags & PhotometryFlags.SATURATED) != 0) plain.add("saturated");
        if ((flags & PhotometryFlags.NONLINEAR) != 0) plain.add("brighter than the estimated linear limit");
        if ((flags & PhotometryFlags.EDGE_OR_VOID) != 0) plain.add("edge or void");
        if ((flags & PhotometryFlags.CROSSING) != 0) plain.add("moving-object crossing");
        if ((flags & PhotometryFlags.OUTLIER) != 0) plain.add("isolated outlier");
        if ((flags & PhotometryFlags.FRAME_EXCLUDED) != 0) plain.add("frame excluded");
        if ((flags & PhotometryFlags.BAD_FLUX) != 0) plain.add("brightness could not be measured");
        if ((flags & PhotometryFlags.CONTAMINATED) != 0) plain.add("shape contamination");
        return plain.isEmpty() ? "excluded" : String.join("; ", plain);
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

}
