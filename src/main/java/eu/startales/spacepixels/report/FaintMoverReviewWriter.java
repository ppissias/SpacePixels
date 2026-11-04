package eu.startales.spacepixels.report;

import com.google.gson.Gson;
import eu.startales.spacepixels.fits.DisplayImageRenderer;
import io.github.ppissias.jtransient.core.FaintMoverCandidate;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.execution.CancellationScope;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;

/** Exports bounded native-intensity cutouts and an offline review surface without retaining full input frames. */
final class FaintMoverReviewWriter {
    /** Prevents construction of this report rendering utility. */
    private FaintMoverReviewWriter() { }

    /** Embeds styles only in reports containing the new review surface. */
    static void appendStyle(PrintWriter out) throws IOException { appendResource(out, "faint-mover-review.css", "style"); }

    /** Installs the offline viewer after the shared report animation controller. */
    static void appendScript(PrintWriter out) throws IOException { appendResource(out, "faint-mover-review.js", "script"); }

    /** Reads a packaged, local-only presentation resource into the standalone report. */
    private static void appendResource(PrintWriter out, String name, String tag) throws IOException {
        try (var stream = FaintMoverReviewWriter.class.getResourceAsStream(name)) {
            if (stream == null) throw new IOException("Missing faint-mover review resource: " + name);
            out.println("<" + tag + ">" + new String(stream.readAllBytes(), StandardCharsets.UTF_8) + "</" + tag + ">");
        }
    }

