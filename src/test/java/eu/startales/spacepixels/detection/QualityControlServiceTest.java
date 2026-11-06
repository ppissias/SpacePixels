package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.execution.OperationFailedException;
import io.github.ppissias.jtransient.execution.CancellationScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** File-backed QC assesses every input and rejects sources that change during its two passes. */
public class QualityControlServiceTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** No caller-array/settings mutation from a callback can change an admitted quality run. */
    @Test public void allFramesAndSettingsAreCaptured() throws Exception {
        var files = files();
        DetectionConfig config = new DetectionConfig();
        String expectedKey = QualityControlSnapshot.settingsKey(config);
        var result = new QualityControlService().evaluate(files, config, (percent, message) -> {
            files[0] = null;
            config.qualitySigmaMultiplier = 100;
        });
        assertEquals(5, result.result().frames().size());
        assertEquals(expectedKey, result.settingsKey());
    }

    /** A successful coverage pass does not authorize using changed image data in the measurement pass. */
    @Test public void editedFrameFailsWithItsOriginalCause() throws Exception {
        var files = files();
        Path path = Path.of(files[0].getFilePath());
        var failure = assertThrows(OperationFailedException.class, () -> new QualityControlService().evaluate(files,
                new DetectionConfig(), (percent, message) -> {
                    if (percent == 30) {
                        try { Files.setLastModifiedTime(path, FileTime.fromMillis(1234567)); }
                        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                    }
                }));
        assertTrue(failure.getCause().getMessage().contains("Frame changed during quality control"));
    }

    /** Cancellation between coverage and measurement yields no partial snapshot and closes all FITS readers. */
    @Test public void cancellationAfterFirstPassDoesNotReturnPartialQuality() throws Exception {
        var files = files();
        AtomicBoolean cancelled = new AtomicBoolean();
        try (var scope = CancellationScope.open(cancelled::get)) {
            assertThrows(CancellationException.class, () -> new QualityControlService().evaluate(files,
                    new DetectionConfig(), (percent, message) -> { if (percent >= 30) cancelled.set(true); }));
        }
        assertEquals(5, new QualityControlService().evaluate(files, new DetectionConfig(), null).result().frames().size());
    }

    /** Creates scratch input and immutable descriptors with the same timing conventions as imported frames. */
    private FitsFileInformation[] files() throws Exception {
        Path directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder().toPath(), "frames", 5);
        try (var paths = Files.list(directory)) {
            return paths.sorted().map(path -> new FitsFileInformation(path.toString(), path.getFileName().toString(),
                    true, 96, 96, Map.of("EXPTIME", "30"))).toArray(FitsFileInformation[]::new);
        }
    }
}
