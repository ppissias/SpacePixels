package eu.startales.spacepixels.session;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobHandle;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.tasks.FitsImportTask;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.AutoTuneSessionStore;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SkySnapshot;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.swing.*;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Ordered session metadata I/O and callback lifetime on the shared application queue. */
public class ApplicationStorageSessionTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test(timeout = 15000)
    public void slowMetadataRestoreKeepsTheEdtFreeAndReturnsPreparedState() throws Exception {
        File input = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "frames", 3).toFile();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SkyCatalogue catalogue = new SkyCatalogue();
        SessionMetadata metadata = new SessionMetadata(catalogue, null, null);
        Store store = new Store() {
            @Override public SessionMetadata load(File directory, FitsFileInformation[] files) throws IOException {
                assertFalse(EventQueue.isDispatchThread());
                entered.countDown();
                await(release);
                return metadata;
            }
        };
        ApplicationStorage storage = new ApplicationStorage(store);
        ExecutorService importer = Executors.newSingleThreadExecutor();
        Future<JobOutcome<FitsImportTask.Result>> imported = null;
        try {
            imported = importer.submit(() -> new FitsImportTask(input,
                    ApplicationSettings.load(new File(folder.getRoot(), "preferences.json")), storage).run(context()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(imported.isDone());
            exerciseEdt();
            release.countDown();
            var outcome = imported.get(5, TimeUnit.SECONDS);
            assertTrue(outcome instanceof JobOutcome.Completed<?>);
            var result = ((JobOutcome.Completed<FitsImportTask.Result>) outcome).result();
            try (var session = new ProcessingSession(result.processing(), result.files(), result.lastReport(),
                    result.metadata(), storage, EventQueue::invokeLater)) {
                assertSame(catalogue, session.getSkyCatalogue().get());
                assertSame(metadata, session.getMetadata());
                assertEquals(3, session.getFiles().length);
            }
        } finally {
            release.countDown();
            importer.shutdown();
            importer.awaitTermination(5, TimeUnit.SECONDS);
            storage.finish();
        }
    }

    /** A toggle is in use at once, before its save finishes; the save and a run admitted meanwhile keep their copies. */
    @Test(timeout = 15000)
    public void aToggleIsInUseBeforeItsSaveFinishesAndTheSaveAndARunKeepTheirCopies() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> saved = new CopyOnWriteArrayList<>();
        ApplicationStorage storage = new ApplicationStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                assertFalse(EventQueue.isDispatchThread());
                entered.countDown();
                await(release);
                saved.add(correction.enabled);
            }
        });
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        ProcessingSession session = session(storage, callbacks::add);
        SkyCatalogueModel model = model(session);
        try {
            EventQueue.invokeAndWait(() -> toggle(model, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            exerciseEdt();
            SkySnapshot admitted = model.snapshot();
            assertTrue("a run admitted before the save finishes takes the toggle", admitted.correction().enabled);
            assertTrue(admitted.belongsTo(new File(session.getFiles()[0].getFilePath()).getParentFile()));
            model.getCorrection().enabled = false;
            assertTrue("a later change in memory does not reach the admitted run", admitted.correction().enabled);
            release.countDown();
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertEquals("the save writes the toggled copy", List.of(true), saved);
            assertNull(model.getSaveFailure());
        } finally {
            release.countDown();
            storage.finish();
            session.close();
        }
    }

    /** A failed save keeps the correction in use for the session and says why, until a later save succeeds. */
    @Test(timeout = 15000)
    public void aFailedSaveKeepsTheCorrectionInUseUntilALaterSaveSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        ApplicationStorage storage = new ApplicationStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                if (attempts.incrementAndGet() == 1) throw new IOException("read only");
            }
        });
        ProcessingSession session = session(storage, callbacks::add);
        SkyCatalogueModel model = model(session);
        try {
            EventQueue.invokeAndWait(() -> toggle(model, true));
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertTrue(model.isCorrectionEnabled());
            assertEquals(Boolean.TRUE, model.getCorrection().userEnabled);
            assertNotNull(model.getSaveFailure());
            Throwable recordedFailure = storage.getSessionWriteFailure();
            assertEquals("read only", recordedFailure.getMessage());
            assertTrue("a run uses the correction that could not be saved", model.snapshot().correction().enabled);
            EventQueue.invokeAndWait(() -> toggle(model, false));
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertFalse(model.isCorrectionEnabled());
            assertNull(model.getSaveFailure());
            assertSame("shutdown still reports a session write failure after a successful retry",
                    recordedFailure, storage.getSessionWriteFailure());
        } finally {
            session.close();
            storage.finish();
        }
    }

    @Test(timeout = 15000)
    public void aReimportReadsAfterQueuedWritesAndClosingDoesNotDiscardThemOrNotifyTheOldSession() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger notices = new AtomicInteger();
        ApplicationStorage storage = new ApplicationStorage(new Store() {
            @Override public void saveCatalogue(File directory, SkyCatalogue catalogue) throws IOException {
                entered.countDown();
                await(release);
                events.add("write");
            }
            @Override public SessionMetadata load(File directory, FitsFileInformation[] files) {
                events.add("read");
                return SessionMetadata.empty();
            }
        });
        ProcessingSession session = session(storage, EventQueue::invokeLater);
        try {
            session.saveCatalogue(new SkyCatalogue(),
                    failure -> notices.incrementAndGet());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var imported = storage.submit(() -> storage.store().load(folder.getRoot(), new FitsFileInformation[0]));
            session.close();
            storage.close();
            exerciseEdt();
            assertFalse(imported.isDone());
            assertThrows(IllegalStateException.class, () -> session.saveCatalogue(new SkyCatalogue(), failure -> {}));
            release.countDown();
            storage.finish();
            imported.get(5, TimeUnit.SECONDS);
            EventQueue.invokeAndWait(() -> {});
            assertEquals(List.of("write", "read"), events);
            assertEquals(0, notices.get());
        } finally {
            release.countDown();
            storage.finish();
        }
    }

    /** Settings writes, session saves and import reads use one FIFO worker, rather than two independent queues. */
    @Test(timeout = 15000)
    public void settingsAndSessionWorkShareSubmissionOrderAndWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        List<Thread> workers = new CopyOnWriteArrayList<>();
        ApplicationStorage storage = new ApplicationStorage(new Store() {
            @Override public void saveCatalogue(File directory, SkyCatalogue catalogue) {
                events.add("session");
                workers.add(Thread.currentThread());
            }
            @Override public SessionMetadata load(File directory, FitsFileInformation[] files) {
                events.add("read");
                workers.add(Thread.currentThread());
                return SessionMetadata.empty();
            }
        });
        try (ProcessingSession session = session(storage, EventQueue::invokeLater)) {
            storage.submit("Application defaults", () -> {
                entered.countDown();
                await(release);
                events.add("settings");
                workers.add(Thread.currentThread());
                return null;
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Throwable> completion = new CompletableFuture<>();
            session.saveCatalogue(new SkyCatalogue(), completion::complete);
            var read = storage.submit(() -> storage.store().load(folder.getRoot(), new FitsFileInformation[0]));
            assertFalse(read.isDone());
            exerciseEdt();
            release.countDown();
            read.get(5, TimeUnit.SECONDS);
            assertNull(completion.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("settings", "session", "read"), events);
            assertEquals(3, workers.size());
            assertSame(workers.get(0), workers.get(1));
            assertSame(workers.get(0), workers.get(2));
        } finally {
            release.countDown();
            storage.finish();
        }
    }

    /** Closing the owner also suppresses a completion that was already queued for UI delivery. */
    @Test(timeout = 15000)
    public void closingDropsAlreadyQueuedUiCompletionAndRefusesNewSessionWrites() throws Exception {
        ApplicationStorage storage = new ApplicationStorage(new Store());
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        AtomicInteger notices = new AtomicInteger();
        try (ProcessingSession session = session(storage, callbacks::add)) {
            session.saveCatalogue(new SkyCatalogue(), failure -> notices.incrementAndGet());
            Runnable callback = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback);
            session.close();
            EventQueue.invokeAndWait(callback);
            assertEquals(0, notices.get());
            assertThrows(IllegalStateException.class, () -> session.saveCatalogue(new SkyCatalogue(), failure -> {}));
            assertThrows(IllegalStateException.class, () -> session.saveAutoTune(new AutoTuneSessionStore.Document(), failure -> {}));
        } finally {
            storage.finish();
        }
    }

    /** A stopped UI executor cannot prevent accepted writes from finishing or poison the application I/O worker. */
    @Test(timeout = 15000)
    public void rejectedUiCompletionDoesNotStopTheWriter() throws Exception {
        ApplicationStorage storage = new ApplicationStorage(new Store());
        AtomicInteger deliveries = new AtomicInteger();
        try (ProcessingSession session = session(storage, action -> {
            deliveries.incrementAndGet();
            throw new RejectedExecutionException("window closed");
        })) {
            session.saveCatalogue(new SkyCatalogue(), failure -> fail("the stopped executor cannot deliver"));
            storage.submit(() -> null).get(5, TimeUnit.SECONDS);
            assertEquals(1, deliveries.get());
            assertNull(storage.getSessionWriteFailure());
        } finally {
            storage.finish();
        }
    }

    @Test
    public void snapshotsDoNotRetainMutableResultOrCorrectionCollections() {
        AutoTuneSessionStore.Document document = new AutoTuneSessionStore.Document();
        document.fileNames = new ArrayList<>(List.of("first.fit"));
        AutoTuneSessionStore.Document snapshot = AutoTuneSessionStore.snapshot(document);
        document.fileNames.clear();
        assertEquals(List.of("first.fit"), snapshot.fileNames);
        PlateSolutionCorrection original = correction();
        PlateSolutionCorrection copied = original.copy();
        original.global.ax[0] = 55;
        original.notes.add("later edit");
        assertEquals(0, copied.global.ax[0], 0);
        assertTrue(copied.notes.isEmpty());
    }

    /** A real session supplies the correction writer and owns its completion lifetime. */
    private SkyCatalogueModel model(ProcessingSession session) {
        SkyCatalogueModel model = session.getSkyCatalogue();
        model.setCorrection(correction());
        return model;
    }

    /** Opens small scratch frames without reading user-home preferences or queuing an import. */
    private ProcessingSession session(ApplicationStorage storage, Executor ui) throws Exception {
        File directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "metadata-owner", 3).toFile();
        ImageProcessing processing = ImageProcessing.openProcessor(directory,
                ApplicationSettings.load(new File(folder.getRoot(), "preferences.json")));
        return new ProcessingSession(processing, processing.readFiles(), null,
                SessionMetadata.empty(), storage, ui);
    }

    private static PlateSolutionCorrection correction() {
        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.global = new PlateSolutionCorrection.Polynomial();
        correction.global.order = 0;
        correction.global.scale = 1;
        correction.global.ax = new double[]{0};
        correction.global.ay = new double[]{0};
        return correction;
    }

    private static void toggle(SkyCatalogueModel model, boolean enabled) {
        try {
            model.setCorrectionEnabled(enabled);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void exerciseEdt() throws Exception {
        FutureTask<Void> heartbeat = new FutureTask<>(() -> {
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("First", new JPanel());
            tabs.addTab("Second", new JPanel());
            tabs.setSelectedIndex(1);
            JTextField editor = new JTextField();
            editor.setText("still responsive");
            tabs.setSize(200, 100);
            BufferedImage image = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { tabs.paint(graphics); } finally { graphics.dispose(); }
            assertEquals(1, tabs.getSelectedIndex());
            assertEquals("still responsive", editor.getText());
            return null;
        });
        EventQueue.invokeLater(heartbeat);
        heartbeat.get(2, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException(interrupted);
        }
    }

    private static JobContext context() {
        return new JobContext() {
            @Override public JobHandle handle() { return null; }
            @Override public boolean isCancelled() { return false; }
            @Override public void progress(int percent, String message) {}
        };
    }

    private static class Store implements SessionMetadataStore {
        @Override public SessionMetadata load(File directory, FitsFileInformation[] files) throws IOException { return SessionMetadata.empty(); }
        @Override public void saveCatalogue(File directory, SkyCatalogue catalogue) throws IOException {}
        @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {}
        @Override public void saveAutoTune(File directory, AutoTuneSessionStore.Document document) throws IOException {}
    }
}
