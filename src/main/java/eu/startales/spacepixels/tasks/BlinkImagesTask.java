package eu.startales.spacepixels.tasks;

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.BlinkFinishedEvent;
import eu.startales.spacepixels.events.BlinkLoadProgressEvent;
import eu.startales.spacepixels.events.BlinkSequenceLoadedEvent;
import eu.startales.spacepixels.events.BlinkStartedEvent;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the frames of a blink. The blink window plays them; the stretch is applied while drawing, so it can change
 * during the blink.
 */
public class BlinkImagesTask implements Runnable {

    private final EventBus eventBus;
    private final FitsFileInformation[] files;
    private final AtomicBoolean isBlinking;

    public BlinkImagesTask(EventBus eventBus, FitsFileInformation[] files, AtomicBoolean isBlinking) {
        this.eventBus = eventBus;
        this.files = files;
        this.isBlinking = isBlinking;
    }

    @Override
    public void run() {
        eventBus.post(new BlinkStartedEvent());
        try {
            if (files == null || files.length == 0) {
                eventBus.post(new BlinkFinishedEvent(false, "No files selected for blinking."));
                return;
            }
            List<BlinkSequence.Frame> frames = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                if (!isBlinking.get()) {
                    eventBus.post(new BlinkFinishedEvent(true, null));
                    return;
                }
                eventBus.post(new BlinkLoadProgressEvent(i, files.length));
                frames.add(BlinkSequence.load(files[i]));
            }
            if (!isBlinking.get()) {
                eventBus.post(new BlinkFinishedEvent(true, null));
                return;
            }
            eventBus.post(new BlinkSequenceLoadedEvent(new BlinkSequence(frames), files));
        } catch (OutOfMemoryError e) {
            eventBus.post(new BlinkFinishedEvent(false, "Not enough memory to hold " + files.length
                    + " frames. Blink fewer frames, or start SpacePixels with more memory."));
        } catch (Exception ex) {
            eventBus.post(new BlinkFinishedEvent(false, ex.getMessage()));
        }
    }
}
