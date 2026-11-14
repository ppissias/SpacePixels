package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.config.AppConfig;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The steps between a report's objects and an ADES file that need no window: which observations are selected by
 * default, how an object is identified in the file, the submission details from the settings, and the residuals from
 * straight-line motion that show whether a track's positions belong together.
 */
public final class MpcExport {
    /** How many observations per object are selected by default: the MPC asks for a few good ones per night. */
    public static final int DEFAULT_SELECTION = 6;
    private static final Pattern NUMBERED = Pattern.compile("^(\\d+)(?: .*)?$");
    private static final Pattern PROVISIONAL = Pattern.compile("^\\(?(\\d{4} [A-Z]{2}\\d*)\\)?$");

    private MpcExport() { }

    /**
     * The indexes of {@code count} observations spread evenly over the time order, always the first and the last;
     * all of them when there are no more than {@code count}.
     */
    public static List<Integer> spreadSelection(int observations, int count) {
        if (observations <= count) return java.util.stream.IntStream.range(0, observations).boxed().toList();
        var selected = new java.util.TreeSet<Integer>();
        for (int k = 0; k < count; k++) selected.add((int) Math.round(k * (observations - 1) / (double) (count - 1)));
        return List.copyOf(selected);
    }

    /**
     * How an object is named in the file: a known numbered object by its number, an unnumbered one by its provisional
     * designation, anything else (no match, ambiguous, a comet) by a tracklet ID unique within the night.
     */
    public static AdesPsv.Tracklet tracklet(ReportedObjects.ReportedObject object, List<MpcObservation> observations,
                                            long sessionStartMillis, int sequence) {
        if (ReportedObjects.KNOWN.equals(object.status()) && object.names().size() == 1) {
            String name = object.names().getFirst().trim();
            Matcher numbered = NUMBERED.matcher(name);
            if (numbered.matches()) return new AdesPsv.Tracklet(numbered.group(1), null, null, observations);
            Matcher provisional = PROVISIONAL.matcher(name);
            if (provisional.matches()) return new AdesPsv.Tracklet(null, provisional.group(1), null, observations);
        }
        return new AdesPsv.Tracklet(null, null, AdesPsv.trkSub(sessionStartMillis, sequence), observations);
    }

    /** The submission details from the settings; the station is the configured IAU observatory code. */
    public static AdesPsv.Context context(AppConfig config) {
        double aperture;
        try {
            aperture = Double.parseDouble(config.mpcAperture.trim().replace(',', '.'));
        } catch (RuntimeException notANumber) {
            aperture = 0;
        }
        return new AdesPsv.Context(config.observatoryCode == null ? "" : config.observatoryCode.trim().toUpperCase(Locale.ROOT),
                config.mpcSubmitter, names(config.mpcObservers), names(config.mpcMeasurers), config.mpcTelescopeDesign,
                aperture, config.mpcDetector, config.mpcMode, config.mpcComment);
    }

    /** Names separated by commas or semicolons. */
    static List<String> names(String text) {
        if (text == null || text.isBlank()) return List.of();
        return Arrays.stream(text.split("[,;]")).map(String::trim).filter(name -> !name.isEmpty()).toList();
    }

