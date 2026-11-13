package eu.startales.spacepixels.fits;

import java.util.Locale;
import java.util.Map;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SkyMath;

/**
 * Minimal WCS transformer between pixels and the sky, for cursor readouts, report links and catalogue labels.
 * Supports celestial TAN, TAN-SIP and TPV projections with CD, PC+CDELT, or CDELT+CROTA matrices.
 * SIP applies A/B pixel distortion; AP/BP only seed the iterative sky-to-pixel inverse.
 * TPV applies its degree-seven polynomial, including radial terms, in the intermediate degree-valued plane.
 */
public final class WcsCoordinateTransformer {
    private static final String STANDARD_CTYPE1 = "RA---TAN";
    private static final String STANDARD_CTYPE2 = "DEC--TAN";

    private final double crpix1;
    private final double crpix2;
    private final double crval1Rad;
    private final double crval2Rad;
    private final double cd11;
    private final double cd12;
    private final double cd21;
    private final double cd22;
    /** TPV coefficients in convention order, or null for TAN and TAN-SIP. */
    private double[] tpv1;
    private double[] tpv2;
    /** SIP coefficients [p][q] of u^p v^q, or null when absent. */
    private double[][] sipA;
    private double[][] sipB;
    private double[][] sipAp;
    private double[][] sipBp;
    /** A correction of the solution in pixels (see {@link PlateSolutionCorrection}), or null. */
    private Displacement displacement;

    /** A displacement (dx, dy) in pixels at a pixel position, from where the solution puts a point to where it is. */
    public interface Displacement {
        double[] at(double x, double y);
    }

    private WcsCoordinateTransformer(double crpix1, double crpix2,
                                     double crval1Degrees, double crval2Degrees,
                                     double cd11, double cd12, double cd21, double cd22) {
        this.crpix1 = crpix1;
        this.crpix2 = crpix2;
        this.crval1Rad = Math.toRadians(crval1Degrees);
        this.crval2Rad = Math.toRadians(crval2Degrees);
        this.cd11 = cd11;
        this.cd12 = cd12;
        this.cd21 = cd21;
        this.cd22 = cd22;
    }

    public static WcsCoordinateTransformer fromHeader(Map<String, String> header) {
        if (header == null || header.isEmpty()) {
            return null;
        }

        String ctype1 = cleanHeaderValue(getHeaderValue(header, "CTYPE1"));
        String ctype2 = cleanHeaderValue(getHeaderValue(header, "CTYPE2"));
        if (ctype1 == null || ctype2 == null) {
            return null;
        }

        String upperCtype1 = ctype1.toUpperCase(Locale.US);
        String upperCtype2 = ctype2.toUpperCase(Locale.US);
        boolean tpv = upperCtype1.equals("RA---TPV") && upperCtype2.equals("DEC--TPV");
        boolean tan = upperCtype1.equals(STANDARD_CTYPE1) && upperCtype2.equals(STANDARD_CTYPE2);
        boolean sip = upperCtype1.equals("RA---TAN-SIP") && upperCtype2.equals("DEC--TAN-SIP");
        if (!tpv && !tan && !sip) return null;

        Double crpix1 = parseDouble(header, "CRPIX1");
        Double crpix2 = parseDouble(header, "CRPIX2");
        Double crval1 = parseDouble(header, "CRVAL1");
        Double crval2 = parseDouble(header, "CRVAL2");
        if (crpix1 == null || crpix2 == null || crval1 == null || crval2 == null) {
            return null;
        }

        double[] matrix = buildTransformationMatrix(header);
        if (matrix == null || !Double.isFinite(matrix[0] * matrix[3] - matrix[1] * matrix[2])
                || matrix[0] * matrix[3] - matrix[1] * matrix[2] == 0 || Math.abs(crval2) > 90) {
            return null;
        }

        WcsCoordinateTransformer transformer =
                new WcsCoordinateTransformer(crpix1, crpix2, crval1, crval2, matrix[0], matrix[1], matrix[2], matrix[3]);
        if (upperCtype1.endsWith("-SIP") && upperCtype2.endsWith("-SIP")) {
            transformer.sipA = sipCoefficients(header, "A");
            transformer.sipB = sipCoefficients(header, "B");
            if (transformer.sipA == null || transformer.sipB == null) {
                transformer.sipA = null;
                transformer.sipB = null;
            } else {
                transformer.sipAp = sipCoefficients(header, "AP");
                transformer.sipBp = sipCoefficients(header, "BP");
                if (transformer.sipAp == null || transformer.sipBp == null) {
                    transformer.sipAp = null;
                    transformer.sipBp = null;
                }
            }
        }
        if (tpv) {
            transformer.tpv1 = tpvCoefficients(header, 1);
            transformer.tpv2 = tpvCoefficients(header, 2);
            if (transformer.tpv1 == null || transformer.tpv2 == null) return null;
        }
        return transformer;
    }

