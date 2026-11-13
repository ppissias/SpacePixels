package eu.startales.spacepixels.fits;

import io.github.ppissias.jplatesolve.SolveHints;
import eu.startales.spacepixels.util.SkyMath;
import nom.tam.fits.Header;
import java.util.LinkedHashMap;
import java.util.Map;

/** Approximate pre-registration pointing carried separately from an authoritative pixel-to-sky solution. */
public final class PlateSolveHints {
    private static final String[] KEYS = {"SPHRA", "SPHDEC", "SPHSCALE", "SPHRAD"};

    /** No mutable state. */
    private PlateSolveHints() { }

    /** Captures the source image centre and local pixel scale; returns null when no usable WCS or prior hint exists. */
    public static SolveHints fromSource(Header header) {
        Map<String, String> cards = new LinkedHashMap<>();
        header.iterator().forEachRemaining(card -> cards.put(card.getKey(), card.getValue()));
        int width = header.getIntValue("NAXIS1"), height = header.getIntValue("NAXIS2");
        WcsCoordinateTransformer wcs = WcsCoordinateTransformer.fromHeader(cards);
        if (wcs == null) return read(header, height);
        double x = (width - 1) / 2.0, y = (height - 1) / 2.0;
        var centre = wcs.pixelToSky(x, y);
        var right = wcs.pixelToSky(x + 1, y);
        var above = wcs.pixelToSky(x, y + 1);
        double sx = SkyMath.separationArcsec(centre.getRaDegrees(), centre.getDecDegrees(), right.getRaDegrees(), right.getDecDegrees());
        double sy = SkyMath.separationArcsec(centre.getRaDegrees(), centre.getDecDegrees(), above.getRaDegrees(), above.getDecDegrees());
        double scale = Math.sqrt(sx * sy);
        double diagonal = Math.hypot(width * sx, height * sy) / 3600;
        try {
            return new SolveHints(centre.getRaDegrees(), centre.getDecDegrees(), Math.min(180, Math.max(0.1, 2 * diagonal)),
                    scale, height * sy / 3600);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Reads only SpacePixels hints, never infers a solved status; height is the current output image height in pixels. */
    public static SolveHints read(Header header, int height) {
        for (String key : KEYS) if (!header.containsKey(key)) return null;
        try {
            double scale = header.getDoubleValue("SPHSCALE", Double.NaN);
            return new SolveHints(header.getDoubleValue("SPHRA", Double.NaN), header.getDoubleValue("SPHDEC", Double.NaN),
                    header.getDoubleValue("SPHRAD", Double.NaN), scale, height * scale / 3600);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Clears inherited hints before writing source-derived values; does not add any WCS cards. */
    public static void write(Header header, SolveHints hints) throws nom.tam.fits.HeaderCardException {
        for (String key : KEYS) header.deleteKey(key);
        if (hints == null) return;
        header.addValue("SPHRA", hints.raDegrees(), "Approximate ICRS RA deg; solving hint, not WCS");
        header.addValue("SPHDEC", hints.decDegrees(), "Approximate ICRS Dec deg; solving hint, not WCS");
        header.addValue("SPHSCALE", hints.arcsecPerPixel(), "Approximate pre-registration arcsec/pixel");
        header.addValue("SPHRAD", hints.radiusDegrees(), "Solving search radius deg; registration margin");
    }
}
