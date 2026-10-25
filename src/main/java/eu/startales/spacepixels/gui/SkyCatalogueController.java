package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.jobs.JobFailures;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import eu.startales.spacepixels.util.skycatalog.PlateCorrectionFitter;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueClient;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import java.awt.EventQueue;
import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.Timer;

/**
 * One session's catalogue fetch and plate-correction workflow. Controls, progress state and model publication belong
 * to the EDT; an owned single-thread executor does the work. Session close shuts down the executor, drops queued
 * results and detaches the view. Every delivery checks both the request and session, including a same-session refetch.
 */
final class SkyCatalogueController implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SkyCatalogueController.class.getName());

    /** The view receives only EDT notifications and reads the controller's current presentation state. */
    interface Listener {
        /** Refreshes progress, command availability and catalogue status on the EDT. */
        void changed();
        /** Shows a workflow outcome or starting message on the EDT. */
        void message(String text);
    }

    /** The scientific operations, replaceable in tests to exercise delayed work without network access. */
    interface Work {
        /** Fetches a catalogue on the worker; interruption cancels the request. */
        SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) throws InterruptedException;
        /** Fits the captured frames on the worker; no live UI or settings are read. */
        PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue catalogue, PlateCorrectionFitter.Progress progress)
                throws IOException, InterruptedException;
    }

    /** One worker request's client, shared between its fetch and correction fit. */
    private static final class CatalogueWork implements Work {
        private final SkyCatalogueClient client = new SkyCatalogueClient();

        /** Fetches through the existing catalogue service, without changing its policy. */
        @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress)
                throws InterruptedException {
            return client.fetch(field, depth, progress);
        }

        /** Fits through the existing correction service with the same request-local client. */
        @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue catalogue,
                PlateCorrectionFitter.Progress progress) throws IOException, InterruptedException {
            return PlateCorrectionFitter.fit(files, catalogue, client, progress);
        }
    }

    /** Identity and captured inputs of one request; only its worker changes {@code fitting}. */
    private static final class Request {
        final FitsFileInformation[] files;
        final SkyField field;
        final double depth;
        final SkyCatalogue existing;
        FutureTask<Void> future;
        volatile boolean fitting;

        /** Captures the session's detached frame array and the values admitted on the EDT. */
        Request(FitsFileInformation[] files, SkyField field, double depth, SkyCatalogue existing) {
            this.files = files;
            this.field = field;
            this.depth = depth;
            this.existing = existing;
            fitting = existing != null;
        }
    }

    private final ProcessingSession session;
    private final BooleanSupplier currentSession;
    private final Supplier<Work> work;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sky-catalogue");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Timer timer = new Timer(1000, event -> changed());
    private Listener listener;
    private Request active;
    private String stage = "";
    private long stageStarted;
    private boolean cancelling;
    private String catalogueProblem;
    private String correctionProblem;

    /** Creates the desktop workflow on the EDT and gives its shutdown to the session. */
    SkyCatalogueController(ProcessingSession session, BooleanSupplier currentSession, Listener listener) {
        this(session, currentSession, listener, CatalogueWork::new);
    }

    /** Uses explicit scientific collaborators for deterministic lifetime and stale-delivery tests. */
    SkyCatalogueController(ProcessingSession session, BooleanSupplier currentSession, Listener listener, Supplier<Work> work) {
        this.session = Objects.requireNonNull(session);
        this.currentSession = Objects.requireNonNull(currentSession);
        this.listener = Objects.requireNonNull(listener);
        this.work = Objects.requireNonNull(work);
        session.ownSkyCatalogueWork(this::close);
    }

    /** Starts a fetch with the current field and captured depth, or cancels the current request; EDT only. */
    void fetchOrCancel(double depth) {
        if (isRunning()) {
            cancel();
            return;
        }
        FitsFileInformation[] files = session.getFiles();
        SkyField field = field(files);
        if (field == null) {
            catalogueProblem = "No frame has a plate solution in its FITS header.";
            changed();
            return;
        }
        catalogueProblem = null;
        correctionProblem = null;
        listener.message("Fetching the sky catalogue (" + field.sizeText() + " field)…");
        start(field, depth, null);
    }

    /** Starts the saved catalogue's missing correction, using imported metadata rather than rereading files. */
    void restore() {
        SkyCatalogue saved = session.getSkyCatalogue().get();
        SkyField field = field(session.getFiles());
        if (saved != null && session.getSkyCatalogue().getCorrection() == null && !saved.stars.isEmpty() && field != null) {
            start(field, saved.starMagnitudeLimit, saved);
        }
    }

    /** Resolves the uncorrected field from the captured session metadata, as before the extraction. */
    private static SkyField field(FitsFileInformation[] files) {
        if (files.length == 0) return null;
        var solution = WcsSolutionResolver.resolveUncorrected(null, files);
        return solution == null ? null : SkyField.of(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight());
    }

    /** Admits or replaces a request on the EDT; the request identity invalidates all older queued callbacks. */
    void start(SkyField field, double depth, SkyCatalogue existing) {
        if (closed.get() || session.isClosed()) throw new IllegalStateException("Sky catalogue workflow is closed.");
        if (active != null) active.future.cancel(true);
        Request request = new Request(session.getFiles(), field, depth, existing);
        request.future = new FutureTask<>(() -> { run(request); return null; }) {
            /** Keeps memory failures on the worker for the application's fatal handler, even after cancellation. */
            @Override protected void setException(Throwable failure) {
                JobFailures.rethrowOutOfMemory(failure);
                super.setException(failure);
            }

            /** Ends even a request cancelled before its worker starts, so the progress indicator cannot stick. */
            @Override protected void done() {
                if (isCancelled()) {
                    post(request, () -> cancelled(request));
                    return;
                }
                try {
                    get();
                } catch (ExecutionException failure) {
                    LOGGER.log(Level.WARNING, "Sky catalogue work failed", failure.getCause());
                    post(request, () -> failed(request, failure.getCause().getMessage()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    post(request, () -> cancelled(request));
                }
            }
        };
        active = request;
        cancelling = false;
        stage = "";
        showStage(request, existing == null ? "Sky catalogue: starting" : "Checking the plate solution: starting");
        timer.start();
        executor.execute(request.future);
    }

    /** Runs the existing fetch/fit operations off the EDT, publishing only guarded callbacks. */
    private void run(Request request) {
        Work operations = work.get();
        SkyCatalogue catalogue = request.existing;
        if (catalogue == null) {
            SkyCatalogue result;
            try {
                result = operations.fetch(request.field, request.depth, (number, total, text) ->
                        post(request, () -> showStage(request, "Sky catalogue " + number + "/" + total + ": " + text)));
            } catch (InterruptedException interrupted) {
                post(request, () -> cancelled(request));
                return;
            } catch (RuntimeException failure) {
                LOGGER.log(Level.WARNING, "Sky catalogue fetch failed", failure);
                post(request, () -> catalogueFinished(request, null, failure.getMessage(), false));
                return;
            }
            boolean fitFollows = result != null && !result.stars.isEmpty();
            post(request, () -> catalogueFinished(request, result, null, fitFollows));
            if (!fitFollows) return;
            catalogue = result;
        }
        request.fitting = true;
        try {
            PlateSolutionCorrection correction = operations.fit(request.files, catalogue, text ->
                    post(request, () -> showStage(request, "Checking the plate solution: " + text)));
            post(request, () -> correctionFinished(request, correction, null));
        } catch (InterruptedException interrupted) {
            post(request, () -> cancelled(request));
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Plate solution check failed", failure);
            post(request, () -> correctionFinished(request, null, failure.getMessage()));
        }
    }

    /** Queues delivery without trusting cancellation to retract callbacks already on the EDT. */
    private void post(Request request, Runnable delivery) {
        EventQueue.invokeLater(() -> {
            if (accepts(request)) delivery.run();
        });
    }

    /** Both identities and both lifetimes must still match when a callback executes on the EDT. */
    private boolean accepts(Request request) {
        return !closed.get() && !session.isClosed() && active == request && currentSession.getAsBoolean();
    }

    /** Changes the elapsed-time origin only when a different stage arrives; EDT only. */
    private void showStage(Request request, String text) {
        if (!accepts(request)) return;
        if (!text.equals(stage)) {
            stage = text;
            stageStarted = System.currentTimeMillis();
        }
        changed();
    }

    /** Installs a completed catalogue and queues its save before the later fit result; EDT only. */
    private void catalogueFinished(Request request, SkyCatalogue result, String failure, boolean fitFollows) {
        if (!fitFollows) stop();
        if (failure != null || result == null) {
            catalogueProblem = "The fetch failed: " + failure;
            listener.message("Sky catalogue: the fetch failed");
        } else if (result.isEmpty()) {
            catalogueProblem = result.problems.isEmpty() ? "The services found nothing in this field." : String.join("\n", result.problems);
            listener.message("Sky catalogue: nothing could be fetched");
        } else {
            catalogueProblem = result.problems.isEmpty() ? null : String.join("\n", result.problems);
            session.saveCatalogue(result, problem -> {
                if (problem != null && !closed.get() && currentSession.getAsBoolean() && session.getSkyCatalogue().get() == result) {
                    LOGGER.log(Level.WARNING, "Cannot save the sky catalogue", problem);
                    catalogueProblem = "The catalogue could not be saved (" + problem.getMessage() + "); it lasts until SpacePixels closes.";
                    changed();
                }
            });
            session.getSkyCatalogue().set(result);
            listener.message("Sky catalogue: " + result.summary() + (result.problems.isEmpty() ? "" : " (incomplete)"));
        }
        changed();
    }

    /** Applies the existing correction policy and preserves the previous explicit viewer choice; EDT only. */
    private void correctionFinished(Request request, PlateSolutionCorrection correction, String failure) {
        stop();
        if (correction == null) {
            correctionProblem = "The plate solution could not be checked: " + failure;
            listener.message("Plate solution check failed");
        } else {
            correctionProblem = null;
            correction.initializeEnabled(session.getSkyCatalogue().getCorrection());
            try {
                session.getSkyCatalogue().saveCorrection(correction);
            } catch (IOException cannotSave) {
                correctionProblem = "It could not be saved in the session folder (" + cannotSave.getMessage() + ").";
            }
            listener.message(!correction.isNeeded()
                    ? String.format(Locale.US, "Plate solution checked: the FITS header already matches the stars (%.2f px)", correction.beforeMedianPx)
                    : "Plate solution checked: " + correction.improvementText()
                    + (correction.enabled ? correction.userEnabled == null && correction.hasSignificantImprovement()
                    ? ", applied automatically (C switches it off)" : ", correction in use"
                    : "; switch the correction on in a viewer (C)"));
        }
        changed();
    }

    /** Ends a cancelled request even when its scientific operation ignores interruption and returns late. */
    private void cancelled(Request request) {
        stop();
        listener.message(request.fitting ? "Plate solution check: cancelled" : "Sky catalogue: cancelled");
        changed();
    }

    /** Converts an unexpected worker failure into the same phase-specific presentation as a service failure. */
    private void failed(Request request, String failure) {
        if (request.fitting) correctionFinished(request, null, failure);
        else catalogueFinished(request, null, failure, false);
    }

    /** Requests interruption and leaves terminal delivery to the Future, including a not-yet-started request. */
    void cancel() {
        if (active == null) return;
        cancelling = true;
        active.future.cancel(true);
        changed();
    }

    /** Releases the current request and its progress clock after its terminal callback; EDT only. */
    private void stop() {
        active = null;
        timer.stop();
    }

    /** Whether a request still owns the controller, for command availability and the progress indicator. */
    boolean isRunning() { return active != null; }
    /** The current phase description, for the view's short astrometry status. */
    String stage() { return stage; }
    /** Last fetch/service/save problem, independent of whether useful catalogue results remain in use. */
    String catalogueProblem() { return catalogueProblem; }
    /** Last correction-fit/save problem, independent of the installed correction. */
    String correctionProblem() { return correctionProblem; }

    /** The view's elapsed progress text, read on the EDT. */
    String progressText() {
        if (cancelling) return "Cancelling…";
        long seconds = (System.currentTimeMillis() - stageStarted) / 1000;
        return stage + "…" + (seconds >= 2 ? " " + seconds + " s" : "");
    }

    /** Notifies only the still-attached view, on the EDT. */
    private void changed() {
        if (listener != null) listener.changed();
    }

    /** Stops work immediately from any thread; timer cleanup and view detachment are serialized on the EDT. */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
        Runnable detach = () -> {
            if (active != null) active.future.cancel(true);
            stop();
            changed();
            listener = null;
        };
        if (EventQueue.isDispatchThread()) detach.run();
        else EventQueue.invokeLater(detach);
    }
}
