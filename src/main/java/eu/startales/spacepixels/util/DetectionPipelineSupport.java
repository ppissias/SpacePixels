/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.PipelineResult;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Shared runtime helpers used by both standard and iterative pipeline orchestration services.
 */
final class DetectionPipelineSupport {

    static final int MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS = 3;

    private static final DateTimeFormatter TRACE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'").withZone(ZoneOffset.UTC);

    private DetectionPipelineSupport() {
    }

    static void logPipelineFrameTimingPayload(String stageLabel,
                                              List<ImageFrame> frames,
                                              FitsFileInformation[] filesInfo) {
        if (frames == null) {
            System.out.println("\n--- " + stageLabel + " JTransient Frame Timing Payload ---");
            System.out.println("No frames prepared for JTransient.");
            return;
        }

        int validTimestamps = 0;
        System.out.println("\n--- " + stageLabel + " JTransient Frame Timing Payload ---");
        for (int i = 0; i < frames.size(); i++) {
            ImageFrame frame = frames.get(i);
            if (frame.timestamp > 0L) {
                validTimestamps++;
            }

            FitsFileInformation fileInfo = null;
            if (filesInfo != null && frame.sequenceIndex >= 0 && frame.sequenceIndex < filesInfo.length) {
                fileInfo = filesInfo[frame.sequenceIndex];
            } else if (filesInfo != null && i >= 0 && i < filesInfo.length) {
                fileInfo = filesInfo[i];
            }

            String diagnostics = fileInfo != null
                    ? fileInfo.getObservationTimestampDiagnostics()
                    : "No matching FitsFileInformation entry was found for this frame.";

            System.out.println(String.format(
                    Locale.US,
                    "[%03d] sequenceIndex=%d | file='%s' | payloadTimestamp=%d | payloadUtc='%s' | exposureMs=%d | %s",
                    i,
                    frame.sequenceIndex,
                    frame.filename,
                    frame.timestamp,
                    formatTraceTimestamp(frame.timestamp),
                    frame.exposureDuration,
                    diagnostics));
        }
        System.out.println(String.format(
                Locale.US,
                "JTransient payload summary: %d/%d frames carry a usable timestamp.",
                validTimestamps,
                frames.size()));
    }

    static DetectionConfig createEffectiveDetectionConfig(DetectionConfig baseConfig, int frameCount) {
        if (baseConfig == null) {
            return null;
        }

        DetectionConfig effectiveConfig = baseConfig.clone();
        if (!effectiveConfig.enableSlowMoverDetection) {
            return effectiveConfig;
        }

        double adjustedFraction = clampSlowMoverStackMiddleFraction(
                effectiveConfig.slowMoverStackMiddleFraction,
                frameCount);
        if (Double.compare(adjustedFraction, effectiveConfig.slowMoverStackMiddleFraction) != 0) {
            System.out.printf(
                    Locale.US,
                    "Adjusting slowMoverStackMiddleFraction from %.4f to %.4f for %d frames so the slow-mover stack stays one frame below the maximum stack.%n",
                    effectiveConfig.slowMoverStackMiddleFraction,
                    adjustedFraction,
                    frameCount);
            effectiveConfig.slowMoverStackMiddleFraction = adjustedFraction;
        }

        return effectiveConfig;
    }

    static double clampSlowMoverStackMiddleFraction(double requestedFraction, int frameCount) {
        double boundedFraction = Math.max(0.0, Math.min(1.0, requestedFraction));
        if (frameCount <= 1) {
            return boundedFraction;
        }

        int maximumAllowedIndex = frameCount - 2;
        if (computeSlowMoverStackOrderIndex(frameCount, boundedFraction) <= maximumAllowedIndex) {
            return boundedFraction;
        }

        int requestedRoundedWindow = (int) Math.round(frameCount * boundedFraction);
        int safeRoundedWindow = requestedRoundedWindow;

        while (safeRoundedWindow > 0
                && computeSlowMoverStackOrderIndex(frameCount, safeRoundedWindow / (double) frameCount) > maximumAllowedIndex) {
            safeRoundedWindow--;
        }

        return safeRoundedWindow / (double) frameCount;
    }

