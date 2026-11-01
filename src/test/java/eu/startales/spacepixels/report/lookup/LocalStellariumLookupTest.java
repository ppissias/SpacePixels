package eu.startales.spacepixels.report.lookup;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsImportService;
import eu.startales.spacepixels.fits.WcsSolutionResolver;
import eu.startales.spacepixels.report.DetectionReportAstrometry;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class LocalStellariumLookupTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void skyViewerHtmlIncludesLocalStellariumButtonsAndManualLink() throws Exception {
        File directory = SyntheticDatasetFactory.createSolvedMono16FitsSequence(
                folder.getRoot().toPath(), "frames", 3).toFile();
        FitsFileInformation[] files;
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            files = importer.readFiles();
        }
        WcsSolutionResolver.ResolvedWcsSolution wcsSolution = WcsSolutionResolver.resolveUncorrected(files[0], files);
        DetectionReportAstrometry.ObserverSite observerSite = new DetectionReportAstrometry.ObserverSite(
                37.9838, 23.7275, 120.0, "FITS header");
        long timestampMillis = 1768514400000L; // 2026-01-15T22:00:00Z
        DetectionReportAstrometry.Context astrometry = new DetectionReportAstrometry.Context(
                files, wcsSolution, observerSite, null, null, timestampMillis);

        SolarSystemQueryTarget queryTarget = new SolarSystemQueryTarget(
                32.0, 32.0, timestampMillis, 0.25, "Test target");

        String html = SkyViewerHtmlBuilder.buildSkyViewerLinksHtml(
                astrometry, queryTarget, "Reference epoch", 1.5);

        assertTrue(html.contains(">Stellarium Local</button>"));
        assertTrue(html.contains(">Stellarium Local Wide</button>"));
        assertTrue(html.contains("class='id-link stellarium-local-link'"));
        assertTrue(html.contains("class='astro-note stellarium-local-status'"));
        assertTrue(html.contains("data-time='1768514400000'"));
        assertTrue(html.contains("data-fov='1.5000'"));
        assertTrue(html.contains("data-lat='37.98380'"));
        assertTrue(html.contains("data-lng='23.72750'"));
        assertTrue(html.contains("data-elev='120.0'"));
        assertTrue(html.contains(SkyViewerHtmlBuilder.LOCAL_STELLARIUM_MANUAL_URL));
        assertTrue(html.contains("setup instructions in the SpacePixels manual"));
    }
}
