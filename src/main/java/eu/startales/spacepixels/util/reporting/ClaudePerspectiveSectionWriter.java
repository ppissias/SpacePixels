/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.core.TrackLinker;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "The AI's Perspective: The Night, Retold", Claude's page of the report: one timeline of the whole session
 * (frames kept and rejected, sky background, seeing, every moving object and event, and the variable stars)
 * and a short account of the night written from the measured numbers.
 */
final class ClaudePerspectiveSectionWriter {

    private static final int WIDTH = 1100;
    private static final int LEFT = 132;
    private static final int RIGHT = 24;
    private static final int MAX_VARIABLE_LANES = 6;
    private static final String GRID = "#3a3f44";
    private static final String TEXT = "#aab4bd";
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CLOCK_SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

    private ClaudePerspectiveSectionWriter() {
    }

    /** Session time axis: capture time of every loaded frame, or its index when timestamps are missing. */
    private static final class Timeline {
        final double[] position;
        final boolean timed;
        final double start;
        final double end;

        Timeline(FitsFileInformation[] files) {
            int n = files == null ? 0 : files.length;
            position = new double[n];
            boolean allTimed = n > 0;
            for (int i = 0; i < n; i++) {
                long t = files[i] != null ? files[i].getObservationTimestamp() : -1L;
                if (t <= 0) {
                    allTimed = false;
                    break;
                }
                position[i] = t + Math.max(0L, files[i].getExposureDurationMillis()) / 2.0;
            }
            timed = allTimed;
            if (!timed) {
                for (int i = 0; i < n; i++) {
                    position[i] = i;
                }
            }
            double lo = Double.POSITIVE_INFINITY;
            double hi = Double.NEGATIVE_INFINITY;
            for (double p : position) {
                lo = Math.min(lo, p);
                hi = Math.max(hi, p);
            }
            start = n > 0 ? lo : 0;
            end = n > 0 && hi > lo ? hi : start + 1;
        }

        double at(int frameIndex) {
            return frameIndex >= 0 && frameIndex < position.length ? position[frameIndex] : Double.NaN;
        }

        double x(double value) {
            return LEFT + (value - start) / (end - start) * (WIDTH - LEFT - RIGHT);
        }

        String label(double value) {
            return timed ? CLOCK_SECONDS.format(Instant.ofEpochMilli(Math.round(value))) + " UTC" : "frame " + (Math.round(value) + 1);
        }
    }

    static void writeSection(PrintWriter report,
                             DetectionReportContext context,
                             PipelineTelemetry telemetry,
                             DetectionReportSummary summary,
                             VariableStarAnalysis variables) {
        if (telemetry == null || context.fitsFiles == null || context.fitsFiles.length == 0) {
            return;
        }
        Timeline timeline = new Timeline(context.fitsFiles);
        List<StarLightCurve> shownVariables = new ArrayList<>();
        if (variables != null && variables.readiness != null && variables.readiness.allowsScoring()) {
            for (StarLightCurve star : variables.candidates) {
                if (star.tier == VariabilityTier.HIGH_CONFIDENCE && shownVariables.size() < MAX_VARIABLE_LANES) {
                    shownVariables.add(star);
                }
            }
        }

        report.println("<div class='panel' style='background: linear-gradient(180deg, #2a2f3a 0%, #2b2b2b 100%); border: 1px solid #4b5566;'>");
        report.println("<h2 style='color:#d9c7ff;'>The AI's Perspective: The Night, Retold</h2>");
        report.println("<p style='color:#c8c2d8; font-size:14px; margin-top:-10px; margin-bottom:16px; font-style:italic;'>Written by Claude. "
                + "The rest of this report sorts the session into categories. Here I put it back together in time order, the way the night actually happened, and tell it in a few sentences.</p>");
        report.println(timelineSvg(context, telemetry, timeline, variables, shownVariables));
        report.println("<div style='max-width: 900px; font-size: 15px; line-height: 1.7; color: #e3e0ea; margin-top: 18px;'>");
        for (String paragraph : narrative(context, telemetry, summary, variables, timeline)) {
            report.println("<p style='margin: 0 0 12px 0;'>" + paragraph + "</p>");
        }
        report.println("<p style='margin: 14px 0 0 0; color:#b9b2c9; font-style: italic;'>&mdash; Claude</p>");
        report.println("</div>");
        report.println("</div>");
    }

