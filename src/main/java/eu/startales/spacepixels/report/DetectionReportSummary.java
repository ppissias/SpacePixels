package eu.startales.spacepixels.report;

/**
 * Precomputed aggregate counts and guardrail flags shared across report sections so the exporter
 * does not keep recomputing the same derived metrics inline.
 */
final class DetectionReportSummary {

    final int slowMoverCandidateCount;
    final int localRescueCandidateCount;
    final int localActivityClusterCount;
    final int singleStreakCount;
    final int streakTrackCount;
    final int movingTargetCount;
    final int confirmedLinkedTrackCount;
    /** The engine stopped after quality control because it kept too few frames ({@code PipelineResult.tooFewFrames}). */
    final boolean tooFewFrames;
    final int returnedTrackCount;
    final int masterStarCount;
    final int masterMapObjectCount;
    final int anomalyCount;
    final int peakSigmaAnomalyCount;
    final int integratedSigmaAnomalyCount;
    final int otherAnomalyCount;
    final int unclassifiedTransientCount;

    DetectionReportSummary(int slowMoverCandidateCount,
                           int localRescueCandidateCount,
                           int localActivityClusterCount,
                           int singleStreakCount,
                           int streakTrackCount,
                           int movingTargetCount,
                           int confirmedLinkedTrackCount,
                           boolean tooFewFrames,
                           int returnedTrackCount,
                           int masterStarCount,
                           int masterMapObjectCount,
                           int anomalyCount,
                           int peakSigmaAnomalyCount,
                           int integratedSigmaAnomalyCount,
                           int otherAnomalyCount,
                           int unclassifiedTransientCount) {
        this.slowMoverCandidateCount = slowMoverCandidateCount;
        this.localRescueCandidateCount = localRescueCandidateCount;
        this.localActivityClusterCount = localActivityClusterCount;
        this.singleStreakCount = singleStreakCount;
        this.streakTrackCount = streakTrackCount;
        this.movingTargetCount = movingTargetCount;
        this.confirmedLinkedTrackCount = confirmedLinkedTrackCount;
        this.tooFewFrames = tooFewFrames;
        this.returnedTrackCount = returnedTrackCount;
        this.masterStarCount = masterStarCount;
        this.masterMapObjectCount = masterMapObjectCount;
        this.anomalyCount = anomalyCount;
        this.peakSigmaAnomalyCount = peakSigmaAnomalyCount;
        this.integratedSigmaAnomalyCount = integratedSigmaAnomalyCount;
        this.otherAnomalyCount = otherAnomalyCount;
        this.unclassifiedTransientCount = unclassifiedTransientCount;
    }
}
