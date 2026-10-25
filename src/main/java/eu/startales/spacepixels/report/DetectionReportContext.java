package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.CameraFixedRejector;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.core.SlowMoverSummaryTelemetry;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Complete, run-owned report inputs and identities, shared by its section writers without live lookups. */
final class DetectionReportContext {
    final VisualizationSettings settings;
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
    final TrackFindingIndex trackFindings;
    final ReportData data;
    final DataArea dataArea;
    final String dataAreaText;
    final List<CameraFixedRejector.Group> cameraFixedGroups;
    final ReportProgress progress;
    final ReportRunContext runContext;
    final PipelineTelemetry pipelineTelemetry;
    final List<DetectedObject> masterStars;
    final List<List<DetectedObject>> allTransients;
    final List<List<DetectedObject>> unclassifiedTransients;
    final ResidualTransientAnalysis residualTransientAnalysis;
    final List<ResidualTransientAnalysis.CommonMotionGroup> commonMotionGroups;
    final List<Track> localRescueTracks;
    final DetectionReportSummary summary;
    private VariableStarPresentation variablePresentation;

    /** Builds every shared report input and model before a section can use the context. */
    static DetectionReportContext create(ReportRequest request, File exportDir, ReportProgress progress) {
        return new DetectionReportContext(request, exportDir, progress);
    }

    /** Normalizes optional engine outputs while retaining the captured run's settings, WCS and catalogue. */
    private DetectionReportContext(ReportRequest request, File exportDir, ReportProgress progress) {
        PipelineResult result = request.result();
        ReportInclusion inclusion = request.inclusion();
        this.settings = request.runContext().options().visualization();
        this.exportDir = exportDir;
        this.rawFrames = request.rawFrames();
        this.fitsFiles = request.fitsFiles();
        this.config = request.config();
        this.runContext = request.runContext();
        this.astrometryContext = runContext.astrometry();
        this.progress = java.util.Objects.requireNonNull(progress, "progress");
        // Extract variables from the PipelineResult and normalize newer track categories for rendering.
        List<Track> tracks = result.tracks != null ? result.tracks : new ArrayList<>();
        this.anomalies = FindingCategory.ANOMALY.included(inclusion)
                ? sortAnomaliesForDisplay(result.anomalies)
                : new ArrayList<>();
        this.pipelineTelemetry = result.telemetry;
        this.masterStackData = result.masterStackData;
        this.maximumStackData = result.maximumStackData;
        this.masterVetoMask = result.masterVetoMask;
        this.masterStars = result.masterStars;
        SlowMoverAnalysis slowMoverAnalysis = result.slowMoverAnalysis != null
                ? result.slowMoverAnalysis
                : SlowMoverAnalysis.empty();
        this.slowMoverStackData = slowMoverAnalysis.maximumStackData != null
                ? slowMoverAnalysis.maximumStackData
                : result.maximumStackData;
        this.slowMoverMedianVetoMask = slowMoverAnalysis.medianMask != null
                ? slowMoverAnalysis.medianMask
                : result.slowMoverMedianVetoMask;
        this.slowMoverCandidateResults = !FindingCategory.SLOW_MOVER.included(inclusion)
                ? java.util.Collections.emptyList()
                : slowMoverAnalysis.candidates != null
                ? slowMoverAnalysis.candidates
                : java.util.Collections.emptyList();
        this.slowMoverCandidates = new ArrayList<>();
        if (FindingCategory.SLOW_MOVER.included(inclusion) && !slowMoverCandidateResults.isEmpty()) {
            for (SlowMoverCandidateResult candidateResult : slowMoverCandidateResults) {
                if (candidateResult != null && candidateResult.object != null) {
                    slowMoverCandidates.add(candidateResult.object);
                }
            }
        } else if (FindingCategory.SLOW_MOVER.included(inclusion) && result.slowMoverCandidates != null) {
            slowMoverCandidates.addAll(result.slowMoverCandidates);
        }
        this.slowMoverTelemetry = !FindingCategory.SLOW_MOVER.included(inclusion) ? null
                : hasMeaningfulSlowMoverTelemetry(slowMoverAnalysis.telemetry)
                ? slowMoverAnalysis.telemetry
                : (pipelineTelemetry != null && pipelineTelemetry.slowMoverTelemetry != null
                ? new SlowMoverSummaryTelemetry(pipelineTelemetry.slowMoverTelemetry)
                : null);
        this.allTransients = result.allTransients;
        this.unclassifiedTransients = result.unclassifiedTransients;
        this.residualTransientAnalysis = result.residualTransientAnalysis != null
                ? result.residualTransientAnalysis
                : ResidualTransientAnalysis.empty();
        this.localRescueCandidates = FindingCategory.LOCAL_RESCUE.included(inclusion)
                ? residualTransientAnalysis.localRescueCandidates
                : java.util.Collections.emptyList();
        this.localActivityClusters = FindingCategory.LOCAL_ACTIVITY.included(inclusion)
                ? residualTransientAnalysis.localActivityClusters
                : java.util.Collections.emptyList();
        this.commonMotionGroups = FindingCategory.COMMON_MOTION.included(inclusion)
                ? residualTransientAnalysis.commonMotionGroups
                : java.util.Collections.emptyList();

        this.trackFindings = TrackFindingIndex.of(tracks);
        this.singleStreaks = trackFindings.singleStreaks();
        this.streakTracks = trackFindings.streakTracks();
        this.movingTargets = trackFindings.movingTargets();
        this.localRescueTracks = new ArrayList<>();

        for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
            Track residualTrack = ResidualReviewSectionWriter.buildResidualTrack(candidate.points);
            if (residualTrack.points != null && !residualTrack.points.isEmpty()) {
                localRescueTracks.add(residualTrack);
            }
        }

