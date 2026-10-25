/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;
import eu.startales.spacepixels.util.Statistics;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Writes the Night &amp; Equipment section: the night (seeing and focus, transparency, sky brightness), the setup
 * (pixel scale, focal length, sampling, brightness range), the optics (star size and shape across the field, coma)
 * and the flat-field check, each with a plain reading.
 */
final class NightEquipmentSectionWriter {

    private static final String SECOND = "&Prime;";

    private NightEquipmentSectionWriter() {
    }

    static void writeSection(PrintWriter report, NightEquipment ne, FieldDepth fieldDepth) {
        report.println("<div class='panel'>");
        report.println("<h2>Night &amp; Equipment</h2>");
        if (ne == null || ne.isEmpty()) {
            report.println("<p class='section-lede'>The night and the equipment could not be measured for this session"
                    + (ne != null && !ne.notes.isEmpty() ? ": " + esc(String.join(" ", ne.notes)) : ".") + "</p>");
            report.println("</div>");
            return;
        }
        report.println("<p class='section-lede'>What the frames say about the night and the equipment, from up to "
                + ne.starsSelected + " isolated stars per frame across " + ne.frames.size()
                + " kept frames. Each part ends with a reading; the numbers behind it are in the charts and maps.</p>");
        writeCards(report, ne);
        writeNight(report, ne);
        writeSetup(report, ne, fieldDepth);
        writeOptics(report, ne);
        writeCalibration(report, ne);
        writeMethod(report, ne);
        report.println("</div>");
    }

    // ==========================================
    // CARDS
    // ==========================================

    private static void writeCards(PrintWriter report, NightEquipment ne) {
        report.println("<div class='flex-container' style='margin-bottom: 4px;'>");
        report.println(card(size(ne, ne.centreSizePx), "Seeing (star size)"));
        double sky = medianOf(ne, f -> f.skyBrightness);
        if (!Double.isNaN(sky)) {
            report.println(card(String.format(Locale.US, "%.2f", sky), "Sky, mag/arcsec&sup2;"));
        }
        double dimming = maxOf(ne, f -> f.dimming);
        if (!Double.isNaN(dimming)) {
            report.println(card(String.format(Locale.US, "%.2f mag", dimming), "Transparency range"));
        }
        if (ne.hasPixelScale()) {
            report.println(card(String.format(Locale.US, "%.2f%s/px", ne.pixelScale, SECOND), "Pixel scale"));
        }
        if (!Double.isNaN(ne.measuredFocalLength)) {
            report.println(card(String.format(Locale.US, "%.0f mm", ne.measuredFocalLength), "Focal length"));
        }
        int worst = worstCorner(ne);
        if (worst >= 0) {
            report.println(card(signedPercent(ne.cornerSizes[worst]), "Star size, worst corner"));
        }
        if (!Double.isNaN(ne.cornerDimming)) {
            report.println(card(signedPercent(-dimFraction(ne.cornerDimming)), "Star brightness in the corners"));
        }
        report.println("</div>");
    }

    private static String card(String value, String label) {
        return "<div class='metric-box compact'><span class='metric-value'>" + value + "</span><span class='metric-label'>"
                + label + "</span></div>";
    }

    // ==========================================
    // THE NIGHT
    // ==========================================

    private static void writeNight(PrintWriter report, NightEquipment ne) {
        report.println("<h3 class='ne-heading'>The night</h3>");
        boolean arcsec = ne.hasPixelScale();
        double scale = arcsec ? ne.pixelScale : 1;
        String unit = arcsec ? SECOND : " px";
        report.println("<div class='ne-grid'>");

        // Seeing and focus
        List<Double> seeing = values(ne, f -> f.seeingPx * scale);
        report.println("<div><div class='ne-chart-title'>Star size near the centre (" + (arcsec ? "arcseconds" : "pixels") + ")</div>");
        report.println(lineChart(ne, f -> f.seeingPx * scale, Double.isNaN(ne.frames.get(0).temperature) ? null : f -> f.temperature,
                "%.1f", "%.0f °C", "Larger stars are higher"));
        report.println("<p class='ne-reading'>" + seeingReading(ne, seeing, unit) + "</p></div>");

        // Transparency
        if (!Double.isNaN(maxOf(ne, f -> f.dimming))) {
            report.println("<div><div class='ne-chart-title'>Star brightness against the clearest frame (magnitudes)</div>");
            report.println(lineChart(ne, f -> -f.dimming, null, "%.2f", null, "Dimmer frames are lower"));
            report.println("<p class='ne-reading'>" + transparencyReading(ne) + "</p></div>");
        }

        // Sky brightness
        if (!Double.isNaN(medianOf(ne, f -> f.skyBrightness))) {
            report.println("<div><div class='ne-chart-title'>Sky brightness (Gaia G magnitudes per square arcsecond)</div>");
            report.println(lineChart(ne, f -> f.skyBrightness, null, "%.1f", null, "Darker sky is higher"));
            report.println("<p class='ne-reading'>" + skyReading(ne) + "</p></div>");
        }
        report.println("</div>");
    }

    private static String seeingReading(NightEquipment ne, List<Double> seeing, String unit) {
        if (seeing.size() < 3) {
            return "The stars measure " + format(Statistics.median(seeing), unit) + " across.";
        }
        double median = Statistics.median(seeing);
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (double value : seeing) {
            lowest = Math.min(lowest, value);
            highest = Math.max(highest, value);
        }
        int third = Math.max(1, seeing.size() / 3);
        double start = Statistics.median(seeing.subList(0, third));
        double end = Statistics.median(seeing.subList(seeing.size() - third, seeing.size()));
        double change = end / start - 1;
        double trend = rankCorrelation(seeing);
        StringBuilder text = new StringBuilder(String.format(Locale.US, "The stars near the centre measure %s across (median), from %s to %s. ",
                format(median, unit), format(lowest, unit), format(highest, unit)));
        if (trend > 0.5 && change > 0.12) {
            text.append(String.format(Locale.US, "They grew steadily, by %.0f %% from the first to the last third of the session. Focus drift is one possible cause; seeing, tracking and altitude can also contribute. ", change * 100));
            String temperature = temperatureChange(ne);
            if (temperature != null) {
                text.append(temperature).append(' ');
            }
            text.append("Refocusing during the session (or autofocus after a temperature change) keeps the stars small.");
        } else if (trend < -0.5 && change < -0.12) {
            text.append(String.format(Locale.US, "They became %.0f %% smaller during the session: the seeing improved, or the target rose higher.", -change * 100));
        } else {
            text.append("There is no steady trend. Seeing, focus and tracking can all contribute to these changes.");
            String temperature = temperatureChange(ne);
            if (temperature != null) {
                text.append(' ').append(temperature);
            }
        }
        List<String> large = new ArrayList<>();
        for (NightEquipment.Frame frame : ne.frames) {
            if (frame.seeingPx * (ne.hasPixelScale() ? ne.pixelScale : 1) > 1.3 * median) {
                large.add(String.valueOf(frame.index + 1));
            }
        }
        if (!large.isEmpty() && large.size() <= 12) {
            text.append(" Frame").append(large.size() == 1 ? " " : "s ").append(String.join(", ", large))
                    .append(" ha").append(large.size() == 1 ? "s" : "ve").append(" clearly larger stars (wind, a focus jump, or a moment of poor seeing).");
        }
        return text.toString();
    }

