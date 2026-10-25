package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Inputs for one synchronous report export. Metadata, configuration and the frame container are copied; image
 * buffers and engine results remain shared by ownership and must not be changed during export. Options, WCS and
 * catalogue come from the context captured at run admission. A null output directory creates a new detections
 * folder beside the input frames; a null inclusion includes all groups, and a null listener disables progress.
 */
public record ReportRequest(PipelineResult result, List<short[][]> rawFrames, FitsFileInformation[] fitsFiles,
                            File outputDirectory, DetectionConfig config, ReportRunContext runContext,
                            ReportInclusion inclusion, TransientEngineProgressListener progress) {
    /** Takes small defensive snapshots without copying the large image buffers or rereading session files. */
    public ReportRequest {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(runContext, "runContext");
        rawFrames = rawFrames == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(rawFrames));
        fitsFiles = snapshot(fitsFiles);
        config = config == null ? null : config.clone();
        inclusion = inclusion == null ? ReportInclusion.all() : inclusion;
    }

    /** Returns detached frame metadata, preserving absent entries. */
    @Override public FitsFileInformation[] fitsFiles() {
        return snapshot(fitsFiles);
    }

    /** Returns a detached engine configuration, or null when it was not supplied. */
    @Override public DetectionConfig config() {
        return config == null ? null : config.clone();
    }

    /** Copies metadata without retaining a caller's mutable array or header maps. */
    private static FitsFileInformation[] snapshot(FitsFileInformation[] files) {
        return files == null ? new FitsFileInformation[0] : Arrays.stream(files)
                .map(file -> file == null ? null : file.snapshot()).toArray(FitsFileInformation[]::new);
    }
}