    // =================================================================
    // Timeline
    // =================================================================

    private static String timelineSvg(DetectionReportContext context, PipelineTelemetry telemetry, Timeline timeline,
                                      VariableStarAnalysis variables, List<StarLightCurve> shownVariables) {
        int framesTop = 8;
        int framesHeight = 22;
        int skyTop = framesTop + framesHeight + 18;
        int lineHeight = 54;
        int seeingTop = skyTop + lineHeight + 14;
        int eventsTop = seeingTop + lineHeight + 18;
        int eventsHeight = 46;
        int variablesTop = eventsTop + eventsHeight + 18;
        int variableHeight = 34;
        int axisTop = variablesTop + shownVariables.size() * (variableHeight + 8) + 6;
        int height = axisTop + 34;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ").append(WIDTH).append(' ').append(height)
                .append("' width='").append(WIDTH).append("' height='").append(height)
                .append("' style='max-width:100%; height:auto; background:#23262b; border-radius:6px;' font-family=\"'Segoe UI', Tahoma, sans-serif\">");
        svg.append("<title>The session in time order</title>");

        // Frames lane: kept and rejected frames.
        laneLabel(svg, framesTop + framesHeight / 2.0, "Frames");
        for (PipelineTelemetry.FrameQualityStat stat : telemetry.frameQualityStats) {
            double t = timeline.at(stat.frameIndex);
            if (Double.isNaN(t)) {
                continue;
            }
            double x = timeline.x(t);
            String color = stat.rejected ? "#e05a5a" : "#5d87b5";
            String tip = "Frame " + (stat.frameIndex + 1) + " at " + timeline.label(t) + ": "
                    + (stat.rejected ? "rejected (" + safe(stat.rejectionReason) + ")" : "kept");
            svg.append("<rect x='").append(fmt(x - 1.5)).append("' y='").append(framesTop).append("' width='3' height='").append(framesHeight)
                    .append("' fill='").append(color).append("'><title>").append(escape(tip)).append("</title></rect>");
        }

        // Sky background and seeing, from the quality-control measurements.
        List<double[]> sky = new ArrayList<>();
        List<double[]> seeing = new ArrayList<>();
        for (PipelineTelemetry.FrameQualityStat stat : telemetry.frameQualityStats) {
            double t = timeline.at(stat.frameIndex);
            if (Double.isNaN(t) || stat.rejected) {
                continue;
            }
            if (Double.isFinite(stat.backgroundMedian)) {
                sky.add(new double[]{t, stat.backgroundMedian, stat.frameIndex});
            }
            if (Double.isFinite(stat.medianFWHM)) {
                seeing.add(new double[]{t, stat.medianFWHM, stat.frameIndex});
            }
        }
        seriesLane(svg, timeline, sky, skyTop, lineHeight, "Sky background", "ADU", "#9fb8d6", 0, 0.02);
        seriesLane(svg, timeline, seeing, seeingTop, lineHeight, "Seeing (FWHM)", "px", "#b9a3e0", 2, 0.2);

        // Events lane: tracks as bars over their time span, single-frame events as marks.
        laneLabel(svg, eventsTop + eventsHeight / 2.0, "Movers & events");
        svg.append("<line x1='").append(LEFT).append("' x2='").append(WIDTH - RIGHT).append("' y1='").append(eventsTop + eventsHeight / 2)
                .append("' y2='").append(eventsTop + eventsHeight / 2).append("' stroke='").append(GRID).append("' stroke-width='1'/>");
        int row = 0;
        row = trackBars(svg, timeline, context.movingTargets, "T", "#4da6ff", "moving-object track", eventsTop, row);
        row = trackBars(svg, timeline, context.streakTracks, "ST", "#ffcc33", "streak track", eventsTop, row);
        trackBars(svg, timeline, context.suspectedStreakTracks, "SST", "#ffb347", "suspected streak track", eventsTop, row);
        int counter = 1;
        for (TrackLinker.Track track : context.singleStreaks) {
            int frame = track.points.isEmpty() ? -1 : track.points.get(0).sourceFrameIndex;
            eventMark(svg, timeline, frame, eventsTop, eventsHeight, "#ff9933", "S" + counter, "Single-frame streak S" + counter, true);
            counter++;
        }
        counter = 1;
        for (TrackLinker.AnomalyDetection anomaly : context.anomalies) {
            int frame = anomaly.object != null ? anomaly.object.sourceFrameIndex : -1;
            eventMark(svg, timeline, frame, eventsTop, eventsHeight, "#ff4d6d", context.anomalies.size() <= 8 ? "A" + counter : null,
                    "Single-frame anomaly A" + counter, false);
            counter++;
        }

        // Variable stars: one small light curve per high-confidence candidate, brighter up.
        int lane = 0;
        for (StarLightCurve star : shownVariables) {
            int top = variablesTop + lane * (variableHeight + 8);
            variableLane(svg, timeline, variables, star, lane + 1, top, variableHeight);
            lane++;
        }

        // Time axis.
        svg.append("<line x1='").append(LEFT).append("' x2='").append(WIDTH - RIGHT).append("' y1='").append(axisTop)
                .append("' y2='").append(axisTop).append("' stroke='#6a6a66'/>");
        int ticks = 6;
        for (int k = 0; k <= ticks; k++) {
            double value = timeline.start + (timeline.end - timeline.start) * k / ticks;
            double x = timeline.x(value);
            svg.append("<line x1='").append(fmt(x)).append("' x2='").append(fmt(x)).append("' y1='").append(axisTop).append("' y2='").append(axisTop + 5)
                    .append("' stroke='#6a6a66'/>");
            String label = timeline.timed ? CLOCK.format(Instant.ofEpochMilli(Math.round(value))) : String.valueOf(Math.round(value) + 1);
            svg.append("<text x='").append(fmt(x)).append("' y='").append(axisTop + 18).append("' fill='").append(TEXT)
                    .append("' font-size='11' text-anchor='middle'>").append(label).append("</text>");
        }
        svg.append("<text x='").append(LEFT - 30).append("' y='").append(axisTop + 18).append("' fill='").append(TEXT)
                .append("' font-size='11' text-anchor='end'>").append(timeline.timed ? "UTC" : "Frame").append("</text>");
        svg.append("</svg>");
        return svg.toString();
    }

