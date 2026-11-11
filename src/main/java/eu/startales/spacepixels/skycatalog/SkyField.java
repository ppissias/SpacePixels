/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.skycatalog;
import eu.startales.spacepixels.util.SkyMath;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;

import java.util.Locale;

/**
 * The part of the sky to fetch: the outline of the frame, widened by a margin so that objects at the edges and the
 * frame drift are covered.
 */
public final class SkyField {

    /** Extra width on each side, as a fraction of the frame size. */
    static final double MARGIN = 0.03;

    public double centerRa;
    public double centerDec;
    /** Corners in order around the field, in degrees. */
    public double[] cornerRa;
    public double[] cornerDec;
    /** Size across the frame, in degrees. */
    public double widthDeg;
    public double heightDeg;

    /** The field of a frame of this size, from its plate solution. */
    public static SkyField of(WcsCoordinateTransformer transformer, int width, int height) {
        SkyField field = new SkyField();
        double marginX = width * MARGIN;
        double marginY = height * MARGIN;
        double[][] corners = {
                {-0.5 - marginX, -0.5 - marginY},
                {width - 0.5 + marginX, -0.5 - marginY},
                {width - 0.5 + marginX, height - 0.5 + marginY},
                {-0.5 - marginX, height - 0.5 + marginY}};
        field.cornerRa = new double[4];
        field.cornerDec = new double[4];
        for (int i = 0; i < 4; i++) {
            WcsCoordinateTransformer.SkyCoordinate corner = transformer.pixelToSky(corners[i][0], corners[i][1]);
            field.cornerRa[i] = corner.getRaDegrees();
            field.cornerDec[i] = corner.getDecDegrees();
        }
        WcsCoordinateTransformer.SkyCoordinate center = transformer.pixelToSky((width - 1) / 2.0, (height - 1) / 2.0);
        field.centerRa = center.getRaDegrees();
        field.centerDec = center.getDecDegrees();
        field.widthDeg = SkyMath.separationDegrees(field.cornerRa[0], field.cornerDec[0], field.cornerRa[1], field.cornerDec[1]);
        field.heightDeg = SkyMath.separationDegrees(field.cornerRa[1], field.cornerDec[1], field.cornerRa[2], field.cornerDec[2]);
        return field;
    }

    /** The radius, in degrees, of a circle around the centre that covers every corner. */
    double coveringRadiusDeg() {
        double radius = 0;
        if (cornerRa != null) {
            for (int i = 0; i < cornerRa.length; i++) {
                radius = Math.max(radius, SkyMath.separationDegrees(centerRa, centerDec, cornerRa[i], cornerDec[i]));
            }
        }
        return radius + 0.001;
    }

    /**
     * True when this sky position lies inside the field outline. The outline is the great-circle polygon of the
     * corners, tested on the tangent plane at the field centre, where those edges are straight lines.
     */
    boolean containsSky(double ra, double dec) {
        if (cornerRa == null || cornerRa.length < 3) {
            return false;
        }
        double[] point = project(ra, dec);
        if (point == null) {
            return false;
        }
        double[][] corners = new double[cornerRa.length][];
        for (int i = 0; i < cornerRa.length; i++) {
            corners[i] = project(cornerRa[i], cornerDec[i]);
            if (corners[i] == null) {
                return false;
            }
        }
        boolean inside = false;
        for (int i = 0, previous = corners.length - 1; i < corners.length; previous = i++) {
            double x1 = corners[i][0];
            double y1 = corners[i][1];
            double x2 = corners[previous][0];
            double y2 = corners[previous][1];
            boolean crosses = (y1 > point[1]) != (y2 > point[1]);
            if (crosses && point[0] < (x2 - x1) * (point[1] - y1) / (y2 - y1) + x1) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Gnomonic projection of this sky position onto the tangent plane at the field centre, as {xi, eta} in radians.
     * Null when the position is on the far side of the sky from the centre.
     */
    private double[] project(double ra, double dec) {
        double ra0 = Math.toRadians(centerRa);
        double dec0 = Math.toRadians(centerDec);
        double raRad = Math.toRadians(ra);
        double decRad = Math.toRadians(dec);
        double cosc = Math.sin(dec0) * Math.sin(decRad)
                + Math.cos(dec0) * Math.cos(decRad) * Math.cos(raRad - ra0);
        if (cosc <= 1e-8) {
            return null;
        }
        double xi = Math.cos(decRad) * Math.sin(raRad - ra0) / cosc;
        double eta = (Math.cos(dec0) * Math.sin(decRad)
                - Math.sin(dec0) * Math.cos(decRad) * Math.cos(raRad - ra0)) / cosc;
        return new double[] {xi, eta};
    }

    /** The field as an ADQL polygon. */
    String adqlPolygon() {
        StringBuilder polygon = new StringBuilder("POLYGON('ICRS'");
        for (int i = 0; i < cornerRa.length; i++) {
            polygon.append(String.format(Locale.US, ", %.6f, %.6f", cornerRa[i], cornerDec[i]));
        }
        return polygon.append(')').toString();
    }

    /** True when the other field is roughly this one: its centre is within a quarter of the diagonal of this centre. */
    public boolean isSameFieldAs(SkyField other) {
        return other != null
                && SkyMath.separationDegrees(centerRa, centerDec, other.centerRa, other.centerDec) < Math.hypot(widthDeg, heightDeg) / 4;
    }

    /** "4.1° × 2.7°". */
    public String sizeText() {
        return String.format(Locale.US, "%.1f° × %.1f°", widthDeg, heightDeg);
    }
}
