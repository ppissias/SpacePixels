/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;

// --- NEW IMPORTS FOR PROGRESS ---
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.AutoTuneStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.quality.FrameQualityAnalyzer;
import nom.tam.fits.Fits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;

public class AutoTuneTask implements Runnable {

    private final EventBus eventBus;
    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig baseConfig;

    // Helper class to track the top frames in memory
    private static class ScoredFrame {
        ImageFrame frame;
        double score;

        ScoredFrame(ImageFrame frame, double score) {
            this.frame = frame;
            this.score = score;
        }
    }

    public AutoTuneTask(EventBus eventBus, FitsFileInformation[] filesInfo, DetectionConfig baseConfig) {
        this.eventBus = eventBus;
        this.filesInfo = filesInfo;
        this.baseConfig = baseConfig;
    }

    @Override
    public void run() {
        eventBus.post(new AutoTuneStartedEvent());

        try {
            if (filesInfo == null || filesInfo.length < JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE) {
                throw new IllegalStateException("Not enough frames to run Auto-Tuning.");
            }

            ApplicationWindow.logger.info("Auto-Tuner evaluating " + filesInfo.length + " frames for quality...");

            List<ScoredFrame> topFrames = new ArrayList<>();

            // ==========================================
            // PHASE 1: Quality Evaluation (0% to 50%)
            // ==========================================
            for (int i = 0; i < filesInfo.length; i++) {

                // --- PROGRESS UPDATE ---
                int percent = (int) (((float) i / filesInfo.length) * 50);
                eventBus.post(new EngineProgressUpdateEvent(percent, "Evaluating quality of frame " + (i + 1) + " of " + filesInfo.length + "..."));

                FitsFileInformation info = filesInfo[i];
                try (Fits fitsFile = new Fits(info.getFilePath())) {
                    Object kernel = fitsFile.getHDU(0).getKernel();

                    if (kernel instanceof short[][]) {
                        short[][] pixelData = (short[][]) kernel;

                        // 1. Evaluate quality immediately while the file is open
                        FrameQualityAnalyzer.FrameMetrics metrics = FrameQualityAnalyzer.evaluateFrame(pixelData, baseConfig);
                        double score = metrics.backgroundNoise * metrics.medianFWHM;

                        ImageFrame currentFrame = new ImageFrame(i, info.getFileName(), pixelData);
                        topFrames.add(new ScoredFrame(currentFrame, score));

                        // 2. Sort so the lowest score (best quality) is at the top
                        topFrames.sort(Comparator.comparingDouble(f -> f.score));

                        // 3. If we have more than we need, throw away the worst one!
                        if (topFrames.size() > JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE) {
                            topFrames.remove(topFrames.size() - 1);
                        }

                    } else {
                        throw new IOException("FITS file data is not short[][], cannot auto-tune.");
                    }
                }
            }

            // Extract just the ImageFrames from our top list
            List<ImageFrame> bestFrames = new ArrayList<>();
            for (ScoredFrame sf : topFrames) {
                bestFrames.add(sf.frame);
            }

            // Re-sort chronologically so the AutoTuner can measure kinematic jitter correctly
            bestFrames.sort(Comparator.comparingInt(f -> f.sequenceIndex));

            ApplicationWindow.logger.info("Quality pass complete. Passing Top " + JTransientAutoTuner.AUTO_TUNE_SAMPLE_SIZE + " frames to AutoTuner math engine.");

            // ==========================================
            // PHASE 2: Mathematical Tuning (50% to 100%)
            // ==========================================

            // --- PROGRESS UPDATE: The Scaled Listener ---
            // Maps the tuner's internal 0-100% to our UI's 50-100%
            TransientEngineProgressListener autoTuneListener = (enginePercent, message) -> {
                int scaledPercent = 50 + (int) ((enginePercent / 100.0f) * 50);
                eventBus.post(new EngineProgressUpdateEvent(scaledPercent, "Tuning: " + message));
            };

            eventBus.post(new EngineProgressUpdateEvent(50, "Starting mathematical tuning algorithms..."));

            // --- Pass the listener into the tune method ---
            JTransientAutoTuner.AutoTunerResult result = JTransientAutoTuner.tune(bestFrames, baseConfig, autoTuneListener);

            eventBus.post(new EngineProgressUpdateEvent(100, "Auto-Tuning complete!"));

            // Post success
            eventBus.post(new AutoTuneFinishedEvent(true, "Auto-Tuning completed successfully.", result));

        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Auto-Tuning failed", ex);
            // Post failure
            eventBus.post(new AutoTuneFinishedEvent(false, ex.getMessage(), null));
        }
    }
}