    private static void laneLabel(StringBuilder svg, double y, String label) {
        svg.append("<text x='").append(LEFT - 12).append("' y='").append(fmt(y + 4)).append("' fill='#d6dde4' font-size='12' text-anchor='end'>")
                .append(escape(label)).append("</text>");
    }

    /** A labelled line of per-frame values scaled to the lane, with its range and hover values. */
    private static void seriesLane(StringBuilder svg, Timeline timeline, List<double[]> points, int top, int height,
                                   String label, String unit, String color, int digits, double minRelativeSpan) {
        laneLabel(svg, top + height / 2.0, label);
        svg.append("<rect x='").append(LEFT).append("' y='").append(top).append("' width='").append(WIDTH - LEFT - RIGHT).append("' height='").append(height)
                .append("' fill='none' stroke='").append(GRID).append("'/>");
        if (points.size() < 2) {
            return;
        }
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (double[] p : points) {
            lo = Math.min(lo, p[1]);
            hi = Math.max(hi, p[1]);
        }
        // A minimum span relative to the level keeps a steady series flat instead of magnifying tiny wobbles.
        double minSpan = Math.max(1e-6, minRelativeSpan * Math.abs((hi + lo) / 2));
        if (hi - lo < minSpan) {
            double mid = (hi + lo) / 2;
            lo = mid - minSpan / 2;
            hi = mid + minSpan / 2;
        }
        double span = hi - lo;
        StringBuilder path = new StringBuilder();
        for (double[] p : points) {
            double x = timeline.x(p[0]);
            double y = top + height - 6 - (p[1] - lo) / span * (height - 12);
            path.append(path.length() == 0 ? "M" : " L").append(fmt(x)).append(' ').append(fmt(y));
        }
        svg.append("<path d='").append(path).append("' fill='none' stroke='").append(color).append("' stroke-width='1.8' stroke-linejoin='round'/>");
        for (double[] p : points) {
            double x = timeline.x(p[0]);
            double y = top + height - 6 - (p[1] - lo) / span * (height - 12);
            svg.append("<circle cx='").append(fmt(x)).append("' cy='").append(fmt(y)).append("' r='4' fill='transparent'><title>")
                    .append(escape("Frame " + ((int) p[2] + 1) + " at " + timeline.label(p[0]) + ": " + format(p[1], digits) + " " + unit))
                    .append("</title></circle>");
        }
        svg.append("<text x='").append(WIDTH - RIGHT - 4).append("' y='").append(top + 12).append("' fill='").append(TEXT)
                .append("' font-size='10' text-anchor='end'>").append(escape(format(hi, digits) + " " + unit)).append("</text>");
        svg.append("<text x='").append(WIDTH - RIGHT - 4).append("' y='").append(top + height - 4).append("' fill='").append(TEXT)
                .append("' font-size='10' text-anchor='end'>").append(escape(format(lo, digits) + " " + unit)).append("</text>");
    }