    /**
     * Each observation's offset from straight-line motion fitted to all of them, in arcseconds: {RA times cos Dec,
     * Dec} per observation, in their order. Empty for fewer than three observations, where a line says nothing.
     */
    public static double[][] linearResiduals(List<MpcObservation> observations) {
        int n = observations.size();
        if (n < 3) return new double[0][];
        double t0 = observations.getFirst().midpointMillis();
        double ra0 = observations.getFirst().raDegrees();
        double cosDec = Math.cos(Math.toRadians(observations.getFirst().decDegrees()));
        double[] t = new double[n], x = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            var o = observations.get(i);
            t[i] = (o.midpointMillis() - t0) / 3_600_000.0;
            x[i] = ((o.raDegrees() - ra0 + 540) % 360 - 180) * cosDec * 3600;
            y[i] = o.decDegrees() * 3600;
        }
        double[] fx = line(t, x), fy = line(t, y);
        double[][] residuals = new double[n][];
        for (int i = 0; i < n; i++) residuals[i] = new double[]{x[i] - fx[0] - fx[1] * t[i], y[i] - fy[0] - fy[1] * t[i]};
        return residuals;
    }

    /** The least-squares line {intercept, slope} through the points. */
    private static double[] line(double[] t, double[] v) {
        double mt = Arrays.stream(t).average().orElse(0), mv = Arrays.stream(v).average().orElse(0);
        double num = 0, den = 0;
        for (int i = 0; i < t.length; i++) { num += (t[i] - mt) * (v[i] - mv); den += (t[i] - mt) * (t[i] - mt); }
        double slope = den > 0 ? num / den : 0;
        return new double[]{mv - slope * mt, slope};
    }

    /** The RMS of residuals {RA, Dec} in arcseconds over the given indexes, or NaN when there are none. */
    public static double rms(double[][] residuals, List<Integer> indexes) {
        double squares = 0;
        int count = 0;
        for (int i : indexes) {
            if (i < 0 || i >= residuals.length) continue;
            squares += residuals[i][0] * residuals[i][0] + residuals[i][1] * residuals[i][1];
            count++;
        }
        return count == 0 ? Double.NaN : Math.sqrt(squares / count);
    }

    /** One line per selected object for a summary: "T1 · 649341 · 6 observations over 3.97 h". */
    public static String describe(AdesPsv.Tracklet tracklet, String findingId) {
        var o = tracklet.observations();
        double hours = o.isEmpty() ? 0 : (o.getLast().midpointMillis() - o.getFirst().midpointMillis()) / 3_600_000.0;
        String id = tracklet.permID() != null ? tracklet.permID() : tracklet.provID() != null ? tracklet.provID() : tracklet.trkSub();
        return String.format(Locale.US, "%s · %s · %d observation%s over %.2f h", findingId, id, o.size(), o.size() == 1 ? "" : "s", hours);
    }

    /**
     * The values MPChecker's form asks for, for one observation: the UTC date with a decimal day, RA and Dec in
     * sexagesimal and the observatory code. MPChecker has no documented programmatic interface, so the user types or
     * pastes these into its page.
     */
    public static String mpcheckerValues(MpcObservation observation, String stationCode) {
        var time = java.time.Instant.ofEpochMilli(observation.midpointMillis()).atZone(java.time.ZoneOffset.UTC);
        double day = time.getDayOfMonth() + time.toLocalTime().toNanoOfDay() / 86_400e9;
        long tenthsOfTimeSecond = Math.round(observation.raDegrees() / 15 * 36_000) % (24 * 36_000);
        long tenthsOfArcsecond = Math.round(Math.abs(observation.decDegrees()) * 36_000);
        return String.format(Locale.US, "Date (UT): %04d %02d %08.5f%nRA: %02d %02d %04.1f%nDec: %s%02d %02d %04.1f%nObservatory code: %s",
                time.getYear(), time.getMonthValue(), day,
                tenthsOfTimeSecond / 36_000, tenthsOfTimeSecond / 600 % 60, tenthsOfTimeSecond % 600 / 10.0,
                observation.decDegrees() < 0 ? "-" : "+", tenthsOfArcsecond / 36_000, tenthsOfArcsecond / 600 % 60, tenthsOfArcsecond % 600 / 10.0,
                stationCode == null || stationCode.isBlank() ? "500" : stationCode.trim().toUpperCase(Locale.ROOT));
    }

    /** The objects the window offers to export by default: tracks that are known or match nothing known. */
    public static boolean includedByDefault(ReportedObjects.ReportedObject object) {
        return object.measured() && (ReportedObjects.KNOWN.equals(object.status()) || ReportedObjects.NO_KNOWN_MATCH.equals(object.status()));
    }
}
