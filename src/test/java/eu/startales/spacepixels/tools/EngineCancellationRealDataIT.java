package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.util.Cancellation;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.FitsImportService;
import eu.startales.spacepixels.util.FitsInput;
import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.GsonBuilder;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;
import nom.tam.fits.Fits;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Read-only, opt-in latency measurements using SPACEPIXELS_CANCELLATION_DATASET and a sufficiently large test heap. */
public class EngineCancellationRealDataIT {
    private static List<ImageFrame> frames;
    private static Path scratchHome;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass the destination for a scientific-output comparison snapshot.");
        }
        loadFrames();
        try (JTransientEngine engine = new JTransientEngine()) {
            DetectionConfig config = new DetectionConfig();
            config.enableVariableStarDetection = true;
            PipelineResult result = engine.runPipeline(new ArrayList<>(frames), config, null);
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("config", config);
            snapshot.put("result", result);
            snapshot.put("masterStackSha256", hashPixels(result.masterStackData));
            snapshot.put("maximumStackSha256", hashPixels(result.maximumStackData));
            snapshot.put("slowMoverStackSha256", hashPixels(result.slowMoverStackData));
            snapshot.put("masterVetoMaskSha256", hashMask(result.masterVetoMask));
            snapshot.put("slowMoverMedianVetoMaskSha256", hashMask(result.slowMoverMedianVetoMask));
            var gson = new GsonBuilder().serializeNulls().serializeSpecialFloatingPointValues()
                    .addSerializationExclusionStrategy(new ExclusionStrategy() {
                        @Override
                        public boolean shouldSkipField(FieldAttributes field) {
                            return field.getDeclaredClass() == short[][].class || field.getDeclaredClass() == boolean[][].class
                                    || field.getName().equals("stageTimeMs") || field.getName().endsWith("TimeMs");
                        }

                        @Override
                        public boolean shouldSkipClass(Class<?> type) {
                            return false;
                        }
                    }).create();
            try (var writer = Files.newBufferedWriter(Path.of(args[0]))) {
                gson.toJson(snapshot, writer);
            }
            System.out.println("Scientific-output snapshot written.");
        } finally {
            releaseFrames();
        }
    }

    private static String hashPixels(short[][] pixels) throws Exception {
        if (pixels == null) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (short[] row : pixels) {
            byte[] values = new byte[row.length * 2];
            for (int column = 0; column < row.length; column++) {
                values[2 * column] = (byte) (row[column] >>> 8);
                values[2 * column + 1] = (byte) row[column];
            }
            digest.update(values);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashMask(boolean[][] mask) throws Exception {
        if (mask == null) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (boolean[] row : mask) {
            byte[] values = new byte[row.length];
            for (int column = 0; column < row.length; column++) values[column] = row[column] ? (byte) 1 : (byte) 0;
            digest.update(values);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @BeforeClass
    public static void loadFrames() throws Exception {
        String dataset = System.getenv("SPACEPIXELS_CANCELLATION_DATASET");
        Assume.assumeTrue("SPACEPIXELS_CANCELLATION_DATASET not set", dataset != null && !dataset.isBlank());
        String originalHome = System.getProperty("user.home");
        scratchHome = Files.createTempDirectory("spacepixels-cancellation-home-");
        try {
            System.setProperty("user.home", scratchHome.toString());
            try (FitsImportService importer = new FitsImportService(new File(dataset), null)) {
                FitsFileInformation[] files = importer.readFiles();
                frames = new ArrayList<>();
                for (int index = 0; index < files.length; index++) {
                    FitsFileInformation info = files[index];
                    try (Fits fits = new Fits(info.getFilePath())) {
                        Object pixels = FitsInput.getImageHDU(fits).getKernel();
                        assertTrue("Use a prepared 16-bit monochrome dataset.", pixels instanceof short[][]);
                        frames.add(new ImageFrame(index, info.getFileName(), (short[][]) pixels,
                                info.getObservationTimestamp(), info.getExposureDurationMillis()));
                    }
                }
            }
        } finally {
            System.setProperty("user.home", originalHome);
        }
        assertTrue("Use at least five real frames.", frames.size() >= 5);
    }

    @AfterClass
    public static void releaseFrames() throws Exception {
        frames = null;
        if (scratchHome != null) Files.deleteIfExists(scratchHome);
    }

    @Test
    public void measuresCancellationDuringExtraction() throws Exception {
        measure("extraction", "Extracting features from frame 5 of", false, 0);
    }

    @Test
    public void measuresCancellationDuringPhotometrySolution() throws Exception {
        measure("photometry ensemble", "Photometry: readiness checks and ensemble solution", false, 100);
    }

    @Test
    public void measuresCancellationDuringCalibratedTuning() throws Exception {
        measure("calibrated tuning", "Measuring sigma", true, 100);
    }

    private void measure(String stage, String trigger, boolean tuning, long delayMillis) throws Exception {
        boolean listenerOnly = "1".equals(System.getenv("SPACEPIXELS_CANCELLATION_LISTENER_ONLY"));
        AtomicBoolean scheduled = new AtomicBoolean();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong requestedAt = new AtomicLong();
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        DetectionConfig config = new DetectionConfig();
        config.enableVariableStarDetection = true;
        TransientEngineProgressListener listener = (percent, message) -> {
            if (message.startsWith(trigger) && scheduled.compareAndSet(false, true)) {
                if (delayMillis == 0) {
                    requestedAt.set(System.nanoTime());
                    cancelled.set(true);
                } else {
                    timer.schedule(() -> {
                        requestedAt.set(System.nanoTime());
                        cancelled.set(true);
                    }, delayMillis, TimeUnit.MILLISECONDS);
                }
            }
            if (listenerOnly && cancelled.get()) throw new CancellationException("Real-data cancellation measurement.");
        };
        Object result = null;
        Exception failure = null;
        try (CancellationScope scope = CancellationScope.open(cancelled::get)) {
            try {
                if (tuning) {
                    List<ImageFrame> pool = new ArrayList<>();
                    int poolSize = Math.min(15, frames.size());
                    for (int index = 0; index < poolSize; index++) pool.add(frames.get(index * frames.size() / poolSize));
                    result = CalibratedAutoTuner.tune(pool, config, AutoTuneProfile.MEDIUM, listener);
                } else {
                    try (JTransientEngine engine = new JTransientEngine()) {
                        result = engine.runPipeline(new ArrayList<>(frames), config, listener);
                    }
                }
            } catch (Exception stopped) {
                failure = stopped;
            }
        } finally {
            timer.shutdownNow();
            assertTrue(timer.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue("Stage was not reached: " + stage, scheduled.get());
        assertTrue("Cancellation was not requested in time: " + stage, cancelled.get());
        double seconds = (System.nanoTime() - requestedAt.get()) / 1_000_000_000.0;
        System.out.printf(java.util.Locale.ROOT, "Cancellation latency | %s | %.3f s | %s%n", stage, seconds,
                failure == null ? "returned result" : failure.getClass().getSimpleName());
        if (listenerOnly && tuning && failure == null) {
            assertTrue(result instanceof AutoTunerResult tune && !tune.success);
        } else {
            assertNotNull("Cancelled work returned normally: " + stage, failure);
            if (!Cancellation.isCancellation(failure)) fail("Unexpected failure: " + failure);
        }
    }
}