    /** Whether the solution has SIP or TPV distortion terms, which are applied. */
    public boolean hasDistortion() {
        return sipA != null || tpv1 != null;
    }

    /** Whether a correction fitted to the stars is applied. */
    public boolean isCorrected() {
        return displacement != null;
    }

    /** This solution with a correction: positions from {@link #skyToPixel} are moved by it, and the reverse. */
    public WcsCoordinateTransformer withDisplacement(Displacement correction) {
        WcsCoordinateTransformer corrected = new WcsCoordinateTransformer(crpix1, crpix2, Math.toDegrees(crval1Rad),
                Math.toDegrees(crval2Rad), cd11, cd12, cd21, cd22);
        corrected.tpv1 = tpv1;
        corrected.tpv2 = tpv2;
        corrected.sipA = sipA;
        corrected.sipB = sipB;
        corrected.sipAp = sipAp;
        corrected.sipBp = sipBp;
        corrected.displacement = correction;
        return corrected;
    }

    /**
     * The header cards of the solution as it was read: CTYPE, CRPIX, CRVAL, CD and any SIP or TPV terms, in insertion order.
     * A fitted correction ({@link #isCorrected()}) has no standard card form and is not included.
     */
    public java.util.LinkedHashMap<String, Object> nominalHeaderCards() {
        java.util.LinkedHashMap<String, Object> cards = new java.util.LinkedHashMap<>();
        boolean sip = sipA != null && sipB != null;
        cards.put("CTYPE1", tpv1 != null ? "RA---TPV" : sip ? "RA---TAN-SIP" : "RA---TAN");
        cards.put("CTYPE2", tpv1 != null ? "DEC--TPV" : sip ? "DEC--TAN-SIP" : "DEC--TAN");
        cards.put("CRPIX1", crpix1);
        cards.put("CRPIX2", crpix2);
        cards.put("CRVAL1", Math.toDegrees(crval1Rad));
        cards.put("CRVAL2", Math.toDegrees(crval2Rad));
        cards.put("CD1_1", cd11);
        cards.put("CD1_2", cd12);
        cards.put("CD2_1", cd21);
        cards.put("CD2_2", cd22);
        if (sip) {
            addSip(cards, "A", sipA);
            addSip(cards, "B", sipB);
            if (sipAp != null && sipBp != null) {
                addSip(cards, "AP", sipAp);
                addSip(cards, "BP", sipBp);
            }
        }
        if (tpv1 != null) {
            for (int i = 0; i < 40; i++) {
                cards.put("PV1_" + i, tpv1[i]);
                cards.put("PV2_" + i, tpv2[i]);
            }
        }
        return cards;
    }

    private static void addSip(java.util.Map<String, Object> cards, String prefix, double[][] coefficients) {
        int order = coefficients.length - 1;
        cards.put(prefix + "_ORDER", order);
        for (int p = 0; p <= order; p++) {
            for (int q = 0; p + q <= order; q++) {
                if (coefficients[p][q] != 0) {
                    cards.put(prefix + "_" + p + "_" + q, coefficients[p][q]);
                }
            }
        }
    }

