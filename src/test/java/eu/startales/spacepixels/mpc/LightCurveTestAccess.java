package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;

import java.util.List;

/** The light-curve tests' synthetic sky, shared with tests in other packages. */
public final class LightCurveTestAccess {
    private LightCurveTestAccess() { }

    public static WcsCoordinateTransformer wcs() { return LightCurveTest.wcs(); }

    public static short[][] frame(double[][] stars, double transparency, long seed) { return LightCurveTest.frame(stars, transparency, seed); }

    public static List<SkyCatalogue.Star> catalogue(WcsCoordinateTransformer wcs, double[][] stars) { return LightCurveTest.catalogue(wcs, stars); }
}
