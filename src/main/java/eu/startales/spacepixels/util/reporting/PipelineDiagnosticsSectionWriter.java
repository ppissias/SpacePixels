package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Emits the session header, navigation and overview at the top of the report, and the processing
 * diagnostics (quality control, configuration, masks, extraction, linking) collapsed near its end.
 */
final class PipelineDiagnosticsSectionWriter {

    private PipelineDiagnosticsSectionWriter() {
    }

    /**
     * Writes the one-line session header under the report title: field, time span, frames, camera and
     * plate-solve status, taken from the FITS headers and the aligned WCS.
     */
    static void writeSessionHeader(PrintWriter report,
                                   DetectionReportContext reportContext,
                                   PipelineTelemetry pipelineTelemetry) {
        List<String> items = new ArrayList<>();
        FitsFileInformation[] files = reportContext.fitsFiles;
        FitsFileInformation first = files != null && files.length > 0 ? files[0] : null;
        int width = first != null ? first.getSizeWidth() : 0;
        int height = first != null ? first.getSizeHeight() : 0;

        WcsCoordinateTransformer transformer = reportContext.astrometryContext.hasAstrometricSolution()
                ? reportContext.astrometryContext.getTransformer() : null;
        if (transformer != null && width > 0 && height > 0) {
            WcsCoordinateTransformer.SkyCoordinate centre = transformer.pixelToSky(width / 2.0, height / 2.0);
            WcsCoordinateTransformer.SkyCoordinate step = transformer.pixelToSky(width / 2.0 + 1.0, height / 2.0);
            double scaleArcsec = angularSeparationDeg(centre, step) * 3600.0;
            String field = WcsCoordinateTransformer.formatRa(centre.getRaDegrees()) + " "
                    + WcsCoordinateTransformer.formatDec(centre.getDecDegrees());
            items.add(headerItem("Field", DetectionReportGenerator.escapeHtml(field)
                    + String.format(Locale.US, " &middot; %.1f&deg; &times; %.1f&deg; &middot; %.2f&Prime;/px",
                    width * scaleArcsec / 3600.0, height * scaleArcsec / 3600.0, scaleArcsec)));
        }

        long firstTime = Long.MAX_VALUE;
        long lastTime = Long.MIN_VALUE;
        if (files != null) {
            for (FitsFileInformation file : files) {
                long t = file != null ? file.getObservationTimestamp() : -1L;
                if (t > 0) {
                    firstTime = Math.min(firstTime, t);
                    lastTime = Math.max(lastTime, t + Math.max(0L, file.getExposureDurationMillis()));
                }
            }
        }
        if (firstTime != Long.MAX_VALUE) {
            DateTimeFormatter day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
            DateTimeFormatter clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
            long minutes = Math.round((lastTime - firstTime) / 60000.0);
            items.add(headerItem("Session", day.format(Instant.ofEpochMilli(firstTime)) + " &middot; "
                    + clock.format(Instant.ofEpochMilli(firstTime)) + "&ndash;" + clock.format(Instant.ofEpochMilli(lastTime))
                    + " UTC (" + formatDuration(minutes) + ")"));
        }

        if (pipelineTelemetry != null) {
            items.add(headerItem("Frames", pipelineTelemetry.totalFramesKept + " of " + pipelineTelemetry.totalFramesLoaded + " kept"));
        }
        if (first != null) {
            String filter = headerValue(first, "FILTER");
            if (first.getExposureDurationMillis() > 0) {
                items.add(headerItem("Exposure", formatSeconds(first.getExposureDurationMillis() / 1000.0)
                        + (filter != null ? " &middot; " + DetectionReportGenerator.escapeHtml(filter) : "")));
            }
            String camera = headerValue(first, "INSTRUME");
            String telescope = headerValue(first, "TELESCOP");
            if (camera != null || telescope != null) {
                String text = camera != null && telescope != null && !camera.equals(telescope)
                        ? DetectionReportGenerator.escapeHtml(camera) + " &middot; " + DetectionReportGenerator.escapeHtml(telescope)
                        : DetectionReportGenerator.escapeHtml(camera != null ? camera : telescope);
                items.add(headerItem("Camera", text));
            }
            if (width > 0 && height > 0) {
                items.add(headerItem("Image", width + " &times; " + height + " px"));
            }
        }
        items.add(headerItem("Astrometry", transformer != null ? "plate-solved" : "not plate-solved (sky lookups off)"));

        report.println("<div class='session-header'>");
        for (String item : items) {
            report.println(item);
        }
        report.println("</div>");
    }

    /** One entry of the sticky section navigation. */
    static final class NavItem {
        final String anchor;
        final String label;
        final String count;
        final boolean muted;

