/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */

package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import eu.startales.spacepixels.util.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Command-line entrypoint for headless batch transient detection on monochrome 16-bit FITS sequences.
 */
public class BatchDetectionCli {

    private static class CliArguments {
        private final File inputDir;
        private final File configFile;
        private final JTransientAutoTuner.AutoTuneProfile autoTuneProfile;
        private final boolean showHelp;

        private CliArguments(File inputDir,
                             File configFile,
                             JTransientAutoTuner.AutoTuneProfile autoTuneProfile,
                             boolean showHelp) {
            this.inputDir = inputDir;
            this.configFile = configFile;
            this.autoTuneProfile = autoTuneProfile;
            this.showHelp = showHelp;
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

        SpacePixelsDetectionProfile detectionProfile = loadDetectionConfig(cliArguments.configFile);
        DetectionConfig baseConfig = detectionProfile.getDetectionConfig();
        SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(detectionProfile.getAutoTuneMaxCandidateFrames());
        loadVisualizationPreferences();
        ImageProcessing imageProcessing = ImageProcessing.getInstance(cliArguments.inputDir);
        FitsFileInformation[] filesInfo = imageProcessing.getFitsfileInformationHeadless();

        DetectionConfig effectiveConfig = baseConfig;
        if (cliArguments.autoTuneProfile != null) {
            effectiveConfig = runAutoTune(
                    filesInfo,
                    baseConfig.clone(),
                    detectionProfile.getAutoTuneMaxCandidateFrames(),
                    cliArguments.autoTuneProfile);
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

    private static SpacePixelsDetectionProfile loadDetectionConfig(File configFile) throws IOException {
        try (FileReader reader = new FileReader(configFile)) {
            return SpacePixelsDetectionProfileIO.load(reader);
        }
    }

    private static void loadVisualizationPreferences() {
        File visualizationPreferencesFile = new File(System.getProperty("user.home"), SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
        if (!visualizationPreferencesFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(visualizationPreferencesFile)) {
            SpacePixelsVisualizationPreferences preferences = SpacePixelsVisualizationPreferencesIO.load(reader);
            preferences.applyToRuntime();
        } catch (IOException e) {
            System.err.println("Failed to load visualization preferences: " + e.getMessage());
        }
    }

    private static DetectionConfig runAutoTune(FitsFileInformation[] filesInfo,
                                               DetectionConfig baseConfig,
                                               int autoTuneMaxCandidateFrames,
                                               JTransientAutoTuner.AutoTuneProfile profile) throws Exception {
        if (filesInfo.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            throw new IOException("Auto-Tune requires at least " + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES +
                    " frames, but only " + filesInfo.length + " were available.");
        }

        System.out.println();
        System.out.println("Running Auto-Tune with profile: " + profile.name().toLowerCase(Locale.ROOT));
        System.out.println("Building candidate pool with up to " + autoTuneMaxCandidateFrames + " frames.");

        List<io.github.ppissias.jtransient.engine.ImageFrame> candidateFrames = AutoTuneCandidatePoolBuilder.buildCandidatePool(
                filesInfo,
                baseConfig,
                autoTuneMaxCandidateFrames,
                (percentage, message) -> System.out.printf(Locale.US, "[Auto-Tune %3d%%] %s%n", percentage, message));

        TransientEngineProgressListener autoTuneListener = createScaledConsoleProgressListener("Auto-Tune", 50, 100, "Tuning");
        int originalSampleSize = JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE;
        int effectiveSampleSize = Math.min(originalSampleSize, candidateFrames.size());
        if (effectiveSampleSize != originalSampleSize) {
            System.out.println("Temporarily lowering Auto-Tuner sample size from " + originalSampleSize + " to " + effectiveSampleSize + " to support the available frame count.");
        }

        JTransientAutoTuner.AutoTunerResult result;
        JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = effectiveSampleSize;
        try {
            result = JTransientAutoTuner.tune(candidateFrames, baseConfig, profile, autoTuneListener);
        } finally {
            JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE = originalSampleSize;
        }

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

        return new CliArguments(
                new File(positionalArgs.get(0)),
                new File(positionalArgs.get(1)),
                autoTuneProfile,
                false);
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
        System.out.println("  SpacePixels - Batch Detection CLI");
        System.out.println("==================================================================");
        System.out.println("Usage:");
        System.out.println("  java eu.startales.spacepixels.tools.BatchDetectionCli <fits_directory> <detection_config.json> [--auto-tune <conservative|balanced|aggressive>]");
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
