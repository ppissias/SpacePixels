package eu.startales.spacepixels.report.lookup;

import com.google.gson.*;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Scientific regression checks for finite exposure paths, independence, wraparound and incomplete predictions.
 */
public class SatelliteTrackComparisonTest {
    private static final double JD = 2461000;

    /**
     * Fragments Anywhere In Their Exposure Fit Without Manufacturing Midpoint Residuals.
     */
    @Test
    public void fragmentsAnywhereInTheirExposureFitWithoutManufacturingMidpointResiduals() {
        var observation = observation(List.of(exposure(1, 0, 100, .01), exposure(2, 200, 300, .29)));
        var result = SatelliteTrackComparison.compare(observation, predictions(0, 300, 0), 1);
        assertEquals(0, result.get("medianDistanceArcsec").getAsDouble(), 1e-5);
        assertEquals(2, result.get("coveredExposures").getAsInt());
        assertTrue(result.get("centroidSpeedUpperArcsecSecond").getAsDouble() > result.get("predictedSpeedArcsecSecond").getAsDouble());
        assertTrue(result.get("centroidSpeedLowerArcsecSecond").getAsDouble() < result.get("predictedSpeedArcsecSecond").getAsDouble());
        assertEquals(0, result.get("motionDirectionDifferenceDegrees").getAsDouble(), 1e-5);
    }

    /**
     * Wrong Candidate And Reversed Exposure Associations Fail Despite Following The Same Sky Line.
     */
    @Test
    public void wrongCandidateAndReversedExposureAssociationsFailDespiteFollowingTheSameSkyLine() {
        var o = observation(List.of(exposure(1, 0, 100, .01), exposure(2, 200, 300, .29)));
        assertTrue(SatelliteTrackComparison.compare(o, predictions(0, 300, 1), 1).get("medianDistanceArcsec").getAsDouble() > 3000);
        var reversed = observation(List.of(exposure(1, 0, 100, .29), exposure(2, 200, 300, .01)));
        var result = SatelliteTrackComparison.compare(reversed, predictions(0, 300, 0), 1);
        assertTrue(result.get("medianDistanceArcsec").getAsDouble() > 600);
        assertEquals(180, result.get("motionDirectionDifferenceDegrees").getAsDouble(), 1e-5);
    }

    /**
     * Independent Exposures Have Equal Weight Regardless Of Fragment Count.
     */
    @Test
    public void independentExposuresHaveEqualWeightRegardlessOfFragmentCount() {
        var first = new StreakTrackObservation.Exposure(1, JD, JD + 100.0 / 86400, Collections.nCopies(99, fragment(.05, .001)));
        var second = new StreakTrackObservation.Exposure(2, JD + 200.0 / 86400, JD + 300.0 / 86400, List.of(fragment(.25, .003)));
        var result = SatelliteTrackComparison.compare(observation(List.of(first, second)), predictions(0, 300, 0), 1);
        assertEquals(7.2, result.get("medianDistanceArcsec").getAsDouble(), .001);
        assertEquals(100, result.get("fragmentCount").getAsInt());
    }

    /**
     * A Fragment Outside The Finite Exposure Path Retains Its Along Path Disagreement.
     */
    @Test
    public void aFragmentOutsideTheFiniteExposurePathRetainsItsAlongPathDisagreement() {
        var o = observation(List.of(exposure(1, 0, 100, .15), exposure(2, 200, 300, .25)));
        var frame = SatelliteTrackComparison.compare(o, predictions(0, 300, 0), 1).getAsJsonArray("exposures").get(0).getAsJsonObject();
        assertEquals(180, frame.get("medianDistanceArcsec").getAsDouble(), .001);
        assertEquals(0, frame.get("perpendicularArcsec").getAsDouble(), 1e-6);
        assertEquals(180, frame.get("beyondExposurePathArcsec").getAsDouble(), .001);
    }

    /**
     * Missing Samples And Orbit Changes Are Explicit Instead Of Being Interpolated Across.
     */
    @Test
    public void missingSamplesAndOrbitChangesAreExplicitInsteadOfBeingInterpolatedAcross() {
        var o = observation(List.of(exposure(1, 0, 100, .05), exposure(2, 200, 300, .25)));
        var path = new ArrayList<>(predictions(0, 300, 0));
        path.removeIf(s -> s.jd() > JD + 220.0 / 86400 && s.jd() < JD + 240.0 / 86400);
        var result = SatelliteTrackComparison.compare(o, path, 1);
        assertFalse(result.get("completeCoverage").getAsBoolean());
        assertEquals(1, result.get("coveredExposures").getAsInt());
        path = new ArrayList<>(predictions(0, 300, 0));
        var p = path.get(50);
        path.set(50, new SatelliteTrackComparison.Sample(p.jd(), p.ra(), p.dec(), "different orbit", "test"));
        assertEquals(1, SatelliteTrackComparison.compare(o, path, 1).get("coveredExposures").getAsInt());
    }

    /**
     * Right Ascension Wrap And Near Pole Geometry Remain Finite.
     */
    @Test
    public void rightAscensionWrapAndNearPoleGeometryRemainFinite() {
        var path = new ArrayList<SatelliteTrackComparison.Sample>();
        for (int t = 0; t <= 300; t++)
            path.add(new SatelliteTrackComparison.Sample(JD + t / 86400.0, (359.9 + t * .001) % 360, 89.9, "epoch", "test"));
        var o = observation(List.of(new StreakTrackObservation.Exposure(1, JD, JD + 100.0 / 86400, List.of(fragment(359.95, 89.9))),
                new StreakTrackObservation.Exposure(2, JD + 200.0 / 86400, JD + 300.0 / 86400, List.of(fragment(.15, 89.9)))));
        var result = SatelliteTrackComparison.compare(o, path, 1);
        assertEquals(0, result.get("medianDistanceArcsec").getAsDouble(), 1e-4);
        assertTrue(Double.isFinite(result.get("predictedSpeedArcsecSecond").getAsDouble()));
    }

    /**
     * Two exposure windows with immutable, deliberately synthetic observing context.
     */
    static StreakTrackObservation observation(List<StreakTrackObservation.Exposure> exposures) {
        return new StreakTrackObservation(1, "ST1", new StreakTrackObservation.Observer(0, 0, 0.0, "synthetic"),
                Map.of("coordinateMetadata", Map.of("RADESYS", "ICRS")), exposures, List.of());
    }

    /**
     * One centroid at an arbitrary instant within a known exposure.
     */
    static StreakTrackObservation.Exposure exposure(int frame, double start, double end, double ra) {
        return new StreakTrackObservation.Exposure(frame, JD + start / 86400, JD + end / 86400, List.of(fragment(ra, 0)));
    }

    /**
     * One synthetic centroid without a measured footprint direction.
     */
    static StreakTrackObservation.Fragment fragment(double ra, double dec) {
        return new StreakTrackObservation.Fragment(1, 1, ra, dec, 1, null, null, null, null);
    }

    /**
     * Uniform motion at 3.6 arcseconds/second along the equator, sampled every second.
     */
    static List<SatelliteTrackComparison.Sample> predictions(int start, int end, double offset) {
        var out = new ArrayList<SatelliteTrackComparison.Sample>();
        for (int i = start; i <= end; i++)
            out.add(new SatelliteTrackComparison.Sample(JD + i / 86400.0, (i * .001 + offset + 360) % 360, 0, "epoch", "test"));
        return out;
    }
}
