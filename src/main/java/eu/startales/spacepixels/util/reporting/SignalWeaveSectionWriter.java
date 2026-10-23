package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Locale;

/** Codex's optional, deterministic visual interpretation of the measured session. */
final class SignalWeaveSectionWriter {
    private SignalWeaveSectionWriter() { }

    static void write(PrintWriter out, DetectionReportContext context, PipelineTelemetry telemetry,
                      List<List<DetectedObject>> transients,
                      List<List<DetectedObject>> leftovers) throws IOException {
        short[][] background = context.masterStackData != null ? context.masterStackData
                : (context.rawFrames.isEmpty() ? null : context.rawFrames.get(0));
        if (background == null) return;
        BufferedImage image = CreativeTributeRenderer.createCreativeTributeImage(background, transients, leftovers,
                context.anomalies, context.singleStreaks, context.streakTracks, context.movingTargets,
                context.slowMoverCandidates, telemetry, context.settings, context);
        String filename = "creative_tribute_skyprint.png";
        TrackVisualizationRenderer.saveLosslessPng(image, new File(context.exportDir, filename));
        image.flush();

        int raw = CreativeTributeRenderer.countTotalTransientDetections(transients);
        int unresolved = CreativeTributeRenderer.countTotalTransientDetections(leftovers);
        int variables = context.data == null ? 0 : context.data.variableCandidates.size();
        long commonGroups = context.data == null ? 0 : context.data.findings.stream().filter(f -> f.id().startsWith("CM")).count();
        out.println("<div class='panel signal-weave' style='background:linear-gradient(150deg,#162b36,#252333 65%,#2b2b2b);border:1px solid #536875;'>");
        out.println("<h2>The AI's Perspective: Signal Weave</h2>");
        out.println("<p class='section-lede'>Motion writes paths. Variability stays put. Noise often repeats itself.</p>");
        out.println("<p>A visual interpretation by Codex, drawn from this run's measurements. "
                + "The weave brings motion, brightness changes and unresolved detections into one field atlas. "
                + "Its glow is an artistic emphasis, not a measurement of brightness or confidence.</p>");
        out.println("<a href='" + filename + "' target='_blank'><img loading='lazy' decoding='async' src='" + filename
                + "' style='display:block;width:100%;height:auto;border:1px solid #536875;border-radius:8px;' "
                + "alt='Signal Weave: linked paths, anomaly pulses, variable-star rings and the shared data-area outline' /></a>");
        out.println("<div class='flex-container' style='margin-top:16px;'>");
        metric(out, raw, "Post-veto detections", "#96dcff");
        metric(out, context.movingTargets.size() + context.streakTracks.size(), "Linked tracks", "#4da6ff");
        metric(out, variables, "Variable-star candidates", "#73f0c3");
        metric(out, commonGroups, "Common-motion groups · likely noise", "#a4adb7");
        metric(out, unresolved, "Unclassified detections", "#ffae5c");
        out.println("</div>");
        out.println("<h3>What this night's pattern says</h3><p>" + DetectionReportGenerator.escapeHtml(
                CreativeTributeRenderer.buildCreativeSignalInterpretation(transients, leftovers, context.anomalies,
                        context.singleStreaks, context.streakTracks, context.movingTargets, context.slowMoverCandidates)) + "</p>");
        out.println("<p>" + (variables > 0 ? "Mint double rings mark " + variables
                + " variable-star candidates at fixed positions: another kind of change, measured in brightness rather than travel. "
                : "No variable-star candidates are included in this view. ")
                + (commonGroups > 0 ? "The " + commonGroups + " common-motion groups are most likely noise or systematic artifacts. "
                + "They are counted separately here; the Common Motion section shows why they were filtered out. " : "")
                + "Amber leftovers remain unresolved; neither their number nor their glow establishes an astronomical source.</p>");
        if (context.dataArea != null) {
            out.printf(Locale.US, "<p class='astro-note'>The shared data area covers <strong>%.1f%%</strong> of the frame. "
                    + "Its dashed blue boundary shows where every kept frame has image data; parts of the outline may lie outside this crop.</p>%n",
                    context.dataArea.getFraction() * 100);
        }
        out.println("<p class='astro-note'>" + (telemetry == null ? "" : telemetry.totalFramesKept + " frames kept; "
                + telemetry.totalFramesRejected + " rejected. ")
                + "Net linked-motion trend in image coordinates: " + DetectionReportGenerator.escapeHtml(
                CreativeTributeRenderer.computeDominantMotionLabel(context.movingTargets, context.streakTracks))
                + String.format(Locale.US, ". Longest linked path: %.1f px. ",
                CreativeTributeRenderer.computeLongestTrackPathPx(context.streakTracks, context.movingTargets))
                + "Frame numbers start at 1. This illustration adds no detections or classifications; inspect the finding cards and light curves for evidence.</p>");
        out.println("</div>");
    }

    private static void metric(PrintWriter out, long value, String label, String color) {
        out.println("<div class='metric-box compact' style='border-left-color:" + color + ";'><span class='metric-value'>"
                + value + "</span><span class='metric-label'>" + label + "</span></div>");
    }
}
