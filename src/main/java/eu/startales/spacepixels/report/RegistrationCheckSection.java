package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The quality-control registration check in the report: how far the stars move between the registered frames, over
 * the whole frame and in zones, in star widths (FWHM). One place builds both the {@code registrationCheck} key of
 * report-data.json and the report panel, so they show the same values. Flagged zones are offered as excluded areas.
 */
final class RegistrationCheckSection {
    private RegistrationCheckSection() {
    }

    /** The report-data.json value, or null when quality control did not run the check. NaN becomes null. */
    static Map<String, Object> data(PipelineTelemetry telemetry) {
        if (telemetry == null || telemetry.registrationCheck == null) {
            return null;
        }
        PipelineTelemetry.RegistrationCheckTelemetry r = telemetry.registrationCheck;
        if (!r.available && r.reason == null) {
            return null;
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("available", r.available);
        if (!r.available) {
            data.put("reason", r.reason);
            return data;
        }
        data.put("fwhmPixels", finite(r.fwhmPixels));
        data.put("framesMeasured", r.framesMeasured);
        data.put("referenceStars", r.referenceStars);
        data.put("framesRejected", r.framesRejected);
        data.put("largestKeptJumpFwhm", finite(r.largestKeptJumpFwhm));
        data.put("creepLimitFwhm", finite(r.creepLimitFwhm));
        data.put("overallCreepFwhm", finite(r.overallCreepFwhm));
        data.put("overallFlagged", r.overallCreepFwhm > r.creepLimitFwhm);
        data.put("zones", r.zones);
        List<Map<String, Object>> zones = new ArrayList<>();
        for (int row = 0; row < r.zones; row++) {
            for (int column = 0; column < r.zones; column++) {
                int i = row * r.zones + column;
                int[] rect = io.github.ppissias.jtransient.quality.RegistrationCheck.zoneRectangle(r.width, r.height, row, column);
                var zone = new LinkedHashMap<String, Object>();
                zone.put("row", row);
                zone.put("column", column);
                zone.put("x", rect[0]);
                zone.put("y", rect[1]);
                zone.put("width", rect[2]);
                zone.put("height", rect[3]);
                zone.put("stars", r.zoneStars[i]);
                zone.put("creepFwhm", finite(r.zoneCreepFwhm[i]));
                zone.put("flagged", r.zoneCreepFwhm[i] > r.creepLimitFwhm);
                zones.add(zone);
            }
        }
        data.put("zoneCreep", zones);
        return data;
    }

    private static Double finite(double value) {
        return Double.isFinite(value) ? value : null;
    }

    /** Whether the report shows the panel: quality control ran the check, measured or not. */
    static boolean shown(PipelineTelemetry telemetry) {
        return telemetry != null && telemetry.registrationCheck != null
                && (telemetry.registrationCheck.available || telemetry.registrationCheck.reason != null);
    }

    /** Whether the check flagged the whole frame or a zone. */
    static boolean flagged(PipelineTelemetry.RegistrationCheckTelemetry r) {
        if (!r.available) {
            return false;
        }
        if (r.overallCreepFwhm > r.creepLimitFwhm) {
            return true;
        }
        for (double creep : r.zoneCreepFwhm) {
            if (creep > r.creepLimitFwhm) {
                return true;
            }
        }
        return false;
    }

    /** Writes the panel: the summary, the zone map and, when something is flagged, what to do. */
    static void write(PrintWriter report, PipelineTelemetry telemetry) {
        if (!shown(telemetry)) {
            return;
        }
        PipelineTelemetry.RegistrationCheckTelemetry r = telemetry.registrationCheck;
        report.println("<section class='panel' id='registration-check'>");
        report.println("<h2>Registration Check</h2>");
        report.println("<p class='section-lede'>How far the stars move between the registered frames, in star widths "
                + "(FWHM). Stars that move leave light past the star masks, which the detection stages can take for "
                + "moving objects. Frames whose stars jump against their neighbours are rejected; a slow change over "
                + "the session is shown here.</p>");
        if (!r.available) {
            report.println("<div class='astro-note'>Not measured: " + ReportFormat.escapeHtml(r.reason) + ".</div>");
            report.println("</section>");
            return;
        }
        report.printf(Locale.US, "<p>%d frames, %d reference stars, star width %.2f px. Frames rejected for a jump: "
                        + "%d (largest jump among the kept frames %.2f FWHM). Whole-frame change over the session: "
                        + "<strong>%.2f FWHM</strong> (%.1f px).</p>%n",
                r.framesMeasured, r.referenceStars, r.fwhmPixels, r.framesRejected, r.largestKeptJumpFwhm,
                r.overallCreepFwhm, r.overallCreepFwhm * r.fwhmPixels);
        report.println("<table class='registration-grid' aria-label='Change of the star positions over the session, "
                + "by zone of the frame'>");
        for (int row = 0; row < r.zones; row++) {
            report.print("<tr>");
            for (int column = 0; column < r.zones; column++) {
                double creep = r.zoneCreepFwhm[row * r.zones + column];
                if (Double.isNaN(creep)) {
                    report.print("<td class='unmeasured' title='Too few stars'>&ndash;</td>");
                } else {
                    report.printf(Locale.US, "<td class='%s' title='%.1f px'>%.2f</td>",
                            creep > r.creepLimitFwhm ? "flagged" : "", creep * r.fwhmPixels, creep);
                }
            }
            report.println("</tr>");
        }
        report.println("</table>");
        report.printf(Locale.US, "<div class='astro-note'>Change of the star positions over the session in each zone of "
                + "the frame, relative to the whole frame, in FWHM; flagged above %.2f. &ndash;: too few stars.</div>%n",
                r.creepLimitFwhm);
        if (r.overallCreepFwhm > r.creepLimitFwhm) {
            report.printf(Locale.US, "<div class='registration-alert'><strong>The whole star field moves by %.2f FWHM "
                            + "(%.1f px) during the session.</strong> The frames are not registered on the stars, for "
                            + "example registered on a moving target or with a drifting registration. Detections that "
                            + "move with the field may be false. Register the frames on the stars.</div>%n",
                    r.overallCreepFwhm, r.overallCreepFwhm * r.fwhmPixels);
        }
        int flaggedZones = 0;
        double worst = 0;
        for (double creep : r.zoneCreepFwhm) {
            if (creep > r.creepLimitFwhm) {
                flaggedZones++;
                worst = Math.max(worst, creep);
            }
        }
        if (flaggedZones > 0) {
            List<String> areas = new ArrayList<>();
            for (int[] a : io.github.ppissias.jtransient.quality.RegistrationCheck.flaggedAreas(r.width, r.height, r.zones,
                    r.zoneCreepFwhm, r.creepLimitFwhm)) {
                areas.add(String.format(Locale.US, "x %d&ndash;%d, y %d&ndash;%d", a[0], a[0] + a[2] - 1, a[1], a[1] + a[3] - 1));
            }
            report.printf(Locale.US, "<div class='registration-alert'><strong>Stars move by up to %.2f FWHM (%.1f px) "
                            + "in %d zone%s during the session.</strong> The registration does not follow the field there, "
                            + "for example a lens distortion it does not model. Detections there may be false. "
                            + "Register the frames with distortion correction, or exclude %s (the Registration chip of the "
                            + "frame list offers this): %s.</div>%n",
                    worst, worst * r.fwhmPixels, flaggedZones, flaggedZones == 1 ? "" : "s",
                    areas.size() == 1 ? "this area" : "these areas", String.join("; ", areas));
        }
        report.println("</section>");
    }
}
