package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.solarsystem.SolarSystemClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * "Identify all", as MPChecker does it: the known objects near a finding at one time, from a field search without a
 * magnitude limit. A known object is near when it lies within {@value #RADIUS_ARCSEC}″ of the finding's place at that
 * time; its motion agrees when its rate differs by at most the larger of {@value #MIN_RATE_TOLERANCE}″/h and
 * {@value #RATE_FRACTION} of its speed. This is looser than the report's identification, which follows every frame
 * within the ephemeris uncertainty: an object near with agreeing motion is a possible identification to check, for
 * example of a poorly known orbit, not an identity.
 */
public final class NearbyKnown {
    /** Search radius around the finding, arcseconds (MPChecker's usual one arcminute). */
    public static final double RADIUS_ARCSEC = 60;
    /** Rate tolerance: an absolute floor in arcseconds per hour and a fraction of the known object's speed. */
    public static final double MIN_RATE_TOLERANCE = 5, RATE_FRACTION = 0.25;

    /** A finding's place and motion at one time: degrees, and arcseconds per hour (RA scaled by cos Dec). */
    public record Place(double ra, double dec, double raRateArcsecPerHour, double decRateArcsecPerHour) { }

    /**
     * A known object near the finding: its name and magnitude, its distance, its rate difference, whether the motion
     * agrees, and the other finding that lies nearer to the same object (null when none does), which makes this
     * finding more likely a piece of that one's track.
     */
    public record Match(String name, Double magnitude, double offsetArcsec, double rateDifferenceArcsecPerHour, boolean motionAgrees,
                        String nearerFinding, Double nearerOffsetArcsec) {
        Match withNearer(String finding, double offset) {
            return new Match(name, magnitude, offsetArcsec, rateDifferenceArcsecPerHour, motionAgrees, finding, offset);
        }
    }

    private NearbyKnown() { }

    /**
     * The finding's place at {@code epochMillis}: from its fitted motion (a faint mover), else from a straight-line
     * fit of its measured positions; null when neither gives one or the plate solution has no answer.
     */
    public static Place place(List<LightCurve.Sample> samples, ReportedObjects.Motion motion, long epochMillis, WcsCoordinateTransformer wcs) {
        if (wcs == null) return null;
        double x, y, vx, vy;
        if (motion != null && Double.isFinite(motion.x()) && Double.isFinite(motion.vxPerMinute())) {
            double minutes = (epochMillis - motion.referenceMillis()) / 60000.0;
            vx = motion.vxPerMinute();
            vy = motion.vyPerMinute();
            x = motion.x() + vx * minutes;
            y = motion.y() + vy * minutes;
        } else {
            if (samples.size() < 2) return null;
            int n = samples.size();
            double mt = 0, mx = 0, my = 0;
            for (var s : samples) { mt += (s.midpointMillis() - epochMillis) / 60000.0; mx += s.x(); my += s.y(); }
            mt /= n; mx /= n; my /= n;
            double stt = 0, stx = 0, sty = 0;
            for (var s : samples) {
                double t = (s.midpointMillis() - epochMillis) / 60000.0 - mt;
                stt += t * t; stx += t * (s.x() - mx); sty += t * (s.y() - my);
            }
            if (stt <= 0) return null;
            vx = stx / stt;
            vy = sty / stt;
            x = mx - vx * mt;
            y = my - vy * mt;
        }
        var here = wcs.pixelToSky(x, y);
        var hourLater = wcs.pixelToSky(x + 60 * vx, y + 60 * vy);
        if (here == null || hourLater == null) return null;
        double cos = Math.cos(Math.toRadians(here.getDecDegrees()));
        double dRa = hourLater.getRaDegrees() - here.getRaDegrees();
        if (dRa > 180) dRa -= 360;
        if (dRa < -180) dRa += 360;
        return new Place(here.getRaDegrees(), here.getDecDegrees(), dRa * cos * 3600, (hourLater.getDecDegrees() - here.getDecDegrees()) * 3600);
    }

    /** The known objects within {@value #RADIUS_ARCSEC}″ of the place, nearest first. */
    public static List<Match> near(Place place, List<SolarSystemClient.Known> known) {
        var matches = new ArrayList<Match>();
        if (place == null) return matches;
        double cos = Math.cos(Math.toRadians(place.dec()));
        for (var k : known) {
            double dRa = k.ra() - place.ra();
            if (dRa > 180) dRa -= 360;
            if (dRa < -180) dRa += 360;
            double offset = Math.hypot(dRa * cos, k.dec() - place.dec()) * 3600;
            if (offset > RADIUS_ARCSEC) continue;
            double difference = Math.hypot(k.raRateArcsecPerHour() - place.raRateArcsecPerHour(), k.decRateArcsecPerHour() - place.decRateArcsecPerHour());
            double tolerance = Math.max(MIN_RATE_TOLERANCE, RATE_FRACTION * Math.hypot(k.raRateArcsecPerHour(), k.decRateArcsecPerHour()));
            matches.add(new Match(k.name(), k.magnitude(), offset, difference, difference <= tolerance, null, null));
        }
        matches.sort(Comparator.comparingDouble(Match::offsetArcsec));
        return matches;
    }

    /**
     * Notes, for each finding's matches, another finding that lies nearer to the same known object: two findings near
     * one object are often two pieces of its track. The map is by finding, in its order.
     */
    public static java.util.Map<String, List<Match>> noteNearer(java.util.Map<String, List<Match>> byFinding) {
        var nearest = new java.util.HashMap<String, java.util.Map.Entry<String, Double>>();
        for (var entry : byFinding.entrySet()) {
            for (Match m : entry.getValue()) {
                var best = nearest.get(m.name());
                if (best == null || m.offsetArcsec() < best.getValue()) nearest.put(m.name(), java.util.Map.entry(entry.getKey(), m.offsetArcsec()));
            }
        }
        var noted = new java.util.LinkedHashMap<String, List<Match>>();
        for (var entry : byFinding.entrySet()) {
            var list = new ArrayList<Match>();
            for (Match m : entry.getValue()) {
                var best = nearest.get(m.name());
                list.add(best == null || best.getKey().equals(entry.getKey()) ? m : m.withNearer(best.getKey(), best.getValue()));
            }
            noted.put(entry.getKey(), List.copyOf(list));
        }
        return noted;
    }
}