    static int computeSlowMoverStackOrderIndex(int frameCount, double fraction) {
        if (frameCount <= 0) {
            return -1;
        }

        double boundedFraction = Math.max(0.0, Math.min(1.0, fraction));
        int roundedWindow = (int) Math.round(frameCount * boundedFraction);
        int centerIndex = (frameCount - 1) / 2;
        return Math.min(frameCount - 1, centerIndex + (roundedWindow / 2));
    }

    static ImageProcessing.DetectionSummary summarizeDetections(PipelineResult result) {
        List<TrackLinker.Track> tracks = result.tracks != null ? result.tracks : Collections.emptyList();
        List<TrackLinker.AnomalyDetection> anomalies = result.anomalies != null ? result.anomalies : Collections.emptyList();
        int suspectedStreakTracks = 0;
        int singleStreaks = 0;
        int streakTracks = 0;
        int movingTargets = 0;

        for (TrackLinker.Track track : tracks) {
            if (track == null || track.points == null || track.points.isEmpty()) {
                continue;
            }

            if (track.isSuspectedStreakTrack) {
                suspectedStreakTracks++;
                continue;
            }

            if (track.points.size() == 1) {
                singleStreaks++;
            } else if (track.isStreakTrack) {
                streakTracks++;
            } else {
                movingTargets++;
            }
        }

        SlowMoverAnalysis slowMoverAnalysis = result.slowMoverAnalysis != null
                ? result.slowMoverAnalysis
                : SlowMoverAnalysis.empty();
        int slowMoverCandidates = !slowMoverAnalysis.candidates.isEmpty()
                ? slowMoverAnalysis.candidates.size()
                : (result.slowMoverCandidates == null ? 0 : result.slowMoverCandidates.size());
        ResidualTransientAnalysis residualAnalysis = result.residualTransientAnalysis != null
                ? result.residualTransientAnalysis
                : ResidualTransientAnalysis.empty();
        int localRescueCandidates = residualAnalysis.localRescueCandidates.size();
        int localActivityClusters = residualAnalysis.localActivityClusters.size();
        int anomalyCount = anomalies.size();

        return new ImageProcessing.DetectionSummary(
                singleStreaks + streakTracks + movingTargets + anomalyCount + suspectedStreakTracks
                        + slowMoverCandidates + localRescueCandidates + localActivityClusters,
                singleStreaks,
                streakTracks,
                movingTargets,
                anomalyCount,
                suspectedStreakTracks,
                slowMoverCandidates,
                localRescueCandidates,
                localActivityClusters);
    }

    static PipelineResult suppressLatePhaseOutputsWhenTooFewFramesRemain(PipelineResult result) {
        if (result == null || result.telemetry == null) {
            return result;
        }

        int keptFrames = result.telemetry.totalFramesKept;
        if (keptFrames >= MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS) {
            return result;
        }

        System.out.println(
                "Only " + keptFrames
                        + " frames remained after quality control. Suppressing downstream multi-frame report outputs.");

        result.telemetry.totalTracksFound = 0;
        result.telemetry.totalAnomaliesFound = 0;
        result.telemetry.totalSuspectedStreakTracksFound = 0;
        result.telemetry.trackerTelemetry = null;
        result.telemetry.slowMoverTelemetry = null;

        return new PipelineResult(
                Collections.emptyList(),
                result.telemetry,
                result.masterStackData,
                result.masterStars == null ? Collections.emptyList() : result.masterStars,
                SlowMoverAnalysis.empty(),
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                result.allTransients == null ? Collections.emptyList() : result.allTransients,
                result.unclassifiedTransients == null ? Collections.emptyList() : result.unclassifiedTransients,
                ResidualTransientAnalysis.empty(),
                result.masterVetoMask,
                result.driftPoints == null ? Collections.emptyList() : result.driftPoints,
                result.maximumStackData);
    }

    private static String formatTraceTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "N/A";
        }
        return TRACE_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis));
    }
}
