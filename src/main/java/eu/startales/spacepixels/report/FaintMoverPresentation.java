package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import eu.startales.spacepixels.util.SkyMath;
import io.github.ppissias.jtransient.core.FaintMoverCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Report-owned fitted paths and catalogue comparisons; never changes or identifies an engine candidate. */
final class FaintMoverPresentation {
    /** Sky coordinates and motion derived through the run's captured WCS; absent values remain unavailable. */
    record Sky(Double raDegrees, Double decDegrees, Double speedArcsecPerMinute, Double positionAngleDegrees) { }
    /** A heuristic comparison with one fetched prediction, with residuals in registered-image coordinates. */
    record Match(String name, Double predictedMagnitude, double positionResidualPixels, double motionResidualPixelsPerMinute) { }
    /** An evaluated fitted position, not an independent per-frame detection. Frame numbers are one-based. */
    record Position(int frameNumber, long midpointMillis, double x, double y, Double raDegrees, Double decDegrees) { }
    /** Finite export values and image links for one unverified candidate; no image arrays are retained here. */
    record Entry(String id, double x, double y, long referenceTimeMillis, double vxPixelsPerMinute,
                 double vyPixelsPerMinute, double speedPixelsPerMinute, double score, Double firstHalfScore,
                 Double secondHalfScore, Double stationaryScore, Double cameraScore, boolean firstHalfAvailable,
                 boolean secondHalfAvailable, boolean cameraComparisonAvailable, Sky sky, List<Position> fittedPositions,
                 boolean catalogueComparisonAvailable, List<Match> catalogueComparisons, String cutout, String blink) { }

    /** Prevents construction of this report projection utility. */
    private FaintMoverPresentation() { }

    /** Derives all entries once, preserving engine ordering and explicit searched-frame identities. */
    static List<Entry> entries(DetectionReportContext context) {
        var output = new ArrayList<Entry>();
        var wcs = context.astrometryContext == null ? null : context.astrometryContext.getTransformer();
        double fwhm = context.pipelineTelemetry != null && context.pipelineTelemetry.faintMoverSearchTelemetry != null
                ? context.pipelineTelemetry.faintMoverSearchTelemetry.fwhmPixels : 2.5;
        for (int i = 0; i < context.faintMoverCandidates.size(); i++) {
            var c = context.faintMoverCandidates.get(i);
            String id = FindingCategory.FAINT_MOVER.id(i + 1);
            var positions = new ArrayList<Position>();
            for (int frame : context.faintMoverFrames) {
                if (frame >= context.fitsFiles.length || context.fitsFiles[frame] == null) continue;
                var file = context.fitsFiles[frame];
                if (file.getObservationTimestamp() == -1) continue;
                long mid = file.getObservationTimestamp() + Math.max(0, file.getExposureDurationMillis()) / 2;
                double[] p = c.positionAt(mid);
                var sky = sky(c, wcs, mid);
                positions.add(new Position(frame + 1, mid, p[0], p[1], sky.raDegrees, sky.decDegrees));
            }
            long start = positions.stream().mapToLong(Position::midpointMillis).min().orElse(c.referenceTimeMillis);
            long end = positions.stream().mapToLong(Position::midpointMillis).max().orElse(c.referenceTimeMillis);
            var objects = context.runContext.solarSystem();
            output.add(new Entry(id, c.x, c.y, c.referenceTimeMillis, c.vx, c.vy, c.speedPixelsPerMinute(), c.score,
                    finite(c.firstHalfScore), finite(c.secondHalfScore), finite(c.stationaryScore), finite(c.cameraScore),
                    Double.isFinite(c.firstHalfScore), Double.isFinite(c.secondHalfScore), Double.isFinite(c.cameraScore),
                    sky(c, wcs, c.referenceTimeMillis), List.copyOf(positions),
                    canCompare(c, wcs, objects, start, end), compare(c, wcs, objects, fwhm, start, end),
                    "faint_movers/" + id.toLowerCase(java.util.Locale.ROOT) + "_stack.png",
                    positions.isEmpty() ? null : "faint_movers/" + id.toLowerCase(java.util.Locale.ROOT) + "_blink.gif"));
        }
        return List.copyOf(output);
    }

    /** Converts a fitted one-minute pixel displacement through WCS, handling RA wrapping and unavailable solutions. */
    static Sky sky(FaintMoverCandidate candidate, WcsCoordinateTransformer wcs, long time) {
        if (wcs == null) return new Sky(null, null, null, null);
        var p = candidate.positionAt(time);
        var q = candidate.positionAt(time + 60_000);
        var a = wcs.pixelToSky(p[0], p[1]);
        var b = wcs.pixelToSky(q[0], q[1]);
        if (a == null || b == null) return new Sky(null, null, null, null);
        double r1 = Math.toRadians(a.getRaDegrees()), r2 = Math.toRadians(b.getRaDegrees());
        double d1 = Math.toRadians(a.getDecDegrees()), d2 = Math.toRadians(b.getDecDegrees());
        double angle = Math.toDegrees(Math.atan2(Math.sin(r2 - r1) * Math.cos(d2),
                Math.cos(d1) * Math.sin(d2) - Math.sin(d1) * Math.cos(d2) * Math.cos(r2 - r1)));
        double speed = SkyMath.separationArcsec(a.getRaDegrees(), a.getDecDegrees(), b.getRaDegrees(), b.getDecDegrees());
        return new Sky(finite(a.getRaDegrees()), finite(a.getDecDegrees()), finite(speed),
                speed > 0 ? finite(SkyMath.normalizeDegrees(angle)) : null);
    }

