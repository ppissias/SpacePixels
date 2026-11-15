package eu.startales.spacepixels.report;

import eu.startales.spacepixels.mpc.LightCurveTestAccess;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Comparing saved findings with predictions after the report: known, ambiguous, no match. */
public class PredictionMatchTest {
    private static final long T0 = 1_660_000_000_000L;

    /** A predicted object moving 2 px per minute along x from (100, 200), sampled every minute. */
    private static SolarSystemObjects.Body body(String name, double offsetPixels) {
        var wcs = LightCurveTestAccess.wcs();
        var body = new SolarSystemObjects.Body();
        body.name = name;
        for (int k = -5; k <= 40; k++) {
            var sky = wcs.pixelToSky(100 + 2 * k + offsetPixels, 200);
            var sample = new SolarSystemObjects.Sample();
            sample.timeMillis = T0 + k * 60_000L;
            sample.ra = sky.getRaDegrees();
            sample.dec = sky.getDecDegrees();
            sample.magnitude = 23.2;
            body.samples.add(sample);
        }
        return body;
    }

    private static SolarSystemObjects objects(SolarSystemObjects.Body... bodies) {
        var objects = new SolarSystemObjects();
        objects.magnitudeLimit = 25;
        objects.startMillis = T0 - 600_000;
        objects.endMillis = T0 + 3_000_000;
        objects.bodies = new ArrayList<>(List.of(bodies));
        return objects;
    }

    private static List<PredictionMatch.Point> track() {
        var points = new ArrayList<PredictionMatch.Point>();
        for (int k = 0; k < 6; k++) points.add(new PredictionMatch.Point(k + 1, T0 + k * 300_000L, 100 + 10 * k + 0.3, 200.2));
        return points;
    }

    @Test
    public void identifiesTracksAgain() {
        var wcs = LightCurveTestAccess.wcs();
        var known = PredictionMatch.track(track(), objects(body("800463 (2014 FM90)", 0), body("far", 60)), wcs, 1, 566);
        assertEquals("known", known.status());
        assertEquals(List.of("800463 (2014 FM90)"), known.names());
        var none = PredictionMatch.track(track(), objects(body("far", 60)), wcs, 1, 566);
        assertEquals("no-known-match", none.status());
        assertTrue(none.note(), none.note().contains("V 25.0"));
        var two = PredictionMatch.track(track(), objects(body("A", 0), body("B", 0.2)), wcs, 1, 566);
        assertEquals("check", two.status());
        assertNull("no predictions", PredictionMatch.track(track(), null, wcs, 1, 566));
    }

    @Test
    public void identifiesFaintMoversAgain() {
        var wcs = LightCurveTestAccess.wcs();
        var fitted = new ArrayList<PredictionMatch.Point>();
        for (int k = 0; k < 6; k++) fitted.add(new PredictionMatch.Point(k + 1, T0 + k * 300_000L, 100 + 10 * k, 200));
        var known = PredictionMatch.faintMover(100, 200, T0, 2, 0, fitted, objects(body("FM object", 0.5)), wcs, 3, 566);
        assertEquals("known", known.status());
        var fast = PredictionMatch.faintMover(100, 200, T0, 2.5, 0, fitted, objects(body("FM object", 0.5)), wcs, 3, 566);
        assertEquals("motion 0.5 px/min off", "no-known-match", fast.status());
    }
}
