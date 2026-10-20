package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.DisplayImageRenderer;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Presents single-frame candidates without inferring their physical cause or flash duration. */
final class AnomalySectionWriter {
    private static final Color EVENT_COLOR = new Color(239, 192, 105);

    private AnomalySectionWriter() { }

    static void writeSection(PrintWriter report, DetectionReportContext context) throws IOException {
        report.println("<h3 id='anomalies'>Single-Frame Anomalies</h3>");
        report.println("<p class='astro-note'>These candidates were detected in one exposure; their cause is unconfirmed. "
                + "They may be flashes, satellite glints or image artifacts. Cards are ordered by frame. "
                + "Events associated with a confirmed streak are shown with that streak.</p>");
        int number = 1;
        for (TrackLinker.AnomalyDetection anomaly : context.anomalies) {
            writeCard(report, context, anomaly, number++);
        }
    }

    private static void writeCard(PrintWriter report, DetectionReportContext context,
                                  TrackLinker.AnomalyDetection anomaly, int number) throws IOException {
        SourceExtractor.DetectedObject point = anomaly.object;
        var bounds = new TrackCropGeometry.CropBounds(point, context.settings.getTrackCropPadding());
        String prefix = "anomaly_" + number;
        report.println("<div class='detection-card anomaly-card' id='finding-a" + number + "'>");
        report.println("<div class='detection-title'>Anomaly A" + number + "</div>"
                + "<div class='anomaly-subtitle'>Single-frame event</div>");
        writeSummary(report, context, point);
        report.println("<p class='anomaly-reason'><strong>Why it was retained:</strong> <span title='"
                + reasonExplanation(anomaly.type) + "'>" + reasonLabel(anomaly.type) + "</span></p>");

        short[][] eventCrop = TrackVisualizationRenderer.robustEdgeAwareCrop(context.rawFrames.get(point.sourceFrameIndex),
                bounds.fixedCenterX, bounds.fixedCenterY, bounds.trackBoxWidth, bounds.trackBoxHeight);
        double[] levels = DisplayImageRenderer.displayLevels(eventCrop,
                context.settings.getAutoStretchBlackSigma(), context.settings.getAutoStretchWhiteSigma());
        List<Integer> neighbours = context.data != null ? context.data.neighbours(point.sourceFrameIndex)
                : List.of(point.sourceFrameIndex - 1, point.sourceFrameIndex, point.sourceFrameIndex + 1);
        Integer before = null, after = null;
        for (int index : neighbours) {
            if (index < 0 || index >= context.rawFrames.size()) continue;
            if (index < point.sourceFrameIndex) before = index;
            if (index > point.sourceFrameIndex) after = index;
        }
        List<BufferedImage> animation = new ArrayList<>();
        report.println("<div class='anomaly-visuals'><div class='anomaly-comparison' role='group' aria-label='Before, event and after comparison'>");
        writeComparisonFrame(report, context, point, bounds, levels, before, "Before", prefix + "_before.png", animation);
        writeComparisonFrame(report, context, point, bounds, levels, point.sourceFrameIndex, "Event", prefix + "_detection.png", animation);
        writeComparisonFrame(report, context, point, bounds, levels, after, "After", prefix + "_after.png", animation);
        report.println("</div>");
        String gif = prefix + "_context.gif";
        TrackVisualizationRenderer.saveAnimatedGif(animation, new File(context.exportDir, gif), context.settings);
        report.println("<figure class='anomaly-blink'><a href='" + gif + "' target='_blank'>"
                + "<img loading='lazy' decoding='async' src='" + gif + "' alt='Blink comparison of the event and available neighbouring frames' /></a>"
                + "<figcaption>Blink comparison &middot; <a href='" + gif + "' target='_blank'>Open GIF</a></figcaption></figure></div>");
        report.println("<p class='astro-note'>The circle marks the same sky position in every image. All images use the same brightness stretch. "
                + "Before and after are the nearest frames kept by quality control; check their frame numbers and times for gaps. "
                + "The exposure length does not measure the flash duration.</p>");

        report.print(AstrometryIdentificationHtmlBuilder.buildAnomalySatCheckerHtml(context.astrometryContext, point,
                "anomaly-" + number + "-satchecker", prefix + "_satchecker_results.json"));
        writeDetails(report, context, anomaly, bounds, prefix);
        report.println("</div>");
    }

