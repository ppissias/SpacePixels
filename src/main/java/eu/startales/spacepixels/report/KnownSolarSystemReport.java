package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import eu.startales.spacepixels.util.SkyMath;

import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Captured catalogue coverage and descriptive ordinary-track comparisons; never identifies, seeds or changes detections. */
final class KnownSolarSystemReport {
    /** An actual centroid with its unchanged midpoint and nominal/trial prediction time; coordinates and distances are pixels. */
    record Residual(int frameNumber, long midpointMillis, long predictionTimeMillis, double observedX, double observedY,
                    double predictedX, double predictedY, double distancePixels, Double ephemerisThreeSigmaPixels) { }
    /** A multi-frame comparison; uncertainty availability covers nominal and any accepted time-shift comparison. */
    record Comparison(String trackId, int frames, double rmsPixels, double maximumPixels, double motionDifferencePixelsPerMinute,
                      double motionTolerancePixelsPerMinute, double reviewTolerancePixels, boolean ephemerisUncertaintyAvailable,
                      boolean positionAgreement, Double suggestedTimeOffsetSeconds, Double timeToleranceSeconds,
                      int shiftedFrames, Double shiftedRmsPixels, Double shiftedMaximumPixels,
                      List<Residual> residuals, List<Residual> shiftedResiduals) { }
    /** Predicted field/path inventory and every compatible visible finding; absence is not a completeness measurement. */
    record Body(String name, boolean comet, String orbitSolution, Double minimumMagnitude, Double maximumMagnitude,
                int predictedFrames, int inFieldFrames, int usablePathFrames, int starMaskFrames,
                List<Comparison> trackComparisons, List<String> faintMoverComparisons, String informationUrl,
                Double maximumEphemerisThreeSigmaPixels, boolean ephemerisUncertaintyTooBroad) { }
    /** Common time-offset evidence from at least two independent, unambiguous objects; never a correction command. */
    record Clock(String status, Double suggestedOffsetSeconds, Double minimumSeconds, Double maximumSeconds,
                 int independentObjects, String explanation) { }
    /** Buffer-free offline provenance, coverage and comparisons from the admitted run's catalogue/WCS snapshot. */
    record Summary(String source, String fetchedAtUtc, Double magnitudeLimit, long startMillis, long endMillis,
                   List<String> problems, List<String> imageFilters, String faintSearchStatus, double fieldDiagonalPixels,
                   List<Body> bodies, Clock clock) { }

    /** Static report calculations retain no live settings or source buffers. */
    private KnownSolarSystemReport() { }