        this.summary = buildDetectionReportSummary(result.tooFewFrames, inclusion);
        this.dataArea = result.dataArea;
        this.cameraFixedGroups = FindingCategory.CAMERA_FIXED.included(inclusion)
                ? result.cameraFixedGroups : Collections.emptyList();
        this.dataAreaText = DataAreaPresentation.describe(result.dataArea, pipelineTelemetry);
        this.data = ReportData.create(this, pipelineTelemetry, result.variableStarAnalysis, commonMotionGroups, inclusion);
    }

    /** Caches the presentation of this run's variable-star analysis for cards, maps and identities. */
    VariableStarPresentation variablePresentation(io.github.ppissias.jtransient.photometry.VariableStarAnalysis analysis) {
        if (variablePresentation == null) variablePresentation = new VariableStarPresentation(this, analysis);
        return variablePresentation;
    }

    /** Returns the catalogue, including its valid absence, resolved when the run was admitted. */
    SessionCatalogue catalogue() {
        return runContext.catalogue();
    }

    /** Returns the captured tuning limit without reading live settings. */
    int autoTuneMaxCandidateFrames() {
        return runContext.options().autoTuneMaxCandidateFrames();
    }

    /** Resolves a category ordinal through this report's prepared identities. */
    String findingId(FindingCategory category, int number) {
        return data.finding(category, number).id();
    }

    /** Uses the prepared anchor, including the variable-star table fallback when no detailed card exists. */
    String findingAnchor(FindingCategory category, int number) {
        return data.finding(category, number).anchor();
    }

    /** Selects representative frames using the report model's quality exclusions. */
    List<Integer> sampleFrames(java.util.Set<Integer> mandatory, int limit) {
        return data.sample(mandatory, limit);
    }

    /** Orders anomaly cards without changing engine order. */
    private static List<AnomalyDetection> sortAnomaliesForDisplay(List<AnomalyDetection> anomalies) {
        List<AnomalyDetection> ordered = new ArrayList<>();
        if (anomalies != null) {
            ordered.addAll(anomalies);
        }
        ordered.sort(Comparator
                .comparingInt(DetectionReportContext::getAnomalyDisplayFrameIndex)
                .thenComparingInt(DetectionReportContext::getAnomalyDisplayOrder)
                .thenComparing(anomaly -> anomaly != null && anomaly.object != null && anomaly.object.sourceFilename != null
                        ? anomaly.object.sourceFilename
                        : "")
                .thenComparingDouble(anomaly -> anomaly != null && anomaly.object != null
                        ? anomaly.object.x
                        : Double.POSITIVE_INFINITY)
                .thenComparingDouble(anomaly -> anomaly != null && anomaly.object != null
                        ? anomaly.object.y
                        : Double.POSITIVE_INFINITY));
        return ordered;
    }

    /** Counts one anomaly category while tolerating absent entries. */
    private static int countAnomaliesOfType(List<AnomalyDetection> anomalies, AnomalyType type) {
        if (anomalies == null || anomalies.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (AnomalyDetection anomaly : anomalies) {
            if (anomaly != null && anomaly.type == type) {
                count++;
            }
        }
        return count;
    }

    /** Summarizes included findings alongside engine diagnostic totals. */
    private DetectionReportSummary buildDetectionReportSummary(boolean tooFewFrames, ReportInclusion inclusion) {
        int slowMoverCandidateCount = slowMoverCandidates == null ? 0 : slowMoverCandidates.size();
        int localRescueCandidateCount = localRescueCandidates.size();
        int localActivityClusterCount = localActivityClusters.size();
        int singleStreakCount = singleStreaks.size();
        int streakTrackCount = streakTracks.size();
        int movingTargetCount = movingTargets.size();
        int confirmedLinkedTrackCount = movingTargetCount + streakTrackCount;
        int returnedTrackCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalTracksFound
                : singleStreakCount + confirmedLinkedTrackCount;
        int masterStarCount = pipelineTelemetry != null
                ? pipelineTelemetry.totalMasterStarsIdentified
                : (masterStars == null ? 0 : masterStars.size());
        int masterMapObjectCount = masterStars == null ? 0 : masterStars.size();
        int anomalyCount = !FindingCategory.ANOMALY.included(inclusion) ? 0
                : pipelineTelemetry != null ? pipelineTelemetry.totalAnomaliesFound : anomalies.size();
        int peakSigmaAnomalyCount = countAnomaliesOfType(anomalies, AnomalyType.PEAK_SIGMA);
        int integratedSigmaAnomalyCount = countAnomaliesOfType(anomalies, AnomalyType.INTEGRATED_SIGMA);
        int otherAnomalyCount = Math.max(0, anomalyCount - peakSigmaAnomalyCount - integratedSigmaAnomalyCount);
        int unclassifiedTransientCount = countTransientDetections(unclassifiedTransients);

        return new DetectionReportSummary(
                slowMoverCandidateCount,
                localRescueCandidateCount,
                localActivityClusterCount,
                singleStreakCount,
                streakTrackCount,
                movingTargetCount,
                confirmedLinkedTrackCount,
                tooFewFrames,
                returnedTrackCount,
                masterStarCount,
                masterMapObjectCount,
                anomalyCount,
                peakSigmaAnomalyCount,
                integratedSigmaAnomalyCount,
                otherAnomalyCount,
                unclassifiedTransientCount
        );
    }

    private static int countTransientDetections(List<List<DetectedObject>> transientsPerFrame) {
        if (transientsPerFrame == null) {
            return 0;
        }
        int total = 0;
        for (List<DetectedObject> frameTransients : transientsPerFrame) {
            if (frameTransients != null) {
                total += frameTransients.size();
            }
        }
        return total;
    }

    /** Distinguishes populated slow-mover diagnostics from the engine's empty result. */
    private static boolean hasMeaningfulSlowMoverTelemetry(SlowMoverSummaryTelemetry telemetry) {
        if (telemetry == null) {
            return false;
        }
        return telemetry.rawCandidatesExtracted > 0
                || telemetry.evaluatedAgainstMedianMask > 0
                || telemetry.candidatesDetected > 0
                || telemetry.rejectedLowMedianSupport > 0
                || telemetry.rejectedHighMedianSupport > 0
                || telemetry.rejectedBelowMinPixels > 0
                || telemetry.rejectedBelowMinAxisRatio > 0
                || telemetry.rejectedAboveMaxAxisRatio > 0
                || telemetry.rejectedLowFillFactor > 0
                || telemetry.evaluatedAgainstFrames > 0
                || telemetry.frameEvidenceUnavailable > 0
                || telemetry.rejectedLowFrameSupport > 0
                || telemetry.rejectedHighStationaryLikelihood > 0
                || telemetry.minAxisRatioThreshold > 0.0
                || telemetry.minFrameSupportThreshold > 0.0
                || telemetry.maxStationaryLikelihoodThreshold > 0.0;
    }
    /** Keeps the established peak, integrated and other anomaly ordering. */
    private static int getAnomalyDisplayOrder(AnomalyDetection anomaly) {
        if (anomaly == null || anomaly.type == null) {
            return 2;
        }
        switch (anomaly.type) {
            case PEAK_SIGMA:
                return 0;
            case INTEGRATED_SIGMA:
                return 1;
            default:
                return 2;
        }
    }

    /** Places an anomaly at its source frame, with missing entries last. */
    private static int getAnomalyDisplayFrameIndex(AnomalyDetection anomaly) {
        if (anomaly == null || anomaly.object == null) {
            return Integer.MAX_VALUE;
        }
        return anomaly.object.sourceFrameIndex;
    }



}
