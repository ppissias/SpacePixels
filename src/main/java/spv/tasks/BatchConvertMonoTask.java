package spv.tasks;

import com.google.common.eventbus.EventBus;
import spv.events.BatchConvertFinishedEvent;
import spv.events.BatchConvertStartedEvent;
import spv.gui.ApplicationWindow;
import spv.util.ImagePreprocessing;
import spv.util.StretchAlgorithm;

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