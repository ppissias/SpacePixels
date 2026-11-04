package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.model.DetectedObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Copied exposure-midpoint positions and descriptive fits shared by report comparisons; never changes findings. */
final class ReportTrackMotion {
    /** A measured centroid in pixels, identified by its original zero-based frame and UTC exposure midpoint. */
    record Point(int frameIndex, long midpointMillis, double x, double y) { }

    /** Unweighted pixel drift about a local time origin; RMS is descriptive scatter, not astrometric uncertainty. */
    record Fit(long originMillis, long endMillis, double x, double y, double vx, double vy, double rms) {
        /** Evaluates the fitted pixel position at UTC milliseconds without implying support outside the measured span. */
        double[] at(long time) {
            double minutes = (time - originMillis) / 60000.0;
            return new double[]{x + vx * minutes, y + vy * minutes};
        }
    }

    /** Static report calculations own no frame buffers. */
    private ReportTrackMotion() { }

    /** Copies finite centroids and known exposure midpoints; ambiguous frames, clocks or metadata make comparison unavailable. */
    static List<Point> points(List<DetectedObject> detections, FitsFileInformation[] files) {
        if (detections == null) return List.of();
        var points = new ArrayList<Point>();
        var identities = new HashSet<Integer>();
        var times = new HashSet<Long>();
        for (var p : detections) {
            if (p == null || p.sourceFrameIndex < 0 || !identities.add(p.sourceFrameIndex)
                    || !Double.isFinite(p.x) || !Double.isFinite(p.y)) return List.of();
            var file = files != null && p.sourceFrameIndex < files.length ? files[p.sourceFrameIndex] : null;
            long start = file == null ? p.timestamp : file.getObservationTimestamp();
            long exposure = file == null ? p.exposureDuration : file.getExposureDurationMillis();
            if (start == -1 || exposure <= 0 || start > Long.MAX_VALUE - exposure
                    || (file != null && p.timestamp != -1 && p.timestamp != start)) return List.of();
            if (file != null) {
                String system = file.getFitsHeader().get("TIMESYS");
                if (system != null && !system.replace("'", "").trim().equalsIgnoreCase("UTC")) return List.of();
            }
            long midpoint = start + exposure / 2;
            if (!times.add(midpoint)) return List.of();
            points.add(new Point(p.sourceFrameIndex, midpoint, p.x, p.y));
        }
        points.sort(Comparator.comparingLong(Point::midpointMillis));
        for (int i = 1; i < points.size(); i++) {
            if (points.get(i).frameIndex() <= points.get(i - 1).frameIndex()) return List.of();
        }
        return List.copyOf(points);
    }

    /** Fits at least two ordered positions in px/min using relative minutes, retaining no mutable input. */
    static Fit fit(List<Point> points) {
        if (points.size() < 2) return null;
        long origin = points.getFirst().midpointMillis();
        double mt = 0, mx = 0, my = 0;
        for (var p : points) {
            mt += (p.midpointMillis() - origin) / 60000.0;
            mx += p.x(); my += p.y();
        }
        int n = points.size();
        mt /= n; mx /= n; my /= n;
        double tt = 0, tx = 0, ty = 0;
        for (var p : points) {
            double t = (p.midpointMillis() - origin) / 60000.0 - mt;
            tt += t * t; tx += t * (p.x() - mx); ty += t * (p.y() - my);
        }
        if (!(tt > 0)) return null;
        double vx = tx / tt, vy = ty / tt;
        double x = mx - vx * mt, y = my - vy * mt, squares = 0;
        for (var p : points) {
            double t = (p.midpointMillis() - origin) / 60000.0;
            squares += Math.pow(p.x() - x - vx * t, 2) + Math.pow(p.y() - y - vy * t, 2);
        }
        double rms = Math.sqrt(squares / n);
        return Double.isFinite(x + y + vx + vy + rms)
                ? new Fit(origin, points.getLast().midpointMillis(), x, y, vx, vy, rms) : null;
    }

    /** Interpolates between actual track detections; returns null outside their measured time span. */
    static double[] interpolate(List<Point> points, long time) {
        if (points.size() < 2 || time < points.getFirst().midpointMillis() || time > points.getLast().midpointMillis()) return null;
        for (int i = 1; i < points.size(); i++) {
            var a = points.get(i - 1); var b = points.get(i);
            if (time > b.midpointMillis()) continue;
            double fraction = (double) (time - a.midpointMillis()) / (b.midpointMillis() - a.midpointMillis());
            return new double[]{a.x() + fraction * (b.x() - a.x()), a.y() + fraction * (b.y() - a.y())};
        }
        return null;
    }
}
