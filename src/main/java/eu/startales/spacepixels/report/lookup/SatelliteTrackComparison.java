package eu.startales.spacepixels.report.lookup;

import com.google.gson.*;

import java.util.*;

/**
 * Pure spherical geometry for measured fragments versus finite, time-bounded satellite exposure paths.
 */
public final class SatelliteTrackComparison {
    private static final double ARCSEC = 180 * 3600 / Math.PI;

    /**
     * One provider prediction, retaining the identity of the orbital data used at that instant.
     */
    public record Sample(double jd, double ra, double dec, String epoch, String source) {
    }

    /**
     * Closest finite-arc distance, perpendicular distance and any distance beyond an arc end, in arcseconds.
     */
    private record Distance(double finite, double perpendicular, double beyond, double[] a, double[] b) {
    }

    private SatelliteTrackComparison() {
    }

    /**
     * Compares only covered exposures. Missing coverage is returned explicitly, never extrapolated.
     */
    public static JsonObject compare(StreakTrackObservation observation, List<Sample> input, double stepSeconds) {
        return compare(observation, input, stepSeconds, () -> {
        });
    }

    /**
     * Same pure calculation with a caller-owned cancellation checkpoint between fragment measurements.
     */
    static JsonObject compare(StreakTrackObservation observation, List<Sample> input, double stepSeconds, Runnable checkpoint) {
        var samples = input.stream().sorted(Comparator.comparingDouble(Sample::jd)).toList();
        var result = new JsonObject();
        var frames = new JsonArray();
        var medians = new ArrayList<Double>();
        var perpendicular = new ArrayList<Double>();
        var directionDifferences = new ArrayList<Double>();
        var speeds = new ArrayList<Double>();
        double curvature = 0;
        int fragmentCount = 0;
        for (var exposure : observation.exposures()) {
            checkpoint.run();
            var frame = new JsonObject();
            frame.addProperty("frame", exposure.frame());
            frame.addProperty("startUtc", java.time.Instant.ofEpochMilli(Math.round((exposure.startJd() - 2440587.5) * 86400000)).toString());
            frame.addProperty("exposureSeconds", (exposure.endJd() - exposure.startJd()) * 86400);
            frames.add(frame);
            try {
                var path = exposurePath(exposure.startJd(), exposure.endJd(), samples, stepSeconds);
                var distances = new ArrayList<Double>();
                var cross = new ArrayList<Double>();
                var beyond = new ArrayList<Double>();
                var directions = new ArrayList<Double>();
                var points = new JsonArray();
                for (var fragment : exposure.fragments()) {
                    checkpoint.run();
                    double[] p = vector(fragment.ra(), fragment.dec());
                    Distance d = nearest(p, path);
                    distances.add(d.finite);
                    cross.add(d.perpendicular);
                    beyond.add(d.beyond);
                    points.add(pair(fragment.ra(), fragment.dec()));
                    if (fragment.axisStartRa() != null && fragment.axisStartDec() != null
                            && fragment.axisEndRa() != null && fragment.axisEndDec() != null) {
                        double[] axis = cross(vector(fragment.axisStartRa(), fragment.axisStartDec()), vector(fragment.axisEndRa(), fragment.axisEndDec()));
                        double[] predicted = cross(d.a, d.b);
                        double angle = normalAngle(axis, predicted);
                        if (Double.isFinite(angle)) directions.add(Math.min(angle, 180 - angle));
                    }
                }
                var localSpeeds = new ArrayList<Double>();
                for (int i = 1; i < path.size(); i++) {
                    double dt = (path.get(i).jd - path.get(i - 1).jd) * 86400;
                    localSpeeds.add(angle(vector(path.get(i - 1)), vector(path.get(i))) * ARCSEC / dt);
                    if (i + 1 < path.size()) {
                        var prev = path.get(i - 1);
                        var mid = path.get(i);
                        var next = path.get(i + 1);
                        double f = (mid.jd - prev.jd) / (next.jd - prev.jd);
                        curvature = Math.max(curvature, angle(vector(mid), interpolate(vector(prev), vector(next), f)) * ARCSEC / 3);
                    }
                }
                double median = median(distances);
                medians.add(median);
                perpendicular.add(median(cross));
                speeds.add(median(localSpeeds));
                if (!directions.isEmpty()) directionDifferences.add(median(directions));
                fragmentCount += distances.size();
                frame.addProperty("medianDistanceArcsec", median);
                frame.addProperty("p90DistanceArcsec", percentile(distances, .9));
                frame.addProperty("maxDistanceArcsec", Collections.max(distances));
                frame.addProperty("perpendicularArcsec", median(cross));
                frame.addProperty("beyondExposurePathArcsec", median(beyond));
                frame.addProperty("fragmentCount", distances.size());
                frame.addProperty("predictedSpeedArcsecSecond", median(localSpeeds));
                if (!directions.isEmpty()) frame.addProperty("axisDifferenceDegrees", median(directions));
                frame.add("observed", points);
                var predicted = new JsonArray();
                int stride = Math.max(1, path.size() / 160);
                for (int i = 0; i < path.size(); i += stride) predicted.add(pair(path.get(i).ra, path.get(i).dec));
                if ((path.size() - 1) % stride != 0) predicted.add(pair(path.getLast().ra, path.getLast().dec));
                frame.add("predicted", predicted);
            } catch (IllegalArgumentException e) {
                frame.addProperty("unavailableReason", e.getMessage());
            }
        }
        result.add("exposures", frames);
        result.addProperty("coveredExposures", medians.size());
        result.addProperty("totalExposures", observation.exposures().size());
        result.addProperty("fragmentCount", fragmentCount);
        result.addProperty("completeCoverage", medians.size() == observation.exposures().size());
        result.addProperty("estimatedInterpolationErrorArcsec", curvature);
        if (!medians.isEmpty()) {
            result.addProperty("medianDistanceArcsec", median(medians));
            result.addProperty("perpendicularArcsec", median(perpendicular));
            double scale = median(observation.exposures().stream().flatMap(e -> e.fragments().stream()).map(StreakTrackObservation.Fragment::pixelScaleArcsec).toList());
            result.addProperty("medianDistancePixels", median(medians) / scale);
            result.addProperty("predictedSpeedArcsecSecond", median(speeds));
            result.addProperty("ut1TimingSensitivityArcsec", median(speeds) * .9);
            if (!directionDifferences.isEmpty())
                result.addProperty("axisDifferenceDegrees", median(directionDifferences));
        }
        addCentroidMotion(result, observation, samples, stepSeconds);
        return result;
    }

