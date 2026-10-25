package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.session.SkyCatalogueModel;

import javax.swing.JLabel;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.WindowEvent;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ViewerLifecycleProbe extends SequenceViewerFrame {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicInteger releases = new AtomicInteger();
    private FitsFileInformation[] files;

    public ViewerLifecycleProbe(boolean disposeOnClose) {
        super("Lifecycle test viewer", "waiting for test frame", disposeOnClose, false);
    }

    public void open(FitsFileInformation[] files, StretchPanel stretch) {
        this.files = files.clone();
        openAt(0, stretch, this.files);
    }

    public void closeViewer() {
        dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING));
    }

    public boolean awaitStarted() throws InterruptedException {
        return started.await(10, TimeUnit.SECONDS);
    }

    public boolean awaitFinished() throws InterruptedException {
        return finished.await(10, TimeUnit.SECONDS);
    }

    public void finishPreparation() {
        proceed.countDown();
    }

    public int releaseCount() {
        return releases.get();
    }

    public boolean hasFrameReferences() {
        return files != null;
    }

    /** The metadata of the frame on show, or null before a frame is shown. */
    public FitsFileInformation shownInfo() {
        return shownFrameInfo();
    }

    @Override
    protected void onFrameMetadataReplaced(FitsFileInformation previous, FitsFileInformation updated) {
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i] != null && files[i].getFilePath().equals(previous.getFilePath())) {
                files[i] = updated;
            }
        }
    }

    public boolean showsObsoleteResult() {
        return containsObsoleteCaption(getContentPane());
    }

    public static int catalogueListenerCount(SkyCatalogueModel model) {
        return model.listenerCount();
    }

    @Override
    protected int frameCount() {
        return files == null ? 0 : files.length;
    }

    @Override
    protected FitsFileInformation infoOf(int index) {
        return files[index];
    }

    @Override
    protected Result prepare(int index) throws Exception {
        FitsFileInformation info = files[index];
        started.countDown();
        try {
            if (!proceed.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release frame preparation.");
            }
            return Result.of(BlinkSequence.load(info), null, "obsolete worker result");
        } finally {
            finished.countDown();
        }
    }

    @Override
    protected void released() {
        files = null;
        releases.incrementAndGet();
    }

    private static boolean containsObsoleteCaption(Container parent) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JLabel label && label.getText() != null
                    && label.getText().contains("obsolete worker result")) {
                return true;
            }
            if (component instanceof Container child && containsObsoleteCaption(child)) {
                return true;
            }
        }
        return false;
    }
}
