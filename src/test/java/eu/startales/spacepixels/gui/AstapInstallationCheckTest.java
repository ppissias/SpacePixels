package eu.startales.spacepixels.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.EventQueue;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class AstapInstallationCheckTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test(timeout = 15_000)
    public void slowChecksKeepTheEdtFreeAndOnlyTheNewestPendingPathIsInspectedAndApplied() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> inspected = new CopyOnWriteArrayList<>();
        CompletableFuture<String> displayed = new CompletableFuture<>();
        AstapInstallationCheck checker = new AstapInstallationCheck(request -> {
            assertFalse(EventQueue.isDispatchThread());
            inspected.add(request.path());
            if (request.path().equals("first")) {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                throw new IOException("obsolete failure");
            }
            return new AstapInstallationCheck.Result(true, request.path());
        }, (request, result) -> {
            assertTrue(EventQueue.isDispatchThread());
            displayed.complete(result.message());
        }, (request, failure) -> displayed.completeExceptionally(failure));
        try {
            EventQueue.invokeAndWait(() -> checker.check("first", false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(() -> {
                checker.check("second", true);
                checker.check("third", true);
                checker.check("latest", true);
            });
            FutureTask<Boolean> heartbeat = new FutureTask<>(EventQueue::isDispatchThread);
            EventQueue.invokeLater(heartbeat);
            assertTrue(heartbeat.get(3, TimeUnit.SECONDS));
            assertFalse(displayed.isDone());
            assertEquals(List.of("first"), inspected);
            release.countDown();
            assertEquals("latest", displayed.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("first", "latest"), inspected);
        } finally {
            release.countDown();
            EventQueue.invokeAndWait(checker::close);
        }
    }

    @Test(timeout = 15_000)
    public void closingDropsThePendingRequestAndIgnoresLateCompletion() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        CompletableFuture<Void> displayed = new CompletableFuture<>();
        AstapInstallationCheck checker = new AstapInstallationCheck(request -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
                return new AstapInstallationCheck.Result(true, "late result");
            } finally {
                finished.countDown();
            }
        }, (request, result) -> displayed.complete(null), (request, failure) -> displayed.completeExceptionally(failure));
        try {
            EventQueue.invokeAndWait(() -> checker.check("first", false));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(() -> {
                checker.check("pending", false);
                checker.close();
                checker.check("after close", false);
            });
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            EventQueue.invokeAndWait(() -> assertFalse(displayed.isDone()));
        } finally {
            EventQueue.invokeAndWait(checker::close);
        }
    }

    @Test(timeout = 15_000)
    public void theLatestFailureReturnsOnTheEdtAndDoesNotPreventAnotherCheck() throws Exception {
        CompletableFuture<Throwable> failed = new CompletableFuture<>();
        CompletableFuture<String> completed = new CompletableFuture<>();
        AstapInstallationCheck checker = new AstapInstallationCheck(request -> {
            if (request.path().equals("unreadable")) throw new IOException("permission denied");
            return new AstapInstallationCheck.Result(true, "rechecked");
        }, (request, result) -> completed.complete(result.message()), (request, failure) -> {
            assertTrue(EventQueue.isDispatchThread());
            failed.complete(failure);
        });
        try {
            EventQueue.invokeAndWait(() -> checker.check("unreadable", false));
            assertEquals("permission denied", failed.get(5, TimeUnit.SECONDS).getMessage());
            EventQueue.invokeAndWait(() -> checker.check("available", false));
            assertEquals("rechecked", completed.get(5, TimeUnit.SECONDS));
        } finally {
            EventQueue.invokeAndWait(checker::close);
        }
    }

    @Test(timeout = 15_000)
    public void realInspectionRecognizesInstalledDatabasesWithoutLaunchingTheSolver() throws Exception {
        File executable = folder.newFile("astap.exe");
        folder.newFile("d50_0101.1476");
        folder.newFile("w08_0101.001");
        CompletableFuture<AstapInstallationCheck.Result> checked = new CompletableFuture<>();
        AstapInstallationCheck checker = new AstapInstallationCheck((request, result) -> checked.complete(result),
                (request, failure) -> checked.completeExceptionally(failure));
        try {
            EventQueue.invokeAndWait(() -> checker.check(executable.getAbsolutePath(), false));
            var result = checked.get(5, TimeUnit.SECONDS);
            assertTrue(result.available());
            assertEquals("✓ ASTAP found · star database D50, W08", result.message());
        } finally {
            EventQueue.invokeAndWait(checker::close);
        }
    }
}
