/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */

package eu.startales.spacepixels.tools;

import com.google.gson.Gson;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Headless batch transient detector for monochrome 16-bit FITS sequences.
 */
public class BatchDetectionTool {

    private static class CliArguments {
        private final File inputDir;
        private final File configFile;
        private final JTransientAutoTuner.AutoTuneProfile autoTuneProfile;
        private final boolean showHelp;

        private CliArguments(File inputDir, File configFile, JTransientAutoTuner.AutoTuneProfile autoTuneProfile, boolean showHelp) {
            this.inputDir = inputDir;
            this.configFile = configFile;
            this.autoTuneProfile = autoTuneProfile;
            this.showHelp = showHelp;
        }
    }

    private static class ScoredFrame {
        private final ImageFrame frame;
        private final double score;

        private ScoredFrame(ImageFrame frame, double score) {
            this.frame = frame;
            this.score = score;
        }
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        int exitCode = 0;
        try {
            CliArguments cliArguments = parseArguments(args);
            if (cliArguments.showHelp) {
                printUsage();
                return;
            }

            run(cliArguments);
        } catch (IllegalArgumentException e) {
            System.err.println("Argument error: " + e.getMessage());
            printUsage();
            exitCode = 2;
        } catch (Exception e) {
            System.err.println("Batch detection failed: " + e.getMessage());
            e.printStackTrace(System.err);
            exitCode = 1;
        }

        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void run(CliArguments cliArguments) throws Exception {
        if (!cliArguments.inputDir.exists() || !cliArguments.inputDir.isDirectory()) {
            throw new IOException("Input path is not a directory: " + cliArguments.inputDir.getAbsolutePath());
        }
        if (!cliArguments.configFile.exists() || !cliArguments.configFile.isFile()) {
            throw new IOException("Configuration JSON file not found: " + cliArguments.configFile.getAbsolutePath());
        }

        DetectionConfig baseConfig = loadDetectionConfig(cliArguments.configFile);
        ImageProcessing imageProcessing = ImageProcessing.getInstance(cliArguments.inputDir);
        FitsFileInformation[] filesInfo = imageProcessing.getFitsfileInformationHeadless();

        DetectionConfig effectiveConfig = baseConfig;
        if (cliArguments.autoTuneProfile != null) {
            effectiveConfig = runAutoTune(filesInfo, baseConfig.clone(), cliArguments.autoTuneProfile);
        }

        File reportFile = imageProcessing.detectObjects(effectiveConfig, null, createConsoleProgressListener("Pipeline"));
        if (reportFile == null) {
            throw new IOException("Pipeline completed without producing an export report.");
        }

        System.out.println();
        System.out.println("Batch detection completed successfully.");
        System.out.println("Export directory: " + reportFile.getParentFile().getAbsolutePath());
        System.out.println("Report file: " + reportFile.getAbsolutePath());
    }

    private static DetectionConfig loadDetectionConfig(File configFile) throws IOException {
        try (FileReader reader = new FileReader(configFile)) {
            DetectionConfig config = new Gson().fromJson(reader, DetectionConfig.class);
            if (config == null) {
                throw new IOException("Configuration file did not contain a DetectionConfig object: " + configFile.getAbsolutePath());
            }
            return config;
        }
    }

    private static DetectionConfig runAutoTune(FitsFileInformation[] filesInfo, DetectionConfig baseConfig, JTransientAutoTuner.AutoTuneProfile profile) throws Exception {
        if (filesInfo.length < JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE) {
            throw new IOException("Auto-Tune requires at least " + JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE +
                    " frames, but only " + filesInfo.length + " were available.");
        }

        System.out.println();
        System.out.println("Running Auto-Tune with profile: " + profile.name().toLowerCase(Locale.ROOT));

        List<ScoredFrame> topFrames = new ArrayList<>();
        for (int i = 0; i < filesInfo.length; i++) {
            int percent = (int) (((i + 1) / (double) filesInfo.length) * 50);
            System.out.printf(Locale.US, "[Auto-Tune %3d%%] Evaluating frame %d of %d...%n", percent, i + 1, filesInfo.length);

            FitsFileInformation info = filesInfo[i];
            try (Fits fitsFile = new Fits(info.getFilePath())) {
                BasicHDU<?> hdu = ImageProcessing.getImageHDU(fitsFile);
                Object kernel = hdu.getKernel();
                if (!(kernel instanceof short[][])) {
                    throw new IOException("FITS file is not 16-bit monochrome: " + info.getFileName());
                }

                short[][] pixelData = (short[][]) kernel;
                FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(pixelData, baseConfig);
                double score = metrics.backgroundNoise * metrics.medianFWHM;

                ImageFrame currentFrame = new ImageFrame(i, info.getFileName(), pixelData, info.getObservationTimestamp(), info.getExposureDurationMillis());
                topFrames.add(new ScoredFrame(currentFrame, score));
                topFrames.sort(Comparator.comparingDouble(frame -> frame.score));

                if (topFrames.size() > JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE) {
                    topFrames.remove(topFrames.size() - 1);
                }
            }
        }

        List<ImageFrame> bestFrames = new ArrayList<>();
        for (ScoredFrame scoredFrame : topFrames) {
            bestFrames.add(scoredFrame.frame);
        }
        bestFrames.sort(Comparator.comparingInt(frame -> frame.sequenceIndex));

        TransientEngineProgressListener autoTuneListener = createScaledConsoleProgressListener("Auto-Tune", 50, 100, "Tuning");
        JTransientAutoTuner.AutoTunerResult result = JTransientAutoTuner.tune(bestFrames, baseConfig, profile, autoTuneListener);

        if (result == null || !result.success || result.optimizedConfig == null) {
            throw new IOException("Auto-Tune did not return an optimized configuration.");
        }

        if (result.optimizedConfig.growSigmaMultiplier > result.optimizedConfig.detectionSigmaMultiplier) {
            result.optimizedConfig.growSigmaMultiplier = result.optimizedConfig.detectionSigmaMultiplier;
        }

        System.out.println();
        System.out.printf(Locale.US, "Auto-Tune complete. Detection Sigma=%.2f, Grow Sigma=%.2f, Min Pixels=%d, Max Star Jitter=%.2f%n",
                result.optimizedConfig.detectionSigmaMultiplier,
                result.optimizedConfig.growSigmaMultiplier,
                result.optimizedConfig.minDetectionPixels,
                result.optimizedConfig.maxStarJitter);

        if (result.telemetryReport != null && !result.telemetryReport.isBlank()) {
            System.out.println(result.telemetryReport);
        }

        return result.optimizedConfig;
    }

