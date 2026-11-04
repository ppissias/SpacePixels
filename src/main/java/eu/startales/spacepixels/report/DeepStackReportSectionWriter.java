package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.DisplayImageRenderer;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.core.SlowMoverCandidateDiagnostics;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverMotionEvidence;
import io.github.ppissias.jtransient.core.SlowMoverTrackMatcher;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Writes the maximum-stack slow-mover section covering morphology telemetry, candidate cards, and supporting
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

        report.println("<h2>Slow-Mover Candidates</h2>");
        report.println("<p class='section-lede'>An object that moves only a few pixels during the whole session leaves a short, elongated smear in the maximum stack (the brightest value of every pixel). These candidates were picked by that shape and because they do not overlap a star of the median stack. <strong>Frame support</strong> is the share of usable frames with signal at the candidate; <strong>stationary likelihood</strong> is the share of those positions that cluster in one spot, so a high value points to a star or a defect rather than motion. Both are heuristics, not confirmations.</p>");

        if (hasTelemetry) {
            report.println("<div class='panel'>");
            report.println("<h3 style='color: #ffffff; margin-top: 0;'>How the Candidates Were Selected</h3>");
            report.println("<div class='flex-container' style='margin-bottom: 25px;'>");
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rawCandidatesExtracted), "Raw Candidates"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedBelowMinPixels), "Below Min Pixels"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedBelowMinAxisRatio), "Below Min Axis Ratio"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedAboveMaxAxisRatio), "Above Max Axis Ratio"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedLowFillFactor), "Low Fill Factor"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.evaluatedAgainstMedianMask), "Mask Stage"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.candidatesDetected), "Final Candidates"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedLowMedianSupport), "Rejected Low Overlap"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedHighMedianSupport), "Rejected High Overlap"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.evaluatedAgainstFrames), "Frame Support Measured"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.frameEvidenceUnavailable), "Frame Support Unavailable"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedLowFrameSupport), "Rejected Low Frame Support"));
            report.println(compactMetricBox(String.valueOf(context.slowMoverTelemetry.rejectedHighStationaryLikelihood), "Rejected High Stationary Likelihood"));
            report.println(compactMetricBox(String.format(Locale.US, "%.2f", context.slowMoverTelemetry.minAxisRatioThreshold), "Min Axis Ratio"));
            report.println(compactMetricBox(String.format(Locale.US, "%.2f", context.slowMoverTelemetry.maxAxisRatioThreshold), "Max Axis Ratio"));
            report.println(compactMetricBox(ReportFormat.formatPercent(context.slowMoverTelemetry.minFillFactorThreshold), "Min Fill Factor"));
            report.println(compactMetricBox(String.format(Locale.US, "%.2f", context.slowMoverTelemetry.avgCandidateAxisRatio), "Avg Candidate Axis Ratio"));
            report.println(compactMetricBox(ReportFormat.formatPercent(context.slowMoverTelemetry.medianSupportOverlapThreshold), "Min Overlap"));
            report.println(compactMetricBox(ReportFormat.formatPercent(context.slowMoverTelemetry.medianSupportMaxOverlapThreshold), "Max Overlap"));
            report.println(compactMetricBox(ReportFormat.formatPercent(context.slowMoverTelemetry.avgMedianMaskOverlap), "Avg Evaluated Overlap"));
            report.println(compactMetricBox(formatPercentage(context.slowMoverTelemetry.minFrameSupportThreshold), "Min Frame Support"));
            report.println(compactMetricBox(formatPercentage(context.slowMoverTelemetry.maxStationaryLikelihoodThreshold), "Max Stationary Likelihood"));
            report.println("</div>");
            report.println(buildDeepStackMaskAndDiffExplanationHtml());
            report.println("</div>");
        } else if (hasSlowMoverStack || hasSlowMoverMask || hasCandidates) {
            report.println("<div class='panel'><p>Slow-mover telemetry was not exported for this run.</p>");
            report.println(buildDeepStackMaskAndDiffExplanationHtml());
            report.println("</div>");
        }

        if (!hasCandidates) {
            report.println("<div class='panel'><p>No maximum-stack candidates passed all enabled filters.</p></div>");
            return;
        }

        List<ReportTrackReference> reportTrackReferences = buildReportTrackReferences(context);

        report.println("<div class='flex-container'>");
        for (int i = 0; i < context.slowMoverCandidates.size(); i++) {
            DetectedObject detection = context.slowMoverCandidates.get(i);
            SlowMoverCandidateDiagnostics candidateDiagnostics = resolveCandidateDiagnostics(context, detection, i);
            List<SlowMoverTrackMatch> matchedTracks = findMatchingReportedTracks(detection, candidateDiagnostics, reportTrackReferences);
            exportDeepStackDetectionCard(report, context, detection, matchedTracks, candidateDiagnostics, i + 1);
        }
        report.println("</div>");
    }

    private static SlowMoverCandidateDiagnostics resolveCandidateDiagnostics(DetectionReportContext context,
                                                                            DetectedObject detection,
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
                                                     DetectedObject detection,
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
        Double resolvedAuxiliaryMaskOverlap = candidateDiagnostics != null ? candidateDiagnostics.medianMaskOverlapFraction : null;

        String prefix = "slow_mover_" + candidateNumber;
        String primaryStackFileName = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_maximum_stack.png");
        String masterFileName = null;
        String auxiliaryMaskFileName = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_median_mask.png");
        String diffFileName = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_diff.png");
        String gifFileName = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_anim.gif");
        String shapeFileName = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_shape.png");

        short[][] croppedPrimaryData = null;
        if (context.slowMoverStackData != null) {
            croppedPrimaryData = TrackVisualizationRenderer.robustEdgeAwareCrop(context.slowMoverStackData, cx, cy, cropSize, cropSize);
            BufferedImage primaryImage = TrackVisualizationRenderer.createDisplayImage(croppedPrimaryData, context.settings);
            TrackVisualizationRenderer.saveLosslessPng(primaryImage, context.file(primaryStackFileName));
        }

        if (context.masterStackData != null) {
            masterFileName = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_sm_stack_master_stack.png");
            short[][] croppedMasterData = TrackVisualizationRenderer.robustEdgeAwareCrop(context.masterStackData, cx, cy, cropSize, cropSize);
            BufferedImage masterImage = TrackVisualizationRenderer.createDisplayImage(croppedMasterData, context.settings);
            TrackVisualizationRenderer.saveLosslessPng(masterImage, context.file(masterFileName));

            if (croppedPrimaryData != null) {
                BufferedImage diffImage = createSlowMoverDifferenceMap(croppedPrimaryData, croppedMasterData);
                TrackVisualizationRenderer.saveLosslessPng(diffImage, context.file(diffFileName));
            }
        }

        if (context.slowMoverMedianVetoMask != null && context.masterStackData != null) {
            BufferedImage maskImage = ReportMaps.createCroppedMaskOverlay(
                    context.masterStackData,
                    context.slowMoverMedianVetoMask,
                    cx,
                    cy,
                    cropSize,
                    cropSize,
                    detection,
                    AUXILIARY_MASK_COLOR,
                    context.settings);
            TrackVisualizationRenderer.saveLosslessPng(maskImage, context.file(auxiliaryMaskFileName));
        }

        if (context.slowMoverMedianVetoMask != null && maskReferenceData != null && resolvedAuxiliaryMaskOverlap == null) {
            resolvedAuxiliaryMaskOverlap = ReportMaps.computeMaskOverlapStats(
                    detection,
                    context.slowMoverMedianVetoMask,
                    maskReferenceData[0].length,
                    maskReferenceData.length).fraction;
        }

        BufferedImage shapeImage = TrackVisualizationRenderer.createSingleStreakShapeImage(
                Collections.singletonList(detection),
                cropSize,
                cropSize,
                startX,
                startY,
                false);
        TrackVisualizationRenderer.saveLosslessPng(shapeImage, context.file(shapeFileName));

        if (!context.rawFrames.isEmpty()) {
            List<Integer> sampledIndices = context.sampleFrames(new HashSet<>(), 10);
            List<short[][]> frameCrops = new ArrayList<>(sampledIndices.size());
            for (int frameIndex : sampledIndices) {
                frameCrops.add(TrackVisualizationRenderer.robustEdgeAwareCrop(context.rawFrames.get(frameIndex), cx, cy, cropSize, cropSize));
            }
            double[] levels = TrackVisualizationRenderer.sharedDisplayLevels(frameCrops, context.settings);
            List<BufferedImage> gifFrames = new ArrayList<>(sampledIndices.size());
            for (int i = 0; i < sampledIndices.size(); i++) {
                int frameIndex = sampledIndices.get(i);
                BufferedImage image = TrackVisualizationRenderer.createDisplayImageAtLevels(frameCrops.get(i), levels);
                var graphics = image.createGraphics(); graphics.setColor(Color.WHITE);
                graphics.drawString(TrackVisualizationRenderer.frameLabel(context, frameIndex), 8, 16); graphics.dispose();
                gifFrames.add(image);
            }
            GifSequenceWriter.saveAnimatedGif(gifFrames, context.file(gifFileName), context.settings.getGifBlinkSpeedMs(), context.animations);
        }

        report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.SLOW_MOVER, candidateNumber) + "' style='border-left-color: #ff66ff; padding: 15px; margin-bottom: 0;'>");
        String trackBadge = matchedTracks.isEmpty() ? "" : ReportPresentation.trackBadge("Track overlap: " + matchedTracks.get(0).label
                + (matchedTracks.size() > 1 ? " +" + (matchedTracks.size() - 1) : ""), matchedTracks.get(0).label);
        report.println("<div class='detection-title' style='color: #ff66ff; font-size: 1.1em; margin-bottom: 10px;'>Slow-Mover Candidate " + FindingCategory.SLOW_MOVER.id(candidateNumber) + trackBadge + "</div>");
        if (!matchedTracks.isEmpty()) {
            report.println("<div class='track-link-note'><strong>Nearby reported track"
                    + (matchedTracks.size() == 1 ? " " : "s ")
                    + buildSlowMoverTrackLinksHtml(matchedTracks)
                    + ".</strong> "
                    + buildSlowMoverTrackMatchSummaryHtml(matchedTracks)
                    + " Overlap alone does not establish that the findings are the same source."
                    + "</div>");
        }
        report.println("<div class='image-container' style='margin-bottom: 10px;'>");
        if (masterFileName != null) {
            report.println("<div><a href='" + masterFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + masterFileName + "' style='max-width: 150px;' alt='Median Stack Crop' /></a><br/><center><small>Median Stack</small></center></div>");
        }
        if (context.slowMoverMedianVetoMask != null && context.masterStackData != null) {
            report.println("<div><a href='" + auxiliaryMaskFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + auxiliaryMaskFileName + "' style='max-width: 150px;' alt='Exact Median Mask Crop' /></a><br/><center><small>Exact Median Mask</small></center></div>");
        }
        if (croppedPrimaryData != null && context.masterStackData != null) {
            report.println("<div><a href='" + diffFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + diffFileName + "' style='max-width: 150px;' alt='Maximum Minus Median Crop' /></a><br/><center><small>Maximum - Median (inspection only)</small></center></div>");
        }
        if (context.slowMoverStackData != null) {
            report.println("<div><a href='" + primaryStackFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + primaryStackFileName + "' style='max-width: 150px;' alt='Maximum Stack Crop' /></a><br/><center><small>Maximum Stack</small></center></div>");
        }
        if (!context.rawFrames.isEmpty()) {
            report.println("<div><a href='" + gifFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + gifFileName + "' style='max-width: 150px;' alt='Sampled Time-Lapse' /></a><br/><center><small>Animation (sampled kept frames)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + shapeFileName + "' style='max-width: 150px;' alt='Shape Footprint' /></a><br/><center><small>Shape</small></center></div>");
        report.println("</div>");

        StringBuilder deepStackStats = new StringBuilder();
        deepStackStats.append("<div style='font-family: monospace; font-size: 12px; color: #aaa;'>")
                .append(ReportFormat.escapeHtml(DetectionReportAstrometry.formatPixelCoordinateWithSky(context.astrometryContext, detection.x, detection.y)))
                .append("<br>Moment Elongation: <span style='color:#fff;'>")
                .append(String.format(Locale.US, "%.2f", detection.elongation))
                .append("</span><br>Pixels: <span style='color:#fff;'>")
                .append((int) detection.pixelArea)
                .append("</span>");
        if (resolvedAuxiliaryMaskOverlap != null) {
            deepStackStats.append("<br>Mask Overlap: <span style='color:#fff;'>")
                    .append(ReportFormat.formatPercent(resolvedAuxiliaryMaskOverlap))
                    .append("</span>");
        }
        if (candidateDiagnostics != null) {
            deepStackStats.append("<br>Axis Ratio: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.2f", candidateDiagnostics.axisRatio))
                    .append("</span><br>Fill Factor: <span style='color:#fff;'>")
                    .append(ReportFormat.formatPercent(candidateDiagnostics.fillFactor))
                    .append("</span><br>Outside Median Mask: <span style='color:#fff;'>")
                    .append(ReportFormat.formatPercent(candidateDiagnostics.outsideMedianMaskFraction))
                    .append("</span><br>Shape-Estimated Motion: <span style='color:#fff;'>")
                    .append(String.format(Locale.US, "%.1f px (%.2f diameters)", candidateDiagnostics.estimatedMotionPixels, candidateDiagnostics.estimatedMotionDiameters))
                    .append("</span>");
            deepStackStats.append("<br>Footprint Pixels: <span style='color:#fff;'>")
                    .append(candidateDiagnostics.pixelCount)
                    .append("</span>");
        }
        deepStackStats.append("<br>Frame Support: <span style='color:#fff;'>");
        if (candidateDiagnostics != null && candidateDiagnostics.frameSupportAvailable) {
            deepStackStats.append(formatPercentage(candidateDiagnostics.frameSupportPercentage))
                    .append(" (")
                    .append(candidateDiagnostics.supportedFrameCount)
                    .append("/")
                    .append(candidateDiagnostics.usableFrameCount)
                    .append(" usable frames)");
        } else {
            deepStackStats.append("Unavailable");
        }
        deepStackStats.append("</span><br>Stationary Likelihood (heuristic): <span style='color:#fff;'>")
                .append(candidateDiagnostics != null && candidateDiagnostics.stationaryLikelihoodAvailable
                        ? formatPercentage(candidateDiagnostics.stationaryLikelihoodPercentage)
                        : "Unavailable")
                .append("</span>");
        deepStackStats.append("</div>");
        report.println(deepStackStats);
        writeMotionEvidence(report, context, candidateDiagnostics, prefix);
        report.print(DetectionReportAstrometry.buildDeepStackIdentificationHtml(
                context.astrometryContext,
                detection,
                "deep-stack-jpl-candidate-" + candidateNumber,
                "jpl_deep_stack_" + sanitizeSidecarSlug("candidate_" + candidateNumber, "stack")));
        report.println("</div>");
    }

    /** Borrows linked tracks and their shared report IDs without independently re-numbering either category. */
    private static List<ReportTrackReference> buildReportTrackReferences(DetectionReportContext context) {
        List<ReportTrackReference> references = new ArrayList<>();
        for (Track track : context.streakTracks) {
            references.add(new ReportTrackReference(track, context.trackFindings.id(track), "streak track"));
        }

        for (Track track : context.movingTargets) {
            references.add(new ReportTrackReference(track, context.trackFindings.id(track), "moving object track"));
        }
        return references;
    }

    /** Writes copied centroid evidence and a CSV; the fit is inspection data, never a confidence or detection gate. */
    private static void writeMotionEvidence(PrintWriter report, DetectionReportContext context,
                                             SlowMoverCandidateDiagnostics diagnostics, String prefix) throws IOException {
        SlowMoverMotionEvidence evidence = diagnostics == null ? null : diagnostics.motionEvidence;
        report.print("<details class='slow-mover-motion-evidence'><summary>Original-frame centroid drift (inspection)</summary>");
        if (evidence != null && !evidence.positions.isEmpty()) {
            String csv = ReportFiles.link(FindingCategory.SLOW_MOVER, prefix + "_positions.csv");
            try (var writer = Files.newBufferedWriter(context.file(csv).toPath(), StandardCharsets.UTF_8)) {
                writer.write("original_frame_number,retained_frame_number,timestamp_ms,x_px,y_px,aperture_snr\n");
                for (var p : evidence.positions) {
                    writer.write(String.format(Locale.US, "%d,%d,%s,%.6f,%.6f,%.6f%n",
                            (long) p.sourceFrameIndex() + 1, p.retainedFrameIndex() + 1,
                            p.timestamp() >= 0 ? Long.toString(p.timestamp()) : "", p.x(), p.y(), p.signalToNoise()));
                }
            }
            report.printf(Locale.US, "<p>%d supported aperture centroids. <a href='%s' download>Download positions CSV</a>.</p>",
                    evidence.positions.size(), csv);
        }
        if (evidence != null && evidence.fitAvailable) {
            report.printf(Locale.US,
                    "<p>Timed fit over %.2f of %.2f min (%.1f%% of the session span). Fitted drift: %.3f px/min; %.2f px over the supported span. RMS residual: %.2f px. ",
                    evidence.supportedSpanMinutes, evidence.sessionSpanMinutes, 100 * evidence.sessionSpanFraction,
                    evidence.speedPixelsPerMinute, evidence.displacementPixels, evidence.rmsPixels);
            if (evidence.correlationAvailable) {
                report.printf(Locale.US, "|r| along the footprint axis: %.3f.</p>", Math.abs(evidence.projectedCorrelation));
            } else {
                report.print("Correlation unavailable: no measurable position variance along the footprint axis.</p>");
            }
        } else {
            report.print("<p>Timed fit unavailable: " + ReportFormat.escapeHtml(evidence == null
                    ? "Original-frame positions are unavailable" : evidence.unavailableReason) + ".</p>");
        }
        report.print("<p>These are the strongest aperture positions inside the footprint. A star, seeing or registration trend may dominate. "
                + "The fit and correlation do not confirm a moving source.</p></details>");
    }

    /** Measures possible correspondences only after reported tracks exist; neither finding is suppressed. */
    private static List<SlowMoverTrackMatch> findMatchingReportedTracks(DetectedObject detection,
                                                                        SlowMoverCandidateDiagnostics diagnostics,
                                                                        List<ReportTrackReference> trackReferences) {
        List<SlowMoverTrackMatch> matches = new ArrayList<>();
        for (ReportTrackReference trackReference : trackReferences) {
            SlowMoverTrackMatcher.Match comparison = SlowMoverTrackMatcher.match(detection,
                    diagnostics == null ? null : diagnostics.motionEvidence, trackReference.track);
            SlowMoverTrackMatch match = comparison == null ? null
                    : new SlowMoverTrackMatch(trackReference.label, trackReference.categoryLabel, comparison);
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

    private static String buildSlowMoverTrackLinksHtml(List<SlowMoverTrackMatch> matches) {
        StringBuilder html = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                html.append(", ");
            }
            html.append(ReportPresentation.findingLink(matches.get(i).label));
        }
        return html.toString();
    }

    /** Describes spatial screening and actual common-frame distances without assigning source identity. */
    private static String buildSlowMoverTrackMatchSummaryHtml(List<SlowMoverTrackMatch> matches) {
        StringBuilder html = new StringBuilder();
        int displayedMatches = Math.min(matches.size(), 3);
        for (int i = 0; i < displayedMatches; i++) {
            SlowMoverTrackMatch match = matches.get(i);
            if (i > 0) {
                html.append(" ");
            }
            html.append(ReportFormat.escapeHtml(match.label))
                    .append(" (")
                    .append(ReportFormat.escapeHtml(match.categoryLabel))
                    .append(") has ")
                    .append(match.overlappingTrackPoints)
                    .append(match.overlappingTrackPoints == 1 ? " detection" : " detections")
                    .append(" near this footprint, the closest ")
                    .append(String.format(Locale.US, "%.1f px", match.bestDistancePixels))
                    .append(" from it.");
            var comparison = match.comparison;
            if (comparison.frameComparisonAvailable()) {
                html.append(String.format(Locale.US,
                        " %d common supported frame%s: RMS centroid separation %.2f px, maximum %.2f px.",
                        comparison.comparedFrames(), comparison.comparedFrames() == 1 ? "" : "s",
                        comparison.framePositionRmsPixels(), comparison.maxFramePositionDistancePixels()));
            } else {
                html.append(" Common-frame centroid comparison unavailable; spatial overlap only.");
            }
            if (comparison.ambiguousFrames() > 0 || comparison.timestampMismatches() > 0) {
                html.append(String.format(Locale.US, " Excluded %d ambiguous frame IDs and %d conflicting capture times.",
                        comparison.ambiguousFrames(), comparison.timestampMismatches()));
            }
        }
        if (matches.size() > displayedMatches) {
            html.append(" ")
                    .append(matches.size() - displayedMatches)
                    .append(" more ")
                    .append(matches.size() - displayedMatches == 1 ? "track overlaps" : "tracks overlap")
                    .append(" it.");
        }
        return html.toString();
    }

    private static BufferedImage createSlowMoverDifferenceMap(short[][] slowMover, short[][] master) {
        int width = master[0].length;
        int height = master.length;

        BufferedImage rgbMap = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        int[][] diff = new int[height][width];
        boolean[][] valid = new boolean[height][width];
        long sum = 0;
        long validCount = 0;
        int maxDiff = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                short rawSlowMover = slowMover[y][x];
                short rawMaster = master[y][x];
                if (rawSlowMover <= DataArea.FILL_THRESHOLD || rawMaster <= DataArea.FILL_THRESHOLD) {
                    continue;
                }
                valid[y][x] = true;
                validCount++;
                int slowMoverValue = rawSlowMover + 32768;
                int masterValue = rawMaster + 32768;
                int delta = Math.max(0, slowMoverValue - masterValue);
                diff[y][x] = delta;
                sum += delta;
                if (delta > maxDiff) {
                    maxDiff = delta;
                }
            }
        }
        if (validCount == 0) {
            return rgbMap;
        }

        double mean = (double) sum / validCount;
        double sumSq = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!valid[y][x]) {
                    continue;
                }
                double delta = diff[y][x] - mean;
                sumSq += delta * delta;
            }
        }
        double stdDev = Math.sqrt(sumSq / validCount);
        double threshold = mean + (2.0 * stdDev);
        double range = maxDiff - threshold;
        if (range <= 0.0) {
            range = 1.0;
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!valid[y][x] || diff[y][x] <= threshold) {
                    continue;
                }
                int red = DisplayImageRenderer.sqrtToneCurve((diff[y][x] - threshold) / range);
                rgbMap.setRGB(x, y, (255 << 24) | (red << 16));
            }
        }

        return rgbMap;
    }

    private static String compactMetricBox(String value, String label) {
        return "<div class='metric-box compact'>"
                + "<span class='metric-value'>" + ReportFormat.escapeHtml(value) + "</span>"
                + "<span class='metric-label'>" + ReportFormat.escapeHtml(label) + "</span>"
                + "</div>";
    }

    private static String formatPercentage(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    private static String buildDeepStackMaskAndDiffExplanationHtml() {
        return "<div class='astro-note' style='margin-bottom: 18px;'><strong>Exact Median Mask</strong> contains raw source pixels extracted independently from the median stack. Overlap is the fraction of maximum-stack candidate raw pixels inside that mask; zero minimum overlap does not require median persistence. High overlap can indicate a stationary source.<br><strong>Maximum - Median</strong> is a positive-only difference image for visual inspection. It does not participate in candidate acceptance.</div>";
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
        private final Track track;
        private final String label;
        private final String categoryLabel;

        private ReportTrackReference(Track track, String label, String categoryLabel) {
            this.track = track;
            this.label = label;
            this.categoryLabel = categoryLabel;
        }
    }

    /** Presentation labels and immutable engine measurements for one possible correspondence. */
    private static final class SlowMoverTrackMatch {
        private final String label;
        private final String categoryLabel;
        private final int overlappingTrackPoints;
        private final double bestDistancePixels;
        private final SlowMoverTrackMatcher.Match comparison;

        /** Adds report labels to an engine-owned comparison, without retaining mutable track objects. */
        private SlowMoverTrackMatch(String label,
                                    String categoryLabel,
                                    SlowMoverTrackMatcher.Match comparison) {
            this.label = label;
            this.categoryLabel = categoryLabel;
            this.overlappingTrackPoints = comparison.overlappingTrackPoints();
            this.bestDistancePixels = comparison.closestDistancePixels();
            this.comparison = comparison;
        }
    }
}
