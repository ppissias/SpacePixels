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

    /** Deterministic interaction records the production options while avoiding external processes or Swing. */
    private final class Answer implements FitsImportInteraction {
        private final boolean available;
        private final int choice;
        private boolean offered;
        private int alignedFrames;
        /** Captures capability and the chosen option. */
        Answer(boolean available, int choice) { this.available = available; this.choice = choice; }
        @Override public boolean canAlignFrames() { return available; }
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
