package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/** Candidate positions on the median stack, with an offline full-resolution light-curve map. */
final class VariableStarMapWriter {
    static final String PREVIEW_FILE = "photometry_candidate_map.svg";
    static final String FULL_SIZE_FILE = "photometry_candidate_map_full.svg";
    private static final int HEADER_HEIGHT = 100;

    private VariableStarMapWriter() { }

    static void write(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                      List<StarLightCurve> candidates) throws IOException {
        short[][] stack = context.masterStackData;
        if (candidates.isEmpty()) return;
        if (stack == null || stack.length == 0 || stack[0].length == 0) {
            report.println("<p class='compact-note'>The candidate map is unavailable because this report has no median stack.</p>");
            return;
        }
        int width = stack[0].length, height = stack.length;
        List<String> names = candidates.stream().map(star -> candidateName(context, star)).toList();
        BufferedImage display = TrackVisualizationRenderer.createDisplayImage(stack, context.settings);
        writeMap(new File(context.exportDir, FULL_SIZE_FILE), display, width, height, analysis, candidates, names, true);
        double scale = Math.min(1.0, 1800.0 / width);
        BufferedImage preview = SessionPictureSectionWriter.downscale(display,
                Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale)));
        writeMap(new File(context.exportDir, PREVIEW_FILE), preview, width, height, analysis, candidates, names, false);

        report.println("<div class='variable-candidate-map' style='margin:16px 0;'>");
        report.println("<h3>Variable candidates across the field</h3>");
        report.println("<p class='compact-note'>The median stack with all " + candidates.size()
                + " reported candidates circled. Labels prefer a VSX name, then a catalogue star name or Gaia DR3 designation; otherwise they use the table's V number. "
                + "<span style='color:" + PhotometrySvgChart.SERIES_BLUE + ";'>Blue: high confidence.</span> "
                + "<span style='color:" + PhotometrySvgChart.SERIES_ORANGE + ";'>Orange: possible.</span> "
                + "Click the picture to open the full-size image in a new tab, with a small light curve beside each candidate.</p>");
        report.println("<a href='" + FULL_SIZE_FILE + "' target='_blank' rel='noopener'><img loading='lazy' decoding='async' src='"
                + PREVIEW_FILE + "' width='" + width + "' height='" + height
                + "' style='display:block;max-width:100%;height:auto;border-radius:6px;' "
                + "alt='Median stack with variable-star candidates circled and labelled by catalogue name or V number' /></a>");
        report.println("<p class='compact-note'><a href='" + FULL_SIZE_FILE + "' target='_blank' rel='noopener'>Open full-size candidate map and light curves</a>. "
                + "The transparent mini curves show only the curve and observed Δmag in mag, without axes. Brighter is up; each curve has its own scale. "
                + "Only unflagged measurements are plotted, with gaps left open. Hover for the V number and catalogue name. "
                + "Use the candidate cards for error bars and comparison stars.</p></div>");
    }

    private static String candidateName(DetectionReportContext context, StarLightCurve star) {
        var target = PhotometryReportSectionWriter.skyTarget(context, star.x, star.y);
        if (target == null) return null;
        SessionCatalogue catalogue = context.catalogue();
        var variables = catalogue.variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec);
        if (!variables.isEmpty() && variables.get(0).star.name != null && !variables.get(0).star.name.isBlank())
            return variables.get(0).star.name;
        var identity = catalogue.starIdentityNear(target.raDegrees, target.decDegrees, Math.min(target.radiusArcsec, 5.0));
        return identity == null ? null : identity.name();
    }

    private static void writeMap(File file, BufferedImage image, int width, int height, VariableStarAnalysis analysis,
                                 List<StarLightCurve> candidates, List<String> names, boolean curves) throws IOException {
        try (AtomicReportFile artifact = new AtomicReportFile(file)) {
            PrintWriter out = artifact.writer();
            int header = curves ? HEADER_HEIGHT : 0;
            out.println("<svg xmlns='http://www.w3.org/2000/svg' width='" + width + "' height='" + (height + header)
                    + "' viewBox='0 0 " + width + " " + (height + header) + "' role='img' font-family='Segoe UI, sans-serif'>");
            out.println("<title>Variable-star candidate map" + (curves ? " with light curves" : "") + "</title>");
            out.println("<desc>Median stack at its original orientation. Catalogue names identify stars, not known variability; V numbers match the report. Blue means high confidence; orange means possible. "
                    + (curves ? "Light curves show differential magnitudes, brighter upward, with individual scales and a common session time axis." : "") + "</desc>");
            out.println("<rect width='100%' height='100%' fill='#191919'/>");
            if (curves) {
                text(out, 12, 25, "Variable-star candidates · " + candidates.size(), "#eeeeee", 20);
                text(out, 12, 48, "High confidence", PhotometrySvgChart.SERIES_BLUE, 14);
                text(out, 180, 48, "Possible", PhotometrySvgChart.SERIES_ORANGE, 14);
                text(out, 12, 70, "Δmag · brighter up · individual scales", "#cccccc", 14);
                text(out, 12, 89, "Unflagged measurements; gaps left open", "#aaaaaa", 12);
            }
            out.println("<g transform='translate(0 " + header + ")'>");
            // Embedding the display image keeps both SVGs self-contained, including when copied or opened offline.
            out.println("<image width='" + width + "' height='" + height + "' href='data:image/jpeg;base64,"
                    + jpeg(image) + "'/>");
            List<Rectangle2D.Double> occupied = new ArrayList<>();
            if (curves) {
                for (int i = 0; i < candidates.size(); i++) {
                    StarLightCurve star = candidates.get(i);
                    if (!onImage(star, width, height)) continue;
                    Rectangle2D.Double box = place(star, candidates, occupied, width, height);
                    occupied.add(box);
                    out.printf(Locale.US, "<path d='M%.2f %.2fL%.2f %.2f' stroke='%s' stroke-width='1.5' fill='none'/>%n",
                            star.x + .5, star.y + .5, Math.max(box.x, Math.min(star.x + .5, box.getMaxX())),
                            Math.max(box.y, Math.min(star.y + .5, box.getMaxY())), color(star));
                }
                int boxIndex = 0;
                for (int i = 0; i < candidates.size(); i++) {
                    StarLightCurve star = candidates.get(i);
                    if (onImage(star, width, height)) writeCurve(out, analysis, star, i + 1, names.get(i), occupied.get(boxIndex++));
                }
            }
            List<Rectangle2D.Double> labels = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                StarLightCurve star = candidates.get(i);
                if (!onImage(star, width, height)) continue;
                double radius = curves ? Math.max(6, Math.min(14, width / 500.0)) : Math.max(6, width / 140.0);
                double font = curves ? 16 : Math.max(12, width / 90.0);
                String labelText = names.get(i) == null ? FindingCategory.VARIABLE_STAR.id(i + 1) : names.get(i);
                font = Math.min(font, Math.max(9, width / (3.0 * .65 * labelText.length())));
                out.printf(Locale.US, "<g class='variable-map-marker' data-candidate='%s'><title>%s · %s · star #%d · %s</title>"
                                + "<circle cx='%.2f' cy='%.2f' r='%.2f' fill='none' stroke='#000' stroke-width='%s'/>"
                                + "<circle cx='%.2f' cy='%.2f' r='%.2f' fill='none' stroke='%s' stroke-width='%s'/>",
                        FindingCategory.VARIABLE_STAR.id(i + 1), FindingCategory.VARIABLE_STAR.id(i + 1), ReportFormat.escapeHtml(labelText), star.id, star.tier == VariabilityTier.HIGH_CONFIDENCE ? "High confidence" : "Possible",
                        star.x + .5, star.y + .5, radius, curves ? "4" : Math.max(3, width / 450.0),
                        star.x + .5, star.y + .5, radius, color(star), curves ? "2" : Math.max(2, width / 1000.0));
                Rectangle2D.Double label = placeLabel(star, labelText, radius, font, width, height, occupied, labels);
                labels.add(label);
                out.printf(Locale.US, "<text x='%.2f' y='%.2f' fill='%s' font-size='%.2f' stroke='#000' stroke-width='3' paint-order='stroke'>%s</text></g>%n",
                        label.x, label.y + font, color(star), font, ReportFormat.escapeHtml(labelText));
            }
            out.println("</g></svg>");
            artifact.commit();
        }
    }

    /** Try nearby positions, penalising covered candidates and overlapping chart boxes. */
    private static Rectangle2D.Double place(StarLightCurve star, List<StarLightCurve> stars,
                                             List<Rectangle2D.Double> occupied, int width, int height) {
        double inset = Math.min(6, Math.min(width, height) / 10.0);
        double w = Math.min(190, width - 2 * inset), h = Math.min(84, height - 2 * inset);
        Rectangle2D.Double best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int ring = 0; ring < 5; ring++) {
            double reach = 18 + ring * 65;
            double[][] offsets = {{reach, -h / 2}, {-w - reach, -h / 2}, {-w / 2, -h - reach}, {-w / 2, reach},
                    {reach, -h - reach}, {reach, reach}, {-w - reach, -h - reach}, {-w - reach, reach}};
            for (double[] offset : offsets) {
                Rectangle2D.Double box = new Rectangle2D.Double(
                        Math.max(inset, Math.min(width - w - inset, star.x + offset[0])),
                        Math.max(inset, Math.min(height - h - inset, star.y + offset[1])), w, h);
                double score = Math.hypot(box.getCenterX() - star.x, box.getCenterY() - star.y);
                for (Rectangle2D.Double other : occupied) {
                    Rectangle2D overlap = box.createIntersection(other);
                    if (!overlap.isEmpty()) score += overlap.getWidth() * overlap.getHeight() * 10;
                }
                for (StarLightCurve candidate : stars) {
                    if (box.intersects(candidate.x - 15, candidate.y - 15, 30, 30)) score += w * h;
                }
                if (score < bestScore) { best = box; bestScore = score; }
            }
        }
        return best;
    }

    /** Keep labels within the image and prefer a clear side of the star and its chart. */
    private static Rectangle2D.Double placeLabel(StarLightCurve star, String label, double radius, double font,
                                                 int width, int height, List<Rectangle2D.Double> charts,
                                                 List<Rectangle2D.Double> labels) {
        double w = font * .65 * label.length(), h = font * 1.2;
        double[][] positions = {{star.x + radius + 3, star.y - radius - h},
                {star.x + radius + 3, star.y + radius + 3},
                {star.x - radius - w - 3, star.y - radius - h},
                {star.x - radius - w - 3, star.y + radius + 3}};
        Rectangle2D.Double best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < positions.length; i++) {
            Rectangle2D.Double box = new Rectangle2D.Double(Math.max(2, Math.min(width - w - 2, positions[i][0])),
                    Math.max(2, Math.min(height - h - 2, positions[i][1])), w, h);
            double score = i;
            for (Rectangle2D.Double chart : charts) if (box.intersects(chart)) score += w * h;
            for (Rectangle2D.Double other : labels) if (box.intersects(other)) score += w * h;
            if (score < bestScore) { best = box; bestScore = score; }
        }
        return best;
    }

    private static void writeCurve(PrintWriter out, VariableStarAnalysis analysis, StarLightCurve star, int number,
                                    String name, Rectangle2D.Double box) {
        out.printf(Locale.US, "<g class='variable-map-curve' data-candidate='%s' data-star-id='%d' transform='translate(%.2f %.2f)'>%n",
                FindingCategory.VARIABLE_STAR.id(number), star.id, box.x, box.y);
        out.println("<title>" + FindingCategory.VARIABLE_STAR.id(number) + (name == null ? "" : " · " + ReportFormat.escapeHtml(name))
                + " · observed variation (95th–5th percentile); brighter up; individual magnitude scale</title>");
        out.printf(Locale.US, "<rect width='%.2f' height='%.2f' rx='4' fill='#000' fill-opacity='0.18'/>%n",
                box.width, box.height);
        out.printf(Locale.US, "<text x='6' y='15' fill='%s' font-size='12' stroke='#000' stroke-width='2' paint-order='stroke'>%s</text>%n",
                color(star), Double.isFinite(star.amplitude)
                        ? String.format(Locale.US, "Δmag %.3f mag", star.amplitude) : "Δmag unavailable");
        int n = analysis.frames.size();
        boolean timeAxis = n > 1 && analysis.frames.stream().allMatch(f -> Double.isFinite(f.julianDate))
                && analysis.frames.get(n - 1).julianDate > analysis.frames.get(0).julianDate;
        double firstJd = n == 0 ? 0 : analysis.frames.get(0).julianDate;
        double[] x = new double[n], y = new double[n];
        java.util.Arrays.fill(y, Double.NaN);
        int usable = 0;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (int j = 0; j < n; j++) {
            var frame = analysis.frames.get(j);
            x[j] = timeAxis ? (frame.julianDate - firstJd) * 1440 : frame.frameIndex + 1;
            if (star.flags != null && j < star.flags.length && star.flags[j] == 0
                    && star.deltaMag != null && j < star.deltaMag.length && Double.isFinite(star.deltaMag[j])) {
                y[j] = star.deltaMag[j];
                min = Math.min(min, y[j]);
                max = Math.max(max, y[j]);
                usable++;
            }
        }
        if (usable == 0) {
            text(out, 6, 36, "No usable measurements", "#cccccc", 11);
        } else {
            double span = Math.max(0.02, max - min), centre = (max + min) / 2;
            double low = centre - span * .6, range = span * 1.2;
            double xSpan = n > 1 ? x[n - 1] - x[0] : 0;
            double plotWidth = Math.max(1, box.width - 12), plotHeight = Math.max(1, box.height - 26);
            StringBuilder path = new StringBuilder();
            boolean connected = false;
            for (int j = 0; j < n; j++) {
                if (!Double.isFinite(y[j])) { connected = false; continue; }
                double px = 6 + (xSpan > 0 ? (x[j] - x[0]) / xSpan : .5) * plotWidth;
                double py = 21 + (y[j] - low) / range * plotHeight;
                path.append(String.format(Locale.US, "%s%.2f %.2f", connected ? "L" : "M", px, py));
                connected = true;
                out.printf(Locale.US, "<circle cx='%.2f' cy='%.2f' r='1.5' fill='%s'><title>%s · frame %d · Δmag %.4f</title></circle>%n",
                        px, py, color(star), FindingCategory.VARIABLE_STAR.id(number), analysis.frames.get(j).frameIndex + 1, y[j]);
            }
            out.printf(Locale.US, "<path class='variable-map-sparkline' d='%s' stroke='%s' stroke-width='1.5' fill='none'/>%n",
                    path, color(star));
        }
        out.println("</g>");
    }

    private static boolean onImage(StarLightCurve star, int width, int height) {
        return Double.isFinite(star.x) && Double.isFinite(star.y) && star.x >= 0 && star.y >= 0 && star.x < width && star.y < height;
    }

    private static String color(StarLightCurve star) {
        return star.tier == VariabilityTier.HIGH_CONFIDENCE ? PhotometrySvgChart.SERIES_BLUE : PhotometrySvgChart.SERIES_ORANGE;
    }

    private static void text(PrintWriter out, int x, int y, String value, String color, int size) {
        out.printf(Locale.US, "<text x='%d' y='%d' fill='%s' font-size='%d'>%s</text>%n",
                x, y, color, size, ReportFormat.escapeHtml(value));
    }

    private static String jpeg(BufferedImage image) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(.9f);
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally { writer.dispose(); }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
}
