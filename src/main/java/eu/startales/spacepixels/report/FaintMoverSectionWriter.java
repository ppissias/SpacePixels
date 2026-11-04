package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.DisplayImageRenderer;
import io.github.ppissias.jtransient.execution.CancellationScope;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Writes experimental fitted-path cards and streamed, fixed-stretch animations from the captured report inputs. */
final class FaintMoverSectionWriter {
    /** Prevents construction of this report section. */
    private FaintMoverSectionWriter() { }

    /** Presents every included FM candidate, preserving unavailable evidence and partial-search status. */
    static void writeSection(PrintWriter out, DetectionReportContext c) throws IOException {
        var telemetry = c.pipelineTelemetry == null ? null : c.pipelineTelemetry.faintMoverSearchTelemetry;
        if (telemetry == null) return;
        out.println("<h2>Faint mover candidates (experimental)</h2>");
        out.println("<p>Independent digital tracking. These fitted paths are unverified candidates; scores are empirical, "
                + "not calibrated significances. Positions along a fit are not individual frame detections.</p>");
        if (telemetry.skippedReason != null) {
            out.println("<p><strong>Search skipped:</strong> " + escape(telemetry.skippedReason) + "</p>");
            return;
        }
        out.printf(Locale.ROOT, "<p><strong>%s</strong>: %d of %d tiles; %,d searched pixels; %d frames over %.2f min "
                        + "(%d kept frames outside the searched session). Motion %.4f–%.4f px/min, %d coarse trials. "
                        + "FWHM %.2f px (%s); elapsed %.1f s.</p>%n",
                telemetry.truncated ? "Search truncated at its time limit" : "Search completed", telemetry.tilesSearched, telemetry.tiles,
                telemetry.searchedPixels, telemetry.frames, telemetry.sessionMinutes, telemetry.framesOutsideSession,
                telemetry.minSpeedPixelsPerMinute, telemetry.maxSpeedPixelsPerMinute, telemetry.coarseMotions,
                telemetry.fwhmPixels, telemetry.fwhmFromStars ? "measured" : "fallback", telemetry.elapsedMs / 1000.0);
        out.printf(Locale.ROOT, "<p>Candidate checks: %d below score, %d split-half, %d stationary, %d camera-fixed, "
                        + "%d duplicates, %d matching tracks, %d near stronger paths; %d candidates retained.</p>%n",
                telemetry.belowScore, telemetry.rejectedSplitHalf, telemetry.rejectedStationary, telemetry.followingCamera,
                telemetry.duplicates, telemetry.matchedTracks, telemetry.nearStronger, telemetry.candidates);
        var objects = c.runContext.solarSystem();
        if (objects == null) out.println("<p>No matching saved known-object predictions are available for this run.</p>");
        else out.printf(Locale.ROOT, "<p>Catalogue comparisons use the saved fetch to predicted magnitude %.2f, within %.2f px "
                        + "and 0.1 px/min. These are heuristic comparisons, not identity confirmations. "
                        + "An unmatched candidate is not evidence of a new object; fainter objects may be absent from the fetch.</p>%n",
                objects.magnitudeLimit, 2 * telemetry.fwhmPixels);
        if (objects != null && objects.problems != null && !objects.problems.isEmpty()) {
            out.println("<p><strong>Saved prediction fetch was incomplete:</strong> " + escape(String.join("; ", objects.problems)) + ".</p>");
        }
        out.println("<p><a href='faint_movers/faint_movers.csv'>Download candidate and fitted-position CSV</a></p>");
        try (var csv = new PrintWriter(c.file("faint_movers/faint_movers.csv"), java.nio.charset.StandardCharsets.UTF_8)) {
            csv.println("id,reference_time_utc,x,y,vx_px_min,vy_px_min,score,first_half_score,second_half_score,stationary_score,camera_score,ra_deg,dec_deg,speed_arcsec_min,position_angle_deg,possible_catalogue_matches,frame,midpoint_utc,fitted_x,fitted_y,fitted_ra_deg,fitted_dec_deg");
            c.progress.expect(c.faintMoverPresentation.size());
            for (int i = 0; i < c.faintMoverPresentation.size(); i++) {
                c.progress.next();
                CancellationScope.check();
                var entry = c.faintMoverPresentation.get(i);
                var candidate = c.faintMoverCandidates.get(i);
                String anchor = c.findingAnchor(FindingCategory.FAINT_MOVER, i + 1);
                out.println("<div class='detection-card' id='" + anchor + "' style='border-left-color:#50e1d2'>");
                out.println("<div class='detection-title'>Faint mover " + entry.id() + " · Unverified</div>");
                out.printf(Locale.ROOT, "<p>Reference: %s; position (%.2f, %.2f) px. Motion (%.4f, %.4f) px/min; speed %.4f px/min.</p>%n",
                        Instant.ofEpochMilli(entry.referenceTimeMillis()), entry.x(), entry.y(), entry.vxPixelsPerMinute(), entry.vyPixelsPerMinute(), entry.speedPixelsPerMinute());
                out.printf(Locale.ROOT, "<p>Score %s; first / second half %s / %s; stationary %s; camera-fixed %s. "
                                + "Unavailable half scores mean insufficient unmasked support; unavailable camera evidence means no usable registration.</p>%n",
                        number(entry.score()), number(entry.firstHalfScore()), number(entry.secondHalfScore()), number(entry.stationaryScore()), number(entry.cameraScore()));
                out.println("<p>RA / Dec: " + number(entry.sky().raDegrees()) + " / " + number(entry.sky().decDegrees())
                        + " deg; sky speed " + number(entry.sky().speedArcsecPerMinute()) + " arcsec/min; position angle "
                        + number(entry.sky().positionAngleDegrees()) + " deg east of north.</p>");
                String comparisons = entry.catalogueComparisons().stream().map(FaintMoverPresentation.Match::name).collect(java.util.stream.Collectors.joining("; "));
                out.println("<p>Possible catalogue matches: " + (!entry.catalogueComparisonAvailable()
                        ? "unavailable for this field or reference time" : comparisons.isEmpty()
                        ? "none within the comparison tolerances" : escape(comparisons)) + ".</p>");
                for (var match : entry.catalogueComparisons()) out.printf(Locale.ROOT,
                        "<p>%s: position residual %.3f px; motion residual %.4f px/min; predicted magnitude %s.</p>%n",
                        escape(match.name()), match.positionResidualPixels(), match.motionResidualPixelsPerMinute(), number(match.predictedMagnitude()));
                ImageIO.write(DisplayImageRenderer.createAsinhDisplayImage(candidate.cutout), "png", c.file(entry.cutout()));
                out.println("<div class='image-container'><div><img src='" + entry.cutout() + "' width='248' height='248' "
                        + "alt='" + entry.id() + " PSF-filtered shift stack' style='image-rendering:pixelated'><p>31 × 31 px shift stack; score units, asinh display.</p></div>");
                if (!entry.fittedPositions().isEmpty()) {
                    writeBlink(c, entry);
                    out.println("<div><img src='" + entry.blink() + "' style='width:248px;max-width:100%;height:auto;image-rendering:pixelated' alt='" + entry.id() + " source-frame blink with fitted path'>"
                            + "<p>Searched source frames, shared display levels; dashed line and ring show the fitted path.</p></div>");
                }
                out.println("</div></div>");
                var positions = entry.fittedPositions().isEmpty() ? java.util.Collections.<FaintMoverPresentation.Position>singletonList(null) : entry.fittedPositions();
                for (var p : positions) {
                    csv.printf(Locale.ROOT, "%s,%s,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            entry.id(), Instant.ofEpochMilli(entry.referenceTimeMillis()), entry.x(), entry.y(), entry.vxPixelsPerMinute(), entry.vyPixelsPerMinute(),
                            cell(entry.score()), cell(entry.firstHalfScore()), cell(entry.secondHalfScore()), cell(entry.stationaryScore()), cell(entry.cameraScore()),
                            cell(entry.sky().raDegrees()), cell(entry.sky().decDegrees()), cell(entry.sky().speedArcsecPerMinute()), cell(entry.sky().positionAngleDegrees()),
                            "\"" + comparisons.replace("\"", "\"\"") + "\"", p == null ? "" : p.frameNumber(), p == null ? "" : Instant.ofEpochMilli(p.midpointMillis()),
                            p == null ? "" : cell(p.x()), p == null ? "" : cell(p.y()), p == null ? "" : cell(p.raDegrees()), p == null ? "" : cell(p.decDegrees()));
                }
            }
        }
    }

