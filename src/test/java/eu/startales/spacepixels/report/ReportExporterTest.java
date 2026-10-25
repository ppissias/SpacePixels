package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import static org.junit.Assert.*;
import eu.startales.spacepixels.util.Cancellation;

/** Protects request ownership, valid missing sky data, cancellation cleanup and atomic HTML publication. */
public class ReportExporterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    /** Small request snapshots cannot be changed through caller metadata, configuration or list containers. */
    @Test public void requestOwnsMetadataAndConfigButSharesImageBuffers() throws Exception {
        File directory = temporary.newFolder();
        FitsFileInformation[] files = files(directory);
        files[0] = files[0].withFitsHeader(java.util.Map.of("EXPTIME", "60"));
        var config = new DetectionConfig();
        double sigma = config.detectionSigmaMultiplier;
        short[][] image = new short[24][24];
        var frames = new ArrayList<short[][]>();
        frames.add(image);
        var request = new ReportRequest(PipelineResult.builder().build(), frames, files, directory, config,
                ReportRunContext.capture(DetectionRunOptions.defaults(), files), null, null);
        frames.clear();
        files[0] = files[0].withFitsHeader(java.util.Map.of("EXPTIME", "120"));
        files[0] = null;
        config.detectionSigmaMultiplier = sigma + 1;
        assertThrows(UnsupportedOperationException.class, () -> request.fitsFiles()[0].getFitsHeader().clear());
        request.fitsFiles()[0] = null;
        request.config().detectionSigmaMultiplier = sigma + 2;
        assertEquals("60", request.fitsFiles()[0].getFitsHeader().get("EXPTIME"));
        assertEquals(sigma, request.config().detectionSigmaMultiplier, 0);
        assertSame(image, request.rawFrames().get(0));
        assertThrows(UnsupportedOperationException.class, () -> request.rawFrames().clear());
    }

    /** Empty analyses and absent WCS/catalogue produce a complete model with no invented findings. */
    @Test public void emptyResultHasACompleteContextWithoutSkyData() throws Exception {
        var request = request(temporary.newFolder(), null);
        var context = DetectionReportContext.create(request, request.outputDirectory(), new ReportProgress(null));
        assertNotNull(context.data);
        assertNotNull(context.runContext);
        assertNull(context.astrometryContext.getTransformer());
        assertNull(context.catalogue().catalogue);
        assertTrue(context.data.findings.isEmpty());
        assertTrue(context.cameraFixedGroups.isEmpty());
        assertTrue(context.localRescueCandidates.isEmpty());
        assertEquals(List.of(0, 1, 2), context.sampleFrames(Set.of(1), 3));
        assertThrows(NullPointerException.class, () -> context.findingId(FindingCategory.MOVING_TARGET, 1));
        File report = ReportExporter.export(request);
        assertTrue(report.isFile());
        assertTrue(new File(report.getParentFile(), "report-data.json").isFile());
    }

    /** Cancellation both before modeling and after opening the staged page removes the unfinished run folder. */
    @Test public void cancellationRemovesUnfinishedOutputAtBothBoundaries() throws Exception {
        for (int boundary : List.of(0, 2)) {
            File output = new File(temporary.getRoot(), "cancel-" + boundary);
            var request = request(output, (percent, message) -> {
                if (percent >= boundary) throw new CancellationException("cancel export");
            });
            assertThrows(CancellationException.class, () -> ReportExporter.export(request));
            assertFalse(output.exists());
        }
    }

    /** A rendering failure preserves its cause and the previously published HTML instead of replacing it partially. */
    @Test public void ordinaryFailureKeepsPublishedHtmlAndItsReason() throws Exception {
        File output = temporary.newFolder();
        File published = new File(output, ReportExporter.REPORT_FILE_NAME);
        Files.writeString(published.toPath(), "previous complete report");
        var request = request(output, (percent, message) -> {
            if (percent >= 2) throw new IllegalStateException("report probe");
        });
        var failure = assertThrows(IllegalStateException.class, () -> ReportExporter.export(request));
        assertEquals("report probe", failure.getMessage());
        assertEquals("previous complete report", Files.readString(published.toPath()));
    }

    /** A request with no input metadata retains the previous no-report result without creating a folder. */
    @Test public void noFilesDoesNotCreateAnOutputFolder() throws Exception {
        File output = new File(temporary.getRoot(), "empty");
        var request = new ReportRequest(PipelineResult.builder().build(), List.of(), null, output, null,
                ReportRunContext.capture(DetectionRunOptions.defaults(), null), null, null);
        assertNull(ReportExporter.export(request));
        assertFalse(output.exists());
    }

    /** Creates a small non-WCS run without reading any FITS buffers from disk. */
    private ReportRequest request(File output, TransientEngineProgressListener listener) {
        FitsFileInformation[] files = files(temporary.getRoot());
        return new ReportRequest(PipelineResult.builder().build(),
                List.of(new short[24][24], new short[24][24], new short[24][24]), files, output,
                new DetectionConfig(), ReportRunContext.capture(DetectionRunOptions.defaults(), files), null, listener);
    }

    /** Supplies three detached frame identities; missing headers deliberately mean no WCS or observing timestamps. */
    private FitsFileInformation[] files(File directory) {
        FitsFileInformation[] files = new FitsFileInformation[3];
        for (int i = 0; i < files.length; i++) {
            String name = "frame" + i + ".fit";
            files[i] = new FitsFileInformation(new File(directory, name).toString(), name, true, 24, 24);
        }
        return files;
    }
}
