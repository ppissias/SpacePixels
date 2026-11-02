package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.platesolve.PlateSolveService;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.session.SessionMetadata;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import eu.startales.spacepixels.solarsystem.SolarSystemStore;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.EventQueue;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The Fetch Asteroids and Comets workflow: publication and saving on the EDT, failures, missing inputs and cancelling. */
public class SolarSystemControllerTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private final List<ProcessingSession> sessions = new ArrayList<>();
    private final List<ApplicationStorage> stores = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    /** Releases blocked workers, then closes the sessions and drains their writes. */
    @After public void closeResources() throws Exception {
        gates.forEach(CountDownLatch::countDown);
        edt(() -> { sessions.forEach(ProcessingSession::close); return null; });
        for (ApplicationStorage store : stores) {
            store.close();
            store.finish();
        }
        edt(() -> null);
    }

    @Test public void aFetchIsShownAndSavedWithTheSession() throws Exception {
        ProcessingSession session = session(true);
        SolarSystemObjects predictions = predictions("JPL SB-Ident API 1.1");
        Recorder view = new Recorder();
        SolarSystemController controller = edt(() -> new SolarSystemController(session, () -> true, view,
                () -> (query, progress) -> {
                    progress.stage("asteroids and comets in the field (JPL)");
                    assertEquals(18.5, query.magnitudeLimit(), 0);
                    return predictions;
                }));
        AppConfig config = new AppConfig();
        config.solarSystemMagnitude = 18.5;
        edt(() -> { controller.fetchOrCancel(config); return null; });
        await(() -> edt(() -> session.getSkyCatalogue().getSolarSystem()) == predictions);
        assertFalse(edt(controller::isRunning));
        assertNull(edt(controller::problem));
        assertTrue(view.edtOnly);
        assertTrue(view.messages.toString(), view.messages.get(view.messages.size() - 1).contains("1 asteroid in the field"));
        File saved = SolarSystemStore.fileIn(new File(session.getFiles()[0].getFilePath()).getParentFile());
        await(saved::isFile);
    }

    @Test public void aFailedFetchKeepsThePreviousPredictions() throws Exception {
        ProcessingSession session = session(true);
        SolarSystemObjects previous = predictions("JPL SB-Ident API 1.1");
        edt(() -> { session.getSkyCatalogue().setSolarSystem(previous); return null; });
        SolarSystemObjects nothing = predictions("");
        nothing.problems.add("Field search (JPL SB-Ident): JPL did not answer within 300 s.");
        Recorder view = new Recorder();
        SolarSystemController controller = edt(() -> new SolarSystemController(session, () -> true, view, () -> (query, progress) -> nothing));
        edt(() -> { controller.fetchOrCancel(new AppConfig()); return null; });
        await(() -> edt(() -> !controller.isRunning()));
        assertSame(previous, edt(() -> session.getSkyCatalogue().getSolarSystem()));
        assertTrue(edt(controller::problem).contains("did not answer"));
        assertEquals("Asteroids and comets: JPL did not answer within 300 s.",
                view.messages.get(view.messages.size() - 1));
    }

    @Test public void aMissingSiteIsSaidWithoutAskingJpl() throws Exception {
        ProcessingSession session = session(false);
        AtomicInteger fetches = new AtomicInteger();
        Recorder view = new Recorder();
        SolarSystemController controller = edt(() -> new SolarSystemController(session, () -> true, view,
                () -> (query, progress) -> { fetches.incrementAndGet(); return null; }));
        edt(() -> { controller.fetchOrCancel(new AppConfig()); return null; });
        assertFalse(edt(controller::isRunning));
        assertTrue(edt(controller::problem), edt(controller::problem).startsWith("No observing site"));
        assertEquals(0, fetches.get());
    }

    @Test public void aRunningFetchIsCancelledAndItsLateResultDropped() throws Exception {
        ProcessingSession session = session(true);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = gate();
        Recorder view = new Recorder();
        SolarSystemController controller = edt(() -> new SolarSystemController(session, () -> true, view, () -> (query, progress) -> {
            started.countDown();
            // A service that ignores the interruption and answers late.
            boolean interrupted = false;
            for (;;) {
                try { release.await(); break; } catch (InterruptedException ignored) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return predictions("JPL SB-Ident API 1.1");
        }));
        edt(() -> { controller.fetchOrCancel(new AppConfig()); return null; });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(edt(controller::isRunning));
        edt(() -> { controller.fetchOrCancel(new AppConfig()); return null; });
        await(() -> edt(() -> !controller.isRunning()));
        release.countDown();
        edt(() -> null);
        Thread.sleep(100);
        assertNull("the late result is dropped", edt(() -> session.getSkyCatalogue().getSolarSystem()));
        assertTrue(view.messages.toString(), view.messages.contains("Asteroids and comets: cancelled"));
    }

    // ==========================================
    // FIXTURES
    // ==========================================

    /** A session of one plate-solved frame with a time, and with or without a site in its header. */
    private ProcessingSession session(boolean site) throws Exception {
        File directory = folder.newFolder();
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "10.5");
        header.put("CRPIX2", "10.5");
        header.put("CRVAL1", "150.0");
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-1.5 / 3600));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(1.5 / 3600));
        header.put("DATE-OBS", "2026-03-10T02:00:00");
        header.put("EXPTIME", "60");
        if (site) {
            header.put("SITELAT", "37.9");
            header.put("SITELONG", "23.7");
        }
        FitsFileInformation frame = new FitsFileInformation(new File(directory, "frame.fit").toString(), "frame.fit", true, 20, 20)
                .withFitsHeader(header);
        ApplicationStorage storage = new ApplicationStorage();
        stores.add(storage);
        ProcessingSession session = new ProcessingSession(new PlateSolveService(new AppConfig()), new FitsFileInformation[]{frame},
                null, SessionMetadata.empty(), storage, EventQueue::invokeLater);
        sessions.add(session);
        return session;
    }

    private static SolarSystemObjects predictions(String source) {
        SolarSystemObjects objects = new SolarSystemObjects();
        objects.source = source;
        if (!source.isEmpty()) {
            SolarSystemObjects.Body body = new SolarSystemObjects.Body();
            body.name = "194 Prokne (A879 FA)";
            objects.bodies.add(body);
        }
        return objects;
    }

    private CountDownLatch gate() {
        CountDownLatch gate = new CountDownLatch(1);
        gates.add(gate);
        return gate;
    }

    private static <Value> Value edt(Callable<Value> action) throws Exception {
        FutureTask<Value> result = new FutureTask<>(action);
        EventQueue.invokeAndWait(result);
        return result.get();
    }

    private static void await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) fail("Timed out waiting for controller delivery");
            Thread.sleep(10);
        }
    }

    /** Records EDT notifications without opening a window. */
    private static final class Recorder implements SolarSystemController.Listener {
        final List<String> messages = new ArrayList<>();
        boolean edtOnly = true;
        @Override public void changed() { edtOnly &= EventQueue.isDispatchThread(); }
        @Override public void message(String text) {
            edtOnly &= EventQueue.isDispatchThread();
            messages.add(text);
        }
    }
}
