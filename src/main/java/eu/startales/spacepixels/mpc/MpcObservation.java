package eu.startales.spacepixels.mpc;

/**
 * One astrometric observation of a moving object for the Minor Planet Center, in the units of ADES: the exposure
 * midpoint in UTC, J2000 (ICRF) right ascension and declination in degrees, and their random 1-sigma uncertainties in
 * arcseconds, the RA one scaled by cos Dec. The frame number (one-based) and pixel position are provenance kept with
 * it; they are not exported.
 *
 * @param frameNumber    one-based frame in the session
 * @param midpointMillis exposure midpoint, UTC epoch milliseconds
 * @param exposureMillis exposure length, or 0 when unknown
 * @param x              measured pixel column
 * @param y              measured pixel row
 * @param raDegrees      right ascension, 0 to 360
 * @param decDegrees     declination
 * @param rmsRaArcsec    random uncertainty of RA times cos Dec, 1 sigma
 * @param rmsDecArcsec   random uncertainty of Dec, 1 sigma
 * @param astCat         ADES star catalogue of the reduction, such as {@code Gaia3}
 * @param notes          ADES note codes, such as {@code K} for a stacked image, or empty
 */
public record MpcObservation(int frameNumber, long midpointMillis, long exposureMillis, double x, double y,
                             double raDegrees, double decDegrees, double rmsRaArcsec, double rmsDecArcsec,
                             String astCat, String notes) {
}
