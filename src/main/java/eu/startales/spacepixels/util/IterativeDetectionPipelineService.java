package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.session.SessionFrames;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import eu.startales.spacepixels.util.reporting.ExportVisualizationSettings;
import eu.startales.spacepixels.util.reporting.IterativeDetectionIndex;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class IterativeDetectionPipelineService {
    @FunctionalInterface
    public interface ReportPrompt {
        boolean confirm(ImageProcessing.DetectionSummary summary, FitsFileInformation[] files, DetectionConfig config);
    }

    @FunctionalInterface
    interface PassRunner {
        StandardDetectionPipelineService.RunResult run(DetectionConfig config, FitsFileInformation[] files,
                AppConfig appConfig, ImageProcessing.DetectionSafetyPrompt prompt,
                TransientEngineProgressListener progress, File output, ExportVisualizationSettings visualization) throws Exception;
    }

    @FunctionalInterface
    interface Tuner {
        Tuned run(FitsFileInformation[] files, DetectionConfig config, int limit,
                  JTransientAutoTuner.AutoTuneProfile profile, TransientEngineProgressListener progress) throws Exception;
    }

    private static final Logger LOGGER = Logger.getLogger(IterativeDetectionPipelineService.class.getName());
    private final AppConfig legacyAppConfig;
    private final PassRunner passRunner;
    private final Tuner tuner;
    private volatile File indexFile;

    public IterativeDetectionPipelineService() { this(null, IterativeDetectionPipelineService::standardPass); }
    IterativeDetectionPipelineService(AppConfig appConfig) { this(appConfig, IterativeDetectionPipelineService::standardPass); }
    IterativeDetectionPipelineService(AppConfig appConfig, PassRunner passRunner) {
        this(appConfig, passRunner, IterativeDetectionPipelineService::tune);
    }
    IterativeDetectionPipelineService(AppConfig appConfig, PassRunner passRunner, Tuner tuner) {
        this.legacyAppConfig = appConfig;
        this.passRunner = passRunner;
        this.tuner = tuner;
    }

    public File getIndexFile() { return indexFile; }

    public static int countInputFiles(File directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("Choose an existing input directory.");
        FitsSequenceValidator.validateDirectoryExtensions(directory);
        File[] files = directory.listFiles(file -> file.isFile() && FitsSequenceValidator.imageExtension(file.getName()) != null);
        if (files == null) throw new IOException("Could not list input directory: " + directory);
        return files.length;
    }

    public File run(IterativeDetectionRequest request, ReportPrompt prompt,
                    TransientEngineProgressListener progress) throws Exception {
        indexFile = null;
        emit(progress, 0, "Preparing iterative input...");
        File prepared = DetectionInputPreparation.prepareInputDirectory(request.directory(), request.autoPrepare(),
                request.keepNegativeValues(), (percent, message) -> emit(progress, percent / 10, message)).getPreparedInputDirectory();
        FitsFileInformation[] files;
        try (FitsImportService importer = new FitsImportService(prepared, new SessionFrames(), null, null)) {
            files = importer.getFitsfileInformationHeadless();
        }
        if (request.expectedFrameCount() > 0 && files.length != request.expectedFrameCount()) {
            throw new IOException("The dataset frame count changed. Reopen the iterative configuration to review the passes.");
        }
        List<Integer> counts = request.plan().frameCounts(files.length);
        DetectionConfig config = request.config();
        String tuneReport = null;
        int passStart = 10;
        int tuneLimit = Math.min(request.autoTuneFrames(), counts.getLast());
        if (request.autoTuneProfile() != null) {
            Tuned tuned = tuner.run(files, config, tuneLimit, request.autoTuneProfile(), progress);
            config = tuned.config();
            tuneReport = tuned.telemetry();
            passStart = 30;
        }
        config = DetectionConfigRules.snapshot(config, DetectionConfigRules.Operation.CONFIGURATION);
        return runPasses(config, files, request.appConfig(), request.visualization(), request.plan(),
                request.directory(), prepared, request.autoTuneProfile(), tuneLimit, tuneReport, prompt, progress, passStart);
    }

    File detectSlowObjectsIterative(DetectionConfig config, FitsFileInformation[] files,
                                   ImageProcessing.DetectionSafetyPrompt prompt,
                                   TransientEngineProgressListener progress, int maximumFrames) throws Exception {
        indexFile = null;
        AppConfig appConfig = legacyAppConfig.copy();
        if (files.length < 5) {
            return new StandardDetectionPipelineService(appConfig, new SessionFrames()).detectObjects(config, files, prompt, progress);
        }
        File directory = new File(files[0].getFilePath()).getParentFile();
        IterativeDetectionPlan plan = new IterativeDetectionPlan(5, 5, maximumFrames > 0 ? Math.max(5, maximumFrames) : 0);
        return runPasses(config.clone(), files, appConfig,
                DetectionReportGenerator.captureExportSettings(appConfig.variableStarReportOptions), plan,
                directory, directory, null, 0, null,
                prompt == null ? null : (summary, subset, settings) -> prompt.shouldProceed(summary), progress, 0);
    }

    static List<Integer> sampleFrameIndices(long[] times, boolean timed, int availableFrames, int count) {
        return IterativeDetectionPlan.sampleIndices(times, timed, availableFrames, count);
    }

    private File runPasses(DetectionConfig config, FitsFileInformation[] files, AppConfig appConfig,
                           ExportVisualizationSettings visualization, IterativeDetectionPlan plan,
                           File source, File prepared, JTransientAutoTuner.AutoTuneProfile tuneProfile,
                           int tuneLimit, String tuneReport, ReportPrompt prompt,
                           TransientEngineProgressListener progress, int passStart) throws Exception {
        List<Integer> sizes = plan.frameCounts(files.length);
        long[] timestamps = Arrays.stream(files).mapToLong(FitsFileInformation::getObservationTimestamp).toArray();
        boolean timed = Arrays.stream(timestamps).allMatch(timestamp -> timestamp >= 0);
        List<IterativeDetectionIndex.Pass> passes = new ArrayList<>();
        for (int count : sizes) {
            passes.add(new IterativeDetectionIndex.Pass(count, "NOT_RUN", null, null,
                    frames(new FitsFileInformation[]{files[0], files[files.length - 1]}), Map.of(), null));
        }
        File root = OutputFolders.createNew(source, "iterative_detections_");
        if (tuneReport != null) {
            try (StagedFile staged = new StagedFile(new File(root, "auto_tune.txt"))) {
                staged.writeString(tuneReport);
                staged.publish();
            }
        }
        publish(root, source, prepared, plan, tuneProfile, tuneLimit, config, visualization, "RUNNING", passes);
        for (int passIndex = 0; passIndex < sizes.size(); passIndex++) {
            int count = sizes.get(passIndex);
            FitsFileInformation[] subset = sampleFrameIndices(timestamps, timed, files.length, count).stream()
                    .map(index -> files[index]).toArray(FitsFileInformation[]::new);
            File output = new File(root, subset.length + "_frames");
            int base = passStart + (int) ((99L - passStart) * passIndex / sizes.size());
            int end = passStart + (int) ((99L - passStart) * (passIndex + 1) / sizes.size());
            TransientEngineProgressListener passProgress = (percent, message) -> {
                if (percent < 100) emit(progress, base + (end - base) * percent / 100,
                        "Pass " + subset.length + " frames: " + message);
            };
            String inputManifest = null;
            try {
                CancellationScope.check();
                inputManifest = IterativeDetectionIndex.writeInputs(root, subset.length, frames(subset));
                passes.set(passIndex, pass(subset, "RUNNING", null, inputManifest, Map.of(), null));
                publish(root, source, prepared, plan, tuneProfile, tuneLimit, config, visualization, "RUNNING", passes);
                DetectionConfig passConfig = config.clone();
                StandardDetectionPipelineService.RunResult result = passRunner.run(passConfig, subset, appConfig,
                        prompt == null ? null : summary -> prompt.confirm(summary, subset, passConfig), passProgress, output, visualization);
                passes.set(passIndex, pass(subset, result.report() == null ? "REPORT_DECLINED" : "COMPLETED",
                        result.report() == null ? null : subset.length + "_frames/" + result.report().getName(),
                        inputManifest, counts(result.summary()), result.report() == null ? "Detection completed; report declined."
                                : result.summary().photometryVerdict == null ? null : "Variable-star analysis: "
                                        + result.summary().photometryVerdict.toLowerCase(java.util.Locale.ROOT).replace('_', ' ')));
            } catch (Exception failure) {
                if (Cancellation.isCancellation(failure)) {
                    OutputFolders.deleteUnfinished(output);
                    passes.set(passIndex, pass(subset, "CANCELLED", null, inputManifest, Map.of(), "The run was cancelled."));
                    boolean finished = passes.stream().anyMatch(item -> List.of("COMPLETED", "REPORT_DECLINED", "FAILED").contains(item.outcome()));
                    if (finished) publish(root, source, prepared, plan, tuneProfile, tuneLimit, config, visualization, "CANCELLED", passes);
                    else {
                        OutputFolders.deleteUnfinished(root);
                        indexFile = null;
                    }
                    throw failure;
                }
                LOGGER.log(Level.WARNING, "Iterative pass " + subset.length + " failed", failure);
                passes.set(passIndex, pass(subset, "FAILED", null, inputManifest, Map.of(),
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()));
            }
            publish(root, source, prepared, plan, tuneProfile, tuneLimit, config, visualization, "RUNNING", passes);
        }
        String outcome = passes.stream().allMatch(item -> item.outcome().equals("COMPLETED")) ? "COMPLETED" : "FINISHED_WITH_ISSUES";
        publish(root, source, prepared, plan, tuneProfile, tuneLimit, config, visualization, outcome, passes);
        emit(progress, 100, "Iterative detection finished.");
        return indexFile;
    }

    private void publish(File root, File source, File prepared, IterativeDetectionPlan plan,
                         JTransientAutoTuner.AutoTuneProfile tuneProfile, int tuneLimit, DetectionConfig config,
                         ExportVisualizationSettings visualization, String outcome,
                         List<IterativeDetectionIndex.Pass> passes) throws IOException {
        IterativeDetectionIndex.write(root, new IterativeDetectionIndex.Run(1, source.getName(), prepared.getName(),
                plan, tuneProfile == null ? null : tuneProfile.name(), tuneProfile == null ? 0 : tuneLimit,
                config, visualization, outcome, List.copyOf(passes)));
        indexFile = new File(root, "index.html");
    }

    private static IterativeDetectionIndex.Pass pass(FitsFileInformation[] files, String outcome,
                                                      String report, String inputManifest, Map<String, Integer> counts, String detail) {
        return new IterativeDetectionIndex.Pass(files.length, outcome, report, inputManifest,
                frames(new FitsFileInformation[]{files[0], files[files.length - 1]}), counts, detail);
    }

    private static List<IterativeDetectionIndex.Frame> frames(FitsFileInformation[] files) {
        return Arrays.stream(files).map(file -> new IterativeDetectionIndex.Frame(
                file.getFileName(), file.getObservationTimestamp(), file.getExposureDurationMillis(),
                file.getSizeWidth(), file.getSizeHeight())).toList();
    }

    private static Map<String, Integer> counts(ImageProcessing.DetectionSummary summary) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("Moving targets", summary.movingTargets);
        counts.put("Streak tracks", summary.streakTracks);
        counts.put("Single streaks", summary.singleStreaks);
        counts.put("Anomalies", summary.anomalies);
        counts.put("Slow movers", summary.slowMoverCandidates);
        counts.put("Local rescue candidates", summary.localRescueCandidates);
        counts.put("Local activity clusters", summary.localActivityClusters);
        counts.put("Common-motion groups", summary.commonMotionGroups);
        counts.put("Camera-fixed groups", summary.cameraFixedGroups);
        counts.put("Unclassified detections", summary.unclassifiedDetections);
        counts.put("Variable-star candidates", summary.variableStarCandidates);
        counts.put("Possible variables", summary.possibleVariableStars);
        return counts;
    }

    private static StandardDetectionPipelineService.RunResult standardPass(DetectionConfig config,
            FitsFileInformation[] files, AppConfig appConfig, ImageProcessing.DetectionSafetyPrompt prompt,
            TransientEngineProgressListener progress, File output, ExportVisualizationSettings visualization) throws Exception {
        SessionFrames state = new SessionFrames();
        try {
            return new StandardDetectionPipelineService(appConfig, state).run(config, files, prompt, progress, output, visualization);
        } finally {
            state.clear();
        }
    }

    record Tuned(DetectionConfig config, String telemetry) {}

    private static Tuned tune(FitsFileInformation[] files, DetectionConfig config, int limit,
                              JTransientAutoTuner.AutoTuneProfile profile, TransientEngineProgressListener progress) throws Exception {
        List<ImageFrame> candidates = AutoTuneCandidatePoolBuilder.buildCandidatePool(files, config, limit,
                (percent, message) -> emit(progress, 10 + percent / 10, "Auto-Tune: " + message));
        try {
            JTransientAutoTuner.AutoTunerResult result = AutoTunerRunner.run(candidates, config.clone(), profile,
                    AutoTunerRunner.DEFAULT_ALGORITHM, (percent, message) -> emit(progress, 20 + percent / 10, "Auto-Tune: " + message));
            if (result == null || !result.success || result.optimizedConfig == null) {
                throw new IOException("Auto-Tune did not produce usable settings. Import the dataset normally to experiment with settings."
                        + (result == null ? "" : "\n" + result.telemetryReport));
            }
            return new Tuned(result.optimizedConfig.clone(), result.telemetryReport);
        } finally {
            candidates.clear();
        }
    }

    private static void emit(TransientEngineProgressListener progress, int percent, String message) {
        if (progress != null) progress.onProgressUpdate(percent, message);
    }
}
