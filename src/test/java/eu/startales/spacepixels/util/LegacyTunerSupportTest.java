package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;

/** Checks both the independent engine path and the old-artifact compatibility path without scientific work. */
public class LegacyTunerSupportTest {
    /** Observes unchanged global sample size inside a new-engine progress callback and preserves caller settings. */
    @Test
    public void independentRunDoesNotTemporarilyEditSampleDefaults() {
        org.junit.Assume.assumeTrue(java.util.Arrays.stream(JTransientAutoTuner.class.getMethods())
                .anyMatch(method -> method.getName().equals("tuneAvailableSnapshot")));
        int sampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
        DetectionConfig config = new DetectionConfig();
        var result = LegacyTunerSupport.available().run(frames(), config, JTransientAutoTuner.AutoTuneProfile.MEDIUM,
                (percent, message) -> assertEquals(sampleSize, JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE));
        assertFalse(result.success);
        assertNotSame(config, result.optimizedConfig);
        assertEquals(sampleSize, JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE);
    }

    /** Simulates an older artifact and checks that failures restore the temporarily adjusted legacy default. */
    @Test
    public void oldArtifactFallbackRestoresDefaultsAfterListenerFailure() {
        int sampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
        var failure = new IllegalStateException("listener failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () ->
                new LegacyTunerSupport(null).run(frames(), new DetectionConfig(), JTransientAutoTuner.AutoTuneProfile.MEDIUM,
                        (percent, message) -> {
                            assertEquals(1, JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE);
                            throw failure;
                        })));
        assertEquals(sampleSize, JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE);
    }

    /** Verifies reflective invocation does not wrap cooperative cancellation in an ordinary execution failure. */
    @Test
    public void cancellationKeepsItsPublicExceptionContract() {
        try (var scope = CancellationScope.open(() -> true)) {
            assertThrows(CancellationException.class, () -> LegacyTunerSupport.available().run(frames(),
                    new DetectionConfig(), JTransientAutoTuner.AutoTuneProfile.MEDIUM, null));
        }
    }

    /** Creates a valid but deliberately too-small image so integration tests stop before extraction. */
    private static List<ImageFrame> frames() {
        return List.of(new ImageFrame(0, "small.fit", new short[8][8], 0, 1000));
    }
}
