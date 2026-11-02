package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.jobs.JobFailures;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.solarsystem.SolarSystemClient;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import eu.startales.spacepixels.solarsystem.SolarSystemQuery;

import java.awt.EventQueue;
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
 * One session's Fetch Asteroids and Comets workflow, run like the sky catalogue's ({@link SkyCatalogueController}):
 * controls, progress and model publication belong to the EDT, an owned single-thread executor asks JPL. Session close
 * shuts the executor down, drops queued results and detaches the view; every delivery checks the request and the
 * session.
 */
final class SolarSystemController implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SolarSystemController.class.getName());

    /** The view receives only EDT notifications and reads the controller's current presentation state. */
    interface Listener {
        /** Refreshes progress, command availability and status on the EDT. */
        void changed();
        /** Shows a workflow outcome or starting message on the EDT. */
        void message(String text);
    }

    /** The JPL fetch, replaceable in tests to exercise delayed work without network access. */
    interface Work {
        /** Fetches the predictions on the worker; interruption cancels the request. */
        SolarSystemObjects fetch(SolarSystemQuery query, SolarSystemClient.Progress progress) throws InterruptedException;
    }

    /** Identity and captured query of one request. */
    private static final class Request {
        final SolarSystemQuery query;
        FutureTask<Void> future;

        Request(SolarSystemQuery query) {
            this.query = query;
        }
    }

    private final ProcessingSession session;
    private final BooleanSupplier currentSession;
    private final Supplier<Work> work;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "solar-system");
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
    private String problem;

    /** Creates the desktop workflow on the EDT and gives its shutdown to the session. */
    SolarSystemController(ProcessingSession session, BooleanSupplier currentSession, Listener listener) {
        this(session, currentSession, listener, () -> new SolarSystemClient()::fetch);
    }

    /** Uses an explicit fetch for deterministic lifetime and stale-delivery tests. */
    SolarSystemController(ProcessingSession session, BooleanSupplier currentSession, Listener listener, Supplier<Work> work) {
        this.session = Objects.requireNonNull(session);
        this.currentSession = Objects.requireNonNull(currentSession);
        this.listener = Objects.requireNonNull(listener);
        this.work = Objects.requireNonNull(work);
        session.ownSolarSystemWork(this::close);
    }

    /** Starts a fetch for the session's frames with these settings, or cancels the current one; EDT only. */
    void fetchOrCancel(AppConfig config) {
        if (isRunning()) {
            cancel();
            return;
        }
        SolarSystemQuery query;
        try {
            query = SolarSystemQuery.of(session.getFiles(), config);
        } catch (IllegalArgumentException missing) {
            problem = missing.getMessage();
            listener.message("Asteroids and comets: " + missing.getMessage());
            changed();
            return;
        }
        problem = null;
        listener.message(String.format(java.util.Locale.US, "Asking JPL for the asteroids and comets of the field, to V %.1f…",
                query.magnitudeLimit()));
        start(query);
    }

    /** Admits a request on the EDT; its identity invalidates all older queued callbacks. */
    private void start(SolarSystemQuery query) {
        if (closed.get() || session.isClosed()) throw new IllegalStateException("Asteroid and comet workflow is closed.");
        Request request = new Request(query);
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
                    LOGGER.log(Level.WARNING, "Asteroid and comet fetch failed", failure.getCause());
                    post(request, () -> finished(request, null, failure.getCause().getMessage()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    post(request, () -> cancelled(request));
                }
            }
        };
        active = request;
        cancelling = false;
        stage = "";
        showStage(request, "Asteroids and comets: starting");
        timer.start();
        executor.execute(request.future);
    }

    /** Runs the fetch off the EDT, publishing only guarded callbacks. */
    private void run(Request request) {
        SolarSystemObjects result;
        try {
            result = work.get().fetch(request.query, text -> post(request, () -> showStage(request, "Asteroids and comets: " + text)));
        } catch (InterruptedException interrupted) {
            post(request, () -> cancelled(request));
            return;
        }
        post(request, () -> finished(request, result, null));
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

    /**
     * Installs and saves predictions when the field search answered, even with no object in the field; keeps the
     * previous ones when nothing could be fetched. EDT only.
     */
    private void finished(Request request, SolarSystemObjects result, String failure) {
        stop();
        if (failure != null || result == null) {
            problem = "The fetch failed: " + failure;
            listener.message("Asteroids and comets: JPL request failed"
                    + (failure == null || failure.isBlank() ? "" : " — " + failure));
        } else if (result.source == null || result.source.isEmpty()) {
            problem = result.problems.isEmpty() ? "JPL did not answer." : String.join("\n", result.problems);
            String detail = result.problems.isEmpty() ? "JPL did not answer."
                    : result.problems.get(0).replaceFirst("^Field search \\(JPL SB-Ident\\):\\s*", "");
            listener.message("Asteroids and comets: " + detail);
        } else {
            problem = result.problems.isEmpty() ? null : String.join("\n", result.problems);
            session.saveSolarSystem(result, saveProblem -> {
                if (saveProblem != null && !closed.get() && currentSession.getAsBoolean()
                        && session.getSkyCatalogue().getSolarSystem() == result) {
                    LOGGER.log(Level.WARNING, "Cannot save the asteroid and comet predictions", saveProblem);
                    problem = "The predictions could not be saved (" + saveProblem.getMessage() + "); they last until SpacePixels closes.";
                    changed();
                }
            });
            session.getSkyCatalogue().setSolarSystem(result);
            listener.message("Asteroids and comets: " + result.summary() + " in the field"
                    + (result.problems.isEmpty() ? "" : " (incomplete)") + (result.bodies.isEmpty() ? "" : " · A in a viewer shows them"));
        }
        changed();
    }

    /** Ends a cancelled request even when the fetch ignores interruption and returns late. */
    private void cancelled(Request request) {
        stop();
        listener.message("Asteroids and comets: cancelled");
        changed();
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
    /** Last fetch or save problem, independent of whether predictions remain in use. */
    String problem() { return problem; }

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
