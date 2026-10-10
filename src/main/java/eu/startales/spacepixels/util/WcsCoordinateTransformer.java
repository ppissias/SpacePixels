package eu.startales.spacepixels.util;

import java.util.Locale;
import java.util.Map;

/**
 * Minimal WCS transformer between pixels and the sky, for cursor readouts, report links and catalogue labels.
 * Supports standard celestial TAN projections with CD, PC+CDELT, or CDELT+CROTA matrices, and the SIP distortion
 * terms of TAN-SIP solutions (A/B; AP/BP are only used as a first guess when going from the sky to pixels).
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
    /** SIP distortion: coefficients [p][q] of u^p v^q, or null when the solution has none. */
    private double[][] sipA;
    private double[][] sipB;
    private double[][] sipAp;
    private double[][] sipBp;

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
        if (!upperCtype1.contains("RA") || !upperCtype2.contains("DEC") || !upperCtype1.contains("TAN") || !upperCtype2.contains("TAN")) {
            return null;
        }

        Double crpix1 = parseDouble(header, "CRPIX1");
        Double crpix2 = parseDouble(header, "CRPIX2");
        Double crval1 = parseDouble(header, "CRVAL1");
        Double crval2 = parseDouble(header, "CRVAL2");
        if (crpix1 == null || crpix2 == null || crval1 == null || crval2 == null) {
            return null;
        }

        double[] matrix = buildTransformationMatrix(header);
        if (matrix == null) {
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
        return transformer;
    }

    /** Whether the solution has SIP distortion terms, which are applied. */
    public boolean hasDistortion() {
        return sipA != null;
    }

    public SkyCoordinate pixelToSky(double pixelX, double pixelY) {
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

        double xi = Math.toRadians((cd11 * dx) + (cd12 * dy));
        double eta = Math.toRadians((cd21 * dx) + (cd22 * dy));

        double sinDec0 = Math.sin(crval2Rad);
        double cosDec0 = Math.cos(crval2Rad);
        double denominator = cosDec0 - (eta * sinDec0);

        double ra = Math.atan2(xi, denominator) + crval1Rad;
        double dec = Math.atan2(
                sinDec0 + (eta * cosDec0),
                Math.sqrt((denominator * denominator) + (xi * xi))
        );

        return new SkyCoordinate(normalizeDegrees(Math.toDegrees(ra)), Math.toDegrees(dec));
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
        return new double[]{crpix1 + dx - 1.0, crpix2 + dy - 1.0};
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
        double totalSeconds = normalizeDegrees(raDegrees) / 15.0 * 3600.0;
        int hours = (int) (totalSeconds / 3600.0);
        totalSeconds -= hours * 3600.0;
        int minutes = (int) (totalSeconds / 60.0);
        double seconds = totalSeconds - (minutes * 60.0);
        return String.format(Locale.US, "%02d:%02d:%04.1f", hours, minutes, seconds);
    }

    public static String formatDec(double decDegrees) {
        double absolute = Math.abs(decDegrees);
        int degrees = (int) absolute;
        double remainingMinutes = (absolute - degrees) * 60.0;
        int minutes = (int) remainingMinutes;
        double seconds = (remainingMinutes - minutes) * 60.0;
        return String.format(Locale.US, "%s%02d:%02d:%04.1f", decDegrees >= 0.0 ? "+" : "-", degrees, minutes, seconds);
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
            return Double.parseDouble(rawValue);
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

    private static double normalizeDegrees(double degrees) {
        double normalized = degrees % 360.0;
        return normalized < 0.0 ? normalized + 360.0 : normalized;
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