    /** Identifies the header solution, so a correction is applied only to the solution it was fitted to. */
    public String solutionKey() {
        return String.format(Locale.US, "%.4f,%.4f,%.9f,%.9f,%.10e,%.10e,%.10e,%.10e,%s", crpix1, crpix2,
                Math.toDegrees(crval1Rad), Math.toDegrees(crval2Rad), cd11, cd12, cd21, cd22, sipA != null ? "sip" : tpv1 != null
                        ? "tpv:" + java.util.Arrays.toString(tpv1) + java.util.Arrays.toString(tpv2) : "tan");
    }

    public SkyCoordinate pixelToSky(double pixelX, double pixelY) {
        if (displacement != null) {
            // The header position whose corrected position is this pixel; the correction is small and smooth.
            double x = pixelX;
            double y = pixelY;
            for (int iteration = 0; iteration < 20; iteration++) {
                double[] d = displacement.at(x, y);
                double nextX = pixelX - d[0];
                double nextY = pixelY - d[1];
                boolean converged = Math.abs(nextX - x) < 1e-7 && Math.abs(nextY - y) < 1e-7;
                x = nextX;
                y = nextY;
                if (converged) {
                    break;
                }
            }
            pixelX = x;
            pixelY = y;
        }
        double fitsX = pixelX + 1.0;
        double fitsY = pixelY + 1.0;

        double dx = fitsX - crpix1;
        double dy = fitsY - crpix2;
        if (sipA != null) {
            double u = dx;
            double v = dy;
            dx = u + polynomial(sipA, u, v);
            dy = v + polynomial(sipB, u, v);
        }

        double planeX = cd11 * dx + cd12 * dy;
        double planeY = cd21 * dx + cd22 * dy;
        double xi = Math.toRadians(tpv1 == null ? planeX : tpvPolynomial(tpv1, planeX, planeY));
        double eta = Math.toRadians(tpv2 == null ? planeY : tpvPolynomial(tpv2, planeY, planeX));

        double sinDec0 = Math.sin(crval2Rad);
        double cosDec0 = Math.cos(crval2Rad);
        double denominator = cosDec0 - (eta * sinDec0);

        double ra = Math.atan2(xi, denominator) + crval1Rad;
        double dec = Math.atan2(
                sinDec0 + (eta * cosDec0),
                Math.sqrt((denominator * denominator) + (xi * xi))
        );

        return new SkyCoordinate(SkyMath.normalizeDegrees(Math.toDegrees(ra)), Math.toDegrees(dec));
    }

    /**
     * The pixel (0-based, as {@link #pixelToSky}) at this sky position, as {x, y}; null when the position is 90° or
     * more from the field centre, where the projection does not reach.
     */
    public double[] skyToPixel(double raDegrees, double decDegrees) {
        double ra = Math.toRadians(raDegrees);
        double dec = Math.toRadians(decDegrees);
        double sinDec0 = Math.sin(crval2Rad);
        double cosDec0 = Math.cos(crval2Rad);
        double deltaRa = ra - crval1Rad;
        double cosDistance = sinDec0 * Math.sin(dec) + cosDec0 * Math.cos(dec) * Math.cos(deltaRa);
        if (cosDistance <= 0) {
            return null;
        }
        double xi = Math.toDegrees(Math.cos(dec) * Math.sin(deltaRa) / cosDistance);
        double eta = Math.toDegrees((cosDec0 * Math.sin(dec) - sinDec0 * Math.cos(dec) * Math.cos(deltaRa)) / cosDistance);
        if (!Double.isFinite(xi) || !Double.isFinite(eta)) return null;
        if (tpv1 != null) {
            double[] plane = invertTpv(xi, eta);
            if (plane == null) return null;
            xi = plane[0];
            eta = plane[1];
        }
        double determinant = cd11 * cd22 - cd12 * cd21;
        if (determinant == 0) {
            return null;
        }
        double dx = (cd22 * xi - cd12 * eta) / determinant;
        double dy = (cd11 * eta - cd21 * xi) / determinant;
        if (sipA != null) {
            // Solve u + A(u, v) = dx, v + B(u, v) = dy: start from the inverse terms if there are any, then refine
            // so the result agrees with pixelToSky exactly (the inverse terms are only an approximation).
            double u = sipAp != null ? dx + polynomial(sipAp, dx, dy) : dx;
            double v = sipBp != null ? dy + polynomial(sipBp, dx, dy) : dy;
            for (int iteration = 0; iteration < 50; iteration++) {
                double nextU = dx - polynomial(sipA, u, v);
                double nextV = dy - polynomial(sipB, u, v);
                boolean converged = Math.abs(nextU - u) < 1e-9 && Math.abs(nextV - v) < 1e-9;
                u = nextU;
                v = nextV;
                if (converged) {
                    break;
                }
            }
            dx = u;
            dy = v;
        }
        double x = crpix1 + dx - 1.0;
        double y = crpix2 + dy - 1.0;
        if (displacement != null) {
            double[] d = displacement.at(x, y);
            x += d[0];
            y += d[1];
        }
        return new double[]{x, y};
    }

