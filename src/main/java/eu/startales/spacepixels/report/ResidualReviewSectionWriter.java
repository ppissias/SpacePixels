package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import io.github.ppissias.jtransient.model.Track;
import io.github.ppissias.jtransient.core.CameraFixedRejector;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes the residual-review report sections for local rescue candidates and broader residual activity clusters,
 * and the folded group of likely artifacts (camera-fixed and common-motion groups).
 */
final class ResidualReviewSectionWriter {

    private static final Color LOCAL_RESCUE_COLOR = new Color(64, 224, 208);
    private static final Color LOCAL_ACTIVITY_COLOR = new Color(255, 170, 80);
    private static final int COMMON_MOTION_SHOWN_SOURCES = 4;
    private static final int COMMON_MOTION_PANEL = 160;
    private static final int COMMON_MOTION_GIF_FRAMES = 12;
    private static final int COMMON_MOTION_FIELD_MAX = 1200;
    private static final double COMMON_MOTION_ARROW_PX = 18.0;
    private static final Color[] COMMON_MOTION_COLORS = {
            new Color(64, 224, 208),
            new Color(255, 176, 64),
            new Color(120, 170, 255),
            new Color(255, 96, 144),
            new Color(180, 230, 80),
            new Color(200, 140, 255),
            new Color(255, 220, 80),
            new Color(80, 210, 170)
    };

    private ResidualReviewSectionWriter() {
    }

    static void writeSection(PrintWriter report, DetectionReportContext context) throws IOException {
        if (!context.localRescueCandidates.isEmpty()) {
            short[][] background = context.masterStackData != null
                    ? context.masterStackData
                    : (!context.rawFrames.isEmpty() ? context.rawFrames.get(0) : null);
            report.println("<h2 id='local-rescue'>Local Rescue Candidates</h2>");
            report.println("<p class='section-lede'>A second look at the detections the tracker left unexplained. These candidates are faint local motions of just a few pixels, sparse but consistent drifts over a handful of frames, or short repeats at the same spot. They are worth a manual look; none is a confirmed object.</p>");

            int counter = 1;
            for (ResidualTransientAnalysis.LocalRescueCandidate candidate : context.localRescueCandidates) {
                exportLocalRescueCandidateCard(report, context, background, candidate, counter);
                counter++;
            }
        }

        if (!context.localActivityClusters.isEmpty()) {
            short[][] background = context.masterStackData != null
                    ? context.masterStackData
                    : (!context.rawFrames.isEmpty() ? context.rawFrames.get(0) : null);
            report.println("<h2 id='activity-clusters'>Local Activity Clusters</h2>");
            report.println("<p class='section-lede'>Groups of leftover detections that keep appearing in the same area, after the local rescue candidates took their points. They are places to review, not confirmed objects.</p>");

            int counter = 1;
            for (ResidualTransientAnalysis.LocalActivityCluster cluster : context.localActivityClusters) {
                exportLocalActivityClusterCard(report, context, background, cluster, counter);
                counter++;
            }
        }
    }

    /**
     * The groups the pipeline set aside as likely artifacts, folded near the end of the report: camera-fixed
     * groups first (removed before tracking), then common-motion groups (removed from the tracker's leftovers).
     */
    static void writeLikelyArtifactsSection(PrintWriter report,
                                            DetectionReportContext context,
                                            List<CameraFixedRejector.Group> cameraFixedGroups,
                                            List<ResidualTransientAnalysis.CommonMotionGroup> commonMotionGroups) throws IOException {
        int cameraFixed = cameraFixedGroups == null ? 0 : cameraFixedGroups.size();
        int commonMotion = commonMotionGroups == null ? 0 : commonMotionGroups.size();
        if (cameraFixed + commonMotion == 0) {
            return;
        }
        List<String> counts = new ArrayList<>();
        if (cameraFixed > 0) {
            counts.add(cameraFixed + " camera-fixed group" + (cameraFixed == 1 ? "" : "s") + " (hot pixels or dust)");
        }
        if (commonMotion > 0) {
            counts.add(commonMotion + " common-motion group" + (commonMotion == 1 ? "" : "s") + " (likely noise)");
        }
        context.progress.expect(cameraFixed + commonMotion);
        report.println("<details class='report-group' id='likely-artifacts'>");
        report.println("<summary>Likely Artifacts<span class='summary-note'>" + String.join(" and ", counts)
                + ". The pipeline set them aside; open this to check them by eye.</span></summary>");
        writeCameraFixedSection(report, context, cameraFixedGroups);
        writeCommonMotion(report, context, commonMotionGroups);
        report.println("</details>");
    }

    /** The detections set aside before tracking because they stay at one camera position (hot pixels, dust). */
    private static void writeCameraFixedSection(PrintWriter report,
                                                DetectionReportContext context,
                                                List<CameraFixedRejector.Group> groups) throws IOException {
        if (groups == null || groups.isEmpty()) {
            return;
        }
        report.println("<h2 id='camera-fixed'>Camera-Fixed Groups</h2>");
        report.println("<p class='section-lede'><strong>These groups are most likely hot pixels or dust.</strong> "
                + "Each one stays at the same position on the camera while the frames move across the sky with the dither and the drift. "
                + "Registration moves such a spot along with the frames, so it can look like a faint mover, a repeat at one spot or common motion. "
                + "They are set aside before tracking, so they do not become tracks, anomalies or rescue candidates. "
                + "How each frame moved is measured from its registration fill; frames without fill are not checked.</p>");
        short[][] background = context.masterStackData != null
                ? context.masterStackData
                : (!context.rawFrames.isEmpty() ? context.rawFrames.get(0) : null);
        int groupNumber = 1;
        for (CameraFixedRejector.Group group : groups) {
            context.progress.next();
            writeCameraFixedCard(report, context, background, group, groupNumber);
            groupNumber++;
        }
    }