    private static void writeSummary(PrintWriter report, DetectionReportContext context, SourceExtractor.DetectedObject point) {
        long start = startTimestamp(context, point);
        long exposure = exposureMillis(context, point);
        long end = start > 0 && exposure > 0 && start <= Long.MAX_VALUE - exposure ? start + exposure : -1;
        report.println("<dl class='anomaly-summary'>");
        summaryItem(report, "Frame", Integer.toString(point.sourceFrameIndex + 1));
        summaryItem(report, "Exposure start", timestamp(start));
        summaryItem(report, "Exposure end", timestamp(end));
        summaryItem(report, "Exposure length", exposure > 0 ? String.format(Locale.US, "%s s", exposure / 1000.0d) : "Unknown");
        summaryItem(report, "Position", DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, point.x, point.y));
        report.println("</dl>");
    }

    private static void summaryItem(PrintWriter report, String label, String value) {
        report.println("<div><dt>" + label + "</dt><dd>" + DetectionReportGenerator.escapeHtml(value) + "</dd></div>");
    }

    private static void writeComparisonFrame(PrintWriter report, DetectionReportContext context,
                                             SourceExtractor.DetectedObject point, TrackCropGeometry.CropBounds bounds,
                                             double[] levels, Integer index, String role, String filename,
                                             List<BufferedImage> animation) throws IOException {
        boolean event = "Event".equals(role);
        report.println("<figure class='anomaly-frame" + (event ? " anomaly-event" : "") + "'>");
        if (index == null) {
            report.println("<div class='anomaly-missing'>No kept frame " + role.toLowerCase(Locale.ROOT) + " this event.</div>");
        } else {
            BufferedImage crop = TrackVisualizationRenderer.createStarCentricHighlightedFrame(context.rawFrames.get(index),
                    bounds, point, context.settings, event ? EVENT_COLOR : Color.WHITE, null, null, true, levels);
            BufferedImage labelled = labelFrame(crop, role + " · Frame " + (index + 1), frameTimestamp(context, point, index), event);
            TrackVisualizationRenderer.saveLosslessPng(labelled, new File(context.exportDir, filename));
            animation.add(labelled);
            report.println("<a href='" + filename + "' target='_blank'><img loading='lazy' decoding='async' src='" + filename
                    + "' alt='" + role + ", frame " + (index + 1) + ", " + timestamp(frameTimestamp(context, point, index)) + "' /></a>");
        }
        report.println("<figcaption>" + role + (event ? " &middot; detected here" : "") + "</figcaption></figure>");
    }

    /** Labels sit outside the sky crop so even a small crop has room for a complete UTC timestamp. */
    private static BufferedImage labelFrame(BufferedImage crop, String label, long time, boolean event) {
        int width = Math.max(270, crop.getWidth());
        BufferedImage image = new BufferedImage(width, crop.getHeight() + 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(32, 36, 42));
        g.fillRect(0, 0, width, image.getHeight());
        g.drawImage(crop, (width - crop.getWidth()) / 2, 48, null);
        g.setFont(new Font("Segoe UI", Font.BOLD, 12));
        g.setColor(event ? EVENT_COLOR : Color.WHITE);
        g.drawString(label, 10, 17);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g.setColor(Color.LIGHT_GRAY);
        g.drawString(timestamp(time), 10, 35);
        if (event) {
            g.setColor(EVENT_COLOR);
            g.drawRect(0, 0, width - 1, image.getHeight() - 1);
        }
        g.dispose();
        return image;
    }

    private static void writeDetails(PrintWriter report, DetectionReportContext context,
                                     TrackLinker.AnomalyDetection anomaly, TrackCropGeometry.CropBounds bounds,
                                     String prefix) throws IOException {
        var point = anomaly.object;
        report.println("<details class='foldable-streak-details anomaly-details'><summary>Detection details</summary><div class='foldable-streak-body'>");
        String metrics = String.format(Locale.US,
                "Rescue type: %s, Peak sigma: %.2f, Integrated sigma: %.2f, Flux: %.1f, Pixels: %d, Elongation: %.2f",
                anomaly.type, point.peakSigma, point.integratedSigma, point.totalFlux, (int) point.pixelArea, point.elongation);
        report.println("<ul class='source-list'>" + DetectionReportAstrometry.buildSourceCoordinateListEntry(
                point.sourceFilename, context.astrometryContext, point.x, point.y, metrics) + "</ul>");
        String shape = prefix + "_shape.png";
        TrackVisualizationRenderer.saveLosslessPng(TrackVisualizationRenderer.createSingleStreakShapeImage(
                Collections.singletonList(point), bounds.trackBoxWidth, bounds.trackBoxHeight, bounds.startX, bounds.startY, false),
                new File(context.exportDir, shape));
        report.println("<div class='image-container anomaly-diagnostics'>");
        diagnosticImage(report, shape, "Detected footprint");
        if (context.masterStackData != null && context.masterVetoMask != null) {
            String mask = prefix + "_master_mask.png";
            TrackVisualizationRenderer.saveLosslessPng(DetectionReportGenerator.createCroppedMaskOverlay(
                    context.masterStackData, context.masterVetoMask, bounds.fixedCenterX, bounds.fixedCenterY,
                    bounds.trackBoxWidth, bounds.trackBoxHeight, point, new Color(255, 32, 32), context.settings),
                    new File(context.exportDir, mask));
            diagnosticImage(report, mask, "Master star-mask overlap");
        }
        report.println("</div>");
        if (context.masterStackData != null && context.masterVetoMask != null) {
            var overlap = DetectionReportGenerator.computeMaskOverlapStats(point, context.masterVetoMask,
                    context.masterStackData[0].length, context.masterStackData.length);
            if (overlap.totalPixels > 0) {
                report.printf(Locale.US, "<p class='astro-note'>Veto-mask overlap: %.1f%% (%d / %d detection pixels). Configured limit: %.1f%%.</p>%n",
                        overlap.fraction * 100, overlap.overlappingPixels, overlap.totalPixels, context.config.maxMaskOverlapFraction * 100);
            }
        }
        report.println("</div></details>");
    }

    private static void diagnosticImage(PrintWriter report, String filename, String label) {
        report.println("<figure><a href='" + filename + "' target='_blank'><img loading='lazy' decoding='async' src='"
                + filename + "' alt='" + label + "' /></a><figcaption>" + label + "</figcaption></figure>");
    }

    private static String reasonLabel(TrackLinker.AnomalyType type) {
        if (type == TrackLinker.AnomalyType.PEAK_SIGMA) return "Strong peak signal";
        if (type == TrackLinker.AnomalyType.INTEGRATED_SIGMA) return "Strong combined signal across the detected pixels";
        return "Retained by the single-frame detection checks";
    }

    private static String reasonExplanation(TrackLinker.AnomalyType type) {
        if (type == TrackLinker.AnomalyType.PEAK_SIGMA) return "The peak pixel signal passed the configured anomaly threshold above the background noise. This describes detection strength, not the physical cause.";
        if (type == TrackLinker.AnomalyType.INTEGRATED_SIGMA) return "The combined signal across the detection passed the configured integrated-signal threshold. This describes detection strength, not the physical cause.";
        return "The detection method is unavailable; see Detection details for the recorded measurements.";
    }

    private static FitsFileInformation frameInfo(DetectionReportContext context, int index) {
        return context.fitsFiles != null && index >= 0 && index < context.fitsFiles.length ? context.fitsFiles[index] : null;
    }

    private static long startTimestamp(DetectionReportContext context, SourceExtractor.DetectedObject point) {
        FitsFileInformation info = frameInfo(context, point.sourceFrameIndex);
        return point.timestamp > 0 ? point.timestamp : info != null ? info.getObservationTimestamp() : -1;
    }

    private static long exposureMillis(DetectionReportContext context, SourceExtractor.DetectedObject point) {
        FitsFileInformation info = frameInfo(context, point.sourceFrameIndex);
        return point.exposureDuration > 0 ? point.exposureDuration : info != null ? info.getExposureDurationMillis() : -1;
    }

    private static long frameTimestamp(DetectionReportContext context, SourceExtractor.DetectedObject point, int index) {
        FitsFileInformation info = frameInfo(context, index);
        return index == point.sourceFrameIndex ? startTimestamp(context, point) : info != null ? info.getObservationTimestamp() : -1;
    }

    private static String timestamp(long time) {
        return time > 0 ? DateTimeFormatter.ofPattern(time % 1000 == 0 ? "yyyy-MM-dd HH:mm:ss 'UTC'" : "yyyy-MM-dd HH:mm:ss.SSS 'UTC'")
                .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(time)) : "Unknown";
    }
}
