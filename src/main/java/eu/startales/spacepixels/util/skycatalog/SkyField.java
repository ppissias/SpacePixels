/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.skycatalog;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;

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
        field.widthDeg = separation(field.cornerRa[0], field.cornerDec[0], field.cornerRa[1], field.cornerDec[1]);
        field.heightDeg = separation(field.cornerRa[1], field.cornerDec[1], field.cornerRa[2], field.cornerDec[2]);
        return field;
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
                && separation(centerRa, centerDec, other.centerRa, other.centerDec) < Math.hypot(widthDeg, heightDeg) / 4;
    }

    /** Angle between two sky positions, in degrees. */
    static double separation(double ra1, double dec1, double ra2, double dec2) {
        double phi1 = Math.toRadians(dec1);
        double phi2 = Math.toRadians(dec2);
        double deltaPhi = phi2 - phi1;
        double deltaLambda = Math.toRadians(ra2 - ra1);
        double a = Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(deltaLambda / 2) * Math.sin(deltaLambda / 2);
        return Math.toDegrees(2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    /** "4.1° × 2.7°". */
    public String sizeText() {
        return String.format(Locale.US, "%.1f° × %.1f°", widthDeg, heightDeg);
    }
}
