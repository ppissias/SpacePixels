/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;

import java.util.List;

/**
 * Compatibility presentation adapter for the original GUI rules. Numeric meanings, applicability and alignment
 * belong to JTransient's DetectionConfigRules, also used by profile loading and API requests. Pair diagnostics are
 * advisory for direct engine callers; legacy GUI alignment remains a separate compatibility policy.
 */
@Deprecated
public final class DetectionSettingsRules {

    /**
     * Two settings ordered by the legacy GUI policy. When an edit crosses them, the settings tab moves one
     * of the two to the other's value: the first down ({@link #lowersFirst}) or the second up.
     * <p>
     * Master and per-frame multipliers use different image noise estimates and are not ordered against one another.
     * The calibrated Auto-Tuner measures leakage and coverage rather than imposing a cross-image inequality.
     */
    public enum Pair {
        GROW_SIGMA, MASTER_GROW_SIGMA, SLOW_MOVER_GROW_SIGMA, SLOW_MOVER_AXIS_RATIO,
        MEDIAN_MASK_OVERLAP, RHYTHM_STATIONARY_THRESHOLD;

        public final String firstTitle;
        public final String secondTitle;
        /** Whether the first setting is lowered to the second when they cross; otherwise the second is raised. */
        public final boolean lowersFirst;
        private final DetectionConfigRules.Pair rule;

        Pair() {
            rule = DetectionConfigRules.pairs().stream().filter(pair -> pair.ruleId().name().equals(name())).findFirst().orElseThrow();
            firstTitle = DetectionSettingsPresentation.title(rule.firstField());
            secondTitle = DetectionSettingsPresentation.title(rule.secondField());
            lowersFirst = rule.direction() == DetectionConfigRules.Direction.LOWER;
        }

        public boolean holds(DetectionConfig config) {
            return rule.holds(config);
        }

        /** What moving a setting of the pair did, for example "Grow Sigma lowered to 4.0 (cannot be above Detection Sigma)". */
        public String adjustment(String newValue) {
            return lowersFirst
                    ? firstTitle + " lowered to " + newValue + " (cannot be above " + secondTitle + ")"
                    : secondTitle + " raised to " + newValue + " (cannot be below " + firstTitle + ")";
        }
    }

    private DetectionSettingsRules() {
    }

    /** What breaks the rules, one sentence per problem; empty when the configuration meets them all. */
    public static List<String> problems(DetectionConfig config) {
        return DetectionConfigRules.validate(config, DetectionConfigRules.Operation.CONFIGURATION).stream()
                .filter(issue -> issue.ruleId() == DetectionConfigRules.RuleId.NON_FINITE
                        || java.util.Arrays.stream(Pair.values()).anyMatch(pair -> pair.name().equals(issue.ruleId().name())))
                .map(issue -> issue.ruleId() == DetectionConfigRules.RuleId.RHYTHM_STATIONARY_THRESHOLD
                        ? "Rhythm Stationary Threshold is above Max Jump Velocity" : DetectionSettingsPresentation.issue(issue)).toList();
    }
}