    private static void writeCameraFixedCard(PrintWriter report,
                                             DetectionReportContext context,
                                             short[][] background,
                                             CameraFixedRejector.Group group,
                                             int groupNumber) throws IOException {
        Color color = COMMON_MOTION_COLORS[(groupNumber - 1) % COMMON_MOTION_COLORS.length];
        List<List<DetectedObject>> pictured = List.of(group.points);
        String trailName = "camera_fixed_" + groupNumber + "_trail.png";
        String gifName = "camera_fixed_" + groupNumber + ".gif";
        boolean trail = background != null && writeCommonMotionTrail(context, background, pictured, color, trailName);
        boolean gif = !context.rawFrames.isEmpty() && writeCommonMotionGif(context, pictured, color, gifName);
        double spread = 0;
        for (DetectedObject a : group.points) {
            for (DetectedObject b : group.points) {
                spread = Math.max(spread, Math.hypot(a.x - b.x, a.y - b.y));
            }
        }

        String css = String.format(Locale.US, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.CAMERA_FIXED, groupNumber) + "' style='border-left-color: " + css + ";'>");
        report.println("<div class='detection-title' style='color: " + css + ";'>Camera-Fixed Group " + FindingCategory.CAMERA_FIXED.id(groupNumber) + "</div>");
        report.printf(Locale.US, "<p class='astro-note'>Stays at one camera position in %d frames while it moved up to %.0f px across "
                + "the frames: most likely a hot pixel or dust. Set aside before tracking.</p>%n", group.points.size(), spread);
        report.println("<div class='image-container'>");
        if (trail) {
            report.println("<div><a href='" + trailName + "' target='_blank'><img loading='lazy' decoding='async' src='" + trailName
                    + "' alt='Camera-fixed trail' /></a><br/><center><small>Positions, blue to red</small></center></div>");
        }
        if (gif) {
            report.println("<div><a href='" + gifName + "' target='_blank'><img loading='lazy' decoding='async' src='" + gifName
                    + "' alt='Camera-fixed animation' /></a><br/><center><small>The frames</small></center></div>");
        }
        report.println("</div>");
        report.println("<details class='foldable-streak-details'><summary>Coordinates</summary><div class='foldable-streak-body'>");
        report.print("<ul class='source-list'><li>");
        for (DetectedObject point : group.points) {
            report.printf(Locale.US, " frame %d (%.1f, %.1f)", point.sourceFrameIndex + 1, point.x, point.y);
        }
        report.println("</li></ul></div></details>");
        report.println("</div>");
    }

    private static void writeCommonMotion(PrintWriter report,
                                          DetectionReportContext context,
                                          List<ResidualTransientAnalysis.CommonMotionGroup> groups) throws IOException {
        if (groups == null || groups.isEmpty()) {
            return;
        }
        report.println("<h2 id='common-motion'>Common Motion</h2>");
        report.println("<p class='section-lede'><strong>These groups are most likely noise or moving artifacts.</strong> "
                + "The common-motion filter looks for separated compact sources that share the same apparent displacement across frames. "
                + "This pattern often comes from hot pixels, registration residuals or other systematic artifacts. "
                + "Their detections are filtered out before further local rescue and activity clustering, "
                + "so they do not become candidates in those stages. They remain here for inspection: "
                + "shared motion is a warning, not proof, and a real pair can occasionally be grouped together.</p>");

        short[][] background = context.masterStackData != null
                ? context.masterStackData
                : (!context.rawFrames.isEmpty() ? context.rawFrames.get(0) : null);
        writeCommonMotionField(report, context, background, groups);

        int groupNumber = 1;
        for (ResidualTransientAnalysis.CommonMotionGroup group : groups) {
            context.progress.next();
            writeCommonMotionCard(report, context, background, group, groupNumber);
            groupNumber++;
        }
    }

    /** Field map of every source. Returns how much the arrows were lengthened, or 1 when they are true. */
    private static double writeCommonMotionField(PrintWriter report,
                                                DetectionReportContext context,
                                                short[][] background,
                                                List<ResidualTransientAnalysis.CommonMotionGroup> groups) throws IOException {
        if (background == null || background.length == 0 || background[0].length == 0) {
            return 1.0;
        }
        int width = background[0].length;
        int height = background.length;
        double scale = Math.min(1.0, COMMON_MOTION_FIELD_MAX / (double) Math.max(width, height));
        int scaledW = Math.max(1, (int) Math.round(width * scale));
        int scaledH = Math.max(1, (int) Math.round(height * scale));

        BufferedImage full = TrackVisualizationRenderer.createDisplayImage(background, context.settings);
        BufferedImage map = new BufferedImage(scaledW, scaledH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = map.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.drawImage(full, 0, 0, scaledW, scaledH, null);
        full.flush();

        double arrowFactor = commonMotionArrowFactor(groups, scale);
        int groupNumber = 0;
        for (ResidualTransientAnalysis.CommonMotionGroup group : groups) {
            Color color = COMMON_MOTION_COLORS[groupNumber % COMMON_MOTION_COLORS.length];
            for (List<DetectedObject> source : group.sources) {
                drawCommonMotionArrow(g2d, source, scale, arrowFactor, color, scaledW, scaledH);
            }
            groupNumber++;
        }
        g2d.dispose();

        String fileName = "common_motion_field.png";
        TrackVisualizationRenderer.saveLosslessPng(map, new File(context.exportDir, fileName));
        report.println("<div class='image-container'>");
        report.println("<div><a href='" + fileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + fileName
                + "' alt='Common motion field' /></a><br/><center><small>Shared motion across the field</small></center></div>");
        report.println("</div>");
        if (arrowFactor > 1.05) {
            report.println("<p class='astro-note'>Each arrow starts at a source's first detection and points the way it moved. "
                    + "One colour is one group. The arrows are " + String.format(Locale.US, "%.0f", arrowFactor)
                    + " times the true shift, all by the same factor, so a shift of a few pixels can be seen.</p>");
        } else {
            report.println("<p class='astro-note'>Each arrow starts at a source's first detection and points the way it moved, at the true shift. "
                    + "One colour is one group. A ring is a source seen in only one frame.</p>");
        }
        return arrowFactor;
    }

    private static void writeCommonMotionCard(PrintWriter report,
                                              DetectionReportContext context,
                                              short[][] background,
                                              ResidualTransientAnalysis.CommonMotionGroup group,
                                              int groupNumber) throws IOException {
        Color color = COMMON_MOTION_COLORS[(groupNumber - 1) % COMMON_MOTION_COLORS.length];
        int shown = Math.min(COMMON_MOTION_SHOWN_SOURCES, group.sources.size());
        List<List<DetectedObject>> pictured = group.sources.subList(0, shown);
        String trailName = "common_motion_" + groupNumber + "_trail.png";
        String gifName = "common_motion_" + groupNumber + ".gif";
        boolean trail = background != null && writeCommonMotionTrail(context, background, pictured, color, trailName);
        boolean gif = !context.rawFrames.isEmpty() && writeCommonMotionGif(context, pictured, color, gifName);

        String css = String.format(Locale.US, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.COMMON_MOTION, groupNumber) + "' style='border-left-color: " + css + ";'>");
        report.println("<div class='detection-title' style='color: " + css + ";'>Common Motion Group " + FindingCategory.COMMON_MOTION.id(groupNumber) + "</div>");
        report.println("<p class='astro-note'>Most likely noise or a moving artifact; filtered out before local rescue and activity clustering.</p>");
        report.println("<div class='astro-note' style='margin-top: -5px; margin-bottom: 12px;'>"
                + group.sources.size() + " sources, " + group.sharedFrameCount + " shared frames."
                + (shown < group.sources.size()
                ? " The pictures show the first " + shown + "."
                : " The pictures show every source.")
                + " Each panel is one source, and the frames play together.</div>");
        report.println("<div class='image-container'>");
        if (trail) {
            report.println("<div><a href='" + trailName + "' target='_blank'><img loading='lazy' decoding='async' src='" + trailName
                    + "' alt='Common motion trail' /></a><br/><center><small>Trails, blue to red</small></center></div>");
        }
        if (gif) {
            report.println("<div><a href='" + gifName + "' target='_blank'><img loading='lazy' decoding='async' src='" + gifName
                    + "' alt='Common motion animation' /></a><br/><center><small>Same frames, side by side</small></center></div>");
        }
        report.println("</div>");
        report.println("<details class='foldable-streak-details'><summary>Coordinates</summary><div class='foldable-streak-body'>");
        report.println("<ul class='source-list'>");
        int sourceNumber = 1;
        for (List<DetectedObject> source : group.sources) {
            report.print("<li>Source " + sourceNumber + ":");
            for (DetectedObject point : source) {
                report.printf(Locale.US, " frame %d (%.1f, %.1f)", point.sourceFrameIndex + 1, point.x, point.y);
            }
            report.println("</li>");
            sourceNumber++;
        }
        report.println("</ul></div></details>");
        report.println("</div>");
    }

    private static boolean writeCommonMotionTrail(DetectionReportContext context,
                                                  short[][] background,
                                                  List<List<DetectedObject>> sources,
                                                  Color color,
                                                  String fileName) throws IOException {
        List<BufferedImage> panels = new ArrayList<>();
        int index = 1;
        for (List<DetectedObject> source : sources) {
            if (source.isEmpty()) {
                continue;
            }
            int cx = (int) Math.round(mean(source, true));
            int cy = (int) Math.round(mean(source, false));
            BufferedImage panel = croppedDisplay(background, cx, cy, context.settings);
            Graphics2D g2d = panel.createGraphics();
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int count = source.size();
            for (int i = 0; i < count; i++) {
                DetectedObject point = source.get(i);
                float ratio = count > 1 ? (float) i / (count - 1) : 0f;
                Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);
                int localX = local(point.x, cx);
                int localY = local(point.y, cy);
                g2d.setColor(new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 140));
                g2d.fillOval(localX - 6, localY - 6, 12, 12);
                g2d.setColor(timeColor);
                g2d.fillOval(localX - 3, localY - 3, 6, 6);
            }
            if (sources.size() > 1) {
                labelPanel(g2d, Integer.toString(index), color);
            }
            g2d.dispose();
            panels.add(panel);
            index++;
        }
        if (panels.isEmpty()) {
            return false;
        }
        TrackVisualizationRenderer.saveLosslessPng(stitch(panels), new File(context.exportDir, fileName));
        return true;
    }

    private static boolean writeCommonMotionGif(DetectionReportContext context,
                                                List<List<DetectedObject>> sources,
                                                Color color,
                                                String fileName) throws IOException {
        Set<Integer> mandatory = new HashSet<>();
        for (List<DetectedObject> source : sources) {
            for (DetectedObject point : source) {
                if (point.sourceFrameIndex >= 0 && point.sourceFrameIndex < context.rawFrames.size()) {
                    mandatory.add(point.sourceFrameIndex);
                }
            }
        }
        if (mandatory.isEmpty()) {
            return false;
        }
        List<Integer> frames = context.sampleFrames(mandatory, COMMON_MOTION_GIF_FRAMES);
        List<BufferedImage> gifFrames = new ArrayList<>();
        for (int frameIndex : frames) {
            List<BufferedImage> panels = new ArrayList<>();
            int index = 1;
            boolean first = true;
            for (List<DetectedObject> source : sources) {
                if (source.isEmpty()) {
                    continue;
                }
                int cx = (int) Math.round(mean(source, true));
                int cy = (int) Math.round(mean(source, false));
                BufferedImage panel = croppedDisplay(context.rawFrames.get(frameIndex), cx, cy, context.settings);
                Graphics2D g2d = panel.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                DetectedObject hit = pointOnFrame(source, frameIndex);
                if (hit != null) {
                    int localX = local(hit.x, cx);
                    int localY = local(hit.y, cy);
                    g2d.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 90));
                    g2d.fillOval(localX - 8, localY - 8, 16, 16);
                    g2d.setColor(color);
                    g2d.setStroke(new BasicStroke(2f));
                    g2d.drawOval(localX - 5, localY - 5, 10, 10);
                }
                if (sources.size() > 1) {
                    labelPanel(g2d, Integer.toString(index), color);
                }
                if (first) {
                    g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
                    g2d.setColor(new Color(255, 255, 255, 230));
                    g2d.drawString(TrackVisualizationRenderer.frameLabel(context, frameIndex), 8, COMMON_MOTION_PANEL - 8);
                    first = false;
                }
                g2d.dispose();
                panels.add(panel);
                index++;
            }
            if (!panels.isEmpty()) {
                gifFrames.add(stitch(panels));
            }
        }
        if (gifFrames.isEmpty()) {
            return false;
        }
        TrackVisualizationRenderer.saveAnimatedGif(gifFrames, new File(context.exportDir, fileName), context.settings);
        return true;
    }

    private static double commonMotionArrowFactor(List<ResidualTransientAnalysis.CommonMotionGroup> groups, double scale) {
        List<Double> lengths = new ArrayList<>();
        for (ResidualTransientAnalysis.CommonMotionGroup group : groups) {
            for (List<DetectedObject> source : group.sources) {
                if (source.size() < 2) {
                    continue;
                }
                DetectedObject first = source.get(0);
                DetectedObject last = source.get(source.size() - 1);
                lengths.add(Math.hypot((last.x - first.x) * scale, (last.y - first.y) * scale));
            }
        }
        if (lengths.isEmpty()) {
            return 1.0;
        }
        lengths.sort(Comparator.naturalOrder());
        double median = lengths.get(lengths.size() / 2);
        if (median < 1.0) {
            median = 1.0;
        }
        return median >= COMMON_MOTION_ARROW_PX ? 1.0 : COMMON_MOTION_ARROW_PX / median;
    }

    private static void drawCommonMotionArrow(Graphics2D g2d,
                                              List<DetectedObject> source,
                                              double scale,
                                              double arrowFactor,
                                              Color color,
                                              int width,
                                              int height) {
        if (source.isEmpty()) {
            return;
        }
        DetectedObject first = source.get(0);
        int x1 = clamp((int) Math.round(first.x * scale), 0, width - 1);
        int y1 = clamp((int) Math.round(first.y * scale), 0, height - 1);
        g2d.setColor(color);
        g2d.setStroke(new BasicStroke(2f));
        if (source.size() < 2) {
            g2d.drawOval(x1 - 4, y1 - 4, 8, 8);
            return;
        }
        DetectedObject last = source.get(source.size() - 1);
        double dx = (last.x - first.x) * scale * arrowFactor;
        double dy = (last.y - first.y) * scale * arrowFactor;
        if (Math.hypot(dx, dy) < 1.5) {
            g2d.drawOval(x1 - 4, y1 - 4, 8, 8);
            return;
        }
        int x2 = clamp((int) Math.round(x1 + dx), 0, width - 1);
        int y2 = clamp((int) Math.round(y1 + dy), 0, height - 1);
        g2d.drawLine(x1, y1, x2, y2);
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int head = 7;
        g2d.drawLine(x2, y2, x2 - (int) Math.round(head * Math.cos(angle - 0.45)), y2 - (int) Math.round(head * Math.sin(angle - 0.45)));
        g2d.drawLine(x2, y2, x2 - (int) Math.round(head * Math.cos(angle + 0.45)), y2 - (int) Math.round(head * Math.sin(angle + 0.45)));
        g2d.fillOval(x1 - 2, y1 - 2, 4, 4);
    }

    /** A colour copy of the stretched crop: drawing on the grayscale display image would turn every marker gray. */
    private static BufferedImage croppedDisplay(short[][] image, int cx, int cy, VisualizationSettings settings) {
        short[][] crop = TrackVisualizationRenderer.robustEdgeAwareCrop(image, cx, cy, COMMON_MOTION_PANEL, COMMON_MOTION_PANEL);
        BufferedImage gray = TrackVisualizationRenderer.createDisplayImage(crop, settings);
        BufferedImage panel = new BufferedImage(gray.getWidth(), gray.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = panel.createGraphics();
        g2d.drawImage(gray, 0, 0, null);
        g2d.dispose();
        return panel;
    }

    private static BufferedImage stitch(List<BufferedImage> panels) {
        int gap = 6;
        int width = panels.size() * COMMON_MOTION_PANEL + Math.max(0, panels.size() - 1) * gap;
        BufferedImage strip = new BufferedImage(Math.max(1, width), COMMON_MOTION_PANEL, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = strip.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, strip.getWidth(), strip.getHeight());
        int x = 0;
        for (BufferedImage panel : panels) {
            g2d.drawImage(panel, x, 0, null);
            x += COMMON_MOTION_PANEL + gap;
        }
        g2d.dispose();
        return strip;
    }

    private static void labelPanel(Graphics2D g2d, String label, Color color) {
        g2d.setFont(new Font("Segoe UI", Font.BOLD, 13));
        g2d.setColor(new Color(0, 0, 0, 160));
        g2d.fillRoundRect(6, 6, 22, 18, 4, 4);
        g2d.setColor(color);
        g2d.drawString(label, 10, 20);
    }

    private static DetectedObject pointOnFrame(List<DetectedObject> source, int frameIndex) {
        for (DetectedObject point : source) {
            if (point.sourceFrameIndex == frameIndex) {
                return point;
            }
        }
        return null;
    }

    private static double mean(List<DetectedObject> source, boolean horizontal) {
        double sum = 0.0;
        for (DetectedObject point : source) {
            sum += horizontal ? point.x : point.y;
        }
        return sum / source.size();
    }

    private static int local(double coordinate, int center) {
        return (int) Math.round(coordinate - (center - COMMON_MOTION_PANEL / 2));
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    static Track buildResidualTrack(List<DetectedObject> points) {
        Track track = new Track();
        if (points == null || points.isEmpty()) {
            return track;
        }

        List<DetectedObject> orderedPoints = new ArrayList<>(points);
        orderedPoints.sort(Comparator.comparingInt((DetectedObject point) -> point.sourceFrameIndex)
                .thenComparingDouble(point -> point.x)
                .thenComparingDouble(point -> point.y));
        for (DetectedObject point : orderedPoints) {
            track.addPoint(point);
        }
        return track;
    }

    private static void exportLocalRescueCandidateCard(PrintWriter report,
                                                       DetectionReportContext context,
                                                       short[][] referenceBackground,
                                                       ResidualTransientAnalysis.LocalRescueCandidate candidate,
                                                       int counter) throws IOException {
        Track track = buildResidualTrack(candidate.points);
        TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, Math.max(140, context.settings.getTrackCropPadding() / 2));

        String prefix = "micro_drift_" + counter;
        String backgroundFileName = prefix + "_background.png";
        String trailFileName = prefix + "_trail.png";
        String shapeFileName = prefix + "_shape.png";
        String contextGifFileName = prefix + "_context.gif";

        short[][] backgroundSource = resolveBackgroundSource(referenceBackground, context.rawFrames, track);

        if (backgroundSource != null) {
            short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    backgroundSource,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            TrackVisualizationRenderer.saveLosslessPng(
                    TrackVisualizationRenderer.createDisplayImage(croppedBackground, context.settings),
                    new File(context.exportDir, backgroundFileName));

            BufferedImage trailImage = createMicroDriftTrailImage(
                    track,
                    backgroundSource,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY,
                    context.settings);
            TrackVisualizationRenderer.saveLosslessPng(trailImage, new File(context.exportDir, trailFileName));
        }

        BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                track,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                cropBounds.startX,
                cropBounds.startY);
        TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

        if (!context.rawFrames.isEmpty()) {
            Set<Integer> mandatoryFrames = collectResidualFrameIndices(track);
            Map<Integer, DetectedObject> pointByFrame = buildPointByFrameMap(track);
            List<Integer> sampledIndices = context.sampleFrames(mandatoryFrames, 12);
            List<BufferedImage> contextFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                DetectedObject detectedPoint = pointByFrame.get(frameIndex);
                String pointLabel = detectedPoint != null ? "P" + (track.points.indexOf(detectedPoint) + 1) : null;
                BufferedImage frameImage = TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                        context.rawFrames.get(frameIndex),
                        cropBounds,
                        detectedPoint,
                        context.settings,
                        LOCAL_RESCUE_COLOR,
                        pointLabel,
                        TrackVisualizationRenderer.frameLabel(context, frameIndex),
                        true);
                contextFrames.add(frameImage);
            }
            TrackVisualizationRenderer.saveAnimatedGif(contextFrames, new File(context.exportDir, contextGifFileName), context.settings);
        }

        String candidateBadge = buildLocalRescueBadge(candidate.kind);
        String candidateNote = buildLocalRescueNote(candidate.kind);
        ResidualTransientAnalysis.LocalTransientMetrics metrics = candidate.metrics;
        report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.LOCAL_RESCUE, counter) + "' style='border-left-color: #40e0d0;'>");
        report.println("<div class='detection-title' style='color: #40e0d0;'>Local Rescue Candidate " + FindingCategory.LOCAL_RESCUE.id(counter) + candidateBadge + "</div>");
        report.println("<div class='astro-note' style='margin-top: -5px; margin-bottom: 12px;'>" + candidateNote + "</div>");
        report.println("<div class='image-container'>");
        if (backgroundSource != null) {
            report.println("<div><a href='" + backgroundFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + backgroundFileName + "' alt='Reference Crop' /></a><br/><center><small>Reference Crop</small></center></div>");
            report.println("<div><a href='" + trailFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + trailFileName + "' alt='Local Trail Map' /></a><br/><center><small>Local Trail Map</small></center></div>");
        }
        if (!context.rawFrames.isEmpty()) {
            report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + contextGifFileName + "' alt='Micro-Drift Animation' /></a><br/><center><small>Animation (Star-Centric)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + shapeFileName + "' alt='Shape Footprint Evolution' /></a><br/><center><small>Shape Evolution</small></center></div>");
        report.println("</div>");

        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 10px;'>"
                + "Type: <span style='color:#fff;'>" + ReportFormat.escapeHtml(formatLocalRescueKind(candidate.kind)) + "</span>"
                + " | "
                + "Support: <span style='color:#fff;'>" + metrics.pointCount + " detections</span>"
                + " across <span style='color:#fff;'>" + metrics.frameSpan + "</span> frame(s)"
                + " | Gap Frames: <span style='color:#fff;'>" + metrics.totalGapFrames + "</span>"
                + " | Total Motion: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.totalDisplacementPixels) + "</span>"
                + " | Mean Step: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.averageStepPixels) + "</span>"
                + " | Cluster Radius: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.clusterRadiusPixels) + "</span>"
                + " | Fit RMSE: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.linearityRmsePixels) + "</span>"
                + " | Score: <span style='color:#fff;'>" + String.format(Locale.US, "%.1f", candidate.score) + "</span>"
                + "</div>");
        report.print(ReportFormat.buildTrackTimingSummaryHtml(track, context.astrometryContext));

        report.println("<strong>Detection Coordinates & Frames:</strong><ul class='source-list'>");
        for (int i = 0; i < track.points.size(); i++) {
            DetectedObject point = track.points.get(i);
            String pointMetrics = buildMicroDriftMetricsText(point, i + 1);
            report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    "[" + (i + 1) + "] " + point.sourceFilename,
                    context.astrometryContext,
                    point.x,
                    point.y,
                    pointMetrics));
        }
        report.println("</ul>");
        report.print(DetectionReportAstrometry.buildTrackSolarSystemIdentificationHtml(
                context.astrometryContext,
                track,
                "local-rescue-jpl-" + counter,
                String.format(Locale.US, "jpl_local_rescue_%02d", counter)));
        report.println("</div>");
    }

    private static void exportLocalActivityClusterCard(PrintWriter report,
                                                       DetectionReportContext context,
                                                       short[][] referenceBackground,
                                                       ResidualTransientAnalysis.LocalActivityCluster cluster,
                                                       int counter) throws IOException {
        Track track = buildResidualTrack(cluster.points);
        TrackCropGeometry.CropBounds cropBounds = new TrackCropGeometry.CropBounds(track, Math.max(180, context.settings.getTrackCropPadding() / 2));

        String prefix = "local_activity_" + counter;
        String backgroundFileName = prefix + "_background.png";
        String trailFileName = prefix + "_trail.png";
        String shapeFileName = prefix + "_shape.png";
        String contextGifFileName = prefix + "_context.gif";

        short[][] backgroundSource = resolveBackgroundSource(referenceBackground, context.rawFrames, track);

        if (backgroundSource != null) {
            short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                    backgroundSource,
                    cropBounds.fixedCenterX,
                    cropBounds.fixedCenterY,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight);
            TrackVisualizationRenderer.saveLosslessPng(
                    TrackVisualizationRenderer.createDisplayImage(croppedBackground, context.settings),
                    new File(context.exportDir, backgroundFileName));

            BufferedImage trailImage = createMicroDriftTrailImage(
                    track,
                    backgroundSource,
                    cropBounds.trackBoxWidth,
                    cropBounds.trackBoxHeight,
                    cropBounds.startX,
                    cropBounds.startY,
                    context.settings);
            TrackVisualizationRenderer.saveLosslessPng(trailImage, new File(context.exportDir, trailFileName));
        }

        BufferedImage shapeImage = TrackVisualizationRenderer.createTrackShapeImage(
                track,
                cropBounds.trackBoxWidth,
                cropBounds.trackBoxHeight,
                cropBounds.startX,
                cropBounds.startY);
        TrackVisualizationRenderer.saveLosslessPng(shapeImage, new File(context.exportDir, shapeFileName));

        if (!context.rawFrames.isEmpty()) {
            Set<Integer> mandatoryFrames = collectResidualFrameIndices(track);
            Map<Integer, DetectedObject> pointByFrame = buildPointByFrameMap(track);
            List<Integer> sampledIndices = context.sampleFrames(mandatoryFrames, 12);
            List<BufferedImage> contextFrames = new ArrayList<>();
            for (int frameIndex : sampledIndices) {
                DetectedObject detectedPoint = pointByFrame.get(frameIndex);
                String pointLabel = detectedPoint != null ? "P" + (track.points.indexOf(detectedPoint) + 1) : null;
                BufferedImage frameImage = TrackVisualizationRenderer.createStarCentricHighlightedFrame(
                        context.rawFrames.get(frameIndex),
                        cropBounds,
                        detectedPoint,
                        context.settings,
                        LOCAL_ACTIVITY_COLOR,
                        pointLabel,
                        TrackVisualizationRenderer.frameLabel(context, frameIndex),
                        true);
                contextFrames.add(frameImage);
            }
            TrackVisualizationRenderer.saveAnimatedGif(contextFrames, new File(context.exportDir, contextGifFileName), context.settings);
        }

        ResidualTransientAnalysis.LocalTransientMetrics metrics = cluster.metrics;
        report.println("<div class='detection-card' id='" + context.findingAnchor(FindingCategory.LOCAL_ACTIVITY, counter) + "' style='border-left-color: #ffb347;'>");
        report.println("<div class='detection-title' style='color: #ffb347;'>Local Activity Cluster " + FindingCategory.LOCAL_ACTIVITY.id(counter)
                + " <span style='background: #7a4b14; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>"
                + String.format(Locale.US, "%.0f px Linkage", cluster.linkageRadiusPixels)
                + "</span></div>");
        report.println("<div class='astro-note' style='margin-top: -5px; margin-bottom: 12px;'>Leftover detections that keep appearing in the same area. Not a confirmed object; a place worth checking by eye.</div>");
        report.println("<div class='image-container'>");
        if (backgroundSource != null) {
            report.println("<div><a href='" + backgroundFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + backgroundFileName + "' alt='Reference Crop' /></a><br/><center><small>Reference Crop</small></center></div>");
            report.println("<div><a href='" + trailFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + trailFileName + "' alt='Local Cluster Trail Map' /></a><br/><center><small>Cluster Trail Map</small></center></div>");
        }
        if (!context.rawFrames.isEmpty()) {
            report.println("<div><a href='" + contextGifFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + contextGifFileName + "' alt='Local Activity Animation' /></a><br/><center><small>Animation (Star-Centric)</small></center></div>");
        }
        report.println("<div><a href='" + shapeFileName + "' target='_blank'><img loading='lazy' decoding='async' src='" + shapeFileName + "' alt='Cluster Shape Evolution' /></a><br/><center><small>Shape Evolution</small></center></div>");
        report.println("</div>");

        report.println("<div style='font-family: monospace; font-size: 12px; color: #aaa; margin-bottom: 10px;'>"
                + "Support: <span style='color:#fff;'>" + metrics.pointCount + " detections</span>"
                + " across <span style='color:#fff;'>" + metrics.uniqueFrameCount + "</span> unique frame(s)"
                + " | Frame Span: <span style='color:#fff;'>" + metrics.frameSpan + "</span>"
                + " | Gap Frames: <span style='color:#fff;'>" + metrics.totalGapFrames + "</span>"
                + " | Total Motion: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.totalDisplacementPixels) + "</span>"
                + " | Mean Step: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.averageStepPixels) + "</span>"
                + " | Cluster Radius: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.clusterRadiusPixels) + "</span>"
                + " | Fit RMSE: <span style='color:#fff;'>" + String.format(Locale.US, "%.2f px", metrics.linearityRmsePixels) + "</span>"
                + " | Linkage Radius: <span style='color:#fff;'>" + String.format(Locale.US, "%.1f px", cluster.linkageRadiusPixels) + "</span>"
                + "</div>");

        report.println("<strong>Cluster Coordinates & Frames:</strong><ul class='source-list'>");
        for (int i = 0; i < track.points.size(); i++) {
            DetectedObject point = track.points.get(i);
            String metricsText = buildMicroDriftMetricsText(point, i + 1);
            report.println(DetectionReportAstrometry.buildSourceCoordinateListEntry(
                    "[" + (i + 1) + "] " + point.sourceFilename,
                    context.astrometryContext,
                    point.x,
                    point.y,
                    metricsText));
        }
        report.println("</ul>");
        report.println("</div>");
    }

    private static short[][] resolveBackgroundSource(short[][] referenceBackground,
                                                     List<short[][]> rawFrames,
                                                     Track track) {
        if (referenceBackground != null) {
            return referenceBackground;
        }
        if (rawFrames == null || rawFrames.isEmpty() || track == null || track.points == null || track.points.isEmpty()) {
            return null;
        }
        int fallbackIndex = Math.max(0, Math.min(rawFrames.size() - 1, track.points.get(0).sourceFrameIndex));
        return rawFrames.get(fallbackIndex);
    }

    private static Map<Integer, DetectedObject> buildPointByFrameMap(Track track) {
        Map<Integer, DetectedObject> pointByFrame = new HashMap<>();
        for (DetectedObject point : track.points) {
            pointByFrame.put(point.sourceFrameIndex, point);
        }
        return pointByFrame;
    }

    private static Set<Integer> collectResidualFrameIndices(Track track) {
        Set<Integer> frameIndices = new HashSet<>();
        if (track == null || track.points == null) {
            return frameIndices;
        }
        for (DetectedObject point : track.points) {
            frameIndices.add(point.sourceFrameIndex);
        }
        return frameIndices;
    }

    private static String formatLocalRescueKind(ResidualTransientAnalysis.LocalRescueKind kind) {
        if (kind == null) {
            return "Local Rescue";
        }
        switch (kind) {
            case LOCAL_REPEAT:
                return "Same-Location Repeat";
            case SPARSE_LOCAL_DRIFT:
                return "Sparse Local Drift";
            case MICRO_DRIFT:
            default:
                return "Micro-Drift";
        }
    }

    private static String buildLocalRescueBadge(ResidualTransientAnalysis.LocalRescueKind kind) {
        if (kind == ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT) {
            return " <span style='background: #19595a; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>2+ Same-Location Rescue</span>";
        }
        if (kind == ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT) {
            return " <span style='background: #2d5a88; color: white; font-size: 0.7em; padding: 3px 8px; border-radius: 5px; margin-left: 10px; vertical-align: middle;'>Sparse Local-Drift Rescue</span>";
        }
        return "";
    }

    private static String buildLocalRescueNote(ResidualTransientAnalysis.LocalRescueKind kind) {
        if (kind == ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT) {
            return "Found among the detections the tracker left unexplained. It repeats at nearly the same spot rather than moving clearly, so check it by eye.";
        }
        if (kind == ResidualTransientAnalysis.LocalRescueKind.SPARSE_LOCAL_DRIFT) {
            return "Found among the detections the tracker left unexplained. It drifts consistently across a few frames that are not consecutive, which the normal track linking would miss.";
        }
        return "Found among the detections the tracker left unexplained. Not a confirmed track; check it by eye.";
    }

    private static BufferedImage createMicroDriftTrailImage(Track track,
                                                            short[][] backgroundData,
                                                            int cropWidth,
                                                            int cropHeight,
                                                            int startX,
                                                            int startY, VisualizationSettings settings) {
        short[][] croppedBackground = TrackVisualizationRenderer.robustEdgeAwareCrop(
                backgroundData,
                startX + (cropWidth / 2),
                startY + (cropHeight / 2),
                cropWidth,
                cropHeight);
        BufferedImage grayBackground = TrackVisualizationRenderer.createDisplayImage(croppedBackground, settings);
        BufferedImage output = new BufferedImage(cropWidth, cropHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = output.createGraphics();
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, cropWidth, cropHeight);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
        g2d.drawImage(grayBackground, 0, 0, null);
        g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int pointCount = track == null || track.points == null ? 0 : track.points.size();
        for (int i = 0; i < pointCount; i++) {
            DetectedObject point = track.points.get(i);
            float ratio = pointCount > 1 ? (float) i / (pointCount - 1) : 0f;
            Color timeColor = Color.getHSBColor(0.66f - (0.66f * ratio), 1.0f, 1.0f);
            int localX = (int) Math.round(point.x - startX);
            int localY = (int) Math.round(point.y - startY);

            if (point.rawPixels != null && !point.rawPixels.isEmpty()) {
                for (Pixel pixel : point.rawPixels) {
                    int px = pixel.x - startX;
                    int py = pixel.y - startY;
                    if (px >= 0 && px < cropWidth && py >= 0 && py < cropHeight) {
                        output.setRGB(px, py, timeColor.getRGB());
                    }
                }
            }

            g2d.setColor(new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 120));
            g2d.fillOval(localX - 7, localY - 7, 14, 14);
            g2d.setColor(timeColor);
            g2d.fillOval(localX - 3, localY - 3, 6, 6);
            g2d.setColor(Color.WHITE);
            g2d.setFont(new Font("Segoe UI", Font.BOLD, 12));
            g2d.drawString(String.valueOf(i + 1), localX + 8, localY - 8);

            if (i > 0) {
                DetectedObject previous = track.points.get(i - 1);
                int prevX = (int) Math.round(previous.x - startX);
                int prevY = (int) Math.round(previous.y - startY);
                g2d.setColor(new Color(timeColor.getRed(), timeColor.getGreen(), timeColor.getBlue(), 180));
                g2d.setStroke(new BasicStroke(2.0f));
                g2d.drawLine(prevX, prevY, localX, localY);
            }
        }

        g2d.dispose();
        return output;
    }

    private static String buildMicroDriftMetricsText(DetectedObject detection, int sequenceNumber) {
        return String.format(
                Locale.US,
                "Point %d, Flux: %.1f, Pixels: %d, Elongation: %.2f, FWHM: %.2f, Peak Sigma: %.2f, Integrated Sigma: %.2f, UTC: %s",
                sequenceNumber,
                detection.totalFlux,
                (int) detection.pixelArea,
                detection.elongation,
                detection.fwhm,
                detection.peakSigma,
                detection.integratedSigma,
                formatUtcTimestamp(detection.timestamp));
    }

    private static String formatUtcTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "Unknown";
        }

        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(timestampMillis));
    }
}
