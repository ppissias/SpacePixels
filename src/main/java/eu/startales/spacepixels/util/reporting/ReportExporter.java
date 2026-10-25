package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.Cancellation;
import eu.startales.spacepixels.util.DebugOutput;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.OutputFolders;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Owns normal report orchestration, output-folder selection, atomic HTML publication and cancellation cleanup. */
public final class ReportExporter {
    /** Main page of a detection report, also used when locating reports and linking iterative passes. */
    public static final String REPORT_FILE_NAME = "detection_report.html";

    /** Prevents construction of this stateless export entry point. */
    private ReportExporter() {}

    /**
     * Writes the request on the calling worker thread and returns its main page, or null when there are no files.
     * Progress reports 0–99 percent and may throw to cancel; cancellation removes the unfinished output folder.
     * Other failures preserve their reason and never publish an incomplete HTML page.
     */
    public static File export(ReportRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        FitsFileInformation[] files = request.fitsFiles();
        if (files.length == 0) return null;
        File exportDir = request.outputDirectory() == null
                ? OutputFolders.createDetectionsDirectory(new File(files[0].getFilePath())) : request.outputDirectory();
        Files.createDirectories(exportDir.toPath());
        try {
            ReportProgress progress = new ReportProgress(request.progress());
            progress.step(0, 2, "preparing");
            DetectionReportContext context = DetectionReportContext.create(request, exportDir, progress);
            write(context, request.result(), request.inclusion());
        } catch (IOException | RuntimeException failure) {
            if (Cancellation.isCancellation(failure)) OutputFolders.deleteUnfinished(exportDir);
            throw failure;
        }
        return new File(exportDir, REPORT_FILE_NAME);
    }

    /** Renders sections in report order using a context that is complete before any output is emitted. */
    private static void write(DetectionReportContext reportContext, PipelineResult result, ReportInclusion inclusion)
            throws IOException {
        File exportDir = reportContext.exportDir;
        ReportProgress progress = reportContext.progress;
        reportContext.data.writeJson(exportDir);

        File reportFile = new File(exportDir, REPORT_FILE_NAME);

        long reportStarted = System.nanoTime();
        try (AtomicReportFile output = new AtomicReportFile(reportFile)) {
            java.io.PrintWriter report = output.writer();
            DetectionReportDocumentWriter.appendDetectionReportStart(report);
            PipelineDiagnosticsSectionWriter.writeSessionHeader(report, reportContext, reportContext.pipelineTelemetry);
            report.println("<div class='report-toolbar'>");
            PipelineDiagnosticsSectionWriter.writeNavigation(report, buildNavigation(
                    reportContext, reportContext.pipelineTelemetry, reportContext.summary, reportContext.commonMotionGroups.size(), inclusion));
            ReportPresentation.controls(report, reportContext);
            report.println("</div>");

            // Results first: overview, moving objects, slow movers, residual review, maps, inspector, variable stars,
            // then the likely artifacts, folded. The shares of the bar follow the time each step took on a 33-frame
            // 61-megapixel session.
            progress.step(2, 16, "measuring the field depth and the night");
            FieldDepth fieldDepth = FieldDepth.measure(reportContext, reportContext.pipelineTelemetry);
            NightEquipment nightEquipment = NightEquipment.measure(reportContext, reportContext.pipelineTelemetry, reportContext.masterStars);
            DebugOutput.printf(Locale.US, "Night & Equipment measured in %.1f s%n", nightEquipment.millis / 1000.0);
            progress.step(16, 20, "overview");
            PipelineDiagnosticsSectionWriter.writeOverview(report, reportContext, reportContext.pipelineTelemetry, reportContext.summary, reportContext.localRescueTracks, fieldDepth, nightEquipment,
                    reportContext.commonMotionGroups.size(), inclusion);

            progress.step(20, 26, "moving objects and streaks");
            report.println("<section id='moving-objects'>");
            TargetVisualizationSectionWriter.writeSection(report, reportContext);
            report.println("</section>");

            if (FindingCategory.SLOW_MOVER.included(inclusion)) {
                progress.step(26, 27, "slow movers");
                report.println("<section id='slow-movers'>");
                DeepStackReportSectionWriter.writeSection(report, reportContext);
                report.println("</section>");
            }

            if (FindingCategory.LOCAL_RESCUE.included(inclusion) || FindingCategory.LOCAL_ACTIVITY.included(inclusion)) {
                progress.step(27, 28, "residual review");
                report.println("<section id='residual-review'>");
                ResidualReviewSectionWriter.writeSection(report, reportContext);
                report.println("</section>");
            }

            progress.step(28, 36, "maps");
            report.println("<section id='maps'>");
            GlobalMapsSectionWriter.writeSections(report, reportContext, reportContext.localRescueTracks, reportContext.allTransients);
            report.println("</section>");

            progress.step(36, 66, "unclassified transients");
            report.println("<section id='inspector'>");
            UnclassifiedTransientSectionWriter.writeSection(report, reportContext, reportContext.allTransients, reportContext.unclassifiedTransients,
                    UnclassifiedTransientSectionWriter.rejectedFrameIndices(reportContext.pipelineTelemetry),
                    reportContext.residualTransientAnalysis, result.cameraFixedGroups, inclusion);
            report.println("</section>");

            if (FindingCategory.VARIABLE_STAR.included(inclusion)) {
                progress.step(66, 68, "variable stars");
                report.println("<section id='variables'>");
                PhotometryReportSectionWriter.writeSection(report, reportContext, result.variableStarAnalysis);
                report.println("</section>");
            }

            progress.step(68, 96, "likely artifacts");
            ResidualReviewSectionWriter.writeLikelyArtifactsSection(report, reportContext, reportContext.cameraFixedGroups, reportContext.commonMotionGroups);

            progress.step(96, 99, "night & equipment and diagnostics");
            report.println("<section id='night-equipment'>");
            NightEquipmentSectionWriter.writeSection(report, nightEquipment, fieldDepth);
            report.println("</section>");

            // Processing diagnostics, collapsed.
            PipelineDiagnosticsSectionWriter.writeDiagnostics(
                    report,
                    reportContext,
                    reportContext.pipelineTelemetry,
                    reportContext.pipelineTelemetry != null ? reportContext.pipelineTelemetry.trackerTelemetry : null,
                    reportContext.summary,
                    result.driftPoints,
                    fieldDepth);

            ReportClientScriptWriter.appendLiveReportRenderingScript(report);
            ReportPresentation.appendScript(report);
            report.printf(Locale.US, "<p class='astro-note'>SpacePixels %s · JTransient %s · Generated %s · Report preparation %.2f s</p>%n",
                    ReportPresentation.version("spacepixels"), ReportPresentation.version("jtransient"), reportContext.data.generatedAtUtc,
                    (System.nanoTime() - reportStarted) / 1e9);
            DetectionReportDocumentWriter.appendHtmlDocumentEnd(report);
            output.commit();
        }
        DebugOutput.println("\nFinished exporting visualizations and generated HTML report at: " + reportFile.getAbsolutePath());
    }