    /** Compares position and velocity using explicit heuristic tolerances; retains every compatible match. */
    static List<Match> compare(FaintMoverCandidate c, WcsCoordinateTransformer wcs, SolarSystemObjects objects,
                               double fwhm, long sessionStart, long sessionEnd) {
        if (!canCompare(c, wcs, objects, sessionStart, sessionEnd)) return List.of();
        long before = Math.max(objects.startMillis, sessionStart);
        long after = Math.min(objects.endMillis, sessionEnd);
        if (after <= before) return List.of();
        var matches = new ArrayList<Match>();
        for (var body : objects.bodies) {
            if (body == null || body.samples == null || body.samples.isEmpty()) continue;
            var p = SolarSystemObjects.positionAt(body, c.referenceTimeMillis);
            var a = SolarSystemObjects.positionAt(body, before);
            var b = SolarSystemObjects.positionAt(body, after);
            if (p == null || a == null || b == null) continue;
            var pixel = wcs.skyToPixel(p.ra(), p.dec());
            var pa = wcs.skyToPixel(a.ra(), a.dec());
            var pb = wcs.skyToPixel(b.ra(), b.dec());
            if (pixel == null || pa == null || pb == null) continue;
            double offset = Math.hypot(pixel[0] - c.x, pixel[1] - c.y);
            double minutes = (after - before) / 60000.0;
            double motion = Math.hypot((pb[0] - pa[0]) / minutes - c.vx, (pb[1] - pa[1]) / minutes - c.vy);
            if (offset <= 2 * fwhm && motion <= 0.1) matches.add(new Match(body.name == null ? "Unnamed catalogue object" : body.name,
                    p.magnitude() == null ? null : finite(p.magnitude()), offset, motion));
        }
        return List.copyOf(matches);
    }

    /** Distinguishes an unavailable comparison from an available catalogue with no compatible predictions. */
    private static boolean canCompare(FaintMoverCandidate c, WcsCoordinateTransformer wcs, SolarSystemObjects objects,
                                      long sessionStart, long sessionEnd) {
        return wcs != null && objects != null && objects.bodies != null && c.referenceTimeMillis >= objects.startMillis
                && c.referenceTimeMillis <= objects.endMillis
                && Math.min(objects.endMillis, sessionEnd) > Math.max(objects.startMillis, sessionStart);
    }

    /** Produces finite, explicit search-domain/status data for the report JSON; null means the stage was off. */
    static Map<String, Object> search(DetectionReportContext c) {
        var t = c.pipelineTelemetry == null ? null : c.pipelineTelemetry.faintMoverSearchTelemetry;
        if (t == null) return null;
        var data = new LinkedHashMap<String, Object>();
        data.put("status", t.skippedReason != null ? "skipped" : t.truncated ? "truncated" : "completed");
        if (t.skippedReason != null) data.put("skippedReason", t.skippedReason);
        data.put("frames", t.frames); data.put("framesOutsideSession", t.framesOutsideSession);
        data.put("frameNumbers", c.faintMoverFrames.stream().map(i -> i + 1).toList());
        data.put("sessionMinutes", finite(t.sessionMinutes)); data.put("fwhmPixels", finite(t.fwhmPixels));
        data.put("fwhmFromStars", t.fwhmFromStars);
        data.put("minSpeedPixelsPerMinute", finite(t.minSpeedPixelsPerMinute));
        data.put("maxSpeedPixelsPerMinute", finite(t.maxSpeedPixelsPerMinute));
        data.put("coarseStepPixelsPerMinute", finite(t.coarseStepPixelsPerMinute));
        data.put("fineStepPixelsPerMinute", finite(t.fineStepPixelsPerMinute));
        data.put("coarseMotions", t.coarseMotions); data.put("tiles", t.tiles); data.put("tilesSearched", t.tilesSearched);
        data.put("searchedPixels", t.searchedPixels); data.put("elapsedMs", t.elapsedMs);
        data.put("refinedPeaks", t.refinedPeaks); data.put("belowScore", t.belowScore);
        data.put("rejectedSplitHalf", t.rejectedSplitHalf); data.put("rejectedStationary", t.rejectedStationary);
        data.put("followingCamera", t.followingCamera); data.put("duplicates", t.duplicates);
        data.put("matchedTracks", t.matchedTracks); data.put("nearStronger", t.nearStronger); data.put("candidates", t.candidates);
        data.put("scoreInterpretation", "empirical score; candidates are unverified; fitted positions are not individual detections");
        var objects = c.runContext.solarSystem();
        data.put("catalogueComparisonStatus", objects == null ? "unavailable"
                : objects.problems != null && !objects.problems.isEmpty() ? "partial-fetch" : "available");
        if (objects != null) {
            data.put("catalogueMagnitudeLimit", finite(objects.magnitudeLimit));
            data.put("catalogueFetchedAtUtc", objects.fetchedAtUtc); data.put("catalogueSource", objects.source);
            data.put("catalogueStartMillis", objects.startMillis); data.put("catalogueEndMillis", objects.endMillis);
            data.put("catalogueProblems", objects.problems == null ? List.of() : List.copyOf(objects.problems));
        }
        data.put("comparisonPositionTolerancePixels", finite(2 * t.fwhmPixels));
        data.put("comparisonMotionTolerancePixelsPerMinute", 0.1);
        return java.util.Collections.unmodifiableMap(data);
    }

    /** Represents unavailable evidence without writing NaN or infinity to standard JSON. */
    private static Double finite(double value) { return Double.isFinite(value) ? value : null; }
}
