package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

/** Verifies direct selected-engine tuning, detached fallback settings and cooperative cancellation. */
public class AutoTunerRunnerTest {
    /** Available-sample tuning uses private policy rather than temporarily editing process defaults. */
    @Test
    public void legacyRunUsesIndependentAvailableSamples() {
        int sampleSize = JTransientAutoTuner.Options.builder().sampleSize;
        DetectionConfig config = new DetectionConfig();
        var result = AutoTunerRunner.run(frames(), config, JTransientAutoTuner.AutoTuneProfile.MEDIUM,
                AutoTunerRunner.Algorithm.LEGACY,
                (percent, message) -> assertEquals(sampleSize, JTransientAutoTuner.Options.builder().sampleSize));
        assertFalse(result.success);
        assertNotSame(config, result.optimizedConfig);
    }

    /** Direct calls propagate listener failures without reflection transport wrappers. */
    @Test
    public void listenerFailureRemainsAvailable() {
        var failure = new IllegalStateException("listener failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> AutoTunerRunner.run(frames(),
                new DetectionConfig(), JTransientAutoTuner.AutoTuneProfile.MEDIUM, AutoTunerRunner.Algorithm.LEGACY,
                (percent, message) -> { throw failure; })));
    }

    /** Cancellation remains distinct from an unsuccessful scientific tuning result. */
    @Test
    public void cancellationPropagates() {
        try (var scope = CancellationScope.open(() -> true)) {
            assertThrows(CancellationException.class, () -> AutoTunerRunner.run(frames(),
                    new DetectionConfig(), JTransientAutoTuner.AutoTuneProfile.MEDIUM, AutoTunerRunner.Algorithm.LEGACY, null));
        }
    }

    /** Creates a valid small frame that cannot provide a successful tuning measurement. */
    private static List<ImageFrame> frames() {
        return List.of(new ImageFrame(10, "small.fit", new short[8][8], 0, 1000));
    }
}