    /**
     * Extracts a covered path with interpolated exposure boundaries, rejecting orbit jumps and sample gaps.
     */
    private static List<Sample> exposurePath(double start, double end, List<Sample> samples, double step) {
        if (samples.size() < 2 || start < samples.getFirst().jd - 1e-9 || end > samples.getLast().jd + 1e-9)
            throw new IllegalArgumentException("Prediction does not cover this exposure.");
        var path = new ArrayList<Sample>();
        for (int i = 1; i < samples.size(); i++) {
            var a = samples.get(i - 1);
            var b = samples.get(i);
            if (b.jd <= start || a.jd >= end) continue;
            if ((b.jd - a.jd) * 86400 > step * 1.6 + .002 || b.jd <= a.jd
                    || !Objects.equals(a.epoch, b.epoch) || !Objects.equals(a.source, b.source))
                throw new IllegalArgumentException("Prediction has a gap or changes orbital data during this exposure.");
            if (path.isEmpty()) path.add(at(start, a, b));
            path.add(b.jd < end ? b : at(end, a, b));
        }
        if (path.size() < 2 || Math.abs(path.getLast().jd - end) > 1e-9)
            throw new IllegalArgumentException("Prediction does not cover the complete exposure.");
        return path;
    }

    /**
     * Separates a descriptive midpoint-based speed estimate from its exposure-limited possible time interval.
     */
    private static void addCentroidMotion(JsonObject out, StreakTrackObservation o, List<Sample> samples, double step) {
        var first = o.exposures().getFirst();
        var last = o.exposures().getLast();
        var a = first.fragments().getFirst();
        var b = last.fragments().getFirst();
        double separation = angle(vector(a.ra(), a.dec()), vector(b.ra(), b.dec())) * ARCSEC;
        double midSpan = ((last.startJd() + last.endJd()) - (first.startJd() + first.endJd())) * 43200;
        double minSpan = (last.startJd() - first.endJd()) * 86400;
        double maxSpan = (last.endJd() - first.startJd()) * 86400;
        if (midSpan > 0) out.addProperty("centroidSpeedArcsecSecond", separation / midSpan);
        if (maxSpan > 0) out.addProperty("centroidSpeedLowerArcsecSecond", separation / maxSpan);
        if (minSpan > 0) out.addProperty("centroidSpeedUpperArcsecSecond", separation / minSpan);
        if (samples.size() < 2) return;
        // Normals of the oriented great circles compare chronological direction without an RA-wrap discontinuity.
        double amid = (first.startJd() + first.endJd()) / 2, bmid = (last.startJd() + last.endJd()) / 2;
        Sample pa = sampleAt(amid, samples, step), pb = sampleAt(bmid, samples, step);
        if (pa != null && pb != null) {
            double d = normalAngle(cross(vector(a.ra(), a.dec()), vector(b.ra(), b.dec())), cross(vector(pa), vector(pb)));
            if (Double.isFinite(d)) out.addProperty("motionDirectionDifferenceDegrees", d);
        }
    }

