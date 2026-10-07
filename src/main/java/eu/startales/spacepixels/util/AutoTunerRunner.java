/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.List;
import java.util.Locale;

/**
 * Runs the selected JTransient auto-tuner. Both tuners are kept while the calibrated one is evaluated.
 */
public final class AutoTunerRunner {

    /** Which auto-tuning algorithm to use. */
    public enum Algorithm {
        /** Measures noise (negative image), star leakage and sensitivity (synthetic sources) directly. */
        CALIBRATED("Calibrated (measured)"),
        /** The original score-based tuner. */
        LEGACY("Legacy (score-based)");

        private final String label;

        Algorithm(String label) {
            this.label = label;
        }

        /** Parses {@code calibrated} or {@code legacy}, case-insensitive. */
        public static Algorithm parse(String value) {
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (Exception e) {
                throw new IllegalArgumentException("Unknown auto-tuner '" + value + "'. Expected calibrated or legacy.");
            }
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Default algorithm for new runs. */
    public static final Algorithm DEFAULT_ALGORITHM = Algorithm.CALIBRATED;

    private AutoTunerRunner() {
    }

    /**
     * Tunes {@code baseConfig} on the candidate frames with the chosen algorithm and profile.
     */
    public static JTransientAutoTuner.AutoTunerResult run(List<ImageFrame> candidateFrames,
                                                          DetectionConfig baseConfig,
                                                          JTransientAutoTuner.AutoTuneProfile profile,
                                                          Algorithm algorithm,
                                                          TransientEngineProgressListener listener) {
        if (algorithm == Algorithm.LEGACY) {
            // The legacy tuner samples AUTO_TUNE_SAMPLE_SIZE frames; never ask for more than are available.
            int originalSampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
            JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = Math.min(originalSampleSize, candidateFrames.size());
            try {
                return JTransientAutoTuner.tune(candidateFrames, baseConfig, profile, listener);
            } finally {
                JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = originalSampleSize;
            }
        }
        return CalibratedAutoTuner.tune(candidateFrames, baseConfig, profile, listener);
    }
}
