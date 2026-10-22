package eu.startales.spacepixels.session;

import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.jobs.JobContext;
import eu.startales.spacepixels.jobs.JobHandle;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.tasks.FitsImportTask;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.AutoTuneSessionStore;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
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

public class SessionStorageTest {
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
        SessionStorage storage = new SessionStorage(store);
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
                    result.metadata(), new SessionPersistence(storage, EventQueue::invokeLater))) {
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

    @Test(timeout = 15000)
    public void slowCorrectionSaveIsDetachedAndTheRunBarrierWaitsForUiAcknowledgement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        List<Boolean> saved = new CopyOnWriteArrayList<>();
        SessionStorage storage = new SessionStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                assertFalse(EventQueue.isDispatchThread());
                entered.countDown();
                await(release);
                saved.add(correction.enabled);
            }
        });
        SessionPersistence persistence = new SessionPersistence(storage, callbacks::add);
        SkyCatalogueModel model = model(persistence);
        ExecutorService run = Executors.newSingleThreadExecutor();
        try {
            EventQueue.invokeAndWait(() -> toggle(model, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            exerciseEdt();
            model.getCorrection().enabled = false;
            Future<?> waiting = run.submit(() -> { persistence.awaitWrites(); return null; });
            release.countDown();
            Runnable acknowledge = callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(acknowledge);
            assertFalse(waiting.isDone());
            EventQueue.invokeAndWait(acknowledge);
            waiting.get(5, TimeUnit.SECONDS);
            assertEquals(List.of(true), saved);
        } finally {
            release.countDown();
            run.shutdownNow();
            storage.finish();
            persistence.close();
        }
    }

    @Test(timeout = 15000)
    public void aFailedOldToggleDoesNotOverwriteANewerEditAndLatestFailureRollsBackToSavedState() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        SessionStorage storage = new SessionStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                if (attempts.incrementAndGet() != 2) throw new IOException("read only");
            }
        });
        SessionPersistence persistence = new SessionPersistence(storage, callbacks::add);
        SkyCatalogueModel model = model(persistence);
        try {
            EventQueue.invokeAndWait(() -> { toggle(model, true); toggle(model, false); });
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertFalse(model.isCorrectionEnabled());
            assertNull(model.getSaveFailure());
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(() -> toggle(model, true));
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertFalse(model.isCorrectionEnabled());
            assertEquals(Boolean.FALSE, model.getCorrection().userEnabled);
            assertNotNull(model.getSaveFailure());
        } finally {
            persistence.close();
            storage.finish();
        }
    }

    @Test(timeout = 15000)
    public void repeatedFailedEditsRollBackToTheLastSavedCorrectionNotAnUnsavedIntermediate() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        SessionStorage storage = new SessionStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                throw new IOException("read only");
            }
        });
        SessionPersistence persistence = new SessionPersistence(storage, callbacks::add);
        SkyCatalogueModel model = model(persistence);
        try {
            EventQueue.invokeAndWait(() -> { toggle(model, true); toggle(model, false); toggle(model, true); });
            for (int index = 0; index < 3; index++) EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertFalse(model.isCorrectionEnabled());
            assertNull(model.getCorrection().userEnabled);
            assertNotNull(model.getSaveFailure());
        } finally {
            persistence.close();
            storage.finish();
        }
    }

    @Test(timeout = 15000)
    public void aReimportReadsAfterQueuedWritesAndClosingDoesNotDiscardThemOrNotifyTheOldSession() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicInteger notices = new AtomicInteger();
        SessionStorage storage = new SessionStorage(new Store() {
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
        SessionPersistence persistence = new SessionPersistence(storage, EventQueue::invokeLater);
        try {
            persistence.save(() -> storage.store().saveCatalogue(folder.getRoot(), new SkyCatalogue()),
                    failure -> notices.incrementAndGet());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var imported = storage.submit(() -> storage.store().load(folder.getRoot(), new FitsFileInformation[0]));
            persistence.close();
            storage.close();
            exerciseEdt();
            assertFalse(imported.isDone());
            assertThrows(IllegalStateException.class, () -> persistence.save(() -> {}, failure -> {}));
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

    @Test(timeout = 15000)
    public void cancellingARunWaitingForASaveDoesNotCancelOrLoseTheSave() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        SessionStorage storage = new SessionStorage(new Store());
        SessionPersistence persistence = new SessionPersistence(storage, EventQueue::invokeLater);
        ExecutorService run = Executors.newSingleThreadExecutor();
        try {
            persistence.save(() -> { entered.countDown(); await(release); writes.incrementAndGet(); }, failure -> {});
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> waiting = run.submit(() -> { persistence.awaitWrites(cancelled::get); return null; });
            cancelled.set(true);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> waiting.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertEquals(0, writes.get());
            release.countDown();
            persistence.awaitWrites();
            assertEquals(1, writes.get());
        } finally {
            release.countDown();
            run.shutdownNow();
            storage.finish();
            persistence.close();
        }
    }

    private SkyCatalogueModel model(SessionPersistence persistence) {
        SkyCatalogueModel model = new SkyCatalogueModel();
        model.setSessionFolder(folder.getRoot());
        model.setPersistence(persistence);
        model.setCorrection(correction());
        return model;
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
