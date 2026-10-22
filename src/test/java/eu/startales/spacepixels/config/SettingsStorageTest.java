package eu.startales.spacepixels.config;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class SettingsStorageTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test(timeout = 15_000)
    public void delayedWritesAreOrderedAndUseTheCapturedApplicationDefaults() throws Exception {
        File file = new File(folder.getRoot(), "app.json");
        ApplicationSettings settings = ApplicationSettings.load(file);
        SettingsStorage storage = new SettingsStorage();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> writes = new ArrayList<>();
        AtomicReference<CompletableFuture<Void>> saved = new AtomicReference<>();
        try {
            storage.submit("gate", () -> {
                assertFalse(EventQueue.isDispatchThread());
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(() -> {
                settings.getConfig().siteLat = "10";
                AppConfig first = settings.getConfig().copy();
                storage.submit("Application defaults", () -> {
                    assertFalse(EventQueue.isDispatchThread());
                    settings.save(first);
                    writes.add(SpacePixelsAppConfigIO.load(file).siteLat);
                    return null;
                });
                settings.getConfig().siteLat = "20";
                AppConfig second = settings.getConfig().copy();
                saved.set(storage.submit("Application defaults", () -> {
                    settings.save(second);
                    writes.add(SpacePixelsAppConfigIO.load(file).siteLat);
                    return null;
                }));
                settings.getConfig().siteLat = "30";
            });
            FutureTask<Boolean> heartbeat = new FutureTask<>(EventQueue::isDispatchThread);
            EventQueue.invokeLater(heartbeat);
            assertTrue(heartbeat.get(3, TimeUnit.SECONDS));
            assertFalse(saved.get().isDone());
            assertFalse(file.exists());
            release.countDown();
            saved.get().get(5, TimeUnit.SECONDS);
            assertEquals(List.of("10", "20"), writes);
            assertEquals("20", SpacePixelsAppConfigIO.load(file).siteLat);
            assertEquals("30", settings.getConfig().siteLat);
            assertTrue(storage.getFailures().isEmpty());
        } finally {
            release.countDown();
            storage.finish();
        }
    }

    @Test(timeout = 15_000)
    public void failuresAreReportedAndOnlyASuccessfulRetryOfThatTargetClearsThem() throws Exception {
        SettingsStorage storage = new SettingsStorage();
        try {
            IOException unavailable = new IOException("disk unavailable");
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> storage.submit("Application defaults", () -> { throw unavailable; }).get(5, TimeUnit.SECONDS));
            assertSame(unavailable, failure.getCause());
            assertSame(unavailable, storage.getFailures().get("Application defaults"));
            storage.submit("Detection settings", () -> null).get(5, TimeUnit.SECONDS);
            assertSame(unavailable, storage.getFailures().get("Application defaults"));
            storage.submit("Application defaults", () -> null).get(5, TimeUnit.SECONDS);
            assertTrue(storage.getFailures().isEmpty());
        } finally {
            storage.finish();
        }
    }

    @Test(timeout = 15_000)
    public void shutdownDrainsAcceptedWritesWithoutBlockingTheEdtAndRejectsNewWrites() throws Exception {
        SettingsStorage storage = new SettingsStorage();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> saved = storage.submit("Application defaults", () -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "saved";
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(storage::close);
            assertFalse(saved.isDone());
            release.countDown();
            storage.finish();
            assertEquals("saved", saved.get(5, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () ->
                    storage.submit("Late write", () -> null).get(5, TimeUnit.SECONDS));
            assertTrue(storage.getFailures().containsKey("Late write"));
        } finally {
            release.countDown();
            storage.finish();
        }
    }
}
