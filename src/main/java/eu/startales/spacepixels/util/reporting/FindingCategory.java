package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import java.awt.Color;

/** Authoritative report-category identities and presentation rules; legacy labels, IDs and export options stay stable. */
public enum FindingCategory {
    MOVING_TARGET("T", "Moving target", "Moving-object tracks", "Moving object tracks", new Color(77, 166, 255), false, false),
    STREAK_TRACK("ST", "Streak track", "Streak tracks", "Confirmed streak tracks", new Color(255, 204, 51), false, false),
    SINGLE_STREAK("S", "Single-frame streak", "Single streaks", "Single streaks", new Color(255, 153, 51), false, false),
    ANOMALY("A", "Single-frame anomaly", "Single-frame anomalies", "Single-frame anomalies", new Color(255, 51, 255), true, false),
    SLOW_MOVER("DS", "Slow-mover candidate", "Slow-mover candidates", "Maximum-stack shape candidates", new Color(170, 255, 110), true, false),
    LOCAL_RESCUE("LR", "Local rescue", "Local rescue candidates", "Local rescue candidates", new Color(64, 224, 208), true, false),
    LOCAL_ACTIVITY("LC", "Local activity", "Local activity clusters", "Local activity clusters", new Color(150, 120, 255), true, true),
    VARIABLE_STAR("V", "Variable-star candidate", "Variable stars", "Variable-star candidates", new Color(255, 90, 210), true, false),
    CAMERA_FIXED("FC", "Camera-fixed", "Camera-fixed groups", "Camera-fixed groups", Color.GRAY, true, true),
    COMMON_MOTION("CM", "Common motion", "Common-motion groups", "Common-motion groups", Color.GRAY, true, true);

    private final String prefix;
    private final String label;
    private final String summaryLabel;
    private final String mapLabel;
    private final Color color;
    private final boolean optional;
    private final boolean centroid;

    /** Captures compatibility labels and immutable presentation rules, independently of any detection result. */
    FindingCategory(String prefix, String label, String summaryLabel, String mapLabel, Color color,
                    boolean optional, boolean centroid) {
        this.prefix = prefix;
        this.label = label;
        this.summaryLabel = summaryLabel;
        this.mapLabel = mapLabel;
        this.color = color;
        this.optional = optional;
        this.centroid = centroid;
    }

    /** Returns the stable report-local prefix, also used by interactive map category controls. */
    public String prefix() { return prefix; }

    /** Returns the singular category text preserved in report-data JSON. */
    public String label() { return label; }

    /** Returns the existing report-confirmation row title. */
    public String summaryLabel() { return summaryLabel; }

    /** Returns the existing explanatory legend title, which can differ from the short JSON category. */
    public String mapLabel() { return mapLabel; }

    /** Returns the shared map marker/legend color; artifact groups retain their separate sections. */
    public Color color() { return color; }

    /** Whether report inclusion can omit this measured category; core track categories are always included. */
    public boolean optional() { return optional; }

    /** Whether representative JSON coordinates use the group centroid rather than the first measured point. */
    public boolean usesCentroid() { return centroid; }

    /** Whether this category belongs on the combined detection map rather than only an artifact section. */
    public boolean onDetectionMap() { return this != COMMON_MOTION && this != CAMERA_FIXED; }

    /** Creates a report-local identity using one-based category numbering. */
    public String id(int number) {
        if (number < 1) throw new IllegalArgumentException("Finding numbers must be positive.");
        return prefix + number;
    }

    /** Returns the detailed-card anchor; variables without a detailed card retain their explicit table fallback. */
    public String anchor(int number) {
        String findingId = id(number);
        return this == VARIABLE_STAR ? "variable-v" + number : "finding-" + findingId.toLowerCase(java.util.Locale.ROOT);
    }

    /** Resolves an existing finding ID without confusing the S/ST prefixes or accepting malformed numeric suffixes. */
    public static FindingCategory fromId(String id) {
        if (id != null) {
            for (FindingCategory category : values()) {
                if (id.startsWith(category.prefix) && id.substring(category.prefix.length()).matches("[1-9][0-9]*")) return category;
            }
        }
        throw new IllegalArgumentException("Unknown finding ID: " + id);
    }

    /** Applies existing report-only inclusion switches; this never changes engine settings or measured counts. */
    public boolean included(ReportInclusion inclusion) {
        return switch (this) {
            case MOVING_TARGET, STREAK_TRACK, SINGLE_STREAK -> true;
            case ANOMALY -> inclusion.anomalies;
            case SLOW_MOVER -> inclusion.slowMovers;
            case LOCAL_RESCUE -> inclusion.localRescue;
            case LOCAL_ACTIVITY -> inclusion.localActivity;
            case VARIABLE_STAR -> inclusion.variableStars;
            case CAMERA_FIXED -> inclusion.cameraFixed;
            case COMMON_MOTION -> inclusion.commonMotion;
        };
    }

    /** Resolves measured summary counts, keeping both variable confidence tiers represented. */
    public int count(ImageProcessing.DetectionSummary summary) {
        return switch (this) {
            case MOVING_TARGET -> summary.movingTargets;
            case STREAK_TRACK -> summary.streakTracks;
            case SINGLE_STREAK -> summary.singleStreaks;
            case ANOMALY -> summary.anomalies;
            case SLOW_MOVER -> summary.slowMoverCandidates;
            case LOCAL_RESCUE -> summary.localRescueCandidates;
            case LOCAL_ACTIVITY -> summary.localActivityClusters;
            case VARIABLE_STAR -> summary.variableStarCandidates + summary.possibleVariableStars;
            case CAMERA_FIXED -> summary.cameraFixedGroups;
            case COMMON_MOTION -> summary.commonMotionGroups;
        };
    }

    /** Preserves summary-row visibility for enabled analyses, including photometry's separate run verdict. */
    public boolean computed(DetectionConfig config, ImageProcessing.DetectionSummary summary) {
        return switch (this) {
            case MOVING_TARGET, STREAK_TRACK, SINGLE_STREAK -> true;
            case ANOMALY -> config == null || config.enableAnomalyRescue;
            case SLOW_MOVER -> config == null || config.enableSlowMoverDetection;
            case LOCAL_RESCUE -> config == null || config.enableResidualTransientAnalysis && config.enableLocalRescueCandidates;
            case LOCAL_ACTIVITY -> config == null || config.enableResidualTransientAnalysis && config.enableLocalActivityClusters;
            case VARIABLE_STAR -> config != null && config.enableVariableStarDetection
                    && summary.photometryVerdict != null && !"NOT_RUN".equals(summary.photometryVerdict);
            case CAMERA_FIXED -> config == null || config.enableCameraFixedRejection;
            case COMMON_MOTION -> config == null || config.enableCommonMotionRejection;
        };
    }
}