    /** Builds the whole comparison once using saved predictions, actual measured tracks and admitted metadata only. */
    static Summary capture(DetectionReportContext c) {
        var catalogue = c.runContext.solarSystem();
        var wcs = c.astrometryContext == null ? null : c.astrometryContext.getTransformer();
        if (catalogue == null || catalogue.bodies == null || wcs == null) return null;
        int[] dimensions = c.frameDimensions();
        double fieldDiagonal = Math.hypot(dimensions[0], dimensions[1]);
        var rejectedFrames = new java.util.HashSet<>(UnclassifiedTransientSectionWriter.rejectedFrameIndices(c.pipelineTelemetry));
        if (c.pipelineTelemetry != null) for (var f : c.pipelineTelemetry.frameQualityStats) if (f.rejected) rejectedFrames.add(f.frameIndex);
        var bodies = new ArrayList<Body>();
        for (var body : catalogue.bodies) {
            io.github.ppissias.jtransient.execution.CancellationScope.check();
            if (body == null || body.samples == null || body.samples.isEmpty()) continue;
            int predicted = 0, inField = 0, usable = 0, masked = 0;
            Double minimum = null, maximum = null, maximumUncertainty = null;
            for (int i = 0; i < c.fitsFiles.length; i++) {
                var file = c.fitsFiles[i];
                if (file == null || file.getObservationTimestamp() == -1 || file.getExposureDurationMillis() <= 0) continue;
                String timeSystem = file.getFitsHeader().get("TIMESYS");
                if (timeSystem != null && !timeSystem.replace("'", "").trim().equalsIgnoreCase("UTC")) continue;
                long time = file.getObservationTimestamp() + file.getExposureDurationMillis() / 2;
                var p = position(body, time);
                if (p == null) continue;
                predicted++;
                if (p.magnitude() != null && Double.isFinite(p.magnitude())) {
                    minimum = minimum == null ? p.magnitude() : Math.min(minimum, p.magnitude());
                    maximum = maximum == null ? p.magnitude() : Math.max(maximum, p.magnitude());
                }
                var pixel = wcs.skyToPixel(p.ra(), p.dec());
                if (!finite(pixel)) continue;
                Double uncertainty = uncertaintyPixels(p, wcs, pixel);
                if (uncertainty != null) maximumUncertainty = maximumUncertainty == null
                        ? uncertainty : Math.max(maximumUncertainty, uncertainty);
                int x = (int) Math.floor(pixel[0] + .5), y = (int) Math.floor(pixel[1] + .5);
                if (x < 0 || y < 0 || x >= file.getSizeWidth() || y >= file.getSizeHeight()) continue;
                inField++;
                boolean rejected = rejectedFrames.contains(i);
                boolean data = c.dataArea == null || (y < c.dataArea.getHeight()
                        && x >= c.dataArea.getRowStart(y) && x <= c.dataArea.getRowEnd(y));
                boolean fill = i >= c.rawFrames.size() || c.rawFrames.get(i) == null || y >= c.rawFrames.get(i).length
                        || x >= c.rawFrames.get(i)[y].length || c.rawFrames.get(i)[y][x] <= io.github.ppissias.jtransient.core.DataArea.FILL_THRESHOLD;
                if (!rejected && data && !fill) usable++;
                if (c.masterVetoMask != null && y < c.masterVetoMask.length && x < c.masterVetoMask[y].length
                        && c.masterVetoMask[y][x]) masked++;
            }
            boolean uncertaintyTooBroad = maximumUncertainty != null && maximumUncertainty >= fieldDiagonal;
            var comparisons = new ArrayList<Comparison>();
            if (!uncertaintyTooBroad) for (var track : c.movingTargets) {
                var match = compare(c.trackFindings.id(track), ReportTrackMotion.points(track.points, c.fitsFiles), body, wcs,
                        c.config == null ? 1 : Math.max(1, c.config.maxStarJitter), fieldDiagonal);
                if (match != null) comparisons.add(match);
            }
            var faint = c.faintMoverPresentation.stream().filter(entry -> entry.catalogueComparisons().stream()
                    .anyMatch(m -> java.util.Objects.equals(m.name(), body.name))).map(FaintMoverPresentation.Entry::id).toList();
            bodies.add(new Body(body.name == null ? "Unnamed catalogue object" : body.name, body.comet, body.orbitSolution,
                    minimum, maximum, predicted, inField, usable, masked, List.copyOf(comparisons), faint,
                    informationUrl(body.name, body.comet), maximumUncertainty, uncertaintyTooBroad));
        }
        var t = c.pipelineTelemetry == null ? null : c.pipelineTelemetry.faintMoverSearchTelemetry;
        String faint = t == null ? "off" : t.skippedReason != null ? "skipped: " + t.skippedReason
                : t.truncated ? "partial: " + t.tilesSearched + " of " + t.tiles + " tiles" : "completed";
        var filters = java.util.Arrays.stream(c.fitsFiles).filter(java.util.Objects::nonNull)
                .map(f -> f.getFitsHeader().get("FILTER")).filter(java.util.Objects::nonNull)
                .map(s -> s.replace("'", "").trim()).filter(s -> !s.isEmpty()).distinct().sorted().toList();
        return new Summary(catalogue.source, catalogue.fetchedAtUtc,
                Double.isFinite(catalogue.magnitudeLimit) ? catalogue.magnitudeLimit : null, catalogue.startMillis,
                catalogue.endMillis, catalogue.problems == null ? List.of() : catalogue.problems.stream().filter(java.util.Objects::nonNull).toList(), filters, faint,
                fieldDiagonal, List.copyOf(bodies), clock(bodies));
    }

