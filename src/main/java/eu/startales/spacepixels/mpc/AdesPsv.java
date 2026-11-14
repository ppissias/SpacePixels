package eu.startales.spacepixels.mpc;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Writes MPC observations as ADES PSV (pipe-separated values), the format the Minor Planet Center prefers: a
 * {@code # version} line, the observation context ({@code #} elements with {@code !} children), one keyword line and
 * one data line per observation, padded to the ADES default columns. The rules follow the ADES description (PSV,
 * chapter 5) and the submission schema; {@link #problems} lists what would make a file unacceptable before it is
 * written.
 */
public final class AdesPsv {
    /** The ADES version written on the first line. */
    static final String VERSION = "2022";
    /** ADES detector modes SpacePixels writes: CCD and CMOS. */
    public static final List<String> MODES = List.of("CCD", "CMO");
    private static final Pattern TRK_SUB = Pattern.compile("[A-Za-z0-9]{1,8}");
    private static final Pattern STATION = Pattern.compile("[A-Za-z0-9]{3,4}");
    private static final DateTimeFormatter OBS_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC);

    /**
     * Who and what took and measured the observations. Names are written as initials and surname, such as
     * "P. Smith"; ADES asks to keep other personal details out of the file.
     *
     * @param stationCode     the MPC observatory code of the acquisition site
     * @param submitter       the person submitting
     * @param observers       the people who took the data
     * @param measurers       the people who measured it
     * @param telescopeDesign such as "Reflector"
     * @param apertureMeters  the aperture in metres
     * @param detector        such as "CCD" or "CMOS"
     * @param mode            the ADES mode, one of {@link #MODES}
     * @param comment         free text, or empty
     */
    public record Context(String stationCode, String submitter, List<String> observers, List<String> measurers,
                          String telescopeDesign, double apertureMeters, String detector, String mode, String comment) { }

    /**
     * The observations of one object: its permanent designation (a number), its provisional designation, or else the
     * observer's tracklet identifier, which ADES requires to be unique within the submission.
     */
    public record Tracklet(String permID, String provID, String trkSub, List<MpcObservation> observations) { }

    private AdesPsv() { }

    /** Why these observations cannot be written as an acceptable submission; empty when they can. */
    public static List<String> problems(Context context, List<Tracklet> tracklets) {
        var problems = new ArrayList<String>();
        if (context.stationCode() == null || !STATION.matcher(context.stationCode().trim()).matches()) {
            problems.add("Set the IAU observatory code of the site where the frames were taken (Astrometry Config).");
        }
        if (blank(context.submitter())) problems.add("Name the submitter.");
        if (context.measurers() == null || context.measurers().stream().allMatch(AdesPsv::blank)) problems.add("Name the measurer.");
        if (blank(context.telescopeDesign()) || blank(context.detector()) || !(context.apertureMeters() > 0)) {
            problems.add("Describe the telescope: design, aperture and detector.");
        }
        if (!MODES.contains(context.mode())) problems.add("Choose the detector type: CCD or CMOS.");
        if (length(context.telescopeDesign()) > 35) problems.add("The telescope design is limited to 35 characters.");
        if (length(context.detector()) > 25) problems.add("The detector is limited to 25 characters.");
        if (length(context.comment()) > 100) problems.add("The comment is limited to 100 characters.");
        for (String text : texts(context)) {
            if (text.contains("|")) problems.add("The details must not contain the character |.");
        }
        if (tracklets.isEmpty()) problems.add("Select at least one object with observations.");
        var ids = new java.util.HashSet<String>();
        for (Tracklet t : tracklets) {
            if (blank(t.permID()) && blank(t.provID())) {
                if (t.trkSub() == null || !TRK_SUB.matcher(t.trkSub()).matches()) {
                    problems.add("Tracklet ID " + t.trkSub() + " must be 1 to 8 letters or digits.");
                } else if (!ids.add(t.trkSub())) {
                    problems.add("Tracklet ID " + t.trkSub() + " is used twice.");
                }
            }
            if (t.observations().isEmpty()) problems.add((blank(t.permID()) ? blank(t.provID()) ? t.trkSub() : t.provID() : t.permID())
                    + " has no observations.");
        }
        return problems;
    }

    /** How far a station may be from the observing site, in kilometres, before one of the two is doubted. */
    static final double STATION_TOLERANCE_KM = 5;

    /**
     * Why a station code does not belong to these frames: its station is far from the observing site (from the FITS
     * headers or the Astrometry Config tab). Null when they agree or the site is unknown.
     */
    public static String stationProblem(eu.startales.spacepixels.solarsystem.SolarSystemClient.Station station,
                                        eu.startales.spacepixels.fits.ObservingSite site) {
        if (station == null || site == null) return null;
        double lat1 = Math.toRadians(station.latitudeDeg()), lat2 = Math.toRadians(site.latitudeDeg());
        double dLat = lat2 - lat1, dLon = Math.toRadians(site.longitudeDeg() - station.longitudeDeg());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double km = 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(a)));
        if (km <= STATION_TOLERANCE_KM) return null;
        return String.format(Locale.US, "Observatory code %s is %s (%.3f° %s, %.3f° %s), %,.0f km from the observing site "
                        + "(%s). Check which one is right: the export reports the code's station.", station.code(), station.name(),
                Math.abs(station.latitudeDeg()), station.latitudeDeg() < 0 ? "S" : "N",
                Math.abs(station.longitudeDeg()), station.longitudeDeg() < 0 ? "W" : "E", km, site.describe());
    }

    /** The PSV text. Call {@link #problems} first: this writes what it is given. */
    public static String write(Context context, List<Tracklet> tracklets) {
        var out = new StringBuilder("# version=").append(VERSION).append('\n');
        out.append("# observatory\n! mpcCode ").append(context.stationCode().trim()).append('\n');
        out.append("# submitter\n! name ").append(context.submitter().trim()).append('\n');
        names(out, "observers", context.observers());
        names(out, "measurers", context.measurers());
        out.append("# telescope\n! design ").append(context.telescopeDesign().trim()).append('\n')
                .append("! aperture ").append(String.format(Locale.US, "%.2f", context.apertureMeters()).replaceAll("0$", "")).append('\n')
                .append("! detector ").append(context.detector().trim()).append('\n');
        out.append("# software\n! astrometry SpacePixels\n");
        if (!blank(context.comment())) out.append("# comment\n! line ").append(context.comment().trim().replace('\n', ' ')).append('\n');
        out.append(row("permID", "provID", "trkSub", "mode", "stn", "obsTime", "ra", "dec", "rmsRA", "rmsDec", "astCat", "exp", "notes"))
                .append('\n');
        for (Tracklet t : tracklets) {
            for (MpcObservation o : t.observations()) {
                out.append(row(text(t.permID()), text(t.provID()), blank(t.permID()) && blank(t.provID()) ? t.trkSub() : text(t.trkSub()),
                        context.mode(), context.stationCode().trim(), OBS_TIME.format(Instant.ofEpochMilli(o.midpointMillis())),
                        ra(o.raDegrees()), String.format(Locale.US, "%+.7f", o.decDegrees()),
                        String.format(Locale.US, "%.3f", o.rmsRaArcsec()), String.format(Locale.US, "%.3f", o.rmsDecArcsec()),
                        o.astCat(), o.exposureMillis() >= 1000 ? Long.toString(Math.round(o.exposureMillis() / 1000.0))
                                : o.exposureMillis() > 0 ? String.format(Locale.US, "%.3f", o.exposureMillis() / 1000.0) : "",
                        text(o.notes()))).append('\n');
            }
        }
        return out.toString();
    }

    /** The ADES default PSV widths of these fields (chapter 5.1); values are padded, never cut. */
    private static final int[] WIDTHS = {7, 11, 8, 4, 4, 23, 11, 11, 5, 6, 8, 4, 5};

    /** One pipe-separated line, each field padded to its default width. */
    private static String row(String... fields) {
        var line = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) line.append('|');
            line.append(String.format(Locale.US, "%-" + WIDTHS[i] + "s", fields[i]));
        }
        return line.toString().stripTrailing();
    }

    /** A context element with one {@code ! name} child per non-blank name; nothing when there is none. */
    private static void names(StringBuilder out, String element, List<String> names) {
        if (names == null || names.stream().allMatch(AdesPsv::blank)) return;
        out.append("# ").append(element).append('\n');
        for (String name : names) if (!blank(name)) out.append("! name ").append(name.trim()).append('\n');
    }

    private static List<String> texts(Context c) {
        var texts = new ArrayList<String>();
        for (String s : new String[]{c.stationCode(), c.submitter(), c.telescopeDesign(), c.detector(), c.comment()}) if (s != null) texts.add(s);
        if (c.observers() != null) texts.addAll(c.observers().stream().filter(java.util.Objects::nonNull).toList());
        if (c.measurers() != null) texts.addAll(c.measurers().stream().filter(java.util.Objects::nonNull).toList());
        return texts;
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static int length(String s) { return s == null ? 0 : s.trim().length(); }

    /** RA in [0, 360) with seven decimals; a value that rounds up to 360 is written as 0. */
    static String ra(double degrees) {
        String text = String.format(Locale.US, "%.7f", degrees);
        return text.equals("360.0000000") ? "0.0000000" : text;
    }

    private static String text(String s) { return s == null ? "" : s.trim(); }

    /**
     * A tracklet ID for an unidentified object of a session: "S", the last digit of the year and the UTC month and
     * day of the session's first frame, and a two-character sequence (0-9, A-Z), so IDs stay unique within a night's
     * submission and say when the object was seen.
     */
    public static String trkSub(long sessionStartMillis, int sequence) {
        if (sequence < 0 || sequence >= 36 * 36) throw new IllegalArgumentException("sequence " + sequence);
        String date = DateTimeFormatter.ofPattern("yyMMdd", Locale.US).withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(sessionStartMillis));
        String digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        return "S" + date.substring(1) + digits.charAt(sequence / 36) + digits.charAt(sequence % 36);
    }
}
