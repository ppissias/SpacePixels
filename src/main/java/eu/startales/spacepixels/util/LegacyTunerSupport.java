package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.CancellationToken;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/** Isolates the optional JTransient 1.0.6 tuner capability while retaining published 1.0.5 behavior. */
final class LegacyTunerSupport {
    private static final Object LEGACY_DEFAULTS_LOCK = new Object();
    private static final LegacyTunerSupport AVAILABLE = new LegacyTunerSupport(findIndependentTuner());
    private final Method independentTuner;

    /** Captures the resolved optional entry point; null selects the serialized pre-1.0.6 compatibility path. */
    LegacyTunerSupport(Method independentTuner) {
        this.independentTuner = independentTuner;
    }

    /** Returns the adapter selected once for the JTransient artifact on this application's classpath. */
    static LegacyTunerSupport available() {
        return AVAILABLE;
    }

    /** Tunes borrowed pixels until return, without touching shared defaults when the independent API is present. */
    JTransientAutoTuner.AutoTunerResult run(List<ImageFrame> frames, DetectionConfig config,
                                          JTransientAutoTuner.AutoTuneProfile profile,
                                          TransientEngineProgressListener listener) {
        if (independentTuner != null) {
            try {
                return (JTransientAutoTuner.AutoTunerResult) independentTuner.invoke(null,
                        frames, config, profile, listener, CancellationScope.current());
            } catch (InvocationTargetException failure) {
                Throwable cause = failure.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Independent legacy tuning failed.", cause);
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException("Independent legacy tuner is not accessible.", failure);
            }
        }
        synchronized (LEGACY_DEFAULTS_LOCK) {
            int originalSampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
            JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = Math.min(originalSampleSize, frames.size());
            try {
                return JTransientAutoTuner.tuneSnapshot(frames, config, profile, listener, CancellationScope.current());
            } finally {
                JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = originalSampleSize;
            }
        }
    }

    /** Resolves only the new optional method; the rest of SpacePixels still compiles and runs against 1.0.5. */
    private static Method findIndependentTuner() {
        try {
            return JTransientAutoTuner.class.getMethod("tuneAvailableSnapshot", List.class, DetectionConfig.class,
                    JTransientAutoTuner.AutoTuneProfile.class, TransientEngineProgressListener.class, CancellationToken.class);
        } catch (NoSuchMethodException olderEngine) {
            return null;
        }
    }
}