    /** Compares actual centroids/rates; field-wide ephemeris uncertainty cannot support a local association or clock check. */
    static Comparison compare(String id, List<ReportTrackMotion.Point> points, SolarSystemObjects.Body body,
                              WcsCoordinateTransformer wcs, double reviewFloor, double fieldDiagonalPixels) {
        if (points.size() < 3 || body == null || body.samples == null || body.samples.isEmpty()
                || wcs == null || !Double.isFinite(reviewFloor) || reviewFloor <= 0
                || !Double.isFinite(fieldDiagonalPixels) || fieldDiagonalPixels <= 0) return null;
        var observedFit = ReportTrackMotion.fit(points);
        if (observedFit == null) return null;
        var predicted = new ArrayList<ReportTrackMotion.Point>();
        var residuals = new ArrayList<Residual>();
        double squares = 0, maximum = 0, tolerance = reviewFloor;
        boolean errorsAvailable = true;
        for (var p : points) {
            var sky = position(body, p.midpointMillis());
            if (sky == null) return null;
            var q = wcs.skyToPixel(sky.ra(), sky.dec());
            if (!finite(q)) return null;
            predicted.add(new ReportTrackMotion.Point(p.frameIndex(), p.midpointMillis(), q[0], q[1]));
            Double uncertainty = uncertaintyPixels(sky, wcs, q);
            if (uncertainty != null && uncertainty >= fieldDiagonalPixels) return null;
            errorsAvailable &= uncertainty != null;
            double limit = uncertainty == null ? reviewFloor : Math.hypot(reviewFloor, uncertainty);
            tolerance = Math.max(tolerance, limit);
            double distance = Math.hypot(p.x() - q[0], p.y() - q[1]);
            squares += distance * distance; maximum = Math.max(maximum, distance);
            residuals.add(new Residual(p.frameIndex() + 1, p.midpointMillis(), p.midpointMillis(),
                    p.x(), p.y(), q[0], q[1], distance, uncertainty));
        }
        var predictedFit = ReportTrackMotion.fit(predicted);
        if (predictedFit == null) return null;
        double minutes = (observedFit.endMillis() - observedFit.originMillis()) / 60000.0;
        double motionDifference = Math.hypot(observedFit.vx() - predictedFit.vx(), observedFit.vy() - predictedFit.vy());
        double motionTolerance = 2 * tolerance / minutes;
        if (motionDifference > motionTolerance) return null;
        boolean agrees = true;
        for (var r : residuals) {
            double limit = r.ephemerisThreeSigmaPixels() == null ? reviewFloor : Math.hypot(reviewFloor, r.ephemerisThreeSigmaPixels());
            agrees &= r.distancePixels() <= limit;
        }
        Double offset = null, timeTolerance = null;
        Double shiftedRms = null, shiftedMaximum = null;
        List<Residual> shiftedResiduals = List.of();
        int shiftedFrames = 0;
        double speedSquared = predictedFit.vx() * predictedFit.vx() + predictedFit.vy() * predictedFit.vy();
        if (speedSquared > 1e-12 && Math.sqrt(speedSquared) * minutes > 2 * tolerance
                && Math.hypot(observedFit.vx(), observedFit.vy()) * minutes > 2 * tolerance) {
            var offsets = new ArrayList<Double>();
            for (int i = 0; i < points.size(); i++) {
                var p = points.get(i); var q = predicted.get(i);
                offsets.add(60 * ((p.x() - q.x()) * predictedFit.vx() + (p.y() - q.y()) * predictedFit.vy()) / speedSquared);
            }
            offsets.sort(Double::compare);
            double proposed = offsets.get(offsets.size() / 2);
            boolean shiftedAgreement = true;
            boolean shiftedErrorsAvailable = errorsAvailable;
            double shiftedSquares = 0, shiftedMax = 0;
            double shiftedTolerance = tolerance;
            var checked = new ArrayList<Residual>();
            for (var p : points) {
                long predictionTime = p.midpointMillis() + Math.round(proposed * 1000);
                var sky = position(body, predictionTime);
                if (sky == null) continue;
                var q = wcs.skyToPixel(sky.ra(), sky.dec());
                if (!finite(q)) continue;
                shiftedFrames++;
                double distance = Math.hypot(p.x() - q[0], p.y() - q[1]);
                Double uncertainty = uncertaintyPixels(sky, wcs, q);
                if (uncertainty != null && uncertainty >= fieldDiagonalPixels) {
                    shiftedAgreement = false;
                    continue;
                }
                shiftedErrorsAvailable &= uncertainty != null;
                double limit = uncertainty == null ? reviewFloor : Math.hypot(reviewFloor, uncertainty);
                shiftedTolerance = Math.max(shiftedTolerance, limit);
                shiftedAgreement &= distance <= limit;
                shiftedSquares += distance * distance; shiftedMax = Math.max(shiftedMax, distance);
                checked.add(new Residual(p.frameIndex() + 1, p.midpointMillis(), predictionTime,
                        p.x(), p.y(), q[0], q[1], distance, uncertainty));
            }
            if (shiftedAgreement && shiftedFrames >= 3 && shiftedFrames * 2 >= points.size()) {
                offset = proposed;
                timeTolerance = 60 * shiftedTolerance / Math.sqrt(speedSquared);
                shiftedRms = Math.sqrt(shiftedSquares / shiftedFrames); shiftedMaximum = shiftedMax;
                shiftedResiduals = List.copyOf(checked);
                errorsAvailable = shiftedErrorsAvailable;
            }
        }
        if (!agrees && offset == null) return null;
        return new Comparison(id, points.size(), Math.sqrt(squares / points.size()), maximum, motionDifference,
                motionTolerance, tolerance, errorsAvailable, agrees, offset, timeTolerance, shiftedFrames,
                shiftedRms, shiftedMaximum, List.copyOf(residuals), shiftedResiduals);
    }

