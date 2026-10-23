package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CameraFixedRejector;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverSummaryTelemetry;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Inputs and pipeline outputs shared across a single report export, with its report model and cached catalogue.
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
    final List<DetectedObject> slowMoverCandidates;
    final SlowMoverSummaryTelemetry slowMoverTelemetry;
    final List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates;
    final List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters;
    final List<AnomalyDetection> anomalies;
    final List<Track> singleStreaks;
    final List<Track> streakTracks;
    final List<Track> movingTargets;
    ReportData data;
    /** Where every kept frame holds image data; set after construction, null when not measured. */
    DataArea dataArea;
    /** One sentence about the data area for the reader, empty when not measured. */
    String dataAreaText = "";
    /** Detections set aside before tracking because they stay at one camera position; empty when left out. */
    List<CameraFixedRejector.Group> cameraFixedGroups = Collections.emptyList();
    /** Where the slow sections report their items; set after construction. */
    ReportProgress progress = ReportProgress.NONE;
    private SessionCatalogue catalogue;
    ReportRunContext runContext;
    private VariableStarPresentation variablePresentation;

    VariableStarPresentation variablePresentation(io.github.ppissias.jtransient.photometry.VariableStarAnalysis analysis) {
        if (variablePresentation == null) variablePresentation = new VariableStarPresentation(this, analysis);
        return variablePresentation;
    }

    SessionCatalogue catalogue() {
        if (runContext != null) return runContext.catalogue();
        if (catalogue == null) catalogue = SessionCatalogue.load(this);
        return catalogue;
    }

    /** Returns the captured tuning limit; isolated section callers use deterministic built-in defaults. */
    int autoTuneMaxCandidateFrames() {
        return runContext == null ? SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES
                : runContext.options().autoTuneMaxCandidateFrames();
    }

    List<Integer> sampleFrames(java.util.Set<Integer> mandatory, int limit) {
        return data != null ? data.sample(mandatory, limit)
                : DetectionReportGenerator.getRepresentativeSequence(rawFrames.size(), mandatory, limit);
    }

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
                           List<DetectedObject> slowMoverCandidates,
                           SlowMoverSummaryTelemetry slowMoverTelemetry,
                           List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates,
                           List<ResidualTransientAnalysis.LocalActivityCluster> localActivityClusters,
                           List<AnomalyDetection> anomalies,
                           List<Track> singleStreaks,
                           List<Track> streakTracks,
                           List<Track> movingTargets) {
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
        this.movingTargets = movingTargets != null ? movingTargets : Collections.emptyList();
    }
}
