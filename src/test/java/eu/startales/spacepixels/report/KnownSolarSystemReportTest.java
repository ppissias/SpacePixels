package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;

/** Synthetic controls for multi-frame prediction agreement, unavailable uncertainties, ambiguous identities and clock sign. */
public class KnownSolarSystemReportTest {
    private static final long START = 1_700_000_000_000L;

    /** Sparse actual frame identities and an RA wrap retain all measured residuals and the physical rate. */
    @Test public void comparesAtLeastThreeActualFramesAndRates() {
        var match = KnownSolarSystemReport.compare("T1", observations(0), body("first"), wcs(), .5);
        assertNotNull(match);
        assertTrue(match.positionAgreement());
        assertTrue(match.ephemerisUncertaintyAvailable());
        assertEquals(4, match.frames());
        assertEquals(14, match.residuals().getLast().frameNumber());
        assertTrue(match.rmsPixels() < .0001);
        assertTrue(match.motionDifferencePixelsPerMinute() < .0001);
        assertNull(KnownSolarSystemReport.compare("T1", observations(0).subList(0, 2), body("first"), wcs(), .5));
    }

    /** Negative UTC milliseconds and a zero epoch obey the engine's exact -1 missing-time convention. */
    @Test public void comparesPredictionsAndCentroidsBeforeTheUnixEpoch() {
        var body = body("historical control");
        long translate = START + 720_000;
        for (var sample : body.samples) sample.timeMillis -= translate;
        var points = observations(0).stream().map(p -> new ReportTrackMotion.Point(p.frameIndex(),
                p.midpointMillis() - translate, p.x(), p.y())).toList();
        var match = KnownSolarSystemReport.compare("T1", points, body, wcs(), .5);
        assertNotNull(match); assertTrue(match.positionAgreement());
        assertEquals(0, match.residuals().getLast().midpointMillis());
    }

    /** Missing formal ephemeris errors stay explicit; they cannot authorize a clock inference. */
    @Test public void unavailableErrorsDoNotBecomeZeroUncertainty() {
        var body = body("first");
        for (var sample : body.samples) sample.raSigmaArcsec = null;
        var match = KnownSolarSystemReport.compare("T1", observations(0), body, wcs(), .5);
        assertNotNull(match);
        assertFalse(match.ephemerisUncertaintyAvailable());
        assertNull(match.residuals().getFirst().ephemerisThreeSigmaPixels());
        assertEquals("unavailable", KnownSolarSystemReport.clock(List.of(row("one", match))).status());
    }

    /** Similar sky positions with reversed velocity or a transverse offset cannot masquerade as a clock shift. */
    @Test public void rejectsWrongMotionAndCrossTrackOffsets() {
        var reverse = new ArrayList<ReportTrackMotion.Point>();
        for (var p : observations(0)) reverse.add(new ReportTrackMotion.Point(p.frameIndex(), p.midpointMillis(), 100 - p.x(), p.y()));
        assertNull(KnownSolarSystemReport.compare("T1", reverse, body("first"), wcs(), .5));
        var transverse = new ArrayList<ReportTrackMotion.Point>();
        for (var p : observations(0)) transverse.add(new ReportTrackMotion.Point(p.frameIndex(), p.midpointMillis(), p.x(), p.y() + 10));
        assertNull(KnownSolarSystemReport.compare("T1", transverse, body("first"), wcs(), .5));
    }

    /** A positive diagnostic means add seconds to the stored time; two independent objects must support the same sign. */
    @Test public void clockRequiresIndependentUnambiguousAgreement() {
        var a = KnownSolarSystemReport.compare("T1", observations(600), body("one"), wcs(), .5);
        var b = KnownSolarSystemReport.compare("T2", observations(600), body("two"), wcs(), .5);
        assertNotNull(a); assertNotNull(b);
        assertFalse(a.positionAgreement());
        assertEquals(600, a.suggestedTimeOffsetSeconds(), .01);
        var clock = KnownSolarSystemReport.clock(List.of(row("one", a), row("two", b)));
        assertEquals("possible-common-offset", clock.status());
        assertEquals(600, clock.suggestedOffsetSeconds(), .01);
        assertTrue(clock.explanation().contains("No timestamp has been changed"));
        assertEquals("unavailable", KnownSolarSystemReport.clock(List.of(row("one", a))).status());
        assertEquals("unavailable", KnownSolarSystemReport.clock(List.of(row("one", a), row("two", a))).status());
        assertEquals("unavailable", KnownSolarSystemReport.clock(List.of(row("same", a), row("same", b))).status());
        var reverse = KnownSolarSystemReport.compare("T2", observations(-600), body("two"), wcs(), .5);
        assertEquals("no-common-offset", KnownSolarSystemReport.clock(List.of(row("one", a), row("two", reverse))).status());
    }