        NavItem(String anchor, String label, String count, boolean muted) {
            this.anchor = anchor;
            this.label = label;
            this.count = count;
            this.muted = muted;
        }
    }

    /** Writes the sticky navigation bar that links every report section, with its count where useful. */
    static void writeNavigation(PrintWriter report, List<NavItem> items) {
        report.println("<nav class='report-nav'><span class='nav-title'>Jump to</span>");
        for (NavItem item : items) {
            report.println("<a href='#" + item.anchor + "'" + (item.muted ? " class='nav-muted'" : "") + ">"
                    + DetectionReportGenerator.escapeHtml(item.label)
                    + (item.count != null ? "<span class='nav-count'>" + DetectionReportGenerator.escapeHtml(item.count) + "</span>" : "")
                    + "</a>");
        }
        report.println("</nav>");
    }

    /**
     * Writes the results-first overview: what was found (each card jumps to its section) and a compact
     * line of processing figures.
     */
    static void writeOverview(PrintWriter report,
                              DetectionReportContext reportContext,
                              PipelineTelemetry pipelineTelemetry,
                              DetectionReportSummary summary) {
        if (pipelineTelemetry == null) {
            return;
        }
        report.println("<div class='panel' id='overview'>");
        report.println("<h2>Overview</h2>");
        report.println("<p class='section-lede'>What this session found. Click a card to jump to its section.</p>");
        // Categories that found something get a card; empty and switched-off ones share one line.
        List<String> cards = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        addResult(cards, empty, summary.movingTargetCount, "Moving-object tracks", "moving-targets");
        addResult(cards, empty, summary.streakTrackCount, "Streak tracks", "streak-tracks");
        addResult(cards, empty, summary.singleStreakCount, "Single-frame streaks", "single-streaks");
        addResult(cards, empty, summary.anomalyCount, "Single-frame anomalies", "anomalies");
        addResult(cards, empty, summary.suspectedStreakTrackCount, "Suspected streak tracks", "streak-tracks");
        if (reportContext.config.enableSlowMoverDetection) {
            addResult(cards, empty, summary.slowMoverCandidateCount, "Slow-mover candidates", "slow-movers");
        } else {
            empty.add("slow movers (switched off)");
        }
        addResult(cards, empty, summary.localRescueCandidateCount, "Local rescue candidates", "local-rescue");
        addResult(cards, empty, summary.localActivityClusterCount, "Local activity clusters", "activity-clusters");
        PipelineTelemetry.PhotometryTelemetry photometry = pipelineTelemetry.photometryTelemetry;
        if (!reportContext.config.enableVariableStarDetection) {
            empty.add("variable stars (switched off)");
        } else if (photometry == null) {
            empty.add("variable stars (not run)");
        } else if ("NOT_READY".equals(photometry.verdict)) {
            cards.add(textCard("Not ready", "Variable stars", "variables", false));
        } else if (photometry.highConfidence + photometry.possible == 0) {
            empty.add("variable stars");
        } else {
            String value = photometry.highConfidence + (photometry.possible > 0
                    ? " <span style='color:#888; font-size: 16px;'>+ " + photometry.possible + " possible</span>" : "");
            cards.add(textCard(value, "Variable stars", "variable-candidates", false));
        }
        report.println("<div class='flex-container'>");
        if (cards.isEmpty()) {
            report.println(textCard("&ndash;", "Nothing found", null, false));
        }
        for (String card : cards) {
            report.println(card);
        }
        report.println("</div>");
        if (!empty.isEmpty()) {
            report.println("<div class='astro-note' style='margin-top: -6px; margin-bottom: 14px;'>Nothing found: "
                    + DetectionReportGenerator.escapeHtml(String.join(", ", empty)) + ".</div>");
        }

        report.println("<div class='flex-container' style='margin-bottom: 4px;'>");
        report.println(compactCard(pipelineTelemetry.totalFramesKept + " <span style='color:#888;'>of " + pipelineTelemetry.totalFramesLoaded + "</span>", "Frames kept"));
        report.println(compactCard(String.format(Locale.US, "%.1f s", pipelineTelemetry.processingTimeMs / 1000.0), "Detection time"));
        report.println(compactCard(String.valueOf(pipelineTelemetry.totalRawObjectsExtracted), "Objects extracted"));
        report.println(compactCard(String.valueOf(summary.masterStarCount), "Stars in the master map"));
        report.println("</div>");

        report.println("<div class='astro-note'>" + plural(summary.returnedTrackCount, "track-like detection") + " in total: "
                + plural(summary.singleStreakCount, "single-frame streak") + ", "
                + plural(summary.confirmedLinkedTrackCount, "linked track") + " and "
                + plural(summary.suspectedStreakTrackCount, "suspected streak grouping") + ".</div>");
        if (!reportContext.anomalies.isEmpty()) {
            String split = summary.peakSigmaAnomalyCount + " found by their peak brightness, "
                    + summary.integratedSigmaAnomalyCount + " by their integrated brightness"
                    + (summary.otherAnomalyCount > 0 ? ", " + summary.otherAnomalyCount + " other" : "");
            report.println("<div class='astro-note'>Anomalies: " + split + " (the order used on the anomaly cards and the A# map labels).</div>");
        }
        if (reportContext.config.enableSlowMoverDetection) {
            report.println("<div class='astro-note'>Local rescue candidates and activity clusters come from a second look at the "
                    + plural(summary.unclassifiedTransientCount, "detection") + " the tracker left unexplained: rescue candidates are short, consistent motions; the rest are grouped into activity clusters for manual review.</div>");
        } else {
            report.println("<div class='astro-note'>Slow-mover analysis was switched off for this session.</div>");
        }
        report.println("</div>");

        if (summary.insufficientFramesAfterQuality) {
            report.println("<div class='panel'>");
            report.println("<h2>Too Few Frames After Quality Control</h2>");
            report.println("<p>Only <strong>" + pipelineTelemetry.totalFramesKept + "</strong> frames remained after quality control. SpacePixels needs at least <strong>" + ImageProcessing.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS + "</strong> usable frames before multi-frame tracking and maximum-stack candidate review are meaningful, so those sections were skipped for this run.</p>");
            report.println("<div class='astro-note'>The quality-control tables under Diagnostics show which frames were rejected and why.</div>");
            report.println("</div>");
        }
    }