    /** Uses formal plane-of-sky RA/Dec 3-sigma RSS, scaled locally; this is not a covariance model or total astrometric error. */
    private static Double uncertaintyPixels(SolarSystemObjects.Position sky, WcsCoordinateTransformer wcs, double[] pixel) {
        if (sky.raSigmaArcsec() == null || sky.decSigmaArcsec() == null || !Double.isFinite(sky.raSigmaArcsec())
                || !Double.isFinite(sky.decSigmaArcsec()) || sky.raSigmaArcsec() < 0 || sky.decSigmaArcsec() < 0) return null;
        var a = wcs.pixelToSky(pixel[0] + 1, pixel[1]); var b = wcs.pixelToSky(pixel[0], pixel[1] + 1);
        if (a == null || b == null) return null;
        double scale = Math.min(SkyMath.separationArcsec(sky.ra(), sky.dec(), a.getRaDegrees(), a.getDecDegrees()),
                SkyMath.separationArcsec(sky.ra(), sky.dec(), b.getRaDegrees(), b.getDecDegrees()));
        double value = Math.hypot(sky.raSigmaArcsec(), sky.decSigmaArcsec()) / scale;
        return scale > 0 && Double.isFinite(value) ? value : null;
    }

    /** Rejects malformed sampled clocks/coordinates rather than letting interpolation invent finite comparison evidence. */
    private static SolarSystemObjects.Position position(SolarSystemObjects.Body body, long time) {
        long previous = 0;
        boolean first = true;
        for (var s : body.samples) {
            if (s == null || (!first && s.timeMillis <= previous) || !Double.isFinite(s.ra) || !Double.isFinite(s.dec)
                    || s.dec < -90 || s.dec > 90) return null;
            previous = s.timeMillis;
            first = false;
        }
        var p = SolarSystemObjects.positionAt(body, time);
        return p != null && Double.isFinite(p.ra()) && Double.isFinite(p.dec()) && Math.abs(p.dec()) <= 90 ? p : null;
    }