    /** Expanded saved samples verify the motivating two-hour local-time error rather than trusting a linear extrapolation. */
    @Test public void clockChecksTwoHourOffsetsInsideExtendedSavedEphemerides() {
        var a = KnownSolarSystemReport.compare("T1", observations(7200), body("one"), wcs(), .5);
        var b = KnownSolarSystemReport.compare("T2", observations(7200), body("two"), wcs(), .5);
        assertNotNull(a); assertNotNull(b);
        assertEquals(7200, a.suggestedTimeOffsetSeconds(), .05);
        assertEquals(4, a.shiftedFrames());
        assertTrue(a.shiftedRmsPixels() < .0001);
        assertEquals(4, a.shiftedResiduals().size());
        assertEquals(7_200_000, a.shiftedResiduals().getFirst().predictionTimeMillis() - a.shiftedResiduals().getFirst().midpointMillis());
        var clock = KnownSolarSystemReport.clock(List.of(row("one", a), row("two", b)));
        assertEquals("possible-common-offset", clock.status());
        assertEquals(7200, clock.suggestedOffsetSeconds(), .05);
        var earlyA = KnownSolarSystemReport.compare("T1", observations(-7200), body("one"), wcs(), .5);
        var earlyB = KnownSolarSystemReport.compare("T2", observations(-7200), body("two"), wcs(), .5);
        var earlyClock = KnownSolarSystemReport.clock(List.of(row("one", earlyA), row("two", earlyB)));
        assertEquals("possible-common-offset", earlyClock.status());
        assertEquals(-7200, earlyClock.suggestedOffsetSeconds(), .05);
        var changingErrors = body("varying uncertainty");
        for (var sample : changingErrors.samples) if (sample.timeMillis >= START + 120 * 60_000L) {
            sample.raSigmaArcsec = 1.0; sample.decSigmaArcsec = 1.0;
        }
        var wider = KnownSolarSystemReport.compare("T3", observations(7200), changingErrors, wcs(), .5);
        assertNotNull(wider);
        assertTrue("The time interval must include uncertainty at shifted prediction times", wider.timeToleranceSeconds() > a.timeToleranceSeconds() * 2);
    }

    /** A path shorter than its positional review allowance cannot justify a clock-offset measurement. */
    @Test public void unresolvedMeasuredMotionDoesNotInventClockEvidence() {
        var shortTrack = new ArrayList<ReportTrackMotion.Point>();
        for (int i = 0; i < 4; i++) shortTrack.add(new ReportTrackMotion.Point(i, START + i * 10_000L, 50 + i / 12.0, 50));
        var match = KnownSolarSystemReport.compare("T1", shortTrack, body("one"), wcs(), .5);
        assertNotNull(match); assertTrue(match.positionAgreement());
        assertNull(match.suggestedTimeOffsetSeconds());
        assertTrue(match.shiftedResiduals().isEmpty());
    }

    /** A time-shift suggestion must be checked on stored sampled ephemerides rather than extrapolated indefinitely. */
    @Test public void insufficientTimeCoverageAndMalformedSamplesStayUnavailable() {
        var body = body("first");
        body.samples = new ArrayList<>(body.samples.stream().filter(s -> s.timeMillis >= START && s.timeMillis <= START + 1_200_000).toList());
        assertNull(KnownSolarSystemReport.compare("T1", observations(60_000), body, wcs(), .5));
        body = body("first");
        body.samples.get(1).timeMillis = body.samples.getFirst().timeMillis;
        assertNull(KnownSolarSystemReport.compare("T1", observations(0), body, wcs(), .5));
    }

    /** Supplies one compared object without assuming that its name proves identity. */
    private static KnownSolarSystemReport.Body row(String name, KnownSolarSystemReport.Comparison match) {
        return new KnownSolarSystemReport.Body(name, false, "synthetic", 18.0, 18.0, 4, 4, 4, 0, List.of(match), List.of());
    }

    /** Observed centroids follow the predicted path at a deliberately shifted capture time. */
    private static List<ReportTrackMotion.Point> observations(double offsetSeconds) {
        var output = new ArrayList<ReportTrackMotion.Point>();
        int[] indices = {0, 2, 7, 13};
        for (int i = 0; i < indices.length; i++) output.add(new ReportTrackMotion.Point(indices[i], START + i * 240_000,
                50 + .5 * (i * 4 + offsetSeconds / 60), 50));
        return output;
    }

    /** Finite saved samples bracket the observing session, including room for a clock diagnostic. */
    private static SolarSystemObjects.Body body(String name) {
        var body = new SolarSystemObjects.Body(); body.name = name;
        for (int minutes = -840; minutes <= 840; minutes += 10) {
            var sky = wcs().pixelToSky(50 + .5 * minutes, 50);
            var p = new SolarSystemObjects.Sample(); p.timeMillis = START + minutes * 60_000L;
            p.ra = sky.getRaDegrees(); p.dec = sky.getDecDegrees(); p.raSigmaArcsec = .1; p.decSigmaArcsec = .1;
            body.samples.add(p);
        }
        return body;
    }

    /** Solved one-arcsecond pixels crossing RA zero exercise interpolation and sky geometry without provider calls. */
    private static WcsCoordinateTransformer wcs() {
        var h = new HashMap<String, String>();
        h.put("CTYPE1", "RA---TAN"); h.put("CTYPE2", "DEC--TAN");
        h.put("CRPIX1", "51"); h.put("CRPIX2", "51"); h.put("CRVAL1", "359.99999"); h.put("CRVAL2", "0");
        h.put("CD1_1", "-0.000277777777778"); h.put("CD1_2", "0");
        h.put("CD2_1", "0"); h.put("CD2_2", "0.000277777777778");
        return WcsCoordinateTransformer.fromHeader(h);
    }
}