    /**
     * Writes the processing diagnostics (astrometry, quality control, configuration, masks, extraction and
     * linking) as one collapsible group near the end of the report.
     */
    static void writeDiagnostics(PrintWriter report,
                                 DetectionReportContext reportContext,
                                 PipelineTelemetry pipelineTelemetry,
                                 TrackerTelemetry linkerTelemetry,
                                 DetectionReportSummary summary,
                                 List<SourceExtractor.Pixel> driftPoints) throws IOException {
        report.println("<details class='report-group' id='diagnostics'>");
        report.println("<summary>Diagnostics<span class='summary-note'>Astrometry, frame quality control, configuration, star mask, extraction and track linking. Click to expand.</span></summary>");
        if (pipelineTelemetry != null) {
            writeAstrometricContext(report, reportContext);
            writeFrameQualityStatistics(report, pipelineTelemetry);
            writeRejectedFrames(report, pipelineTelemetry);
            writePipelineConfiguration(report, reportContext);
            writeMasterShieldDiagnostics(report, reportContext, summary);
            writeDriftDiagnostics(report, reportContext, driftPoints, pipelineTelemetry.driftExcludedFrames);
            writeExtractionStatistics(report, pipelineTelemetry);
            writeStationaryStarPurification(report, pipelineTelemetry, linkerTelemetry);
        }
        if (linkerTelemetry != null) {
            writeTrackLinkingDiagnostics(report, linkerTelemetry, summary);
        }
        report.println("</details>");
    }

    private static void addResult(List<String> cards, List<String> empty, int count, String label, String anchor) {
        if (count > 0) {
            cards.add(textCard(String.valueOf(count), label, anchor, false));
        } else {
            empty.add(label.toLowerCase(Locale.ROOT));
        }
    }

    private static String textCard(String value, String label, String anchor, boolean quiet) {
        String box = "<div class='metric-box" + (quiet ? " quiet" : "") + "'><span class='metric-value'>" + value
                + "</span><span class='metric-label'>" + DetectionReportGenerator.escapeHtml(label) + "</span></div>";
        return anchor != null ? "<a class='metric-link' href='#" + anchor + "'>" + box + "</a>" : box;
    }

    private static String compactCard(String value, String label) {
        return "<div class='metric-box compact'><span class='metric-value'>" + value + "</span><span class='metric-label'>"
                + DetectionReportGenerator.escapeHtml(label) + "</span></div>";
    }

    private static String headerItem(String label, String valueHtml) {
        return "<span class='item'><span class='label'>" + label + "</span><b>" + valueHtml + "</b></span>";
    }

