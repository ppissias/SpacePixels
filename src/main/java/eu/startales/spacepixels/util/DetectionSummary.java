/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import java.util.Objects;

/**
 * The counts of a finished detection, which a {@link DetectionSafetyPrompt} sees before the report is written, and
 * the report-only choice of which optional groups to write. The counts never change; only that choice does.
 */
public final class DetectionSummary {
    public final int totalDetections;
    public final int singleStreaks;
    public final int streakTracks;
    public final int movingTargets;
    public final int anomalies;
    public final int slowMoverCandidates;
    public final int localRescueCandidates;
    public final int localActivityClusters;
    /** Compact sources that share one displacement. Included in {@link #totalDetections}. */
    public final int commonMotionGroups;
    /** Detections that stay at one camera position (hot pixels, dust). Included in {@link #totalDetections}. */
    public final int cameraFixedGroups;
    /** Leftover detections. Shown in the summary, and not added to {@link #totalDetections}. */
    public final int unclassifiedDetections;
    /** Photometry verdict ("READY", "LIMITED", "NOT_READY") or null when variable-star detection did not run. */
    public final String photometryVerdict;
    public final int variableStarCandidates;
    public final int possibleVariableStars;
    /** Groups to write. The warning dialog replaces this when the user leaves some out. */
    private ReportInclusion reportInclusion = ReportInclusion.all();

    /** Captures measured counts; report inclusion starts with every optional group enabled. */
    public DetectionSummary(int totalDetections,
                     int singleStreaks,
                     int streakTracks,
                     int movingTargets,
                     int anomalies,
                     int slowMoverCandidates,
                     int localRescueCandidates,
                     int localActivityClusters,
                     String photometryVerdict,
                     int variableStarCandidates,
                     int possibleVariableStars,
                     int commonMotionGroups,
                     int unclassifiedDetections,
                     int cameraFixedGroups) {
        this.totalDetections = totalDetections;
        this.singleStreaks = singleStreaks;
        this.streakTracks = streakTracks;
        this.movingTargets = movingTargets;
        this.anomalies = anomalies;
        this.slowMoverCandidates = slowMoverCandidates;
        this.localRescueCandidates = localRescueCandidates;
        this.localActivityClusters = localActivityClusters;
        this.photometryVerdict = photometryVerdict;
        this.variableStarCandidates = variableStarCandidates;
        this.possibleVariableStars = possibleVariableStars;
        this.commonMotionGroups = commonMotionGroups;
        this.unclassifiedDetections = unclassifiedDetections;
        this.cameraFixedGroups = cameraFixedGroups;
    }

    /** Which optional groups this report should contain. All of them, unless the warning dialog said otherwise. */
    public ReportInclusion reportInclusion() {
        return reportInclusion;
    }

    /** Replaces report-only inclusion choices without mutating detection settings or measured results. */
    public void includeInReport(ReportInclusion inclusion) {
        reportInclusion = Objects.requireNonNull(inclusion);
    }

    /** A checkbox is offered only for a group the detection ran and that found something. */
    public static boolean offerInReport(boolean featureEnabled, int count) {
        return featureEnabled && count > 0;
    }

    /** Describes the largest classified group, preserving the existing tie order. */
    public String largestGroup() {
        String[] names = {"moving objects", "single-frame anomalies", "slow-mover candidates",
                "residual analysis", "common-motion groups", "camera-fixed groups"};
        int[] counts = {movingTargets + streakTracks + singleStreaks, anomalies,
                slowMoverCandidates, localRescueCandidates + localActivityClusters, commonMotionGroups,
                cameraFixedGroups};
        int best = 0;
        for (int i = 1; i < counts.length; i++) {
            if (counts[i] > counts[best]) {
                best = i;
            }
        }
        return names[best] + " (" + counts[best] + ")";
    }
}
