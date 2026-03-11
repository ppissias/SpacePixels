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
import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.AutoTuneStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import nom.tam.fits.Fits;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class AutoTuneTask implements Runnable {

    private final EventBus eventBus;
    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig baseConfig;

    public AutoTuneTask(EventBus eventBus, FitsFileInformation[] filesInfo, DetectionConfig baseConfig) {
        this.eventBus = eventBus;
        this.filesInfo = filesInfo;
        this.baseConfig = baseConfig;
    }

    @Override
    public void run() {
        eventBus.post(new AutoTuneStartedEvent());

        try {
            if (filesInfo == null || filesInfo.length < 5) {
                throw new IllegalStateException("Not enough frames to run Auto-Tuning.");
            }

            // We only need to load 5 frames from the middle to feed the AutoTuner
            List<ImageFrame> sampleFrames = new ArrayList<>();
            int startIndex = filesInfo.length / 2 - 2;

            for (int i = 0; i < 5; i++) {
                FitsFileInformation info = filesInfo[startIndex + i];
                try (Fits fitsFile = new Fits(info.getFilePath())) {
                    Object kernel = fitsFile.getHDU(0).getKernel();
                    if (kernel instanceof short[][]) {
                        sampleFrames.add(new ImageFrame(startIndex + i, info.getFileName(), (short[][]) kernel));
                    } else {
                        throw new IOException("FITS file data is not short[][], cannot auto-tune.");
                    }
                }
            }

            // Run the math!
            JTransientAutoTuner.AutoTunerResult result = JTransientAutoTuner.tune(sampleFrames, baseConfig);

            // Post success
            eventBus.post(new AutoTuneFinishedEvent(true, "Auto-Tuning completed successfully.", result));

        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Auto-Tuning failed", ex);
            // Post failure
            eventBus.post(new AutoTuneFinishedEvent(false, ex.getMessage(), null));
        }
    }
}