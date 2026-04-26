package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import io.github.ppissias.jtransient.telemetry.TrackerTelemetry;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Emits the pipeline-summary, diagnostics, configuration, and quality-control sections that appear
 * before the per-target report cards.
 */
final class PipelineDiagnosticsSectionWriter {

    private PipelineDiagnosticsSectionWriter() {
    }

    static void writeSections(PrintWriter report,
                              DetectionReportContext reportContext,
                              PipelineTelemetry pipelineTelemetry,
                              TrackerTelemetry linkerTelemetry,
                              DetectionReportSummary summary,
                              List<SourceExtractor.Pixel> driftPoints) throws IOException {
        if (pipelineTelemetry != null) {
            writePipelineOverview(report, reportContext, pipelineTelemetry, summary);
            writeAstrometricContext(report, reportContext);
            writeFrameQualityStatistics(report, pipelineTelemetry);
            writeRejectedFrames(report, pipelineTelemetry);
            writePipelineConfiguration(report, reportContext);
            writeMasterShieldDiagnostics(report, reportContext, summary);
            writeDriftDiagnostics(report, reportContext, driftPoints);
            writeExtractionStatistics(report, pipelineTelemetry);
            writeStationaryStarPurification(report, pipelineTelemetry, linkerTelemetry);
        }

        if (linkerTelemetry != null) {
            writeTrackLinkingDiagnostics(report, linkerTelemetry, summary);
        }
    }