    /** Checks whether every unambiguous, independent eligible comparison supports the same resolved offset sign and interval. */
    static Clock clock(List<Body> bodies) {
        var byTrack = new HashMap<String, Integer>();
        var byName = new HashMap<String, Integer>();
        for (var body : bodies) byName.merge(body.name(), 1, Integer::sum);
        for (var body : bodies) for (var m : body.trackComparisons()) byTrack.merge(m.trackId(), 1, Integer::sum);
        var matches = new ArrayList<Comparison>();
        for (var body : bodies) if (body.trackComparisons().size() == 1 && byName.get(body.name()) == 1) {
            var m = body.trackComparisons().getFirst();
            if (byTrack.get(m.trackId()) == 1 && m.ephemerisUncertaintyAvailable()
                    && m.suggestedTimeOffsetSeconds() != null) matches.add(m);
        }
        if (matches.size() < 2) return new Clock("unavailable", null, null, null, matches.size(),
                "Need at least two independent, unambiguous multi-frame comparisons with resolved measured motion, ephemeris uncertainties and sampled time coverage. Large clock errors outside the saved ephemeris span cannot be checked.");
        double low = Double.NEGATIVE_INFINITY, high = Double.POSITIVE_INFINITY;
        int sign = 0;
        double sum = 0;
        for (var m : matches) {
            double value = m.suggestedTimeOffsetSeconds(), width = m.timeToleranceSeconds();
            int current = value > width ? 1 : value < -width ? -1 : 0;
            if (current == 0 || (sign != 0 && sign != current)) return new Clock("no-common-offset", null, null, null,
                    matches.size(), "The eligible comparisons do not all support a resolved offset in the same direction.");
            sign = current; sum += value;
            low = Math.max(low, value - width); high = Math.min(high, value + width);
        }
        if (low > high) return new Clock("inconsistent", null, null, null, matches.size(),
                "Object offsets disagree at the stated review tolerances; a common clock offset is not supported.");
        return new Clock("possible-common-offset", Math.max(low, Math.min(high, sum / matches.size())), low, high,
                matches.size(), "Add this offset to stored capture times to align these comparisons. This is a diagnostic, not proof of a clock error: WCS, centroid, observer-site or orbit errors can imitate it. No timestamp has been changed.");
    }

    /** Writes the field inventory and comparison evidence as static HTML, including explicit coverage/passband limits. */
    static void write(PrintWriter out, DetectionReportContext c, FieldDepth depth) {
        var s = c.knownSolarSystem;
        if (s == null) return;
        var inField = inFieldBodies(s);
        out.print("<section id='known-solar-system'><h2>Known asteroids and comets in the field</h2>");
        out.printf(Locale.US, "<p>Saved predictions: %s; fetched %s; magnitude limit %s. %s</p>",
                escape(s.source()), escape(s.fetchedAtUtc()), s.magnitudeLimit() == null ? "unknown" : String.format(Locale.US, "%.2f", s.magnitudeLimit()),
                s.problems().isEmpty() ? "Fetch reported complete." : "Partial fetch: " + escape(String.join("; ", s.problems())));
        out.print("<p>Predicted magnitudes are V for asteroids or total comet magnitudes. Field depth: "
                + (depth == null || depth.isEmpty() ? "unavailable" : escape(depth.medianLabel()))
                + ". Image FILTER metadata: " + escape(s.imageFilters().isEmpty() ? "unknown" : String.join(", ", s.imageFilters()))
                + ". Gaia G depth is a stellar, sampled-field estimate in a different passband; it is not a moving-object completeness limit. "
                + "Coverage counts sample known UTC exposure midpoints, not the whole exposure or source aperture. Masks, blends, rejected frames, "
                + "search limits and report exclusions can hide objects. No compatible finding does not establish a missed detection. Faint-mover search: " + escape(s.faintSearchStatus()) + ".</p>");
        out.print("<div class='known-objects-table-wrap'><table class='known-objects-table'><thead><tr>"
                + "<th>Predicted object</th><th>Magnitude</th><th>Path coverage</th><th>Compatible report findings (review)</th></tr></thead><tbody>");
        for (var body : inField) {
            out.print("<tr><td><span class='known-object-name'>" + escape(body.name()) + "</span><span class='known-object-meta'>"
                    + (body.comet() ? "Comet" : "Asteroid") + " · " + escape(body.orbitSolution()) + "</span>");
            if (body.informationUrl() != null) out.print("<a class='known-object-info' href='" + escape(body.informationUrl())
                    + "' target='_blank' rel='noopener noreferrer'>JPL object details ↗</a>");
            out.print("</td><td class='known-object-magnitude'>");
            if (body.minimumMagnitude() == null) out.print("Unknown");
            else out.printf(Locale.US, "%.2f–%.2f", body.minimumMagnitude(), body.maximumMagnitude());
            out.printf(Locale.US, "</td><td><strong>%d kept frames with data</strong><span class='known-object-meta'>"
                            + "%d / %d predicted · %d in field<br>%d on star-mask pixels</span></td><td>",
                    body.usablePathFrames(), body.predictedFrames(), c.fitsFiles.length, body.inFieldFrames(), body.starMaskFrames());
            if (body.ephemerisUncertaintyTooBroad()) {
                out.print("<span class='known-object-warning'>Orbit too uncertain for comparison</span>");
                out.printf(Locale.US, "<details class='known-object-evidence'><summary>Why no identification?</summary>"
                                + "<p>Supplied ephemeris 3-sigma RSS reaches %,.0f px; the field diagonal is %,.0f px. "
                                + "This uncertainty cannot distinguish tracks within the field. No track identification or clock comparison is made.</p></details>",
                        body.maximumEphemerisThreeSigmaPixels(), s.fieldDiagonalPixels());
            } else if (body.trackComparisons().isEmpty() && body.faintMoverComparisons().isEmpty()) {
                out.print("<span class='known-object-meta'>No compatible reported finding</span>");
            }
            for (var m : body.trackComparisons()) {
                out.print("<div class='known-object-finding'><a class='known-finding-link' href='#" + anchor(c, m.trackId()) + "'>"
                        + escape(m.trackId()) + "</a> <span>" + (m.positionAgreement() ? "Position and motion agree" : "Trial clock shift only") + "</span>");
                writeComparisonDetails(out, m);
                out.print("</div>");
            }
            for (var id : body.faintMoverComparisons()) out.print("<div class='known-object-finding'><a class='known-finding-link' href='#"
                    + anchor(c, id) + "'>" + escape(id) + "</a> <span>Possible fitted-path match</span></div>");
            out.print("</td></tr>");
        }
        out.print("</tbody></table></div>");
        out.printf(Locale.US, "<p>Objects predicted inside the image at one or more exposure midpoints: %d. "
                        + "Saved inventory entries without an in-field midpoint omitted from this table: %d; "
                        + "the complete saved inventory and comparison evidence remain in report-data.json.</p>",
                inField.size(), s.bodies().size() - inField.size());
        out.print("<p>All plausible comparisons for the listed objects remain visible. Ordinary tracks require at least three actual frames and agreeing measured rates. "
                + "The screen combines the supplied ephemeris 3-sigma RSS with an empirical pixel floor of max(1, star jitter); "
                + "centroid and plate-solution uncertainties and ephemeris covariance are not modelled. Uncertainty at least as large as the field diagonal "
                + "cannot support a field-local comparison. Agreement is not an identity confirmation.</p>");
        out.print("<details><summary>Clock check</summary><p>" + escape(s.clock().explanation()) + "</p>");
        if (s.clock().suggestedOffsetSeconds() != null) out.printf(Locale.US,
                "<p>%d independent comparisons suggest adding %+.1f s (review interval %+.1f to %+.1f s) to the recorded time.</p>",
                s.clock().independentObjects(), s.clock().suggestedOffsetSeconds(), s.clock().minimumSeconds(), s.clock().maximumSeconds());
        out.print("</details></section>");
    }

