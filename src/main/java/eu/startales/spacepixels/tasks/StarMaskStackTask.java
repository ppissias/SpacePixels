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
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Builds the session's master stack exactly as a detection run does (same drift check, quality settings and frame
 * selection, then the median), for the Star Mask Explorer. Uses the same progress display and window locking as
 * Manual Transient Inspection.
 */
public class StarMaskStackTask implements Runnable {

    private final EventBus eventBus;
    private final FitsFileInformation[] filesInfo;
    private final DetectionConfig config;
    private final Consumer<JTransientEngine.MasterStackResult> onReady;

    /**
     * @param onReady receives the result on the event thread
     */
    public StarMaskStackTask(EventBus eventBus, FitsFileInformation[] filesInfo, DetectionConfig config,
                             Consumer<JTransientEngine.MasterStackResult> onReady) {
        this.eventBus = eventBus;
        this.filesInfo = filesInfo;
        this.config = config;
        this.onReady = onReady;
    }

    @Override
    public void run() {
        eventBus.post(new DetectionStartedEvent());
        JTransientEngine engine = new JTransientEngine();
        try {
            List<ImageFrame> frames = SessionFrameLoader.loadAll(filesInfo,
                    (percent, message) -> eventBus.post(new EngineProgressUpdateEvent(percent, message)), 30);
            JTransientEngine.MasterStackResult result = engine.generateMasterStackWithDetails(frames, config,
                    (percent, message) -> eventBus.post(new EngineProgressUpdateEvent(30 + (int) (percent * 0.7), message)));
            eventBus.post(new EngineProgressUpdateEvent(100, "Master stack ready"));
            // Close the progress display without the "open report" prompt, as Manual Transient Inspection does.
            eventBus.post(new DetectionFinishedEvent(null, true, true, null, null, 0, 0, null));
            SwingUtilities.invokeLater(() -> onReady.accept(result));
        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError || (t.getCause() instanceof OutOfMemoryError)) {
                ApplicationWindow.logger.log(Level.SEVERE, "OOM while building the master stack", t);
            } else {
                ApplicationWindow.logger.log(Level.SEVERE, "Building the master stack failed", t);
                eventBus.post(new DetectionFinishedEvent(null, false, false, t.getMessage(), null, 0, 0, null));
            }
        } finally {
            engine.shutdown();
        }
    }
}
