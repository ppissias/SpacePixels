/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.engine.AutoTuneSnapshot;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;

/**
 * Builds the deterministic frame pool that SpacePixels passes to
 * {@code CalibratedAutoTuner.tune(...)}.
 * <p>
 * SpacePixels does not pick the final internal tuning sample. It only provides a broader candidate
 * pool, using frame quality plus sequence coverage, and leaves the final sampling to JTransient.
 * <p>
 * Frame quality comes from the engine's quality control over the whole session
 * ({@link QualityControlService}), the measurement detection uses: frames it rejects are not candidates, and the
 * others are ranked by its noise and star size.
 */
public final class AutoTuneCandidatePoolBuilder {

    private static final double[] CANDIDATE_POOL_WEIGHTS = {0.4d, 0.4d, 0.2d};

    private AutoTuneCandidatePoolBuilder() {
    }

    /** Copies the metadata array so an admitted job keeps the sequence captured when it started. */
    public static FitsFileInformation[] inputFrames(FitsFileInformation[] imported) {
        return imported == null ? null : imported.clone();
    }

    /**
     * Progress callback for the pre-tuning candidate-pool preparation stage.
     */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(int percentage, String message);
    }

    /**
     * The frames Auto-Tune measures on. {@code qualityControlled} says that quality control over the whole session
     * already left its rejected frames out, so the tuner must not select again within this subset; otherwise the
     * pool is the whole session and the tuner selects, as detection does.
     */
    public record CandidatePool(List<ImageFrame> frames, boolean qualityControlled) { }

    /**
     * Minimal per-frame record used during the quality-only selection pass so the full pixel data
     * does not need to stay resident in memory for every input frame.
     */
    static final class FrameQualityRecord {
        private final int sequenceIndex;
        private final double score;

        FrameQualityRecord(int sequenceIndex, double score) {
            this.sequenceIndex = sequenceIndex;
            this.score = score;
        }
    }

    /**
     * Produces the ordered candidate frames for auto-tuning.
     * <p>
     * If the sequence is already within the configured limit, all frames are used; the tuner then leaves out the
     * ones quality control rejects. Otherwise quality control runs over the whole session, its rejected frames are
     * left out, and the pool is built from the kept frames: roughly 40% best-quality frames, 40% median-quality
     * frames, and 20% evenly spaced sequence coverage, with duplicates removed and the final output sorted by
     * {@link ImageFrame#sequenceIndex}.
     */
    public static List<ImageFrame> buildCandidatePool(FitsFileInformation[] filesInfo,
                                                      DetectionConfig baseConfig,
                                                      int autoTuneMaxCandidateFrames,
                                                      ProgressListener progressListener) throws Exception {
        return buildCandidatePool(filesInfo, baseConfig, autoTuneMaxCandidateFrames, progressListener,
                VisualizationSettings.defaults());
    }

    /** Builds the same pool with the admitted run's rendering settings, only making pictures when requested. */
    public static List<ImageFrame> buildCandidatePool(FitsFileInformation[] filesInfo, DetectionConfig baseConfig,
            int autoTuneMaxCandidateFrames, ProgressListener progressListener, VisualizationSettings visualization) throws Exception {
        return buildCandidatePool(filesInfo, baseConfig, autoTuneMaxCandidateFrames, progressListener, visualization, List.of());
    }

    /** Builds the pool scoring each frame's quality without the excluded areas, as detection measures it. */
    public static List<ImageFrame> buildCandidatePool(FitsFileInformation[] filesInfo, DetectionConfig baseConfig,
            int autoTuneMaxCandidateFrames, ProgressListener progressListener, VisualizationSettings visualization,
            List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) throws Exception {
        return candidatePool(filesInfo, baseConfig, autoTuneMaxCandidateFrames, progressListener, visualization,
                excludedAreas).frames();
    }

    /** {@link #buildCandidatePool} with the fact of whether quality control already chose the frames. */
    public static CandidatePool candidatePool(FitsFileInformation[] filesInfo, DetectionConfig baseConfig,
            int autoTuneMaxCandidateFrames, ProgressListener progressListener, VisualizationSettings visualization,
            List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) throws Exception {
        if (filesInfo == null || filesInfo.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            throw new IllegalStateException("Not enough frames to run Auto-Tuning.");
        }

        int candidateLimit = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
        if (filesInfo.length <= candidateLimit) {
            emitProgress(progressListener, 0, "Loading full candidate pool...");
            return new CandidatePool(loadFrames(filesInfo, buildSequentialIndices(filesInfo.length), progressListener, 0, 50,
                    "Loading candidate frame", visualization), false);
        }

        emitProgress(progressListener, 0, "Evaluating frame quality for candidate pool...");
        // Positions in keptFrames are what the selection works on; a rejected frame is never a candidate.
        List<Integer> keptFrames = new ArrayList<>();
        List<FrameQualityRecord> frameQualityRecords = scoreKeptFrames(filesInfo, baseConfig, progressListener,
                excludedAreas, keptFrames);
        List<Integer> selectedIndices = new ArrayList<>();
        for (int position : selectCandidateSequenceIndices(frameQualityRecords, candidateLimit)) {
            selectedIndices.add(keptFrames.get(position));
        }

        emitProgress(progressListener, 40, "Loading deterministic candidate pool...");
        return new CandidatePool(loadFrames(filesInfo, selectedIndices, progressListener, 40, 50, "Loading candidate frame",
                visualization), true);
    }

    static List<Integer> selectCandidateSequenceIndices(List<FrameQualityRecord> frameQualityRecords, int autoTuneMaxCandidateFrames) {
        int candidateLimit = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
        if (frameQualityRecords.size() <= candidateLimit) {
            return buildSequentialIndices(frameQualityRecords.size());
        }

        List<FrameQualityRecord> qualitySorted = new ArrayList<>(frameQualityRecords);
        qualitySorted.sort(Comparator
                .comparingDouble((FrameQualityRecord record) -> record.score)
                .thenComparingInt(record -> record.sequenceIndex));

        int[] bucketCounts = allocateBucketCounts(candidateLimit);
        List<Integer> bestQualityOrder = toSequenceIndexList(qualitySorted);
        List<Integer> medianQualityOrder = buildMedianQualityOrder(qualitySorted);
        List<Integer> sequenceSpreadSelection = buildEvenlySpacedSequenceOrder(frameQualityRecords.size(), bucketCounts[2]);
        List<Integer> sequenceSpreadFallbackOrder = buildEvenlySpacedSequenceOrder(frameQualityRecords.size(), candidateLimit);

        LinkedHashSet<Integer> selected = new LinkedHashSet<>(candidateLimit);
        addFromOrder(selected, bestQualityOrder, bucketCounts[0]);
        addFromOrder(selected, medianQualityOrder, bucketCounts[1]);
        addFromOrder(selected, sequenceSpreadSelection, bucketCounts[2]);

        if (selected.size() < candidateLimit) {
            addFromOrder(selected, sequenceSpreadFallbackOrder, candidateLimit - selected.size());
        }
        if (selected.size() < candidateLimit) {
            addFromOrder(selected, medianQualityOrder, candidateLimit - selected.size());
        }
        if (selected.size() < candidateLimit) {
            addFromOrder(selected, bestQualityOrder, candidateLimit - selected.size());
        }

        List<Integer> orderedSelection = new ArrayList<>(selected);
        orderedSelection.sort(Integer::compareTo);
        return orderedSelection;
    }

    /**
     * Runs the engine's quality control over every frame, as the Inspect step does, and scores the frames it keeps
     * by noise × star size (lower is better). Fills {@code keptFrames} with their file positions; a record's index
     * is its position in that list.
     */
    private static List<FrameQualityRecord> scoreKeptFrames(FitsFileInformation[] filesInfo, DetectionConfig baseConfig,
            ProgressListener progressListener, List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas,
            List<Integer> keptFrames) throws Exception {
        var quality = new QualityControlService().evaluate(filesInfo, baseConfig, excludedAreas,
                (percent, message) -> emitProgress(progressListener, scaleProgress(percent, 100, 0, 40), message)).result();
        List<FrameQualityRecord> scoredFrames = new ArrayList<>();
        for (int i = 0; i < quality.frames().size(); i++) {
            var frame = quality.frames().get(i);
            if (frame.rejected()) {
                continue;
            }
            double score = frame.backgroundNoise() * frame.medianFwhm();
            if (!Double.isFinite(score) || score <= 0.0d) {
                score = Double.POSITIVE_INFINITY;
            }
            scoredFrames.add(new FrameQualityRecord(keptFrames.size(), score));
            keptFrames.add(i);
        }
        return scoredFrames;
    }

    private static List<ImageFrame> loadFrames(FitsFileInformation[] filesInfo,
                                               List<Integer> selectedIndices,
                                               ProgressListener progressListener,
                                               int startPercent,
                                               int endPercent,
                                               String progressPrefix, VisualizationSettings visualization) throws Exception {
        List<Integer> orderedIndices = new ArrayList<>(selectedIndices);
        orderedIndices.sort(Integer::compareTo);

        List<ImageFrame> frames = new ArrayList<>(orderedIndices.size());
        for (int i = 0; i < orderedIndices.size(); i++) {
            int frameIndex = orderedIndices.get(i);
            FitsFileInformation info = filesInfo[frameIndex];

            emitProgress(progressListener,
                    scaleProgress(i + 1, orderedIndices.size(), startPercent, endPercent),
                    progressPrefix + " " + (i + 1) + " of " + orderedIndices.size() + "...");

            try (Fits fitsFile = new Fits(info.getFilePath())) {
                BasicHDU<?> hdu = FitsInput.getImageHDU(fitsFile);
                Object kernel = hdu.getKernel();
                if (!(kernel instanceof short[][])) {
                    throw new IOException("FITS file is not 16-bit monochrome: " + info.getFileName());
                }

                frames.add(new ImageFrame(
                        frameIndex,
                        info.getFileName(),
                        (short[][]) kernel,
                        info.getObservationTimestamp(),
                        info.getExposureDurationMillis()));
                offerFrame((short[][]) kernel, "Loading candidate " + (i + 1) + " of " + orderedIndices.size()
                        + " · sequence frame " + (frameIndex + 1), visualization);
            }
        }

        frames.sort(Comparator.comparingInt(frame -> frame.sequenceIndex));
        return frames;
    }

    /** Reuses the loaded pixels while they are owned here; a hidden or absent live view builds nothing. */
    private static void offerFrame(short[][] pixels, String caption, VisualizationSettings visualization) {
        RunPreviewScope.offer(false, () -> AutoTuneLiveView.render(new AutoTuneSnapshot(AutoTuneSnapshot.Kind.FRAME,
                caption, pixels, null, List.of(), List.of()), visualization));
    }

    private static int[] allocateBucketCounts(int candidateLimit) {
        int[] counts = new int[CANDIDATE_POOL_WEIGHTS.length];
        double[] remainders = new double[CANDIDATE_POOL_WEIGHTS.length];
        int allocated = 0;

        for (int i = 0; i < CANDIDATE_POOL_WEIGHTS.length; i++) {
            double exact = candidateLimit * CANDIDATE_POOL_WEIGHTS[i];
            counts[i] = (int) Math.floor(exact);
            remainders[i] = exact - counts[i];
            allocated += counts[i];
        }

        while (allocated < candidateLimit) {
            int bestIndex = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i] > remainders[bestIndex]) {
                    bestIndex = i;
                }
            }
            counts[bestIndex]++;
            remainders[bestIndex] = -1.0d;
            allocated++;
        }

        return counts;
    }

    private static List<Integer> buildMedianQualityOrder(List<FrameQualityRecord> qualitySorted) {
        List<Integer> orderedIndices = new ArrayList<>(qualitySorted.size());
        int left = (qualitySorted.size() - 1) / 2;
        int right = qualitySorted.size() / 2;

        if (left == right && left >= 0) {
            orderedIndices.add(qualitySorted.get(left).sequenceIndex);
            left--;
            right++;
        }

        while (left >= 0 || right < qualitySorted.size()) {
            if (left >= 0) {
                orderedIndices.add(qualitySorted.get(left).sequenceIndex);
                left--;
            }
            if (right < qualitySorted.size()) {
                orderedIndices.add(qualitySorted.get(right).sequenceIndex);
                right++;
            }
        }

        return orderedIndices;
    }

    private static List<Integer> buildEvenlySpacedSequenceOrder(int totalFrames, int desiredCount) {
        List<Integer> orderedIndices = new ArrayList<>(desiredCount);
        if (desiredCount <= 0 || totalFrames <= 0) {
            return orderedIndices;
        }
        if (desiredCount == 1) {
            orderedIndices.add(totalFrames / 2);
            return orderedIndices;
        }

        LinkedHashSet<Integer> uniqueIndices = new LinkedHashSet<>(desiredCount);
        for (int i = 0; i < desiredCount; i++) {
            int position = (int) Math.round(i * (totalFrames - 1.0d) / (desiredCount - 1.0d));
            uniqueIndices.add(position);
        }

        if (uniqueIndices.size() < desiredCount) {
            for (int i = 0; i < totalFrames && uniqueIndices.size() < desiredCount; i++) {
                uniqueIndices.add(i);
            }
        }

        orderedIndices.addAll(uniqueIndices);
        return orderedIndices;
    }

    private static List<Integer> buildSequentialIndices(int size) {
        List<Integer> orderedIndices = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            orderedIndices.add(i);
        }
        return orderedIndices;
    }

    private static List<Integer> toSequenceIndexList(List<FrameQualityRecord> frameQualityRecords) {
        List<Integer> orderedIndices = new ArrayList<>(frameQualityRecords.size());
        for (FrameQualityRecord record : frameQualityRecords) {
            orderedIndices.add(record.sequenceIndex);
        }
        return orderedIndices;
    }

    private static void addFromOrder(LinkedHashSet<Integer> selected, List<Integer> orderedCandidates, int targetCount) {
        int targetSize = selected.size() + targetCount;
        for (Integer candidate : orderedCandidates) {
            if (selected.size() >= targetSize) {
                return;
            }
            selected.add(candidate);
        }
    }

    private static int scaleProgress(int completed, int total, int startPercent, int endPercent) {
        if (total <= 0) {
            return endPercent;
        }
        double ratio = completed / (double) total;
        return startPercent + (int) Math.round(ratio * (endPercent - startPercent));
    }

    private static void emitProgress(ProgressListener progressListener, int percentage, String message) {
        if (progressListener != null) {
            progressListener.onProgress(percentage, message);
        }
    }
}