    private static String temperatureChange(NightEquipment ne) {
        List<Double> temperatures = values(ne, f -> f.temperature);
        if (temperatures.size() < 3 || ne.temperatureSource == null) {
            return null;
        }
        double start = temperatures.get(0);
        double end = temperatures.get(temperatures.size() - 1);
        double lowest = Double.POSITIVE_INFINITY;
        double highest = Double.NEGATIVE_INFINITY;
        for (double t : temperatures) {
            lowest = Math.min(lowest, t);
            highest = Math.max(highest, t);
        }
        String source = "FOCTEMP".equals(ne.temperatureSource) ? "focuser" : "air";
        if (highest - lowest < 0.5) {
            return "The " + source + " temperature stayed within half a degree.";
        }
        String text = String.format(Locale.US, "The %s temperature (%s in the FITS header) went from %.1f °C to %.1f °C", source,
                ne.temperatureSource, start, end);
        if (lowest < Math.min(start, end) - 0.3) {
            text += String.format(Locale.US, ", lowest %.1f °C", lowest);
        } else if (highest > Math.max(start, end) + 0.3) {
            text += String.format(Locale.US, ", highest %.1f °C", highest);
        }
        return text + ".";
    }

    private static String transparencyReading(NightEquipment ne) {
        double range = maxOf(ne, f -> f.dimming);
        List<String> dim = new ArrayList<>();
        for (NightEquipment.Frame frame : ne.frames) {
            if (frame.dimming > 0.3) {
                dim.add(String.valueOf(frame.index + 1));
            }
        }
        StringBuilder text = new StringBuilder();
        if (range < 0.1) {
            text.append(String.format(Locale.US, "The stars kept their brightness within %.2f mag through the session, consistent with stable transparency.", range));
        } else if (dim.isEmpty()) {
            text.append(String.format(Locale.US, "The stars changed in brightness by up to %.2f mag: some haze, or the target's change in altitude.", range));
        } else {
            text.append(String.format(Locale.US, "The stars changed in brightness by up to %.2f mag. ", range));
            text.append(dim.size() <= 12 ? "Frame" + (dim.size() == 1 ? " " : "s ") + String.join(", ", dim)
                    : dim.size() + " frames").append(" lost more than 0.3 mag (about 24 % of their light): haze, thin cloud or increasing airmass may contribute.");
        }
        text.append(" Measured per second of exposure on the same stars in every frame; a lower target also dims the stars.");
        return text.toString();
    }

    private static String skyReading(NightEquipment ne) {
        List<Double> sky = values(ne, f -> f.skyBrightness);
        double median = Statistics.median(sky);
        StringBuilder text = new StringBuilder(String.format(Locale.US, "The estimated sky brightness is %.2f mag/arcsec&sup2; (median).", median));
        if (sky.size() >= 6) {
            int third = Math.max(1, sky.size() / 3);
            double start = Statistics.median(sky.subList(0, third));
            double end = Statistics.median(sky.subList(sky.size() - third, sky.size()));
            if (start - end > 0.2) {
                text.append(String.format(Locale.US, " It got %.1f mag brighter towards the end (twilight, the Moon rising, or the target sinking into the light pollution).", start - end));
            } else if (end - start > 0.2) {
                text.append(String.format(Locale.US, " It got %.1f mag darker during the session (twilight ending, the Moon setting, or the target rising).", end - start));
            }
        }
        text.append(" This is an approximate Gaia G estimate, not a direct SQM reading or a site classification. Calibrated with the Gaia stars of each frame, it assumes calibrated frames (bias or dark subtracted, no added pedestal): "
                + "frames that still contain the camera's offset show a brighter sky than the real one.");
        return text.toString();
    }

    // ==========================================
    // THE SETUP
    // ==========================================

    private static void writeSetup(PrintWriter report, NightEquipment ne, FieldDepth fieldDepth) {
        report.println("<h3 class='ne-heading'>The setup</h3>");
        report.println("<ul class='ne-list'>");
        if (ne.hasPixelScale()) {
            double widthDeg = ne.width * ne.pixelScale / 3600;
            double heightDeg = ne.height * ne.pixelScale / 3600;
            report.println(String.format(Locale.US, "<li><strong>Pixel scale %.3f%s per pixel</strong>, a field of %s &times; %s.%s</li>",
                    ne.pixelScale, SECOND, angle(widthDeg), angle(heightDeg),
                    Double.isNaN(ne.cameraAngle) ? "" : String.format(Locale.US,
                            " Camera angle %.1f&deg; (the frame's y axis east of north, as plate solvers report it): set the same angle to frame the next session the same way.", ne.cameraAngle)));
        }
        if (!Double.isNaN(ne.measuredFocalLength)) {
            StringBuilder text = new StringBuilder(String.format(Locale.US,
                    "<li><strong>Focal length %.0f mm</strong>, from the plate solution and the %.2f &micro;m pixels in the FITS header",
                    ne.measuredFocalLength, ne.pixelSizeMicrons));
            if (ne.binning > 1) {
                text.append(" (assuming the pixel size includes the ").append(ne.binning).append("&times;").append(ne.binning).append(" binning)");
            }
            if (!Double.isNaN(ne.headerFocalLength)) {
                double difference = ne.measuredFocalLength / ne.headerFocalLength - 1;
                if (Math.abs(difference) < 0.015) {
                    text.append(String.format(Locale.US, ". It matches the %.0f mm in the FITS header.", ne.headerFocalLength));
                } else {
                    text.append(String.format(Locale.US, ". The FITS header says %.0f mm (%+.1f %%). With a reducer or flattener, its distance to the camera changes the focal length: "
                            + "compare with your other sessions, since with the same equipment a change means the spacing changed.", ne.headerFocalLength, difference * 100));
                }
            } else {
                text.append(". With a reducer or flattener, its distance to the camera changes the focal length: compare with your other sessions.");
            }
            report.println(text.append("</li>"));
        }
        report.println("<li>" + samplingReading(ne) + "</li>");
        String range = rangeReading(ne, fieldDepth);
        if (range != null) {
            report.println("<li>" + range + "</li>");
        }
        report.println("</ul>");
    }

