package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.FitsImportService;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SkySnapshot;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

/** Verifies that site, FITS metadata, catalogue and plate corrections belong to the admitted run. */
public class ReportRunContextTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Late file/DTO changes affect a later run while the first run keeps its original resolved context. */
    @Test public void resolvedContextKeepsMetadataCatalogueAndCorrectionUntilTheRunEnds() throws Exception {
        File directory = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "frames", 5).toFile();
        FitsFileInformation[] files;
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            files = importer.readFiles();
        }
        for (FitsFileInformation file : files) {
            for (String key : new String[]{"SITELAT", "SITELONG", "SITELON", "OBSGEO-B", "OBSGEO-L", "LAT-OBS", "LONG-OBS", "LON-OBS"}) {
                file.getFitsHeader().remove(key);
            }
        }
        var transformer = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        assertNotNull(transformer);
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = SkyField.of(transformer, files[0].getSizeWidth(), files[0].getSizeHeight());
        catalogue.starSource = "first catalogue";
        SkyCatalogueStore.save(directory, catalogue);
        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.enabled = true;
        correction.solutionKey = transformer.solutionKey();
        correction.width = files[0].getSizeWidth();
        correction.height = files[0].getSizeHeight();
        correction.global = new PlateSolutionCorrection.Polynomial();
        correction.global.order = 0;
        correction.global.scale = 1;
        correction.global.ax = new double[]{1};
        correction.global.ay = new double[]{0};
        PlateSolutionCorrection.save(directory, correction);
        AppConfig application = new AppConfig();
        application.siteLat = "37.5";
        application.siteLong = "23.5";
        DetectionRunOptions options = DetectionRunOptions.capture(application, 17);
        ReportRunContext first = ReportRunContext.capture(options, files);
        long timestamp = first.astrometry().getFrameTimestampMillis(0);
        double rightAscension = first.astrometry().getTransformer().pixelToSky(30, 30).getRaDegrees();
        assertEquals("first catalogue", first.catalogue().catalogue.starSource);
        application.siteLat = "-24";
        options.applicationConfig().siteLat = "wrong";
        files[0].getFitsHeader().put("DATE-OBS", "2030-01-01T00:00:00");
        first.filesInformation()[0].getFitsHeader().put("DATE-OBS", "2040-01-01T00:00:00");
        catalogue.starSource = "second catalogue";
        SkyCatalogueStore.save(directory, catalogue);
        correction.global.ax[0] = 5;
        PlateSolutionCorrection.save(directory, correction);
        ReportRunContext second = ReportRunContext.capture(DetectionRunOptions.capture(application, 29), files);
        assertEquals(37.5, first.astrometry().getObserverSite().latitudeDeg, 0);
        assertEquals(-24, second.astrometry().getObserverSite().latitudeDeg, 0);
        assertEquals(timestamp, first.astrometry().getFrameTimestampMillis(0));
        assertNotEquals(timestamp, second.astrometry().getFrameTimestampMillis(0));
        assertEquals(rightAscension, first.astrometry().getTransformer().pixelToSky(30, 30).getRaDegrees(), 0);
        assertNotEquals(rightAscension, second.astrometry().getTransformer().pixelToSky(30, 30).getRaDegrees(), 1e-10);
        assertEquals("first catalogue", first.catalogue().catalogue.starSource);
        assertEquals("second catalogue", second.catalogue().catalogue.starSource);
        assertEquals(17, first.options().autoTuneMaxCandidateFrames());
        assertEquals(29, second.options().autoTuneMaxCandidateFrames());
    }

    /** An absent catalogue is also captured, so fetching one later cannot silently enrich an admitted run. */
    @Test public void absentCatalogueDoesNotBecomeAvailableDuringTheRun() throws Exception {
        File directory = folder.newFolder("no-catalogue");
        FitsFileInformation file = new FitsFileInformation(new File(directory, "frame.fit").toString(), "frame.fit", true, 20, 20);
        ReportRunContext context = ReportRunContext.capture(DetectionRunOptions.defaults(), new FitsFileInformation[]{file});
        SkyCatalogueStore.save(directory, new SkyCatalogue());
        assertFalse(context.catalogue().isAvailable());
    }

    /** A run on the session's frames takes the session's correction and catalogue, saved or not, not the files. */
    @Test public void aRunOnTheSessionsFramesUsesItsSkyDataRatherThanTheFiles() throws Exception {
        File directory = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "session", 5).toFile();
        FitsFileInformation[] files = solvedFiles(directory);
        var transformer = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        SkyCatalogueStore.save(directory, catalogue(transformer, files[0], "saved catalogue"));
        PlateSolutionCorrection.save(directory, correction(transformer, files[0], false));
        SkySnapshot sky = new SkySnapshot(directory, correction(transformer, files[0], true),
                catalogue(transformer, files[0], "unsaved catalogue"));

        ReportRunContext fromSession = ReportRunContext.capture(DetectionRunOptions.defaults(), files, sky);
        ReportRunContext fromFiles = ReportRunContext.capture(DetectionRunOptions.defaults(), files);

        assertEquals("unsaved catalogue", fromSession.catalogue().catalogue.starSource);
        assertTrue(fromSession.astrometry().getTransformer().isCorrected());
        assertEquals("saved catalogue", fromFiles.catalogue().catalogue.starSource);
        assertFalse(fromFiles.astrometry().getTransformer().isCorrected());
    }

    /** The session's sky data is not used for frames in another folder, such as an iterative pass on a prepared copy. */
    @Test public void skyDataOfAnotherFolderIsIgnored() throws Exception {
        File directory = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "copy", 5).toFile();
        FitsFileInformation[] files = solvedFiles(directory);
        var transformer = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        SkyCatalogueStore.save(directory, catalogue(transformer, files[0], "the copy's catalogue"));
        SkySnapshot elsewhere = new SkySnapshot(folder.newFolder("session"), correction(transformer, files[0], true),
                catalogue(transformer, files[0], "the session's catalogue"));

        ReportRunContext context = ReportRunContext.capture(DetectionRunOptions.defaults(), files, elsewhere);

        assertEquals("the copy's catalogue", context.catalogue().catalogue.starSource);
        assertFalse(context.astrometry().getTransformer().isCorrected());
    }

    private static FitsFileInformation[] solvedFiles(File directory) throws Exception {
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            return importer.readFiles();
        }
    }

    private static SkyCatalogue catalogue(WcsCoordinateTransformer transformer, FitsFileInformation file, String source) {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = SkyField.of(transformer, file.getSizeWidth(), file.getSizeHeight());
        catalogue.starSource = source;
        return catalogue;
    }

    private static PlateSolutionCorrection correction(WcsCoordinateTransformer transformer, FitsFileInformation file, boolean enabled) {
        PlateSolutionCorrection correction = new PlateSolutionCorrection();
        correction.enabled = enabled;
        correction.solutionKey = transformer.solutionKey();
        correction.width = file.getSizeWidth();
        correction.height = file.getSizeHeight();
        correction.global = new PlateSolutionCorrection.Polynomial();
        correction.global.order = 0;
        correction.global.scale = 1;
        correction.global.ax = new double[]{1};
        correction.global.ay = new double[]{0};
        return correction;
    }
}
