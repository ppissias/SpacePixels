package eu.startales.spacepixels.fits;

import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import static org.junit.Assert.*;

/** Import offers external alignment only for supported misaligned input and redirects after an accepted preparation. */
public class ImportAlignmentOfferTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** A confirmed alignment receives exactly the admitted metadata and redirects before session installation. */
    @Test public void acceptedAlignmentRedirectsImport() throws Exception {
        var source = SyntheticDatasetFactory.createMisalignedMono16FitsSequence(temporary.getRoot().toPath(), "shift", 3, 4, 0);
        Answer answer = new Answer(true, 2);
        try (var importer = new FitsImportService(source.toFile(), answer)) {
            var redirect = assertThrows(RedirectImportException.class, importer::readFiles);
            assertEquals(temporary.getRoot(), redirect.getNewDirectory());
            assertTrue(answer.offered);
            assertEquals(3, answer.alignedFrames);
        }
    }

    /** Missing Siril retains the existing cancel/proceed choices; cancelling never invokes alignment. */
    @Test public void unavailableAlignmentKeepsExistingChoices() throws Exception {
        var source = SyntheticDatasetFactory.createMisalignedMono16FitsSequence(temporary.getRoot().toPath(), "shift", 3, 4, 0);
        Answer answer = new Answer(false, 0);
        try (var importer = new FitsImportService(source.toFile(), answer)) {
            assertEquals(0, importer.readFiles().length);
            assertFalse(answer.offered);
            assertEquals(0, answer.alignedFrames);
        }
    }

    /** Clean samples cannot invoke the optional alignment action. */
    @Test public void cleanInputDoesNotOfferAlignment() throws Exception {
        var source = SyntheticDatasetFactory.createMono16FitsSequence(temporary.getRoot().toPath(), "clean", 3);
        Answer answer = new Answer(true, 2);
        try (var importer = new FitsImportService(source.toFile(), answer)) {
            assertEquals(3, importer.readFiles().length);
            assertFalse(answer.offered);
            assertEquals(0, answer.alignedFrames);
        }
    }

    /** Raw colour exposures are named before anything else; only an explicit choice imports them as monochrome. */
    @Test public void rawColourExposuresAreNamedBeforeImport() throws Exception {
        var source = SyntheticDatasetFactory.createRawColourFitsSequence(temporary.getRoot().toPath(), "raw", 3, java.util.Map.of());
        for (var choice : new FitsImportInteraction.RawColourChoice[]{FitsImportInteraction.RawColourChoice.PREPARE,
                FitsImportInteraction.RawColourChoice.CANCEL}) {
            Answer answer = new Answer(true, 1);
            answer.rawColourChoice = choice;
            try (var importer = new FitsImportService(source.toFile(), answer)) {
                assertEquals(0, importer.readFiles().length);
                assertEquals("GRBG", answer.rawColourAsked);
            }
        }
        Answer anyway = new Answer(true, 1);
        anyway.rawColourChoice = FitsImportInteraction.RawColourChoice.IMPORT_AS_MONOCHROME;
        try (var importer = new FitsImportService(source.toFile(), anyway)) {
            assertEquals(3, importer.readFiles().length);
        }
        var mono = SyntheticDatasetFactory.createMono16FitsSequence(temporary.getRoot().toPath(), "mono", 3);
        Answer monochrome = new Answer(true, 1);
        try (var importer = new FitsImportService(mono.toFile(), monochrome)) {
            assertEquals(3, importer.readFiles().length);
            assertNull("Monochrome frames are not asked about", monochrome.rawColourAsked);
        }
    }

    /** The strict import leaves raw colour to the API's refusal and the window's question; the default adapter cancels. */
    @Test public void adaptersWithoutPreparationCancelRawColour() throws Exception {
        var source = SyntheticDatasetFactory.createRawColourFitsSequence(temporary.getRoot().toPath(), "raw-default", 3, java.util.Map.of());
        FitsImportInteraction plain = new FitsImportInteraction() {
            @Override public int confirm(String message, String title) { return 1; }
            @Override public int choose(String message, String title, String[] options) { return 1; }
            @Override public void error(String message, String title) { fail(message); }
            @Override public Progress progress(String title) { throw new AssertionError(title); }
        };
        try (var importer = new FitsImportService(source.toFile(), plain)) {
            assertEquals(0, importer.readFiles().length);
        }
    }

    /** Deterministic interaction records the production options while avoiding external processes or Swing. */
    private final class Answer implements FitsImportInteraction {
        private final boolean available;
        private final int choice;
        private boolean offered;
        private int alignedFrames;
        private String rawColourAsked;
        private RawColourChoice rawColourChoice = RawColourChoice.CANCEL;
        /** Captures capability and the chosen option. */
        Answer(boolean available, int choice) { this.available = available; this.choice = choice; }
        @Override public boolean canAlignFrames(FitsFileInformation[] frames) { return available; }
        @Override public RawColourChoice rawColour(BayerMosaic mosaic, File folder) {
            rawColourAsked = mosaic.pattern();
            return rawColourChoice;
        }
        @Override public File alignFrames(FitsFileInformation[] frames) { alignedFrames = frames.length; return temporary.getRoot(); }
        @Override public int choose(String message, String title, String[] options) {
            offered = java.util.Arrays.asList(options).contains("Align with Siril");
            return choice;
        }
        @Override public int confirm(String message, String title) { return 1; }
        @Override public void error(String message, String title) { fail(message); }
        @Override public Progress progress(String title) { throw new AssertionError(title); }
    }
}