    private static String samplingReading(NightEquipment ne) {
        double px = ne.centreSizePx;
        String size = String.format(Locale.US, "%.1f px", px) + (ne.hasPixelScale() ? String.format(Locale.US, " (%.1f%s)", px * ne.pixelScale, SECOND) : "");
        String head = "<strong>Sampling: stars " + size + " across.</strong> ";
        if (px < 1.5) {
            return head + "The stars are strongly undersampled: they fall on too few pixels to show their shape. Dithering between frames and drizzle integration recover some detail; a longer focal length or smaller pixels would match the seeing better.";
        }
        if (px < 2.0) {
            return head + "The stars are slightly undersampled. Dithering between frames and drizzle integration recover some detail.";
        }
        if (px <= 4.0) {
            return head + "Well sampled: the pixels match the star size.";
        }
        if (px <= 5.5) {
            return head + "Oversampled: 2&times;2 binning would lose no detail and lowers the noise from the camera.";
        }
        return head + "Strongly oversampled: 2&times;2 or 3&times;3 binning would lose no detail and lowers the noise from the camera.";
    }

    private static String rangeReading(NightEquipment ne, FieldDepth fieldDepth) {
        boolean hasDepth = fieldDepth != null && !fieldDepth.isEmpty();
        if (Double.isNaN(ne.saturationMagnitude) && !hasDepth) {
            return null;
        }
        StringBuilder text = new StringBuilder("<strong>Brightness range:</strong> ");
        if (!Double.isNaN(ne.saturationMagnitude)) {
            text.append(String.format(Locale.US, "stars brighter than about G %.1f saturate", ne.saturationMagnitude));
        } else if (ne.calibrationStars > 0) {
            text.append("no measured star saturates");
        }
        if (hasDepth) {
            text.append(Double.isNaN(ne.saturationMagnitude) && ne.calibrationStars == 0 ? "" : ", and ")
                    .append("the detection reaches ").append(fieldDepth.medianLabel()).append(" (field depth)");
        }
        text.append(".");
        if (!Double.isNaN(ne.saturationMagnitude) && hasDepth) {
            text.append(" This is an estimated detection range, not a photometric accuracy limit; the Variable Stars readiness checks assess suitability for differential photometry.");
        }
        return text.toString();
    }

    // ==========================================
    // THE OPTICS
    // ==========================================

    private static void writeOptics(PrintWriter report, NightEquipment ne) {
        if (ne.relativeSize == null) {
            return;
        }
        report.println("<h3 class='ne-heading'>The optics</h3>");
        report.println("<p class='compact-note' style='margin-top: 0;'>Each map is the whole frame as in the pictures of this report, split into "
                + ne.columns + " &times; " + ne.rows + " parts; hover a part for its numbers. Orange: larger stars than in the centre; blue: smaller."
                + (ne.pierFlip ? " Frames from the other side of the pier (meridian flip) were turned back by 180&deg;, so the maps show the camera's own orientation." : "") + "</p>");
        report.println("<div class='ne-grid'>");
        report.println("<div><div class='ne-chart-title'>Star size against the centre</div>");
        report.println(heatMap(ne, ne.relativeSize, ne.rows, ne.columns, v -> v - 1, 0.25, (r, c) -> {
            double value = ne.relativeSize[r][c];
            return Double.isNaN(value) ? null : signedPercent(value - 1);
        }, (r, c) -> {
            double value = ne.sizeArcsecOrPx[r][c];
            return Double.isNaN(value) ? "" : String.format(Locale.US, "Star size %.2f%s", value, ne.hasPixelScale() ? " arcsec" : " px");
        }));
        report.println("<p class='ne-reading'>" + sizeReading(ne) + "</p></div>");

        report.println("<div><div class='ne-chart-title'>Star shapes (tracking removed)</div>");
        report.println(whiskerMap(ne));
        report.println("<p class='ne-reading'>" + shapeReading(ne) + "</p></div>");

        report.println("<div><div class='ne-chart-title'>Coma: where the light lies against the core</div>");
        report.println(comaMap(ne));
        report.println("<p class='ne-reading'>" + comaReading(ne) + "</p></div>");
        report.println("</div>");
    }

    private static final String[] CORNER_NAMES = {"top left", "top right", "bottom left", "bottom right"};

    /** The corner with the largest stars, or -1. */
    private static int worstCorner(NightEquipment ne) {
        if (ne.cornerSizes == null) {
            return -1;
        }
        int worst = 0;
        for (int k = 1; k < 4; k++) {
            if (ne.cornerSizes[k] > ne.cornerSizes[worst]) {
                worst = k;
            }
        }
        return worst;
    }