    private static CliArguments parseArguments(String[] args) {
        if (args.length == 0) {
            return new CliArguments(null, null, null, true);
        }

        List<String> positionalArgs = new ArrayList<>();
        JTransientAutoTuner.AutoTuneProfile autoTuneProfile = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                return new CliArguments(null, null, null, true);
            }

            if (arg.startsWith("--auto-tune=")) {
                autoTuneProfile = parseAutoTuneProfile(arg.substring("--auto-tune=".length()));
                continue;
            }

            if ("--auto-tune".equalsIgnoreCase(arg) || "-a".equalsIgnoreCase(arg)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Missing auto-tune profile after " + arg + ".");
                }
                autoTuneProfile = parseAutoTuneProfile(args[++i]);
                continue;
            }

            positionalArgs.add(arg);
        }

        if (positionalArgs.size() != 2) {
            throw new IllegalArgumentException("Expected exactly 2 positional arguments: <fits_directory> <detection_config.json>.");
        }

        return new CliArguments(new File(positionalArgs.get(0)), new File(positionalArgs.get(1)), autoTuneProfile, false);
    }

    private static JTransientAutoTuner.AutoTuneProfile parseAutoTuneProfile(String value) {
        try {
            return JTransientAutoTuner.AutoTuneProfile.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown auto-tune profile '" + value + "'. Expected conservative, balanced, or aggressive.");
        }
    }

    private static TransientEngineProgressListener createConsoleProgressListener(String prefix) {
        return new TransientEngineProgressListener() {
            private int lastPercent = -1;
            private String lastMessage = "";

            @Override
            public synchronized void onProgressUpdate(int percentage, String message) {
                if (percentage != lastPercent || !Objects.equals(message, lastMessage)) {
                    System.out.printf(Locale.US, "[%s %3d%%] %s%n", prefix, percentage, message);
                    lastPercent = percentage;
                    lastMessage = message;
                }
            }
        };
    }

    private static TransientEngineProgressListener createScaledConsoleProgressListener(String prefix, int startPercent, int endPercent, String label) {
        return new TransientEngineProgressListener() {
            private int lastPercent = -1;
            private String lastMessage = "";

            @Override
            public synchronized void onProgressUpdate(int percentage, String message) {
                int scaledPercent = startPercent + (int) ((percentage / 100.0) * (endPercent - startPercent));
                String displayMessage = label + ": " + message;
                if (scaledPercent != lastPercent || !Objects.equals(displayMessage, lastMessage)) {
                    System.out.printf(Locale.US, "[%s %3d%%] %s%n", prefix, scaledPercent, displayMessage);
                    lastPercent = scaledPercent;
                    lastMessage = displayMessage;
                }
            }
        };
    }

    private static void printUsage() {
        System.out.println("==================================================================");
        System.out.println("  SpacePixels - Batch Detection Tool");
        System.out.println("==================================================================");
        System.out.println("Usage:");
        System.out.println("  java eu.startales.spacepixels.tools.BatchDetectionTool <fits_directory> <detection_config.json> [--auto-tune <conservative|balanced|aggressive>]");
        System.out.println();
        System.out.println("Notes:");
        System.out.println("  - The input directory must contain only uncompressed 16-bit monochrome FITS files with identical dimensions.");
        System.out.println("  - The configuration JSON should be a full DetectionConfig export, such as SpacePixels' detection_config.json.");
        System.out.println("  - When Auto-Tune is enabled, the tuned configuration is used for the pipeline run and exported with the report.");
        System.out.println();
        System.out.println("Gradle example:");
        System.out.println("  gradlew.bat batchDetect -PbatchArgs=\"\\\"C:\\\\astro\\\\sequence\\\" \\\"C:\\\\astro\\\\detection_config.json\\\" --auto-tune balanced\"");
    }
}
