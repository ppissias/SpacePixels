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
import eu.startales.spacepixels.config.VisualizationSettings;

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
        return run(candidateFrames, baseConfig, profile, listener, VisualizationSettings.defaults());
    }

    /** Uses the run's captured rendering choices when a live view is open; headless calls construct no observer. */
    public static AutoTunerResult run(List<ImageFrame> candidateFrames, DetectionConfig baseConfig,
            AutoTuneProfile profile, TransientEngineProgressListener listener, VisualizationSettings visualization) {
        return run(candidateFrames, baseConfig, profile, listener, visualization, List.of());
    }

    /** Tunes on sky that detection uses: the tuner's measurement crops stay clear of the excluded areas. */
    public static AutoTunerResult run(List<ImageFrame> candidateFrames, DetectionConfig baseConfig,
            AutoTuneProfile profile, TransientEngineProgressListener listener, VisualizationSettings visualization,
            List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) {
        try (var diagnostics = DebugOutput.openEngineDiagnostics()) {
            RunPreviewSink sink = RunPreviewScope.currentSink();
            CalibratedAutoTuner.Options.Builder options = CalibratedAutoTuner.Options.builder();
            options.excludedAreas = excludedAreas;
            return CalibratedAutoTuner.tune(candidateFrames, baseConfig, options.build(), profile, listener,
                    sink == null ? null : new AutoTuneLiveView(sink, visualization));
        }
    }
}