    private static String sizeReading(NightEquipment ne) {
        double[] corners = ne.cornerSizes;
        if (corners == null) {
            return "Too few stars across the field to compare the corners with the centre.";
        }
        double lowest = Math.min(Math.min(corners[0], corners[1]), Math.min(corners[2], corners[3]));
        double highest = Math.max(Math.max(corners[0], corners[1]), Math.max(corners[2], corners[3]));
        double tilt = Math.hypot(ne.sizeTiltX, ne.sizeTiltY);
        String tiltCause = "a pattern consistent with sensor tilt, a sagging focuser or adapter, or uneven focus";
        StringBuilder text = new StringBuilder();
        if (highest < 0.05 && lowest > -0.05) {
            text.append("The stars are as sharp in the corners as in the centre (within 5 %): no corner growth is resolved by these measurements. ");
        } else if (lowest >= 0.05) {
            text.append(String.format(Locale.US, "The stars are larger in all four corners than in the centre, by %.0f to %.0f %%: %s. ",
                    lowest * 100, highest * 100, lowest >= 0.15 ? "field curvature; with a field flattener or reducer, check its distance to the camera (backfocus)"
                            : "slight field curvature"));
            if (tilt >= 0.08) {
                text.append(String.format(Locale.US, "They grow more towards the %s (%.0f %% from one side to the other): in addition, %s.",
                        direction(ne.sizeTiltX, ne.sizeTiltY), tilt * 100, tiltCause));
            }
        } else if (highest >= 0.05) {
            int worst = worstCorner(ne);
            text.append(String.format(Locale.US, "The stars grow towards the %s, up to %.0f %% larger in the %s corner, and stay sharp on the opposite side",
                    direction(ne.sizeTiltX, ne.sizeTiltY), highest * 100, CORNER_NAMES[worst]));
            text.append(highest >= 0.10 || tilt >= 0.08 ? ": " + tiltCause + "." : ": a slight tilt.");
        } else {
            text.append(String.format(Locale.US, "The stars are up to %.0f %% smaller in the corners than in the centre: the focus was set on the outer field, or the field is curved. ", -lowest * 100));
        }
        return text.toString().trim();
    }

    private static String shapeReading(NightEquipment ne) {
        StringBuilder text = new StringBuilder();
        double tracking = Math.hypot(ne.trackingE1, ne.trackingE2);
        double trackingRatio = axisRatio(tracking);
        if (trackingRatio >= 1.12) {
            text.append(String.format(Locale.US, "All stars are stretched %.0f %% in the same direction (%s): tracking, guiding, wind or a shared optical effect may contribute. "
                    + "This shared stretch is taken out of the map. ", (trackingRatio - 1) * 100, lineDirection(ne.trackingE1, ne.trackingE2)));
        } else if (trackingRatio >= 1.05) {
            text.append(String.format(Locale.US, "All stars share a slight stretch of %.0f %% (%s): tracking or guiding; it is taken out of the map. ",
                    (trackingRatio - 1) * 100, lineDirection(ne.trackingE1, ne.trackingE2)));
        } else {
            text.append("Round on average: the stars share no measured stretch beyond 5 %. ");
        }
        double radial = ne.radialEllipticity;
        if (Double.isNaN(radial)) {
            return text.toString();
        }
        double ratio = axisRatio(Math.abs(radial));
        if (ratio < 1.05) {
            text.append("In the outer field the stars keep their shape (within 5 %).");
        } else if (radial > 0) {
            text.append(String.format(Locale.US, "In the outer field the stars are stretched %.0f %% along the line to the centre: typical of a reducer or flattener at the wrong distance from the camera, or of coma.", (ratio - 1) * 100));
        } else {
            text.append(String.format(Locale.US, "In the outer field the stars are stretched %.0f %% across the line to the centre: typical of a reducer or flattener at the wrong distance (the opposite way to stretching along the line), of astigmatism, or of field rotation.", (ratio - 1) * 100));
        }
        return text.toString();
    }

    private static String comaReading(NightEquipment ne) {
        if (Double.isNaN(ne.comaSlope)) {
            return "Too few bright stars across the field to measure coma.";
        }
        double halfDiagonal = Math.hypot(ne.width, ne.height) / 2;
        double corner = Math.abs(ne.comaSlope) * halfDiagonal;
        double share = corner / ne.centreSizePx;
        boolean significant = Math.abs(ne.comaSlope) > 3 * ne.comaSlopeError;
        StringBuilder text = new StringBuilder();
        double lean = Math.hypot(ne.comaCentreX, ne.comaCentreY) / ne.centreSizePx;
        if (lean >= 0.04) {
            text.append(String.format(Locale.US, "All stars lean the same way, with their light %.0f %% of their size towards the %s of the core: "
                    + "the tracking or guiding moved during the exposures, or an optical part is slightly off-centre. ",
                    lean * 100, direction(ne.comaCentreX, ne.comaCentreY).replace(" side", "")));
        }
        if (!significant || share < 0.05) {
            double[] edge = largestComaCell(ne);
            if (edge != null && edge[0] >= 0.08 && edge[0] >= 2.5 * edge[3]) {
                text.append(String.format(Locale.US, "Only towards the %s are the stars lopsided, by up to %.0f %% of their size: this goes with an uneven focus across the field (see the star sizes), not with coma. ",
                        direction(edge[1], edge[2]).replace(" side", ""), edge[0] * 100));
            }
            text.append(String.format(Locale.US, "No coma at the edges: towards the corners the light of a star moves by only %.0f %% of its size against its core.", Math.max(share, 0) * 100));
        } else if (share < 0.10) {
            text.append(String.format(Locale.US, "The corner stars are slightly lopsided, with a faint tail pointing %s the centre: their light lies %s (%.0f %% of their size) from the core. "
                            + "Slight coma; with a camera lens it can also be lateral colour (the colours focused side by side).",
                    ne.comaSlope > 0 ? "away from" : "towards", format(corner * (ne.hasPixelScale() ? ne.pixelScale : 1), ne.hasPixelScale() ? SECOND : " px"), share * 100));
        } else {
            text.append(String.format(Locale.US, "The corner stars are lopsided, with a faint tail pointing %s the centre: their light lies %s (%.0f %% of their size) from the core. "
                            + "This pattern is consistent with coma. On a reflector, check the corrector and its spacing; asymmetric focus and colour effects can also contribute. ",
                    ne.comaSlope > 0 ? "away from" : "towards", format(corner * (ne.hasPixelScale() ? ne.pixelScale : 1), ne.hasPixelScale() ? SECOND : " px"), share * 100));
            if (!Double.isNaN(ne.comaZeroX)) {
                double zx = ne.comaZeroX - (ne.width - 1) / 2.0;
                double zy = ne.comaZeroY - (ne.height - 1) / 2.0;
                double off = Math.hypot(zx, zy) / halfDiagonal;
                boolean inside = ne.comaZeroX >= 0 && ne.comaZeroY >= 0 && ne.comaZeroX < ne.width && ne.comaZeroY < ne.height;
                if (inside && off >= 0.2) {
                    text.append(String.format(Locale.US, "The point without coma (the cross) lies %.0f %% of the way from the centre to the %s corner: on a reflector, the collimation is probably off.",
                            off * 100, direction(zx, zy)));
                } else if (inside) {
                    text.append("The point without coma is near the centre: the fitted asymmetry is centred within these measurements.");
                }
            }
        }
        return text.toString();
    }

