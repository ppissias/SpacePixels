package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverSummaryTelemetry;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Immutable bundle of inputs, intermediate products, and pipeline outputs shared across a single report export.
 */
final class DetectionReportContext {

    final ExportVisualizationSettings settings;
    final File exportDir;
    final List<short[][]> rawFrames;
    final FitsFileInformation[] fitsFiles;
    final DetectionConfig config;
    final DetectionReportAstrometry.Context astrometryContext;
    final short[][] masterStackData;
    final short[][] maximumStackData;
    final boolean[][] masterVetoMask;
    final short[][] slowMoverStackData;
    final boolean[][] slowMoverMedianVetoMask;
    final List<SlowMoverCandidateResult> slowMoverCandidateResults;
    final List<SourceExtractor.DetectedObject> slowMoverCandidates;
    final SlowMoverSummaryTelemetry slowMoverTelemetry;
    final List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates;
    final List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters;
    final List<TrackLinker.AnomalyDetection> anomalies;
    final List<TrackLinker.Track> singleStreaks;
    final List<TrackLinker.Track> streakTracks;
    final List<TrackLinker.Track> suspectedStreakTracks;
    final List<TrackLinker.Track> movingTargets;

    DetectionReportContext(ExportVisualizationSettings settings,
                           File exportDir,
                           List<short[][]> rawFrames,
                           FitsFileInformation[] fitsFiles,
                           DetectionConfig config,
                           DetectionReportAstrometry.Context astrometryContext,
                           short[][] masterStackData,
                           short[][] maximumStackData,
                           boolean[][] masterVetoMask,
                           short[][] slowMoverStackData,
                           boolean[][] slowMoverMedianVetoMask,
                           List<SlowMoverCandidateResult> slowMoverCandidateResults,
                           List<SourceExtractor.DetectedObject> slowMoverCandidates,
                           SlowMoverSummaryTelemetry slowMoverTelemetry,
                           List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates,
                           List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters,
                           List<TrackLinker.AnomalyDetection> anomalies,
                           List<TrackLinker.Track> singleStreaks,
                           List<TrackLinker.Track> streakTracks,
                           List<TrackLinker.Track> suspectedStreakTracks,
                           List<TrackLinker.Track> movingTargets) {
        this.settings = settings;
        this.exportDir = exportDir;
        this.rawFrames = rawFrames != null ? rawFrames : Collections.emptyList();
        this.fitsFiles = fitsFiles;
        this.config = config;
        this.astrometryContext = astrometryContext;
        this.masterStackData = masterStackData;
        this.maximumStackData = maximumStackData;
        this.masterVetoMask = masterVetoMask;
        this.slowMoverStackData = slowMoverStackData;
        this.slowMoverMedianVetoMask = slowMoverMedianVetoMask;
        this.slowMoverCandidateResults = slowMoverCandidateResults != null ? slowMoverCandidateResults : Collections.emptyList();
        this.slowMoverCandidates = slowMoverCandidates != null ? slowMoverCandidates : Collections.emptyList();
        this.slowMoverTelemetry = slowMoverTelemetry;
        this.localRescueCandidates = localRescueCandidates != null ? localRescueCandidates : Collections.emptyList();
        this.localActivityClusters = localActivityClusters != null ? localActivityClusters : Collections.emptyList();
        this.anomalies = anomalies != null ? anomalies : Collections.emptyList();
        this.singleStreaks = singleStreaks != null ? singleStreaks : Collections.emptyList();
        this.streakTracks = streakTracks != null ? streakTracks : Collections.emptyList();
        this.suspectedStreakTracks = suspectedStreakTracks != null ? suspectedStreakTracks : Collections.emptyList();
        this.movingTargets = movingTargets != null ? movingTargets : Collections.emptyList();
    }
}