    /** Draws each track as a bar from its first to its last frame, stacked in rows above the lane's midline. */
    private static int trackBars(StringBuilder svg, Timeline timeline, List<TrackLinker.Track> tracks, String prefix,
                                 String color, String kind, int top, int row) {
        int counter = 1;
        for (TrackLinker.Track track : tracks) {
            int first = Integer.MAX_VALUE;
            int last = Integer.MIN_VALUE;
            for (SourceExtractor.DetectedObject point : track.points) {
                first = Math.min(first, point.sourceFrameIndex);
                last = Math.max(last, point.sourceFrameIndex);
            }
            double t0 = timeline.at(first);
            double t1 = timeline.at(last);
            if (Double.isNaN(t0) || Double.isNaN(t1)) {
                counter++;
                continue;
            }
            double x0 = timeline.x(t0);
            double x1 = Math.max(x0 + 4, timeline.x(t1));
            int y = top + 2 + (row % 3) * 7;
            String tip = prefix + counter + ", " + kind + ": " + timeline.label(t0) + " to " + timeline.label(t1) + ", " + track.points.size() + " detections";
            svg.append("<rect x='").append(fmt(x0)).append("' y='").append(y).append("' width='").append(fmt(x1 - x0)).append("' height='5' rx='2' fill='")
                    .append(color).append("'><title>").append(escape(tip)).append("</title></rect>");
            row++;
            counter++;
        }
        return row;
    }

    private static void eventMark(StringBuilder svg, Timeline timeline, int frame, int top, int height, String color,
                                  String code, String description, boolean line) {
        double t = timeline.at(frame);
        if (Double.isNaN(t)) {
            return;
        }
        double x = timeline.x(t);
        String tip = escape(description + " at " + timeline.label(t));
        if (line) {
            svg.append("<line x1='").append(fmt(x)).append("' x2='").append(fmt(x)).append("' y1='").append(top + height / 2 - 2).append("' y2='").append(top + height)
                    .append("' stroke='").append(color).append("' stroke-width='3'><title>").append(tip).append("</title></line>");
        } else {
            svg.append("<circle cx='").append(fmt(x)).append("' cy='").append(top + height * 3 / 4).append("' r='4.5' fill='").append(color)
                    .append("'><title>").append(tip).append("</title></circle>");
        }
        if (code != null) {
            svg.append("<text x='").append(fmt(x + 4)).append("' y='").append(top + height - 2).append("' fill='").append(color)
                    .append("' font-size='9'>").append(escape(code)).append("</text>");
        }
    }