    /**
     * The cell with the most lopsided stars: {share of the star size, x and y of the cell from the centre, median share
     * of all cells}; null when none.
     */
    private static double[] largestComaCell(NightEquipment ne) {
        double[] best = null;
        List<Double> shares = new ArrayList<>();
        for (int r = 0; r < ne.rows; r++) {
            for (int c = 0; c < ne.columns; c++) {
                if (Double.isNaN(ne.comaX[r][c])) {
                    continue;
                }
                double share = Math.hypot(ne.comaX[r][c], ne.comaY[r][c]) / ne.centreSizePx;
                shares.add(share);
                if (best == null || share > best[0]) {
                    double[] uv = ne.cellCentre(r, c);
                    best = new double[]{share, uv[0], uv[1], 0};
                }
            }
        }
        if (best != null) {
            best[3] = Statistics.median(shares);
        }
        return best;
    }

    // ==========================================
    // CALIBRATION
    // ==========================================

    private static void writeCalibration(PrintWriter report, NightEquipment ne) {
        if (ne.background == null && ne.dimming == null) {
            return;
        }
        report.println("<h3 class='ne-heading'>Flat-field check</h3>");
        report.println("<p class='compact-note' style='margin-top: 0;'>Orange: dimmer than the centre (or the frame's median); blue: brighter.</p>");
        report.println("<div class='ne-grid'>");
        if (ne.dimming != null) {
            report.println("<div><div class='ne-chart-title'>Star brightness against Gaia, compared with the centre</div>");
            report.println(heatMap(ne, ne.dimming, ne.rows, ne.columns, v -> v, 0.15, (r, c) -> {
                double value = ne.dimming[r][c];
                return Double.isNaN(value) ? null : signedPercent(-dimFraction(value));
            }, (r, c) -> {
                double value = ne.dimming[r][c];
                return Double.isNaN(value) ? "" : String.format(Locale.US, "%+.3f mag against the centre", -value);
            }));
            report.println("</div>");
        }
        if (ne.background != null) {
            report.println("<div><div class='ne-chart-title'>Sky background against the frame's median</div>");
            report.println(heatMap(ne, ne.background, ne.backgroundRows, ne.backgroundColumns, v -> -v, 0.1, (r, c) -> null, (r, c) -> {
                double value = ne.background[r][c];
                return Double.isNaN(value) ? "" : String.format(Locale.US, "%+.1f %%", value * 100);
            }));
            report.println("</div>");
        }
        report.println("</div>");
        report.println("<p class='ne-reading'>" + calibrationReading(ne) + "</p>");
    }

    private static String calibrationReading(NightEquipment ne) {
        StringBuilder text = new StringBuilder();
        double starCorner = Double.isNaN(ne.cornerDimming) ? Double.NaN : dimFraction(ne.cornerDimming);
        double skyCorner = ne.backgroundCorner;
        if (!Double.isNaN(starCorner)) {
            if (Math.abs(starCorner) < 0.03) {
                text.append("The stars are equally bright across the field (within 3 %): no brightness gradient larger than this is resolved in the measured stars. ");
            } else if (starCorner > 0) {
                text.append(String.format(Locale.US, "The stars are %.0f %% dimmer in the corners than in the centre", starCorner * 100));
                if (!Double.isNaN(skyCorner) && skyCorner < -0.02) {
                    text.append(", and so is the sky: vignetting that the flats did not remove (flats missing, or taken with a different setup or focus). ");
                } else {
                    text.append(" while the sky background is even: vignetting remains in the stars; the background was probably flattened afterwards (gradient removal), which does not correct the stars. ");
                }
            } else {
                text.append(String.format(Locale.US, "The stars are %.0f %% brighter in the corners than in the centre: the flats over-correct "
                        + "(for example flats with stray light, or taken with a different setup). ", -starCorner * 100));
            }
            double side = dimFraction(Math.hypot(ne.dimmingTiltX, ne.dimmingTiltY));
            if (Math.abs(side) >= 0.03) {
                text.append(String.format(Locale.US, "They are %.0f %% dimmer towards the %s: uneven illumination of the flats or a light leak. ",
                        Math.abs(side) * 100, direction(ne.dimmingTiltX, ne.dimmingTiltY)));
            }
        } else if (ne.calibrationStars == 0) {
            text.append("The star brightness needs the sky catalogue (Fetch Sky Catalogue before the detection) and a plate solution. ");
        }
        if (!Double.isNaN(skyCorner)) {
            double gradient = Math.hypot(ne.backgroundTiltX, ne.backgroundTiltY);
            if (gradient >= 0.03) {
                text.append(String.format(Locale.US, "The sky background is %.0f %% brighter towards the %s: a gradient from light pollution, the Moon or twilight. ",
                        gradient * 100, direction(ne.backgroundTiltX, ne.backgroundTiltY)));
            }
            if (Math.abs(skyCorner) >= 0.03) {
                text.append(String.format(Locale.US, "The sky is %.0f %% %s in the corners than in the centre.", Math.abs(skyCorner) * 100,
                        skyCorner < 0 ? "darker" : "brighter"));
            } else if (gradient < 0.03) {
                text.append("The sky background is even across the field (within 3 %).");
            }
        }
        return text.toString().trim();
    }

