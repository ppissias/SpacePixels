package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.BatchConvertFinishedEvent;
import eu.startales.spacepixels.events.BatchConvertStartedEvent;
import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.util.ImagePreprocessing;
import eu.startales.spacepixels.util.StretchAlgorithm;

import java.util.logging.Level;

public class BatchConvertMonoTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final boolean stretchEnabled;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public BatchConvertMonoTask(EventBus eventBus, ImagePreprocessing preProcessing,
                                boolean stretchEnabled, int stretchFactor,
                                int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.stretchEnabled = stretchEnabled;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public void run() {
        eventBus.post(new BatchConvertStartedEvent());

        try {
            ApplicationWindow.logger.info("Will batch convert all images to mono");

            preProcessing.batchConvertToMono(stretchEnabled, stretchFactor, iterations, algo);

            eventBus.post(new BatchConvertFinishedEvent(true, null));
        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Cannot convert images", ex);
            eventBus.post(new BatchConvertFinishedEvent(false, ex.getMessage()));
        }
    }
}