    /** A FITS header string value without quotes and padding, or null when absent or blank. */
    private static String headerValue(FitsFileInformation file, String key) {
        String value = file.getFitsHeader().get(key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.startsWith("'")) {
            value = value.substring(1);
        }
        int quote = value.indexOf('\'');
        if (quote >= 0) {
            value = value.substring(0, quote);
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    static String plural(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    private static String formatDuration(long minutes) {
        return minutes < 60 ? minutes + " min" : String.format(Locale.US, "%d h %02d min", minutes / 60, minutes % 60);
    }

    private static String formatSeconds(double seconds) {
        return Math.abs(seconds - Math.rint(seconds)) < 1e-6
                ? String.format(Locale.US, "%.0f s", seconds)
                : String.format(Locale.US, "%.1f s", seconds);
    }

    private static double angularSeparationDeg(WcsCoordinateTransformer.SkyCoordinate a, WcsCoordinateTransformer.SkyCoordinate b) {
        double ra1 = Math.toRadians(a.getRaDegrees());
        double ra2 = Math.toRadians(b.getRaDegrees());
        double de1 = Math.toRadians(a.getDecDegrees());
        double de2 = Math.toRadians(b.getDecDegrees());
        double sinDe = Math.sin((de2 - de1) / 2);
        double sinRa = Math.sin((ra2 - ra1) / 2);
        return Math.toDegrees(2 * Math.asin(Math.sqrt(sinDe * sinDe + Math.cos(de1) * Math.cos(de2) * sinRa * sinRa)));
    }

    private static void writeAstrometricContext(PrintWriter report, DetectionReportContext reportContext) {
        report.println("<div class='panel'>");
        report.println("<h2>Astrometric Context</h2>");
        if (reportContext.astrometryContext.hasAstrometricSolution()) {
            report.println("<div class='flex-container'>");
            report.println("<div class='metric-box'><span class='metric-value'>Available</span><span class='metric-label'>Aligned WCS</span></div>");
            report.println("<div class='metric-box'><span class='metric-value'>" + DetectionReportGenerator.escapeHtml(DetectionReportGenerator.formatUtcTimestamp(reportContext.astrometryContext.getSessionMidpointTimestampMillis())) + "</span><span class='metric-label'>Session Midpoint (UTC)</span></div>");
            String observerMetric = reportContext.astrometryContext.hasSkybotObserverCode()
                    ? DetectionReportGenerator.escapeHtml(reportContext.astrometryContext.getSkybotObserverCode())
                    : "Geocentre";
            report.println("<div class='metric-box'><span class='metric-value'>" + observerMetric + "</span><span class='metric-label'>Observer for SkyBoT</span></div>");
            report.println("</div>");
            report.println("<div class='astro-note'>WCS source: " + reportContext.astrometryContext.getWcsSummary() + ".");
            if (reportContext.astrometryContext.hasSkybotObserverCode()) {
                report.println("<br>SkyBoT observer code source: " + DetectionReportGenerator.escapeHtml(reportContext.astrometryContext.getSkybotObserverSource()) + ".");
            } else {
                report.println("<br>No IAU observatory code configured. SkyBoT links will use geocenter (500).");
            }
            if (reportContext.astrometryContext.getObserverSiteSourceLabel() != null) {
                report.println("<br>Known site coordinates source: " + DetectionReportGenerator.escapeHtml(reportContext.astrometryContext.getObserverSiteSourceLabel()) + ".");
            } else {
                report.println("<br>No observer site coordinates found.");
            }
            report.println("</div>");
        } else {
            report.println("<p>No reusable WCS solution was found in the aligned set. Sky-coordinate report links are disabled for this session.</p>");
        }
        report.println("</div>");
    }

    private static void writeFrameQualityStatistics(PrintWriter report, PipelineTelemetry pipelineTelemetry) {
        if (pipelineTelemetry.frameQualityStats.isEmpty()) {
            return;
        }

        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Quality Control: Frame Quality Statistics</h2>");
        report.println("<p class='compact-note'>Per-frame quality measurements and the session limits a frame had to meet. Bright-star eccentricity shows <code>n/a</code> when too few bright stars qualified in that frame." + (pipelineTelemetry.qualityThresholds.available ? "" : " No session limits could be derived for this run.") + "</p>");
        report.println("<div class='compact-threshold-grid'>");
        report.println("<div class='config-item'><span>Min Allowed Star Count</span><span class='val'>" + (Double.isFinite(pipelineTelemetry.qualityThresholds.minAllowedStarCount) ? String.valueOf((long) Math.ceil(pipelineTelemetry.qualityThresholds.minAllowedStarCount)) : "n/a") + "</span></div>");
        report.println("<div class='config-item'><span>Max Allowed FWHM</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedFwhm) + "</span></div>");
        report.println("<div class='config-item'><span>Max Allowed Eccentricity</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedEccentricity) + "</span></div>");
        report.println("<div class='config-item'><span>Max Bright-Star Eccentricity</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBrightStarEccentricity) + "</span></div>");
        report.println("<div class='config-item'><span>Background Median Baseline</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.backgroundMedianBaseline) + "</span></div>");
        report.println("<div class='config-item'><span>Max Background Deviation</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBackgroundDeviation) + "</span></div>");
        report.println("<div class='config-item'><span>Min Allowed Bg Median</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.minAllowedBackgroundMedian) + "</span></div>");
        report.println("<div class='config-item'><span>Max Allowed Bg Median</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBackgroundMedian) + "</span></div>");
        report.println("</div>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Frame</th><th>Filename</th><th>Bg Median</th><th>Bg Sigma</th><th>Median FWHM</th><th>Median Ecc</th><th>Bright-Star Median Ecc</th><th>Stars</th><th>Shape Stars</th><th>Bright Shape Stars</th><th>FWHM Stars</th><th>Status</th><th>Rejection Reason</th></tr></thead><tbody>");
        for (PipelineTelemetry.FrameQualityStat stat : pipelineTelemetry.frameQualityStats) {
            String statusLabel = stat.rejected ? "Rejected" : "Kept";
            String rejectionReason = (stat.rejectionReason == null || stat.rejectionReason.isBlank()) ? "-" : DetectionReportGenerator.escapeHtml(stat.rejectionReason);
            report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
            report.println("<td>" + DetectionReportGenerator.escapeHtml(stat.filename) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(stat.backgroundMedian) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(stat.backgroundNoise) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(stat.medianFWHM) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(stat.medianEccentricity) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(stat.brightStarMedianEccentricity) + "</td>");
            report.println("<td>" + stat.starCount + "</td>");
            report.println("<td>" + stat.usableShapeStarCount + "</td>");
            report.println("<td>" + stat.brightStarShapeStarCount + "</td>");
            report.println("<td>" + stat.fwhmStarCount + "</td>");
            report.println("<td" + (stat.rejected ? " class='alert'" : "") + ">" + statusLabel + "</td>");
            report.println("<td" + (stat.rejected ? " class='alert'" : "") + ">" + rejectionReason + "</td></tr>");
        }
        report.println("</tbody></table>");
        report.println("</div>");
        report.println("</div>");
    }

    private static void writeRejectedFrames(PrintWriter report, PipelineTelemetry pipelineTelemetry) {
        if (pipelineTelemetry.rejectedFrames.isEmpty()) {
            return;
        }

        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Quality Control: Rejected Frames</h2>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><tr><th>Frame</th><th>Filename</th><th>Median Ecc</th><th>Bright-Star Median Ecc</th><th>Bright Shape Stars</th><th>Rejection Reason</th></tr>");
        for (PipelineTelemetry.FrameRejectionStat rej : pipelineTelemetry.rejectedFrames) {
            report.println("<tr><td>" + (rej.frameIndex + 1) + "</td>");
            report.println("<td>" + DetectionReportGenerator.escapeHtml(rej.filename) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(rej.medianEccentricity) + "</td>");
            report.println("<td>" + DetectionReportGenerator.formatOptionalMetric(rej.brightStarMedianEccentricity) + "</td>");
            report.println("<td>" + rej.brightStarShapeStarCount + "</td>");
            report.println("<td class='alert'>" + DetectionReportGenerator.escapeHtml(rej.reason) + "</td></tr>");
        }
        report.println("</table>");
        report.println("</div>");
        report.println("</div>");
    }

    private static void writePipelineConfiguration(PrintWriter report, DetectionReportContext reportContext) {
        if (reportContext.config == null) {
            return;
        }

        File jsonConfigFile = new File(reportContext.exportDir, "detection_config.json");
        try (FileWriter writer = new FileWriter(jsonConfigFile)) {
            SpacePixelsDetectionProfileIO.write(
                    writer,
                    reportContext.config,
                    SpacePixelsDetectionProfileIO.getActiveAutoTuneMaxCandidateFrames());
        } catch (Exception e) {
            System.err.println("Failed to write config JSON: " + e.getMessage());
        }

        report.println("<div class='panel'>");
        report.println("<h2>Pipeline Configuration</h2>");
        report.println("<p style='font-size: 13px; color: #888; margin-top: -10px;'>Every detection parameter used in this session, as stored in the profile file. <a href='detection_config.json' target='_blank' style='color: #4da6ff; text-decoration: none;'>[View / Download JSON Profile]</a></p>");
        // Values that differ from the built-in defaults come first and are highlighted.
        DetectionConfig defaults = new DetectionConfig();
        List<String> changed = new ArrayList<>();
        List<String> unchanged = new ArrayList<>();
        for (Field field : reportContext.config.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(reportContext.config);
                Object defaultValue = field.get(defaults);
                boolean differs = !java.util.Objects.equals(value, defaultValue);
                String item = "<div class='config-item'" + (differs ? " style='border-left-color:#ff9933;' title='Default: "
                        + DetectionReportGenerator.escapeHtml(String.valueOf(defaultValue)) + "'" : "") + "><span>" + field.getName()
                        + "</span> <span class='val'" + (differs ? " style='color:#ffb366;'" : "") + ">" + DetectionReportGenerator.escapeHtml(String.valueOf(value)) + "</span></div>";
                (differs ? changed : unchanged).add(item);
            } catch (IllegalAccessException e) {
                // Silently ignore fields that cannot be accessed
            }
        }
        report.println("<p class='compact-note'>" + changed.size() + (changed.size() == 1 ? " value differs" : " values differ")
                + " from the built-in defaults (orange, listed first; hover for the default).</p>");
        report.println("<div class='scroll-box' style='padding: 10px;'><div class='config-grid'>");
        for (String item : changed) {
            report.println(item);
        }
        for (String item : unchanged) {
            report.println(item);
        }
        report.println("</div></div></div>");
    }

    private static void writeMasterShieldDiagnostics(PrintWriter report,
                                                     DetectionReportContext reportContext,
                                                     DetectionReportSummary summary) throws IOException {
        boolean hasMasterShieldDiagnostics = reportContext.masterStackData != null && reportContext.masterVetoMask != null;

        report.println("<div class='panel'>");
        report.println("<h2>Star Mask</h2>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
        report.println("The median stack (left) and the star mask built from it, in red (right): every object of the median stack, grown by the allowed star jitter of <strong>" + reportContext.config.maxStarJitter + "</strong> pixels. " +
                "A detection that overlaps this mask too much is treated as a star and removed before tracking. " +
                "The maximum allowed overlap fraction is currently set to <strong>" + reportContext.config.maxMaskOverlapFraction + "</strong> (" + (int) (reportContext.config.maxMaskOverlapFraction * 100) + "%).</p>");
        report.println("<div class='flex-container' style='margin-bottom: 15px;'>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.masterMapObjectCount + "</span><span class='metric-label'>Objects in the star mask</span></div>");
        report.println("</div>");
        if (hasMasterShieldDiagnostics) {
            BufferedImage masterImg = DetectionReportGenerator.createDisplayImage(reportContext.masterStackData);
            TrackVisualizationRenderer.saveLosslessPng(masterImg, new File(reportContext.exportDir, "master_stack.png"));

            BufferedImage masterMaskImg = DetectionReportGenerator.createMasterMaskOverlay(reportContext.masterStackData, reportContext.masterVetoMask);
            TrackVisualizationRenderer.saveLosslessPng(masterMaskImg, new File(reportContext.exportDir, "master_mask_overlay.png"));

            report.println("<div class='image-container'>");
            report.println("<div><a href='master_stack.png' target='_blank'><img src='master_stack.png' style='max-width: 400px;' alt='Master Stack' /></a><br/><center><small>Median stack</small></center></div>");
            report.println("<div><a href='master_mask_overlay.png' target='_blank'><img src='master_mask_overlay.png' style='max-width: 400px;' alt='Mask Overlay' /></a><br/><center><small>Star mask (red)</small></center></div>");
            report.println("</div>");
        } else {
            report.println("<div class='astro-note'>Preview images were not produced for this run, so the master shield and veto-mask links are omitted.</div>");
        }
        report.println("</div>");
    }

    private static void writeDriftDiagnostics(PrintWriter report,
                                              DetectionReportContext reportContext,
                                              List<SourceExtractor.Pixel> driftPoints,
                                              List<Integer> excludedFrames) throws IOException {
        if (reportContext.rawFrames.isEmpty() || driftPoints == null || driftPoints.isEmpty()) {
            return;
        }

        List<BufferedImage> cornerFrames = new ArrayList<>();
        boolean hasDrift = false;
        SourceExtractor.Pixel firstPoint = driftPoints.get(0);
        for (SourceExtractor.Pixel point : driftPoints) {
            if (point.x != firstPoint.x || point.y != firstPoint.y) {
                hasDrift = true;
                break;
            }
        }

        if (!hasDrift) {
            return;
        }

        List<Integer> sampledCornerIndices = DetectionReportGenerator.getRepresentativeSequence(
                reportContext.rawFrames.size(),
                new HashSet<>(),
                15);

        for (int idx : sampledCornerIndices) {
            cornerFrames.add(DetectionReportGenerator.createFourCornerMosaic(reportContext.rawFrames.get(idx), 150));
        }

        BufferedImage driftImg = DetectionReportGenerator.createDriftMap(driftPoints, 300);
        TrackVisualizationRenderer.saveLosslessPng(driftImg, new File(reportContext.exportDir, "dither_drift_map.png"));

        String cornerGifFile = "dither_corners_sampled.gif";
        GifSequenceWriter.saveAnimatedGif(cornerFrames, new File(reportContext.exportDir, cornerGifFile), reportContext.settings.getGifBlinkSpeedMs());

        report.println("<div class='panel'>");
        report.println("<h2>Dither & Sensor Drift Diagnostics</h2>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
        report.println("Shows how the image frame drifted across the session. Sensor dust or hot pixels move exactly along this trajectory, potentially creating false moving targets.</p>");
        if (excludedFrames != null && !excludedFrames.isEmpty()) {
            StringBuilder frames = new StringBuilder();
            for (Integer index : excludedFrames) {
                frames.append(frames.length() == 0 ? "" : ", ").append(index + 1);
            }
            report.println("<div class='astro-note'>Left out of the drift analysis as blank or failed registrations (less than half of the pixels hold image data): frame "
                    + DetectionReportGenerator.escapeHtml(frames.toString()) + ".</div>");
        }
        report.println("<div class='image-container'>");
        report.println("<div><a href='dither_drift_map.png' target='_blank'><img src='dither_drift_map.png' style='max-width: 300px;' alt='Drift Trajectory' /></a><br/><center><small>Drift Trajectory Map (Blue = Start, Red = End)</small></center></div>");
        report.println("<div><a href='" + cornerGifFile + "' target='_blank'><img src='" + cornerGifFile + "' style='max-width: 300px;' alt='Corners Sampled Time-Lapse' /></a><br/><center><small>4-Corners Sampled Time-Lapse</small></center></div>");
        report.println("<div style='min-width: 200px; flex-grow: 1;'><div class='scroll-box' style='max-height: 325px; border-color: #333; padding: 10px;'>");
        report.println("<div class='config-grid' style='grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));'>");
        for (int i = 0; i < driftPoints.size(); i++) {
            SourceExtractor.Pixel point = driftPoints.get(i);
            report.println("<div class='config-item'><span style='color: #aaa;'>Frame " + (point.value + 1) + "</span> <span class='val'>" + DetectionReportGenerator.escapeHtml(DetectionReportGenerator.formatPixelCoordinateOnly(point.x, point.y)) + "</span></div>");
        }
        report.println("</div></div></div>");
        report.println("</div>");
        report.println("</div>");
    }

    private static void writeExtractionStatistics(PrintWriter report, PipelineTelemetry pipelineTelemetry) {
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Frame Extraction Statistics</h2>");
        report.println("<p class='compact-note'>Objects found in each frame and the detection thresholds used (background levels are in the quality-control table).</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Frame</th><th>Filename</th><th>Objects Extracted</th><th>Seed Threshold</th><th>Grow Threshold</th></tr></thead><tbody>");
        for (PipelineTelemetry.FrameExtractionStat stat : pipelineTelemetry.frameExtractionStats) {
            report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
            report.println("<td>" + DetectionReportGenerator.escapeHtml(stat.filename) + "</td>");
            report.println("<td>" + stat.objectCount + "</td>");
            report.println("<td>" + String.format(Locale.US, "%.2f", stat.seedThreshold) + "</td>");
            report.println("<td>" + String.format(Locale.US, "%.2f", stat.growThreshold) + "</td></tr>");
        }
        report.println("</tbody></table>");
        report.println("</div>");
        report.println("</div>");
    }

    private static void writeStationaryStarPurification(PrintWriter report,
                                                        PipelineTelemetry pipelineTelemetry,
                                                        TrackerTelemetry linkerTelemetry) {
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Star Removal Before Tracking</h2>");
        report.println("<p class='compact-note'>Detections on the star mask are removed before the tracker links what is left. Per frame: point sources found, removed as stars, and left for tracking.</p>");
        if (linkerTelemetry != null) {
            report.println("<div class='flex-container' style='margin-bottom: 10px;'>");
            report.println("<div class='metric-box compact'><span class='metric-value'>" + linkerTelemetry.totalStationaryStarsPurged + "</span><span class='metric-label'>Star detections removed</span></div>");
            report.println("<div class='metric-box compact'><span class='metric-value'>" + linkerTelemetry.totalStationaryStreaksPurged + "</span><span class='metric-label'>Stationary streaks removed</span></div>");
            report.println("</div>");
            report.println("<div class='scroll-box compact-table-box'>");
            report.println("<table><thead><tr><th>Frame</th><th>Filename</th><th>Point Sources</th><th>Removed as Stars</th><th>Left for Tracking</th></tr></thead><tbody>");
            for (TrackerTelemetry.FrameStarMapStat starStat : linkerTelemetry.frameStarMapStats) {
                String fileName = "Unknown";
                if (starStat.frameIndex < pipelineTelemetry.frameExtractionStats.size()) {
                    fileName = pipelineTelemetry.frameExtractionStats.get(starStat.frameIndex).filename;
                }
                report.println("<tr><td>" + (starStat.frameIndex + 1) + "</td>");
                report.println("<td>" + DetectionReportGenerator.escapeHtml(fileName) + "</td>");
                report.println("<td>" + starStat.initialPointSources + "</td>");
                report.println("<td style='color: #ff9933;'>" + starStat.purgedStars + "</td>");
                report.println("<td style='color: #44ff44; font-weight: bold;'>" + starStat.survivingTransients + "</td></tr>");
            }
            report.println("</tbody></table>");
            report.println("</div>");
        } else {
            report.println("<div class='astro-note'>Star-removal figures were not recorded for this run.</div>");
        }
        report.println("</div>");
    }

    private static void writeTrackLinkingDiagnostics(PrintWriter report,
                                                     TrackerTelemetry linkerTelemetry,
                                                     DetectionReportSummary summary) {
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Track Linking Diagnostics</h2>");
        report.println("<p class='compact-note'>How many candidate links each rule rejected, from the first pair of points to the final track.</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Stage</th><th>Rejection Reason</th><th>Links Rejected</th></tr></thead><tbody>");
        report.println("<tr><td>0. Single-frame streak</td><td>Binary-Star-Like Shape Veto</td><td>" + linkerTelemetry.rejectedBinaryStarStreakShape + "</td></tr>");
        report.println("<tr><td>1. First pair of points</td><td>Non-Positive Time Delta</td><td>" + linkerTelemetry.countBaselineNonPositiveDelta + "</td></tr>");
        report.println("<tr><td>1. First pair of points</td><td>Stationary / Jitter</td><td>" + linkerTelemetry.countBaselineJitter + "</td></tr>");
        report.println("<tr><td>1. First pair of points</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countBaselineJump + "</td></tr>");
        report.println("<tr><td>1. First pair of points</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countBaselineSize + "</td></tr>");
        report.println("<tr><td>2. Third and later points</td><td>Non-Positive Time Delta</td><td>" + linkerTelemetry.countP3NonPositiveDelta + "</td></tr>");
        report.println("<tr><td>2. Third and later points</td><td>Velocity Mismatch</td><td>" + linkerTelemetry.countP3VelocityMismatch + "</td></tr>");
        report.println("<tr><td>2. Third and later points</td><td>Off Predicted Trajectory Line</td><td>" + linkerTelemetry.countP3NotLine + "</td></tr>");
        report.println("<tr><td>2. Third and later points</td><td>Wrong Direction / Angle</td><td>" + linkerTelemetry.countP3WrongDirection + "</td></tr>");
        report.println("<tr><td>2. Third and later points</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countP3Jump + "</td></tr>");
        report.println("<tr><td>2. Third and later points</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countP3Size + "</td></tr>");
        report.println("<tr><td>3. Final Track</td><td>Insufficient Track Length</td><td>" + linkerTelemetry.countTrackTooShort + "</td></tr>");
        report.println("<tr><td>3. Final Track</td><td>Erratic Kinematic Rhythm</td><td>" + linkerTelemetry.countTrackErraticRhythm + "</td></tr>");
        report.println("<tr><td>3. Final Track</td><td>Duplicate Track (Ignored)</td><td>" + linkerTelemetry.countTrackDuplicate + "</td></tr>");
        report.println("</tbody></table>");
        report.println("</div>");
        report.println("<p class='astro-note' style='margin-top: 12px;'>");
        report.println("Accepted: <strong>" + linkerTelemetry.streakTracksFound + "</strong> streak tracks, <strong>" + linkerTelemetry.pointTracksFound + "</strong> point tracks and <strong>" + summary.anomalyCount + "</strong> single-frame anomalies.");
        if (summary.anomalyCount > 0) {
            report.println(" The anomaly split was <strong>" + summary.peakSigmaAnomalyCount + "</strong> peak-sigma and <strong>" + summary.integratedSigmaAnomalyCount + "</strong> integrated-sigma.");
            if (summary.otherAnomalyCount > 0) {
                report.println(" <strong>" + summary.otherAnomalyCount + "</strong> anomaly rescues carried other or unknown type labels.");
            }
        }
        if (linkerTelemetry.suspectedStreakTracksFound > 0) {
            report.println(" The tracker also flagged <strong>" + linkerTelemetry.suspectedStreakTracksFound + "</strong> suspected streak tracks.");
        }
        report.println("</p>");
        report.println("</div>");
    }
}