    private static void writePipelineOverview(PrintWriter report,
                                              DetectionReportContext reportContext,
                                              PipelineTelemetry pipelineTelemetry,
                                              DetectionReportSummary summary) {
        report.println("<div class='panel'>");
        report.println("<h2>Pipeline Summary</h2>");
        report.println("<div class='flex-container'>");
        report.println("<div class='metric-box'><span class='metric-value'>" + String.format(Locale.US, "%.2f", pipelineTelemetry.processingTimeMs / 1000.0) + "s</span><span class='metric-label'>Processing Time</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalFramesLoaded + "</span><span class='metric-label'>Total Frames</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalFramesKept + " <span style='color:#555; font-size: 16px;'>/ " + pipelineTelemetry.totalFramesRejected + "</span></span><span class='metric-label'>Kept / Rejected</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + pipelineTelemetry.totalRawObjectsExtracted + "</span><span class='metric-label'>Raw Objects Extracted</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.masterStarCount + "</span><span class='metric-label'>Master Stars</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.returnedTrackCount + "</span><span class='metric-label'>Tracks Returned</span></div>");
        report.println("</div>");
        report.println("</div>");

        report.println("<div class='panel'>");
        report.println("<h2>Detection Breakdown</h2>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>Final report sections summarized up front so confirmed tracks, anomalies, suspected streak tracks, and potential slow movers are visible immediately.</p>");
        report.println("<div class='flex-container'>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.singleStreakCount + "</span><span class='metric-label'>Streaks</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.streakTrackCount + "</span><span class='metric-label'>Streak Tracks</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.movingTargetCount + "</span><span class='metric-label'>Moving Object Tracks</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.anomalyCount + "</span><span class='metric-label'>Single-Frame Anomalies</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.suspectedStreakTrackCount + "</span><span class='metric-label'>Suspected Streak Tracks</span></div>");
        String potentialSlowMoverMetric = reportContext.config.enableSlowMoverDetection ? String.valueOf(summary.slowMoverCandidateCount) : "Off";
        report.println("<div class='metric-box'><span class='metric-value'>" + potentialSlowMoverMetric + "</span><span class='metric-label'>Potential Slow Movers</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.localRescueCandidateCount + "</span><span class='metric-label'>Local Rescue Candidates</span></div>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.localActivityClusterCount + "</span><span class='metric-label'>Local Activity Clusters</span></div>");
        report.println("</div>");
        report.println("<div class='astro-note'>JTransient returned <strong>" + summary.returnedTrackCount + "</strong> track-like detections overall: <strong>" + summary.singleStreakCount + "</strong> single-frame streaks, <strong>" + summary.confirmedLinkedTrackCount + "</strong> confirmed linked tracks, and <strong>" + summary.suspectedStreakTrackCount + "</strong> suspected streak groupings.</div>");
        if (!reportContext.anomalies.isEmpty()) {
            report.println("<div class='astro-note'>Single-frame anomaly type split shown below matches the order used in the anomaly cards and A# map labels.</div>");
            report.println("<div class='flex-container'>");
            report.println("<div class='metric-box compact'><span class='metric-value'>" + summary.peakSigmaAnomalyCount + "</span><span class='metric-label'>Peak-Sigma Anomalies</span></div>");
            report.println("<div class='metric-box compact'><span class='metric-value'>" + summary.integratedSigmaAnomalyCount + "</span><span class='metric-label'>Integrated-Sigma Anomalies</span></div>");
            if (summary.otherAnomalyCount > 0) {
                report.println("<div class='metric-box compact'><span class='metric-value'>" + summary.otherAnomalyCount + "</span><span class='metric-label'>Other / Unknown Anomalies</span></div>");
            }
            report.println("</div>");
        }
        if (reportContext.config.enableSlowMoverDetection) {
            report.println("<div class='astro-note'>Top-level counts are separated by category: <strong>" + summary.slowMoverCandidateCount + "</strong> deep-stack slow-mover candidates and <strong>" + summary.localRescueCandidateCount + "</strong> local rescue candidates.</div>");
        } else {
            report.println("<div class='astro-note'>Potential slow mover analysis was disabled for this session.</div>");
        }
        report.println("<div class='astro-note'>Local rescue candidates and local activity clusters are engine-side residual outputs from JTransient. Rescue candidates are mined from <strong>unclassifiedTransients</strong>, then the remaining leftovers are clustered into broader local activity groups for manual review. This run ended with <strong>" + summary.unclassifiedTransientCount + "</strong> unclassified transient detections before residual analysis.</div>");
        report.println("</div>");

        if (summary.insufficientFramesAfterQuality) {
            report.println("<div class='panel'>");
            report.println("<h2>Quality-Control Guardrail</h2>");
            report.println("<p>Only <strong>" + pipelineTelemetry.totalFramesKept + "</strong> frames remained after quality control. SpacePixels needs at least <strong>" + ImageProcessing.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS + "</strong> usable frames before multi-frame tracking and deep-stack review are meaningful, so those downstream sections were skipped for this run.</p>");
            report.println("<div class='astro-note'>The quality-control tables below still show which frames were rejected and why.</div>");
            report.println("</div>");
        }
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
                    : "500";
            report.println("<div class='metric-box'><span class='metric-value'>" + observerMetric + "</span><span class='metric-label'>SkyBoT Observer</span></div>");
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
        report.println("<p class='compact-note'>Per-frame quality metrics after the session evaluator. Bright-star eccentricity is shown as <code>n/a</code> when too few bright stars qualified for that frame. For long sessions, the detailed table below is scrollable and keeps its column headers pinned.</p>");
        report.println("<div class='compact-threshold-grid'>");
        report.println("<div class='config-item'><span>Thresholds Available</span><span class='val'>" + (pipelineTelemetry.qualityThresholds.available ? "Yes" : "No") + "</span></div>");
        report.println("<div class='config-item'><span>Min Allowed Star Count</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.minAllowedStarCount) + "</span></div>");
        report.println("<div class='config-item'><span>Max Allowed FWHM</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedFwhm) + "</span></div>");
        report.println("<div class='config-item'><span>Max Allowed Eccentricity</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedEccentricity) + "</span></div>");
        report.println("<div class='config-item'><span>Max Bright-Star Eccentricity</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBrightStarEccentricity) + "</span></div>");
        report.println("<div class='config-item'><span>Background Median Baseline</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.backgroundMedianBaseline) + "</span></div>");
        report.println("<div class='config-item'><span>Max Background Deviation</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBackgroundDeviation) + "</span></div>");
        report.println("<div class='config-item'><span>Min Allowed Bg Median</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.minAllowedBackgroundMedian) + "</span></div>");
        report.println("<div class='config-item'><span>Max Allowed Bg Median</span><span class='val'>" + DetectionReportGenerator.formatOptionalMetric(pipelineTelemetry.qualityThresholds.maxAllowedBackgroundMedian) + "</span></div>");
        report.println("</div>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Frame Index</th><th>Filename</th><th>Bg Median</th><th>Bg Sigma</th><th>Median FWHM</th><th>Median Ecc</th><th>Bright-Star Median Ecc</th><th>Stars</th><th>Shape Stars</th><th>Bright Shape Stars</th><th>FWHM Stars</th><th>Status</th><th>Rejection Reason</th></tr></thead><tbody>");
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
        report.println("<table><tr><th>Frame Index</th><th>Filename</th><th>Median Ecc</th><th>Bright-Star Median Ecc</th><th>Bright Shape Stars</th><th>Rejection Reason</th></tr>");
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
        report.println("<p style='font-size: 13px; color: #888; margin-top: -10px;'>Active tuning parameters used during this session. <a href='detection_config.json' target='_blank' style='color: #4da6ff; text-decoration: none;'>[View / Download JSON Profile]</a></p>");
        report.println("<div class='scroll-box' style='padding: 10px;'><div class='config-grid'>");

        for (Field field : reportContext.config.getClass().getDeclaredFields()) {
            try {
                field.setAccessible(true);
                Object value = field.get(reportContext.config);
                report.println("<div class='config-item'><span>" + field.getName() + "</span> <span class='val'>" + value + "</span></div>");
            } catch (IllegalAccessException e) {
                // Silently ignore fields that cannot be accessed
            }
        }
        report.println("</div></div></div>");
    }

    private static void writeMasterShieldDiagnostics(PrintWriter report,
                                                     DetectionReportContext reportContext,
                                                     DetectionReportSummary summary) throws IOException {
        boolean hasMasterShieldDiagnostics = reportContext.masterStackData != null && reportContext.masterVetoMask != null;

        report.println("<div class='panel'>");
        report.println("<h2>Master Shield & Veto Mask</h2>");
        report.println("<p style='color: #999999; font-size: 14px; margin-top: -10px; margin-bottom: 15px;'>");
        report.println("The raw median stack (Left) and the extracted Binary Veto Mask painted in red (Right). " +
                "These are the detected objects in the master map extended by a max star jitter of <strong>" + reportContext.config.maxStarJitter + "</strong> pixels. " +
                "During Phase 3, we calculate the overlap fraction of each transient against this mask to determine if it should be deleted (purged as a stationary star) or allowed to survive. " +
                "The maximum allowed overlap fraction is currently set to <strong>" + reportContext.config.maxMaskOverlapFraction + "</strong> (" + (int) (reportContext.config.maxMaskOverlapFraction * 100) + "%).</p>");
        report.println("<div class='flex-container' style='margin-bottom: 15px;'>");
        report.println("<div class='metric-box'><span class='metric-value'>" + summary.masterMapObjectCount + "</span><span class='metric-label'>Master Map Objects</span></div>");
        report.println("</div>");
        if (hasMasterShieldDiagnostics) {
            BufferedImage masterImg = DetectionReportGenerator.createDisplayImage(reportContext.masterStackData);
            TrackVisualizationRenderer.saveLosslessPng(masterImg, new File(reportContext.exportDir, "master_stack.png"));

            BufferedImage masterMaskImg = DetectionReportGenerator.createMasterMaskOverlay(reportContext.masterStackData, reportContext.masterVetoMask);
            TrackVisualizationRenderer.saveLosslessPng(masterMaskImg, new File(reportContext.exportDir, "master_mask_overlay.png"));

            report.println("<div class='image-container'>");
            report.println("<div><a href='master_stack.png' target='_blank'><img src='master_stack.png' style='max-width: 400px;' alt='Master Stack' /></a><br/><center><small>Deep Median Stack</small></center></div>");
            report.println("<div><a href='master_mask_overlay.png' target='_blank'><img src='master_mask_overlay.png' style='max-width: 400px;' alt='Mask Overlay' /></a><br/><center><small>Binary Footprint Mask (Red)</small></center></div>");
            report.println("</div>");
        } else {
            report.println("<div class='astro-note'>Preview images were not produced for this run, so the master shield and veto-mask links are omitted.</div>");
        }
        report.println("</div>");
    }

    private static void writeDriftDiagnostics(PrintWriter report,
                                              DetectionReportContext reportContext,
                                              List<SourceExtractor.Pixel> driftPoints) throws IOException {
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
        report.println("<div class='image-container'>");
        report.println("<div><a href='dither_drift_map.png' target='_blank'><img src='dither_drift_map.png' style='max-width: 300px;' alt='Drift Trajectory' /></a><br/><center><small>Drift Trajectory Map (Blue = Start, Red = End)</small></center></div>");
        report.println("<div><a href='" + cornerGifFile + "' target='_blank'><img src='" + cornerGifFile + "' style='max-width: 300px;' alt='Corners Sampled Time-Lapse' /></a><br/><center><small>4-Corners Sampled Time-Lapse</small></center></div>");
        report.println("<div style='min-width: 200px; flex-grow: 1;'><div class='scroll-box' style='max-height: 325px; border-color: #333; padding: 10px;'>");
        report.println("<div class='config-grid' style='grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));'>");
        for (int i = 0; i < driftPoints.size(); i++) {
            SourceExtractor.Pixel point = driftPoints.get(i);
            report.println("<div class='config-item'><span style='color: #aaa;'>Frame " + (i + 1) + "</span> <span class='val'>" + DetectionReportGenerator.escapeHtml(DetectionReportGenerator.formatPixelCoordinateOnly(point.x, point.y)) + "</span></div>");
        }
        report.println("</div></div></div>");
        report.println("</div>");
        report.println("</div>");
    }

    private static void writeExtractionStatistics(PrintWriter report, PipelineTelemetry pipelineTelemetry) {
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Frame Extraction Statistics</h2>");
        report.println("<p class='compact-note'>Detailed per-frame extraction diagnostics are shown in a scrollable compact table to keep long sequences readable without removing any rows.</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Frame Index</th><th>Filename</th><th>Objects Extracted</th><th>Bg Median</th><th>Bg Sigma</th><th>Seed Threshold</th><th>Grow Threshold</th></tr></thead><tbody>");
        for (PipelineTelemetry.FrameExtractionStat stat : pipelineTelemetry.frameExtractionStats) {
            report.println("<tr><td>" + (stat.frameIndex + 1) + "</td>");
            report.println("<td>" + DetectionReportGenerator.escapeHtml(stat.filename) + "</td>");
            report.println("<td>" + stat.objectCount + "</td>");
            report.println("<td>" + String.format(Locale.US, "%.2f", stat.bgMedian) + "</td>");
            report.println("<td>" + String.format(Locale.US, "%.2f", stat.bgSigma) + "</td>");
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
        report.println("<h2>Phase 3: Stationary Star Purification</h2>");
        report.println("<p class='compact-note'>Master-mask purification removes stationary point-like residues and same-mask stationary streaks before the moving-object linker runs. The per-frame breakdown is shown below in a scrollable compact table.</p>");
        if (linkerTelemetry != null) {
            report.println("<div class='flex-container' style='margin-bottom: 10px;'>");
            report.println("<div class='metric-box compact'><span class='metric-value'>" + linkerTelemetry.totalStationaryStarsPurged + "</span><span class='metric-label'>Stationary Stars Purged</span></div>");
            report.println("<div class='metric-box compact'><span class='metric-value'>" + linkerTelemetry.totalStationaryStreaksPurged + "</span><span class='metric-label'>Stationary Streaks Purged</span></div>");
            report.println("</div>");
            report.println("<div class='scroll-box compact-table-box'>");
            report.println("<table><thead><tr><th>Frame Index</th><th>Filename</th><th>Initial Point Sources</th><th>Stars Purged</th><th>Surviving Transients</th></tr></thead><tbody>");
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
            report.println("<div class='astro-note'>Stationary-star purification telemetry was not available for this run, so the detailed Phase 3 breakdown was skipped.</div>");
        }
        report.println("</div>");
    }

    private static void writeTrackLinkingDiagnostics(PrintWriter report,
                                                     TrackerTelemetry linkerTelemetry,
                                                     DetectionReportSummary summary) {
        report.println("<div class='panel compact-diagnostics-panel'>");
        report.println("<h2>Track Linking Diagnostics</h2>");
        report.println("<p class='compact-note'>Compact scrollable view with sticky headers for the track-link rejection phases and final acceptance summary.</p>");
        report.println("<div class='scroll-box compact-table-box'>");
        report.println("<table><thead><tr><th>Filter Phase</th><th>Rejection Reason</th><th>Points Rejected</th></tr></thead><tbody>");
        report.println("<tr><td>0. Single Streak</td><td>Binary-Star-Like Shape Veto</td><td>" + linkerTelemetry.rejectedBinaryStarStreakShape + "</td></tr>");
        report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Non-Positive Time Delta</td><td>" + linkerTelemetry.countBaselineNonPositiveDelta + "</td></tr>");
        report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Stationary / Jitter</td><td>" + linkerTelemetry.countBaselineJitter + "</td></tr>");
        report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countBaselineJump + "</td></tr>");
        report.println("<tr><td>1. Baseline (p1 &rarr; p2)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countBaselineSize + "</td></tr>");
        report.println("<tr><td>2. Track Search (p3)</td><td>Non-Positive Time Delta</td><td>" + linkerTelemetry.countP3NonPositiveDelta + "</td></tr>");
        report.println("<tr><td>2. Track Search (p3)</td><td>Velocity Mismatch</td><td>" + linkerTelemetry.countP3VelocityMismatch + "</td></tr>");
        report.println("<tr><td>2. Track Search (p3)</td><td>Off Predicted Trajectory Line</td><td>" + linkerTelemetry.countP3NotLine + "</td></tr>");
        report.println("<tr><td>2. Track Search (p3)</td><td>Wrong Direction / Angle</td><td>" + linkerTelemetry.countP3WrongDirection + "</td></tr>");
        report.println("<tr><td>2. Track Search (p3)</td><td>Exceeded Max Jump Velocity</td><td>" + linkerTelemetry.countP3Jump + "</td></tr>");
        report.println("<tr><td>2. Track Search (p3)</td><td>Morphological Size Mismatch</td><td>" + linkerTelemetry.countP3Size + "</td></tr>");
        report.println("<tr><td>3. Final Track</td><td>Insufficient Track Length</td><td>" + linkerTelemetry.countTrackTooShort + "</td></tr>");
        report.println("<tr><td>3. Final Track</td><td>Erratic Kinematic Rhythm</td><td>" + linkerTelemetry.countTrackErraticRhythm + "</td></tr>");
        report.println("<tr><td>3. Final Track</td><td>Duplicate Track (Ignored)</td><td>" + linkerTelemetry.countTrackDuplicate + "</td></tr>");
        report.println("</tbody></table>");
        report.println("</div>");
        report.println("<p class='astro-note' style='margin-top: 12px;'>");
        report.println("Confirmed phase outputs: <strong>" + linkerTelemetry.streakTracksFound + "</strong> accepted streak tracks, <strong>" + linkerTelemetry.pointTracksFound + "</strong> accepted point tracks, and <strong>" + summary.anomalyCount + "</strong> rescued anomalies.");
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
