/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The report's identification of a track or a faint mover against saved predictions, for use after the report was
 * written ("Compare again" in Asteroids &amp; Comets, when the predictions have been fetched again): the same rules as
 * the report's **Asteroids and comets** section, from the values a report saves. Offline: nothing is fetched.
 */
public final class PredictionMatch {
    /** A position: one-based frame, exposure midpoint (UTC milliseconds) and registered pixel. */
    public record Point(int frameNumber, long midpointMillis, double x, double y) { }

    /** The status ({@code known}, {@code check} or {@code no-known-match}), the agreeing names and the note. */
    public record Result(String status, List<String> names, String note) { }

    private PredictionMatch() { }

    /**
     * A track against the predictions, as the report compares it: every measured frame within the ephemeris
     * uncertainty and {@code reviewFloorPixels} (the larger of one pixel and the run's star jitter), and agreeing motion.
     * Null without predictions or with fewer than three positions.
     */
    public static Result track(List<Point> points, SolarSystemObjects objects, WcsCoordinateTransformer wcs,
                               double reviewFloorPixels, double fieldDiagonalPixels) {
        if (objects == null || objects.bodies == null || wcs == null || points.size() < 3) return null;
        var sorted = new ArrayList<>(points);
        sorted.sort(Comparator.comparingLong(Point::midpointMillis));
        var reportPoints = sorted.stream().map(p -> new ReportTrackMotion.Point(p.frameNumber() - 1, p.midpointMillis(), p.x(), p.y())).toList();
        var comparisons = new ArrayList<Map.Entry<String, KnownSolarSystemReport.Comparison>>();
        for (var body : objects.bodies) {
            var comparison = KnownSolarSystemReport.compare("", reportPoints, body, wcs, reviewFloorPixels, fieldDiagonalPixels);
            if (comparison != null) comparisons.add(Map.entry(body.name == null ? "Unnamed catalogue object" : body.name, comparison));
        }
        var verdict = KnownSolarSystemReport.trackVerdict(comparisons, limit(objects));
        return new Result(verdict.status(), verdict.names(), verdict.note());
    }

    /**
     * A faint mover against the predictions, as the report compares it: its fitted path within 2 FWHM of a predicted
     * object at every fitted position and the motion within 0.1 pixel per minute.
     *
     * @param x, y           its place at {@code referenceMillis}, registered pixels
     * @param vx, vy         its motion, pixels per minute
     */
    public static Result faintMover(double x, double y, long referenceMillis, double vx, double vy, List<Point> fitted,
                                    SolarSystemObjects objects, WcsCoordinateTransformer wcs, double fwhm, double fieldDiagonalPixels) {
        if (objects == null || objects.bodies == null || wcs == null) return null;
        var positions = fitted.stream().map(p -> new FaintMoverPresentation.Position(p.frameNumber(), p.midpointMillis(), p.x(), p.y(), null, null)).toList();
        var names = FaintMoverPresentation.compare(x, y, referenceMillis, vx, vy, wcs, objects, fwhm, positions, fieldDiagonalPixels)
                .stream().map(FaintMoverPresentation.Match::name).toList();
        if (names.size() == 1) {
            return new Result(KnownSolarSystemReport.KNOWN, names, "The fitted path and motion agree with the prediction; the path is fitted, not measured frame by frame.");
        }
        if (names.size() > 1) {
            return new Result(KnownSolarSystemReport.CHECK, names, "Agrees with " + names.size() + " known objects: the identification is ambiguous.");
        }
        return new Result(KnownSolarSystemReport.NO_KNOWN_MATCH, List.of(), KnownSolarSystemReport.noMatchNote(limit(objects)));
    }

    private static Double limit(SolarSystemObjects objects) {
        return Double.isFinite(objects.magnitudeLimit) ? objects.magnitudeLimit : null;
    }
}
