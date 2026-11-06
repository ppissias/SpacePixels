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
    /** Bounds original plus residual review pixels; their six bytes/sample accompany the engine's four-byte residuals. */
    private static final long REVIEW_BUDGET_BYTES = 96L * 1024 * 1024;
    /** Sky coordinates and motion derived through the run's captured WCS; absent values remain unavailable. */
    record Sky(Double raDegrees, Double decDegrees, Double speedArcsecPerMinute, Double positionAngleDegrees) { }
    /** A fitted path evaluated at one searched exposure midpoint, not an independently measured centroid. */
    record Residual(int frameNumber, long midpointMillis, double fittedX, double fittedY,
                    double predictedX, double predictedY, double distancePixels) { }
    /** A heuristic full-path comparison; the original reference-offset field remains available in exports. */
    record Match(String name, Double predictedMagnitude, double positionResidualPixels, double motionResidualPixelsPerMinute,
                 int frames, double rmsPixels, double maximumPixels, double positionTolerancePixels,
                 double motionTolerancePixelsPerMinute, List<Residual> residuals) { }
    /** An evaluated fitted position, not an independent per-frame detection. Frame numbers are one-based. */
    record Position(int frameNumber, long midpointMillis, double x, double y, Double raDegrees, Double decDegrees) { }
    /** Actual scoring-grid sample and mask test from the engine, not a fitted per-frame detection or photometry. */
    record PathSample(int frameNumber, long midpointMillis, int sampleX, int sampleY, boolean unmasked, Double processedSignal) { }
    /** Bounded review assets and actual mask support; no scientific pixels enter report-data.json. */
    record Review(String data, int x, int y, int width, int height, String unavailableReason,
                  String residualUnavailableReason, Integer unmaskedFrames, String firstHalfStack, String secondHalfStack,
                  List<PathSample> pathSamples) { }
    /** Finite export values and image links for one unverified candidate; no image arrays are retained here. */
    record Entry(String id, double x, double y, long referenceTimeMillis, double vxPixelsPerMinute,
                 double vyPixelsPerMinute, double speedPixelsPerMinute, double score, Double firstHalfScore,
                 Double secondHalfScore, Double stationaryScore, Double cameraScore, boolean firstHalfAvailable,
                 boolean secondHalfAvailable, boolean cameraComparisonAvailable, Sky sky, List<Position> fittedPositions,
                 boolean catalogueComparisonAvailable, List<Match> catalogueComparisons, String cutout, String blink,
                 Review review) { }

    /** Prevents construction of this report projection utility. */
    private FaintMoverPresentation() { }

    /** Derives all entries once, preserving engine ordering and explicit searched-frame identities. */
    static List<Entry> entries(DetectionReportContext context) {
        var output = new ArrayList<Entry>();
        long[] reviewBudget = {REVIEW_BUDGET_BYTES};
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
            Review review = review(context, c, id, positions, reviewBudget);
            output.add(new Entry(id, c.x, c.y, c.referenceTimeMillis, c.vx, c.vy, c.speedPixelsPerMinute(), c.score,
                    finite(c.firstHalfScore), finite(c.secondHalfScore), finite(c.stationaryScore), finite(c.cameraScore),
                    Double.isFinite(c.firstHalfScore), Double.isFinite(c.secondHalfScore), Double.isFinite(c.cameraScore),
                    sky(c, wcs, c.referenceTimeMillis), List.copyOf(positions),
                    canCompare(c, wcs, objects, start, end), compare(c, wcs, objects, fwhm, positions,
                            Math.hypot(context.fitsFiles[0].getSizeWidth(), context.fitsFiles[0].getSizeHeight()), start, end),
                    "faint_movers/" + id.toLowerCase(java.util.Locale.ROOT) + "_stack.png",
                    review.data() == null ? null : "faint_movers/" + id.toLowerCase(java.util.Locale.ROOT) + "_blink.gif", review));
        }
        return List.copyOf(output);
    }

    /** Chooses a bounded native-pixel rectangle and reports missing engine evidence without inventing support. */
    private static Review review(DetectionReportContext context, FaintMoverCandidate c, String id,
                                 List<Position> positions, long[] budget) {
        var d = c.diagnostics;
        String residualReason = d == null ? "No saved engine review evidence" : d.unavailableReason;
        boolean residual = d != null && residualReason == null && d.frames.size() == positions.size();
        if (residual) for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).frameNumber() != d.frames.get(i).sequenceIndex + 1
                    || positions.get(i).midpointMillis() != d.frames.get(i).midpointMillis) {
                residual = false;
                residualReason = "Engine review frames do not match the searched source frames";
                break;
            }
        }
        if (!residual && residualReason == null) residualReason = "Engine review frames are incomplete";
        int x = 0, y = 0, w = 0, h = 0;
        String unavailable = positions.isEmpty() ? "No searched source frames are available" : null;
        if (unavailable == null) {
            int height = context.rawFrames.getFirst().length, width = context.rawFrames.getFirst()[0].length;
            x = Math.max(0, (int) Math.floor(positions.stream().mapToDouble(Position::x).min().orElse(c.x) - 35));
            y = Math.max(0, (int) Math.floor(positions.stream().mapToDouble(Position::y).min().orElse(c.y) - 35));
            w = Math.max(0, Math.min(width, (int) Math.ceil(positions.stream().mapToDouble(Position::x).max().orElse(c.x) + 36)) - x);
            h = Math.max(0, Math.min(height, (int) Math.ceil(positions.stream().mapToDouble(Position::y).max().orElse(c.y) + 36)) - y);
            if (residual) { x = d.x; y = d.y; w = d.width; h = d.height; }
            long bytes = (residual ? 6L : 2L) * w * h * positions.size();
            if (w <= 0 || h <= 0 || w > 256 || h > 256 || positions.size() > 128)
                unavailable = "Review limited to 128 frames and 256 pixels per side";
            else if (bytes > budget[0]) unavailable = "96 MiB report review pixel budget exhausted";
            else budget[0] -= bytes;
        }
        String prefix = "faint_movers/" + id.toLowerCase(java.util.Locale.ROOT);
        Integer unmasked = residual ? (int) d.frames.stream().filter(f -> f.unmasked).count() : null;
        List<PathSample> samples = residual ? d.frames.stream().map(f -> new PathSample(f.sequenceIndex + 1,
                f.midpointMillis, f.sampleX, f.sampleY, f.unmasked, finite(f.signal))).toList() : List.of();
        return new Review(unavailable == null ? prefix + "_view.js" : null, x, y, w, h, unavailable,
                residualReason, unmasked, residual && d.firstHalf != null ? prefix + "_first_half.png" : null,
                residual && d.secondHalf != null ? prefix + "_second_half.png" : null, samples);
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

    /** Checks every searched fitted epoch and the predicted rate with the existing heuristic screens; retains all alternatives. */
    static List<Match> compare(FaintMoverCandidate c, WcsCoordinateTransformer wcs, SolarSystemObjects objects,
                               double fwhm, List<Position> positions, double fieldDiagonalPixels,
                               long sessionStart, long sessionEnd) {
        if (!canCompare(c, wcs, objects, sessionStart, sessionEnd) || positions.size() < 2
                || !Double.isFinite(fwhm) || fwhm <= 0) return List.of();
        var matches = new ArrayList<Match>();
        for (var body : objects.bodies) {
            if (body == null || body.samples == null || body.samples.isEmpty()) continue;
            var p = KnownSolarSystemReport.position(body, c.referenceTimeMillis);
            if (p == null) continue;
            var pixel = wcs.skyToPixel(p.ra(), p.dec());
            if (!finitePixel(pixel)) continue;
            double offset = Math.hypot(pixel[0] - c.x, pixel[1] - c.y);
            if (offset > 2 * fwhm) continue;
            var predicted = new ArrayList<ReportTrackMotion.Point>();
            var residuals = new ArrayList<Residual>();
            double squares = 0, maximum = 0;
            boolean compatible = true;
            for (var position : positions) {
                var sky = KnownSolarSystemReport.position(body, position.midpointMillis());
                var q = sky == null ? null : wcs.skyToPixel(sky.ra(), sky.dec());
                if (!finitePixel(q)) { compatible = false; break; }
                Double uncertainty = KnownSolarSystemReport.uncertaintyPixels(sky, wcs, q);
                if (uncertainty != null && uncertainty >= fieldDiagonalPixels) { compatible = false; break; }
                double distance = Math.hypot(position.x() - q[0], position.y() - q[1]);
                if (distance > 2 * fwhm) { compatible = false; break; }
                squares += distance * distance;
                maximum = Math.max(maximum, distance);
                predicted.add(new ReportTrackMotion.Point(position.frameNumber() - 1, position.midpointMillis(), q[0], q[1]));
                residuals.add(new Residual(position.frameNumber(), position.midpointMillis(), position.x(), position.y(), q[0], q[1], distance));
            }
            if (!compatible) continue;
            var fit = ReportTrackMotion.fit(predicted);
            if (fit == null) continue;
            double motion = Math.hypot(fit.vx() - c.vx, fit.vy() - c.vy);
            if (motion <= 0.1) matches.add(new Match(body.name == null ? "Unnamed catalogue object" : body.name,
                    p.magnitude() == null ? null : finite(p.magnitude()), offset, motion,
                    residuals.size(), Math.sqrt(squares / residuals.size()), maximum, 2 * fwhm, 0.1, List.copyOf(residuals)));
        }
        return List.copyOf(matches);
    }

    /** Rejects unavailable or nonfinite WCS projections before calculating evidence. */
    private static boolean finitePixel(double[] pixel) {
        return pixel != null && pixel.length >= 2 && Double.isFinite(pixel[0]) && Double.isFinite(pixel[1]);
    }

    /** Distinguishes an unavailable comparison from an available catalogue with no compatible predictions. */
    private static boolean canCompare(FaintMoverCandidate c, WcsCoordinateTransformer wcs, SolarSystemObjects objects,
                                      long sessionStart, long sessionEnd) {
        return wcs != null && objects != null && objects.bodies != null && c.referenceTimeMillis >= objects.startMillis
                && c.referenceTimeMillis <= objects.endMillis
                && sessionEnd > sessionStart && sessionStart >= objects.startMillis && sessionEnd <= objects.endMillis;
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
        Double angularScale = angularPixelScale(c);
        data.put("pixelScaleArcsecPerPixel", angularScale);
        data.put("minSpeedArcsecPerMinute", angularScale == null ? null : finite(t.minSpeedPixelsPerMinute * angularScale));
        data.put("maxSpeedArcsecPerMinute", angularScale == null ? null : finite(t.maxSpeedPixelsPerMinute * angularScale));
        data.put("angularSpeedReference", angularScale == null ? "unavailable without WCS" : "field-centre WCS equivalent of the actual pixel search domain");
        data.put("coarseStepPixelsPerMinute", finite(t.coarseStepPixelsPerMinute));
        data.put("fineStepPixelsPerMinute", finite(t.fineStepPixelsPerMinute));
        data.put("coarseMotions", t.coarseMotions); data.put("tiles", t.tiles); data.put("tilesSearched", t.tilesSearched);
        data.put("searchedPixels", t.searchedPixels); data.put("elapsedMs", t.elapsedMs);
        data.put("diagnosticsAvailable", t.diagnosticsAvailable); data.put("diagnosticBytes", t.diagnosticBytes);
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

    /** Converts the completed run's pixel rates through its captured, corrected field-centre WCS. */
    static Double angularPixelScale(DetectionReportContext context) {
        if (context.astrometryContext == null || context.fitsFiles.length == 0 || context.fitsFiles[0] == null) return null;
        return finite(eu.startales.spacepixels.detection.FaintMoverRuntimeEstimate.angularPixelScale(
                context.astrometryContext.getTransformer(), context.fitsFiles[0].getSizeWidth(), context.fitsFiles[0].getSizeHeight()));
    }

    /** Represents unavailable evidence without writing NaN or infinity to standard JSON. */
    private static Double finite(double value) { return Double.isFinite(value) ? value : null; }
}