    /**
     * Finds the bracketing prediction for a descriptive motion direction, without extrapolating.
     */
    private static Sample sampleAt(double jd, List<Sample> samples, double step) {
        for (int i = 1; i < samples.size(); i++)
            if (samples.get(i - 1).jd <= jd && samples.get(i).jd >= jd
                    && (samples.get(i).jd - samples.get(i - 1).jd) * 86400 <= step * 1.6 + .002
                    && Objects.equals(samples.get(i - 1).epoch, samples.get(i).epoch)
                    && Objects.equals(samples.get(i - 1).source, samples.get(i).source))
                return at(jd, samples.get(i - 1), samples.get(i));
        return null;
    }

    /**
     * Returns the closest point on the union of short great-circle arcs.
     */
    private static Distance nearest(double[] p, List<Sample> path) {
        Distance best = null;
        for (int i = 1; i < path.size(); i++) {
            double[] a = vector(path.get(i - 1)), b = vector(path.get(i)), n = cross(a, b);
            double length = norm(n), distance, perpendicular, beyond;
            if (length < 1e-15) {
                distance = angle(p, a);
                perpendicular = distance;
                beyond = 0;
            } else {
                n = scale(n, 1 / length);
                double dot = dot(p, n);
                double[] q = unit(subtract(p, scale(n, dot)));
                boolean inside = dot(cross(a, q), n) >= -1e-13 && dot(cross(q, b), n) >= -1e-13 && dot(q, p) > 0;
                perpendicular = Math.asin(Math.min(1, Math.abs(dot)));
                distance = inside ? perpendicular : Math.min(angle(p, a), angle(p, b));
                beyond = inside ? 0 : Math.acos(Math.min(1, Math.max(-1, Math.cos(distance) / Math.max(1e-15, Math.cos(perpendicular)))));
            }
            if (best == null || distance * ARCSEC < best.finite)
                best = new Distance(distance * ARCSEC, perpendicular * ARCSEC, beyond * ARCSEC, a, b);
        }
        return best;
    }

    /**
     * Interpolates on the sphere and preserves the bracket's orbital provenance.
     */
    private static Sample at(double jd, Sample a, Sample b) {
        double[] v = interpolate(vector(a), vector(b), (jd - a.jd) / (b.jd - a.jd));
        return new Sample(jd, (Math.toDegrees(Math.atan2(v[1], v[0])) + 360) % 360,
                Math.toDegrees(Math.asin(Math.clamp(v[2], -1, 1))), a.epoch, a.source);
    }

    /**
     * Normalized chord interpolation; short arcs are explicitly constrained by the acquisition service.
     */
    private static double[] interpolate(double[] a, double[] b, double f) {
        return unit(new double[]{a[0] * (1 - f) + b[0] * f, a[1] * (1 - f) + b[1] * f, a[2] * (1 - f) + b[2] * f});
    }

    /**
     * Converts a provider sample to a unit sky vector.
     */
    private static double[] vector(Sample s) {
        return vector(s.ra, s.dec);
    }

    /**
     * Converts degrees to a unit sky vector.
     */
    private static double[] vector(double ra, double dec) {
        double a = Math.toRadians(ra), d = Math.toRadians(dec);
        return new double[]{Math.cos(d) * Math.cos(a), Math.cos(d) * Math.sin(a), Math.sin(d)};
    }

    /**
     * Stable unsigned angle in radians.
     */
    private static double angle(double[] a, double[] b) {
        return Math.atan2(norm(cross(a, b)), dot(a, b));
    }

    /**
     * Angle between non-degenerate oriented planes, in degrees.
     */
    private static double normalAngle(double[] a, double[] b) {
        return norm(a) < 1e-12 || norm(b) < 1e-12 ? Double.NaN : Math.toDegrees(angle(unit(a), unit(b)));
    }

    /**
     * Vector cross product.
     */
    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /**
     * Vector dot product.
     */
    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * Vector norm.
     */
    private static double norm(double[] v) {
        return Math.sqrt(dot(v, v));
    }

    /**
     * Scales a vector.
     */
    private static double[] scale(double[] v, double s) {
        return new double[]{v[0] * s, v[1] * s, v[2] * s};
    }

    /**
     * Vector subtraction.
     */
    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    /**
     * Unit vector, preserving a degenerate zero vector for the caller's explicit checks.
     */
    private static double[] unit(double[] v) {
        return scale(v, 1 / Math.max(1e-300, norm(v)));
    }

    /**
     * Equal-observation median.
     */
    private static double median(List<Double> values) {
        return percentile(values, .5);
    }

    /**
     * Linear-interpolated percentile on a detached sorted copy.
     */
    private static double percentile(List<Double> values, double p) {
        var a = new ArrayList<>(values);
        a.sort(Double::compare);
        double x = (a.size() - 1) * p;
        int lo = (int) x;
        return a.get(lo) + (a.get((int) Math.ceil(x)) - a.get(lo)) * (x - lo);
    }

    /**
     * Coordinate pair for the self-contained report overlay.
     */
    private static JsonArray pair(double a, double b) {
        var out = new JsonArray();
        out.add(a);
        out.add(b);
        return out;
    }
}
