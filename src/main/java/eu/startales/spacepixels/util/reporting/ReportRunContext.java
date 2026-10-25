package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SkySnapshot;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.util.FitsFileInformation;
import java.util.Arrays;
import java.util.Objects;

/** Run-owned site/WCS/catalogue snapshot resolved before detection; it owns metadata and retains no image buffers. */
public final class ReportRunContext {
    private final DetectionRunOptions options;
    private final FitsFileInformation[] files;
    private final DetectionReportAstrometry.Context astrometry;
    private final SessionCatalogue catalogue;

    /**
     * Resolves the correction and the catalogue once against privately copied FITS metadata: from {@code sky} when it
     * is the sky data of the frames' folder, otherwise from that folder's files.
     */
    private ReportRunContext(DetectionRunOptions options, FitsFileInformation[] files, SkySnapshot sky) {
        this.options = Objects.requireNonNull(options);
        this.files = snapshot(files);
        java.io.File directory = Arrays.stream(this.files).filter(Objects::nonNull)
                .map(FitsFileInformation::getFilePath).filter(Objects::nonNull)
                .map(path -> new java.io.File(path).getAbsoluteFile().getParentFile()).findFirst().orElse(null);
        boolean fromSession = sky != null && sky.belongsTo(directory);
        var correction = fromSession ? sky.correction() : directory == null ? null : PlateSolutionCorrection.load(directory);
        this.astrometry = AstrometryContextFactory.buildContext(this.files, options.applicationConfig(), correction);
        this.catalogue = fromSession ? SessionCatalogue.of(this.files, astrometry, folder -> sky.catalogue())
                : SessionCatalogue.load(this.files, astrometry);
    }

    /**
     * Captures the run's context when it is admitted, before callbacks or engine work can see later edits; the
     * correction and the catalogue are read from the frames' folder once. Used by headless runs.
     */
    public static ReportRunContext capture(DetectionRunOptions options, FitsFileInformation[] files) {
        return new ReportRunContext(options, files, null);
    }

    /**
     * As {@link #capture(DetectionRunOptions, FitsFileInformation[])}, but with the session's correction and catalogue
     * when the frames are in the session's folder, so the run uses what the window shows, saved or not.
     */
    public static ReportRunContext capture(DetectionRunOptions options, FitsFileInformation[] files, SkySnapshot sky) {
        return new ReportRunContext(options, files, sky);
    }

    /** Returns the immutable application options used by this run. */
    public DetectionRunOptions options() {
        return options;
    }

    /** Returns independent metadata copies so callers cannot mutate the context or its astrometry timestamps. */
    public FitsFileInformation[] filesInformation() {
        return snapshot(files);
    }

    /** Builds the viewer's detached candidate labels from the same catalogue/WCS snapshot used by the report. */
    public eu.startales.spacepixels.util.VariableStarOverlay variableStarOverlay(io.github.ppissias.jtransient.engine.PipelineResult result) {
        return eu.startales.spacepixels.util.VariableStarOverlay.from(result, files, astrometry.getTransformer(), catalogue.catalogue);
    }

    /** Supplies the resolved astrometry to report writers without another correction-file lookup. */
    DetectionReportAstrometry.Context astrometry() {
        return astrometry;
    }

    /** Supplies the privately loaded catalogue, including an explicit absent catalogue, without rereading disk. */
    SessionCatalogue catalogue() {
        return catalogue;
    }

    /** Copies only metadata while preserving null slots used by component-level report callers. */
    private static FitsFileInformation[] snapshot(FitsFileInformation[] files) {
        return files == null ? new FitsFileInformation[0] : files.clone();

    }
}
