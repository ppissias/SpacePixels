package eu.startales.spacepixels.platesolve;

import eu.startales.spacepixels.config.AppConfig;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import org.junit.Assume;
import org.junit.Test;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import eu.startales.spacepixels.fits.FitsInput;

/** Writing a plate solution into a frame's header and removing the solvers' side files. */
public class PlateSolveServiceWcsTest {

    @Test
    public void updateFitsHeaderWithWcsImportsDownloadedHeaderWithoutLegacySummaryPersistence() throws Exception {
        Path tempDirectory = Files.createTempDirectory("spacepixels-wcs-remote");
        Path fitsPath = tempDirectory.resolve("sample.fit");
        Path remoteWcsPath = tempDirectory.resolve("remote.wcs");

        Map<String, Object> originalHeader = new HashMap<>();
        originalHeader.put("RA", 1.0d);
        originalHeader.put("DEC", 2.0d);
        originalHeader.put("PIXSCALE", 3.0d);
        writeFitsFile(fitsPath, 100, 80, originalHeader);

        Map<String, Object> remoteWcsHeader = new LinkedHashMap<>();
        remoteWcsHeader.put("WCSAXES", 2);
        remoteWcsHeader.put("CTYPE1", "RA---TAN");
        remoteWcsHeader.put("CTYPE2", "DEC--TAN");
        remoteWcsHeader.put("CUNIT1", "deg");
        remoteWcsHeader.put("CUNIT2", "deg");
        remoteWcsHeader.put("CRPIX1", 42.25d);
        remoteWcsHeader.put("CRPIX2", 17.75d);
        remoteWcsHeader.put("CRVAL1", 140.89806d);
        remoteWcsHeader.put("CRVAL2", -7.0669513d);
        remoteWcsHeader.put("CD1_1", 0.000467862222d);
        remoteWcsHeader.put("CD1_2", 0.0d);
        remoteWcsHeader.put("CD2_1", 0.0d);
        remoteWcsHeader.put("CD2_2", -0.000467862222d);
        writeFitsFile(remoteWcsPath, 2, 2, remoteWcsHeader);

        PlateSolveService solver = new PlateSolveService(new AppConfig());
        Map<String, String> updatedHeader = solver.updateFitsHeaderWithWCS(
                fitsPath.toString(),
                createAstrometrySolveInfo(remoteWcsPath.toUri().toString().replace(":", "\\:")));

        assertEquals("42.25", updatedHeader.get("CRPIX1"));
        assertEquals("astrometry.net", updatedHeader.get("SOURCE"));
        assertFalse(updatedHeader.containsKey("RA"));
        assertFalse(updatedHeader.containsKey("DEC"));
        assertFalse(updatedHeader.containsKey("PIXSCALE"));
        assertFalse(updatedHeader.containsKey("PARITY"));
        assertFalse(updatedHeader.containsKey("RADIUS"));
        assertFalse(updatedHeader.containsKey("WCS_LINK"));

        try (Fits updatedFits = new Fits(fitsPath.toFile())) {
            Header header = FitsInput.getImageHDU(updatedFits).getHeader();

            assertEquals(2, header.getIntValue("WCSAXES"));
            assertEquals(42.25d, header.getDoubleValue("CRPIX1"), 1.0e-9);
            assertEquals(17.75d, header.getDoubleValue("CRPIX2"), 1.0e-9);
            assertEquals(140.89806d, header.getDoubleValue("CRVAL1"), 1.0e-9);
            assertEquals(-7.0669513d, header.getDoubleValue("CRVAL2"), 1.0e-9);
            assertEquals("astrometry.net", header.getStringValue("SOURCE"));
            assertFalse(header.containsKey("RA"));
            assertFalse(header.containsKey("DEC"));
            assertFalse(header.containsKey("PIXSCALE"));
            assertFalse(header.containsKey("PARITY"));
            assertFalse(header.containsKey("RADIUS"));
            assertFalse(header.containsKey("WCS_LINK"));
        }

        assertFalse(Files.exists(tempDirectory.resolve("sample.wcs")));
    }

