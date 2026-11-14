package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SkyMath;
import io.github.ppissias.jtransient.model.DetectedObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns measured detections into MPC observations. A detection's centroid goes through the session's header plate
 * solution and the Gaia correction fitted for that frame ({@link PlateSolutionCorrection}): that fit matches the Gaia
 * DR3 stars, moved to the frame's date, to the detected stars of every frame, so the positions are tied to Gaia DR3
 * ({@code astCat Gaia3}). Without a correction fitted for this solution the positions are not tied to a known
 * catalogue, and {@link #create} says so instead of reducing.
 * <p>
 * The random uncertainty per axis combines the centroid error, FWHM / (2.355 x SNR) for a Gaussian image with the
 * detection's integrated sigma as its SNR, and the plate error: the fit's median star distance, divided by 1.1774 to
 * turn the median of a two-dimensional Gaussian distance into one axis's sigma. Catalogue systematics are left out,
 * as ADES asks.
 */
public final class AstrometricReduction {
    /** ADES name of the Gaia DR3 catalogue. */
    public static final String GAIA_DR3 = "Gaia3";
    /** The median of a 2-D Gaussian distance in units of one axis's sigma: sqrt(2 ln 2). */
    static final double MEDIAN_DISTANCE_PER_SIGMA = Math.sqrt(2 * Math.log(2));
    /** FWHM over sigma for a Gaussian. */
    static final double FWHM_PER_SIGMA = 2 * Math.sqrt(2 * Math.log(2));
    /** The smallest uncertainty written, in arcseconds: ADES carries three decimals here. */
    static final double MIN_RMS_ARCSEC = 0.001;

    private final WcsCoordinateTransformer header;
    private final PlateSolutionCorrection correction;
    private final FitsFileInformation[] files;
    private final double platePixels;

    private AstrometricReduction(WcsCoordinateTransformer header, PlateSolutionCorrection correction,
                                 FitsFileInformation[] files, double platePixels) {
        this.header = header;
        this.correction = correction;
        this.files = files;
        this.platePixels = platePixels;
    }

    /** The reduction, or why there is none. */
    public record Result(AstrometricReduction reduction, String problem) { }

    /**
     * A reduction for these frames with their header plate solution and the session's correction, which may be null.
     * Nothing is retained but the given objects, which the caller must not change.
     */
    public static Result create(WcsCoordinateTransformer header, PlateSolutionCorrection correction, FitsFileInformation[] files) {
        if (header == null) {
            return new Result(null, "The frames have no plate solution.");
        }
        if (correction == null || correction.solutionKey == null || !correction.solutionKey.equals(header.solutionKey())) {
            return new Result(null, "The plate solution has not been checked against Gaia DR3 stars. Fetch the sky catalogue "
                    + "so SpacePixels fits the plate correction; the positions are reduced against those stars.");
        }
        double median = correction.global != null ? correction.afterMedianPx : correction.beforeMedianPx;
        if (!Double.isFinite(median) || median < 0) {
            return new Result(null, "The plate correction holds no star residuals; fit it again.");
        }
        return new Result(new AstrometricReduction(header, correction, files, median / MEDIAN_DISTANCE_PER_SIGMA), null);
    }

    /** The plate solution's random error along one axis, in pixels, from the Gaia stars. */
    public double plateSigmaPixels() {
        return platePixels;
    }

    /**
     * The observations of a track's detections in time order, one per detection whose frame, time and position are
     * known; others are left out.
     */
    public List<MpcObservation> reduce(List<DetectedObject> detections) {
        var observations = new ArrayList<MpcObservation>();
        for (DetectedObject point : detections) {
            MpcObservation observation = reduce(point);
            if (observation != null) observations.add(observation);
        }
        observations.sort(java.util.Comparator.comparingLong(MpcObservation::midpointMillis));
        return observations;
    }

    /** The observations of a report's measured positions, in time order; positions without a frame or time are left out. */
    public List<MpcObservation> reducePositions(List<ReportedObjects.Position> positions) {
        var observations = new ArrayList<MpcObservation>();
        for (var p : positions) {
            MpcObservation observation = observe(p.frameNumber() - 1, p.x(), p.y(), p.fwhm(), p.snr());
            if (observation != null) observations.add(observation);
        }
        observations.sort(java.util.Comparator.comparingLong(MpcObservation::midpointMillis));
        return observations;
    }

    /** One detection's observation, or null when its frame, time or sky position is unknown. */
    MpcObservation reduce(DetectedObject point) {
        return point == null ? null : observe(point.sourceFrameIndex, point.x, point.y, point.fwhm, point.integratedSigma);
    }

    /**
     * The observation of a centroid on a zero-based frame, with the detection's FWHM in pixels and signal-to-noise
     * (either null or not positive when unknown: then only the plate error is counted). Null when the frame, its time
     * or the sky position is unknown.
     */
    MpcObservation observe(int frameIndex, double x, double y, Double fwhm, Double snr) {
        if (frameIndex < 0 || frameIndex >= files.length || files[frameIndex] == null || !Double.isFinite(x) || !Double.isFinite(y)) {
            return null;
        }
        FitsFileInformation file = files[frameIndex];
        if (file.getObservationTimestamp() <= 0) {
            return null;
        }
        long exposure = Math.max(0, file.getExposureDurationMillis());
        long midpoint = file.getObservationTimestamp() + exposure / 2;
        WcsCoordinateTransformer frame = correction.apply(header, file.getFileName(), file.getSizeWidth(), file.getSizeHeight());
        var sky = frame.pixelToSky(x, y);
        var right = frame.pixelToSky(x + 1, y);
        var down = frame.pixelToSky(x, y + 1);
        if (sky == null || right == null || down == null) {
            return null;
        }
        double scale = (SkyMath.separationArcsec(sky.getRaDegrees(), sky.getDecDegrees(), right.getRaDegrees(), right.getDecDegrees())
                + SkyMath.separationArcsec(sky.getRaDegrees(), sky.getDecDegrees(), down.getRaDegrees(), down.getDecDegrees())) / 2;
        double centroid = fwhm != null && snr != null && fwhm > 0 && snr > 0 ? fwhm / (FWHM_PER_SIGMA * snr) : 0;
        double sigma = Math.max(MIN_RMS_ARCSEC, scale * Math.hypot(centroid, platePixels));
        return new MpcObservation(frameIndex + 1, midpoint, exposure, x, y,
                SkyMath.normalizeDegrees(sky.getRaDegrees()), sky.getDecDegrees(), sigma, sigma, GAIA_DR3, "");
    }
}
