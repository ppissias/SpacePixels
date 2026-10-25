package eu.startales.spacepixels.session;

import eu.startales.spacepixels.util.DetectionOverlays;
import eu.startales.spacepixels.util.FitsBatchService;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.FitsVisualizationRenderer;
import eu.startales.spacepixels.util.PlateSolveService;
import eu.startales.spacepixels.util.TrackOverlay;
import eu.startales.spacepixels.util.VariableStarOverlay;
import eu.startales.spacepixels.util.AutoTuneSessionStore;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Imported-session identity, owned state, services and metadata-save callback lifetime. The session owns its plate
 * solver and the batch operations on its folder, and closes the solver with itself. Closing drops later UI
 * completions without cancelling accepted writes on the application's shared storage. Frame data is shared, not
 * deep-copied.
 */
public final class ProcessingSession implements AutoCloseable {
    private final UUID id = UUID.randomUUID();
    private final PlateSolveService plateSolver;
    /** Batch operations on the session's folder; null for a session without frames. */
    private final FitsBatchService batch;
    private final SkyCatalogueModel skyCatalogue = new SkyCatalogueModel();
    private final AutoTuneState autoTune = new AutoTuneState();
    private final SessionMetadata metadata;
    private final ApplicationStorage storage;
    private final Executor ui;
    private final File folder;
    /** This session owns the array; metadata elements remain shared with its table, selection and admitted runs. */
    private FitsFileInformation[] files;
    private List<FitsFileInformation> selection = List.of();
    private File lastReport;
    /** The overlays of the last detection run; written on the EDT when a run ends, read by the viewers. */
    private volatile DetectionOverlays lastRun;
    private String masterStackKey;
    private JTransientEngine.MasterStackResult masterStack;
    /** Non-blocking shutdown supplied by the desktop workflow; the session has no dependency on its GUI owner. */
    private Runnable closeSkyCatalogueWork;
    private volatile boolean closed;

    /** A session without application storage, for headless use and tests; it owns and closes {@code plateSolver}. */
    public ProcessingSession(PlateSolveService plateSolver, FitsFileInformation[] files, File lastReport) {
        this(plateSolver, files, lastReport, SessionMetadata.empty(), null, null);
    }

    /**
     * Owns imported state, {@code plateSolver} and UI callback lifetime; the application retains and closes the
     * shared storage, and keeps the settings the solver reads.
     */
    public ProcessingSession(PlateSolveService plateSolver, FitsFileInformation[] files, File lastReport,
                             SessionMetadata metadata, ApplicationStorage storage, Executor ui) {
        this.metadata = Objects.requireNonNull(metadata);
        this.storage = storage;
        this.ui = storage == null ? null : Objects.requireNonNull(ui);
        this.plateSolver = Objects.requireNonNull(plateSolver);
        this.files = Objects.requireNonNull(files).clone();
        this.lastReport = lastReport;
        folder = files.length == 0 ? null : new File(files[0].getFilePath()).getParentFile();
        batch = folder == null ? null : new FitsBatchService(folder, new FitsVisualizationRenderer());
        skyCatalogue.setSessionFolder(folder);
        if (storage != null) skyCatalogue.setCorrectionWriter((correction, finished) ->
                saveMetadata(() -> storage.store().saveCorrection(folder, correction), finished));
        skyCatalogue.set(metadata.catalogue());
        skyCatalogue.setCorrection(metadata.correction());
    }

    public SessionMetadata getMetadata() { return metadata; }

    /** Whether this desktop session can queue metadata writes on the application's storage. */
    public boolean hasStorage() { return storage != null; }

    /** Saves this session's already captured catalogue; completion is delivered only while this session is open. */
    public void saveCatalogue(SkyCatalogue catalogue, Consumer<Throwable> finished) {
        saveMetadata(() -> storage.store().saveCatalogue(folder, catalogue), finished);
    }

    /** Saves this session's detached tuning document; completion is delivered only while this session is open. */
    public void saveAutoTune(AutoTuneSessionStore.Document document, Consumer<Throwable> finished) {
        saveMetadata(() -> storage.store().saveAutoTune(folder, document), finished);
    }

    /** One captured metadata write; its contents are not read again from live state. */
    @FunctionalInterface
    private interface Write {
        /** Writes the captured metadata on the application I/O worker. */
        void run() throws IOException;
    }

    /**
     * Queues a write without waiting. Failures remain logged/recorded even after close, but completion checks this
     * session's identity/lifetime on the UI executor. A UI executor that has stopped accepting work is tolerated.
     */
    private void saveMetadata(Write write, Consumer<Throwable> finished) {
        requireOpen();
        if (storage == null) throw new IllegalStateException("This session has no application storage.");
        storage.submit(() -> { write.run(); return null; }).whenComplete((ignored, failure) -> {
            if (failure != null) {
                storage.recordSessionWriteFailure(failure);
                java.util.logging.Logger.getLogger(ProcessingSession.class.getName()).log(
                        java.util.logging.Level.WARNING, "Could not save session metadata", failure);
            }
            try {
                ui.execute(() -> {
                    if (!closed) finished.accept(failure);
                });
            } catch (RuntimeException windowGone) {
                // The application has stopped accepting UI callbacks.
            }
        });
    }

