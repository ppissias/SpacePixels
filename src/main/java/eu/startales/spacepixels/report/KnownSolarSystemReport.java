package eu.startales.spacepixels.report;

import eu.startales.spacepixels.mpc.MainBeltMotion;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.solarsystem.JplSbdbLookup;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import eu.startales.spacepixels.util.SkyMath;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/** Captured catalogue coverage and measured-track/fitted-path comparisons; never identifies, seeds or changes detections. */
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
    /** One frame's observed-minus-predicted offset on the sky, in arcseconds, RA scaled by cos Dec. */
    record Offset(int frameNumber, long midpointMillis, double raArcsec, double decArcsec) { }
    /**
     * One track or faint mover against the predictions. {@code status} is "known" (position and motion agree with one
     * object), "check" (agreeing with several, or only with shifted frame times), "no-known-match", or "not-compared"
     * (the predictions do not cover it). The O−C is the measured position minus the prediction for a known track, and
     * the fitted path minus the prediction for a known faint mover; absent otherwise. Agreement is not proof of identity.
     * {@code motionNote} compares the motion with a main-belt asteroid's at that place and time ({@link MainBeltMotion}),
     * and {@code unusualMotion} says briefly why it is unusual, or is null; an unmatched object with unusual motion is
     * "check". Both are null without a position, time or motion.
     */
    record Identification(String id, String kind, String status, List<String> names, String note, int frames,
                          Double arcHours, Double speedArcsecPerHour, Double positionAngleDegrees, Double meanRaArcsec,
                          Double meanDecArcsec, Double rmsArcsec, List<Offset> offsets, String motionNote, String unusualMotion) { }
    /**
     * Buffer-free offline provenance, coverage and comparisons from the admitted run's catalogue/WCS snapshot.
     * {@code siteProblem} says why the run's observing site cannot have seen the field, or is null.
     */
    record Summary(String source, String fetchedAtUtc, Double magnitudeLimit, long startMillis, long endMillis,
                   List<String> problems, List<String> imageFilters, String faintSearchStatus, double fieldDiagonalPixels,
                   List<Body> bodies, Clock clock, String siteProblem, List<Identification> identifications) { }

    static final String KNOWN = "known", CHECK = "check", NO_KNOWN_MATCH = "no-known-match", NOT_COMPARED = "not-compared";

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
        Double limit = Double.isFinite(catalogue.magnitudeLimit) ? catalogue.magnitudeLimit : null;
        var identifications = new ArrayList<Identification>();
        for (var track : c.movingTargets) identifications.add(identifyTrack(c, wcs, bodies, track, limit));
        for (var entry : c.faintMoverPresentation) identifications.add(identifyFaintMover(wcs, entry, limit));
        return new Summary(catalogue.source, catalogue.fetchedAtUtc, limit, catalogue.startMillis,
                catalogue.endMillis, catalogue.problems == null ? List.of() : catalogue.problems.stream().filter(java.util.Objects::nonNull).toList(), filters, faint,
                fieldDiagonal, List.copyOf(bodies), clock(bodies), siteProblem(c, wcs, dimensions), List.copyOf(identifications));
    }

    /** A track's status from the comparisons already made: one agreeing object, several, a clock shift only, or none. */
    private static Identification identifyTrack(DetectionReportContext c, WcsCoordinateTransformer wcs, List<Body> bodies,
                                                io.github.ppissias.jtransient.model.Track track, Double limit) {
        String id = c.trackFindings.id(track);
        var points = ReportTrackMotion.points(track.points, c.fitsFiles);
        var fit = points.size() >= 2 ? ReportTrackMotion.fit(points) : null;
        double[] motion = fit == null ? null : skyMotion(wcs, fit.x(), fit.y(), fit.vx(), fit.vy());
        var place = fit == null ? null : wcs.pixelToSky(fit.x(), fit.y());
        double[] where = place == null ? null : new double[]{place.getRaDegrees(), place.getDecDegrees(), fit.originMillis()};
        Double arc = points.size() < 2 ? null : (points.stream().mapToLong(ReportTrackMotion.Point::midpointMillis).max().orElseThrow()
                - points.stream().mapToLong(ReportTrackMotion.Point::midpointMillis).min().orElseThrow()) / 3_600_000.0;
        var named = new ArrayList<java.util.Map.Entry<String, Comparison>>();
        for (var body : bodies) for (var m : body.trackComparisons()) {
            if (m.trackId().equals(id)) named.add(java.util.Map.entry(body.name(), m));
        }
        var verdict = trackVerdict(named, limit);
        List<Offset> offsets = List.of();
        if (verdict.known() != null) {
            var list = new ArrayList<Offset>();
            for (var r : verdict.known().residuals()) {
                var o = offset(wcs, r.frameNumber(), r.midpointMillis(), r.observedX(), r.observedY(), r.predictedX(), r.predictedY());
                if (o != null) list.add(o);
            }
            offsets = List.copyOf(list);
        }
        return identification(id, "track", verdict.status(), verdict.names(), verdict.note(), points.size(), arc, motion, offsets, where);
    }

    /** A track's status, names and note, and the comparison of a known object (else null). */
    record Verdict(String status, List<String> names, String note, Comparison known) { }

    /**
     * A track's verdict from its comparisons with the predicted objects (name and comparison): one agreeing object,
     * several, a clock shift only, or none; {@code limit} is the predictions' depth for the no-match note.
     */
    static Verdict trackVerdict(List<java.util.Map.Entry<String, Comparison>> comparisons, Double limit) {
        var agreeing = new ArrayList<String>();
        var shiftOnly = new ArrayList<Comparison>();
        var shiftNames = new ArrayList<String>();
        Comparison known = null;
        for (var entry : comparisons) {
            if (entry.getValue().positionAgreement()) { agreeing.add(entry.getKey()); known = entry.getValue(); }
            else { shiftOnly.add(entry.getValue()); shiftNames.add(entry.getKey()); }
        }
        if (agreeing.size() == 1) {
            return new Verdict(KNOWN, List.copyOf(agreeing), "Position and motion agree with the prediction in every measured frame.", known);
        }
        if (agreeing.size() > 1) {
            return new Verdict(CHECK, List.copyOf(agreeing), "Agrees with " + agreeing.size() + " known objects: the identification is ambiguous.", null);
        }
        if (!shiftOnly.isEmpty()) {
            return new Verdict(CHECK, List.copyOf(shiftNames), String.format(Locale.US, "Agrees with %s only if the frame times are "
                    + "shifted by %+.0f s: check the clock and the observing site.", String.join(" or ", shiftNames),
                    shiftOnly.getFirst().suggestedTimeOffsetSeconds()), null);
        }
        return new Verdict(NO_KNOWN_MATCH, List.of(), noMatchNote(limit), null);
    }

    /** A faint mover's status from its fitted-path comparisons with the predictions. */
    private static Identification identifyFaintMover(WcsCoordinateTransformer wcs, FaintMoverPresentation.Entry entry, Double limit) {
        var times = entry.fittedPositions().stream().mapToLong(FaintMoverPresentation.Position::midpointMillis).sorted().toArray();
        Double arc = times.length < 2 ? null : (times[times.length - 1] - times[0]) / 3_600_000.0;
        var sky = entry.sky();
        double[] motion = sky.speedArcsecPerMinute() == null ? null
                : new double[]{60 * sky.speedArcsecPerMinute(), sky.positionAngleDegrees() == null ? Double.NaN : sky.positionAngleDegrees()};
        var names = entry.catalogueComparisons().stream().map(FaintMoverPresentation.Match::name).toList();
        String status, note;
        List<Offset> offsets = List.of();
        if (!entry.catalogueComparisonAvailable()) {
            status = NOT_COMPARED;
            note = "The saved predictions do not cover this session or field.";
        } else if (names.size() == 1) {
            status = KNOWN;
            note = "The fitted path and motion agree with the prediction; the path is fitted, not measured frame by frame.";
            var list = new ArrayList<Offset>();
            for (var r : entry.catalogueComparisons().getFirst().residuals()) {
                var o = offset(wcs, r.frameNumber(), r.midpointMillis(), r.fittedX(), r.fittedY(), r.predictedX(), r.predictedY());
                if (o != null) list.add(o);
            }
            offsets = List.copyOf(list);
        } else if (names.size() > 1) {
            status = CHECK;
            note = "Agrees with " + names.size() + " known objects: the identification is ambiguous.";
        } else {
            status = NO_KNOWN_MATCH;
            note = noMatchNote(limit);
        }
        double[] where = sky.raDegrees() == null || sky.decDegrees() == null ? null
                : new double[]{sky.raDegrees(), sky.decDegrees(), entry.referenceTimeMillis()};
        return identification(entry.id(), "faint mover", status, names, note, entry.fittedPositions().size(), arc, motion, offsets, where);
    }

    /**
     * Completes an identification with its mean and RMS O−C and the main-belt motion check; {@code where} is the
     * object's RA, Dec (degrees) and UTC milliseconds, or null.
     */
    private static Identification identification(String id, String kind, String status, List<String> names, String note,
                                                 int frames, Double arc, double[] motion, List<Offset> offsets, double[] where) {
        String motionNote = null, unusual = null;
        if (where != null && motion != null && Double.isFinite(motion[0]) && Double.isFinite(where[0]) && Double.isFinite(where[1])) {
            var assessment = MainBeltMotion.assess(where[0], where[1], (long) where[2], motion[0], motion[1]);
            motionNote = assessment.note();
            unusual = assessment.reason();
            if (assessment.unusual() && NO_KNOWN_MATCH.equals(status)) {
                status = CHECK;
                note = note + " Its motion is unusual: " + assessment.reason() + ".";
            }
        }
        Double meanRa = null, meanDec = null, rms = null;
        if (!offsets.isEmpty()) {
            double ra = 0, dec = 0, squares = 0;
            for (var o : offsets) { ra += o.raArcsec(); dec += o.decArcsec(); squares += o.raArcsec() * o.raArcsec() + o.decArcsec() * o.decArcsec(); }
            meanRa = ra / offsets.size(); meanDec = dec / offsets.size(); rms = Math.sqrt(squares / offsets.size());
        }
        return new Identification(id, kind, status, List.copyOf(names), note, frames, arc,
                motion == null || !Double.isFinite(motion[0]) ? null : motion[0],
                motion == null || !Double.isFinite(motion[1]) ? null : motion[1], meanRa, meanDec, rms, offsets, motionNote, unusual);
    }

    /** Names the depth of the search that found nothing, so absence is not read as completeness. */
    static String noMatchNote(Double limit) {
        return limit == null ? "No predicted object agrees with its position and motion."
                : String.format(Locale.US, "No known object to V %.1f agrees with its position and motion.", limit);
    }

    /** Observed minus predicted on the sky, in arcseconds; null where the WCS has no answer. */
    private static Offset offset(WcsCoordinateTransformer wcs, int frame, long time, double ox, double oy, double px, double py) {
        var o = wcs.pixelToSky(ox, oy);
        var p = wcs.pixelToSky(px, py);
        if (o == null || p == null) return null;
        double ra = SkyMath.normalizeSignedDegrees(o.getRaDegrees() - p.getRaDegrees()) * Math.cos(Math.toRadians(p.getDecDegrees())) * 3600;
        double dec = (o.getDecDegrees() - p.getDecDegrees()) * 3600;
        return Double.isFinite(ra) && Double.isFinite(dec) ? new Offset(frame, time, ra, dec) : null;
    }

    /** Sky speed in arcseconds per hour and position angle of a pixel motion (px per minute) at a pixel; null without WCS. */
    private static double[] skyMotion(WcsCoordinateTransformer wcs, double x, double y, double vx, double vy) {
        var a = wcs.pixelToSky(x, y);
        var b = wcs.pixelToSky(x + vx, y + vy);
        if (a == null || b == null) return null;
        double speed = 60 * SkyMath.separationArcsec(a.getRaDegrees(), a.getDecDegrees(), b.getRaDegrees(), b.getDecDegrees());
        return new double[]{speed, speed > 0 ? SkyMath.positionAngleDegrees(a.getRaDegrees(), a.getDecDegrees(),
                b.getRaDegrees(), b.getDecDegrees()) : Double.NaN};
    }

    /** Why the run's observing site cannot have seen the field centre during the session; null when it can or is unknown. */
    private static String siteProblem(DetectionReportContext c, WcsCoordinateTransformer wcs, int[] dimensions) {
        var site = c.astrometryContext.getObserverSite();
        var centre = wcs.pixelToSky(dimensions[0] / 2.0, dimensions[1] / 2.0);
        if (site == null || centre == null) return null;
        long start = Long.MAX_VALUE, end = Long.MIN_VALUE;
        for (var file : c.fitsFiles) {
            if (file == null || file.getObservationTimestamp() <= 0) continue;
            start = Math.min(start, file.getObservationTimestamp());
            end = Math.max(end, file.getObservationTimestamp() + Math.max(0, file.getExposureDurationMillis()));
        }
        if (start == Long.MAX_VALUE) return null;
        return new eu.startales.spacepixels.fits.ObservingSite(site.latitudeDeg, site.longitudeDeg, site.altitudeMeters,
                site.getSourceLabel()).horizonProblem(centre.getRaDegrees(), centre.getDecDegrees(), start, end);
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
    static Double uncertaintyPixels(SolarSystemObjects.Position sky, WcsCoordinateTransformer wcs, double[] pixel) {
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
    static SolarSystemObjects.Position position(SolarSystemObjects.Body body, long time) {
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

    /**
     * Writes the Asteroids and comets section: the site check, every track and faint mover with its status against the
     * predictions and the O−C of the known ones, then the predicted objects with their coverage and comparison evidence.
     */
    static void write(PrintWriter out, DetectionReportContext c, FieldDepth depth) {
        var s = c.knownSolarSystem;
        if (s == null) return;
        var inField = inFieldBodies(s);
        out.print("<section id='known-solar-system'><h2>Asteroids and comets</h2>");
        out.print("<p>Each moving-object track and faint mover is compared with the known asteroids and comets predicted "
                + "for this field. <strong>Known</strong> means its position and motion agree with one predicted object; "
                + "<strong>Check</strong> means it agrees with several, or only with shifted frame times, or that nothing known agrees "
                + "and it moves unusually for a main-belt asteroid (much faster, much slower or in another direction); "
                + "<strong>No known match</strong> means no predicted object agrees. Agreement supports an identification but does "
                + "not prove it, and the motion check is a screen of one night's motion, not an orbit.</p>");
        if (s.siteProblem() != null) out.print("<div class='asteroid-site-alert' role='alert'><strong>Observing site:</strong> "
                + escape(s.siteProblem()) + " The predictions and the matches below were computed for that site.</div>");
        out.printf(Locale.US, "<p>Saved predictions: %s; fetched %s; magnitude limit %s. %s</p>",
                escape(s.source()), escape(s.fetchedAtUtc()), s.magnitudeLimit() == null ? "unknown" : String.format(Locale.US, "%.2f", s.magnitudeLimit()),
                s.problems().isEmpty() ? "Fetch reported complete." : "Fetch problems: " + escape(String.join("; ", s.problems())));
        if (depth != null && !depth.isEmpty() && s.magnitudeLimit() != null
                && s.magnitudeLimit() < depth.median() + eu.startales.spacepixels.skycatalog.PhotometryStars.DEPTH_MARGIN - 0.5) {
            out.printf(Locale.US, "<div class='asteroid-site-alert' role='note'><strong>Predictions shallower than the frames:</strong> "
                            + "they stop at V %.1f, while the frames reach %s. Known objects fainter than V %.1f are missing, so a finding "
                            + "can show No known match although it is known. Fetch Asteroids and Comets again (it now goes as deep as the "
                            + "frames) and detect again, or use Compare again in Asteroids &amp; Comets.</div>",
                    s.magnitudeLimit(), escape(depth.medianLabel()), s.magnitudeLimit());
        }
        writeIdentifications(out, c, s.identifications());
        out.print("<h3>Predicted objects in the field</h3>");
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
            for (var id : body.faintMoverComparisons()) {
                var match = c.faintMoverPresentation.stream().filter(entry -> entry.id().equals(id))
                        .flatMap(entry -> entry.catalogueComparisons().stream())
                        .filter(m -> java.util.Objects.equals(m.name(), body.name())).findFirst().orElseThrow();
                out.print("<div class='known-object-finding'><a class='known-finding-link' href='#"
                        + anchor(c, id) + "'>" + escape(id) + "</a> <span>Fitted position and motion agree</span>");
                writeFaintComparisonDetails(out, match);
                out.print("</div>");
            }
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

    /** The tracks and faint movers with their status, identification, arc, motion and O−C against the prediction. */
    private static void writeIdentifications(PrintWriter out, DetectionReportContext c, List<Identification> rows) {
        out.print("<h3>Tracks and faint movers</h3>");
        if (rows.isEmpty()) {
            out.print("<p>This run reported no moving-object track or faint mover.</p>");
            return;
        }
        out.print("<div class='known-objects-table-wrap'><table class='known-objects-table asteroid-findings'><thead><tr>"
                + "<th>Finding</th><th>Status</th><th>Identification</th><th>Arc</th><th>Motion</th><th>O−C against the prediction</th>"
                + "</tr></thead><tbody>");
        for (var row : rows) {
            String link = findingAnchor(c, row.id());
            out.print("<tr><td>" + (link == null ? "<strong>" + escape(row.id()) + "</strong>"
                    : "<a class='known-finding-link' href='#" + escape(link) + "'>" + escape(row.id()) + "</a>")
                    + "<span class='known-object-meta'>" + ("track".equals(row.kind()) ? "Moving-object track" : "Faint mover") + "</span></td>");
            out.print("<td><span class='asteroid-status asteroid-status-" + row.status() + "'>" + statusLabel(row.status()) + "</span></td>");
            out.print("<td>" + (row.names().isEmpty() ? "" : "<span class='known-object-name'>" + escape(String.join(" or ", row.names())) + "</span>")
                    + "<span class='known-object-meta'>" + escape(row.note()) + "</span></td>");
            out.printf(Locale.US, "<td>%d %s%s</td>", row.frames(), "track".equals(row.kind()) ? "frames" : "searched frames",
                    row.arcHours() == null ? "" : String.format(Locale.US, "<span class='known-object-meta'>%.2f h</span>", row.arcHours()));
            out.print("<td>" + (row.speedArcsecPerHour() == null ? "N/A" : String.format(Locale.US, "%.1f″/h", row.speedArcsecPerHour())
                    + (row.positionAngleDegrees() == null ? "" : String.format(Locale.US, "<span class='known-object-meta'>PA %.0f°</span>", row.positionAngleDegrees())))
                    + motionCell(row) + "</td><td>");
            if (row.rmsArcsec() == null) out.print("<span class='known-object-meta'>—</span>");
            else {
                out.printf(Locale.US, "Mean %+.2f″ RA, %+.2f″ Dec<span class='known-object-meta'>RMS %.2f″ over %d %s</span>",
                        row.meanRaArcsec(), row.meanDecArcsec(), row.rmsArcsec(), row.offsets().size(),
                        "track".equals(row.kind()) ? "measured frames" : "fitted positions");
                out.print(offsetPlot(row));
            }
            out.print("</td></tr>");
        }
        out.print("</tbody></table></div>");
        out.print("<p>O−C is the position minus the prediction, in arcseconds (RA scaled by cos Dec), through this run's plate "
                + "solution. A mean offset well beyond the scatter in every known object points to the plate solution, the "
                + "frame times or the observing site rather than the objects.</p>");
        out.printf(Locale.US, "<p>Motion check: each motion is compared with a main-belt asteroid on a circular orbit between %.1f "
                        + "and %.1f AU seen in the same direction at the same time. Faster than %.1f times its fastest rate suggests a "
                        + "near-Earth object; slower than %.1f times its slowest, a more distant one (not judged near a stationary point, "
                        + "where every main-belt asteroid slows and turns); a direction more than %.0f° off its direction (more for slow "
                        + "rates, allowing inclinations to %.0f°), a high inclination or a near-Earth object. Eccentric and more inclined "
                        + "orbits, a short arc and the motion's own error widen the range, so this is a screen, not a classification. "
                        + "Hover over a motion for the expected values. A known object keeps its identification; only an orbit from "
                        + "more nights tells what an object is.</p>",
                MainBeltMotion.INNER_AU, MainBeltMotion.OUTER_AU, MainBeltMotion.FAST_FACTOR, MainBeltMotion.SLOW_FACTOR,
                MainBeltMotion.MIN_DIRECTION_TOLERANCE_DEG, MainBeltMotion.INCLINATION_DEG);
    }

    /** The motion check under a row's motion: usual or unusual for the main belt, with the full note on hover. */
    private static String motionCell(Identification row) {
        if (row.motionNote() == null) return "";
        return "<span class='known-object-meta' title='" + escape(row.motionNote()) + "'>"
                + (row.unusualMotion() == null ? "Usual for the main belt" : "<span class='known-object-warning'>Unusual: "
                + escape(row.unusualMotion()) + "</span>") + "</span>";
    }

    /** A small time plot of one row's O−C: RA filled, Dec hollow, on a symmetric scale of at least one arcsecond. */
    private static String offsetPlot(Identification row) {
        var offsets = row.offsets();
        long first = offsets.stream().mapToLong(Offset::midpointMillis).min().orElse(0);
        long last = offsets.stream().mapToLong(Offset::midpointMillis).max().orElse(first);
        double range = 1;
        for (var o : offsets) range = Math.max(range, Math.max(Math.abs(o.raArcsec()), Math.abs(o.decArcsec())));
        double width = 150, height = 44, span = Math.max(1, last - first);
        var svg = new StringBuilder(String.format(Locale.US, "<svg class='asteroid-oc-plot' viewBox='0 0 %.0f %.0f' role='img' "
                + "aria-label='%s O−C per frame, RA filled and Dec hollow, scale ±%.1f″'><line x1='0' y1='%.1f' x2='%.0f' y2='%.1f'/>",
                width, height, escape(row.id()), range, height / 2, width, height / 2));
        for (var o : offsets) {
            double x = 3 + (width - 6) * (o.midpointMillis() - first) / span;
            svg.append(String.format(Locale.US, "<circle class='oc-ra' cx='%.1f' cy='%.1f' r='1.6'/>", x, height / 2 - (height / 2 - 3) * o.raArcsec() / range));
            svg.append(String.format(Locale.US, "<circle class='oc-dec' cx='%.1f' cy='%.1f' r='1.6'/>", x, height / 2 - (height / 2 - 3) * o.decArcsec() / range));
        }
        return svg.append(String.format(Locale.US, "</svg><span class='known-object-meta'>±%.1f″ · RA ● Dec ○</span>", range)).toString();
    }

    /** The label of a status. */
    static String statusLabel(String status) {
        return switch (status) {
            case KNOWN -> "Known";
            case CHECK -> "Check";
            case NO_KNOWN_MATCH -> "No known match";
            default -> "Not compared";
        };
    }

    /** The anchor of a finding shown in this report, or null when its group is left out. */
    private static String findingAnchor(DetectionReportContext c, String id) {
        return c.data.findings.stream().filter(f -> f.id().equals(id)).findFirst().map(f -> f.anchor()).orElse(null);
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

    /** Shows full-epoch agreement while distinguishing fitted positions from a track's independently measured frames. */
    static void writeFaintComparisonDetails(PrintWriter out, FaintMoverPresentation.Match match) {
        out.printf(Locale.US, "<details class='known-object-evidence'><summary>%d fitted frames · RMS %.2f px</summary>"
                        + "<p>Maximum %.2f px; position screen %.2f px. Rate difference %.4f px/min (screen %.4f). "
                        + "All searched exposure midpoints were compared. Positions come from one fitted path, "
                        + "not separate per-frame centroid measurements. Agreement is not identity confirmation.</p></details>",
                match.frames(), match.rmsPixels(), match.maximumPixels(), match.positionTolerancePixels(),
                match.motionResidualPixelsPerMinute(), match.motionTolerancePixelsPerMinute());
    }

    /** Builds a JPL SBDB lookup from the saved designation; no network request or extra catalogue fetch is needed. */
    static String informationUrl(String name, boolean comet) {
        return JplSbdbLookup.informationUrl(name, comet);
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
