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
        String speed = eu.startales.spacepixels.detection.FaintMoverRuntimeEstimate.SPEED_NAMES[Math.max(0, Math.min(2, telemetry.searchSpeed))];
        if (telemetry.coarseBin > 1 || telemetry.coarseFrames < telemetry.frames) out.printf(Locale.ROOT,
                "<p>Search speed: %s. The first pass added %s%s. Every peak was refined and checked on all %d frames "
                        + "at full resolution.</p>%n", speed,
                telemetry.coarseBin > 1 ? telemetry.coarseBin + "×" + telemetry.coarseBin + " pixels" : "",
                telemetry.coarseFrames < telemetry.frames ? (telemetry.coarseBin > 1 ? " and " : "")
                        + "consecutive frames into " + telemetry.coarseFrames + " frames" : "",
                telemetry.frames);
        else out.printf(Locale.ROOT, "<p>Search speed: %s. The first pass used every pixel of every frame.</p>%n", speed);
        Double angularScale = FaintMoverPresentation.angularPixelScale(c);
        if (angularScale != null) out.printf(Locale.ROOT,
                "<p>Searched angular motion range: %.5g–%.5g arcsec/min (field-centre WCS equivalent). "
                        + "This uses the completed search's retained session and seeing above.</p>%n",
                telemetry.minSpeedPixelsPerMinute * angularScale, telemetry.maxSpeedPixelsPerMinute * angularScale);
        out.printf(Locale.ROOT, "<details><summary>Search checks and diagnostic coverage</summary><p>Candidate checks: %d below score, %d split-half, %d few frames, %d stationary, %d camera-fixed, "
                        + "%d duplicates, %d matching tracks, %d near stronger paths; %d candidates retained.</p>%n",
                telemetry.belowScore, telemetry.rejectedSplitHalf, telemetry.rejectedFewFrames, telemetry.rejectedStationary, telemetry.followingCamera,
                telemetry.duplicates, telemetry.matchedTracks, telemetry.nearStronger, telemetry.candidates);
        out.printf(Locale.ROOT, "<p>Bounded residual/support evidence: %d / %d candidates, %.2f MiB. "
                        + "Resource-limited evidence is labelled on each card; it does not change candidate selection.</p></details>%n",
                telemetry.diagnosticsAvailable, telemetry.candidates, telemetry.diagnosticBytes / (1024.0 * 1024));
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
        int limit = c.settings.getMaxCategoryItems();
        int total = c.faintMoverPresentation.size();
        int cardCount = (limit > 0 && total > limit) ? limit : total;
        if (cardCount < total) {
            out.printf(Locale.US, "<p class='compact-note'>Showing the first %d of %d faint-mover candidate cards (limited by report configuration). All %d are recorded in the CSV and data.</p>%n", cardCount, total, total);
        }
        try (var csv = new PrintWriter(c.file("faint_movers/faint_movers.csv"), java.nio.charset.StandardCharsets.UTF_8)) {
            csv.println("id,reference_time_utc,x,y,vx_px_min,vy_px_min,score,first_half_score,second_half_score,stationary_score,camera_score,ra_deg,dec_deg,speed_arcsec_min,position_angle_deg,possible_catalogue_matches,frame,midpoint_utc,fitted_x,fitted_y,fitted_ra_deg,fitted_dec_deg,scoring_sample_x,scoring_sample_y,centre_unmasked,processed_residual_signal");
            c.progress.expect(c.faintMoverPresentation.size());
            for (int i = 0; i < c.faintMoverPresentation.size(); i++) {
                c.progress.next();
                CancellationScope.check();
                var entry = c.faintMoverPresentation.get(i);
                var candidate = c.faintMoverCandidates.get(i);
                String comparisons = entry.catalogueComparisons().stream().map(FaintMoverPresentation.Match::name).collect(java.util.stream.Collectors.joining("; "));
                if (i < cardCount) {
                    String anchor = c.findingAnchor(FindingCategory.FAINT_MOVER, i + 1);
                    out.println("<div class='detection-card' id='" + anchor + "' style='border-left-color:var(--sp-finding-fm)'>");
                    out.println("<div class='detection-title'>Faint mover " + entry.id() + " · Unverified</div>");
                    KnownSolarSystemReport.writeFaintMoverIdentification(out, c, entry);
                    writeSummary(out, c, entry);
                    if (entry.catalogueComparisons().isEmpty()) out.println("<p>Possible catalogue matches: " + (!entry.catalogueComparisonAvailable()
                            ? "unavailable for this field or reference time" : comparisons.isEmpty()
                            ? "none within the comparison tolerances" : escape(comparisons)) + ".</p>");
                    if (!entry.catalogueComparisons().isEmpty()) {
                        out.println("<details><summary>Catalogue position and motion comparisons</summary><table><thead><tr><th>Possible match</th><th>Path RMS (px)</th><th>Motion residual (px/min)</th><th>Predicted magnitude</th></tr></thead><tbody>");
                        for (var match : entry.catalogueComparisons()) out.printf(Locale.ROOT,
                                "<tr><td>%s</td><td>%.3f</td><td>%.4f</td><td>%s</td></tr>%n",
                                escape(match.name()), match.rmsPixels(), match.motionResidualPixelsPerMinute(), number(match.predictedMagnitude()));
                        out.println("</tbody></table>");
                        for (var match : entry.catalogueComparisons()) {
                            out.println("<p>" + escape(match.name()) + "</p>");
                            KnownSolarSystemReport.writeFaintComparisonDetails(out, match);
                        }
                        out.println("</details>");
                    }
                    if (entry.blink() != null) writeBlink(c, entry);
                    FaintMoverReviewWriter.write(out, c, entry, candidate);
                    out.print(DetectionReportAstrometry.buildFaintMoverIdentificationHtml(c.astrometryContext, entry));
                    out.println("</div>");
                }
                var positions = entry.fittedPositions().isEmpty() ? java.util.Collections.<FaintMoverPresentation.Position>singletonList(null) : entry.fittedPositions();
                for (var p : positions) {
                    var sample = p == null ? null : entry.review().pathSamples().stream().filter(s -> s.frameNumber() == p.frameNumber()).findFirst().orElse(null);
                    csv.printf(Locale.ROOT, "%s,%s,%.6f,%.6f,%.6f,%.6f,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s%n",
                            entry.id(), Instant.ofEpochMilli(entry.referenceTimeMillis()), entry.x(), entry.y(), entry.vxPixelsPerMinute(), entry.vyPixelsPerMinute(),
                            cell(entry.score()), cell(entry.firstHalfScore()), cell(entry.secondHalfScore()), cell(entry.stationaryScore()), cell(entry.cameraScore()),
                            cell(entry.sky().raDegrees()), cell(entry.sky().decDegrees()), cell(entry.sky().speedArcsecPerMinute()), cell(entry.sky().positionAngleDegrees()),
                            "\"" + comparisons.replace("\"", "\"\"") + "\"", p == null ? "" : p.frameNumber(), p == null ? "" : Instant.ofEpochMilli(p.midpointMillis()),
                            p == null ? "" : cell(p.x()), p == null ? "" : cell(p.y()), p == null ? "" : cell(p.raDegrees()), p == null ? "" : cell(p.decDegrees()),
                            sample == null ? "" : sample.sampleX(), sample == null ? "" : sample.sampleY(), sample == null ? "" : sample.unmasked(),
                            sample == null ? "" : cell(sample.processedSignal()));
                }
            }
        }
    }

    /** Presents fitted motion and existing scores with the same summary and coordinate conventions as other cards. */
    private static void writeSummary(PrintWriter out, DetectionReportContext c, FaintMoverPresentation.Entry e) {
        var t = c.pipelineTelemetry.faintMoverSearchTelemetry;
        var first = e.fittedPositions().stream().min(java.util.Comparator.comparingLong(FaintMoverPresentation.Position::midpointMillis));
        var last = e.fittedPositions().stream().max(java.util.Comparator.comparingLong(FaintMoverPresentation.Position::midpointMillis));
        Double minutes = first.isPresent() && last.isPresent() ? (last.get().midpointMillis() - first.get().midpointMillis()) / 60000.0 : null;
        double exposure = e.fittedPositions().stream().mapToLong(p -> c.fitsFiles[p.frameNumber() - 1].getExposureDurationMillis())
                .filter(v -> v > 0).average().orElse(Double.NaN) / 60000;
        out.println("<dl class='anomaly-summary fm-summary'>");
        metric(out, "Whole-stack score", number(e.score()));
        metric(out, "Sky speed", number(e.sky().speedArcsecPerMinute()) + " arcsec/min");
        metric(out, "Position angle", number(e.sky().positionAngleDegrees()) + "° E of N");
        metric(out, "Pixel speed", number(e.speedPixelsPerMinute()) + " px/min");
        metric(out, "Fitted displacement", number(minutes == null ? null : minutes * e.speedPixelsPerMinute()) + " px");
        metric(out, "Motion / mean exposure", number(exposure * e.speedPixelsPerMinute()) + " px");
        metric(out, "Searched frames", Integer.toString(e.fittedPositions().size()));
        metric(out, "Unmasked path centres", e.review().unmaskedFrames() == null ? "N/A" : e.review().unmaskedFrames() + " / " + e.fittedPositions().size());
        metric(out, "Search coverage", t.tilesSearched + " / " + t.tiles + " tiles · " + (t.truncated ? "partial" : "complete"));
        out.println("</dl><div class='coord-line'>" + escape(DetectionReportAstrometry.formatPixelCoordinateWithSky(c.astrometryContext, e.x(), e.y()))
                + " · reference " + Instant.ofEpochMilli(e.referenceTimeMillis()) + "</div>");
        out.println("<details><summary>Scores, timing and fitted motion</summary><dl class='anomaly-summary fm-summary'>");
        metric(out, "First / second half", number(e.firstHalfScore()) + " / " + number(e.secondHalfScore()));
        metric(out, "Stationary score", number(e.stationaryScore()));
        metric(out, "Camera-fixed score", number(e.cameraScore()));
        metric(out, "Motion minus stationary", number(e.stationaryScore() == null ? null : e.score() - e.stationaryScore()));
        metric(out, "Motion minus camera", number(e.cameraScore() == null ? null : e.score() - e.cameraScore()));
        metric(out, "vx / vy (px/min)", number(e.vxPixelsPerMinute()) + " / " + number(e.vyPixelsPerMinute()));
        metric(out, "First / last midpoint", first.map(p -> Instant.ofEpochMilli(p.midpointMillis()).toString()).orElse("N/A")
                + " / " + last.map(p -> Instant.ofEpochMilli(p.midpointMillis()).toString()).orElse("N/A"));
        out.println("</dl><p>Unavailable half scores mean insufficient unmasked support; unavailable camera evidence means no usable registration. "
                + "The paths use exposure midpoints. Image coordinates increase rightwards and downwards.</p></details>");
    }

    /** Escapes both parts of one compact evidence row. */
    private static void metric(PrintWriter out, String title, String value) {
        out.println("<div><dt>" + escape(title) + "</dt><dd>" + escape(value) + "</dd></div>");
    }

    /** Streams one source-frame crop at a time, using one shared stretch for a faithful blink. */
    private static void writeBlink(DetectionReportContext c, FaintMoverPresentation.Entry entry) throws IOException {
        var review = entry.review();
        int w = review.width(), h = review.height();
        var crops = FaintMoverReviewWriter.crops(c, entry);
        double[] levels = DisplayImageRenderer.sharedDisplayLevels(crops, c.settings.getAutoStretchBlackSigma(), c.settings.getAutoStretchWhiteSigma());
        final var first = entry.fittedPositions().getFirst();
        final var last = entry.fittedPositions().getLast();
        var source = crops.iterator();
        var frames = java.util.stream.IntStream.range(0, entry.fittedPositions().size()).mapToObj(k -> {
            CancellationScope.check();
            var p = entry.fittedPositions().get(k);
            int x = review.origin(k).x(), y = review.origin(k).y();
            var image = DisplayImageRenderer.createDisplayImageAtLevels(source.next(), levels[0], levels[1]);
            var rgb = new BufferedImage(w, h + 22, BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            try {
                g.drawImage(image, 0, 22, null);
                g.setColor(new Color(80, 225, 210));
                g.setStroke(new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{3, 3}, 0));
                // A path-following cutout shows only part of the path; keep it off the label strip.
                g.setClip(0, 22, w, h);
                g.draw(new java.awt.geom.Line2D.Double(first.x() - x, first.y() - y + 22, last.x() - x, last.y() - y + 22));
                g.draw(new Ellipse2D.Double(p.x() - x - 6, p.y() - y + 16, 12, 12));
                g.setClip(null);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                // A path-following cutout is too narrow for the identity; its card names it.
                g.drawString((review.followsPath() ? "" : entry.id() + " · ") + "frame " + p.frameNumber(), 3, 15);
            } finally { g.dispose(); }
            return rgb;
        }).iterator();
        GifSequenceWriter.saveAnimatedGif(frames, c.file(entry.blink()), c.settings.getGifBlinkSpeedMs(), c.animations);
    }

    /** Formats optional evidence for readable cards. */
    private static String number(Double value) { return value == null || !Double.isFinite(value) ? "N/A" : String.format(Locale.ROOT, "%.4f", value); }
    /** Formats optional evidence as an empty CSV cell when unavailable. */
    private static String cell(Double value) { return value == null || !Double.isFinite(value) ? "" : Double.toString(value); }
    /** Escapes text from catalogues and engine diagnostics before it enters HTML. */
    private static String escape(String text) { return DetectionReportAstrometry.escapeHtml(text); }
}
