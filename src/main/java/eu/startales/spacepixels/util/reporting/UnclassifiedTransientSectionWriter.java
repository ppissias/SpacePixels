package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.core.SourceExtractor;
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

    private static final String BACKGROUND_FILE = "unclassified_median_background.png";
    private static final String COMPOSITE_FILE = "unclassified_transients.png";
    private static final int INSPECTION_SIZE = 80;
    /** Folder of the per-detection frame strips used by the cutout animation. */
    private static final String FRAMES_DIR = "unclassified_frames";
    /** Frames shown before and after the detection frame in the cutout animation. */
    private static final int ANIMATION_FRAMES_EACH_SIDE = 2;
    /** Upper bound of animated detections, so very busy sessions keep a moderate report size. */
    static int maxAnimatedMarkers = 500;

    private UnclassifiedTransientSectionWriter() {
    }

    static List<List<SourceExtractor.DetectedObject>> selectRemaining(
            List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
            List<ResidualTransientAnalysis.LocalRescueCandidate> localRescueCandidates) {
        if (unclassifiedTransients == null) {
            return Collections.emptyList();
        }

        Set<SourceExtractor.DetectedObject> rescued = Collections.newSetFromMap(new IdentityHashMap<>());
        if (localRescueCandidates != null) {
            for (ResidualTransientAnalysis.LocalRescueCandidate candidate : localRescueCandidates) {
                if (candidate != null && candidate.points != null) {
                    rescued.addAll(candidate.points);
                }
            }
        }

        Set<SourceExtractor.DetectedObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<List<SourceExtractor.DetectedObject>> remaining = new ArrayList<>(unclassifiedTransients.size());
        for (List<SourceExtractor.DetectedObject> frame : unclassifiedTransients) {
            List<SourceExtractor.DetectedObject> kept = new ArrayList<>();
            if (frame != null) {
                for (SourceExtractor.DetectedObject detection : frame) {
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
                             List<List<SourceExtractor.DetectedObject>> allTransients,
                             List<List<SourceExtractor.DetectedObject>> unclassifiedTransients) throws IOException {
        writeSection(report, context, allTransients, unclassifiedTransients, Collections.emptySet());
    }

    /**
     * Writes the inspector. {@code rejectedFrames} are left out of the cutout animations, as they were left out of
     * the detection.
     */
    static void writeSection(PrintWriter report,
                             DetectionReportContext context,
                             List<List<SourceExtractor.DetectedObject>> allTransients,
                             List<List<SourceExtractor.DetectedObject>> unclassifiedTransients,
                             Set<Integer> rejectedFrames) throws IOException {
        List<List<SourceExtractor.DetectedObject>> remaining = selectRemaining(
                unclassifiedTransients, context.localRescueCandidates);
        int totalCount = countDistinct(allTransients);
        int beforeRescueCount = countDistinct(unclassifiedTransients);
        int remainingCount = countDistinct(remaining);
        int classifiedCount = Math.max(0, totalCount - beforeRescueCount);
        int rescueCount = Math.max(0, beforeRescueCount - remainingCount);

        report.println("<div class='panel' id='unclassified-transient-inspector'>");
        report.println("<h2>Unclassified Transient Inspector</h2>");
        report.println("<p class='astro-note'>Detections left after accepted tracks, standalone anomalies, and local rescue candidates. Local activity clusters remain here because they group detections for review without classifying them. The background uses the median stack when available; colors run from blue for early frames to red for late frames.</p>");
        report.println("<div class='map-legend'>");
        report.println(metric("Post-veto detections", totalCount));
        report.println(metric("Tracks and anomalies", classifiedCount));
        report.println(metric("Local rescue points", rescueCount));
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
        TrackVisualizationRenderer.saveLosslessPng(background, new File(context.exportDir, BACKGROUND_FILE));
        TrackVisualizationRenderer.saveLosslessPng(createComposite(background, markers, markerRadius),
                new File(context.exportDir, COMPOSITE_FILE));
        int animatedCount = writeFrameStrips(context, markers, width, height, rejectedFrames);

        report.println("<style>");
        report.println("#unclassified-transient-inspector .unclassified-layout{display:flex;gap:18px;align-items:flex-start;flex-wrap:wrap}");
        report.println("#unclassified-transient-inspector .unclassified-map-column{flex:3 1 560px;min-width:0}");
        report.println("#unclassified-transient-inspector .unclassified-map{position:relative;display:block;width:" + width + "px;max-width:100%;line-height:0;border:1px solid #68607b;border-radius:5px;overflow:hidden}");
        report.println("#unclassified-transient-inspector .unclassified-map img{display:block;width:100%;height:auto;max-width:none;border:0;border-radius:0}");
        report.println("#unclassified-transient-inspector .unclassified-map svg{position:absolute;inset:0;width:100%;height:100%}");
        report.println("#unclassified-transient-inspector .unclassified-footprint{fill-opacity:.85;pointer-events:none}");
        report.println("#unclassified-transient-inspector .unclassified-source:hover .unclassified-footprint,#unclassified-transient-inspector .unclassified-source.selected .unclassified-footprint{fill-opacity:1}");
        report.println("#unclassified-transient-inspector .unclassified-marker{fill:transparent;stroke-width:2.5;cursor:pointer;pointer-events:all;transition:stroke-width .12s}");
        report.println("#unclassified-transient-inspector .unclassified-marker:hover,#unclassified-transient-inspector .unclassified-marker:focus,#unclassified-transient-inspector .unclassified-marker.selected{stroke-width:5;outline:none}");
        report.println("#unclassified-transient-inspector .unclassified-tooltip{position:absolute;z-index:2;display:none;max-width:230px;padding:8px 10px;background:rgba(12,16,28,.96);border:1px solid #8ea4c8;border-radius:5px;color:#fff;font:12px/1.45 sans-serif;white-space:pre-line;pointer-events:none;box-shadow:0 4px 14px #0009}");
        report.println("#unclassified-transient-inspector .unclassified-details{flex:1 1 240px;max-width:340px;background:#292c37;border:1px solid #565b71;border-radius:6px;padding:14px}");
        report.println("#unclassified-transient-inspector .unclassified-details h3{margin:0 0 10px;color:#fff}");
        report.println("#unclassified-transient-inspector .unclassified-details canvas{display:block;width:100%;max-width:320px;height:auto;background:#14121f;border:1px solid #555}");
        report.println("#unclassified-transient-inspector .unclassified-details dl{display:grid;grid-template-columns:auto 1fr;gap:4px 10px;font-size:12px;line-height:1.35}");
        report.println("#unclassified-transient-inspector .unclassified-details dt{color:#9da9c5}");
        report.println("#unclassified-transient-inspector .unclassified-details dd{margin:0;color:#eee;overflow-wrap:anywhere}");
        report.println("#unclassified-transient-inspector .unclassified-cutout-controls{display:flex;gap:8px;margin:8px 0 0}");
        report.println("#unclassified-transient-inspector .unclassified-cutout-controls[hidden]{display:none}");
        report.println("#unclassified-transient-inspector .unclassified-cutout-controls button{background:#3a3f52;color:#eee;border:1px solid #6a7290;border-radius:4px;padding:3px 10px;font-size:12px;cursor:pointer}");
        report.println("#unclassified-transient-inspector .unclassified-cutout-controls button:hover{background:#4a5170}");
        report.println("#unclassified-transient-inspector .unclassified-time-legend{height:10px;max-width:360px;margin:12px 0 3px;background:linear-gradient(to right,#1500ff,#00ff9a,#ffee00,#ff0000);border-radius:3px}");
        report.println("</style>");
        report.println("<div class='unclassified-layout'>");
        report.println("<div class='unclassified-map-column'>");
        report.println("<div class='unclassified-map' id='unclassified-map'>");
        report.println("<img id='unclassified-background' src='" + BACKGROUND_FILE + "' width='" + width
                + "' height='" + height + "' alt='Subdued " + (medianAvailable ? "median stack" : "first frame") + "' />");
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
        appendInteractionScript(report, context.settings.getGifBlinkSpeedMs());
    }

    private static boolean hasPixels(short[][] imageData) {
        return imageData != null && imageData.length > 0 && imageData[0] != null && imageData[0].length > 0;
    }

    private static String metric(String label, int count) {
        return "<div class='legend-pill'><strong>" + count + "</strong> " + label + "</div>";
    }

    private static int countDistinct(List<List<SourceExtractor.DetectedObject>> frames) {
        if (frames == null) {
            return 0;
        }
        Set<SourceExtractor.DetectedObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (List<SourceExtractor.DetectedObject> frame : frames) {
            if (frame != null) {
                for (SourceExtractor.DetectedObject detection : frame) {
                    if (detection != null) {
                        seen.add(detection);
                    }
                }
            }
        }
        return seen.size();
    }

    private static BufferedImage createMutedBackground(short[][] imageData, DetectionReportContext context) {
        BufferedImage gray = TrackVisualizationRenderer.createDisplayImage(imageData, context.settings);
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

    private static List<Marker> collectMarkers(List<List<SourceExtractor.DetectedObject>> remaining,
                                               int width,
                                               int height) {
        List<Marker> markers = new ArrayList<>();
        int frameCount = remaining.size();
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            float ratio = frameCount > 1 ? (float) frameIndex / (frameCount - 1) : 0f;
            Color color = Color.getHSBColor(0.66f - 0.66f * ratio, 1f, 1f);
            for (SourceExtractor.DetectedObject detection : remaining.get(frameIndex)) {
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
        for (Marker marker : animated) {
            int frame = marker.detection.sourceFrameIndex >= 0 && marker.detection.sourceFrameIndex < frameCount
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
            String fileName = FRAMES_DIR + "/U" + marker.id + ".png";
            TrackVisualizationRenderer.saveLosslessPng(
                    stretchStrip(strip, detectionPosition * cropHeight, cropHeight,
                            context.settings.getAutoStretchBlackSigma(), context.settings.getAutoStretchWhiteSigma()),
                    new File(context.exportDir, fileName));
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
     * Stretches all frames of a strip with the black and white points of the detection frame (mean − black σ to
     * mean + white σ, square-root curve, as the other report images), so a blank neighbouring frame cannot flatten
     * the contrast and brightness changes between frames stay visible.
     */
    static BufferedImage stretchStrip(short[][] strip, int detectionRow, int frameHeight, double blackSigma, double whiteSigma) {
        int width = strip[0].length;
        double sum = 0;
        double sumSq = 0;
        int max = 0;
        int n = 0;
        for (int row = detectionRow; row < detectionRow + frameHeight; row++) {
            for (int column = 0; column < width; column++) {
                int value = strip[row][column] + 32768;
                sum += value;
                sumSq += (double) value * value;
                max = Math.max(max, value);
                n++;
            }
        }
        double mean = sum / n;
        double sigma = Math.sqrt(Math.max(0, sumSq / n - mean * mean));
        double black = Math.max(0, mean - blackSigma * sigma);
        double white = Math.min(max, mean + whiteSigma * sigma);
        double range = white - black > 0 ? white - black : 1.0;

        BufferedImage image = new BufferedImage(width, strip.length, BufferedImage.TYPE_BYTE_GRAY);
        java.awt.image.WritableRaster raster = image.getRaster();
        for (int row = 0; row < strip.length; row++) {
            for (int column = 0; column < width; column++) {
                double normalized = Math.max(0, Math.min(range, strip[row][column] + 32768 - black)) / range;
                raster.setSample(column, row, 0, (int) (Math.sqrt(normalized) * 255.0));
            }
        }
        return image;
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
                for (SourceExtractor.Pixel pixel : marker.detection.rawPixels) {
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
        SourceExtractor.DetectedObject detection = marker.detection;
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
                .append("' data-time='").append(escape(DetectionReportGenerator.formatUtcTimestamp(detection.timestamp)))
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

    private static String footprintPath(SourceExtractor.DetectedObject detection, int width, int height) {
        if (detection.rawPixels == null || detection.rawPixels.isEmpty()) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (SourceExtractor.Pixel pixel : detection.rawPixels) {
            if (pixel != null && pixel.x >= 0 && pixel.x < width && pixel.y >= 0 && pixel.y < height) {
                path.append('M').append(pixel.x).append(' ').append(pixel.y).append("h1v1h-1z");
            }
        }
        return path.toString();
    }

    private static int footprintPixelCount(SourceExtractor.DetectedObject detection) {
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
        return DetectionReportGenerator.escapeHtml(value).replace("'", "&#39;");
    }

    private static void appendInteractionScript(PrintWriter report, int frameDelayMs) {
        report.println("<script>(function(){");
        report.println("const panel=document.getElementById('unclassified-transient-inspector');");
        report.println("const map=panel.querySelector('#unclassified-map');");
        report.println("const image=panel.querySelector('#unclassified-background');");
        report.println("const tooltip=panel.querySelector('#unclassified-tooltip');");
        report.println("const metadata=panel.querySelector('#unclassified-metadata');");
        report.println("const canvas=panel.querySelector('#unclassified-cutout');");
        report.println("const markers=Array.from(panel.querySelectorAll('.unclassified-marker'));");
        report.println("let selected=null;");
        report.println("function setDetails(marker){");
        report.println("const data=marker.dataset;");
        report.println("const rows=[['Status','UNCLASSIFIED'],['Transient ID',data.id],['Frame',data.frame+' (index '+data.index+')'],['Timestamp',data.time],['Source file',data.filename],['X',data.x],['Y',data.y],['Peak sigma',data.peak],['Integrated sigma',data.integrated],['Total flux',data.flux],['Pixel count',data.pixels],['Elongation',data.elongation],['FWHM',data.fwhm]];");
        report.println("metadata.replaceChildren();");
        report.println("rows.forEach(([label,value])=>{const term=document.createElement('dt');const detail=document.createElement('dd');term.textContent=label;detail.textContent=value;metadata.append(term,detail);});");
        report.println("}");
        report.println("function drawCutout(marker){");
        report.println("if(!image.complete||!image.naturalWidth)return;");
        report.println("const context=canvas.getContext('2d');const size=80;");
        report.println("const sourceWidth=Math.min(size,image.naturalWidth);const sourceHeight=Math.min(size,image.naturalHeight);");
        report.println("const x=Number(marker.dataset.x);const y=Number(marker.dataset.y);");
        report.println("const left=Math.max(0,Math.min(image.naturalWidth-sourceWidth,Math.round(x-sourceWidth/2)));");
        report.println("const top=Math.max(0,Math.min(image.naturalHeight-sourceHeight,Math.round(y-sourceHeight/2)));");
        report.println("context.clearRect(0,0,canvas.width,canvas.height);context.imageSmoothingEnabled=false;");
        report.println("context.drawImage(image,left,top,sourceWidth,sourceHeight,0,0,canvas.width,canvas.height);");
        report.println("const footprint=marker.parentElement.querySelector('.unclassified-footprint');");
        report.println("if(footprint){context.save();context.setTransform(canvas.width/sourceWidth,0,0,canvas.height/sourceHeight,-left*canvas.width/sourceWidth,-top*canvas.height/sourceHeight);context.fillStyle=marker.getAttribute('stroke');context.globalAlpha=.85;context.fill(new Path2D(footprint.getAttribute('d')));context.restore();}");
        report.println("const markerX=(x-left)*canvas.width/sourceWidth;const markerY=(y-top)*canvas.height/sourceHeight;");
        report.println("context.strokeStyle='#ffffff';context.lineWidth=3;context.beginPath();context.arc(markerX,markerY,13,0,Math.PI*2);context.stroke();");
        report.println("context.strokeStyle=marker.getAttribute('stroke');context.lineWidth=2;context.beginPath();context.arc(markerX,markerY,17,0,Math.PI*2);context.stroke();");
        report.println("}");
        // Frame animation: the strip of the selected detection is loaded on demand and played in the cutout canvas.
        report.println("const controls=panel.querySelector('#unclassified-cutout-controls');const playButton=panel.querySelector('#unclassified-play');");
        report.println("const viewButton=panel.querySelector('#unclassified-view');const note=panel.querySelector('#unclassified-cutout-note');");
        report.println("const staticLabel=viewButton.textContent;const strips={};const delay=" + Math.max(100, frameDelayMs) + ";");
        report.println("let timer=null;let frame=0;let playing=true;let showStatic=false;");
        report.println("function stopTimer(){if(timer){clearInterval(timer);timer=null;}}");
        report.println("function drawStripFrame(marker){");
        report.println("const data=marker.dataset;const strip=strips[data.strip];if(!strip||!strip.complete||!strip.naturalWidth)return;");
        report.println("const count=Number(data.stripCount);const w=strip.naturalWidth;const h=strip.naturalHeight/count;const context=canvas.getContext('2d');");
        report.println("context.clearRect(0,0,canvas.width,canvas.height);context.imageSmoothingEnabled=false;");
        report.println("context.drawImage(strip,0,frame*h,w,h,0,0,canvas.width,canvas.height);");
        report.println("const markerX=(Number(data.x)-Number(data.stripLeft))*canvas.width/w;const markerY=(Number(data.y)-Number(data.stripTop))*canvas.height/h;");
        report.println("const detection=frame===Number(data.stripDetection);");
        report.println("context.strokeStyle=detection?marker.getAttribute('stroke'):'rgba(255,255,255,.55)';context.lineWidth=detection?3:1.5;context.beginPath();context.arc(markerX,markerY,17,0,Math.PI*2);context.stroke();");
        report.println("const frames=data.stripFrames.split(',').map(Number);const offset=frames[frame]-frames[Number(data.stripDetection)];const label='Frame '+frames[frame]+(offset===0?' · detection':' ('+(offset>0?'+':'')+offset+')');");
        report.println("context.font='bold 14px sans-serif';const textWidth=context.measureText(label).width;context.fillStyle='rgba(10,12,20,.75)';context.fillRect(6,6,textWidth+12,22);");
        report.println("context.fillStyle=detection?marker.getAttribute('stroke'):'#ffffff';context.fillText(label,12,22);");
        report.println("}");
        report.println("function startAnimation(marker){");
        report.println("const data=marker.dataset;let strip=strips[data.strip];");
        report.println("if(!strip){strip=new Image();strip.onload=()=>{if(selected===marker&&!showStatic)drawStripFrame(marker);};strip.src=data.strip;strips[data.strip]=strip;}");
        report.println("drawStripFrame(marker);");
        report.println("if(playing)timer=setInterval(()=>{frame=(frame+1)%Number(data.stripCount);drawStripFrame(marker);},delay);");
        report.println("}");
        report.println("function render(marker){");
        report.println("stopTimer();const data=marker.dataset;const animated=!!data.strip;controls.hidden=!animated;");
        report.println("if(animated&&!showStatic){startAnimation(marker);const frames=data.stripFrames.split(',');");
        report.println("note.textContent='80 × 80 pixel cutout from frames '+frames.join(', ')+', detection in frame '+frames[Number(data.stripDetection)]+'. Frames rejected by quality control are skipped; all frames share one stretch. Click the image to step when paused.';}");
        report.println("else{drawCutout(marker);note.textContent=note.dataset.static;}");
        report.println("playButton.textContent=playing?'Pause':'Play';playButton.disabled=showStatic;viewButton.textContent=showStatic?'Show frames':staticLabel;");
        report.println("}");
        report.println("playButton.addEventListener('click',()=>{playing=!playing;if(selected)render(selected);});");
        report.println("viewButton.addEventListener('click',()=>{showStatic=!showStatic;if(selected)render(selected);});");
        report.println("canvas.addEventListener('click',()=>{if(selected&&selected.dataset.strip&&!showStatic&&!playing){frame=(frame+1)%Number(selected.dataset.stripCount);drawStripFrame(selected);}});");
        report.println("function select(marker){if(selected){selected.classList.remove('selected');selected.parentElement.classList.remove('selected');}selected=marker;marker.classList.add('selected');marker.parentElement.classList.add('selected');setDetails(marker);frame=Number(marker.dataset.stripDetection||0);render(marker);}");
        report.println("function positionTooltip(event){const bounds=map.getBoundingClientRect();const left=Math.min(event.clientX-bounds.left+12,bounds.width-tooltip.offsetWidth-8);const top=Math.min(event.clientY-bounds.top+12,bounds.height-tooltip.offsetHeight-8);tooltip.style.left=Math.max(8,left)+'px';tooltip.style.top=Math.max(8,top)+'px';}");
        report.println("function showTooltip(marker,event){const data=marker.dataset;tooltip.textContent=data.id+' · Frame '+data.frame+' · '+data.time+'\\nX '+data.x+', Y '+data.y+' · Peak '+data.peak+'σ · '+data.pixels+' pixels';tooltip.style.display='block';if(event&&typeof event.clientX==='number')positionTooltip(event);}");
        report.println("markers.forEach(marker=>{marker.addEventListener('mouseenter',event=>showTooltip(marker,event));marker.addEventListener('mousemove',positionTooltip);marker.addEventListener('mouseleave',()=>tooltip.style.display='none');marker.addEventListener('focus',()=>showTooltip(marker));marker.addEventListener('blur',()=>tooltip.style.display='none');marker.addEventListener('click',()=>select(marker));marker.addEventListener('keydown',event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();select(marker);}});});");
        report.println("image.addEventListener('load',()=>{if(selected&&(showStatic||!selected.dataset.strip))drawCutout(selected);});");
        report.println("if(markers.length)select(markers[0]);");
        report.println("})();</script>");
    }

    private static final class Marker {
        final int id;
        final int frameIndex;
        final SourceExtractor.DetectedObject detection;
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

        Marker(int id, int frameIndex, SourceExtractor.DetectedObject detection, Color color) {
            this.id = id;
            this.frameIndex = frameIndex;
            this.detection = detection;
            this.color = color;
        }
    }
}