    /** Builds navigation entries for the included report sections and their counts. */
    private static List<PipelineDiagnosticsSectionWriter.NavItem> buildNavigation(DetectionReportContext context,
                                                                                 PipelineTelemetry pipelineTelemetry,
                                                                                 DetectionReportSummary summary,
                                                                                 int commonMotionGroupCount,
                                                                                 ReportInclusion inclusion) {
        List<PipelineDiagnosticsSectionWriter.NavItem> items = new ArrayList<>();
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("overview", "Overview", null, false));
        int movingObjects = summary.movingTargetCount + summary.streakTrackCount + summary.singleStreakCount
                + summary.anomalyCount;
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("moving-objects", "Moving objects & events",
                String.valueOf(movingObjects), movingObjects == 0));
        if (FindingCategory.SLOW_MOVER.included(inclusion) && context.config != null && context.config.enableSlowMoverDetection) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("slow-movers", "Slow movers",
                    String.valueOf(summary.slowMoverCandidateCount), summary.slowMoverCandidateCount == 0));
        }
        int residual = summary.localRescueCandidateCount + summary.localActivityClusterCount;
        if ((FindingCategory.LOCAL_RESCUE.included(inclusion) || FindingCategory.LOCAL_ACTIVITY.included(inclusion)) && residual > 0) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("residual-review", "Local rescue", String.valueOf(residual), false));
        }
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("maps", "Maps", null, false));
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("inspector", "Unclassified", null, false));
        PipelineTelemetry.PhotometryTelemetry photometry = pipelineTelemetry != null ? pipelineTelemetry.photometryTelemetry : null;
        if (FindingCategory.VARIABLE_STAR.included(inclusion) && context.config != null && context.config.enableVariableStarDetection && photometry != null) {
            boolean notReady = "NOT_READY".equals(photometry.verdict);
            int candidates = photometry.highConfidence + photometry.possible;
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("variables", "Variable stars",
                    notReady ? "not ready" : String.valueOf(candidates), notReady || candidates == 0));
        } else if (FindingCategory.VARIABLE_STAR.included(inclusion)) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("variables", "Variable stars", "off", true));
        }
        int likelyArtifacts = (FindingCategory.COMMON_MOTION.included(inclusion) ? commonMotionGroupCount : 0) + context.cameraFixedGroups.size();
        if (likelyArtifacts > 0) {
            items.add(new PipelineDiagnosticsSectionWriter.NavItem("likely-artifacts", "Likely artifacts",
                    String.valueOf(likelyArtifacts), true));
        }
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("night-equipment", "Night & equipment", null, false));
        items.add(new PipelineDiagnosticsSectionWriter.NavItem("diagnostics", "Diagnostics", null, true));
        return items;
    }
}
