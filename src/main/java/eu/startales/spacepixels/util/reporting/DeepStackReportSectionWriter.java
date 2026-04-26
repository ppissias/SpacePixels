package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.core.SlowMoverCandidateDiagnostics;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Writes the deep-stack report section covering ultra-slow-mover telemetry, candidate cards, and supporting
 * comparison imagery.
 */
final class DeepStackReportSectionWriter {

    private static final Color AUXILIARY_MASK_COLOR = new Color(255, 80, 80);

    private DeepStackReportSectionWriter() {
    }

    static void writeSection(PrintWriter report, DetectionReportContext context) throws IOException {
        if (context.config == null || !context.config.enableSlowMoverDetection) {
            return;
        }

        boolean hasCandidates = !context.slowMoverCandidates.isEmpty();
        boolean hasTelemetry = context.slowMoverTelemetry != null;
        boolean hasSlowMoverStack = context.slowMoverStackData != null;
        boolean hasSlowMoverMask = context.slowMoverMedianVetoMask != null;

        if (!hasCandidates && !hasTelemetry && !hasSlowMoverStack && !hasSlowMoverMask) {
            return;
        }

        report.println("<h2>Deep Stack Anomalies (Ultra-Slow Mover Candidates)</h2>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Objects in the master median stack that are significantly elongated compared to the rest of the star field. These may be ultra-slow moving targets that moved just enough to form a short streak, but too slowly to be rejected by the median filter.</p>");

        if (hasTelemetry) {
            report.println("<div class='panel'>");
            report.println("<h3 style='color: #ffffff; margin-top: 0;'>Slow-Mover Telemetry</h3>");
            report.println("<div class='flex-container' style='margin-bottom: 25px;'>");
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rawCandidatesExtracted), "Raw Candidates"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.candidatesAboveElongationThreshold), "Above Elongation"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.candidatesEvaluatedAgainstMasks), "Mask Stage"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.candidatesDetected), "Final Candidates"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedLowMedianSupport), "Rejected Low Overlap"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedHighMedianSupport), "Rejected High Overlap"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedLowResidualFootprintSupport), "Rejected Low Residual"));
            report.println(compactMetricBox(String.format(Locale.US, "%.2f", context.slowMoverTelemetry.medianElongation), "Median Elongation"));
            report.println(compactMetricBox(String.format(Locale.US, "%.2f", context.slowMoverTelemetry.madElongation), "MAD Elongation"));
            report.println(compactMetricBox(String.format(Locale.US, "%.2f", context.slowMoverTelemetry.dynamicElongationThreshold), "Dynamic Threshold"));
            report.println(compactMetricBox(DetectionReportGenerator.formatPercent(context.slowMoverTelemetry.medianSupportOverlapThreshold), "Min Overlap"));
            report.println(compactMetricBox(DetectionReportGenerator.formatPercent(context.slowMoverTelemetry.medianSupportMaxOverlapThreshold), "Max Overlap"));
            report.println(compactMetricBox(DetectionReportGenerator.formatPercent(context.slowMoverTelemetry.avgMedianSupportOverlap), "Average Overlap"));
            report.println(compactMetricBox(DetectionReportGenerator.formatPercent(context.slowMoverTelemetry.residualFootprintMinFluxFractionThreshold), "Residual Flux Min"));
            report.println(compactMetricBox(DetectionReportGenerator.formatPercent(context.slowMoverTelemetry.avgResidualFootprintFluxFraction), "Residual Flux Avg"));
            report.println("</div>");
            report.println(buildDeepStackMaskAndDiffExplanationHtml());
            report.println("</div>");
        } else if (hasSlowMoverStack || hasSlowMoverMask || hasCandidates) {
            report.println("<div class='panel'><p>Slow-mover telemetry was not exported for this run.</p>");
            report.println(buildDeepStackMaskAndDiffExplanationHtml());
            report.println("</div>");
        }

        if (!hasCandidates) {
            report.println("<div class='panel'><p>No ultra-slow movers were detected that exceeded the dynamic threshold.</p></div>");
            return;
        }

        List<ReportTrackReference> reportTrackReferences = buildReportTrackReferences(
                context.streakTracks,
                context.suspectedStreakTracks,
                context.movingTargets);

        report.println("<div class='flex-container'>");
        for (int i = 0; i < context.slowMoverCandidates.size(); i++) {
            SourceExtractor.DetectedObject detection = context.slowMoverCandidates.get(i);
            List<SlowMoverTrackMatch> matchedTracks = findMatchingReportedTracks(detection, reportTrackReferences);
            SlowMoverCandidateDiagnostics candidateDiagnostics = resolveCandidateDiagnostics(context, detection, i);
            exportDeepStackDetectionCard(report, context, detection, matchedTracks, candidateDiagnostics, i + 1);
        }
        report.println("</div>");
    }

    private static SlowMoverCandidateDiagnostics resolveCandidateDiagnostics(DetectionReportContext context,
                                                                            SourceExtractor.DetectedObject detection,
                                                                            int candidateIndex) {
        if (candidateIndex >= context.slowMoverCandidateResults.size()) {
            return null;
        }

        SlowMoverCandidateResult candidateResult = context.slowMoverCandidateResults.get(candidateIndex);
        if (candidateResult == null || candidateResult.object != detection) {
            return null;
        }
        return candidateResult.diagnostics;
    }

    private static void exportDeepStackDetectionCard(PrintWriter report,
                                                     DetectionReportContext context,
                                                     SourceExtractor.DetectedObject detection,
                                                     List<SlowMoverTrackMatch> matchedTracks,
                                                     SlowMoverCandidateDiagnostics candidateDiagnostics,
                                                     int candidateNumber) throws IOException {
        int cx = (int) Math.round(detection.x);
        int cy = (int) Math.round(detection.y);

        double objectRadius = TrackCropGeometry.computeFootprintRadius(detection.pixelArea, true, detection.elongation);
        int cropSize = TrackCropGeometry.computeSquareCropSize(objectRadius, 150, 100);
        int startX = cx - (cropSize / 2);
        int startY = cy - (cropSize / 2);
        short[][] maskReferenceData = context.masterStackData != null ? context.masterStackData : context.slowMoverStackData;
        Double resolvedAuxiliaryMaskOverlap = candidateDiagnostics != null ? candidateDiagnostics.medianSupportOverlap : null;

        String prefix = "slow_mover_" + candidateNumber;
        String primaryStackFileName = prefix + "_sm_stack.png";
        String masterFileName = null;
        String auxiliaryMaskFileName = prefix + "_median_mask.png";
        String secondaryStackFileName = prefix + "_maximum_stack.png";
        String diffFileName = prefix + "_diff.png";
        String gifFileName = prefix + "_anim.gif";
        String shapeFileName = prefix + "_shape.png";

        short[][] croppedPrimaryData = null;
        if (context.slowMoverStackData != null) {
            croppedPrimaryData = TrackVisualizationRenderer.robustEdgeAwareCrop(context.slowMoverStackData, cx, cy, cropSize, cropSize);
            BufferedImage primaryImage = TrackVisualizationRenderer.createDisplayImage(croppedPrimaryData, context.settings);
            TrackVisualizationRenderer.saveLosslessPng(primaryImage, new File(context.exportDir, primaryStackFileName));
        }

        if (context.masterStackData != null) {
            masterFileName = prefix + "_sm_stack_master_stack.png";
            short[][] croppedMasterData = TrackVisualizationRenderer.robustEdgeAwareCrop(context.masterStackData, cx, cy, cropSize, cropSize);
            BufferedImage masterImage = TrackVisualizationRenderer.createDisplayImage(croppedMasterData, context.settings);
            TrackVisualizationRenderer.saveLosslessPng(masterImage, new File(context.exportDir, masterFileName));

            if (croppedPrimaryData != null) {
                BufferedImage diffImage = createSlowMoverDifferenceMap(croppedPrimaryData, croppedMasterData);
                TrackVisualizationRenderer.saveLosslessPng(diffImage, new File(context.exportDir, diffFileName));
            }
        }

        if (context.slowMoverMedianVetoMask != null && context.masterStackData != null) {
            BufferedImage maskImage = DetectionReportGenerator.createCroppedMaskOverlay(
                    context.masterStackData,
                    context.slowMoverMedianVetoMask,
                    cx,
                    cy,
                    cropSize,
                    cropSize,
                    detection,
                    AUXILIARY_MASK_COLOR,
                    context.settings);
            TrackVisualizationRenderer.saveLosslessPng(maskImage, new File(context.exportDir, auxiliaryMaskFileName));
        }

        if (context.slowMoverMedianVetoMask != null && maskReferenceData != null && resolvedAuxiliaryMaskOverlap == null) {
            resolvedAuxiliaryMaskOverlap = DetectionReportGenerator.computeMaskOverlapStats(
                    detection,
                    context.slowMoverMedianVetoMask,
                    maskReferenceData[0].length,
                    maskReferenceData.length).fraction;
        }

        if (context.maximumStackData != null) {
            short[][] croppedSecondaryData = TrackVisualizationRenderer.robustEdgeAwareCrop(context.maximumStackData, cx, cy, cropSize, cropSize);
            BufferedImage secondaryImage = TrackVisualizationRenderer.createDisplayImage(croppedSecondaryData, context.settings);
            TrackVisualizationRenderer.saveLosslessPng(secondaryImage, new File(context.exportDir, secondaryStackFileName));
        }

        BufferedImage shapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                Collections.singletonList(detection),
                cropSize,
                cropSize,
                startX,
                startY,
                false);
        TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

        if (!context.rawFrames.isEmpty()) {
            List<Integer> sampledIndices = DetectionReportGenerator.getRepresentativeSequence(context.rawFrames.size(), new HashSet<>(), 10);
            List<BufferedImage> gifFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                short[][] frameData = TrackVisualizationRenderer.robustEdgeAwareCrop(context.rawFrames.get(frameIndex), cx, cy, cropSize, cropSize);
                gifFrames.add(TrackVisualizationRenderer.createDisplayImage(frameData, context.settings));
            }
            GifSequenceWriter.saveAnimatedGif(gifFrames, new File(context.exportDir, gifFileName), context.settings.getGifBlinkSpeedMs());
        }

        report.println("<div class='detection-card' style='border-left-color: #ff66ff; padding: 15px; margin-bottom: 0;'>");
        report.println("<div class='detection-title' style='color: #ff66ff; font-size: 1.1em; margin-bottom: 10px;'>Candidate #" + candidateNumber + "</div>");
        if (!matchedTracks.isEmpty()) {
            report.println("<div class='astro-note' style='margin-top: 0; margin-bottom: 12px; border-left: 3px solid #66d9a3;'>"
                    + "<strong>Also identified as reported track"
                    + (matchedTracks.size() == 1 ? "" : "s")
                    + ":</strong> "
                    + buildSlowMoverTrackMatchSummaryHtml(matchedTracks)
                    + "</div>");
        }
        report.println("<div class='image-container' style='margin-bottom: 10px;'>");
        if (masterFileName != null) {
            report.println("<div><a href='" + masterFileName + "' target='_blank'><img src='" + masterFileName + "' style='max-width: 150px;' alt='Median Stack Crop' /></a><br/><center><small>Median Stack</small></center></div>");
        }
        if (context.slowMoverMedianVetoMask != null && context.masterStackData != null) {
            report.println("<div><a href='" + auxiliaryMaskFileName + "' target='_blank'><img src='" + auxiliaryMaskFileName + "' style='max-width: 150px;' alt='Slow-Mover Median Mask Crop' /></a><br/><center><small>Slow-Mover Median Mask</small></center></div>");
        }
        if (croppedPrimaryData != null && context.masterStackData != null) {
            report.println("<div><a href='" + diffFileName + "' target='_blank'><img src='" + diffFileName + "' style='max-width: 150px;' alt='Slow Mover Diff Crop' /></a><br/><center><small>Slow Mover Diff</small></center></div>");
        }
        if (context.maximumStackData != null) {
            report.println("<div><a href='" + secondaryStackFileName + "' target='_blank'><img src='" + secondaryStackFileName + "' style='max-width: 150px;' alt='Maximum Stack Crop' /></a><br/><center><small>Maximum Stack</small></center></div>");
        }
        if (context.slowMoverStackData != null) {
            report.println("<div><a href='" + primaryStackFileName + "' target='_blank'><img src='" + primaryStackFileName + "' style='max-width: 150px;' alt='Slow Mover Stack Crop' /></a><br/><center><small>Slow Mover Stack</small></center></div>");
        }
        if (!context.rawFrames.isEmpty()) {
            report.println("<div><a href='" + gifFileName + "' target='_blank'><img src='" + gifFileName + "' style='max-width: 150px;' alt='Sampled Time-Lapse' /></a><br/><center><small>Animation (Sampled)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img src='" + shapeFileName + "' style='max-width: 150px;' alt='Shape Footprint' /></a><br/><center><small>Shape</small></center></div>");
        report.println("</div>");

        StringBuilder deepStackStats = new StringBuilder();
        deepStackStats.append("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>")
                .append(DetectionReportGenerator.escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, detection.x, detection.y)))
                .append("<br>Elongation: <span style='color:#fff;'>")
                .append(String.format(Locale.US, "%.2f", detection.elongation))
                .append("</span><br>Pixels: <span style='color:#fff;'>")
                .append((int) detection.pixelArea)
                .append("</span>");
        if (resolvedAuxiliaryMaskOverlap != null) {
            deepStackStats.append("<br>Mask Overlap: <span style='color:#fff;'>")
                    .append(DetectionReportGenerator.formatPercent(resolvedAuxiliaryMaskOverlap))
                    .append("</span>");
        }
        if (candidateDiagnostics != null) {
            deepStackStats.append("<br>Residual Footprint Flux Fraction: <span style='color:#fff;'>")
                    .append(DetectionReportGenerator.formatPercent(candidateDiagnostics.residualFootprintFluxFraction))
                    .append("</span>");
            Double residualFootprintThreshold = context.slowMoverTelemetry != null
                    ? context.slowMoverTelemetry.residualFootprintMinFluxFractionThreshold
                    : context.config.slowMoverResidualFootprintMinFluxFraction;
            if (residualFootprintThreshold != null) {
                deepStackStats.append(" <span style='color:#777;'>(threshold ")
                        .append(DetectionReportGenerator.formatPercent(residualFootprintThreshold))
                        .append(")</span>");
            }
            deepStackStats.append("<br>Residual Footprint Flux: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f", candidateDiagnostics.residualFootprintFlux))
                    .append("</span>");
            deepStackStats.append("<br>Slow-Mover Footprint Flux: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f", candidateDiagnostics.slowMoverFootprintFlux))
                    .append("</span>");
            deepStackStats.append("<br>Median Footprint Flux: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f", candidateDiagnostics.medianFootprintFlux))
                    .append("</span>");
            deepStackStats.append("<br>Footprint Pixels: <span style='color:#fff;'>")
                    .append(candidateDiagnostics.footprintPixelCount)
                    .append("</span>");
            deepStackStats.append("<br>Residual Footprint Filter: <span style='color:#fff;'>")
                    .append(candidateDiagnostics.residualFootprintFilteringEnabled ? "enabled" : "disabled")
                    .append("</span>");
        }
        deepStackStats.append("</div>");
        report.println(deepStackStats);
        report.print(DetectionReportAstrometry.buildDeepStackIdentificationHtml(
                context.astrometryContext,
                detection,
                "deep-stack-jpl-candidate-" + candidateNumber,
                "jpl_deep_stack_" + sanitizeSidecarSlug("candidate_" + candidateNumber, "stack")));
        report.println("</div>");
    }

    private static List<ReportTrackReference> buildReportTrackReferences(List<TrackLinker.Track> streakTracks,
                                                                         List<TrackLinker.Track> suspectedStreakTracks,
                                                                         List<TrackLinker.Track> movingTargets) {
        List<ReportTrackReference> references = new ArrayList<>();
        int streakCounter = 1;
        for (TrackLinker.Track track : streakTracks) {
            references.add(new ReportTrackReference(track, "ST" + streakCounter, "streak track"));
            streakCounter++;
        }

        int suspectedCounter = 1;
        for (TrackLinker.Track track : suspectedStreakTracks) {
            references.add(new ReportTrackReference(track, "SST" + suspectedCounter, "suspected streak track"));
            suspectedCounter++;
        }

        int movingCounter = 1;
        for (TrackLinker.Track track : movingTargets) {
            references.add(new ReportTrackReference(track, "T" + movingCounter, "moving object track"));
            movingCounter++;
        }
        return references;
    }

    private static List<SlowMoverTrackMatch> findMatchingReportedTracks(SourceExtractor.DetectedObject detection,
                                                                        List<ReportTrackReference> trackReferences) {
        List<SlowMoverTrackMatch> matches = new ArrayList<>();
        for (ReportTrackReference trackReference : trackReferences) {
            SlowMoverTrackMatch match = computeSlowMoverTrackMatch(detection, trackReference);
            if (match != null) {
                matches.add(match);
            }
        }

        matches.sort(Comparator
                .comparingInt((SlowMoverTrackMatch match) -> match.overlappingTrackPoints).reversed()
                .thenComparingDouble(match -> match.bestDistancePixels)
                .thenComparing(match -> match.label));
        return matches;
    }

    private static SlowMoverTrackMatch computeSlowMoverTrackMatch(SourceExtractor.DetectedObject detection,
                                                                  ReportTrackReference trackReference) {
        if (trackReference.track == null || trackReference.track.points == null || trackReference.track.points.isEmpty()) {
            return null;
        }

        double footprintRadius = computeDetectionFootprintRadius(detection);
        double matchTolerancePixels = Math.max(3.0, Math.min(10.0, footprintRadius * 0.55 + 2.0));
        int overlappingTrackPoints = 0;
        double bestDistancePixels = Double.POSITIVE_INFINITY;

        for (SourceExtractor.DetectedObject point : trackReference.track.points) {
            if (point == null) {
                continue;
            }
            double pointDistance = computePointToDetectionDistance(point, detection);
            bestDistancePixels = Math.min(bestDistancePixels, pointDistance);
            if (pointDistance <= matchTolerancePixels) {
                overlappingTrackPoints++;
            }
        }

        if (overlappingTrackPoints == 0 || !Double.isFinite(bestDistancePixels)) {
            return null;
        }

        return new SlowMoverTrackMatch(
                trackReference.label,
                trackReference.categoryLabel,
                overlappingTrackPoints,
                bestDistancePixels);
    }

    private static double computeDetectionFootprintRadius(SourceExtractor.DetectedObject detection) {
        double area = detection.pixelArea > 0.0 ? detection.pixelArea : 0.0;
        if (area <= 0.0 && detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            area = detection.rawPixels.size();
        }
        if (area <= 0.0) {
            area = 1.0;
        }
        return Math.sqrt(area / Math.PI);
    }

    private static double computePointToDetectionDistance(SourceExtractor.DetectedObject point,
                                                          SourceExtractor.DetectedObject detection) {
        if (detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            double bestDistancePixels = Double.POSITIVE_INFINITY;
            for (SourceExtractor.Pixel pixel : detection.rawPixels) {
                double dx = point.x - pixel.x;
                double dy = point.y - pixel.y;
                bestDistancePixels = Math.min(bestDistancePixels, Math.hypot(dx, dy));
            }
            if (Double.isFinite(bestDistancePixels)) {
                return bestDistancePixels;
            }
        }

        return Math.hypot(point.x - detection.x, point.y - detection.y);
    }

    private static String buildSlowMoverTrackMatchSummaryHtml(List<SlowMoverTrackMatch> matches) {
        StringBuilder html = new StringBuilder();
        int displayedMatches = Math.min(matches.size(), 3);
        for (int i = 0; i < displayedMatches; i++) {
            SlowMoverTrackMatch match = matches.get(i);
            if (i > 0) {
                html.append(", ");
            }
            html.append("<span style='color:#fff;'>")
                    .append(DetectionReportGenerator.escapeHtml(match.label))
                    .append("</span> <span style='color:#888;'>(")
                    .append(DetectionReportGenerator.escapeHtml(match.categoryLabel))
                    .append(", ")
                    .append(match.overlappingTrackPoints)
                    .append(match.overlappingTrackPoints == 1 ? " point" : " points")
                    .append(", best ")
                    .append(String.format(Locale.US, "%.1f px", match.bestDistancePixels))
                    .append(")</span>");
        }
        if (matches.size() > displayedMatches) {
            html.append(" <span style='color:#777;'>+")
                    .append(matches.size() - displayedMatches)
                    .append(" more</span>");
        }
        return html.toString();
    }

    private static BufferedImage createSlowMoverDifferenceMap(short[][] slowMover, short[][] master) {
        int width = master[0].length;
        int height = master.length;

        BufferedImage rgbMap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[][] diff = new int[height][width];
        long sum = 0;
        int maxDiff = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int slowMoverValue = slowMover[y][x] + 32768;
                int masterValue = master[y][x] + 32768;
                int delta = Math.max(0, slowMoverValue - masterValue);
                diff[y][x] = delta;
                sum += delta;
                if (delta > maxDiff) {
                    maxDiff = delta;
                }
            }
        }

        double mean = (double) sum / (width * height);
        double sumSq = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double delta = diff[y][x] - mean;
                sumSq += delta * delta;
            }
        }
        double stdDev = Math.sqrt(sumSq / (width * height));
        double threshold = mean + (2.0 * stdDev);
        double range = maxDiff - threshold;
        if (range <= 0.0) {
            range = 1.0;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (diff[y][x] <= threshold) {
                    continue;
                }
                double intensity = Math.sqrt((diff[y][x] - threshold) / range);
                if (intensity > 1.0) {
                    intensity = 1.0;
                }
                int red = (int) Math.min(255, intensity * 255);
                rgbMap.setRGB(x, y, (255 << 24) | (red << 16));
            }
        }

        return rgbMap;
    }

    private static String compactMetricBox(String value, String label) {
        return "<div class='metric-box compact'>"
                + "<span class='metric-value'>" + DetectionReportGenerator.escapeHtml(value) + "</span>"
                + "<span class='metric-label'>" + DetectionReportGenerator.escapeHtml(label) + "</span>"
                + "</div>";
    }

    private static String buildDeepStackMaskAndDiffExplanationHtml() {
        return "<div class='astro-note' style='margin-bottom: 18px;'><strong>Slow-Mover Median Mask</strong> shows the object footprints extracted from the ordinary median stack using the same strict slow-mover detection settings. It marks what the normal median stack already explains. A real ultra-slow mover should usually overlap this mask somewhat, because it still leaves some support in the median stack, but not so much that it is indistinguishable from a fully static source.<br><strong>Slow Mover Diff</strong> is the positive-only difference image <code>Slow Mover Stack - Median Stack</code>. Black means there is no extra signal in the slow-mover stack at that pixel; red highlights signal that becomes brighter in the slow-mover stack and can reveal faint ultra-slow motion.</div>";
    }

    private static String sanitizeSidecarSlug(String value, String fallback) {
        String sanitized = value == null ? "" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
        sanitized = sanitized.replaceAll("^_+|_+$", "");
        if (sanitized.isEmpty()) {
            return fallback;
        }
        return sanitized.length() > 48 ? sanitized.substring(0, 48) : sanitized;
    }

    private static final class ReportTrackReference {
        private final TrackLinker.Track track;
        private final String label;
        private final String categoryLabel;

        private ReportTrackReference(TrackLinker.Track track, String label, String categoryLabel) {
            this.track = track;
            this.label = label;
            this.categoryLabel = categoryLabel;
        }
    }

    private static final class SlowMoverTrackMatch {
        private final String label;
        private final String categoryLabel;
        private final int overlappingTrackPoints;
        private final double bestDistancePixels;

        private SlowMoverTrackMatch(String label,
                                    String categoryLabel,
                                    int overlappingTrackPoints,
                                    double bestDistancePixels) {
            this.label = label;
            this.categoryLabel = categoryLabel;
            this.overlappingTrackPoints = overlappingTrackPoints;
            this.bestDistancePixels = bestDistancePixels;
        }
    }
}