    public UUID getId() { return id; }

    /** The plate solver of this session's frames; the session closes it. */
    public PlateSolveService getPlateSolveService() { return plateSolver; }

    /** Batch conversion and stretching of this session's folder; null for a session without frames. */
    public FitsBatchService getBatchService() { return batch; }

    /** Returns a copy of the owned frame array, sharing its metadata objects; empty after close. */
    public FitsFileInformation[] getFiles() { return files.clone(); }

    /** Whether an owned frame is plate-solved, which gives a detection report sky coordinates. */
    public boolean hasPlateSolvedFrame() {
        for (FitsFileInformation file : files) {
            if (file.isWcsSolved()) return true;
        }
        return false;
    }
    public SkyCatalogueModel getSkyCatalogue() { return skyCatalogue; }
    public AutoTuneState getAutoTune() { return autoTune; }
    public File getLastReport() { return lastReport; }
    public void setLastReport(File report) { requireOpen(); lastReport = report; }
    public List<FitsFileInformation> getSelection() { return selection; }

    public void setSelection(FitsFileInformation[] selected) {
        requireOpen();
        if (selected != null && !Arrays.asList(getFiles()).containsAll(Arrays.asList(selected))) {
            throw new IllegalArgumentException("Selected frames belong to another session.");
        }
        selection = selected == null ? List.of() : List.copyOf(Arrays.asList(selected));
    }

    /**
     * Replaces the FITS header metadata of {@code frame} in this session with an immutable descriptor containing
     * {@code fitsHeader}, updating any selected reference to that frame as well. Existing snapshots held by admitted
     * runs keep their previous descriptor.
     */
    public FitsFileInformation replaceFrameHeader(FitsFileInformation frame, Map<String, String> fitsHeader) {
        requireOpen();
        Objects.requireNonNull(frame, "frame");
        int index = indexOfFrame(frame);
        if (index < 0) {
            throw new IllegalArgumentException("Frame belongs to another session.");
        }
        FitsFileInformation previous = files[index];
        FitsFileInformation updated = previous.withFitsHeader(fitsHeader);
        files[index] = updated;
        if (!selection.isEmpty()) {
            List<FitsFileInformation> updatedSelection = new ArrayList<>(selection.size());
            boolean changed = false;
            for (FitsFileInformation selected : selection) {
                if (selected == previous || sameFrame(selected, previous)) {
                    updatedSelection.add(updated);
                    changed = true;
                } else {
                    updatedSelection.add(selected);
                }
            }
            if (changed) {
                selection = List.copyOf(updatedSelection);
            }
        }
        return updated;
    }

    private int indexOfFrame(FitsFileInformation frame) {
        for (int index = 0; index < files.length; index++) {
            if (files[index] == frame) return index;
        }
        for (int index = 0; index < files.length; index++) {
            if (sameFrame(files[index], frame)) return index;
        }
        return -1;
    }

    private static boolean sameFrame(FitsFileInformation first, FitsFileInformation second) {
        return first != null && second != null
                && first.getFilePath() != null
                && first.getFilePath().equals(second.getFilePath());
    }

    public JTransientEngine.MasterStackResult getMasterStack(String key) {
        return Objects.equals(masterStackKey, key) ? masterStack : null;
    }

    public void setMasterStack(String key, JTransientEngine.MasterStackResult stack) {
        requireOpen();
        masterStackKey = key;
        masterStack = stack;
    }

    /**
     * Keeps a finished detection run's overlays for the viewers, replacing the previous run's. The window calls it on
     * the EDT when the run ends, and only while this session is current; a closed session refuses it.
     */
    public void setLastRun(DetectionOverlays overlays) {
        requireOpen();
        lastRun = Objects.requireNonNull(overlays);
    }

    /** Tracks of the last detection run on this session, or null when none has finished. */
    public TrackOverlay getTrackOverlay() {
        DetectionOverlays run = lastRun;
        return run == null ? null : run.tracks();
    }

    /** Variable-star candidates of the last detection run, numbered as in its report, or null when none has finished. */
    public VariableStarOverlay getVariableStarOverlay() {
        DetectionOverlays run = lastRun;
        return run == null ? null : run.variables();
    }

    public boolean isClosed() { return closed; }

    /** Owns one non-blocking catalogue-workflow shutdown, replacing any previous owner; register while open. */
    public void ownSkyCatalogueWork(Runnable shutdown) {
        requireOpen();
        Objects.requireNonNull(shutdown);
        if (closeSkyCatalogueWork != null) closeSkyCatalogueWork.run();
        closeSkyCatalogueWork = shutdown;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Session is closed.");
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            skyCatalogue.close();
            Runnable shutdown = closeSkyCatalogueWork;
            closeSkyCatalogueWork = null;
            if (shutdown != null) shutdown.run();
        } finally {
            try {
                plateSolver.close();
            } finally {
                masterStack = null;
                masterStackKey = null;
                autoTune.clear();
                selection = List.of();
                files = new FitsFileInformation[0];
                lastReport = null;
                lastRun = null;
            }
        }
    }
}
