package eu.startales.spacepixels.detection;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.execution.CancellationScope;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.*;
import eu.startales.spacepixels.util.Cancellation;

/** Verifies direct engine tuning, detached fallback settings and cooperative cancellation. */
public class AutoTunerRunnerTest {
    /** Insufficient frames give an unsuccessful result whose fallback settings are a copy. */
    @Test
    public void unsuccessfulRunReturnsDetachedFallbackSettings() {
        DetectionConfig config = new DetectionConfig();
        var result = AutoTunerRunner.run(frames(), config, AutoTuneProfile.MEDIUM, null);
        assertFalse(result.success);
        assertNotSame(config, result.optimizedConfig);
    }

    /** Direct calls propagate listener failures without reflection transport wrappers. */
    @Test
    public void listenerFailureRemainsAvailable() {
        var failure = new IllegalStateException("listener failed");
        assertSame(failure, assertThrows(io.github.ppissias.jtransient.execution.OperationFailedException.class, () -> AutoTunerRunner.run(noiseFrames(),
                new DetectionConfig(), AutoTuneProfile.MEDIUM,
                (percent, message) -> { throw failure; })).getCause());
    }

    /** Cancellation remains distinct from an unsuccessful scientific tuning result. */
    @Test
    public void cancellationPropagates() {
        try (var scope = CancellationScope.open(() -> true)) {
            assertThrows(CancellationException.class, () -> AutoTunerRunner.run(frames(),
                    new DetectionConfig(), AutoTuneProfile.MEDIUM, null));
        }
    }

    /** Creates a valid small frame that cannot provide a successful tuning measurement. */
    private static List<ImageFrame> frames() {
        return List.of(new ImageFrame(10, "small.fit", new short[8][8], 0, 1000));
    }

    /** Creates five noise frames large enough for the tuner to start measuring and report progress. */
    private static List<ImageFrame> noiseFrames() {
        java.util.Random random = new java.util.Random(19);
        List<ImageFrame> frames = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            short[][] pixels = new short[900][900];
            for (short[] row : pixels) {
                for (int column = 0; column < row.length; column++) {
                    row[column] = PixelEncoding.fromShiftedPositiveInt(1000 + random.nextInt(61));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 60_000));
        }
        return frames;
    }
}
