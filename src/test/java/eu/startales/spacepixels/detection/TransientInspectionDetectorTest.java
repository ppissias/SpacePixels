package eu.startales.spacepixels.detection;

import com.google.gson.GsonBuilder;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientInspection;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.execution.DiagnosticContext;
import io.github.ppissias.jtransient.execution.DiagnosticOptions;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Checks complete-settings cache identity and nonblocking closure during real engine re-detection. */
public class TransientInspectionDetectorTest {
    /** A live background-setting edit must not return detections made with the opening settings. */
    @Test
    public void nonTrialSettingsUseAFreshDetectionAndRestoringSettingsReusesTheOpeningPass() {
        DetectionConfig config = new DetectionConfig();
        TransientInspection opening = inspection(config);
        try (var detector = new TransientInspectionDetector(opening.reference(), opening.frames(), config)) {
            var original = detector.detect(0, config);
            assertSame(opening.frames().get(0).transients, original);

            DetectionConfig changed = config.clone();
            changed.bgClippingFactor = 1;
            var updated = detector.detect(0, changed);
            assertNotSame("background clipping is part of the detection's identity", original, updated);
            try (var engine = new JTransientEngine()) {
                var expected = engine.redetectFrame(opening.reference(), 0, changed).transients;
                var json = new GsonBuilder().serializeSpecialFloatingPointValues().create();
                assertEquals(json.toJson(expected), json.toJson(updated));
            }
            assertSame("an unchanged snapshot still uses the cache", updated, detector.detect(0, changed.clone()));
            assertSame(original, detector.detect(0, config));
        }
    }

    /** Closing a window neither waits for nor shuts down the engine still owned by its cancelled worker. */
    @Test(timeout = 10_000)
    public void closingDuringRedetectionReturnsBeforeTheCancelledOperationFinishes() throws Exception {
        DetectionConfig config = new DetectionConfig();
        TransientInspection opening = inspection(config);
        var entered = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        var blocked = new AtomicBoolean();
        var cancelled = new AtomicBoolean();
        var worker = Executors.newSingleThreadExecutor();
        try (var detector = new TransientInspectionDetector(opening.reference(), opening.frames(), config)) {
            DetectionConfig trial = config.clone();
            trial.detectionSigmaMultiplier += 0.05;
            var result = worker.submit(() -> {
                try (var cancellation = CancellationScope.open(cancelled::get);
                     var diagnostics = DiagnosticContext.open(new DiagnosticOptions(true, message -> {
                         if (blocked.compareAndSet(false, true)) {
                             entered.countDown();
                             awaitRelease(proceed);
                         }
                     }))) {
                    return detector.detect(0, trial);
                }
            });
            assertTrue("the real engine is active", entered.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            detector.close();
            assertFalse("close does not await computation", result.isDone());
            assertThrows(IllegalStateException.class, () -> detector.detect(0, config));
            proceed.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
        } finally {
            proceed.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** Holds an active diagnostic callback until the test has closed its detector. */
    private static void awaitRelease(CountDownLatch proceed) {
        try {
            if (!proceed.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release re-detection.");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Builds the same small kept-reference workflow used by the viewer. */
    private static TransientInspection inspection(DetectionConfig config) {
        try (var engine = new JTransientEngine()) {
            return engine.inspectTransients(frames(), config, null);
        }
    }

    /** Five repeatable noisy frames with stationary stars and one moving source. */
    private static List<ImageFrame> frames() {
        Random random = new Random(7);
        List<ImageFrame> frames = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            short[][] pixels = new short[96][96];
            for (int y = 0; y < 96; y++) {
                for (int x = 0; x < 96; x++) {
                    double value = 1000 + 8 * random.nextGaussian();
                    value += 900 * Math.exp(-(Math.pow(x - 30, 2) + Math.pow(y - 40, 2)) / 4.0);
                    value += 600 * Math.exp(-(Math.pow(x - 70, 2) + Math.pow(y - 60, 2)) / 4.0);
                    value += 500 * Math.exp(-(Math.pow(x - 12 - 14 * index, 2) + Math.pow(y - 20 - 10 * index, 2)) / 3.0);
                    pixels[y][x] = PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
                }
            }
            frames.add(new ImageFrame(index, "frame-" + index, pixels, index * 60_000L, 30_000));
        }
        return frames;
    }
}
