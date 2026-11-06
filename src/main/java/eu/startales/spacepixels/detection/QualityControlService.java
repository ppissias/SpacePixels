package eu.startales.spacepixels.detection;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.util.DebugOutput;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.execution.CancellationScope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import nom.tam.fits.Fits;

/** Reads all imported frames on workers and delegates every quality decision to the detection engine. */
public final class QualityControlService {
    /** Private read-only file identity used to detect replacements or edits during the two image passes. */
    private record Stamp(long bytes, FileTime modified, Object fileKey) {
        /** Reads file attributes on the worker, never on the EDT. */
        static Stamp read(Path path) throws IOException {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }

    /**
     * Captures inputs/settings before callbacks. Does no stacking, linking, catalogue work or writes. Reads a bounded
     * number of image buffers at once; FITS handles close after every read. Cancellation is supplied by its caller.
     */
    public QualityControlSnapshot evaluate(FitsFileInformation[] inputFiles, DetectionConfig config,
                                           TransientEngineProgressListener progress) throws Exception {
        FitsFileInformation[] files = inputFiles.clone();
        DetectionConfig captured = DetectionPipelineSupport.createEffectiveDetectionConfig(config, files.length);
        var identities = QualityControlSnapshot.inputs(files);
        String settingsKey = QualityControlSnapshot.settingsKey(captured);
        Path[] paths = new Path[files.length];
        Stamp[] stamps = new Stamp[files.length];
        for (int index = 0; index < files.length; index++) {
            CancellationScope.check();
            if (!files[index].isMonochrome()) throw new IOException("Quality control requires monochrome frames.");
            paths[index] = Path.of(files[index].getFilePath());
            stamps[index] = Stamp.read(paths[index]);
        }
        try (var engine = new JTransientEngine(); var diagnostics = DebugOutput.openEngineDiagnostics()) {
            var result = engine.evaluateFrameQuality(files.length, index -> {
                CancellationScope.check();
                checkUnchanged(paths[index], stamps[index]);
                try (Fits fits = new Fits(paths[index].toFile())) {
                    Object kernel = FitsInput.getImageHDU(fits).getKernel();
                    if (!(kernel instanceof short[][] pixels))
                        throw new IOException("Quality control requires unsigned-16-bit monochrome input: " + paths[index].getFileName());
                    if (pixels.length != files[index].getSizeHeight() || pixels[0].length != files[index].getSizeWidth())
                        throw new IOException("Frame dimensions changed since import: " + paths[index].getFileName());
                    checkUnchanged(paths[index], stamps[index]);
                    return new ImageFrame(index, paths[index].getFileName().toString(), pixels,
                            files[index].getObservationTimestamp(), files[index].getExposureDurationMillis());
                }
            }, captured, progress);
            for (int index = 0; index < paths.length; index++) checkUnchanged(paths[index], stamps[index]);
            return new QualityControlSnapshot(identities, settingsKey, result);
        }
    }

    /** Rejects a changed input instead of displaying a result assembled from different versions of a frame. */
    private static void checkUnchanged(Path path, Stamp expected) throws IOException {
        if (!expected.equals(Stamp.read(path))) throw new IOException("Frame changed during quality control: " + path.getFileName());
    }
}