    /** Writes the candidate's small assets and accessible fallback, then supplies controls for progressive enhancement. */
    static void write(PrintWriter out, DetectionReportContext c, FaintMoverPresentation.Entry entry,
                      FaintMoverCandidate candidate) throws IOException {
        var review = entry.review();
        double scale = maximum(candidate.cutout);
        if (review.firstHalfStack() != null) scale = Math.max(scale, maximum(candidate.diagnostics.firstHalf));
        if (review.secondHalfStack() != null) scale = Math.max(scale, maximum(candidate.diagnostics.secondHalf));
        writeScore(candidate.cutout, scale, c.file(entry.cutout()));
        if (review.firstHalfStack() != null) writeScore(candidate.diagnostics.firstHalf, scale, c.file(review.firstHalfStack()));
        if (review.secondHalfStack() != null) writeScore(candidate.diagnostics.secondHalf, scale, c.file(review.secondHalfStack()));
        if (review.data() != null) writeData(c, entry, candidate);
        out.println("<div class='fm-review' data-fm-id='" + entry.id() + "'"
                + (review.data() == null ? "" : " data-fm-src='" + review.data() + "'") + ">");
        if (review.data() != null) {
            out.println("<div class='fm-controls' hidden>"
                    + "<button type='button' data-fm-action='pause'>Pause</button>"
                    + "<button type='button' data-fm-action='previous' aria-label='Previous source frame'>‹ Frame</button>"
                    + "<button type='button' data-fm-action='next' aria-label='Next source frame'>Frame ›</button>"
                    + "<label>Frame <input data-fm-frame type='range' min='0' max='" + (entry.fittedPositions().size() - 1) + "' value='0'></label>"
                    + "<label>Source stretch <select data-fm-stretch><option value='normal'>Normal (report settings)</option>"
                    + "<option value='strong'>Strong asinh</option><option value='custom'>Adjustable asinh</option></select></label>"
                    + "<label>Black (sky σ) <input data-fm-black type='range' min='0' max='6' step='0.25' value='2' disabled></label>"
                    + "<label>White (sky σ) <input data-fm-white type='range' min='1' max='30' step='0.25' value='6' disabled></label>"
                    + "<label>Asinh strength <input data-fm-strength type='range' min='1' max='100' value='5' disabled></label>"
                    + "<label>Residual range ±<input data-fm-residual-range type='range' min='0.5' max='8' step='0.25' value='3'> units</label>"
                    + "<label>Zoom <select data-fm-zoom><option value='1'>1×</option><option value='2'>2×</option><option value='4'>4×</option></select></label>"
                    + "<label><input data-fm-annotations type='checkbox' checked>Fitted path</label>"
                    + "<label><input data-fm-centred type='checkbox'>Motion-centred</label></div>");
        }
        out.println("<p class='fm-frame-label' aria-live='off'></p><div class='fm-review-grid'>");
        if (entry.blink() != null) {
            out.println("<figure><div class='fm-viewport'><img class='fm-fallback' src='" + entry.blink()
                    + ".poster.png' alt='" + entry.id() + " source frame; open the GIF for a blink without JavaScript'>"
                    + "<canvas data-fm-source hidden role='img' aria-label='" + entry.id() + " searched source-frame blink'></canvas></div>"
                    + "<figcaption>Searched source frames · unsigned 16-bit intensities<br><a href='" + entry.blink()
                    + "'>Open / save source GIF</a></figcaption></figure>");
        } else out.println("<p>Source review unavailable: " + escape(review.unavailableReason()) + ".</p>");
        if (review.data() != null && review.residualUnavailableReason() == null) {
            out.println("<figure class='fm-enhancement' hidden><div class='fm-viewport'><canvas data-fm-residual role='img' "
                    + "aria-label='" + entry.id() + " synchronized processed residual blink'></canvas></div><figcaption>Static-reference-subtracted residuals<br>"
                    + "Local background/noise normalisation, clipping, masks and PSF filtering from the search</figcaption></figure>");
        } else out.println("<p class='fm-missing'>Residual / support evidence unavailable: "
                + escape(review.residualUnavailableReason() == null ? review.unavailableReason() : review.residualUnavailableReason()) + ".</p>");
        scoreFigure(out, entry.cutout(), "Whole-session shift stack", "All searched frames; score units");
        out.println("</div><p class='fm-mapping'></p>");
        out.printf(Locale.ROOT, "<p class='fm-note'>Stack panels share a signed asinh mapping from −%.2f to +%.2f score units; "
                + "31 × 31 px around the integer scoring position. These processed views do not measure magnitude.</p>%n", scale, scale);
        if (review.firstHalfStack() != null || review.secondHalfStack() != null) {
            out.println("<details><summary>First / second half stacks</summary><div class='fm-review-grid'>");
            if (review.firstHalfStack() != null) scoreFigure(out, review.firstHalfStack(), "First half (time order)", "Same score and display scale as the whole stack");
            else out.println("<p>First half: insufficient unmasked support.</p>");
            if (review.secondHalfStack() != null) scoreFigure(out, review.secondHalfStack(), "Second half (time order)", "Same score and display scale as the whole stack");
            else out.println("<p>Second half: insufficient unmasked support.</p>");
            out.println("</div><p class='fm-note'>Half scores are the maximum within the central refinement area, with each half's mask-support normalisation.</p></details>");
        }
        if (review.residualUnavailableReason() == null && review.data() != null) {
            out.println("<details class='fm-enhancement' hidden><summary>Signal and mask support along the scoring path</summary>"
                    + "<p>Samples at the search's rounded pixel shifts, before the subpixel position refinement. "
                    + "Unmasked support is an actual centre-mask test; it is not a count of individual detections. "
                    + "Signal is a locally normalised, PSF-filtered residual, not flux, S/N or an uncertainty.</p>"
                    + "<svg data-fm-signal viewBox='0 0 640 150' role='img' aria-label='Processed residual signal versus searched frame'></svg>"
                    + "<div class='scroll-box'><table><thead><tr><th>Frame</th><th>Midpoint UTC</th><th>Sample x, y (px)</th>"
                    + "<th>Centre unmasked</th><th>Processed signal</th></tr></thead><tbody>");
            for (var f : candidate.diagnostics.frames) out.printf(Locale.ROOT,
                    "<tr><td>%d</td><td>%s</td><td>%d, %d</td><td>%s</td><td>%.4f</td></tr>%n", f.sequenceIndex + 1,
                    java.time.Instant.ofEpochMilli(f.midpointMillis), f.sampleX, f.sampleY, f.unmasked ? "yes" : "masked", f.signal);
            out.println("</tbody></table></div></details>");
        }
        out.println("</div>");
    }