    /** Selects nominally in-field objects across all sampled exposure midpoints for both the table and navigation count. */
    static List<Body> inFieldBodies(Summary summary) {
        return summary.bodies().stream().filter(body -> body.inFieldFrames() > 0).toList();
    }

    /** Places nominal-time compatible names on a track card; clock-shift-only evidence never labels an identity. */
    static void writeTrackIdentification(PrintWriter out, DetectionReportContext c, String trackId) {
        if (c.knownSolarSystem == null) return;
        var bodies = c.knownSolarSystem.bodies().stream().filter(body -> !body.ephemerisUncertaintyTooBroad()
                && body.trackComparisons().stream().anyMatch(m -> m.trackId().equals(trackId) && m.positionAgreement())).toList();
        if (bodies.isEmpty()) return;
        out.print("<div class='track-link-note known-object-identification'><strong>"
                + (bodies.size() == 1 ? "Possible identification: " : "Possible identifications (ambiguous): ") + "</strong>");
        for (int i = 0; i < bodies.size(); i++) {
            if (i > 0) out.print("; ");
            var body = bodies.get(i);
            if (body.informationUrl() != null) out.print("<a href='" + escape(body.informationUrl())
                    + "' target='_blank' rel='noopener noreferrer'>" + escape(body.name()) + "</a>");
            else out.print(escape(body.name()));
            out.print(body.comet() ? " (comet)" : " (asteroid)");
        }
        out.print("<span class='known-object-meta'>Position and motion agree with saved predictions. "
                + "<a href='#known-solar-system'>Review the catalogue comparison</a>; identity is unconfirmed.</span></div>");
    }