    private static void variableLane(StringBuilder svg, Timeline timeline, VariableStarAnalysis analysis, StarLightCurve star,
                                     int number, int top, int height) {
        laneLabel(svg, top + height / 2.0, "V" + number + " (" + format(star.amplitude, 2) + " mag)");
        svg.append("<rect x='").append(LEFT).append("' y='").append(top).append("' width='").append(WIDTH - LEFT - RIGHT).append("' height='").append(height)
                .append("' fill='none' stroke='").append(GRID).append("'/>");
        List<double[]> points = new ArrayList<>();
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < analysis.frames.size() && j < star.deltaMag.length; j++) {
            if (star.flags[j] != 0 || !Double.isFinite(star.deltaMag[j])) {
                continue;
            }
            double t = timeline.at(analysis.frames.get(j).frameIndex);
            if (Double.isNaN(t)) {
                continue;
            }
            points.add(new double[]{t, star.deltaMag[j]});
            lo = Math.min(lo, star.deltaMag[j]);
            hi = Math.max(hi, star.deltaMag[j]);
        }
        if (points.size() < 2) {
            return;
        }
        double span = hi > lo ? hi - lo : 0.01;
        StringBuilder path = new StringBuilder();
        for (double[] p : points) {
            // Magnitudes: brighter (smaller) values drawn higher.
            double y = top + 4 + (p[1] - lo) / span * (height - 8);
            path.append(path.length() == 0 ? "M" : " L").append(fmt(timeline.x(p[0]))).append(' ').append(fmt(y));
        }
        svg.append("<path d='").append(path).append("' fill='none' stroke='#4da6ff' stroke-width='1.6' stroke-linejoin='round'><title>")
                .append(escape("V" + number + ": " + PhotometryReportSectionWriter.describeChange(star).toLowerCase(Locale.ROOT)
                        + " by " + format(star.amplitude, 3) + " mag (brighter up)"))
                .append("</title></path>");
    }

    // =================================================================
    // Narrative
    // =================================================================

    private static List<String> narrative(DetectionReportContext context, PipelineTelemetry telemetry, DetectionReportSummary summary,
                                          VariableStarAnalysis variables, Timeline timeline) {
        List<String> paragraphs = new ArrayList<>();

        // 1. The session and the frames.
        StringBuilder opening = new StringBuilder("I read ").append(telemetry.totalFramesLoaded).append(" frames");
        if (timeline.timed) {
            long minutes = Math.round((timeline.end - timeline.start) / 60000.0);
            opening.append(" taken on ").append(DAY.format(Instant.ofEpochMilli(Math.round(timeline.start))))
                    .append(" between ").append(CLOCK.format(Instant.ofEpochMilli(Math.round(timeline.start))))
                    .append(" and ").append(CLOCK.format(Instant.ofEpochMilli(Math.round(timeline.end)))).append(" UTC")
                    .append(", ").append(describeDuration(minutes)).append(" of sky");
        }
        String field = fieldDescription(context);
        if (field != null) {
            opening.append(" around ").append(field);
        }
        opening.append('.');
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (PipelineTelemetry.FrameQualityStat stat : telemetry.frameQualityStats) {
            if (stat.rejected) {
                reasons.merge(plainRejection(stat.rejectionReason), 1, Integer::sum);
            }
        }
        if (telemetry.totalFramesRejected == 0) {
            opening.append(" Every frame met the quality bar.");
        } else {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, Integer> e : reasons.entrySet()) {
                parts.add(e.getValue() + " " + e.getKey());
            }
            opening.append(' ').append(capitalise(countWord(telemetry.totalFramesRejected, "frame"))).append(telemetry.totalFramesRejected == 1 ? " was" : " were")
                    .append(" set aside").append(parts.isEmpty() ? "" : ": " + joinAnd(parts)).append('.');
        }
        paragraphs.add(escape(opening.toString()));

        // 2. Conditions through the night.
        String conditions = conditions(telemetry, variables);
        if (conditions != null) {
            paragraphs.add(escape(conditions));
        }

        // 3. What moved or flashed.
        List<String> events = new ArrayList<>();
        if (summary.movingTargetCount > 0) {
            events.add(countWord(summary.movingTargetCount, "object") + " moved steadily across the field from frame to frame"
                    + (summary.movingTargetCount == 1 ? " (T1)" : " (the T tracks)"));
        }
        if (summary.streakTrackCount > 0) {
            events.add(countWord(summary.streakTrackCount, "streak") + " repeated along one line over several frames");
        }
        // Single-frame streaks grouped by frame: one satellite can be extracted as several streaks.
        Map<Integer, List<Integer>> streaksByFrame = new LinkedHashMap<>();
        int counter = 1;
        for (TrackLinker.Track track : context.singleStreaks) {
            int frame = track.points.isEmpty() ? -1 : track.points.get(0).sourceFrameIndex;
            streaksByFrame.computeIfAbsent(frame, k -> new ArrayList<>()).add(counter++);
        }
        int told = 0;
        int untold = 0;
        for (Map.Entry<Integer, List<Integer>> e : streaksByFrame.entrySet()) {
            if (told >= 3) {
                untold += e.getValue().size();
                continue;
            }
            double t = timeline.at(e.getKey());
            List<Integer> codes = e.getValue();
            String label = codes.size() == 1 ? "S" + codes.get(0) : "S" + codes.get(0) + "–S" + codes.get(codes.size() - 1);
            events.add((timeline.timed && !Double.isNaN(t) ? "at " + CLOCK.format(Instant.ofEpochMilli(Math.round(t))) + " UTC " : "")
                    + (codes.size() == 1 ? "something fast cut a streak across a single frame"
                    : countWord(codes.size(), "streak") + " crossed a single frame, probably one object broken into pieces")
                    + " (" + label + ")");
            told++;
        }
        if (untold > 0) {
            events.add(countWord(untold, "more single-frame streak") + " crossed later");
        }
        if (summary.anomalyCount > 0) {
            events.add(countWord(summary.anomalyCount, "flash") + " of light appeared in just one frame");
        }
        if (summary.slowMoverCandidateCount > 0) {
            events.add(countWord(summary.slowMoverCandidateCount, "faint smear") + " in the maximum stack may be something moving very slowly");
        }
        if (events.isEmpty()) {
            paragraphs.add(escape("Nothing moved through the field that I could link or confirm: no tracks, no streaks, no single-frame flashes."));
        } else {
            paragraphs.add(escape("As for what moved: " + joinAnd(events) + "."));
        }

        // 4. The stars that changed.
        String stars = variableStory(telemetry, variables);
        if (stars != null) {
            paragraphs.add(escape(stars));
        }

        // 5. A closing thought, grounded in the numbers.
        int found = summary.returnedTrackCount + summary.anomalyCount + summary.slowMoverCandidateCount
                + (telemetry.photometryTelemetry != null ? telemetry.photometryTelemetry.highConfidence : 0);
        if (telemetry.totalRawObjectsExtracted > 0) {
            String remainder = found == 0
                    ? "every one of them turned out to be a star, noise or an artefact doing exactly what it should. Here the remainder is empty, and that is a result too."
                    : String.format(Locale.US, "all but %,d of them turned out to be stars, noise or artefacts doing exactly what they should. "
                    + "The interesting part of the night is that small remainder, and every item in it is above, with its evidence, so you can judge it yourself.", found);
            paragraphs.add(escape(String.format(Locale.US,
                    "What I find striking in a session like this is how much of the work is ruling things out. Across the frames there were %,d detections, and ",
                    telemetry.totalRawObjectsExtracted) + remainder));
        }
        return paragraphs;
    }

    private static String conditions(PipelineTelemetry telemetry, VariableStarAnalysis variables) {
        List<Double> fwhm = new ArrayList<>();
        List<Double> background = new ArrayList<>();
        for (PipelineTelemetry.FrameQualityStat stat : telemetry.frameQualityStats) {
            if (stat.rejected) {
                continue;
            }
            if (Double.isFinite(stat.medianFWHM)) {
                fwhm.add(stat.medianFWHM);
            }
            if (Double.isFinite(stat.backgroundMedian)) {
                background.add(stat.backgroundMedian);
            }
        }
        if (fwhm.size() < 2) {
            return null;
        }
        double fLo = min(fwhm);
        double fHi = max(fwhm);
        double fMid = median(fwhm);
        StringBuilder text = new StringBuilder();
        if (fHi - fLo <= 0.15 * fMid) {
            text.append(String.format(Locale.US, "The seeing held steady near %.1f px.", fMid));
        } else {
            text.append(String.format(Locale.US, "The seeing moved between %.1f and %.1f px.", fLo, fHi));
        }
        if (background.size() >= 2) {
            double first = background.get(0);
            double last = background.get(background.size() - 1);
            double bMid = median(background);
            double change = (last - first) / Math.max(1.0, bMid);
            if (Math.abs(change) < 0.05) {
                text.append(" The sky background stayed level.");
            } else {
                text.append(String.format(Locale.US, " The sky background %s by %.0f%% from the first frame to the last.",
                        change > 0 ? "rose" : "fell", Math.abs(change) * 100));
            }
        }
        PipelineTelemetry.PhotometryTelemetry photometry = telemetry.photometryTelemetry;
        if (photometry != null && Double.isFinite(photometry.zeroPointRangeMag)) {
            text.append(photometry.zeroPointRangeMag < 0.05
                    ? String.format(Locale.US, " Transparency barely changed (%.3f mag), a calm sky.", photometry.zeroPointRangeMag)
                    : String.format(Locale.US, " Transparency changed by %.2f mag over the night.", photometry.zeroPointRangeMag));
        }
        return text.toString();
    }

    private static String variableStory(PipelineTelemetry telemetry, VariableStarAnalysis variables) {
        PipelineTelemetry.PhotometryTelemetry photometry = telemetry.photometryTelemetry;
        if (photometry == null || variables == null) {
            return null;
        }
        if ("NOT_READY".equals(photometry.verdict)) {
            return "I measured " + String.format(Locale.US, "%,d", photometry.starsSelected) + " stars, but the session did not pass the readiness checks, so I did not trust their "
                    + "brightness enough to look for variables. The variable-star section explains why, and what could help.";
        }
        List<StarLightCurve> high = new ArrayList<>();
        for (StarLightCurve star : variables.candidates) {
            if (star.tier == VariabilityTier.HIGH_CONFIDENCE) {
                high.add(star);
            }
        }
        if (high.isEmpty()) {
            return String.format(Locale.US, "I followed %,d stars through the night, and none of them changed by more than its own noise allows. A quiet sky, as far as brightness goes.",
                    photometry.starsScored);
        }
        StringBuilder text = new StringBuilder(String.format(Locale.US, "I followed %,d stars through the night, and %s refused to stay constant",
                photometry.starsScored, countWord(high.size(), "star")));
        text.append(high.size() <= 3 ? ": " : ". The clearest: ");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(3, high.size()); i++) {
            StarLightCurve star = high.get(i);
            String shape = PhotometryReportSectionWriter.describeChange(star).toLowerCase(Locale.ROOT);
            String span = Double.isNaN(star.timeSpanMinutes) ? "" : String.format(Locale.US, " over %.0f minutes", star.timeSpanMinutes);
            parts.add(String.format(Locale.US, "V%d %s by %.2f mag%s", i + 1, shape, star.amplitude, span));
        }
        text.append(joinAnd(parts)).append('.');
        if (photometry.possible > 0) {
            text.append(' ').append(capitalise(countWord(photometry.possible, "more star"))).append(photometry.possible == 1 ? " comes" : " come")
                    .append(" close, failing a single check.");
        }
        text.append(" Everything else stayed within its own noise.");
        return text.toString();
    }

    // =================================================================
    // Helpers
    // =================================================================

    private static String fieldDescription(DetectionReportContext context) {
        if (!context.astrometryContext.hasAstrometricSolution() || context.fitsFiles[0] == null) {
            return null;
        }
        WcsCoordinateTransformer transformer = context.astrometryContext.getTransformer();
        WcsCoordinateTransformer.SkyCoordinate centre = transformer.pixelToSky(context.fitsFiles[0].getSizeWidth() / 2.0,
                context.fitsFiles[0].getSizeHeight() / 2.0);
        double ra = centre.getRaDegrees() / 15.0;
        int hours = (int) Math.floor(ra);
        int minutes = (int) Math.floor((ra - hours) * 60);
        return String.format(Locale.US, "RA %dh%02dm, Dec %+.0f°", hours, minutes, centre.getDecDegrees());
    }

    private static String plainRejection(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (r.contains("star count")) return "because the star count dropped (often thin cloud or haze)";
        if (r.contains("eccentricity") || r.contains("elongat")) return "because the stars turned elongated (tracking or wind)";
        if (r.contains("fwhm")) return "because the stars were blurred (poor seeing or focus)";
        if (r.contains("background")) return "because the sky background jumped";
        return "for other quality reasons";
    }

    private static String describeDuration(long minutes) {
        if (minutes < 60) {
            return minutes + " minutes";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return hours + (hours == 1 ? " hour" : " hours") + (rest > 0 ? " " + rest + " minutes" : "");
    }

    private static String countWord(int count, String noun) {
        String[] words = {"no", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"};
        String number = count >= 0 && count < words.length ? words[count] : String.format(Locale.US, "%,d", count);
        boolean sibilant = noun.endsWith("s") || noun.endsWith("sh") || noun.endsWith("ch") || noun.endsWith("x");
        return number + " " + noun + (count == 1 ? "" : sibilant ? "es" : "s");
    }

    private static String joinAnd(List<String> parts) {
        if (parts.size() <= 1) {
            return parts.isEmpty() ? "" : parts.get(0);
        }
        return String.join("; ", parts.subList(0, parts.size() - 1)) + "; and " + parts.get(parts.size() - 1);
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static double min(List<Double> values) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : values) m = Math.min(m, v);
        return m;
    }

    private static double max(List<Double> values) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : values) m = Math.max(m, v);
        return m;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return sorted.get(sorted.size() / 2);
    }

    private static String format(double value, int digits) {
        return String.format(Locale.US, "%." + digits + "f", value);
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "no reason recorded" : value;
    }

    private static String escape(String value) {
        return DetectionReportGenerator.escapeHtml(value);
    }
}