    /** Writes portable little-endian binary samples in an external classic script, usable from file:// without fetch. */
    private static void writeData(DetectionReportContext c, FaintMoverPresentation.Entry entry,
                                  FaintMoverCandidate candidate) throws IOException {
        var r = entry.review();
        int pixels = r.width() * r.height(), count = entry.fittedPositions().size();
        var source = ByteBuffer.allocate(2 * pixels * count).order(ByteOrder.LITTLE_ENDIAN);
        boolean residualAvailable = r.residualUnavailableReason() == null;
        var residual = residualAvailable ? ByteBuffer.allocate(4 * pixels * count).order(ByteOrder.LITTLE_ENDIAN) : null;
        var frameData = new ArrayList<Map<String, Object>>();
        for (int k = 0; k < count; k++) {
            CancellationScope.check();
            var p = entry.fittedPositions().get(k);
            var raw = c.rawFrames.get(p.frameNumber() - 1);
            for (int y = 0; y < r.height(); y++) for (int x = 0; x < r.width(); x++)
                source.putShort((short) (raw[r.y() + y][r.x() + x] + 32768));
            Map<String, Object> frame = new LinkedHashMap<>();
            frame.put("number", p.frameNumber()); frame.put("midpoint", java.time.Instant.ofEpochMilli(p.midpointMillis()).toString());
            frame.put("x", p.x() - r.x()); frame.put("y", p.y() - r.y());
            if (residualAvailable) {
                var f = candidate.diagnostics.frames.get(k);
                for (float value : f.residual) residual.putFloat(value);
                frame.put("unmasked", f.unmasked); frame.put("signal", f.signal);
            }
            frameData.add(frame);
        }
        Iterable<short[][]> crops = () -> entry.fittedPositions().stream()
                .map(p -> crop(c.rawFrames.get(p.frameNumber() - 1), r.x(), r.y(), r.width(), r.height())).iterator();
        var stats = DisplayImageRenderer.sharedDisplayStatistics(crops);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1); data.put("width", r.width()); data.put("height", r.height());
        data.put("frames", frameData); data.put("source", Base64.getEncoder().encodeToString(source.array()));
        if (residual != null) data.put("residual", Base64.getEncoder().encodeToString(residual.array()));
        data.put("stats", stats); data.put("fillMaximum", DataArea.FILL_LEVEL);
        data.put("normalBlackSigma", c.settings.getAutoStretchBlackSigma());
        data.put("normalWhiteSigma", c.settings.getAutoStretchWhiteSigma());
        data.put("delayMs", c.settings.getGifBlinkSpeedMs());
        Files.writeString(c.file(r.data()).toPath(), "globalThis.SpacePixelsFaintMovers.accept(\"" + entry.id() + "\","
                + new Gson().toJson(data) + ");", StandardCharsets.UTF_8);
    }

    /** Copies one small signed-short source rectangle; the source is borrowed and unchanged. */
    static short[][] crop(short[][] raw, int x, int y, int width, int height) {
        short[][] out = new short[height][width];
        for (int row = 0; row < height; row++) System.arraycopy(raw[y + row], x, out[row], 0, width);
        return out;
    }

    /** Writes an image-only score panel with a separate, toggleable fitted-position reticle. */
    private static void scoreFigure(PrintWriter out, String path, String title, String caption) {
        out.println("<figure><div class='fm-viewport fm-stack'><img src='" + path + "' alt='" + title
                + "'><span class='fm-reticle' aria-hidden='true'>+</span></div><figcaption>" + title + "<br>" + caption + "</figcaption></figure>");
    }

    /** Finds a finite common absolute score limit for the related stack panels. */
    private static double maximum(float[][] values) {
        double max = 1;
        for (var row : values) for (float value : row) if (Float.isFinite(value)) max = Math.max(max, Math.abs(value));
        return max;
    }

    /** Renders signed score pixels with one explicit shared mapping, without altering the engine's cutouts. */
    private static void writeScore(float[][] values, double scale, java.io.File target) throws IOException {
        var image = new BufferedImage(values[0].length, values.length, BufferedImage.TYPE_BYTE_GRAY);
        double denominator = Math.log(20 + Math.sqrt(401));
        for (int y = 0; y < values.length; y++) for (int x = 0; x < values[y].length; x++) {
            double v = Float.isFinite(values[y][x]) ? 20 * values[y][x] / scale : 0;
            double mapped = 0.5 + 0.5 * Math.copySign(Math.log(Math.abs(v) + Math.sqrt(v * v + 1)), v) / denominator;
            image.getRaster().setSample(x, y, 0, (int) Math.round(255 * Math.max(0, Math.min(1, mapped))));
        }
        ImageIO.write(image, "png", target);
    }

    /** Escapes engine reasons before rendering them as report text. */
    private static String escape(String text) { return DetectionReportAstrometry.escapeHtml(text == null ? "not available" : text); }
}