    @Test
    public void updateFitsHeaderWithWcsAcceptsStandardWcsMetadataAlreadyPresentInSolveResult() throws Exception {
        Path tempDirectory = Files.createTempDirectory("spacepixels-wcs-direct");
        Path fitsPath = tempDirectory.resolve("sample.fit");

        writeFitsFile(fitsPath, 100, 80, new HashMap<>());

        PlateSolveService solver = new PlateSolveService(new AppConfig());
        solver.updateFitsHeaderWithWCS(fitsPath.toString(), createStandardWcsSolveInfo());

        try (Fits updatedFits = new Fits(fitsPath.toFile())) {
            BasicHDU<?> hdu = FitsInput.getImageHDU(updatedFits);
            Header header = hdu.getHeader();

            assertEquals(2, header.getIntValue("WCSAXES"));
            assertEquals(50.5d, header.getDoubleValue("CRPIX1"), 1.0e-9);
            assertEquals(40.5d, header.getDoubleValue("CRPIX2"), 1.0e-9);
            assertEquals("astap", header.getStringValue("SOURCE"));
            assertFalse(header.containsKey("WCS_LINK"));
            assertEquals(80, hdu.getAxes()[0]);
            assertEquals(100, hdu.getAxes()[1]);
        }
        // The updated copy took the original's place; no temporary file is left beside it.
        assertEquals(Collections.singletonList("sample.fit"), listFileNames(tempDirectory));
    }

    @Test
    public void updateFitsHeaderWithWcsKeepsTheOriginalWhenItCannotBeReplaced() throws Exception {
        // Windows does not let a file that another program holds open be replaced.
        Assume.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        Path tempDirectory = Files.createTempDirectory("spacepixels-wcs-locked");
        Path fitsPath = tempDirectory.resolve("sample.fit");
        Map<String, Object> originalHeader = new HashMap<>();
        originalHeader.put("OBJECT", "M81");
        writeFitsFile(fitsPath, 100, 80, originalHeader);

        PlateSolveService solver = new PlateSolveService(new AppConfig());
        try (FileInputStream heldOpen = new FileInputStream(fitsPath.toFile())) {
            solver.updateFitsHeaderWithWCS(fitsPath.toString(), createStandardWcsSolveInfo());
            fail("Expected the replacement of an open file to fail.");
        } catch (IOException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().startsWith("Cannot replace sample.fit"));
        }

