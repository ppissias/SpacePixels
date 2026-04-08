/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */

package eu.startales.spacepixels.tools;

import eu.startales.spacepixels.api.DefaultSpacePixelsPipelineApi;
import eu.startales.spacepixels.api.InputPreparationMode;
import eu.startales.spacepixels.api.SpacePixelsPipelineApi;
import eu.startales.spacepixels.api.SpacePixelsPipelineRequest;
import eu.startales.spacepixels.api.SpacePixelsPipelineResult;
import eu.startales.spacepixels.api.SpacePixelsProgressListener;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintStream;
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
        int exitCode = execute(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");

        System.setProperty("java.awt.headless", "true");

        int exitCode = 0;
        try {
            CliArguments cliArguments = parseArguments(args);
            if (cliArguments.showHelp) {
                printUsage(out);
                return 0;
            }

            run(cliArguments, out, err);
        } catch (IllegalArgumentException e) {
            err.println("Argument error: " + e.getMessage());
            printUsage(out);
            exitCode = 2;
        } catch (Exception e) {
            err.println("Batch detection failed: " + e.getMessage());
            e.printStackTrace(err);
            exitCode = 1;
        }
        return exitCode;
    }

    private static void run(CliArguments cliArguments, PrintStream out, PrintStream err) throws Exception {
        if (!cliArguments.inputDir.exists() || !cliArguments.inputDir.isDirectory()) {
            throw new IOException("Input path is not a directory: " + cliArguments.inputDir.getAbsolutePath());
        }
        if (!cliArguments.configFile.exists() || !cliArguments.configFile.isFile()) {
            throw new IOException("Configuration JSON file not found: " + cliArguments.configFile.getAbsolutePath());
        }

        SpacePixelsDetectionProfile detectionProfile = loadDetectionConfig(cliArguments.configFile);
        DetectionConfig baseConfig = detectionProfile.getDetectionConfig();
        SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(detectionProfile.getAutoTuneMaxCandidateFrames());
        loadVisualizationPreferences(err);
        SpacePixelsPipelineApi pipelineApi = new DefaultSpacePixelsPipelineApi();
        SpacePixelsPipelineRequest request = SpacePixelsPipelineRequest.builder(cliArguments.inputDir)
                .detectionConfig(baseConfig)
                .autoTuneProfile(cliArguments.autoTuneProfile)
                .autoTuneMaxCandidateFrames(detectionProfile.getAutoTuneMaxCandidateFrames())
                .inputPreparationMode(InputPreparationMode.FAIL_IF_NOT_READY)
                .generateReport(true)
                .progressListener(createConsoleProgressListener("Pipeline", out))
                .build();

        SpacePixelsPipelineResult pipelineResult = pipelineApi.run(request);
        File reportFile = pipelineResult.getReportFile();
        if (reportFile == null) {
            throw new IOException("Pipeline completed without producing an export report.");
        }

        out.println();
        out.println("Batch detection completed successfully.");
        out.println("Export directory: " + reportFile.getParentFile().getAbsolutePath());
        out.println("Report file: " + reportFile.getAbsolutePath());
    }

    private static SpacePixelsDetectionProfile loadDetectionConfig(File configFile) throws IOException {
        try (FileReader reader = new FileReader(configFile)) {
            return SpacePixelsDetectionProfileIO.load(reader);
        }
    }

    private static void loadVisualizationPreferences(PrintStream err) {
        File visualizationPreferencesFile = new File(System.getProperty("user.home"), SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);
        if (!visualizationPreferencesFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(visualizationPreferencesFile)) {
            SpacePixelsVisualizationPreferences preferences = SpacePixelsVisualizationPreferencesIO.load(reader);
            preferences.applyToRuntime();
        } catch (IOException e) {
            err.println("Failed to load visualization preferences: " + e.getMessage());
        }
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

    private static SpacePixelsProgressListener createConsoleProgressListener(String prefix, PrintStream out) {
        return new SpacePixelsProgressListener() {
            private int lastPercent = -1;
            private String lastMessage = "";

            @Override
            public synchronized void onProgress(int percentage, String message) {
                if (percentage != lastPercent || !Objects.equals(message, lastMessage)) {
                    out.printf(Locale.US, "[%s %3d%%] %s%n", prefix, percentage, message);
                    lastPercent = percentage;
                    lastMessage = message;
                }
            }
        };
    }

    private static void printUsage(PrintStream out) {
        out.println("==================================================================");
        out.println("  SpacePixels - Batch Detection CLI");
        out.println("==================================================================");
        out.println("Usage:");
        out.println("  java eu.startales.spacepixels.tools.BatchDetectionCli <fits_directory> <detection_config.json> [--auto-tune <conservative|balanced|aggressive>]");
        out.println();
        out.println("Notes:");
        out.println("  - The input directory must contain only uncompressed 16-bit monochrome FITS files with identical dimensions.");
        out.println("  - The configuration JSON should be a SpacePixels detection profile JSON (flat DetectionConfig fields plus autoTuneMaxCandidateFrames).");
        out.println("  - Packaged distributions include config/default_detection_profile.json as a starting point.");
        out.println("  - When Auto-Tune is enabled, the tuned configuration is used for the pipeline run and exported with the report.");
        out.println();
        out.println("Packaged launcher example:");
        out.println("  batchDetect.bat \"C:\\astro\\sequence\" \"config\\default_detection_profile.json\" --auto-tune aggressive");
        out.println();
        out.println("Gradle example:");
        out.println("  gradlew.bat batchDetect -PbatchArgs=\"\\\"C:\\\\astro\\\\sequence\\\" \\\"src\\\\dist\\\\config\\\\default_detection_profile.json\\\" --auto-tune aggressive\"");
    }
}
