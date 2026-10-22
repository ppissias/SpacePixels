package eu.startales.spacepixels.config;

import java.util.List;

/** Typed application definitions for the existing Residual Analysis page; hidden engine fields stay untouched. */
public final class ResidualAnalysisSettings {
    public static final DetectionSetting<Boolean> ENABLE_RESIDUAL = new DetectionSetting<>(
            "enableResidualTransientAnalysis", "Enable Residual Transient Analysis", "",
            "Enables local rescue and activity clustering after common-motion filtering. Common-motion filtering has its own switch and still runs when residual analysis is off.",
            false, Boolean.class, config -> config.enableResidualTransientAnalysis,
            (config, value) -> config.enableResidualTransientAnalysis = value);
    public static final DetectionSetting<Boolean> ENABLE_RESCUE = new DetectionSetting<>(
            "enableLocalRescueCandidates", "Enable Local Rescue Candidates", "",
            "Runs the object-like rescue pass on leftover point detections to surface weak local motion or repeaters that did not become normal tracks.",
            false, Boolean.class, config -> config.enableLocalRescueCandidates,
            (config, value) -> config.enableLocalRescueCandidates = value);
    public static final DetectionSetting<Boolean> ENABLE_CLUSTERS = new DetectionSetting<>(
            "enableLocalActivityClusters", "Enable Local Activity Clusters", "",
            "After local rescue candidates are removed, groups the remaining leftover detections into broad same-area activity clusters for review. These are not confirmed objects. Their settings appear below when this is on.",
            true, Boolean.class, config -> config.enableLocalActivityClusters,
            (config, value) -> config.enableLocalActivityClusters = value);
    public static final DetectionSetting<Double> CLUSTER_RADIUS = new DetectionSetting<>(
            "localActivityClusterRadiusPixels", "Local Activity Cluster Radius (Pixels)", "pixels",
            "Spatial linkage radius used when grouping leftover detections into broad local activity clusters after rescue candidates are consumed.",
            true, Double.class, config -> config.localActivityClusterRadiusPixels,
            (config, value) -> config.localActivityClusterRadiusPixels = value);
    public static final DetectionSetting<Integer> CLUSTER_FRAMES = new DetectionSetting<>(
            "localActivityClusterMinFrames", "Local Activity Cluster Min Frames", "frames",
            "Minimum number of unique frames required before a broad local activity cluster is exported for review.",
            true, Integer.class, config -> config.localActivityClusterMinFrames,
            (config, value) -> config.localActivityClusterMinFrames = value);
    public static final DetectionSetting<Boolean> REJECT_COMMON_MOTION = new DetectionSetting<>(
            "enableCommonMotionRejection", "Reject Common Motion", "",
            "Before local rescue and activity clustering, sets aside separated compact sources that share one displacement on at least three frames. Matching motion suggests hot pixels or registration residuals; the groups remain visible in the report. Runs independently of the residual-analysis switch. Ambiguous associations and pairs seen in only two frames are kept. The tolerance appears below when this is on.",
            true, Boolean.class, config -> config.enableCommonMotionRejection,
            (config, value) -> config.enableCommonMotionRejection = value);
    public static final DetectionSetting<Double> MOTION_TOLERANCE = new DetectionSetting<>(
            "commonMotionShiftTolerance", "Common Motion Shift Tolerance", "pixels",
            "How closely, in pixels, those sources must share the same frame-to-frame displacement. Lower values keep only a tighter match.",
            true, Double.class, config -> config.commonMotionShiftTolerance,
            (config, value) -> config.commonMotionShiftTolerance = value);

    private static final List<DetectionSetting<?>> DEFINITIONS = List.of(ENABLE_RESIDUAL, ENABLE_RESCUE,
            ENABLE_CLUSTERS, CLUSTER_RADIUS, CLUSTER_FRAMES, REJECT_COMMON_MOTION, MOTION_TOLERANCE);

    /** Prevents constructing the fixed application page definitions. */
    private ResidualAnalysisSettings() {}

    /** Returns the immutable page definitions, without introducing settings for hidden engine fields. */
    public static List<DetectionSetting<?>> all() {
        return DEFINITIONS;
    }
}
