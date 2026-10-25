package eu.startales.spacepixels.session;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.SpacePixelsAppConfigIO;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.DetectionOverlays;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.PlateSolveService;
import eu.startales.spacepixels.util.TrackOverlay;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.engine.PipelineResult;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ProcessingSessionTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private ProcessingSession session(String name, ApplicationSettings settings) throws Exception {
        File directory = folder.newFolder(name);
        FitsFileInformation frame = new FitsFileInformation(new File(directory, "frame.fit").toString(), "frame.fit", true, 10, 10);
        return new ProcessingSession(new PlateSolveService(settings.getConfig()), new FitsFileInformation[]{frame}, null);
    }

    private ApplicationSettings settings() {
        return ApplicationSettings.load(new File(folder.getRoot(), "preferences.json"));
    }

    /**
     * Sessions solve with the window's one live settings object, as the window builds them: an unsaved edit reaches
     * every open session's solver at once, and a new session never reloads the settings file.
     */
    @Test
    public void applicationPreferencesSurviveImportsWithoutReloadingOrLosingUnsavedEdits() throws Exception {
        File file = new File(folder.getRoot(), SpacePixelsAppConfigIO.DEFAULT_FILENAME);
        Files.writeString(file.toPath(), "{\"siteLat\":\"12\"}");
        ApplicationSettings settings = ApplicationSettings.load(file);
        settings.getConfig().siteLat = "34";
        IOException refused = new IOException("bad apikey");
        try (ProcessingSession first = session("first", settings)) {
            Files.writeString(file.toPath(), "{\"siteLat\":\"56\"}");
            try (ProcessingSession second = session("second", settings)) {
                assertTrue(second.getPlateSolveService().astrometryNetFailure(refused).contains("anonymous login"));
                settings.getConfig().astrometryNetUseOwnKey = true;
                settings.getConfig().astrometryNetApiKey = "unsaved";
                assertTrue(first.getPlateSolveService().astrometryNetFailure(refused).contains("rejected the API key"));
                assertTrue(second.getPlateSolveService().astrometryNetFailure(refused).contains("rejected the API key"));
                assertEquals("34", settings.getConfig().siteLat);
                settings.save();
                assertEquals("34", SpacePixelsAppConfigIO.load(file).siteLat);
                assertNotEquals(first.getId(), second.getId());
            }
        }
    }

    @Test
    public void catalogueCorrectionSelectionAndTuningAreIsolatedBetweenSessions() throws Exception {
        ApplicationSettings settings = settings();
        try (ProcessingSession first = session("first", settings);
             ProcessingSession second = session("second", settings)) {
            AtomicInteger changes = new AtomicInteger();
            first.getSkyCatalogue().addListener(changes::incrementAndGet);
            SkyCatalogue catalogue = new SkyCatalogue();
            second.getSkyCatalogue().set(catalogue);
            assertNull(first.getSkyCatalogue().get());
            assertSame(catalogue, second.getSkyCatalogue().get());
            assertEquals(0, changes.get());
            PlateSolutionCorrection correction = new PlateSolutionCorrection();
            second.getSkyCatalogue().setCorrection(correction);
            assertNull(first.getSkyCatalogue().getCorrection());
            first.setSelection(first.getFiles());
            assertTrue(second.getSelection().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> second.setSelection(first.getFiles()));
            AutoTunerResult result = new AutoTunerResult();
            first.getAutoTune().remember(new AutoTuneState.Run(result, Instant.now(), 7, null));
            assertSame(result, first.getAutoTune().result());
            assertTrue(second.getAutoTune().isEmpty());
        }
    }

    @Test
    public void frameReferencesAreSharedButTheMetadataArrayAndSelectionAreProtected() throws Exception {
        try (ProcessingSession session = session("frames", settings())) {
            FitsFileInformation[] files = session.getFiles();
            session.setSelection(files);
            FitsFileInformation frame = files[0];
            files[0] = null;
            assertSame(frame, session.getFiles()[0]);
            assertSame(frame, session.getSelection().get(0));
            assertThrows(UnsupportedOperationException.class, () -> session.getSelection().clear());
        }
    }

    @Test
    public void wcsHeaderReplacementUpdatesSessionAndSelectionWhileAdmittedRunsKeepTheirSnapshot() throws Exception {
        ApplicationSettings settings = settings();
        try (ProcessingSession session = session("wcs-replace", settings);
             ProcessingSession other = session("other-session", settings)) {
            FitsFileInformation unsolved = session.getFiles()[0];
            session.setSelection(new FitsFileInformation[]{unsolved});
            assertFalse(session.hasPlateSolvedFrame());

            var admittedRun = eu.startales.spacepixels.util.reporting.ReportRunContext.capture(
                    eu.startales.spacepixels.config.DetectionRunOptions.defaults(), session.getFiles());

            java.util.Map<String, String> wcsHeader = java.util.Map.of(
                    "CTYPE1", "RA---TAN",
                    "CTYPE2", "DEC--TAN",
                    "CRPIX1", "5.5",
                    "CRPIX2", "5.5",
                    "CRVAL1", "150.0",
                    "CRVAL2", "20.0",
                    "CD1_1", "-0.0004166667",
                    "CD1_2", "0.0",
                    "CD2_1", "0.0",
                    "CD2_2", "0.0004166667");
            FitsFileInformation solved = session.replaceFrameHeader(unsolved, wcsHeader);

            assertNotSame(unsolved, solved);
            assertFalse(unsolved.isWcsSolved());
            assertTrue(solved.isWcsSolved());
            assertTrue(session.hasPlateSolvedFrame());
            assertSame(solved, session.getFiles()[0]);
            assertSame(solved, session.getSelection().get(0));

            assertSame(unsolved, admittedRun.filesInformation()[0]);
            assertFalse(admittedRun.filesInformation()[0].isWcsSolved());

            var nextRun = eu.startales.spacepixels.util.reporting.ReportRunContext.capture(
                    eu.startales.spacepixels.config.DetectionRunOptions.defaults(), session.getFiles());
            assertSame(solved, nextRun.filesInformation()[0]);
            assertTrue(nextRun.filesInformation()[0].isWcsSolved());

            assertThrows(IllegalArgumentException.class,
                    () -> session.replaceFrameHeader(other.getFiles()[0], wcsHeader));
            session.close();
            assertThrows(IllegalStateException.class,
                    () -> session.replaceFrameHeader(solved, wcsHeader));
        }
    }

    @Test
    public void handoverRejectsLateUpdatesAndStaleImportsWithoutConstructingTheWindow() throws Exception {
        ApplicationSettings settings = settings();
        ProcessingSession first = session("first", settings);
        ProcessingSession second = session("second", settings);
        ProcessingSession obsoleteImport = session("obsolete", settings);
        try (SessionOwner owner = new SessionOwner()) {
            assertTrue(owner.replace(null, first));
            assertTrue(owner.replace(first, second));
            assertTrue(first.isClosed());
            assertEquals(0, first.getFiles().length);
            assertFalse(owner.applyIfCurrent(first, () -> second.setLastReport(new File("wrong-report"))));
            assertNull(second.getLastReport());
            assertFalse(owner.replace(first, obsoleteImport));
            assertTrue(obsoleteImport.isClosed());
            assertSame(second, owner.current());
            assertTrue(owner.applyIfCurrent(second, () -> second.setLastReport(new File("right-report"))));
            assertEquals(new File("right-report"), second.getLastReport());
        }
        assertTrue(second.isClosed());
    }

    @Test
    public void delayedJobCallbacksCannotChangeTheNewSession() throws Exception {
        ApplicationSettings settings = settings();
        List<Runnable> workers = new ArrayList<>();
        List<Runnable> ui = new ArrayList<>();
        List<String> notifications = new ArrayList<>();
        JobCoordinator coordinator = new JobCoordinator(workers::add, ui::add, () -> false);
        try (SessionOwner owner = new SessionOwner()) {
            ProcessingSession first = session("first", settings);
            ProcessingSession second = session("second", settings);
            owner.replace(null, first);
            coordinator.setListener(new JobCoordinator.Listener() {
                @Override public void jobStarted(eu.startales.spacepixels.jobs.JobHandle handle) { }
                @Override public void jobProgress(eu.startales.spacepixels.jobs.JobHandle handle, int percent, String message) {
                    owner.applyIfCurrent(handle.session(), () -> notifications.add(message));
                }
                @Override public void jobEnded(eu.startales.spacepixels.jobs.JobHandle handle, JobOutcome<?> outcome) { }
            });
            coordinator.start(JobKind.DETECTION, first, context -> {
                context.progress(50, "old progress");
                return JobOutcome.completed(new File("old-report"));
            }, (handle, outcome) -> owner.applyIfCurrent(handle.session(), () -> notifications.add("old result")));
            workers.remove(0).run();
            owner.replace(first, second);
            while (!ui.isEmpty()) ui.remove(0).run();
            assertTrue(notifications.isEmpty());
            assertSame(second, owner.current());
            assertNull(coordinator.current());
            assertTrue(coordinator.start(JobKind.DETECTION, second, context -> JobOutcome.completed("new"),
                    (handle, outcome) -> owner.applyIfCurrent(handle.session(), () -> notifications.add("new result"))).admitted());
            workers.remove(0).run();
            while (!ui.isEmpty()) ui.remove(0).run();
            assertEquals(List.of("new result"), notifications);
        }
    }

    @Test
    public void aSessionThatCannotConfirmItsShutdownDoesNotStopTheHandoverOrTheWindowClosing() throws Exception {
        // As when a plate-solve client cannot confirm in time that its threads stopped.
        List<ProcessingSession> closed = new ArrayList<>();
        SessionOwner owner = new SessionOwner(session -> {
            closed.add(session);
            session.close();
            throw new IllegalStateException("Cannot confirm Astrometry.net client shutdown.");
        });
        ApplicationSettings settings = settings();
        ProcessingSession first = session("first", settings);
        ProcessingSession second = session("second", settings);
        assertTrue(owner.replace(null, first));
        assertTrue("the handover completes", owner.replace(first, second));
        assertSame(second, owner.current());
        owner.close();
        assertNull(owner.current());
        assertEquals(List.of(first, second), closed);
        assertTrue(second.isClosed());
    }

    @Test
    public void closingTheWindowOwnerRejectsEvenAnInitialImportThatFinishesLater() throws Exception {
        SessionOwner owner = new SessionOwner();
        owner.close();
        ProcessingSession imported = session("late", settings());
        assertFalse(owner.replace(null, imported));
        assertTrue(imported.isClosed());
        assertNull(owner.current());
    }

    @Test
    public void closingASessionStopsItsSkyWorkflowAndClearsItsRunState() throws Exception {
        ProcessingSession session = session("work", settings());
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                Thread.sleep(10000);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } finally {
                stopped.countDown();
            }
        });
        session.setSelection(session.getFiles());
        DetectionOverlays lastRun = new DetectionOverlays(TrackOverlay.from(PipelineResult.builder().build()), null);
        session.setLastRun(lastRun);
        assertSame(lastRun.tracks(), session.getTrackOverlay());
        AtomicInteger shutdowns = new AtomicInteger();
        session.ownSkyCatalogueWork(() -> {
            shutdowns.incrementAndGet();
            worker.interrupt();
        });
        worker.start();
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            session.close();
            assertTrue(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, shutdowns.get());
            assertTrue(session.getSelection().isEmpty());
            assertTrue(session.getAutoTune().isEmpty());
            assertThrows(IllegalStateException.class, () -> session.setLastReport(new File("late")));
            assertNull("closing forgets the last run's overlays", session.getTrackOverlay());
            assertThrows("a closed session takes no run's overlays", IllegalStateException.class,
                    () -> session.setLastRun(lastRun));
            assertNull(session.getTrackOverlay());
            session.close();
            assertEquals(1, shutdowns.get());
        } finally {
            worker.interrupt();
            worker.join(2000);
            session.close();
        }
    }

    /** Replacing the workflow releases its predecessor, and closing releases only the new owner once. */
    @Test public void catalogueWorkflowOwnershipFollowsTheSessionLifetime() throws Exception {
        ProcessingSession session = session("catalogue-owner", settings());
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        session.ownSkyCatalogueWork(first::incrementAndGet);
        session.ownSkyCatalogueWork(second::incrementAndGet);
        assertEquals(1, first.get());
        assertEquals(0, second.get());
        session.close();
        session.close();
        assertEquals(1, second.get());
        assertThrows(IllegalStateException.class, () -> session.ownSkyCatalogueWork(() -> { }));
    }

    /** A faulty desktop shutdown hook cannot prevent solver shutdown and release of the session's frames. */
    @Test public void catalogueShutdownFailureStillReleasesTheSession() throws Exception {
        ProcessingSession session = session("catalogue-shutdown-failure", settings());
        session.ownSkyCatalogueWork(() -> { throw new IllegalStateException("shutdown failed"); });
        assertThrows(IllegalStateException.class, session::close);
        assertTrue(session.isClosed());
        assertEquals(0, session.getFiles().length);
        session.close();
    }
}
