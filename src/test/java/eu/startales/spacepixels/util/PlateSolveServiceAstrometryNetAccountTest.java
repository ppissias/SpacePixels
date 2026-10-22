package eu.startales.spacepixels.util;

import eu.startales.spacepixels.config.AppConfig;
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import io.github.ppissias.jplatesolve.astrometrydotnet.SubmitFileRequest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Online solves and the Check log in with the account chosen on the Astrometry Config tab. No call reaches the network. */
public class PlateSolveServiceAstrometryNetAccountTest {

    /** JPlateSolve 1.0.1's message for a login the server refuses. */
    private static final String REFUSED_LOGIN = "Astrometry.net login failed.";

    /** A client that records its key, its solves and whether it was closed, and answers without the network. */
    private static final class RecordingClient extends AstrometryDotNet {
        final String requestedKey;
        IOException loginFailure;
        int logins;
        int solves;
        boolean closed;
        RuntimeException startFailure;
        File uploaded;
        boolean uploadExisted;
        boolean uploadHadSite;

        RecordingClient(String requestedKey) {
            super(requestedKey);
            this.requestedKey = requestedKey;
        }

        @Override
        public void login() throws IOException {
            logins++;
            if (loginFailure != null) {
                throw loginFailure;
            }
        }

        @Override
        public PlateSolveOperation startCustomSolve(File imageFile, SubmitFileRequest parameters,
                                                    Consumer<PlateSolveOperation.Progress> progress) {
            solves++;
            uploaded = imageFile;
            uploadExisted = imageFile.isFile();
            try {
                uploadHadSite = new String(Files.readAllBytes(imageFile.toPath()), StandardCharsets.ISO_8859_1).contains("SITELAT");
            } catch (IOException unreadable) {
                throw new UncheckedIOException(unreadable);
            }
            if (startFailure != null) {
                throw startFailure;
            }
            return PlateSolveOperation.start(Duration.ofSeconds(5), progress, Runnable::run,
                    context -> new PlateSolveResult(true, null, null, Map.of()));
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private final List<RecordingClient> clients = new ArrayList<>();

    private RecordingClient record(String key) {
        RecordingClient client = new RecordingClient(key);
        clients.add(client);
        return client;
    }

    @Test
    public void anAnonymousClientUsesTheLibraryGuestKeyAndAnOwnClientTheObserversKey() {
        AstrometryDotNet anonymous = AstrometryNetAccount.newClient("");
        AstrometryDotNet own = AstrometryNetAccount.newClient("abcdefghijklmnop");
        try {
            assertEquals(AstrometryDotNet.DEFAULT_API_KEY, anonymous.getApiKey());
            assertEquals("abcdefghijklmnop", own.getApiKey());
        } finally {
            anonymous.close();
            own.close();
        }
    }

    @Test
    public void aSolveUsesTheConfiguredKeyAndAChangedKeyReplacesTheClient() throws Exception {
        AppConfig config = new AppConfig();
        config.astrometryNetApiKey = "abcdefghijklmnop";
        PlateSolveService service = new PlateSolveService(config, this::record);
        assertTrue("no client before the first online solve", clients.isEmpty());

        solve(service);
        solve(service);
        assertEquals("Anonymous is chosen, so the stored key is not used", "", clients.get(0).requestedKey);
        assertEquals("the same account reuses its client", 2, clients.get(0).solves);

        config.astrometryNetUseOwnKey = true;
        solve(service);
        assertEquals(2, clients.size());
        assertEquals("abcdefghijklmnop", clients.get(1).requestedKey);
        assertTrue("the anonymous client is closed when it is replaced", clients.get(0).closed);
        assertFalse(clients.get(1).closed);

        service.close();
        assertTrue("closing the session closes the client in use", clients.get(1).closed);
        service.close();
        assertEquals("a second close makes nothing new", 2, clients.size());
    }

    @Test
    public void theClientIsKeptForOneKey() {
        PlateSolveService service = new PlateSolveService(new AppConfig(), this::record);
        AstrometryDotNet first = service.astrometryNetClient("");
        assertSame(first, service.astrometryNetClient(""));
        assertNotSame(first, service.astrometryNetClient("abcdefghijklmnop"));
        service.close();
    }

    @Test
    public void theCheckLogsInWithTheConfiguredAccountAndClosesItsClient() throws Exception {
        AppConfig config = new AppConfig();
        config.astrometryNetApiKey = "abcdefghijklmnop";

        AstrometryNetAccount.checkLogin(config, this::record);
        assertEquals("", clients.get(0).requestedKey);
        assertEquals(1, clients.get(0).logins);
        assertTrue(clients.get(0).closed);

        config.astrometryNetUseOwnKey = true;
        AstrometryNetAccount.checkLogin(config, this::record);
        assertEquals("abcdefghijklmnop", clients.get(1).requestedKey);
        assertEquals(1, clients.get(1).logins);
        assertTrue(clients.get(1).closed);
    }

    @Test
    public void aRefusedLoginFailsTheCheckWithAMessageForTheUser() throws Exception {
        AppConfig config = new AppConfig();
        config.astrometryNetUseOwnKey = true;
        config.astrometryNetApiKey = "notARealKey123";
        try {
            AstrometryNetAccount.checkLogin(config, key -> {
                RecordingClient client = record(key);
                client.loginFailure = new IOException(REFUSED_LOGIN);
                return client;
            });
            fail("a refused key must fail the check");
        } catch (IOException expected) {
            assertEquals("Astrometry.net rejected the API key. Check the key on the Astrometry Config tab, or choose Anonymous.",
                    expected.getMessage());
        }
        assertTrue("the client is closed after a failed check too", clients.get(0).closed);
    }

    @Test
    public void aFailedOnlineSolveIsDescribedForTheAccountInUse() {
        AppConfig config = new AppConfig();
        PlateSolveService service = new PlateSolveService(config, this::record);
        assertEquals("Astrometry.net did not accept the anonymous login. Enter your own API key on the Astrometry Config tab.",
                service.astrometryNetFailure(new IOException(REFUSED_LOGIN)));
        config.astrometryNetUseOwnKey = true;
        config.astrometryNetApiKey = "abcdefghijklmnop";
        assertEquals("Astrometry.net rejected the API key. Check the key on the Astrometry Config tab, or choose Anonymous.",
                service.astrometryNetFailure(new IOException(REFUSED_LOGIN)));
    }

    @Test
    public void anOnlineSolveUploadsACopyWithoutTheSiteAndDeletesItAfterwards() throws Exception {
        PlateSolveService service = new PlateSolveService(new AppConfig(), this::record);
        File frame = FitsUploadCopyTest.frameWithSite(folder.newFile("frame.fit"));
        byte[] before = Files.readAllBytes(frame.toPath());

        solve(service, frame);

        RecordingClient client = clients.get(0);
        assertNotEquals("the frame itself is not uploaded", frame, client.uploaded);
        assertTrue(client.uploadExisted);
        assertFalse("the uploaded copy has no site", client.uploadHadSite);
        assertFalse("the copy is deleted once the solve has ended", client.uploaded.exists());
        assertArrayEquals("the frame is unchanged", before, Files.readAllBytes(frame.toPath()));
        service.close();
    }

    @Test
    public void aSolveThatCannotStartDeletesItsCopy() throws Exception {
        PlateSolveService service = new PlateSolveService(new AppConfig(), key -> {
            RecordingClient client = record(key);
            client.startFailure = new IllegalStateException("Astrometry.net client is closed.");
            return client;
        });
        File frame = FitsUploadCopyTest.frameWithSite(folder.newFile("frame.fit"));
        try {
            service.startSolve(frame.getPath(), false, true, null);
            fail("the start failure must reach the caller");
        } catch (IllegalStateException expected) {
            assertFalse(clients.get(0).uploaded.exists());
        } finally {
            service.close();
        }
    }

    private void solve(PlateSolveService service) throws Exception {
        solve(service, FitsUploadCopyTest.frameWithSite(folder.newFile()));
    }

    private static void solve(PlateSolveService service, File frame) throws Exception {
        PlateSolveOperation operation = service.startSolve(frame.getPath(), false, true, null);
        assertEquals(PlateSolveOperation.Status.SUCCEEDED,
                operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
    }
}
