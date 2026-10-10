package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.gui.StretchPanel;
import eu.startales.spacepixels.gui.TransientInspectionFrame;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;


import javax.swing.*;
import java.util.List;
import java.util.logging.Level;

public class ManualTransientInspectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final DetectionConfig config;
    private final StretchPanel stretchPanel;

    /** Runs the engine on all frames of the session and opens the inspection window, with the shared stretch. */
    public ManualTransientInspectionTask(EventBus eventBus, ImageProcessing preProcessing, DetectionConfig config,
                                         StretchPanel stretchPanel) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.config = config;
        this.stretchPanel = stretchPanel;
    }

    @Override
    public void run() {
        eventBus.post(new DetectionStartedEvent());

        try {
            FitsFileInformation[] filesInfo = preProcessing.getFitsfileInformation();
            List<ImageFrame> framesForLibrary = SessionFrameLoader.loadAll(filesInfo,
                    (percent, message) -> eventBus.post(new EngineProgressUpdateEvent(percent, message)), 20);

            TransientEngineProgressListener progressListener = (percentage, message) -> {
                if (ApplicationWindow.OOM_FLAG) throw new OutOfMemoryError("Global OOM triggered");
                eventBus.post(new EngineProgressUpdateEvent(20 + (int)(percentage * 0.8), message));
            };

            JTransientEngine engine = new JTransientEngine();
            List<FrameTransients> cleanTransients = engine.detectTransients(framesForLibrary, config, progressListener);
            engine.shutdown();

            SwingUtilities.invokeLater(() -> {
                TransientInspectionFrame frame = new TransientInspectionFrame(framesForLibrary, cleanTransients, filesInfo, stretchPanel);
                if (!frame.open()) {
                    JOptionPane.showMessageDialog(null, "The detection kept no frames to inspect.",
                            "Manual Transient Inspection", JOptionPane.INFORMATION_MESSAGE);
                }
            });

            // Pass 'true' for quickDetection to silence the "Open Report?" prompt and simply close the dialog
            eventBus.post(new DetectionFinishedEvent(null, true, true, null, null, 0, 0, null));

        } catch (Throwable t) {
            if (t instanceof OutOfMemoryError || (t.getCause() != null && t.getCause() instanceof OutOfMemoryError)) {
                ApplicationWindow.logger.log(Level.SEVERE, "OOM during transient inspection", t);
                // Let the global handler catch the OOM
            } else {
                ApplicationWindow.logger.log(Level.SEVERE, "Transient Inspection failed", t);
                eventBus.post(new DetectionFinishedEvent(null, false, false, t.getMessage(), null, 0, 0, null));
            }
        }
    }
}
