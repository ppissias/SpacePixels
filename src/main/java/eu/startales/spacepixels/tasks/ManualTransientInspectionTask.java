package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.gui.TransientInspectionFrame;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.core.SourceExtractor;

import nom.tam.fits.Fits;

import javax.swing.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class ManualTransientInspectionTask implements Runnable {
    private final EventBus eventBus;
    private final ImageProcessing preProcessing;
    private final DetectionConfig config;

    public ManualTransientInspectionTask(EventBus eventBus, ImageProcessing preProcessing, DetectionConfig config) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.config = config;
    }

    @Override
    public void run() {
        eventBus.post(new DetectionStartedEvent());

        try {
            FitsFileInformation[] filesInfo = preProcessing.getFitsfileInformation();
            int numFrames = filesInfo.length;

            List<ImageFrame> framesForLibrary = new ArrayList<>();
            for (int i = 0; i < numFrames; i++) {
                if (ApplicationWindow.OOM_FLAG) throw new OutOfMemoryError("Global OOM triggered");

                int percent = (int) (((float) i / numFrames) * 20);
                eventBus.post(new EngineProgressUpdateEvent(percent, "Loading frame " + (i + 1) + " of " + numFrames + "..."));

                File currentFile = new File(filesInfo[i].getFilePath());
                try (Fits fitsFile = new Fits(currentFile)) {
                    Object kernel = fitsFile.getHDU(0).getKernel();
                    if (!(kernel instanceof short[][])) {
                        throw new Exception("Cannot process: Expected short[][]");
                    }
                    long timestamp = filesInfo[i].getObservationTimestamp();
                    framesForLibrary.add(new ImageFrame(i, currentFile.getName(), (short[][]) kernel, timestamp));
                }
            }

            TransientEngineProgressListener progressListener = (percentage, message) -> {
                if (ApplicationWindow.OOM_FLAG) throw new OutOfMemoryError("Global OOM triggered");
                eventBus.post(new EngineProgressUpdateEvent(20 + (int)(percentage * 0.8), message));
            };

            JTransientEngine engine = new JTransientEngine();
            List<JTransientEngine.FrameTransients> cleanTransients = engine.detectTransients(framesForLibrary, config, progressListener);
            engine.shutdown();

            SwingUtilities.invokeLater(() -> {
                TransientInspectionFrame frame = new TransientInspectionFrame(framesForLibrary, cleanTransients);
                frame.setVisible(true);
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