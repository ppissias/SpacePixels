package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfigRules;

import java.util.Map;

/**
 * Formats shared JTransient rule identifiers and values for SpacePixels settings feedback.
 * Numeric meanings, applicability and alignment remain in {@link DetectionConfigRules}; this class supplies
 * only application-owned English titles and explanations for the GUI and compatibility adapter.
 */
public final class DetectionSettingsPresentation {
    private static final Map<String, String> TITLES = Map.ofEntries(
            Map.entry("growSigmaMultiplier", "Grow Sigma"), Map.entry("detectionSigmaMultiplier", "Detection Sigma"),
            Map.entry("masterGrowSigmaMultiplier", "Master Grow Sigma"), Map.entry("masterSigmaMultiplier", "Master Sigma"),
            Map.entry("masterSlowMoverGrowSigmaMultiplier", "Slow-Mover Grow Sigma"), Map.entry("masterSlowMoverSigmaMultiplier", "Slow-Mover Sigma"),
            Map.entry("slowMoverMinAxisRatio", "Min Axis Ratio"), Map.entry("slowMoverMaxAxisRatio", "Max Axis Ratio"),
            Map.entry("slowMoverMedianSupportOverlapFraction", "Median Mask Min Overlap"), Map.entry("slowMoverMedianSupportMaxOverlapFraction", "Median Mask Max Overlap"),
            Map.entry("rhythmStationaryThreshold", "Rhythm Stationary Threshold"), Map.entry("maxJumpPixels", "Max Jump Velocity"),
            Map.entry("qualityGrowSigmaMultiplier", "Quality Grow Sigma"), Map.entry("qualitySigmaMultiplier", "Quality Sigma"));

    /** Prevents instantiation of this stateless presentation utility. */
    private DetectionSettingsPresentation() {}

    /**
     * Resolves a known settings title, falling back to the field ID for hidden or unmapped settings.
     *
     * @param field engine configuration field identifier
     * @return display title or the unchanged identifier
     */
    public static String title(String field) {
        return DetectionSettings.all().stream().filter(setting -> setting.id().equals(field))
                .map(DetectionSetting::title).findFirst().orElseGet(() -> TITLES.getOrDefault(field, field));
    }

    /**
     * Explains a shared finding without redefining its numeric comparison or changing settings.
     *
     * @param issue engine-produced diagnostic containing rule, fields and numeric evidence
     * @return application-facing English explanation
     */
    public static String issue(DetectionConfigRules.Issue issue) {
        String first = title(issue.fields().getFirst());
        return switch (issue.ruleId()) {
            case NON_FINITE -> "The setting " + issue.fields().getFirst() + " is not a finite number ("
                    + issue.values().get(issue.fields().getFirst()) + ")";
            case NUMERIC_DOMAIN -> first + " is outside its recommended engine domain ("
                    + DetectionConfigRules.metadata().get(issue.fields().getFirst()).domain() + "); legacy behavior is retained.";
            case GROW_SIGMA, MASTER_GROW_SIGMA, SLOW_MOVER_GROW_SIGMA, SLOW_MOVER_AXIS_RATIO,
                    MEDIAN_MASK_OVERLAP, QUALITY_GROW_SIGMA -> first + " is above " + title(issue.fields().get(1));
            case RHYTHM_STATIONARY_THRESHOLD -> "Rhythm Stationary Threshold is above the raw geometric jump cap. "
                    + "This is a legacy GUI policy, not a universal engine error: pruned tracks and streaks can have larger jumps.";
            case GEOMETRIC_JITTER_JUMP -> "Star jitter exceeds the maximum raw jump; geometric point linking cannot form a baseline "
                    + "when that branch is used, including fallback without timestamps.";
            case PHOTOMETRY_STAR_CAP -> "The photometry star cap is below " + issue.values().get("requiredStars")
                    + "; variable-star readiness or ensemble solving cannot succeed. Zero or a negative cap remains uncapped.";
            case PHOTOMETRY_GEOMETRY_FACTORS, PHOTOMETRY_EFFECTIVE_GEOMETRY -> "Photometry annulus factors need effective pixel-radius expansion outside all comparison apertures. "
                    + "The engine resolves this using measured FWHM; configured factors are not rewritten.";
            case LIMITED_AMPLITUDE -> "Limited-readiness amplitude is below the ordinary minimum, making this gate less conservative.";
            case ANOMALY_RESCUE_SUBSUMED -> "The anomaly integrated path is subsumed by the peak path; its peak floor is already at or above the peak gate.";
            case REDUNDANT_MINIMUM_PIXELS -> "The integrated anomaly pixel minimum is dominated by the common anomaly minimum.";
        };
    }

    /**
     * Describes an applied alignment direction using the caller's formatted replacement value.
     * Rhythm/jump feedback explicitly identifies the historical GUI policy rather than an engine invariant.
     *
     * @param adjustment engine-produced change with direction and related field identifiers
     * @param value replacement value already formatted for the relevant control
     * @return concise footer explanation of the applied change
     */
    public static String adjustment(DetectionConfigRules.Adjustment adjustment, String value) {
        String other = adjustment.relatedFields().stream().filter(field -> !field.equals(adjustment.field())).findFirst().orElse(adjustment.field());
        if (adjustment.ruleId() == DetectionConfigRules.RuleId.RHYTHM_STATIONARY_THRESHOLD) {
            return title(adjustment.field()) + " lowered to " + value + " (legacy GUI rhythm/jump policy)";
        }
        return title(adjustment.field()) + (adjustment.direction() == DetectionConfigRules.Direction.LOWER ? " lowered to " : " raised to ")
                + value + (adjustment.direction() == DetectionConfigRules.Direction.LOWER ? " (cannot be above " : " (cannot be below ")
                + title(other) + ")";
    }
}
