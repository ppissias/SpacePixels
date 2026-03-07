package spv.tasks;

import com.google.common.eventbus.EventBus;
import spv.events.BatchStretchFinishedEvent;
import spv.events.BatchStretchStartedEvent;
import spv.gui.ApplicationWindow;
import spv.util.ImagePreprocessing;
import spv.util.StretchAlgorithm;

import java.util.logging.Level;

public class BatchStretchTask implements Runnable {
    private final EventBus eventBus;
    private final ImagePreprocessing preProcessing;
    private final int stretchFactor;
    private final int iterations;
    private final StretchAlgorithm algo;

    public BatchStretchTask(EventBus eventBus, ImagePreprocessing preProcessing,
                            int stretchFactor, int iterations, StretchAlgorithm algo) {
        this.eventBus = eventBus;
        this.preProcessing = preProcessing;
        this.stretchFactor = stretchFactor;
        this.iterations = iterations;
        this.algo = algo;
    }

    @Override
    public void run() {
        // 1. Tell the UI we are starting
        eventBus.post(new BatchStretchStartedEvent());

        try {
            ApplicationWindow.logger.info("Will stretch all images (color or mono)");

            // 2. Perform the heavy lifting
            preProcessing.batchStretch(stretchFactor, iterations, algo);

            // 3. Report Success
            eventBus.post(new BatchStretchFinishedEvent(true, null));
        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Cannot stretch images", ex);

            // 3. Report Failure
            eventBus.post(new BatchStretchFinishedEvent(false, ex.getMessage()));
        }
    }
}