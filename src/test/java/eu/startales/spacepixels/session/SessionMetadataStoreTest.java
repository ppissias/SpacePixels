package eu.startales.spacepixels.session;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Arrays;
import static org.junit.Assert.*;
import eu.startales.spacepixels.detection.AutoTuneSessionStore;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsImportService;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.fits.WcsSolutionResolver;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.skycatalog.SkyField;

public class SessionMetadataStoreTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void preparationRestoresOnlyMetadataThatBelongsToTheImportedFrames() throws Exception {
        File directory = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "frames", 3).toFile();
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            FitsFileInformation[] frames = importer.readFiles();
            var solution = WcsSolutionResolver.resolveUncorrected(null, frames);
            SkyCatalogue catalogue = new SkyCatalogue();
            catalogue.field = SkyField.of(solution.getTransformer(), frames[0].getSizeWidth(), frames[0].getSizeHeight());
            SkyCatalogueStore.save(directory, catalogue);
            PlateSolutionCorrection correction = new PlateSolutionCorrection();
            correction.solutionKey = solution.getTransformer().solutionKey();
            PlateSolutionCorrection.save(directory, correction);
            AutoTuneSessionStore.Document document = new AutoTuneSessionStore.Document();
            document.frameCount = frames.length;
            document.width = frames[0].getSizeWidth();
            document.height = frames[0].getSizeHeight();
            document.fileNames = Arrays.stream(frames).map(FitsFileInformation::getFileName).toList();
            AutoTuneSessionStore.save(directory, document);

            SessionMetadataStore store = SessionMetadataStore.filesystem();
            SessionMetadata matching = store.load(directory, frames);
            assertNotNull(matching.catalogue());
            assertNotNull(matching.correction());
            assertNotNull(matching.autoTune());

            catalogue.field = SkyField.of(WcsCoordinateTransformer.fromHeader(otherHeader(frames[0])), document.width, document.height);
            SkyCatalogueStore.save(directory, catalogue);
            correction.solutionKey = "another solution";
            PlateSolutionCorrection.save(directory, correction);
            document.width++;
            AutoTuneSessionStore.save(directory, document);
            SessionMetadata unrelated = store.load(directory, frames);
            assertNull(unrelated.catalogue());
            assertNull(unrelated.correction());
            assertNull(unrelated.autoTune());
        }
    }

    @Test
    public void suppliedWcsCorrectionDoesNotConsultOrOverrideTheSavedFile() throws Exception {
        File directory = SyntheticDatasetFactory.createSolvedMono16FitsSequence(folder.getRoot().toPath(), "wcs", 3).toFile();
        try (FitsImportService importer = new FitsImportService(directory, null)) {
            FitsFileInformation[] frames = importer.readFiles();
            var header = WcsSolutionResolver.resolveUncorrected(null, frames).getTransformer();
            PlateSolutionCorrection correction = new PlateSolutionCorrection();
            correction.solutionKey = header.solutionKey();
            correction.width = frames[0].getSizeWidth();
            correction.height = frames[0].getSizeHeight();
            correction.enabled = true;
            correction.global = new PlateSolutionCorrection.Polynomial();
            correction.global.scale = 1;
            correction.global.ax = new double[]{1};
            correction.global.ay = new double[]{2};
            assertTrue(WcsSolutionResolver.resolve(frames[0], frames, correction).getTransformer().isCorrected());
            assertFalse(new File(directory, PlateSolutionCorrection.FILE_NAME).exists());
            assertFalse(WcsSolutionResolver.resolve(frames[0], frames, null).getTransformer().isCorrected());
            correction.enabled = false;
            assertFalse(WcsSolutionResolver.resolve(frames[0], frames, correction).getTransformer().isCorrected());
        }
    }

    private static java.util.Map<String, String> otherHeader(FitsFileInformation frame) {
        var header = new java.util.HashMap<>(frame.getFitsHeader());
        header.put("CRVAL1", "150");
        return header;
    }
}
