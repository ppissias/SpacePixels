package eu.startales.spacepixels.platesolve;

import com.sun.net.httpserver.HttpServer;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsUploadCopyTest;
import io.github.ppissias.jplatesolve.PlateSolveOperation;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.astrometrydotnet.AstrometryDotNet;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A plate solver on this computer that offers the Astrometry.net web API: its address, its client and its WCS file. */
public class LocalSolverTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void anAddressIsMadeUsableOrRefused() {
        assertEquals(URI.create("http://localhost:5000/"), AstrometryNetAccount.localSolverAddress(" localhost:5000 "));
        assertEquals(URI.create("http://localhost:5000/"), AstrometryNetAccount.localSolverAddress("http://localhost:5000"));
        assertEquals("ANSVR's address as other programs take it",
                URI.create("http://127.0.0.1:8080/"), AstrometryNetAccount.localSolverAddress("http://127.0.0.1:8080/api/"));
        assertEquals(URI.create("https://solver.lan/astro/"), AstrometryNetAccount.localSolverAddress("HTTPS://solver.lan/astro"));
        assertNull(AstrometryNetAccount.localSolverAddress(""));
        assertNull(AstrometryNetAccount.localSolverAddress("ftp://localhost/"));
        assertNull(AstrometryNetAccount.localSolverAddress("http://localhost:5000/?x=1"));
        assertNull(AstrometryNetAccount.localSolverAddress("http://"));
        assertNull(AstrometryNetAccount.localSolverAddressProblem(" "));
        assertNull(AstrometryNetAccount.localSolverAddressProblem("localhost:5000"));
        assertEquals("Enter the solver's web address, such as http://localhost:5000/",
                AstrometryNetAccount.localSolverAddressProblem("not an address"));

