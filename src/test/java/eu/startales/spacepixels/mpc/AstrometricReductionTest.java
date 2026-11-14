package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import io.github.ppissias.jtransient.model.DetectedObject;
import org.junit.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Checks positions, times and uncertainties of the reduction, and that it refuses an unchecked plate solution. */
public class AstrometricReductionTest {
    private static final double SCALE_ARCSEC = 1.5;
    private static final WcsCoordinateTransformer WCS = WcsCoordinateTransformer.fromHeader(header());
    private static final FitsFileInformation[] FILES = {
            new FitsFileInformation("a.fit", "a.fit", true, 2000, 1600, Map.of("DATE-OBS", "2022-08-24T05:12:22", "EXPTIME", "120")),
            new FitsFileInformation("b.fit", "b.fit", true, 2000, 1600, Map.of("DATE-OBS", "2022-08-24T05:15:00", "EXPTIME", "120"))};

    /** The position is the plate solution's at the centroid, at the exposure midpoint; the error combines centroid and plate. */
    @Test
    public void reducesDetectionsThroughTheCheckedPlateSolution() {
        var correction = correction(WCS.solutionKey(), 0.2);
        var reduction = AstrometricReduction.create(WCS, correction, FILES).reduction();
        var late = detection(1, 1200.25, 700.5);
        var early = detection(0, 1000, 800);
        var observations = reduction.reduce(List.of(late, early));
        assertEquals("time order", List.of(1, 2), observations.stream().map(MpcObservation::frameNumber).toList());
        var first = observations.getFirst();
        assertEquals(Instant.parse("2022-08-24T05:13:22Z").toEpochMilli(), first.midpointMillis());
        assertEquals(120_000, first.exposureMillis());
        var sky = WCS.pixelToSky(1000, 800);
        assertEquals(sky.getRaDegrees(), first.raDegrees(), 1e-12);
        assertEquals(sky.getDecDegrees(), first.decDegrees(), 1e-12);
        double centroid = 3 / (2 * Math.sqrt(2 * Math.log(2)) * 30);
        double plate = 0.2 / Math.sqrt(2 * Math.log(2));
        assertEquals(SCALE_ARCSEC * Math.hypot(centroid, plate), first.rmsRaArcsec(), 1e-3);
        assertEquals(first.rmsRaArcsec(), first.rmsDecArcsec(), 0);
        assertEquals("Gaia3", first.astCat());
    }

    /** Without a correction fitted for this very solution, the positions are not tied to Gaia DR3: no reduction. */
    @Test
    public void refusesAPlateSolutionNotCheckedAgainstGaia() {
        var none = AstrometricReduction.create(WCS, null, FILES);
        assertNull(none.reduction());
        assertTrue(none.problem(), none.problem().contains("not been checked against Gaia DR3"));
        var other = AstrometricReduction.create(WCS, correction("another solution", 0.2), FILES);
        assertNull(other.reduction());
        assertTrue(AstrometricReduction.create(null, null, FILES).problem().contains("no plate solution"));
    }

    /** A detection without a known frame or time is left out rather than given a guessed time. */
    @Test
    public void leavesOutDetectionsWithoutAFrameOrTime() {
        var reduction = AstrometricReduction.create(WCS, correction(WCS.solutionKey(), 0.2), FILES).reduction();
        assertEquals(List.of(), reduction.reduce(List.of(detection(5, 10, 10), detection(-1, 10, 10))));
    }

    private static DetectedObject detection(int frame, double x, double y) {
        var point = new DetectedObject(x, y, 1000, 9);
        point.sourceFrameIndex = frame;
        point.fwhm = 3;
        point.integratedSigma = 30;
        return point;
    }

    /** A correction that found the header solution good: no global polynomial, with its median star distance. */
    private static PlateSolutionCorrection correction(String solutionKey, double medianPixels) {
        var correction = new PlateSolutionCorrection();
        correction.solutionKey = solutionKey;
        correction.width = 2000;
        correction.height = 1600;
        correction.beforeMedianPx = medianPixels;
        correction.afterMedianPx = medianPixels;
        return correction;
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "1000.5");
        header.put("CRPIX2", "800.5");
        header.put("CRVAL1", "357.2");
        header.put("CRVAL2", "-1.8");
        header.put("CD1_1", Double.toString(-SCALE_ARCSEC / 3600));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(SCALE_ARCSEC / 3600));
        return header;
    }
}
