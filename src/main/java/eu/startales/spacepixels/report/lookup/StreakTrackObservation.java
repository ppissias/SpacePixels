package eu.startales.spacepixels.report.lookup;

import com.google.gson.Gson;
import eu.startales.spacepixels.report.DetectionReportAstrometry;
import eu.startales.spacepixels.util.SkyMath;
import io.github.ppissias.jtransient.model.Track;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Immutable report-time measurements. Coordinates are degrees; times are UTC Julian dates; frames are one-based.
 */
public record StreakTrackObservation(int version, String findingId, Observer observer,
                                     Map<String, Object> astrometry, List<Exposure> exposures, List<String> warnings) {
    /**
     * Captured topocentric location; an unknown height remains explicit rather than becoming measured zero.
     */
    public record Observer(double latitude, double longitude, Double elevationMeters, String source) {
    }

    /**
     * One independent exposure and its fragments, not independent observations for each fragment.
     */
    public record Exposure(int frame, double startJd, double endJd, List<Fragment> fragments) {
        /**
         * Copies the fragment collection so later engine or caller mutations cannot change an exposure.
         */
        public Exposure {
            fragments = List.copyOf(fragments);
        }
    }

    /**
     * A centroid and the endpoints of its principal footprint axis, when that axis is measurable.
     */
    public record Fragment(double x, double y, double ra, double dec, double pixelScaleArcsec,
                           Double axisStartRa, Double axisStartDec, Double axisEndRa, Double axisEndDec) {
    }

    /**
     * Owns detached collections and sorts exposures chronologically for both native exports and reopened JSON.
     */
    public StreakTrackObservation {
        astrometry = freeze(astrometry);
        var ordered = new ArrayList<>(exposures);
        ordered.sort(Comparator.comparingDouble(Exposure::startJd));
        exposures = List.copyOf(ordered);
        warnings = List.copyOf(warnings);
    }

    /**
     * Copies the coordinates used by this export, including the applied astrometric correction. No files are read.
     */
    public static StreakTrackObservation capture(String id, DetectionReportAstrometry.Context context, Track track) {
        var warnings = new ArrayList<String>();
        var wcs = context == null ? null : context.getTransformer();
        var site = context == null ? null : context.getObserverSite();
        Observer observer = site == null ? null : new Observer(site.latitudeDeg, SkyMath.normalizeSignedDegrees(site.longitudeDeg),
                Double.isFinite(site.altitudeMeters) ? site.altitudeMeters : null, site.sourceLabel);
        var provenance = new LinkedHashMap<String, Object>();
        if (wcs != null) {
            provenance.put("nominalWcs", wcs.nominalHeaderCards());
            provenance.put("correctionApplied", wcs.isCorrected());
            provenance.put("source", context.getWcsSummary());
            provenance.put("coordinateMetadata", context.getWcsCoordinateMetadata());
        } else warnings.add("The report has no usable sky-coordinate solution.");
        if (observer == null) warnings.add("The report has no observing location.");
        else if (observer.elevationMeters == null)
            warnings.add("Observer height is unknown; predictions use 0 m above the WGS84 ellipsoid.");
        warnings.add("UTC Julian dates approximate the provider's documented UT1 input (up to 0.9 s difference); no clock offset is fitted.");
        warnings.add("Absolute WCS and orbit uncertainties are not calibrated; distances are measurements, not identification probabilities.");
        var grouped = new TreeMap<Integer, List<io.github.ppissias.jtransient.model.DetectedObject>>();
        if (track != null && track.points != null) for (var point : track.points) {
            if (point != null) grouped.computeIfAbsent(point.sourceFrameIndex, k -> new ArrayList<>()).add(point);
        }
        var exposures = new ArrayList<Exposure>();
        for (var entry : grouped.entrySet()) {
            int index = entry.getKey();
            var points = entry.getValue();
            // Header timing is authoritative for the independent exposure, not fragment ordering.
            long start = context == null ? -1 : context.getFrameTimestampMillis(index);
            long duration = context == null ? 0 : context.getFrameExposureMillis(index);
            if (start <= 0) start = points.getFirst().timestamp;
            if (duration <= 0) duration = points.getFirst().exposureDuration;
            if (start <= 0 || duration <= 0 || start > Long.MAX_VALUE - duration) {
                warnings.add("Frame " + (index + 1) + " has unavailable exposure timing.");
                continue;
            }
            String timeSystem = context == null ? null : context.getFrameTimeSystem(index);
            if (timeSystem != null && !timeSystem.equalsIgnoreCase("UTC")) {
                warnings.add("Frame " + (index + 1) + " has unsupported TIMESYS=" + timeSystem + ".");
                continue;
            }
            var fragments = new ArrayList<Fragment>();
            if (wcs != null) for (var point : points) {
                var sky = wcs.pixelToSky(point.x, point.y);
                var next = wcs.pixelToSky(point.x + 1, point.y);
                if (!validSky(sky.getRaDegrees(), sky.getDecDegrees())) continue;
                double scale = SkyMath.separationDegrees(sky.getRaDegrees(), sky.getDecDegrees(),
                        next.getRaDegrees(), next.getDecDegrees()) * 3600;
                if (!Double.isFinite(scale) || scale <= 0) continue;
                Double ar = null, ad = null, br = null, bd = null;
                if (point.elongation >= 3 && point.majorExtent >= 5 && Double.isFinite(point.majorExtent) && Double.isFinite(point.angle)) {
                    double dx = Math.cos(point.angle) * point.majorExtent / 2, dy = Math.sin(point.angle) * point.majorExtent / 2;
                    var a = wcs.pixelToSky(point.x - dx, point.y - dy);
                    var b = wcs.pixelToSky(point.x + dx, point.y + dy);
                    ar = a.getRaDegrees();
                    ad = a.getDecDegrees();
                    br = b.getRaDegrees();
                    bd = b.getDecDegrees();
                }
                fragments.add(new Fragment(point.x, point.y, sky.getRaDegrees(), sky.getDecDegrees(), scale, ar, ad, br, bd));
            }
            if (!fragments.isEmpty())
                exposures.add(new Exposure(index + 1, julianDate(start), julianDate(start + duration), fragments));
        }
        exposures.sort(Comparator.comparingDouble(Exposure::startJd));
        provenance.put("unavailableExposureCount", grouped.size() - exposures.size());
        return new StreakTrackObservation(1, id, observer, provenance, exposures, warnings);
    }

    /**
     * Rejects unbounded or malformed browser input before any provider request.
     */
    public void validate() {
        if (version != 1 || findingId == null || !findingId.matches("ST[1-9][0-9]*") || observer == null
                || !Double.isFinite(observer.latitude) || Math.abs(observer.latitude) > 90
                || !Double.isFinite(observer.longitude) || Math.abs(observer.longitude) > 180
                || observer.elevationMeters != null && (!Double.isFinite(observer.elevationMeters) || Math.abs(observer.elevationMeters) > 20000)
                || exposures.size() < 2 || exposures.size() > 256)
            throw new IllegalArgumentException("A comparison needs a captured observing location and at least two timed exposures.");
        int count = 0;
        var frames = new HashSet<Integer>();
        for (var exposure : exposures) {
            if (exposure.frame <= 0 || !frames.add(exposure.frame) || !Double.isFinite(exposure.startJd)
                    || !Double.isFinite(exposure.endJd) || exposure.startJd < 2440000 || exposure.endJd > 2500000
                    || exposure.endJd <= exposure.startJd || (exposure.endJd - exposure.startJd) * 86400 > 3600
                    || exposure.fragments.isEmpty()) throw new IllegalArgumentException("Invalid captured exposure.");
            for (var fragment : exposure.fragments) {
                if (!validSky(fragment.ra, fragment.dec) || !Double.isFinite(fragment.pixelScaleArcsec)
                        || fragment.pixelScaleArcsec <= 0 || !Double.isFinite(fragment.x) || !Double.isFinite(fragment.y))
                    throw new IllegalArgumentException("Invalid captured fragment coordinates.");
                if (fragment.axisStartRa != null || fragment.axisStartDec != null || fragment.axisEndRa != null || fragment.axisEndDec != null) {
                    if (fragment.axisStartRa == null || fragment.axisStartDec == null || fragment.axisEndRa == null || fragment.axisEndDec == null
                            || !validSky(fragment.axisStartRa, fragment.axisStartDec) || !validSky(fragment.axisEndRa, fragment.axisEndDec))
                        throw new IllegalArgumentException("Invalid captured fragment axis.");
                }
                count++;
            }
        }
        if (count > 10000 || (exposures.getLast().endJd - exposures.getFirst().startJd) * 86400 > 86400)
            throw new IllegalArgumentException("The captured track exceeds the comparison limits.");
    }

    /**
     * Content identity binds cached predictions to this report's measurements and observing context.
     */
    public String digest() {
        var gson = new Gson();
        String canonical = gson.toJson(canonical(gson.toJsonTree(this)));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * JSON object order and equivalent number spellings must not invalidate an offline comparison.
     */
    private static com.google.gson.JsonElement canonical(com.google.gson.JsonElement value) {
        if (value.isJsonObject()) {
            var result = new com.google.gson.JsonObject();
            for (String key : new TreeSet<>(value.getAsJsonObject().keySet()))
                result.add(key, canonical(value.getAsJsonObject().get(key)));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new com.google.gson.JsonArray();
            value.getAsJsonArray().forEach(item -> result.add(canonical(item)));
            return result;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())
            return new com.google.gson.JsonPrimitive(value.getAsBigDecimal().stripTrailingZeros());
        return value;
    }

    /**
     * Embeds the same captured evidence written to report-data.json; old reports keep their original lookup path.
     */
    public String html(String slotId, String sidecar) {
        String problem = null;
        try {
            validate();
        } catch (IllegalArgumentException e) {
            problem = e.getMessage();
        }
        if (observer == null) problem = "The observing location was unavailable when this report was exported.";
        else if (exposures.size() < 2) problem = "Fewer than two exposures have usable sky coordinates and timing. "
                + String.join(" ", warnings.stream().filter(w -> w.startsWith("Frame ") || w.contains("no usable sky")).toList());
        if (problem != null) return "<p class='astro-note'>Satellite motion comparison unavailable: "
                + DetectionReportAstrometry.escapeHtml(problem) + "</p>";
        var first = exposures.getFirst();
        var point = first.fragments.getFirst();
        var site = new DetectionReportAstrometry.ObserverSite(observer.latitude, observer.longitude,
                observer.elevationMeters == null ? 0 : observer.elevationMeters, observer.source);
        String url = SatCheckerUrlBuilder.buildFovUrl(site,
                (long) (((first.startJd + first.endJd) / 2 - 2440587.5) * 86400000), 15, point.ra, point.dec, 1.5, false);
        String evidenceId = "satellite-observation-" + findingId.toLowerCase(Locale.ROOT);
        String button = DetectionReportAstrometry.buildLiveRenderButtonHtml("satchecker", url, slotId,
                        "Compare track with satellites", sidecar + "_comparison.json", "Satellite motion comparison")
                .replace("data-provider=", "data-observation-id='" + evidenceId + "' data-observation-digest='" + digest() + "' data-provider=");
        return "<div class='id-links'>" + button + "<a class='id-link' target='_blank' rel='noopener noreferrer' href='"
                + DetectionReportAstrometry.escapeHtml(url) + "'>Open midpoint candidate search (raw JSON)</a></div>"
                + "<p class='astro-note'>Compares saved streak measurements with predicted paths during each exposure. "
                + "Direction, speed and distances support an association; they do not confirm identity. "
                + "Keep SpacePixels running for a new comparison; saved results reopen offline.</p>"
                + "<script type='application/json' id='" + evidenceId + "'>" + new Gson().toJson(this) + "</script>"
                + DetectionReportAstrometry.buildLiveRenderContainerHtml(slotId);
    }

    /**
     * Converts the application's UTC epoch milliseconds without changing the captured clock.
     */
    static double julianDate(long millis) {
        return millis / 86400000.0 + 2440587.5;
    }

    /**
     * Checks finite celestial coordinates without accepting latitude wrapping.
     */
    private static boolean validSky(double ra, double dec) {
        return Double.isFinite(ra) && Double.isFinite(dec) && ra >= 0 && ra < 360 && Math.abs(dec) <= 90;
    }

    /**
     * Deeply freezes JSON-compatible provenance while retaining the published key order used by the digest.
     */
    private static Map<String, Object> freeze(Map<String, ?> input) {
        var result = new LinkedHashMap<String, Object>();
        for (var entry : input.entrySet()) {
            result.put(entry.getKey(), freezeValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Detaches all JSON-compatible provenance values, including nested lists and mutable Number subclasses.
     */
    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            var nested = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> nested.put(key.toString(), item));
            return freeze(nested);
        }
        if (value instanceof List<?> list) return list.stream().map(StreakTrackObservation::freezeValue).toList();
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString());
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        throw new IllegalArgumentException("Observation provenance must contain JSON values.");
    }
}