        AppConfig config = new AppConfig();
        assertNull("no address, no local solver", AstrometryNetAccount.localSolver(config));
        config.localSolverAddress = "localhost:5000";
        config.localSolverApiKey = " demo ";
        assertEquals(new AstrometryNetAccount.LocalSolver(URI.create("http://localhost:5000/"), "demo"),
                AstrometryNetAccount.localSolver(config));
    }

    @Test
    public void aFailedLocalSolveNamesTheSolver() {
        var solver = new AstrometryNetAccount.LocalSolver(URI.create("http://localhost:5000/"), "demo");
        assertEquals("Could not reach the local solver at http://localhost:5000/. Check that it is running and that the "
                        + "address on the Astrometry Config tab is right.",
                AstrometryNetAccount.localFailureMessage(new IOException(new ConnectException()), solver));
        assertEquals("The local solver rejected the API key. Enter the key from its own settings on the Astrometry "
                        + "Config tab (Watney keeps its keys in apikeys.yml).",
                AstrometryNetAccount.localFailureMessage(new IOException("Astrometry.net login failed: bad apikey"), solver));
        assertEquals("The local solver could not solve the image: Submit request was not successful",
                AstrometryNetAccount.localFailureMessage(new IllegalStateException("Submit request was not successful"), solver));
    }

    @Test
    public void aLocalSolveUsesTheLocalClientAndAChangedAddressReplacesIt() throws Exception {
        AppConfig config = new AppConfig();
        List<AstrometryNetAccount.LocalSolver> made = new ArrayList<>();
        List<RecordingClient> clients = new ArrayList<>();
        PlateSolveService service = new PlateSolveService(config, key -> { throw new AssertionError("not the online client"); },
                solver -> {
                    made.add(solver);
                    RecordingClient client = new RecordingClient();
                    clients.add(client);
                    return client;
                });
        File frame = FitsUploadCopyTest.frameWithSite(folder.newFile("frame.fit"));
        try {
            service.startSolve(frame.getPath(), PlateSolveService.Solver.LOCAL_SOLVER, null);
            fail("no address, no local solve");
        } catch (IOException expected) {
            assertEquals("Set the local solver's address on the Astrometry Config tab first.", expected.getMessage());
        }
        config.localSolverAddress = "http://localhost:5000/";
        config.localSolverApiKey = "demo";
        solve(service, frame);
        solve(service, frame);
        assertEquals(1, made.size());
        assertEquals(new AstrometryNetAccount.LocalSolver(URI.create("http://localhost:5000/"), "demo"), made.get(0));
        assertFalse("the uploaded copy has no site", clients.get(0).uploadHadSite);

        config.localSolverAddress = "http://localhost:5001/";
        solve(service, frame);
        assertEquals(2, made.size());
        assertTrue("the client for the old address is closed", clients.get(0).closed);
        service.close();
        assertTrue(clients.get(1).closed);
    }

    @Test
    public void theLocalWcsFileIsKeptAtOnceAndNamedAsALocalFile() throws Exception {
        File wcs = folder.newFile("job.wcs");
        writeWcs(wcs);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wcs_file/7", exchange -> {
            byte[] body = Files.readAllBytes(wcs.toPath());
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try (PlateSolveService service = new PlateSolveService(new AppConfig())) {
            Map<String, String> information = new HashMap<>();
            information.put("source", "astrometry.net");
            information.put("wcs_link", root + "wcs_file/7");
            information.put("annotated_image_link", root + "annotated_display/7");
            information.put("status_page_link", root + "status/7");
            information.put("ra", "229.65");
            PlateSolveResult kept = service.keepLocalSolverSolution(new PlateSolveResult(true, null, null, information));
            Map<String, String> keptInformation = kept.getSolveInformation();
            File local = new File(URI.create(keptInformation.get("wcs_link")));
            assertTrue(local.isFile());
            assertEquals(PlateSolveService.LOCAL_SOLVER_SOURCE, keptInformation.get("source"));
            assertEquals("229.65", keptInformation.get("ra"));
            assertFalse(keptInformation.containsKey("annotated_image_link"));
            assertFalse(keptInformation.containsKey("status_page_link"));

            File image = folder.newFile("image.fit");
            writeImage(image);
            Map<String, String> header = service.updateFitsHeaderWithWCS(image.getPath(), keptInformation);
            assertEquals("RA---TAN", header.get("CTYPE1").trim());
            assertEquals(PlateSolveService.LOCAL_SOLVER_SOURCE, header.get("SOURCE"));
            assertFalse("a local address is not written into the header", header.containsKey("WCS_LINK"));
            service.cleanupSolveArtifacts(image.getPath(), kept);
            assertFalse("the kept file is deleted with the other solve files", local.exists());

            PlateSolveResult failed = new PlateSolveResult(false, "no solution", null, Map.of("wcs_link", root + "wcs_file/7"));
            assertSame("a failed solve is returned as it is", failed, service.keepLocalSolverSolution(failed));
            information.put("wcs_link", root + "wcs_file/8");
            try {
                service.keepLocalSolverSolution(new PlateSolveResult(true, null, null, information));
                fail("a missing WCS file fails the solve");
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith(
                        "The local solver solved the frame, but its WCS file could not be read: "));
            }
        } finally {
            server.stop(0);
        }
    }

    private static void solve(PlateSolveService service, File frame) throws Exception {
        PlateSolveOperation operation = service.startSolve(frame.getPath(), PlateSolveService.Solver.LOCAL_SOLVER, null);
        assertEquals(PlateSolveOperation.Status.SUCCEEDED,
                operation.completion().toCompletableFuture().get(5, TimeUnit.SECONDS).status());
    }

    /** A client that answers without the network. */
    private static final class RecordingClient extends AstrometryDotNet {
        boolean closed;
        boolean uploadHadSite = true;

        @Override
        public PlateSolveOperation startBlindSolve(File imageFile, Consumer<PlateSolveOperation.Progress> progress) {
            try {
                uploadHadSite = new String(Files.readAllBytes(imageFile.toPath()), java.nio.charset.StandardCharsets.ISO_8859_1).contains("SITELAT");
            } catch (IOException unreadable) {
                throw new java.io.UncheckedIOException(unreadable);
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

    private static void writeWcs(File target) throws Exception {
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(new short[2][2]);
            var header = hdu.getHeader();
            header.addValue("CTYPE1", "RA---TAN", null);
            header.addValue("CTYPE2", "DEC--TAN", null);
            header.addValue("CRVAL1", 229.6535334142, null);
            header.addValue("CRVAL2", 2.084849276888, null);
            header.addValue("CRPIX1", 772.9994656381, null);
            header.addValue("CRPIX2", 567.0016023454, null);
            header.addValue("CD1_1", 6.584544199734E-06, null);
            header.addValue("CD1_2", 0.0007067029074494, null);
            header.addValue("CD2_1", -0.0007063829992551, null);
            header.addValue("CD2_2", 6.425273314292E-06, null);
            fits.addHDU(hdu);
            fits.write(target);
        }
    }

    private static void writeImage(File target) throws Exception {
        try (Fits fits = new Fits()) {
            fits.addHDU(Fits.makeHDU(new short[16][16]));
            fits.write(target);
        }
    }
}