    private static void writeMethod(PrintWriter report, NightEquipment ne) {
        report.println("<details class='ne-method'><summary>How these are measured</summary><div class='astro-note'>");
        report.println("<p>The stars are the isolated stars of the master star map, the brightest of each part of the field. In every kept frame each star is measured on a small cut-out: "
                + "its size and shape from moments weighted by a Gaussian matched to the star, its brightness in an aperture, and its lopsidedness (coma) as the offset of its light-weighted "
                + "centre from its weighted core. Saturated stars and stars near saturation are left out.</p>");
        report.println("<p>Seeing is the median star size within the central third of the field. The maps take the median over all frames of each star's value against its frame "
                + "(size against the frame's centre size, shape minus the shape shared by all stars of the frame). Curvature and tilt come from a bowl and a slope fitted to the size map; "
                + "coma from offsets that grow from a point without coma.</p>");
        StringBuilder calibration = new StringBuilder("<p>Transparency compares the same stars between frames. ");
        if (ne.calibrationStars > 0) {
            calibration.append(String.format(Locale.US, "%d stars were matched with Gaia DR3; each frame's zero point uses Gaia G with a colour term of %.2f per magnitude of BP&minus;RP",
                    ne.calibrationStars, ne.colourTerm));
            if (!Double.isNaN(ne.linearity)) {
                calibration.append(String.format(Locale.US, ", and the brightness follows Gaia with a slope of %.2f (1 for linear data)", ne.linearity));
            }
            calibration.append(". The sky brightness is the sky level per square arcsecond on that zero point.");
        }
        report.println(calibration.append("</p>"));
        for (String note : ne.notes) {
            report.println("<p>" + esc(note) + "</p>");
        }
        report.println(String.format(Locale.US, "<p>These are diagnostic patterns, not a unique diagnosis of the optics. Blank map cells lack enough usable measurements. Measured in %.1f s.</p>", ne.millis / 1000.0));
        report.println("</div></details>");
    }

    // ==========================================
    // CHARTS AND MAPS
    // ==========================================

    /** A line chart over the frames (by time when every frame has one), with an optional second series on the right axis. */
    static String lineChart(NightEquipment ne, ToDoubleFunction<NightEquipment.Frame> value, ToDoubleFunction<NightEquipment.Frame> second,
                            String format, String secondFormat, String note) {
        int width = 520;
        int height = 190;
        int left = 46;
        int right = second != null ? 50 : 14;
        int top = 12;
        int bottom = 30;
        List<NightEquipment.Frame> frames = new ArrayList<>();
        for (NightEquipment.Frame frame : ne.frames) {
            if (Double.isFinite(value.applyAsDouble(frame))) {
                frames.add(frame);
            }
        }
        StringBuilder svg = new StringBuilder("<svg viewBox='0 0 " + width + " " + height + "' class='ne-svg' font-family='Segoe UI, sans-serif' font-size='11'>");
        if (frames.isEmpty()) {
            return svg.append("</svg>").toString();
        }
        boolean byTime = true;
        for (NightEquipment.Frame frame : frames) {
            byTime &= frame.timestamp > 0;
        }
        long t0 = byTime ? frames.get(0).timestamp : 0;
        long t1 = byTime ? frames.get(frames.size() - 1).timestamp : 1;
        byTime &= t1 > t0;
        double[] range = range(frames, value);
        for (int i = 0; i <= 4; i++) {
            double v = range[0] + i * (range[1] - range[0]) / 4;
            double y = top + (range[1] - v) / (range[1] - range[0]) * (height - top - bottom);
            svg.append(String.format(Locale.US, "<line x1='%d' y1='%.1f' x2='%d' y2='%.1f' stroke='#444'/><text x='%d' y='%.1f' fill='#999' text-anchor='end'>%s</text>",
                    left, y, width - right, y, left - 6, y + 4, String.format(Locale.US, format, v)));
        }
        double[] secondRange = second != null ? range(frames, second) : null;
        if (secondRange != null) {
            for (int i = 0; i <= 4; i += 2) {
                double v = secondRange[0] + i * (secondRange[1] - secondRange[0]) / 4;
                double y = top + (secondRange[1] - v) / (secondRange[1] - secondRange[0]) * (height - top - bottom);
                svg.append(String.format(Locale.US, "<text x='%d' y='%.1f' fill='#d9a35b'>%s</text>", width - right + 6, y + 4, String.format(Locale.US, secondFormat, v)));
            }
        }
        StringBuilder line = new StringBuilder();
        StringBuilder secondLine = new StringBuilder();
        for (int i = 0; i < frames.size(); i++) {
            NightEquipment.Frame frame = frames.get(i);
            double fraction = byTime ? (frame.timestamp - t0) / (double) (t1 - t0) : frames.size() == 1 ? 0.5 : i / (double) (frames.size() - 1);
            double x = left + fraction * (width - left - right);
            double v = value.applyAsDouble(frame);
            double y = top + (range[1] - v) / (range[1] - range[0]) * (height - top - bottom);
            line.append(String.format(Locale.US, "%.1f,%.1f ", x, y));
            svg.append(String.format(Locale.US, "<circle cx='%.1f' cy='%.1f' r='2.6' fill='#4da6ff'><title>Frame %d%s: %s</title></circle>",
                    x, y, frame.index + 1, frame.timestamp > 0 ? " (" + ReportFormat.formatUtcTimestamp(frame.timestamp) + ")" : "",
                    String.format(Locale.US, format.replace("%.1f", "%.2f"), v)));
            if (secondRange != null) {
                double s = second.applyAsDouble(frame);
                if (Double.isFinite(s)) {
                    double sy = top + (secondRange[1] - s) / (secondRange[1] - secondRange[0]) * (height - top - bottom);
                    secondLine.append(String.format(Locale.US, "%.1f,%.1f ", x, sy));
                }
            }
        }
        if (secondLine.length() > 0) {
            svg.append("<polyline points='").append(secondLine.toString().trim()).append("' fill='none' stroke='#d9a35b' stroke-width='1.2' stroke-dasharray='4 3'/>");
        }
        svg.append("<polyline points='").append(line.toString().trim()).append("' fill='none' stroke='#4da6ff' stroke-width='1.5'/>");
        String axis = byTime ? "Time →" : "Frame →";
        if (byTime) {
            axis = String.format(Locale.US, "%s → %s UTC", hhmm(t0), hhmm(t1));
        }
        svg.append(String.format(Locale.US, "<text x='%d' y='%d' fill='#999'>%s</text><text x='%d' y='%d' fill='#999' text-anchor='end'>%s</text>",
                left, height - 8, axis, width - right, height - 8, note + (second != null ? "; temperature dashed" : "")));
        return svg.append("</svg>").toString();
    }