    /** Read the convention's 40 coefficients, with identity defaults; reject malformed supplied terms. */
    private static double[] tpvCoefficients(Map<String, String> header, int axis) {
        double[] coefficients = new double[40];
        coefficients[1] = 1;
        for (int i = 0; i < coefficients.length; i++) {
            String key = "PV" + axis + "_" + i;
            if (getHeaderValue(header, key) != null) {
                Double value = parseDouble(header, key);
                if (value == null) return null;
                coefficients[i] = value;
            }
        }
        return coefficients;
    }

    /** Evaluate TPV in degrees; the second axis calls this with y, x. */
    private static double tpvPolynomial(double[] c, double x, double y) {
        double r = Math.hypot(x, y);
        double sum = c[0] + c[1] * x + c[2] * y + c[3] * r;
        int index = 4;
        for (int degree = 2; degree <= 7; degree++) {
            for (int q = 0; q <= degree; q++) {
                sum += c[index++] * Math.pow(x, degree - q) * Math.pow(y, q);
            }
            if (degree % 2 == 1) sum += c[index++] * Math.pow(r, degree);
        }
        return sum;
    }

    /** Invert the degree-valued plane distortion by Newton iteration; return null on failure. */
    private double[] invertTpv(double targetX, double targetY) {
        double x = targetX;
        double y = targetY;
        for (int iteration = 0; iteration < 60; iteration++) {
            double fx = tpvPolynomial(tpv1, x, y) - targetX;
            double fy = tpvPolynomial(tpv2, y, x) - targetY;
            if (!Double.isFinite(fx) || !Double.isFinite(fy)) return null;
            if (Math.max(Math.abs(fx), Math.abs(fy)) < 1e-12) return new double[]{x, y};
            double h = 1e-6 * Math.max(1, Math.max(Math.abs(x), Math.abs(y)));
            double a = (tpvPolynomial(tpv1, x + h, y) - tpvPolynomial(tpv1, x - h, y)) / (2 * h);
            double b = (tpvPolynomial(tpv1, x, y + h) - tpvPolynomial(tpv1, x, y - h)) / (2 * h);
            double c = (tpvPolynomial(tpv2, y, x + h) - tpvPolynomial(tpv2, y, x - h)) / (2 * h);
            double d = (tpvPolynomial(tpv2, y + h, x) - tpvPolynomial(tpv2, y - h, x)) / (2 * h);
            double determinant = a * d - b * c;
            if (!Double.isFinite(determinant) || determinant == 0) return null;
            x -= (d * fx - b * fy) / determinant;
            y -= (a * fy - c * fx) / determinant;
        }
        return null;
    }

    /** SIP coefficients {@code prefix_p_q} up to {@code prefix_ORDER}, or null when the order is missing. */
    private static double[][] sipCoefficients(Map<String, String> header, String prefix) {
        Double order = parseDouble(header, prefix + "_ORDER");
        if (order == null || order < 0 || order > 9) {
            return null;
        }
        int n = order.intValue();
        double[][] coefficients = new double[n + 1][n + 1];
        for (int p = 0; p <= n; p++) {
            for (int q = 0; p + q <= n; q++) {
                Double value = parseDouble(header, prefix + "_" + p + "_" + q);
                coefficients[p][q] = value == null ? 0 : value;
            }
        }
        return coefficients;
    }

