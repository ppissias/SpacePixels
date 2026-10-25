package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import java.io.File;
import java.util.List;

/** Section fixtures use the same complete context factory as a normal export, including captured absent sky data. */
final class ReportContextFixtures {
    /** Prevents construction of this fixture helper. */
    private ReportContextFixtures() {}

    /** Captures fixture options and builds all report identities before returning a section's context. */
    static DetectionReportContext create(VisualizationSettings settings, File directory, List<short[][]> frames,
            FitsFileInformation[] files, DetectionConfig config, PipelineResult result) {
        var options = DetectionRunOptions.defaults().withVisualization(settings == null ? VisualizationSettings.defaults() : settings);
        return createWithOptions(options, directory, frames, files, config, result);
    }

    /** Captures explicit observer options and metadata through the production admission path. */
    static DetectionReportContext createWithOptions(DetectionRunOptions options, File directory, List<short[][]> frames,
            FitsFileInformation[] files, DetectionConfig config, PipelineResult result) {
        var request = new ReportRequest(result, frames, files, directory, config,
                ReportRunContext.capture(options, files), null, null);
        return DetectionReportContext.create(request, directory, new ReportProgress(null));
    }
}
