/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Rules a detection configuration meets beyond the range of each value: every number is finite, and the settings that
 * come in pairs are in order. The detection settings tab keeps its pairs in order and saves or starts a run only with
 * a configuration that meets these rules; profiles and the API are not checked yet.
 */
public final class DetectionSettingsRules {

    /**
     * Two settings where the first may not be above the second. When an edit crosses them, the settings tab moves one
     * of the two to the other's value: the first down ({@link #lowersFirst}) or the second up.
     * <p>
     * The master star map is not paired with the per-frame settings: a master shallower than the per-frame Grow Sigma
     * hides less sky but lets more star wings leak, a trade-off the calibrated Auto-Tuner measures.
     */
    public enum Pair {
        GROW_SIGMA("Grow Sigma", c -> c.growSigmaMultiplier,
                "Detection Sigma", c -> c.detectionSigmaMultiplier, true),
        // A Master Grow Sigma of 0 means "the same as Master Sigma".
        MASTER_GROW_SIGMA("Master Grow Sigma", c -> c.masterGrowSigmaMultiplier,
                "Master Sigma", c -> c.masterSigmaMultiplier, true),
        SLOW_MOVER_GROW_SIGMA("Slow-Mover Grow Sigma", c -> c.masterSlowMoverGrowSigmaMultiplier,
                "Slow-Mover Sigma", c -> c.masterSlowMoverSigmaMultiplier, true),
        SLOW_MOVER_AXIS_RATIO("Min Axis Ratio", c -> c.slowMoverMinAxisRatio,
                "Max Axis Ratio", c -> c.slowMoverMaxAxisRatio, false),
        MEDIAN_MASK_OVERLAP("Median Mask Min Overlap", c -> c.slowMoverMedianSupportOverlapFraction,
                "Median Mask Max Overlap", c -> c.slowMoverMedianSupportMaxOverlapFraction, false),
        // A stationary threshold above the maximum jump would make the geometric linker contradict itself.
        RHYTHM_STATIONARY_THRESHOLD("Rhythm Stationary Threshold", c -> c.rhythmStationaryThreshold,
                "Max Jump Velocity", c -> c.maxJumpPixels, true);

        public final String firstTitle;
        public final String secondTitle;
        /** Whether the first setting is lowered to the second when they cross; otherwise the second is raised. */
        public final boolean lowersFirst;
        private final ToDoubleFunction<DetectionConfig> first;
        private final ToDoubleFunction<DetectionConfig> second;

        Pair(String firstTitle, ToDoubleFunction<DetectionConfig> first,
             String secondTitle, ToDoubleFunction<DetectionConfig> second, boolean lowersFirst) {
            this.firstTitle = firstTitle;
            this.first = first;
            this.secondTitle = secondTitle;
            this.second = second;
            this.lowersFirst = lowersFirst;
        }

        public boolean holds(DetectionConfig config) {
            return first.applyAsDouble(config) <= second.applyAsDouble(config);
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
        List<String> problems = new ArrayList<>();
        for (Field field : DetectionConfig.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) || (field.getType() != double.class && field.getType() != float.class)) {
                continue;
            }
            double value;
            try {
                value = field.getDouble(config);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (!Double.isFinite(value)) {
                problems.add("The setting " + field.getName() + " is not a finite number (" + value + ")");
            }
        }
        for (Pair pair : Pair.values()) {
            if (!pair.holds(config)) {
                problems.add(pair.firstTitle + " is above " + pair.secondTitle);
            }
        }
        return problems;
    }
}