    /** Sum of coefficients[p][q] u^p v^q. */
    private static double polynomial(double[][] coefficients, double u, double v) {
        double sum = 0;
        double uPower = 1;
        for (double[] row : coefficients) {
            double term = uPower;
            for (double coefficient : row) {
                sum += coefficient * term;
                term *= v;
            }
            uPower *= u;
        }
        return sum;
    }

    public static String formatRa(double raDegrees) {
        if (!Double.isFinite(raDegrees)) return "n/a";
        long ticks = Math.round(SkyMath.normalizeDegrees(raDegrees) / 15.0 * 36000.0) % 864000;
        return String.format(Locale.US, "%02d:%02d:%04.1f", ticks / 36000, ticks / 600 % 60, ticks % 600 / 10.0);
    }

    public static String formatDec(double decDegrees) {
        if (!Double.isFinite(decDegrees) || Math.abs(decDegrees) > 90) return "n/a";
        long ticks = Math.round(Math.abs(decDegrees) * 36000);
        return String.format(Locale.US, "%s%02d:%02d:%04.1f", decDegrees >= 0 ? "+" : "-", ticks / 36000, ticks / 600 % 60, ticks % 600 / 10.0);
    }

    private static double[] buildTransformationMatrix(Map<String, String> header) {
        Double cd11 = parseDouble(header, "CD1_1");
        Double cd12 = parseDouble(header, "CD1_2");
        Double cd21 = parseDouble(header, "CD2_1");
        Double cd22 = parseDouble(header, "CD2_2");
        if (cd11 != null && cd12 != null && cd21 != null && cd22 != null) {
            return new double[]{cd11, cd12, cd21, cd22};
        }

        Double cdelt1 = parseDouble(header, "CDELT1");
        Double cdelt2 = parseDouble(header, "CDELT2");
        if (cdelt1 == null || cdelt2 == null) {
            return null;
        }

        Double pc11 = parseDouble(header, "PC1_1");
        Double pc12 = parseDouble(header, "PC1_2");
        Double pc21 = parseDouble(header, "PC2_1");
        Double pc22 = parseDouble(header, "PC2_2");
        if (pc11 != null && pc12 != null && pc21 != null && pc22 != null) {
            return new double[]{pc11 * cdelt1, pc12 * cdelt1, pc21 * cdelt2, pc22 * cdelt2};
        }

        Double crota2 = parseDouble(header, "CROTA2");
        Double crota1 = parseDouble(header, "CROTA1");
        double rotationRad = Math.toRadians(crota2 != null ? crota2 : (crota1 != null ? crota1 : 0.0));
        double cos = Math.cos(rotationRad);
        double sin = Math.sin(rotationRad);
        return new double[]{
                cdelt1 * cos,
                -cdelt2 * sin,
                cdelt1 * sin,
                cdelt2 * cos
        };
    }

    private static Double parseDouble(Map<String, String> header, String key) {
        String rawValue = cleanHeaderValue(getHeaderValue(header, key));
        if (rawValue == null || rawValue.isEmpty()) {
            return null;
        }

        try {
            double value = Double.parseDouble(rawValue.replace('D', 'E').replace('d', 'e'));
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String getHeaderValue(Map<String, String> header, String key) {
        if (header == null || key == null) {
            return null;
        }

        String direct = header.get(key);
        if (direct != null) {
            return direct;
        }

        direct = header.get(key.toUpperCase(Locale.US));
        if (direct != null) {
            return direct;
        }

        direct = header.get(key.toLowerCase(Locale.US));
        if (direct != null) {
            return direct;
        }

        for (Map.Entry<String, String> entry : header.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private static String cleanHeaderValue(String value) {
        if (value == null) {
            return null;
        }

        int commentIndex = value.indexOf('/');
        if (commentIndex >= 0) {
            value = value.substring(0, commentIndex);
        }

        return value.replace("'", "").trim();
    }

    public static final class SkyCoordinate {
        private final double raDegrees;
        private final double decDegrees;

        public SkyCoordinate(double raDegrees, double decDegrees) {
            this.raDegrees = raDegrees;
            this.decDegrees = decDegrees;
        }

        public double getRaDegrees() {
            return raDegrees;
        }

        public double getDecDegrees() {
            return decDegrees;
        }
    }
}
