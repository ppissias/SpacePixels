package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobFailures;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the frames of a blink. The blink window plays them; the stretch is applied while drawing, so it can change
 * during the blink. Stopping declines the job; running out of memory fails it with advice, since the frames read so
 * far are released with it.
 */
public class BlinkImagesTask implements Job<BlinkSequence> {

    /** Told on the worker thread before each frame is read, for the blink window's loading view. */
    @FunctionalInterface
    public interface FrameProgress {
        void reading(int read, int total);
    }

    private final FitsFileInformation[] files;
    private final AtomicBoolean stopRequested;
    private final FrameProgress frameProgress;

    public BlinkImagesTask(FitsFileInformation[] files, AtomicBoolean stopRequested, FrameProgress frameProgress) {
        this.files = files;
        this.stopRequested = stopRequested;
        this.frameProgress = frameProgress;
    }

    @Override
    public JobOutcome<BlinkSequence> run(JobContext context) throws Exception {
        if (files == null || files.length == 0) {
            return JobOutcome.failed("No files selected for blinking.", null);
        }
        try {
            List<BlinkSequence.Frame> frames = new ArrayList<>();
            for (int i = 0; i < files.length; i++) {
                if (stopRequested.get()) {
                    return JobOutcome.declined("Blinking stopped.");
                }
                context.progress((int) (100L * i / files.length),
                        String.format("Loading frame %d of %d for blinking...", i + 1, files.length));
                frameProgress.reading(i, files.length);
                frames.add(BlinkSequence.load(files[i]));
            }
            if (stopRequested.get()) {
                return JobOutcome.declined("Blinking stopped.");
            }
            return JobOutcome.completed(new BlinkSequence(frames));
        } catch (Throwable t) {
            if (!JobFailures.isOutOfMemory(t)) {
                throw t;
            }
            // Recoverable here: the frames read so far are released with this job.
            return JobOutcome.failed("Not enough memory to hold " + files.length
                    + " frames. Blink fewer frames, or start SpacePixels with more memory.", t);
        }
    }
}
