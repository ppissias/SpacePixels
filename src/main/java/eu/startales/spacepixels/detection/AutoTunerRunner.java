/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.detection;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.util.List;
import eu.startales.spacepixels.util.Cancellation;
import eu.startales.spacepixels.util.DebugOutput;

/**
 * Runs JTransient's calibrated auto-tuner with SpacePixels' engine diagnostics. Cancellation comes from the
 * caller's {@link io.github.ppissias.jtransient.execution.CancellationScope}.
 */
public final class AutoTunerRunner {

    private AutoTunerRunner() {
    }

    /**
     * Tunes {@code baseConfig} on the candidate frames for the given profile.
     */
    public static AutoTunerResult run(List<ImageFrame> candidateFrames,
                                      DetectionConfig baseConfig,
                                      AutoTuneProfile profile,
                                      TransientEngineProgressListener listener) {
        try (var diagnostics = DebugOutput.openEngineDiagnostics()) {
            return CalibratedAutoTuner.tune(candidateFrames, baseConfig, profile, listener);
        }
    }
}
