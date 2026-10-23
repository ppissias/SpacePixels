package eu.startales.spacepixels.config;

import java.util.List;

/** Typed definitions for the existing moving objects controls; hidden engine fields remain unbound. */
public final class MovingObjectsSettings {
    public static final DetectionSetting<Boolean> ENABLE_CAMERA_FIXED_REJECTION = new DetectionSetting<>(
            "enableCameraFixedRejection", "Reject Camera-Fixed Sources", "",
            "Before tracking, sets aside detections that stay at one camera position while the sky moves between frames: hot pixels, warm pixels and dust that the calibration left, which registration moves with the dither and the drift. How each frame was moved is measured from its registration fill, so frames without fill are not checked. The groups remain visible in the report.",
            false, Boolean.class, config -> config.enableCameraFixedRejection,
            (config, value) -> config.enableCameraFixedRejection = value);
    public static final DetectionSetting<Boolean> STRICT_EXPOSURE_KINEMATICS = new DetectionSetting<>(
            "strictExposureKinematics", "Strict Exposure Kinematics", "",
            "Rejects candidate links when the implied motion is inconsistent with exposure timing assumptions. Usually it is a good way to filter out false positives",
            true, Boolean.class, config -> config.strictExposureKinematics,
            (config, value) -> config.strictExposureKinematics = value);
    public static final DetectionSetting<Double> PREDICTION_TOLERANCE = new DetectionSetting<>(
            "predictionTolerance", "Prediction Line Tolerance", "pixels",
            "Maximum distance a candidate point may sit from the projected track line and still be accepted. Higher values allow noisier tracks but increase false links.",
            true, Double.class, config -> config.predictionTolerance,
            (config, value) -> config.predictionTolerance = value);
    public static final DetectionSetting<Double> TRACK_MIN_FRAME_RATIO = new DetectionSetting<>(
            "trackMinFrameRatio", "Track Length Min Frame Ratio", "",
            "Controls minimum track length as required points = total frames / this value. Lower values demand longer tracks; higher values allow shorter ones.",
            true, Double.class, config -> config.trackMinFrameRatio,
            (config, value) -> config.trackMinFrameRatio = value);
    public static final DetectionSetting<Integer> ABSOLUTE_MAX_POINTS_REQUIRED = new DetectionSetting<>(
            "absoluteMaxPointsRequired", "Absolute Max Required Points", "points",
            "Upper limit on the required number of points for a valid track in very long sequences.",
            true, Integer.class, config -> config.absoluteMaxPointsRequired,
            (config, value) -> config.absoluteMaxPointsRequired = value);
    public static final DetectionSetting<Double> MAX_FWHM_RATIO = new DetectionSetting<>(
            "maxFwhmRatio", "Max FWHM Ratio", "",
            "Maximum allowed FWHM difference between linked points. Helps ensure all points in a track have similar optical blur; 0 disables this check.",
            true, Double.class, config -> config.maxFwhmRatio,
            (config, value) -> config.maxFwhmRatio = value);
    public static final DetectionSetting<Double> MAX_SURFACE_BRIGHTNESS_RATIO = new DetectionSetting<>(
            "maxSurfaceBrightnessRatio", "Max Surface Brightness Ratio", "",
            "Maximum allowed surface-brightness difference between linked points. Helps prevent linking compact artifacts to diffuse blobs; 0 disables this check.",
            true, Double.class, config -> config.maxSurfaceBrightnessRatio,
            (config, value) -> config.maxSurfaceBrightnessRatio = value);
    public static final DetectionSetting<Double> TIME_BASED_VELOCITY_TOLERANCE = new DetectionSetting<>(
            "timeBasedVelocityTolerance", "Time-Based Velocity Tolerance", "",
            "When timestamps are available, this controls how much speed variation the moving-object linker tolerates between points before rejecting the track.",
            true, Double.class, config -> config.timeBasedVelocityTolerance,
            (config, value) -> config.timeBasedVelocityTolerance = value);
    public static final DetectionSetting<Boolean> ENABLE_GEOMETRIC_TRACK_LINKING = new DetectionSetting<>(
            "enableGeometricTrackLinking", "Enable Geometric Track Linking", "",
            "Enable the geometric (time-agnostic) track linker. May produce false positives. Its settings appear below when it is on.",
            true, Boolean.class, config -> config.enableGeometricTrackLinking,
            (config, value) -> config.enableGeometricTrackLinking = value);
    public static final DetectionSetting<Double> MAX_JUMP_PIXELS = new DetectionSetting<>(
            "maxJumpPixels", "Max Jump Velocity", "pixels",
            "Maximum geometric jump allowed between linked detections. This is only relevant to the geometric moving-object linker.",
            true, Double.class, config -> config.maxJumpPixels,
            (config, value) -> config.maxJumpPixels = value);
    public static final DetectionSetting<Double> RHYTHM_ALLOWED_VARIANCE = new DetectionSetting<>(
            "rhythmAllowedVariance", "Allowed Rhythm Variance", "",
            "Maximum deviation from the median step size allowed when checking whether a track moves at a steady rate.",
            true, Double.class, config -> config.rhythmAllowedVariance,
            (config, value) -> config.rhythmAllowedVariance = value);
    public static final DetectionSetting<Double> RHYTHM_MIN_CONSISTENCY_RATIO = new DetectionSetting<>(
            "rhythmMinConsistencyRatio", "Min Rhythm Consistency Ratio", "",
            "Minimum fraction of jumps that must match the median speed within the allowed variance for the geometric linker to keep the track.",
            true, Double.class, config -> config.rhythmMinConsistencyRatio,
            (config, value) -> config.rhythmMinConsistencyRatio = value);
    public static final DetectionSetting<Double> RHYTHM_STATIONARY_THRESHOLD = new DetectionSetting<>(
            "rhythmStationaryThreshold", "Rhythm Stationary Threshold", "pixels",
            "Tracks with median jump below this value are treated as stationary noise or residual stars rather than moving objects by the geometric linker.",
            true, Double.class, config -> config.rhythmStationaryThreshold,
            (config, value) -> config.rhythmStationaryThreshold = value);

    /** Prevents constructing the fixed page definitions. */
    private MovingObjectsSettings() {}

    /** Returns the immutable definitions in the existing page order. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE_CAMERA_FIXED_REJECTION, STRICT_EXPOSURE_KINEMATICS, PREDICTION_TOLERANCE, TRACK_MIN_FRAME_RATIO, ABSOLUTE_MAX_POINTS_REQUIRED, MAX_FWHM_RATIO, MAX_SURFACE_BRIGHTNESS_RATIO, TIME_BASED_VELOCITY_TOLERANCE, ENABLE_GEOMETRIC_TRACK_LINKING, MAX_JUMP_PIXELS, RHYTHM_ALLOWED_VARIANCE, RHYTHM_MIN_CONSISTENCY_RATIO, RHYTHM_STATIONARY_THRESHOLD);
    }
}
