package eu.startales.spacepixels.session;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.SpacePixelsAppConfigIO;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.util.AutoTunerRunner;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
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
        return new ProcessingSession(ImageProcessing.getInstance(directory, settings), new FitsFileInformation[]{frame}, null);
    }

    private ApplicationSettings settings() {
        return ApplicationSettings.load(new File(folder.getRoot(), "preferences.json"));
    }

    @Test
    public void applicationPreferencesSurviveImportsWithoutReloadingOrLosingUnsavedEdits() throws Exception {
        File file = new File(folder.getRoot(), SpacePixelsAppConfigIO.DEFAULT_FILENAME);
        Files.writeString(file.toPath(), "{\"siteLat\":\"12\"}");
        ApplicationSettings settings = ApplicationSettings.load(file);
        settings.getConfig().siteLat = "34";
        try (ProcessingSession first = session("first", settings)) {
            Files.writeString(file.toPath(), "{\"siteLat\":\"56\"}");
            try (ProcessingSession second = session("second", settings)) {
                assertSame(first.getProcessing().getAppConfig(), second.getProcessing().getAppConfig());
                assertEquals("34", second.getProcessing().getAppConfig().siteLat);
                second.getProcessing().saveAppConfig();
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
            first.getAutoTune().setMaxCandidateFrames(12);
            second.getAutoTune().setMaxCandidateFrames(24);
            assertEquals(12, first.getAutoTune().getMaxCandidateFrames());
            assertEquals(24, second.getAutoTune().getMaxCandidateFrames());
            JTransientAutoTuner.AutoTunerResult result = new JTransientAutoTuner.AutoTunerResult();
            first.getAutoTune().remember(AutoTunerRunner.Algorithm.CALIBRATED,
                    new AutoTuneState.Run(result, Instant.now(), 7, null));
            assertSame(result, first.getAutoTune().result(AutoTunerRunner.Algorithm.CALIBRATED));
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
    public void closingTheWindowOwnerRejectsEvenAnInitialImportThatFinishesLater() throws Exception {
        SessionOwner owner = new SessionOwner();
        owner.close();
        ProcessingSession imported = session("late", settings());
        assertFalse(owner.replace(null, imported));
        assertTrue(imported.isClosed());
        assertNull(owner.current());
    }

    @Test
    public void closingASessionInterruptsItsSkyWorkerAndClearsItsRunState() throws Exception {
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
        session.ownSkyCatalogueWorker(worker);
        worker.start();
        try {
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(session.ownsSkyCatalogueWorker(worker));
            session.close();
            assertTrue(stopped.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(session.ownsSkyCatalogueWorker(worker));
            assertTrue(session.getSelection().isEmpty());
            assertTrue(session.getAutoTune().isEmpty());
            assertThrows(IllegalStateException.class, () -> session.setLastReport(new File("late")));
            session.close();
        } finally {
            worker.interrupt();
            worker.join(2000);
            session.close();
        }
    }
}