    /** Streams one source-frame crop at a time, using one shared stretch for a faithful blink. */
    private static void writeBlink(DetectionReportContext c, FaintMoverPresentation.Entry entry) throws IOException {
        int height = c.rawFrames.getFirst().length, width = c.rawFrames.getFirst()[0].length;
        double minX = entry.x(), maxX = minX, minY = entry.y(), maxY = minY;
        for (var p : entry.fittedPositions()) { minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x()); minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y()); }
        int x = Math.max(0, (int) Math.floor(minX - 35)), y = Math.max(0, (int) Math.floor(minY - 35));
        int w = Math.max(1, Math.min(width, (int) Math.ceil(maxX + 36)) - x);
        int h = Math.max(1, Math.min(height, (int) Math.ceil(maxY + 36)) - y);
        Iterable<short[][]> crops = () -> entry.fittedPositions().stream().map(p -> crop(c.rawFrames.get(p.frameNumber() - 1), x, y, w, h)).iterator();
        double[] levels = DisplayImageRenderer.sharedDisplayLevels(crops, 3, 12);
        final var first = entry.fittedPositions().getFirst();
        final var last = entry.fittedPositions().getLast();
        var frames = entry.fittedPositions().stream().map(p -> {
            CancellationScope.check();
            var image = DisplayImageRenderer.createDisplayImageAtLevels(crop(c.rawFrames.get(p.frameNumber() - 1), x, y, w, h), levels[0], levels[1]);
            var rgb = new BufferedImage(w, h + 22, BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            try {
                g.drawImage(image, 0, 22, null);
                g.setColor(new Color(80, 225, 210));
                g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{3, 3}, 0));
                g.draw(new java.awt.geom.Line2D.Double(first.x() - x, first.y() - y + 22, last.x() - x, last.y() - y + 22));
                g.draw(new Ellipse2D.Double(p.x() - x - 6, p.y() - y + 16, 12, 12));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.drawString(entry.id() + " · frame " + p.frameNumber(), 3, 15);
            } finally { g.dispose(); }
            return rgb;
        }).iterator();
        GifSequenceWriter.saveAnimatedGif(frames, c.file(entry.blink()), c.settings.getGifBlinkSpeedMs(), c.animations);
    }

    /** Copies a bounded rectangle from a borrowed signed-short image without changing the source. */
    private static short[][] crop(short[][] raw, int x, int y, int width, int height) {
        short[][] out = new short[height][width];
        for (int row = 0; row < height; row++) System.arraycopy(raw[y + row], x, out[row], 0, width);
        return out;
    }

    /** Formats optional evidence for readable cards. */
    private static String number(Double value) { return value == null || !Double.isFinite(value) ? "N/A" : String.format(Locale.ROOT, "%.4f", value); }
    /** Formats optional evidence as an empty CSV cell when unavailable. */
    private static String cell(Double value) { return value == null || !Double.isFinite(value) ? "" : Double.toString(value); }
    /** Escapes text from catalogues and engine diagnostics before it enters HTML. */
    private static String escape(String text) { return DetectionReportAstrometry.escapeHtml(text); }
}
