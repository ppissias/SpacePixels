package eu.startales.spacepixels.session;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.TrackOverlay;
import eu.startales.spacepixels.util.VariableStarOverlay;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Imported-session identity and owned state; frame data is shared by reference, not deep-copied. */
public final class ProcessingSession implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final ImageProcessing processing;
    private final SkyCatalogueModel skyCatalogue = new SkyCatalogueModel();
    private final AutoTuneState autoTune = new AutoTuneState();
    private FitsFileInformation[] files;
    private List<FitsFileInformation> selection = List.of();
    private File lastReport;
    private String masterStackKey;
    private JTransientEngine.MasterStackResult masterStack;
    private Thread skyCatalogueWorker;
    private volatile boolean closed;

    public ProcessingSession(ImageProcessing processing, FitsFileInformation[] files, File lastReport) {
        this.processing = Objects.requireNonNull(processing);
        this.files = Objects.requireNonNull(files).clone();
        this.lastReport = lastReport;
        skyCatalogue.setSessionFolder(files.length == 0 ? null : new File(files[0].getFilePath()).getParentFile());
    }

    public UUID getId() { return id; }
    public ImageProcessing getProcessing() { return processing; }
    public FitsFileInformation[] getFiles() { return files.clone(); }
    public SkyCatalogueModel getSkyCatalogue() { return skyCatalogue; }
    public AutoTuneState getAutoTune() { return autoTune; }
    public File getLastReport() { return lastReport; }
    public void setLastReport(File report) { requireOpen(); lastReport = report; }
    public List<FitsFileInformation> getSelection() { return selection; }

    public void setSelection(FitsFileInformation[] selected) {
        requireOpen();
        if (selected != null && !Arrays.asList(files).containsAll(Arrays.asList(selected))) {
            throw new IllegalArgumentException("Selected frames belong to another session.");
        }
        selection = selected == null ? List.of() : List.copyOf(Arrays.asList(selected));
    }

    public JTransientEngine.MasterStackResult getMasterStack(String key) {
        return Objects.equals(masterStackKey, key) ? masterStack : null;
    }

    public void setMasterStack(String key, JTransientEngine.MasterStackResult stack) {
        requireOpen();
        masterStackKey = key;
        masterStack = stack;
    }

    public TrackOverlay getTrackOverlay() { return processing.getLastTrackOverlay(); }
    public VariableStarOverlay getVariableStarOverlay() { return processing.getLastVariableStarOverlay(); }
    public boolean isClosed() { return closed; }

    public void ownSkyCatalogueWorker(Thread worker) {
        requireOpen();
        if (skyCatalogueWorker != null && skyCatalogueWorker != worker) skyCatalogueWorker.interrupt();
        skyCatalogueWorker = Objects.requireNonNull(worker);
    }

    public boolean ownsSkyCatalogueWorker(Thread worker) {
        return !closed && skyCatalogueWorker == worker;
    }

    public void releaseSkyCatalogueWorker(Thread worker) {
        if (skyCatalogueWorker == worker) skyCatalogueWorker = null;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Session is closed.");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (skyCatalogueWorker != null) {
            skyCatalogueWorker.interrupt();
            skyCatalogueWorker = null;
        }
        try {
            processing.close();
        } finally {
            masterStack = null;
            masterStackKey = null;
            autoTune.clear();
            selection = List.of();
            files = new FitsFileInformation[0];
            lastReport = null;
        }
    }
}
