/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.util.reporting.ExportVisualizationSettings;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;
import eu.startales.spacepixels.util.reporting.LiveFindingPictures;
import eu.startales.spacepixels.util.reporting.LiveFindingPictures.Spot;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SlowMoverCandidateResult;
import io.github.ppissias.jtransient.engine.PipelineStage;
import io.github.ppissias.jtransient.engine.PipelineStageObserver;
import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shows what a running detection has found so far in the progress dialog's live view (F10 slice 2). JTransient calls
 * it as each stage finishes; on the engine's thread it only copies the few facts a picture needs, and its own worker
 * renders the crops. Without a live view (headless and API runs) there is no instance, so the engine does no extra
 * work, and while the picture is hidden nothing is copied or rendered. A picture that fails is skipped; the observer
 * never fails the run; it delays it only by copying, and at the end by at most {@link #FINISH_SECONDS} for the
 * pictures still being drawn.
 */
public final class LiveFindings implements PipelineStageObserver, AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(LiveFindings.class.getName());
    /** At most this many findings of one stage are shown in turn. */
    static final int MAX_SLIDES = 8;
    /** How long the end of the engine call waits for the pictures already handed to the worker. */
    static final int FINISH_SECONDS = 2;
    private static final String PROVISIONAL = "provisional";

    private final ExportVisualizationSettings settings;
    private final RunPreviewSink sink;
    private final List<short[][]> frames;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SpacePixels live view");
        thread.setDaemon(true);
        return thread;
    });
    /** The median reference, set when its stage finishes; the engine does not change it afterwards. */
    private short[][] reference;

    /** Captures default rendering once for direct component callers. */
    LiveFindings(RunPreviewSink sink, List<short[][]> frames) {
        this(sink, frames, DetectionReportGenerator.captureExportSettings(
                VariableStarReportOptions.DEFAULT));
    }

    /** Borrows read-only frames and retains the admitted run's immutable rendering choices. */
    private LiveFindings(RunPreviewSink sink, List<short[][]> frames,
            ExportVisualizationSettings settings) {
        this.settings = settings;
        this.sink = sink;
        this.frames = frames;
    }

    /**
     * The observer for this thread's live view, or null when none is open, so the engine runs without one.
     *
     * @param frames the run's frames, by sequence index; they are only read
     */
    public static LiveFindings forCurrentRun(List<short[][]> frames) {
        return forCurrentRun(frames, DetectionReportGenerator.captureExportSettings(
                eu.startales.spacepixels.config.VariableStarReportOptions.DEFAULT));
    }

    /** Creates an observer only for a live view, using the captured run settings for every asynchronous picture. */
    public static LiveFindings forCurrentRun(List<short[][]> frames,
            eu.startales.spacepixels.util.reporting.ExportVisualizationSettings settings) {
        RunPreviewSink sink = RunPreviewScope.currentSink();
        return sink == null ? null : new LiveFindings(sink, frames, settings);
    }

    @Override
    public void stageFinished(PipelineStage stage) {
        if (stage instanceof PipelineStage.MasterReference masterReference) {
            reference = masterReference.medianStack();
        }
        if (stage instanceof PipelineStage.CommonMotion || !sink.wantsPreview(true)) {
            return;
        }
        Supplier<RunPreview> picture = switch (stage) {
            case PipelineStage.MasterReference masterReference -> reference(masterReference);
            case PipelineStage.SlowMovers slowMovers -> slowMovers(slowMovers);
            case PipelineStage.Tracking tracking -> tracking(tracking);
            case PipelineStage.CommonMotion commonMotion -> null;
            case PipelineStage.ResidualAnalysis residual -> residual(residual);
            case PipelineStage.Photometry photometry -> photometry(photometry);
        };
        if (picture == null) {
            return;
        }
        try {
            worker.execute(() -> render(picture));
        } catch (RejectedExecutionException closed) {
            // The run has ended; the picture is no longer wanted.
        }
    }

    private void render(Supplier<RunPreview> picture) {
        try {
            RunPreview preview = picture.get();
            if (preview != null) {
                sink.preview(preview);
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.FINE, "Skipped a live-view picture", failure);
        }
    }

    private Supplier<RunPreview> reference(PipelineStage.MasterReference stage) {
        short[][] median = stage.medianStack();
        int stars = stage.masterStars().size();
        if (median == null || median.length == 0) {
            return null;
        }
        return () -> RunPreview.of(PreviewThumbnails.frame(median, settings.getAutoStretchBlackSigma(), settings.getAutoStretchWhiteSigma()),
                "Stationary-sky reference: the median of the kept frames · " + count(stars, "stationary star", "stationary stars"));
    }

    private Supplier<RunPreview> slowMovers(PipelineStage.SlowMovers stage) {
        if (!stage.enabled()) {
            return null;
        }
        List<SlowMoverCandidateResult> candidates = stage.candidates();
        if (candidates.isEmpty()) {
            return () -> RunPreview.captionOnly("No slow-mover candidates in the maximum stack · picture from the previous stage");
        }
        short[][] maximumStack = stage.maximumStack();
        String total = count(candidates.size(), "slow-mover candidate", "slow-mover candidates") + " so far";
        List<Spot> spots = new ArrayList<>();
        for (SlowMoverCandidateResult candidate : candidates.subList(0, Math.min(MAX_SLIDES, candidates.size()))) {
            spots.add(Spot.of(candidate.object));
        }
        return () -> {
            List<RunPreview> slides = new ArrayList<>();
            for (Spot spot : spots) {
                slides.add(RunPreview.of(LiveFindingPictures.crop(maximumStack, spot, LiveFindingPictures.SLOW_MOVER, null, settings),
                        "Slow-mover candidate · maximum stack · " + PROVISIONAL + "\n" + total));
            }
            return RunPreview.slides(slides);
        };
    }

    private Supplier<RunPreview> tracking(PipelineStage.Tracking stage) {
        List<Track> pointTracks = new ArrayList<>();
        List<Track> streakTracks = new ArrayList<>();
        for (Track track : stage.tracks()) {
            (track.isStreakTrack ? streakTracks : pointTracks).add(track);
        }
        Comparator<Track> longestFirst = Comparator.comparingInt((Track track) -> track.points.size()).reversed();
        pointTracks.sort(longestFirst);
        streakTracks.sort(longestFirst);
        List<AnomalyDetection> anomalies = new ArrayList<>(stage.anomalies());
        anomalies.sort(Comparator.comparingDouble((AnomalyDetection anomaly) -> anomaly.object.peakSigma).reversed());
        String total = count(pointTracks.size(), "moving object", "moving objects") + ", "
                + count(streakTracks.size(), "streak", "streaks") + ", "
                + count(anomalies.size(), "anomaly", "anomalies") + " so far";
        // The reference and the mask are final: the engine changes neither after this stage.
        short[][] median = reference;
        boolean[][] starMask = stage.starMask();
        boolean hasMask = median != null && median.length > 0 && starMask != null && starMask.length == median.length;
        if (pointTracks.isEmpty() && streakTracks.isEmpty() && anomalies.isEmpty()) {
            String none = "No moving objects, streaks or anomalies found";
            return hasMask ? () -> RunPreview.slides(List.of(starMask(median, starMask, none)))
                    : () -> RunPreview.captionOnly(none + " · picture from the previous stage");
        }

        // Copied now: the pictures keep positions and frame numbers, never the engine's objects.
        List<Finding> findings = new ArrayList<>();
        for (Track track : pointTracks.subList(0, Math.min(3, pointTracks.size()))) {
            List<Spot> spots = List.of(Spot.of(track.points.get(0)), Spot.of(track.points.get(track.points.size() / 2)),
                    Spot.of(track.points.get(track.points.size() - 1)));
            findings.add(new Finding(spots, LiveFindingPictures.MOVING_OBJECT, "Moving-object candidate · "
                    + count(track.points.size(), "frame", "frames") + " · " + PROVISIONAL));
        }
        for (Track track : streakTracks.subList(0, Math.min(2, streakTracks.size()))) {
            Spot spot = Spot.of(track.points.get(0));
            findings.add(new Finding(List.of(spot), LiveFindingPictures.STREAK, "Streak · "
                    + (track.points.size() > 1 ? count(track.points.size(), "piece", "pieces") + " from frame " : "frame ")
                    + (spot.frame() + 1) + " · " + PROVISIONAL));
        }
        int anomalySlides = Math.min(MAX_SLIDES - findings.size() - (hasMask ? 1 : 0), anomalies.size());
        for (AnomalyDetection anomaly : anomalies.subList(0, anomalySlides)) {
            Spot spot = Spot.of(anomaly.object);
            findings.add(new Finding(List.of(spot), LiveFindingPictures.ANOMALY, (anomaly.type == AnomalyType.INTEGRATED_SIGMA
                    ? "Anomaly (broad flash)" : "Anomaly (sharp glint)") + " · frame " + (spot.frame() + 1) + " · " + PROVISIONAL));
        }
        return () -> {
            List<RunPreview> slides = new ArrayList<>();
            if (hasMask) {
                slides.add(starMask(median, starMask, total));
            }
            for (Finding finding : findings) {
                BufferedImage image = finding.spots().size() > 1
                        ? LiveFindingPictures.strip(frames, finding.spots(), finding.ring(), settings)
                        : LiveFindingPictures.crop(frames.get(finding.spots().get(0).frame()), finding.spots().get(0),
                        finding.ring(), "Frame " + (finding.spots().get(0).frame() + 1), settings);
                slides.add(RunPreview.of(PreviewThumbnails.fit(image), finding.caption() + "\n" + total));
            }
            return RunPreview.slides(slides);
        };
    }

    /** The reference with the stars masked before linking tinted, and how much of the field they cover. */
    private RunPreview starMask(short[][] median, boolean[][] mask, String findings) {
        long masked = 0;
        for (boolean[] row : mask) {
            for (boolean pixel : row) {
                if (pixel) {
                    masked++;
                }
            }
        }
        double share = 100.0 * masked / ((long) mask.length * mask[0].length);
        return RunPreview.of(LiveFindingPictures.withStarMask(PreviewThumbnails.frame(median, settings.getAutoStretchBlackSigma(), settings.getAutoStretchWhiteSigma()), mask),
                String.format(Locale.US, "Star mask: stationary stars cover %.1f %% of the field, masked before linking", share)
                        + "\n" + findings);
    }

    private Supplier<RunPreview> residual(PipelineStage.ResidualAnalysis stage) {
        List<ResidualTransientAnalysis.LocalRescueCandidate> candidates = stage.analysis().localRescueCandidates;
        if (!stage.enabled() || candidates.isEmpty()) {
            return null;
        }
        String total = count(candidates.size(), "local rescue candidate", "local rescue candidates") + " so far";
        List<Finding> findings = new ArrayList<>();
        for (ResidualTransientAnalysis.LocalRescueCandidate candidate : candidates.subList(0, Math.min(3, candidates.size()))) {
            if (candidate.points.isEmpty()) {
                continue;
            }
            Spot spot = Spot.of(candidate.points.get(0));
            findings.add(new Finding(List.of(spot), LiveFindingPictures.ANOMALY, "Local rescue candidate · "
                    + count(candidate.points.size(), "detection", "detections") + " · " + PROVISIONAL));
        }
        if (findings.isEmpty()) {
            return null;
        }
        return () -> {
            List<RunPreview> slides = new ArrayList<>();
            for (Finding finding : findings) {
                Spot spot = finding.spots().get(0);
                slides.add(RunPreview.of(LiveFindingPictures.crop(frames.get(spot.frame()), spot, finding.ring(),
                        "Frame " + (spot.frame() + 1), settings), finding.caption() + "\n" + total));
            }
            return RunPreview.slides(slides);
        };
    }

    private Supplier<RunPreview> photometry(PipelineStage.Photometry stage) {
        if (!stage.enabled()) {
            return null;
        }
        // As in the report and the viewers: high-confidence and possible candidates, not the rejected ones.
        List<StarLightCurve> candidates = new ArrayList<>();
        for (VariabilityTier tier : List.of(VariabilityTier.HIGH_CONFIDENCE, VariabilityTier.POSSIBLE)) {
            for (StarLightCurve star : stage.analysis().candidates) {
                if (star.tier == tier) {
                    candidates.add(star);
                }
            }
        }
        short[][] median = reference;
        if (candidates.isEmpty() || median == null) {
            return () -> RunPreview.captionOnly("No variable-star candidates · picture from the previous stage");
        }
        String total = count(candidates.size(), "variable-star candidate", "variable-star candidates") + " so far";
        List<Spot> spots = new ArrayList<>();
        List<double[]> curves = new ArrayList<>();
        List<String> captions = new ArrayList<>();
        for (StarLightCurve star : candidates.subList(0, Math.min(MAX_SLIDES, candidates.size()))) {
            spots.add(new Spot(star.x, star.y, 4, -1));
            curves.add(star.deltaMag == null ? new double[0] : star.deltaMag.clone());
            captions.add("Variable-star candidate · " + star.tier.name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " · " + PROVISIONAL);
        }
        return () -> {
            List<RunPreview> slides = new ArrayList<>();
            for (int index = 0; index < spots.size(); index++) {
                BufferedImage crop = LiveFindingPictures.crop(median, spots.get(index), LiveFindingPictures.VARIABLE_STAR, null, settings);
                slides.add(RunPreview.of(PreviewThumbnails.fit(LiveFindingPictures.withLightCurve(crop, curves.get(index))),
                        captions.get(index) + "\n" + total));
            }
            return RunPreview.slides(slides);
        };
    }

    private static String count(int count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    /** Lets the worker finish the pictures already handed over, then stops it; for tests. */
    void finishPictures() throws InterruptedException {
        worker.shutdown();
        worker.awaitTermination(30, TimeUnit.SECONDS);
    }

    /**
     * Called when the engine returns: lets the worker finish the pictures already handed over, so the last stages are
     * shown before the report's pictures, for at most {@link #FINISH_SECONDS}, then stops it and drops the rest.
     */
    @Override
    public void close() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(FINISH_SECONDS, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        reference = null;
    }

    /** One finding to draw: where it is, in which colour, and its caption. */
    private record Finding(List<Spot> spots, Color ring, String caption) {
    }
}
