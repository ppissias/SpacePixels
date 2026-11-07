package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.DisplayImageRenderer;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.core.CameraFixedRejector;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class UnclassifiedTransientSectionWriter {

    private static final String BACKGROUND_FILE = ReportFiles.link(ReportFiles.FIELD, "unclassified_median_background.png");
    private static final String COMPOSITE_FILE = ReportFiles.link(ReportFiles.FIELD, "unclassified_transients.png");
    private static final int INSPECTION_SIZE = 80;
    /** Folder of the per-detection frame strips used by the cutout animation. */
    private static final String FRAMES_DIR = "unclassified_frames";
    /** Frames shown before and after the detection frame in the cutout animation. */
    private static final int ANIMATION_FRAMES_EACH_SIDE = 2;

    private UnclassifiedTransientSectionWriter() {
    }

    static List<List<DetectedObject>> selectRemaining(
            List<List<DetectedObject>> unclassifiedTransients,
            List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates) {
        if (unclassifiedTransients == null) {
            return Collections.emptyList();
        }

        Set<DetectedObject> rescued = Collections.newSetFromMap(new IdentityHashMap<>());
        if (localRescueCandidates != null) {
            for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
                if (candidate != null && candidate.points != null) {
                    rescued.addAll(candidate.points);
                }
            }
        }

        Set<DetectedObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<List<DetectedObject>> remaining = new ArrayList<>(unclassifiedTransients.size());
        for (List<DetectedObject> frame : unclassifiedTransients) {
            List<DetectedObject> kept = new ArrayList<>();
            if (frame != null) {
                for (DetectedObject detection : frame) {
                    if (detection != null && !rescued.contains(detection) && seen.add(detection)) {
                        kept.add(detection);
                    }
                }
            }
            remaining.add(kept);
        }
        return remaining;
    }

    /** Sequence indices of the frames that quality control rejected; empty when unknown. */
    static Set<Integer> rejectedFrameIndices(PipelineTelemetry telemetry) {
        Set<Integer> rejected = new HashSet<>();
        if (telemetry != null && telemetry.rejectedFrames != null) {
            for (PipelineTelemetry.FrameRejectionStat stat : telemetry.rejectedFrames) {
                rejected.add(stat.frameIndex);
            }
        }
        return rejected;
    }

    static void writeSection(PrintWriter report,
                             DetectionReportContext context,
                             List<List<DetectedObject>> allTransients,
                             List<List<DetectedObject>> unclassifiedTransients) throws IOException {
        writeSection(report, context, allTransients, unclassifiedTransients, Collections.emptySet(),
                new ResidualTransientAnalysis(context.localRescueCandidates, context.localActivityClusters),
                context.cameraFixedGroups, ReportInclusion.all());
    }

    /**
     * Writes the inspector. {@code rejectedFrames} are left out of the cutout animations, as they were left out of
     * the detection.
     *
     * <p>{@code residual} and {@code cameraFixedGroups} are the engine's full results. Their detections stay off the
     * map whether or not this report shows their groups; groups that {@code inclusion} leaves out are only counted.</p>
     */
    static void writeSection(PrintWriter report,
                             DetectionReportContext context,
                             List<List<DetectedObject>> allTransients,
                             List<List<DetectedObject>> unclassifiedTransients,
                             Set<Integer> rejectedFrames,
                             ResidualTransientAnalysis residual,
                             List<CameraFixedRejector.Group> cameraFixedGroups,
                             ReportInclusion inclusion) throws IOException {
        List<List<DetectedObject>> remaining = selectRemaining(
                unclassifiedTransients, residual.localRescueCandidates);
        // Camera-fixed detections left before tracking, so they are not in allTransients.
        int cameraFixedCount = countCameraFixed(cameraFixedGroups);
        int totalCount = countDistinct(allTransients) + cameraFixedCount;
        int beforeRescueCount = countDistinct(unclassifiedTransients);
        int remainingCount = countDistinct(remaining);
        int commonMotionCount = countRemovedCommonMotion(unclassifiedTransients, residual.commonMotionGroups);
        int rescueCount = Math.max(0, beforeRescueCount - remainingCount);
        int tracksAndAnomalies = Math.max(0, totalCount - cameraFixedCount - beforeRescueCount - commonMotionCount);
        boolean groupsLeftOut = (!inclusion.commonMotion && commonMotionCount > 0)
                || (!inclusion.cameraFixed && cameraFixedCount > 0)
                || (!inclusion.localRescue && rescueCount > 0);

        report.println("<div class='panel' id='unclassified-transient-inspector' data-frame-delay='" + Math.max(100, context.settings.getGifBlinkSpeedMs()) + "'>");
        report.println("<h2>Unclassified Transient Inspector</h2>");
        report.println("<p class='astro-note'>Detections left after accepted tracks, streak parts, standalone anomalies, common-motion groups, camera-fixed groups (hot pixels, dust) and local rescue candidates."
                + (groupsLeftOut ? " Groups left out of this report are counted here but not shown." : "")
                + " Local activity clusters remain here because they group detections for review without classifying them. The background uses the median stack when available; colors run from blue for early frames to red for late frames.</p>");
        report.println("<div class='map-legend'>");
        report.println(metric("Post-veto detections", totalCount));
        report.println(metric("Tracks, streaks and anomalies", tracksAndAnomalies));
        if (commonMotionCount > 0) {
            report.println(metric(groupLabel("Common motion", inclusion.commonMotion), commonMotionCount));
        }
        if (cameraFixedCount > 0) {
            report.println(metric(groupLabel("Camera-fixed", inclusion.cameraFixed), cameraFixedCount));
        }
        report.println(metric(groupLabel("Local rescue points", inclusion.localRescue), rescueCount));
        report.println(metric("Still unclassified", remainingCount));
        report.println("</div>");

        boolean medianAvailable = hasPixels(context.masterStackData);
        short[][] backgroundData = context.masterStackData;
        if (!medianAvailable && !context.rawFrames.isEmpty()) {
            backgroundData = context.rawFrames.get(0);
        }
        if (!hasPixels(backgroundData)) {
            report.println("<p class='astro-note'>No image background is available for the unclassified transient map.</p></div>");
            return;
        }

        BufferedImage background = createMutedBackground(backgroundData, context);
        int width = background.getWidth();
        int height = background.getHeight();
        int markerRadius = Math.max(7, Math.min(28, Math.round(width / 160.0f)));
        List<Marker> markers = collectMarkers(remaining, width, height);
        TrackVisualizationRenderer.saveLosslessPng(background, context.file(BACKGROUND_FILE));
        TrackVisualizationRenderer.saveLosslessPng(createComposite(background, markers, markerRadius),
                context.file(COMPOSITE_FILE));
        int animatedCount = writeFrameStrips(context, markers, width, height, rejectedFrames);
        java.util.TreeSet<Integer> frameNumbers = new java.util.TreeSet<>();
        for (Marker marker : markers) frameNumbers.add(marker.sourceFrame() + 1);
        report.println("<div class='report-filter'><label>Minimum peak sigma <input id='unclassified-min-sigma' type='number' min='0' step='1' value='0' style='width:70px'></label>"
                + "<label>Frame <select id='unclassified-frame-filter'><option value='0'>All frames</option>");
        for (int frame : frameNumbers) report.println("<option value='" + frame + "'>" + frame + "</option>");
        report.println("</select></label><span id='unclassified-visible-count'></span><span class='astro-note'>Focus a marker and use ← / → to review visible detections.</span></div>");

        report.println("<div class='unclassified-layout'>");
        report.println("<div class='unclassified-map-column'>");
        report.println("<div class='unclassified-map' id='unclassified-map' style='width:" + width + "px'>");
        report.println("<img loading='lazy' decoding='async' id='unclassified-background' src='" + BACKGROUND_FILE + "' width='" + width
                + "' height='" + height + "' alt='Subdued " + (medianAvailable ? context.fieldName() : "first frame") + "' />");
        report.println("<svg viewBox='0 0 " + width + " " + height
                + "' preserveAspectRatio='none' role='img' aria-label='Time-colored unclassified transient markers'>");
        for (Marker marker : markers) {
            report.println(markerHtml(marker, markerRadius, width, height));
        }
        report.println("</svg><div class='unclassified-tooltip' id='unclassified-tooltip'></div></div>");
        report.println("<div class='unclassified-time-legend'></div><div class='astro-note' style='display:flex;justify-content:space-between;max-width:360px;margin-top:0'><span>Early frames</span><span>Late frames</span></div>");
        report.println("<p class='astro-note'>" + markers.size() + " of " + remainingCount
                + " remaining detections have coordinates inside the image. <a href='" + COMPOSITE_FILE
                + "' target='_blank'>Open static composite PNG</a>.</p>");
        report.println("</div>");
        report.println("<aside class='unclassified-details' id='unclassified-details'><h3>Selected transient</h3>");
        report.println("<canvas id='unclassified-cutout' width='320' height='320' aria-label='80 by 80 pixel local inspection cutout'></canvas>");
        report.println("<div class='unclassified-cutout-controls' id='unclassified-cutout-controls' hidden>"
                + "<button type='button' id='unclassified-play'>Pause</button>"
                + "<button type='button' id='unclassified-view'>Show " + (medianAvailable ? "median stack" : "first frame") + "</button></div>");
        report.println("<p class='astro-note' id='unclassified-cutout-note' data-static='" + INSPECTION_SIZE + " × " + INSPECTION_SIZE + " pixel "
                + (medianAvailable ? "median-stack" : "first-frame")
                + " cutout. The marker identifies the detected position.'></p>");
        if (animatedCount > 0 && animatedCount < markers.size()) {
            report.println("<p class='astro-note'>Frame animations are included for the " + animatedCount
                    + " strongest detections (by peak sigma); the others show the static cutout.</p>");
        }
        report.println("<dl id='unclassified-metadata'><dt>Status</dt><dd>UNCLASSIFIED</dd><dt>Selection</dt><dd>Select a marker to inspect it.</dd></dl></aside>");
        report.println("</div>");
        if (markers.isEmpty()) {
            report.println("<p class='astro-note'>No remaining unclassified detections are available to inspect.</p>");
        }
        report.println("</div>");
    }

    private static boolean hasPixels(short[][] imageData) {
        return imageData != null && imageData.length > 0 && imageData[0] != null && imageData[0].length > 0;
    }

    /** Common-motion points that are no longer in the unclassified list, so they are not on this map. */
    private static int countRemovedCommonMotion(List<List<DetectedObject>> unclassifiedTransients,
                                                List<ResidualTransientAnalysis.CommonMotionGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return 0;
        }
        Set<DetectedObject> stillThere = Collections.newSetFromMap(new IdentityHashMap<>());
        if (unclassifiedTransients != null) {
            for (List<DetectedObject> frame : unclassifiedTransients) {
                if (frame != null) {
                    for (DetectedObject detection : frame) {
                        if (detection != null) {
                            stillThere.add(detection);
                        }
                    }
                }
            }
        }
        Set<DetectedObject> counted = Collections.newSetFromMap(new IdentityHashMap<>());
        int removed = 0;
        for (ResidualTransientAnalysis.CommonMotionGroup group : groups) {
            if (group == null || group.sources == null) {
                continue;
            }
            for (List<DetectedObject> source : group.sources) {
                if (source == null) {
                    continue;
                }
                for (DetectedObject detection : source) {
                    if (detection != null && !stillThere.contains(detection) && counted.add(detection)) {
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private static int countCameraFixed(List<CameraFixedRejector.Group> groups) {
        if (groups == null) {
            return 0;
        }
        Set<DetectedObject> points = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CameraFixedRejector.Group group : groups) {
            if (group != null && group.points != null) {
                points.addAll(group.points);
            }
        }
        return points.size();
    }

    private static String groupLabel(String label, boolean inReport) {
        return inReport ? label : label + ", not in this report";
    }

    private static String metric(String label, int count) {
        return "<div class='legend-pill'><strong>" + count + "</strong> " + label + "</div>";
    }

    private static int countDistinct(List<List<DetectedObject>> frames) {
        if (frames == null) {
            return 0;
        }
        Set<DetectedObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<DetectedObject> frame : frames) {
            if (frame != null) {
                for (DetectedObject detection : frame) {
                    if (detection != null) {
                        seen.add(detection);
                    }
                }
            }
        }
        return seen.size();
    }

    private static BufferedImage createMutedBackground(short[][] imageData, DetectionReportContext context) {
        BufferedImage gray = context.fieldDisplayImage(imageData);
        BufferedImage muted = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int row = 0; row < gray.getHeight(); row++) {
            for (int column = 0; column < gray.getWidth(); column++) {
                int value = gray.getRGB(column, row) & 0xff;
                int red = 11 + Math.round(value * 0.39f);
                int green = 11 + Math.round(value * 0.34f);
                int blue = 23 + Math.round(value * 0.47f);
                muted.setRGB(column, row, new Color(red, green, blue).getRGB());
            }
        }
        return muted;
    }

    private static List<Marker> collectMarkers(List<List<DetectedObject>> remaining,
                                               int width,
                                               int height) {
        List<Marker> markers = new ArrayList<>();
        int frameCount = remaining.size();
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            float ratio = frameCount > 1 ? (float) frameIndex / (frameCount - 1) : 0f;
            Color color = Color.getHSBColor(0.66f - 0.66f * ratio, 1f, 1f);
            for (DetectedObject detection : remaining.get(frameIndex)) {
                if (Double.isFinite(detection.x) && Double.isFinite(detection.y)
                        && detection.x >= 0 && detection.x < width && detection.y >= 0 && detection.y < height) {
                    markers.add(new Marker(markers.size() + 1, frameIndex, detection, color));
                }
            }
        }
        return markers;
    }

    /**
     * Writes, for the strongest detections, a vertical strip of the inspection cutout in the detection frame and up
     * to {@link #ANIMATION_FRAMES_EACH_SIDE} frames on each side. All frames of a strip share one stretch, so a
     * transient visibly appears and disappears. The report loads a strip only when its marker is selected.
     *
     * @return the number of detections that received a strip
     */
    private static int writeFrameStrips(DetectionReportContext context, List<Marker> markers, int width, int height,
                                        Set<Integer> rejectedFrames)
            throws IOException {
        int maxAnimatedMarkers = context.settings.getMaxAnimatedMarkers();
        if (context.rawFrames.isEmpty() || markers.isEmpty() || maxAnimatedMarkers <= 0) {
            return 0;
        }
        List<Marker> animated = new ArrayList<>(markers);
        if (animated.size() > maxAnimatedMarkers) {
            animated.sort(Comparator.comparingDouble((Marker m) -> Double.isFinite(m.detection.peakSigma) ? -m.detection.peakSigma : 0));
            animated = animated.subList(0, maxAnimatedMarkers);
        }
        File framesDir = new File(context.exportDir, FRAMES_DIR);
        if (!framesDir.isDirectory() && !framesDir.mkdirs()) {
            return 0;
        }
        int frameCount = context.rawFrames.size();
        int cropWidth = Math.min(INSPECTION_SIZE, width);
        int cropHeight = Math.min(INSPECTION_SIZE, height);
        int written = 0;
        context.progress.expect(animated.size());
        for (Marker marker : animated) {
            context.progress.next();
            int frame =marker.detection.sourceFrameIndex >= 0 && marker.detection.sourceFrameIndex < frameCount
                    ? marker.detection.sourceFrameIndex : marker.frameIndex;
            if (frame < 0 || frame >= frameCount) {
                continue;
            }
            List<Integer> frames = animationFrames(frame, frameCount, rejectedFrames);
            int detectionPosition = frames.indexOf(frame);
            // Same placement as the report's static cutout: centred on the detection, kept inside the image.
            int left = Math.max(0, Math.min(width - cropWidth, (int) Math.round(marker.detection.x - cropWidth / 2.0)));
            int top = Math.max(0, Math.min(height - cropHeight, (int) Math.round(marker.detection.y - cropHeight / 2.0)));

            short[][] strip = new short[frames.size() * cropHeight][];
            boolean usable = true;
            for (int position = 0; position < frames.size(); position++) {
                short[][] raw = context.rawFrames.get(frames.get(position));
                if (raw == null || raw.length < top + cropHeight || raw[0].length < left + cropWidth) {
                    usable = false;
                    break;
                }
                for (int row = 0; row < cropHeight; row++) {
                    strip[position * cropHeight + row] = Arrays.copyOfRange(raw[top + row], left, left + cropWidth);
                }
            }
            if (!usable) {
                continue;
            }
            String fileName = ReportFiles.link(FRAMES_DIR, "U" + marker.id + ".png");
            TrackVisualizationRenderer.saveLosslessPng(
                    stretchStrip(strip, detectionPosition * cropHeight, cropHeight,
                            context.settings.getAutoStretchBlackSigma(), context.settings.getAutoStretchWhiteSigma()),
                    context.file(fileName));
            marker.stripFile = fileName;
            marker.stripFrames = frames;
            marker.stripDetectionPosition = detectionPosition;
            marker.stripLeft = left;
            marker.stripTop = top;
            written++;
        }
        return written;
    }

    /**
     * The frames of a cutout animation: the detection frame and the nearest {@link #ANIMATION_FRAMES_EACH_SIDE}
     * frames on each side that quality control kept. Rejected frames (blank frames, failed registrations, outliers)
     * are skipped, as the detection skipped them.
     */
    static List<Integer> animationFrames(int detectionFrame, int frameCount, Set<Integer> rejectedFrames) {
        List<Integer> before = new ArrayList<>();
        for (int f = detectionFrame - 1; f >= 0 && before.size() < ANIMATION_FRAMES_EACH_SIDE; f--) {
            if (!rejectedFrames.contains(f)) {
                before.add(0, f);
            }
        }
        List<Integer> frames = new ArrayList<>(before);
        frames.add(detectionFrame);
        for (int f = detectionFrame + 1; f < frameCount && frames.size() < before.size() + 1 + ANIMATION_FRAMES_EACH_SIDE; f++) {
            if (!rejectedFrames.contains(f)) {
                frames.add(f);
            }
        }
        return frames;
    }

    /**
     * Stretches all frames of a strip with the black and white points of the detection frame (sky median − black σ
     * to sky median + white σ, square-root curve, as the other report images), so a blank neighbouring frame cannot
     * flatten the contrast and brightness changes between frames stay visible.
     */
    static BufferedImage stretchStrip(short[][] strip, int detectionRow, int frameHeight, double blackSigma, double whiteSigma) {
        short[][] detectionFrame = Arrays.copyOfRange(strip, detectionRow, detectionRow + frameHeight);
        double[] levels = DisplayImageRenderer.displayLevels(detectionFrame, blackSigma, whiteSigma);
        return DisplayImageRenderer.createDisplayImageAtLevels(strip, levels[0], levels[1]);
    }

    private static BufferedImage createComposite(BufferedImage background, List<Marker> markers, int radius) {
        BufferedImage composite = new BufferedImage(background.getWidth(), background.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = composite.createGraphics();
        graphics.drawImage(background, 0, 0, null);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setStroke(new BasicStroke(3f));
        for (Marker marker : markers) {
            int centerX = (int) Math.round(marker.detection.x);
            int centerY = (int) Math.round(marker.detection.y);
            graphics.setColor(marker.color);
            graphics.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
        }
        for (Marker marker : markers) {
            graphics.setColor(marker.color);
            if (marker.detection.rawPixels != null) {
                for (Pixel pixel : marker.detection.rawPixels) {
                    if (pixel != null && pixel.x >= 0 && pixel.x < composite.getWidth()
                            && pixel.y >= 0 && pixel.y < composite.getHeight()) {
                        graphics.fillRect(pixel.x, pixel.y, 1, 1);
                    }
                }
            }
        }
        graphics.dispose();
        return composite;
    }

    private static String markerHtml(Marker marker, int radius, int width, int height) {
        DetectedObject detection = marker.detection;
        String color = String.format("#%02x%02x%02x", marker.color.getRed(), marker.color.getGreen(), marker.color.getBlue());
        StringBuilder html = new StringBuilder();
        html.append("<g class='unclassified-source'>");
        String path = footprintPath(detection, width, height);
        if (!path.isEmpty()) {
            html.append("<path class='unclassified-footprint' fill='").append(color)
                    .append("' d='").append(path).append("' />");
        }
        html.append("<circle class='unclassified-marker' tabindex='0' role='button' cx='")
                .append(format(detection.x)).append("' cy='").append(format(detection.y))
                .append("' r='").append(radius).append("' stroke='").append(color)
                .append("' data-id='U").append(marker.id)
                .append("' data-frame='").append(marker.sourceFrame() + 1)
                .append("' data-index='").append(marker.sourceFrame())
                .append("' data-time='").append(escape(ReportFormat.formatUtcTimestamp(detection.timestamp)))
                .append("' data-filename='").append(escape(detection.sourceFilename == null ? "Unknown" : detection.sourceFilename))
                .append("' data-x='").append(format(detection.x))
                .append("' data-y='").append(format(detection.y))
                .append("' data-peak='").append(format(detection.peakSigma))
                .append("' data-integrated='").append(format(detection.integratedSigma))
                .append("' data-flux='").append(format(detection.totalFlux))
                .append("' data-pixels='").append(footprintPixelCount(detection))
                .append("' data-elongation='").append(format(detection.elongation))
                .append("' data-fwhm='").append(format(detection.fwhm));
        if (marker.stripFile != null) {
            html.append("' data-strip='").append(marker.stripFile)
                    .append("' data-strip-frames='").append(oneBasedList(marker.stripFrames))
                    .append("' data-strip-count='").append(marker.stripFrames.size())
                    .append("' data-strip-detection='").append(marker.stripDetectionPosition)
                    .append("' data-strip-left='").append(marker.stripLeft)
                    .append("' data-strip-top='").append(marker.stripTop);
        }
        html.append("' aria-label='Unclassified transient U").append(marker.id)
                .append(" in frame ").append(marker.sourceFrame() + 1).append("' /></g>");
        return html.toString();
    }

    private static String footprintPath(DetectedObject detection, int width, int height) {
        if (detection.rawPixels == null || detection.rawPixels.isEmpty()) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (Pixel pixel : detection.rawPixels) {
            if (pixel != null && pixel.x >= 0 && pixel.x < width && pixel.y >= 0 && pixel.y < height) {
                path.append('M').append(pixel.x).append(' ').append(pixel.y).append("h1v1h-1z");
            }
        }
        return path.toString();
    }

    private static int footprintPixelCount(DetectedObject detection) {
        if (detection.rawPixels != null && !detection.rawPixels.isEmpty()) {
            return detection.rawPixels.size();
        }
        if (Double.isFinite(detection.pixelArea) && detection.pixelArea > 0) {
            return (int) Math.round(detection.pixelArea);
        }
        return Math.max(0, detection.pixelCount);
    }

    private static String oneBasedList(List<Integer> frameIndices) {
        StringBuilder text = new StringBuilder();
        for (int index : frameIndices) {
            text.append(text.length() == 0 ? "" : ",").append(index + 1);
        }
        return text.toString();
    }

    private static String format(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "Unavailable";
    }

    private static String escape(String value) {
        return ReportFormat.escapeHtml(value).replace("'", "&#39;");
    }

    private static final class Marker {
        final int id;
        final int frameIndex;
        final DetectedObject detection;
        final Color color;
        /** Frame strip of the cutout animation, or null when the detection has none. */
        String stripFile;
        List<Integer> stripFrames;
        int stripDetectionPosition;
        int stripLeft;
        int stripTop;

        /**
         * Index of the detection's frame in the imported sequence. {@link #frameIndex} is its position in the list
         * of frames that passed quality control, which differs once frames were rejected.
         */
        int sourceFrame() {
            return detection.sourceFrameIndex >= 0 ? detection.sourceFrameIndex : frameIndex;
        }

        Marker(int id, int frameIndex, DetectedObject detection, Color color) {
            this.id = id;
            this.frameIndex = frameIndex;
            this.detection = detection;
            this.color = color;
        }
    }
}