    /** Labels every fitted-path comparison just as explicitly as a track, without promoting it to a confirmed identity. */
    static void writeFaintMoverIdentification(PrintWriter out, DetectionReportContext c, FaintMoverPresentation.Entry entry) {
        if (entry.catalogueComparisons().isEmpty()) return;
        out.print("<div class='track-link-note known-object-identification'><strong>"
                + (entry.catalogueComparisons().size() == 1 ? "Possible identification: " : "Possible identifications (ambiguous): ") + "</strong>");
        for (int i = 0; i < entry.catalogueComparisons().size(); i++) {
            if (i > 0) out.print("; ");
            var match = entry.catalogueComparisons().get(i);
            var body = c.knownSolarSystem.bodies().stream()
                    .filter(b -> java.util.Objects.equals(b.name(), match.name())).findFirst().orElseThrow();
            if (body.informationUrl() != null) out.print("<a href='" + escape(body.informationUrl())
                    + "' target='_blank' rel='noopener noreferrer'>" + escape(match.name()) + "</a>");
            else out.print(escape(match.name()));
            out.print(body.comet() ? " (comet)" : " (asteroid)");
        }
        out.print("<span class='known-object-meta'>Fitted position and motion agree with saved predictions. "
                + "<a href='#known-solar-system'>Review the catalogue comparison</a>; identity is unconfirmed.</span></div>");
    }

    /** Keeps the numerical comparison and clock evidence available without making each inventory row a paragraph. */
    private static void writeComparisonDetails(PrintWriter out, Comparison m) {
        out.printf(Locale.US, "<details class='known-object-evidence'><summary>%d measured frames · RMS %.2f px</summary>"
                        + "<p>Maximum %.2f px; position screen up to %.2f px. Rate difference %.4f px/min (screen %.4f). %s</p>",
                m.frames(), m.rmsPixels(), m.maximumPixels(), m.reviewTolerancePixels(), m.motionDifferencePixelsPerMinute(),
                m.motionTolerancePixelsPerMinute(), m.ephemerisUncertaintyAvailable()
                        ? "Includes supplied ephemeris 3-sigma RSS." : "Ephemeris uncertainty incomplete or unavailable; heuristic comparison only.");
        if (m.suggestedTimeOffsetSeconds() != null) out.printf(Locale.US,
                "<p>Along-path time diagnostic: add %+.1f s; position-tolerance equivalent %.1f s; %d shifted frames checked, "
                        + "RMS %.2f px, maximum %.2f px. Original and trial times/positions are saved in report-data.json.</p>",
                m.suggestedTimeOffsetSeconds(), m.timeToleranceSeconds(), m.shiftedFrames(), m.shiftedRmsPixels(), m.shiftedMaximumPixels());
        out.print("</details>");
    }

    /** Builds a JPL SBDB lookup from the saved designation; no network request or extra catalogue fetch is needed. */
    static String informationUrl(String name, boolean comet) {
        if (name == null || name.isBlank()) return null;
        String search = name.trim();
        if (!comet && search.matches("\\(?\\d{4} (?:[A-Z]{2}\\d*|[PT]-[L123])\\)?")) {
            search = search.replace("(", "").replace(")", "");
        } else if (!comet && search.matches("\\d+(?: .*)?")) {
            search = search.split(" ", 2)[0];
        } else if (comet) {
            search = search.split(" \\(", 2)[0];
            if (search.matches("\\d+[PDCXAI](?:-[A-Z0-9]+)?/.*")) search = search.substring(0, search.indexOf('/'));
        }
        return "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr="
                + URLEncoder.encode(search, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Resolves only visible report identities so every generated link opens an exported finding. */
    private static String anchor(DetectionReportContext c, String id) {
        return c.data.findings.stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow().anchor();
    }

    /** Requires finite projected coordinates before geometry or array access. */
    private static boolean finite(double[] p) { return p != null && p.length >= 2 && Double.isFinite(p[0]) && Double.isFinite(p[1]); }

    /** Escapes captured provider text; missing provenance stays explicit. */
    private static String escape(String value) { return ReportFormat.escapeHtml(value == null ? "unavailable" : value); }
}
