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

    /** A toggle is in use at once, before its save finishes; the save and a run admitted meanwhile keep their copies. */
    @Test(timeout = 15000)
    public void aToggleIsInUseBeforeItsSaveFinishesAndTheSaveAndARunKeepTheirCopies() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> saved = new CopyOnWriteArrayList<>();
        SessionStorage storage = new SessionStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                assertFalse(EventQueue.isDispatchThread());
                entered.countDown();
                await(release);
                saved.add(correction.enabled);
            }
        });
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        SessionPersistence persistence = new SessionPersistence(storage, callbacks::add);
        SkyCatalogueModel model = model(persistence);
        try {
            EventQueue.invokeAndWait(() -> toggle(model, true));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            exerciseEdt();
            SkySnapshot admitted = model.snapshot();
            assertTrue("a run admitted before the save finishes takes the toggle", admitted.correction().enabled);
            assertTrue(admitted.belongsTo(folder.getRoot()));
            model.getCorrection().enabled = false;
            assertTrue("a later change in memory does not reach the admitted run", admitted.correction().enabled);
            release.countDown();
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertEquals("the save writes the toggled copy", List.of(true), saved);
            assertNull(model.getSaveFailure());
        } finally {
            release.countDown();
            storage.finish();
            persistence.close();
        }
    }

    /** A failed save keeps the correction in use for the session and says why, until a later save succeeds. */
    @Test(timeout = 15000)
    public void aFailedSaveKeepsTheCorrectionInUseUntilALaterSaveSucceeds() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        SessionStorage storage = new SessionStorage(new Store() {
            @Override public void saveCorrection(File directory, PlateSolutionCorrection correction) throws IOException {
                if (attempts.incrementAndGet() == 1) throw new IOException("read only");
            }
        });
        SessionPersistence persistence = new SessionPersistence(storage, callbacks::add);
        SkyCatalogueModel model = model(persistence);
        try {
            EventQueue.invokeAndWait(() -> toggle(model, true));
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertTrue(model.isCorrectionEnabled());
            assertEquals(Boolean.TRUE, model.getCorrection().userEnabled);
            assertNotNull(model.getSaveFailure());
            assertTrue("a run uses the correction that could not be saved", model.snapshot().correction().enabled);
            EventQueue.invokeAndWait(() -> toggle(model, false));
            EventQueue.invokeAndWait(callbacks.poll(5, TimeUnit.SECONDS));
            assertFalse(model.isCorrectionEnabled());
            assertNull(model.getSaveFailure());
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