    private static String hhmm(long millis) {
        String stamp = ReportFormat.formatUtcTimestamp(millis);
        int space = stamp.indexOf(' ');
        return space >= 0 && stamp.length() >= space + 6 ? stamp.substring(space + 1, space + 6) : stamp;
    }

    private static double[] range(List<NightEquipment.Frame> frames, ToDoubleFunction<NightEquipment.Frame> value) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (NightEquipment.Frame frame : frames) {
            double v = value.applyAsDouble(frame);
            if (Double.isFinite(v)) {
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
        }
        if (!Double.isFinite(min)) {
            return new double[]{0, 1};
        }
        double pad = Math.max((max - min) * 0.15, Math.max(Math.abs(max), 1e-3) * 0.02);
        return new double[]{min - pad, max + pad};
    }

    interface CellText {
        String at(int r, int c);
    }

    interface Badness {
        double of(double value);
    }

    /** A map of the frame in cells, coloured from blue (better) through grey to orange (worse), with an optional label per cell. */
    static String heatMap(NightEquipment ne, double[][] values, int rows, int columns, Badness badness, double full, CellText label, CellText title) {
        int width = 400;
        int height = (int) Math.round(width * ne.height / (double) ne.width);
        double cw = width / (double) columns;
        double ch = height / (double) rows;
        StringBuilder svg = new StringBuilder("<svg viewBox='0 0 " + width + " " + height + "' class='ne-svg ne-map' font-family='Segoe UI, sans-serif' font-size='"
                + (columns > 10 ? 9 : 11) + "'>");
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                double value = values[r][c];
                String fill = Double.isNaN(value) ? "#262626" : colour(badness.of(value) / full);
                svg.append(String.format(Locale.US, "<rect x='%.1f' y='%.1f' width='%.1f' height='%.1f' fill='%s' stroke='#2b2b2b'><title>%s</title></rect>",
                        c * cw, r * ch, cw, ch, fill, esc(title.at(r, c))));
                String text = label.at(r, c);
                if (text != null) {
                    svg.append(String.format(Locale.US, "<text x='%.1f' y='%.1f' fill='#f0f0f0' text-anchor='middle' pointer-events='none'>%s</text>",
                            (c + 0.5) * cw, (r + 0.5) * ch + 4, text));
                }
            }
        }
        return svg.append("</svg>").toString();
    }

    /** -1 (better, blue) .. 0 (grey) .. +1 (worse, orange). */
    private static String colour(double t) {
        t = Math.max(-1, Math.min(1, t));
        int[] grey = {70, 72, 76};
        int[] target = t >= 0 ? new int[]{214, 110, 50} : new int[]{60, 130, 210};
        double a = Math.abs(t);
        return String.format("#%02x%02x%02x", (int) (grey[0] + a * (target[0] - grey[0])), (int) (grey[1] + a * (target[1] - grey[1])),
                (int) (grey[2] + a * (target[2] - grey[2])));
    }

    /** Each cell's star stretch (tracking removed) as a line along the stretch, its length growing with the stretch. */
    private static String whiskerMap(NightEquipment ne) {
        int width = 400;
        int height = (int) Math.round(width * ne.height / (double) ne.width);
        double cw = width / (double) ne.columns;
        double ch = height / (double) ne.rows;
        double scale = Math.min(cw, ch) * 0.45 / 0.15; // 15 % stretch fills most of a cell
        StringBuilder svg = new StringBuilder("<svg viewBox='0 0 " + width + " " + height + "' class='ne-svg ne-map'>");
        svg.append(grid(width, height, ne.rows, ne.columns));
        for (int r = 0; r < ne.rows; r++) {
            for (int c = 0; c < ne.columns; c++) {
                double e1 = ne.shapeE1[r][c];
                double e2 = ne.shapeE2[r][c];
                if (Double.isNaN(e1)) {
                    continue;
                }
                double ratio = axisRatio(Math.hypot(e1, e2));
                double angle = 0.5 * Math.atan2(e2, e1);
                double half = Math.max(1.5, Math.min(Math.min(cw, ch) * 0.48, (ratio - 1) * scale));
                double x = (c + 0.5) * cw;
                double y = (r + 0.5) * ch;
                svg.append(String.format(Locale.US, "<line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='#8fc3ff' stroke-width='3' stroke-linecap='round'><title>%s</title></line>",
                        x - half * Math.cos(angle), y - half * Math.sin(angle), x + half * Math.cos(angle), y + half * Math.sin(angle),
                        String.format(Locale.US, "Stretched %.1f %%", (ratio - 1) * 100)));
            }
        }
        return svg.append("</svg>").toString();
    }

    /** Each cell's coma offset as an arrow from the core towards the light, much enlarged. */
    private static String comaMap(NightEquipment ne) {
        int width = 400;
        int height = (int) Math.round(width * ne.height / (double) ne.width);
        double cw = width / (double) ne.columns;
        double ch = height / (double) ne.rows;
        double largest = 1e-6;
        for (int r = 0; r < ne.rows; r++) {
            for (int c = 0; c < ne.columns; c++) {
                if (!Double.isNaN(ne.comaX[r][c])) {
                    largest = Math.max(largest, Math.hypot(ne.comaX[r][c], ne.comaY[r][c]) / ne.centreSizePx);
                }
            }
        }
        // 10 % of the star size fills most of a cell; smaller offsets stay small.
        double scale = Math.min(cw, ch) * 0.6 / Math.max(0.10, largest);
        StringBuilder svg = new StringBuilder("<svg viewBox='0 0 " + width + " " + height + "' class='ne-svg ne-map'>");
        svg.append("<defs><marker id='ne-arrow' viewBox='0 0 10 10' refX='8' refY='5' markerWidth='3.5' markerHeight='3.5' orient='auto-start-reverse'>"
                + "<path d='M0,0 L10,5 L0,10 z' fill='#ffb366'/></marker></defs>");
        svg.append(grid(width, height, ne.rows, ne.columns));
        for (int r = 0; r < ne.rows; r++) {
            for (int c = 0; c < ne.columns; c++) {
                double dx = ne.comaX[r][c];
                double dy = ne.comaY[r][c];
                if (Double.isNaN(dx)) {
                    continue;
                }
                double share = Math.hypot(dx, dy) / ne.centreSizePx;
                double length = share * scale;
                double x = (c + 0.5) * cw;
                double y = (r + 0.5) * ch;
                String tip = String.format(Locale.US, "Light %.2f px from the core (%.0f %% of the star size)", Math.hypot(dx, dy), share * 100);
                if (length < 2) {
                    svg.append(String.format(Locale.US, "<circle cx='%.1f' cy='%.1f' r='2' fill='#ffb366'><title>%s</title></circle>", x, y, tip));
                    continue;
                }
                double ux = dx / Math.hypot(dx, dy);
                double uy = dy / Math.hypot(dx, dy);
                svg.append(String.format(Locale.US, "<line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f' stroke='#ffb366' stroke-width='2.5' marker-end='url(#ne-arrow)'><title>%s</title></line>",
                        x - ux * length / 2, y - uy * length / 2, x + ux * length / 2, y + uy * length / 2, tip));
            }
        }
        if (!Double.isNaN(ne.comaZeroX) && ne.comaZeroX >= 0 && ne.comaZeroY >= 0 && ne.comaZeroX < ne.width && ne.comaZeroY < ne.height
                && Math.abs(ne.comaSlope) > 3 * ne.comaSlopeError
                && Math.abs(ne.comaSlope) * Math.hypot(ne.width, ne.height) / 2 >= 0.10 * ne.centreSizePx) {
            double x = ne.comaZeroX / ne.width * width;
            double y = ne.comaZeroY / ne.height * height;
            svg.append(String.format(Locale.US, "<g stroke='#ffffff' stroke-width='1.5'><line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f'/><line x1='%.1f' y1='%.1f' x2='%.1f' y2='%.1f'/>"
                    + "<title>Point without coma</title></g>", x - 7, y, x + 7, y, x, y - 7, x, y + 7));
        }
        return svg.append("</svg>").toString();
    }

    private static String grid(int width, int height, int rows, int columns) {
        StringBuilder svg = new StringBuilder();
        for (int r = 1; r < rows; r++) {
            double y = r * height / (double) rows;
            svg.append(String.format(Locale.US, "<line x1='0' y1='%.1f' x2='%d' y2='%.1f' stroke='#3a3a3a'/>", y, width, y));
        }
        for (int c = 1; c < columns; c++) {
            double x = c * width / (double) columns;
            svg.append(String.format(Locale.US, "<line x1='%.1f' y1='0' x2='%.1f' y2='%d' stroke='#3a3a3a'/>", x, x, height));
        }
        return svg.toString();
    }

    // ==========================================
    // HELPERS
    // ==========================================

    /** Words for where a quantity grows: (dx, dy) in the picture, x to the right and y downwards. */
    static String direction(double dx, double dy) {
        double angle = Math.toDegrees(Math.atan2(dy, dx));
        String[] names = {"right", "bottom right", "bottom", "bottom left", "left", "top left", "top", "top right"};
        int sector = (int) Math.floor(((angle + 360 + 22.5) % 360) / 45);
        String name = names[sector];
        return name.contains(" ") ? name : name + " side";
    }

    /** The direction of a stretch with ellipticity (e1, e2), in words. */
    private static String lineDirection(double e1, double e2) {
        double angle = Math.toDegrees(0.5 * Math.atan2(e2, e1));
        double a = ((angle % 180) + 180) % 180;
        if (a < 22.5 || a >= 157.5) {
            return "left to right";
        }
        if (a < 67.5) {
            return "top left to bottom right";
        }
        if (a < 112.5) {
            return "top to bottom";
        }
        return "top right to bottom left";
    }

    private static double axisRatio(double e) {
        e = Math.min(0.99, Math.abs(e));
        return Math.sqrt((1 + e) / (1 - e));
    }

    /** A dimming in magnitudes as a fraction of the light lost. */
    private static double dimFraction(double magnitudes) {
        return 1 - Math.pow(10, -0.4 * magnitudes);
    }

    private static String signedPercent(double fraction) {
        long rounded = Math.round(fraction * 100);
        return (rounded > 0 ? "+" : rounded < 0 ? "&minus;" : "") + Math.abs(rounded) + " %";
    }

    private static String size(NightEquipment ne, double px) {
        return ne.hasPixelScale() ? String.format(Locale.US, "%.1f%s", px * ne.pixelScale, SECOND) : String.format(Locale.US, "%.1f px", px);
    }

    private static String format(double value, String unit) {
        return String.format(Locale.US, "%.1f%s", value, unit);
    }

    private static String angle(double degrees) {
        return degrees >= 1 ? String.format(Locale.US, "%.2f&deg;", degrees) : String.format(Locale.US, "%.0f&prime;", degrees * 60);
    }

    private static List<Double> values(NightEquipment ne, ToDoubleFunction<NightEquipment.Frame> value) {
        List<Double> values = new ArrayList<>();
        for (NightEquipment.Frame frame : ne.frames) {
            double v = value.applyAsDouble(frame);
            if (Double.isFinite(v)) {
                values.add(v);
            }
        }
        return values;
    }

    private static double medianOf(NightEquipment ne, ToDoubleFunction<NightEquipment.Frame> value) {
        return Statistics.median(values(ne, value));
    }

    private static double maxOf(NightEquipment ne, ToDoubleFunction<NightEquipment.Frame> value) {
        double max = Double.NaN;
        for (double v : values(ne, value)) {
            max = Double.isNaN(max) ? v : Math.max(max, v);
        }
        return max;
    }

    /** Spearman's rank correlation of the values with their order. */
    static double rankCorrelation(List<Double> values) {
        int n = values.size();
        if (n < 3) {
            return 0;
        }
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values.get(a), values.get(b)));
        double[] rank = new double[n];
        for (int i = 0; i < n; i++) {
            rank[order[i]] = i;
        }
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double d = rank[i] - i;
            sum += d * d;
        }
        return 1 - 6 * sum / (n * ((double) n * n - 1));
    }

    private static String esc(String text) {
        return ReportFormat.escapeHtml(text == null ? "" : text);
    }
}
