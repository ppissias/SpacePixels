package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.session.SessionMetadata;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.PlateSolveService;
import eu.startales.spacepixels.util.skycatalog.PlateCorrectionFitter;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueClient;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Drives real workers and EDT delivery without the window or network, including deliberately late results. */
public class SkyCatalogueControllerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final List<ProcessingSession> sessions = new ArrayList<>();
    private final List<ApplicationStorage> stores = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    /** Releases deliberately stubborn workers, then closes session-owned resources and drains captured writes. */
    @After public void closeResources() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        edt(() -> { sessions.forEach(ProcessingSession::close); return null; });
        for (ApplicationStorage store : stores) {
            store.close();
            store.finish();
        }
        edt(() -> null);
    }

    /** A fetched catalogue is available while fitting, and the fit preserves the user's explicit correction choice. */
    @Test public void fetchIsPublishedBeforeFitAndEveryNotificationUsesTheEdt() throws Exception {
        SkyCatalogue catalogue = catalogue(true);
        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.solutionKey = "same solution";
        correction.width = correction.height = 20;
        correction.global = new PlateSolutionCorrection.Polynomial();
        correction.global.scale = 1;
        correction.global.ax = new double[]{1};
        correction.global.ay = new double[]{1};
        correction.beforeMedianPx = 3;
        correction.beforeP90Px = 4;
        correction.afterMedianPx = 0.2;
        correction.afterP90Px = 0.5;
        CountDownLatch fitting = new CountDownLatch(1), releaseFit = gate();
        Recorder view = new Recorder();
        ProcessingSession session = session();
        PlateSolutionCorrection previous = new PlateSolutionCorrection();
        previous.solutionKey = correction.solutionKey;
        previous.width = previous.height = 20;
        previous.userEnabled = false;
        edt(() -> { session.getSkyCatalogue().setCorrection(previous); return null; });
        SkyCatalogueController controller = controller(session, view, () -> new EmptyWork() {
            /** Returns the captured catalogue off the EDT. */
            @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                assertFalse(EventQueue.isDispatchThread());
                assertEquals(15, depth, 0);
                progress.stage(1, 3, "stars");
                return catalogue;
            }
            /** Holds the correction so the test observes catalogue publication independently. */
            @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue result,
                    PlateCorrectionFitter.Progress progress) throws InterruptedException {
                assertFalse(EventQueue.isDispatchThread());
                assertSame(catalogue, result);
                fitting.countDown();
                releaseFit.await();
                return correction;
            }
        });
        edt(() -> { controller.start(null, 15, null); return null; });
        assertTrue(fitting.await(5, TimeUnit.SECONDS));
        await(() -> edt(() -> session.getSkyCatalogue().get() == catalogue));
        assertTrue(edt(controller::isRunning));
        assertSame(previous, edt(() -> session.getSkyCatalogue().getCorrection()));
        releaseFit.countDown();
        await(() -> edt(() -> !controller.isRunning()));
        assertSame(correction, edt(() -> session.getSkyCatalogue().getCorrection()));
        assertEquals(Boolean.FALSE, correction.userEnabled);
        assertFalse(correction.enabled);
        assertTrue(view.messages.getLast().startsWith("Plate solution checked:"));
        assertTrue(view.edtOnly);
    }

    /** Importing saved stars without a correction runs only the fit against the already imported metadata. */
    @Test public void restoringSavedCatalogueFitsWithoutFetchingAgain() throws Exception {
        ProcessingSession session = session();
        session.replaceFrameHeader(session.getFiles()[0], Map.of(
                "CTYPE1", "RA---TAN", "CTYPE2", "DEC--TAN", "CRPIX1", "10", "CRPIX2", "10",
                "CRVAL1", "120", "CRVAL2", "30", "CD1_1", "0.001", "CD1_2", "0", "CD2_1", "0", "CD2_2", "0.001"));
        SkyCatalogue saved = catalogue(true);
        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        Recorder view = new Recorder();
        SkyCatalogueController controller = controller(session, view, () -> new EmptyWork() {
            /** A restored catalogue must not trigger a network fetch. */
            @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                throw new AssertionError("Saved catalogue was fetched again");
            }
            /** Verifies the restored catalogue and imported frame metadata are the fit inputs. */
            @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue catalogue,
                    PlateCorrectionFitter.Progress progress) {
                assertSame(saved, catalogue);
                assertEquals("frame.fit", files[0].getFileName());
                return correction;
            }
        });
        edt(() -> { session.getSkyCatalogue().set(saved); controller.restore(); return null; });
        await(() -> edt(() -> !controller.isRunning()));
        assertSame(saved, edt(() -> session.getSkyCatalogue().get()));
        assertSame(correction, edt(() -> session.getSkyCatalogue().getCorrection()));
    }

    /** Queued progress, catalogue and fit callbacks from a replaced request cannot alter a same-session refetch. */
    @Test public void sameSessionReplacementRejectsAlreadyQueuedResults() throws Exception {
        SkyCatalogue first = catalogue(true), second = catalogue(false);
        CountDownLatch firstQueued = new CountDownLatch(1), releaseFirst = gate();
        AtomicInteger requests = new AtomicInteger();
        ProcessingSession session = session();
        Recorder view = new Recorder();
        SkyCatalogueController controller = controller(session, view, () -> requests.getAndIncrement() == 0
                ? new EmptyWork() {
                    /** The fit starts only after the first fetch completion has been queued. */
                    @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                        progress.stage(1, 3, "old stage");
                        return first;
                    }
                    /** Ignores interruption deliberately so its eventual result must be rejected by identity. */
                    @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue result,
                            PlateCorrectionFitter.Progress progress) {
                        firstQueued.countDown();
                        waitIgnoringInterrupt(releaseFirst);
                        progress.update("late old fit");
                        return new PlateSolutionCorrection();
                    }
                } : new EmptyWork() {
                    /** The replacement's useful non-star catalogue completes without a correction fit. */
                    @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                        return second;
                    }
                });
        edt(() -> {
            controller.start(null, 15, null);
            assertTrue(firstQueued.await(5, TimeUnit.SECONDS));
            controller.start(null, 16, null);
            return null;
        });
        edt(() -> { assertNull(session.getSkyCatalogue().get()); return null; });
        releaseFirst.countDown();
        await(() -> edt(() -> !controller.isRunning()));
        assertSame(second, edt(() -> session.getSkyCatalogue().get()));
        assertNull(edt(() -> session.getSkyCatalogue().getCorrection()));
        assertFalse(edt(controller::stage).contains("old"));
        assertEquals(1, view.messages.size());
    }

    /** Future cancellation ends a replacement queued behind stubborn work, without waiting for it to start. */
    @Test public void cancellingBeforeWorkerAdmissionCannotLeaveProgressRunning() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = gate();
        AtomicInteger operations = new AtomicInteger();
        ProcessingSession session = session();
        Recorder view = new Recorder();
        SkyCatalogueController controller = controller(session, view, () -> {
            operations.incrementAndGet();
            return new EmptyWork() {
                /** Retains the executor until the test releases it, even after cancellation. */
                @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                    started.countDown();
                    waitIgnoringInterrupt(release);
                    return catalogue(false);
                }
            };
        });
        edt(() -> { controller.start(null, 15, null); return null; });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        edt(() -> { controller.start(null, 16, null); controller.cancel(); return null; });
        await(() -> edt(() -> !controller.isRunning()));
        assertEquals(List.of("Sky catalogue: cancelled"), view.messages);
        assertEquals(1, operations.get());
        release.countDown();
        edt(() -> { assertNull(session.getSkyCatalogue().get()); return null; });
    }

    /** Session closure invalidates queued results, interrupts work, stops the clock and detaches the listener. */
    @Test public void sessionCloseDropsQueuedResultsAndStopsOwnedResources() throws Exception {
        CountDownLatch fitting = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = gate();
        ProcessingSession session = session();
        Recorder view = new Recorder();
        SkyCatalogueController controller = controller(session, view, () -> new EmptyWork() {
            /** Queues a useful catalogue before entering a delayed fit. */
            @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                return catalogue(true);
            }
            /** Records shutdown interruption but returns a result late, after the session is closed. */
            @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue result,
                    PlateCorrectionFitter.Progress progress) {
                fitting.countDown();
                try { release.await(); } catch (InterruptedException cancelled) { interrupted.countDown(); }
                waitIgnoringInterrupt(release);
                return new PlateSolutionCorrection();
            }
        });
        edt(() -> {
            controller.start(null, 15, null);
            assertTrue(fitting.await(5, TimeUnit.SECONDS));
            session.close();
            return null;
        });
        assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        release.countDown();
        edt(() -> {
            assertFalse(controller.isRunning());
            assertNull(session.getSkyCatalogue().get());
            assertNull(session.getSkyCatalogue().getCorrection());
            assertTrue(view.messages.isEmpty());
            var timerField = SkyCatalogueController.class.getDeclaredField("timer");
            timerField.setAccessible(true);
            assertFalse(((javax.swing.Timer) timerField.get(controller)).isRunning());
            var listenerField = SkyCatalogueController.class.getDeclaredField("listener");
            listenerField.setAccessible(true);
            assertNull(listenerField.get(controller));
            return null;
        });
    }

    /** A lost current-session identity is independently sufficient to suppress queued result delivery. */
    @Test public void sessionHandoverRejectsQueuedResultsBeforeOldSessionClose() throws Exception {
        ProcessingSession session = session();
        AtomicBoolean current = new AtomicBoolean(true);
        CountDownLatch fitStarted = new CountDownLatch(1), release = gate();
        Recorder view = new Recorder();
        SkyCatalogueController controller = edt(() -> new SkyCatalogueController(session, current::get, view,
                () -> new EmptyWork() {
                    /** Queues a fetched catalogue while the EDT is held by the handover. */
                    @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                        return catalogue(true);
                    }
                    /** Keeps the worker available for cancellation after the session identity changes. */
                    @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue catalogue,
                            PlateCorrectionFitter.Progress progress) throws InterruptedException {
                        fitStarted.countDown();
                        release.await();
                        return new PlateSolutionCorrection();
                    }
                }));
        edt(() -> {
            controller.start(null, 15, null);
            assertTrue(fitStarted.await(5, TimeUnit.SECONDS));
            current.set(false);
            return null;
        });
        edt(() -> {
            assertNull(session.getSkyCatalogue().get());
            assertTrue(view.messages.isEmpty());
            controller.close();
            return null;
        });
    }

    /** A failed fit keeps the completed catalogue, and a failed metadata save keeps that catalogue in use too. */
    @Test public void fitAndCatalogueSaveFailuresKeepCompletedCatalogueAvailable() throws Exception {
        ProcessingSession session = session();
        File target = new File(new File(session.getFiles()[0].getFilePath()).getParentFile(), "sky_catalogue.json");
        Files.createDirectory(target.toPath());
        Files.writeString(target.toPath().resolve("keep.txt"), "refuse replacement");
        SkyCatalogue catalogue = catalogue(true);
        Recorder view = new Recorder();
        SkyCatalogueController controller = controller(session, view, () -> new EmptyWork() {
            /** Finishes a useful fetch despite the target's later save failure. */
            @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                return catalogue;
            }
            /** Fails after fetching, exercising the independent correction outcome. */
            @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue result,
                    PlateCorrectionFitter.Progress progress) throws IOException {
                throw new IOException("fit failed");
            }
        });
        edt(() -> { controller.start(null, 15, null); return null; });
        await(() -> edt(() -> !controller.isRunning() && controller.catalogueProblem() != null));
        assertSame(catalogue, edt(() -> session.getSkyCatalogue().get()));
        assertTrue(edt(controller::catalogueProblem).contains("could not be saved"));
        assertTrue(edt(controller::correctionProblem).contains("fit failed"));
        assertTrue(view.messages.contains("Plate solution check failed"));
    }

    /** A fetch failure produces a terminal outcome rather than leaving the view in its running state. */
    @Test public void fetchFailureReleasesProgress() throws Exception {
        ProcessingSession session = session();
        Recorder view = new Recorder();
        SkyCatalogueController controller = controller(session, view, () -> new EmptyWork() {
            /** Supplies the expected service failure from a real worker thread. */
            @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                throw new IllegalStateException("fetch failed");
            }
        });
        edt(() -> { controller.start(null, 15, null); return null; });
        await(() -> edt(() -> !controller.isRunning()));
        assertTrue(edt(controller::catalogueProblem).contains("fetch failed"));
        assertEquals(List.of("Sky catalogue: the fetch failed"), view.messages);
    }

    /** Future cancellation must not swallow a late memory failure that must reach the application's fatal handler. */
    @Test public void fatalMemoryFailureStillEscapesACancelledFuture() throws Exception {
        ProcessingSession session = session();
        Recorder view = new Recorder();
        CountDownLatch started = new CountDownLatch(1), release = gate(), handled = new CountDownLatch(1);
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        OutOfMemoryError failure = new OutOfMemoryError("simulated, without exhausting memory");
        SkyCatalogueController controller = controller(session, view, () -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
                uncaught.set(error);
                handled.countDown();
            });
            return new EmptyWork() {
                /** Delivers a simulated fatal failure after cancellation, with a handler local to this worker. */
                @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress) {
                    started.countDown();
                    waitIgnoringInterrupt(release);
                    throw failure;
                }
            };
        });
        edt(() -> { controller.start(null, 15, null); return null; });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        edt(() -> { controller.cancel(); return null; });
        await(() -> edt(() -> !controller.isRunning()));
        release.countDown();
        assertTrue(handled.await(5, TimeUnit.SECONDS));
        assertSame(failure, uncaught.get());
        assertEquals(List.of("Sky catalogue: cancelled"), view.messages);
    }

    /** Builds an isolated desktop session whose queued saves and callbacks use the real storage and EDT. */
    private ProcessingSession session() throws Exception {
        File directory = folder.newFolder();
        ApplicationStorage storage = new ApplicationStorage();
        stores.add(storage);
        ProcessingSession session = new ProcessingSession(new PlateSolveService(new AppConfig()),
                new FitsFileInformation[]{new FitsFileInformation(new File(directory, "frame.fit").toString(), "frame.fit", true, 20, 20)},
                null, SessionMetadata.empty(), storage, EventQueue::invokeLater);
        sessions.add(session);
        return session;
    }

    /** Creates a controller on the EDT using the scientific seam exercised by these tests. */
    private static SkyCatalogueController controller(ProcessingSession session, Recorder view,
            Supplier<SkyCatalogueController.Work> work) throws Exception {
        return edt(() -> new SkyCatalogueController(session, () -> true, view, work));
    }

    /** Builds a useful catalogue; a catalogue without stars completes without fitting. */
    private static SkyCatalogue catalogue(boolean stars) {
        SkyCatalogue result = new SkyCatalogue();
        if (stars) result.stars.add(new SkyCatalogue.Star());
        else result.deepSky.add(new SkyCatalogue.DeepSkyObject());
        return result;
    }

    /** Owns a latch that teardown releases even when an assertion fails. */
    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    /** Models a non-cooperative service so interruption alone cannot make stale-result tests pass. */
    private static void waitIgnoringInterrupt(CountDownLatch gate) {
        boolean interrupted = false;
        for (;;) {
            try { gate.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /** Runs an assertion/read on the real event dispatch thread and propagates its failure. */
    private static <Value> Value edt(Callable<Value> action) throws Exception {
        FutureTask<Value> result = new FutureTask<>(action);
        EventQueue.invokeAndWait(result);
        return result.get();
    }

    /** Waits with a bounded timeout while repeatedly letting the EDT process queued callbacks. */
    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for controller delivery");
            Thread.sleep(10);
        }
    }

    /** Records EDT notifications without invoking application code or opening a window. */
    private static final class Recorder implements SkyCatalogueController.Listener {
        final List<String> messages = new ArrayList<>();
        boolean edtOnly = true;
        /** Checks that progress/model changes reach the view only on the EDT. */
        @Override public void changed() { edtOnly &= EventQueue.isDispatchThread(); }
        /** Captures terminal/start messages on the EDT. */
        @Override public void message(String text) {
            edtOnly &= EventQueue.isDispatchThread();
            messages.add(text);
        }
    }

    /** Default scientific stub; individual tests override only the operation whose timing matters. */
    private static class EmptyWork implements SkyCatalogueController.Work {
        /** Completes an empty fetch unless a test supplies a result or failure. */
        @Override public SkyCatalogue fetch(SkyField field, double depth, SkyCatalogueClient.Progress progress)
                throws InterruptedException { return new SkyCatalogue(); }
        /** Completes an unchanged plate solution unless a test controls the fit. */
        @Override public PlateSolutionCorrection fit(FitsFileInformation[] files, SkyCatalogue catalogue,
                PlateCorrectionFitter.Progress progress) throws IOException, InterruptedException {
            return new PlateSolutionCorrection();
        }
    }
}