        try (Fits unchangedFits = new Fits(fitsPath.toFile())) {
            Header header = FitsInput.getImageHDU(unchangedFits).getHeader();
            assertEquals("M81", header.getStringValue("OBJECT"));
            assertFalse(header.containsKey("CTYPE1"));
        }
        assertEquals(Collections.singletonList("sample.fit"), listFileNames(tempDirectory));
    }

    @Test
    public void aTruncatedCopyDoesNotReadBackAsTheUpdatedFrame() throws Exception {
        Path tempDirectory = Files.createTempDirectory("spacepixels-wcs-truncated");
        Path fitsPath = tempDirectory.resolve("sample.fit");
        writeFitsFile(fitsPath, 100, 80, new HashMap<>());
        int[] axes = {80, 100};

        PlateSolveService.checkReadsBack(fitsPath.toFile(), axes, "sample.fit");

        try (RandomAccessFile file = new RandomAccessFile(fitsPath.toFile(), "rw")) {
            file.setLength(file.length() / 2);
        }
        try {
            PlateSolveService.checkReadsBack(fitsPath.toFile(), axes, "sample.fit");
            fail("Expected a truncated copy to be refused.");
        } catch (IOException | FitsException expected) {
            // Refused: the copy would not replace the original.
        }
    }

    @Test
    public void updateFitsHeaderWithWcsRejectsCalibrationSummaryWithoutUsableWcsCards() throws Exception {
        Path tempDirectory = Files.createTempDirectory("spacepixels-wcs-invalid");
        Path fitsPath = tempDirectory.resolve("sample.fit");

        writeFitsFile(fitsPath, 100, 80, new HashMap<>());

        PlateSolveService solver = new PlateSolveService(new AppConfig());

        try {
            solver.updateFitsHeaderWithWCS(fitsPath.toString(), createAstrometrySolveInfo(null));
            fail("Expected updateFitsHeaderWithWCS to reject summary-only solve metadata.");
        } catch (IOException ex) {
            assertTrue(ex.getMessage().contains("usable WCS header"));
        }

        try (Fits unchangedFits = new Fits(fitsPath.toFile())) {
            Header header = FitsInput.getImageHDU(unchangedFits).getHeader();
            assertFalse(header.containsKey("CTYPE1"));
            assertFalse(header.containsKey("CRVAL1"));
        }
    }

    @Test
    public void cleanupSolveArtifactsRemovesLegacySolveFiles() throws Exception {
        Path tempDirectory = Files.createTempDirectory("spacepixels-cleanup");
        Path fitsPath = tempDirectory.resolve("sample.fit");
        Path legacyResultPath = tempDirectory.resolve("sample_result.ini");
        Path legacyWcsPath = tempDirectory.resolve("sample.wcs");
        Path legacyUpperWcsPath = tempDirectory.resolve("sample.WCS");
        Path legacyAstapIniPath = tempDirectory.resolve("sample.ini");
        Path temporaryWcsPath = tempDirectory.resolve("downloaded.wcs");
        Path temporaryPreviewPath = tempDirectory.resolve("preview.jpg");

        writeFitsFile(fitsPath, 100, 80, new HashMap<>());
        Files.writeString(legacyResultPath, "legacy");
        Files.writeString(legacyWcsPath, "legacy");
        Files.writeString(legacyUpperWcsPath, "legacy");
        Files.writeString(legacyAstapIniPath, "legacy");
        Files.writeString(temporaryWcsPath, "legacy");
        Files.writeString(temporaryPreviewPath, "legacy");

        Map<String, String> solveInfo = new HashMap<>();
        solveInfo.put("wcs_link", temporaryWcsPath.toString());
        solveInfo.put("annotated_image_link", temporaryPreviewPath.toString());

        PlateSolveService solver = new PlateSolveService(new AppConfig());
        solver.cleanupSolveArtifacts(
                fitsPath.toString(),
                new PlateSolveResult(true, "", "", solveInfo));

        assertFalse(Files.exists(legacyResultPath));
        assertFalse(Files.exists(legacyWcsPath));
        assertFalse(Files.exists(legacyUpperWcsPath));
        assertFalse(Files.exists(legacyAstapIniPath));
        assertFalse(Files.exists(temporaryWcsPath));
        assertFalse(Files.exists(temporaryPreviewPath));
    }

    private static Map<String, String> createStandardWcsSolveInfo() {
        Map<String, String> solveInfo = new LinkedHashMap<>();
        solveInfo.put("source", "astap");
        solveInfo.put("WCSAXES", "2");
        solveInfo.put("CTYPE1", "RA---TAN");
        solveInfo.put("CTYPE2", "DEC--TAN");
        solveInfo.put("CUNIT1", "deg");
        solveInfo.put("CUNIT2", "deg");
        solveInfo.put("CRPIX1", "50.5");
        solveInfo.put("CRPIX2", "40.5");
        solveInfo.put("CRVAL1", "140.89806");
        solveInfo.put("CRVAL2", "-7.0669513");
        solveInfo.put("CD1_1", "0.000467862222");
        solveInfo.put("CD1_2", "0.0");
        solveInfo.put("CD2_1", "0.0");
        solveInfo.put("CD2_2", "-0.000467862222");
        return solveInfo;
    }

    private static List<String> listFileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private static Map<String, String> createAstrometrySolveInfo(String wcsLink) {
        Map<String, String> solveInfo = new HashMap<>();
        solveInfo.put("source", "astrometry.net");
        solveInfo.put("ra", "140.89806");
        solveInfo.put("dec", "-7.0669513");
        solveInfo.put("orientation", "0.0");
        solveInfo.put("pixscale", "1.684304");
        solveInfo.put("parity", "1.0");
        solveInfo.put("radius", "0.38584602");
        if (wcsLink != null) {
            solveInfo.put("wcs_link", wcsLink);
        }
        return solveInfo;
    }

    private static void writeFitsFile(Path targetPath,
                                      int width,
                                      int height,
                                      Map<String, Object> headerValues) throws Exception {
        try (Fits fits = new Fits()) {
            BasicHDU<?> hdu = Fits.makeHDU(new short[height][width]);
            Header header = hdu.getHeader();

            for (Map.Entry<String, Object> entry : headerValues.entrySet()) {
                addHeaderValue(header, entry.getKey(), entry.getValue());
            }

            fits.addHDU(hdu);
            fits.write(targetPath.toFile());
        }
    }

    private static void addHeaderValue(Header header, String key, Object value) throws Exception {
        if (value instanceof Integer) {
            header.addValue(key, (Integer) value, null);
            return;
        }
        if (value instanceof Double) {
            header.addValue(key, (Double) value, null);
            return;
        }
        header.addValue(key, value.toString(), null);
    }
}
