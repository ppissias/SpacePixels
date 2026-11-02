package eu.startales.spacepixels.report.lookup;

import com.google.gson.*;
import eu.startales.spacepixels.report.DetectionReportAstrometry;
import eu.startales.spacepixels.util.SkyMath;

import java.net.HttpURLConnection;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/**
 * Bounded candidate discovery and NORAD-verified follow-up using only a report's immutable observations.
 */
public final class SatCheckerTrackLookup {
    private static final int MAX_EXPOSURES = 16, MAX_CANDIDATES = 12, MAX_REQUESTS = 48;
    private static final int MAX_SAMPLES = 24000, MAX_RAW_CHARS = 12 * 1024 * 1024;

    /**
     * Owns cancellation and the currently active HTTP connection; one control belongs to one comparison.
     */
    public static final class Control {
        private volatile boolean cancelled;
        private volatile boolean timedOut;
        private volatile HttpURLConnection connection;
        private final long deadline = System.nanoTime() + 300_000_000_000L;

        /**
         * Disconnects the current request and prevents every subsequent provider request.
         */
        public synchronized void cancel() {
            cancelled = true;
            if (connection != null) connection.disconnect();
        }

        /**
         * Ends the time budget while allowing already completed evidence to be returned as partial.
         */
        synchronized void expire() {
            timedOut = true;
            if (connection != null) connection.disconnect();
        }

        /**
         * Whether the overall time budget, rather than an explicit cancellation, stopped the run.
         */
        boolean isTimedOut() {
            return timedOut || System.nanoTime() > deadline;
        }

        /**
         * Explicit cancellation must not publish even an otherwise completed comparison.
         */
        void checkCancelled() {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new CancellationException("Satellite comparison cancelled.");
        }

        /**
         * Binds a connection before network I/O so cancellation can interrupt it.
         */
        synchronized void bind(HttpURLConnection value) {
            connection = value;
            check();
        }

        /**
         * Clears only the connection owned by the completing request.
         */
        synchronized void release(HttpURLConnection value) {
            if (connection == value) connection = null;
        }

        /**
         * Enforces interruption and the five-minute overall budget between provider operations.
         */
        void check() {
            checkCancelled();
            if (isTimedOut())
                throw new CancellationException("Satellite comparison cancelled or timed out.");
        }
    }

    private final Function<String, JsonObject> fetch;
    private final Control control;
    private final JsonArray queries = new JsonArray();
    private int requests, samples, rawChars;

    /**
     * Uses the production allowlisted HTTP client; network work is performed by the report proxy's worker.
     */
    public SatCheckerTrackLookup(Control control) {
        this.control = control;
        this.fetch = url -> ReportLookupUpstreamClient.proxyLookup("satchecker", url, control);
    }

    /**
     * Supplies a deterministic provider boundary for tests and replaying saved real responses.
     */
    public SatCheckerTrackLookup(Function<String, JsonObject> fetch, Control control) {
        this.fetch = fetch;
        this.control = control;
    }

    /**
     * Discovers over actual exposures, then compares each returned satellite by catalogue identity.
     */
    public JsonObject compare(StreakTrackObservation observation) {
        observation.validate();
        var warnings = new ArrayList<>(observation.warnings());
        checkCoordinateFrame(observation, warnings);
        var candidates = new LinkedHashMap<Integer, JsonObject>();
        boolean discoveryComplete = true;
        int discoveredExposures = 0;
        int attemptedExposures = 0;
        for (var exposure : observation.exposures()) {
            if (attemptedExposures++ >= MAX_EXPOSURES) {
                discoveryComplete = false;
                warnings.add("Candidate discovery is limited to " + MAX_EXPOSURES + " exposures.");
                break;
            }
            try {
                JsonObject response = request(discoveryUrl(observation, exposure));
                var satellites = response.getAsJsonObject("payload").getAsJsonObject("data").getAsJsonObject("satellites");
                for (var entry : satellites.entrySet()) {
                    var satellite = entry.getValue().getAsJsonObject();
                    int id = satellite.has("norad_id") ? satellite.get("norad_id").getAsInt() : -1;
                    if (id <= 0) {
                        discoveryComplete = false;
                        continue;
                    }
                    JsonObject candidate = candidates.computeIfAbsent(id, k -> {
                        var c = new JsonObject();
                        c.addProperty("noradId", k);
                        c.addProperty("displayName", string(satellite, "name", entry.getKey()));
                        c.add("discoveryOrbitalData", new JsonArray());
                        c.add("discoveryFrames", new JsonArray());
                        return c;
                    });
                    var frame = new JsonPrimitive(exposure.frame());
                    if (!candidate.getAsJsonArray("discoveryFrames").contains(frame))
                        candidate.getAsJsonArray("discoveryFrames").add(frame);
                    if (satellite.has("positions")) for (var position : satellite.getAsJsonArray("positions")) {
                        var p = position.getAsJsonObject();
                        if (p.has("angle") && !p.get("angle").isJsonNull()) {
                            double separation = p.get("angle").getAsDouble();
                            if (Double.isFinite(separation) && (!candidate.has("discoveryMinAngleDegrees")
                                    || separation < candidate.get("discoveryMinAngleDegrees").getAsDouble()))
                                candidate.addProperty("discoveryMinAngleDegrees", separation);
                        }
                        var provenance = new JsonObject();
                        provenance.addProperty("epoch", string(p, "orbital_data_epoch", string(p, "tle_epoch", null)));
                        provenance.addProperty("source", string(p, "orbital_data_source", null));
                        addEpochAge(provenance, observation);
                        if (!candidate.getAsJsonArray("discoveryOrbitalData").contains(provenance))
                            candidate.getAsJsonArray("discoveryOrbitalData").add(provenance);
                    }
                }
                discoveredExposures++;
            } catch (CancellationException e) {
                control.checkCancelled();
                if (control.isTimedOut()) {
                    discoveryComplete = false;
                    warnings.add("The five-minute time budget ended candidate discovery. Completed evidence is retained.");
                    break;
                }
                throw e;
            } catch (RuntimeException e) {
                discoveryComplete = false;
                warnings.add("Frame " + exposure.frame() + " candidate search failed: " + e.getMessage());
            }
        }
        var comparisons = new JsonArray();
        int count = 0;
        var orderedCandidates = new ArrayList<>(candidates.entrySet());
        orderedCandidates.sort(Comparator.<Map.Entry<Integer, JsonObject>>comparingInt(e -> -e.getValue().getAsJsonArray("discoveryFrames").size())
                .thenComparingDouble(e -> e.getValue().has("discoveryMinAngleDegrees") ? e.getValue().get("discoveryMinAngleDegrees").getAsDouble() : Double.POSITIVE_INFINITY));
        for (var entry : orderedCandidates) {
            if (control.isTimedOut()) {
                warnings.add("The five-minute time budget ended follow-up; not every returned candidate was compared.");
                break;
            }
            if (count++ >= MAX_CANDIDATES) {
                warnings.add("Follow-up is limited to " + MAX_CANDIDATES + " of " + candidates.size() + " returned candidates.");
                break;
            }
            JsonObject candidate = entry.getValue();
            try {
                double step = 1;
                List<SatelliteTrackComparison.Sample> path = predictions(observation, entry.getKey(), step);
                JsonObject comparison = SatelliteTrackComparison.compare(observation, path, step, control::check);
                double scale = observation.exposures().stream().flatMap(e -> e.fragments().stream()).mapToDouble(StreakTrackObservation.Fragment::pixelScaleArcsec).min().orElse(1);
                if (comparison.get("estimatedInterpolationErrorArcsec").getAsDouble() > scale * .25 || maxStepAngle(path) > .25) {
                    step = .125;
                    path = predictions(observation, entry.getKey(), step);
                    comparison = SatelliteTrackComparison.compare(observation, path, step, control::check);
                }
                boolean samplingAdequate = path.size() >= 2 && comparison.get("estimatedInterpolationErrorArcsec").getAsDouble() <= scale * .25 && maxStepAngle(path) <= .25;
                comparison.addProperty("samplingAdequate", samplingAdequate);
                if (!samplingAdequate)
                    comparison.addProperty("unavailableReason", "Prediction curvature exceeds the bounded sampling resolution; distances are provisional.");
                candidate.add("comparison", comparison);
                var provenance = new JsonArray();
                for (var sample : path) {
                    var p = new JsonObject();
                    p.addProperty("epoch", sample.epoch());
                    p.addProperty("source", sample.source());
                    addEpochAge(p, observation);
                    if (!provenance.contains(p)) provenance.add(p);
                }
                candidate.add("predictionOrbitalData", provenance);
                candidate.addProperty("orbitalDataChanged", orbitalEpochChanged(candidate.getAsJsonArray("discoveryOrbitalData"), provenance));
            } catch (CancellationException e) {
                control.checkCancelled();
                if (control.isTimedOut()) {
                    candidate.addProperty("unavailableReason", "The five-minute time budget ended this trajectory request.");
                    comparisons.add(candidate);
                    warnings.add("The five-minute time budget ended follow-up; completed comparisons are retained.");
                    break;
                }
                throw e;
            } catch (RuntimeException e) {
                candidate.addProperty("unavailableReason", e.getMessage());
            }
            comparisons.add(candidate);
        }
        var sorted = new ArrayList<JsonElement>();
        comparisons.forEach(sorted::add);
        sorted.sort(Comparator.<JsonElement>comparingInt(c -> comparisonQuality(c.getAsJsonObject()))
                .thenComparingDouble(c -> distance(c.getAsJsonObject())));
        comparisons = new JsonArray();
        for (var candidate : sorted) comparisons.add(candidate);
        var normalized = new JsonObject();
        normalized.addProperty("trackComparison", true);
        normalized.addProperty("comparisonVersion", 1);
        normalized.addProperty("discoveryComplete", discoveryComplete);
        normalized.addProperty("searchedExposures", discoveredExposures);
        normalized.addProperty("totalExposures", observation.exposures().size());
        int unavailableExposures = observation.astrometry().get("unavailableExposureCount") instanceof Number n
                ? Math.clamp(n.intValue(), 0, 256) : 0;
        normalized.addProperty("unavailableExposureCount", unavailableExposures);
        normalized.addProperty("totalReturnedCandidates", candidates.size());
        normalized.addProperty("followUpComplete", unavailableExposures == 0 && comparisons.size() == candidates.size()
                && sorted.stream().allMatch(c -> c.getAsJsonObject().has("comparison")
                && c.getAsJsonObject().getAsJsonObject("comparison").get("completeCoverage").getAsBoolean()
                && c.getAsJsonObject().getAsJsonObject("comparison").get("samplingAdequate").getAsBoolean()));
        normalized.add("candidates", comparisons);
        normalized.add("warnings", new Gson().toJsonTree(warnings));
        var response = new JsonObject();
        response.addProperty("ok", true);
        response.addProperty("provider", "satchecker");
        response.addProperty("observationDigest", observation.digest());
        response.addProperty("fetchedAtUtc", Instant.now().toString());
        response.addProperty("cacheSource", "live");
        response.add("normalized", normalized);
        var payload = new JsonObject();
        payload.add("observation", new Gson().toJsonTree(observation));
        payload.add("queries", queries);
        response.add("payload", payload);
        return response;
    }

    /**
     * Rejects explicitly incompatible frames; unresolved declarations remain visible coordinate assumptions.
     */
    private static void checkCoordinateFrame(StreakTrackObservation observation, List<String> warnings) {
        Object raw = observation.astrometry().get("coordinateMetadata");
        Map<?, ?> metadata = raw instanceof Map<?, ?> m ? m : Map.of();
        String system = Objects.toString(metadata.get("RADESYS"), Objects.toString(metadata.get("RADECSYS"), "")).replace("'", "").trim().toUpperCase(Locale.ROOT);
        String equinox = Objects.toString(metadata.get("EQUINOX"), "").replace("'", "").trim();
        if (!system.isEmpty() && !system.equals("ICRS") && !system.equals("FK5"))
            throw new IllegalArgumentException("Unsupported WCS reference frame: " + system + ".");
        if (system.isEmpty() && !equinox.isEmpty()) {
            try {
                double epoch = Double.parseDouble(equinox);
                if (epoch >= 1800 && epoch <= 2200 && Math.abs(epoch - 2000) > .01)
                    throw new IllegalArgumentException("The WCS equinox requires a reference-frame transformation before comparison.");
            } catch (
                    NumberFormatException ignored) { /* The original declaration is retained and warned about below. */ }
        }
        if (system.equals("FK5")) {
            try {
                if (Math.abs(Double.parseDouble(equinox) - 2000) > .01)
                    throw new IllegalArgumentException("FK5 coordinates require a J2000 equinox for this comparison.");
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("FK5 coordinates have no usable J2000 equinox.");
            }
            warnings.add("FK5 J2000 coordinates are compared with ICRF-oriented predictions without a subarcsecond frame-bias correction.");
        }
        if (system.isEmpty())
            warnings.add("WCS reference frame is undeclared; coordinates are provisionally compared in ICRS/J2000 orientation. No automatic identity verdict is assigned.");
        if (!equinox.isEmpty()) {
            try {
                double epoch = Double.parseDouble(equinox);
                if (!Double.isFinite(epoch) || epoch < 1800 || epoch > 2200)
                    warnings.add("The saved EQUINOX declaration is implausible; TAN/SIP geometry did not use that card.");
            } catch (NumberFormatException e) {
                warnings.add("The saved EQUINOX declaration is unreadable.");
            }
        }
    }

    /**
     * Follows a NORAD identity over the complete span, or over individual exposures when gaps would waste samples.
     */
    private List<SatelliteTrackComparison.Sample> predictions(StreakTrackObservation o, int id, double step) {
        var exposures = o.exposures();
        double start = exposures.getFirst().startJd(), end = exposures.getLast().endJd();
        double observed = exposures.stream().mapToDouble(e -> e.endJd() - e.startJd()).sum();
        var intervals = new ArrayList<double[]>();
        if ((end - start) * 86400 <= 1800 && end - start <= 3 * observed) intervals.add(new double[]{start, end});
        else for (var e : exposures.stream().limit(MAX_EXPOSURES).toList())
            intervals.add(new double[]{e.startJd(), e.endJd()});
        var points = new TreeMap<Double, SatelliteTrackComparison.Sample>();
        var chunks = new ArrayList<double[]>();
        for (var interval : intervals) {
            double from = interval[0] - 2.0 / 86400;
            double stop = interval[1] + 2.0 / 86400;
            while (from < stop) {
                // The live endpoint limits a response to 1,000 rows. Leave room for its inclusive end convention.
                double to = Math.min(stop, from + 900 * step / 86400);
                chunks.add(new double[]{from, to});
                if (to >= stop) break;
                from = to - step / 86400;
            }
        }
        for (var interval : chunks) {
            if ((interval[1] - interval[0]) * 86400 / step + 2 > MAX_SAMPLES - samples)
                throw new IllegalArgumentException("Prediction sample budget exceeded.");
            var site = o.observer();
            String url = String.format(Locale.ROOT, "https://satchecker.cps.iau.org/ephemeris/catalog-number-jdstep/?catalog=%d&latitude=%.8f&longitude=%.8f&elevation=%.2f&startjd=%.10f&stopjd=%.10f&stepjd=%.12f&min_altitude=-90",
                    id, site.latitude(), site.longitude(), site.elevationMeters() == null ? 0 : site.elevationMeters(), interval[0], interval[1], step / 86400);
            var response = request(url);
            var root = response.getAsJsonObject("payload");
            var fields = root.getAsJsonArray("fields");
            var columns = new HashMap<String, Integer>();
            for (int i = 0; i < fields.size(); i++) columns.put(fields.get(i).getAsString(), i);
            for (var value : root.getAsJsonArray("data")) {
                var row = value.getAsJsonArray();
                if (cell(row, columns, "catalog_id").getAsInt() != id)
                    throw new IllegalArgumentException("Provider returned a different NORAD identity.");
                double jd = cell(row, columns, "julian_date").getAsDouble(), ra = cell(row, columns, "right_ascension_deg").getAsDouble(), dec = cell(row, columns, "declination_deg").getAsDouble();
                if (!Double.isFinite(jd) || !Double.isFinite(ra) || !Double.isFinite(dec) || Math.abs(dec) > 90)
                    throw new IllegalArgumentException("Provider returned nonfinite coordinates.");
                String epoch = optionalCell(row, columns, "orbital_data_epoch", optionalCell(row, columns, "tle_epoch", null));
                String source = optionalCell(row, columns, "orbital_data_source", optionalCell(row, columns, "data_source", null));
                points.put(jd, new SatelliteTrackComparison.Sample(jd, SkyMath.normalizeDegrees(ra), dec, epoch, source));
                if (++samples > MAX_SAMPLES) throw new IllegalArgumentException("Prediction sample budget exceeded.");
            }
        }
        return List.copyOf(points.values());
    }

    /**
     * Executes an allowlisted request and retains its original response, including unsuccessful requests.
     */
    private JsonObject request(String url) {
        control.check();
        if (++requests > MAX_REQUESTS) throw new IllegalArgumentException("Provider request budget exceeded.");
        ReportLookupUpstreamClient.validateTarget("satchecker", url);
        var response = fetch.apply(url);
        rawChars += response.toString().length();
        if (rawChars > MAX_RAW_CHARS) throw new IllegalArgumentException("Saved provider response budget exceeded.");
        queries.add(response);
        control.check();
        if (!response.has("ok") || !response.get("ok").getAsBoolean())
            throw new IllegalArgumentException(string(response, "message", "Provider request failed."));
        return response;
    }

    /**
     * Builds a padded local FOV around the fragments in one actual exposure, including its complete duration.
     */
    private static String discoveryUrl(StreakTrackObservation o, StreakTrackObservation.Exposure e) {
        var p = e.fragments().getFirst();
        double radius = .25;
        for (var f : e.fragments())
            radius = Math.max(radius, SkyMath.separationDegrees(p.ra(), p.dec(), f.ra(), f.dec()) + .2);
        if (radius > 5)
            throw new IllegalArgumentException("This exposure needs a search radius above the five-degree comparison limit.");
        var site = o.observer();
        return SatCheckerUrlBuilder.buildFovUrl(new DetectionReportAstrometry.ObserverSite(site.latitude(), site.longitude(), site.elevationMeters() == null ? 0 : site.elevationMeters(), site.source()),
                (long) ((((e.startJd() + e.endJd()) / 2) - 2440587.5) * 86400000), (long) Math.ceil((e.endJd() - e.startJd()) * 86400) + 4, p.ra(), p.dec(), radius, false);
    }

    /**
     * Sampling guard for extreme angular motion even when a path happens to be locally straight.
     */
    private static double maxStepAngle(List<SatelliteTrackComparison.Sample> path) {
        double max = 0;
        for (int i = 1; i < path.size(); i++)
            if ((path.get(i).jd() - path.get(i - 1).jd()) * 86400 < 2)
                max = Math.max(max, SkyMath.separationDegrees(path.get(i - 1).ra(), path.get(i - 1).dec(), path.get(i).ra(), path.get(i).dec()));
        return max;
    }

    /**
     * Ranks numeric evidence only; a low residual is not an identity verdict.
     */
    private static double distance(JsonObject c) {
        return c.has("comparison") && c.getAsJsonObject("comparison").has("medianDistanceArcsec") ? c.getAsJsonObject("comparison").get("medianDistanceArcsec").getAsDouble() : Double.POSITIVE_INFINITY;
    }

    /**
     * Incomplete or unresolved interpolation must not outrank an adequately sampled complete comparison.
     */
    private static int comparisonQuality(JsonObject candidate) {
        if (!candidate.has("comparison")) return 3;
        var comparison = candidate.getAsJsonObject("comparison");
        if (!comparison.get("samplingAdequate").getAsBoolean() || comparison.get("coveredExposures").getAsInt() < 2)
            return 2;
        return comparison.get("completeCoverage").getAsBoolean() ? 0 : 1;
    }

    /**
     * Compares like-for-like epochs; FOV orbital_data_source may mean "tle", not the ephemeris data provider.
     */
    private static boolean orbitalEpochChanged(JsonArray discovery, JsonArray prediction) {
        var before = new TreeSet<String>();
        var after = new TreeSet<String>();
        for (var value : discovery) {
            var p = value.getAsJsonObject();
            if (p.has("epoch") && !p.get("epoch").isJsonNull()) before.add(p.get("epoch").getAsString());
        }
        for (var value : prediction) {
            var p = value.getAsJsonObject();
            if (p.has("epoch") && !p.get("epoch").isJsonNull()) after.add(p.get("epoch").getAsString());
        }
        return !before.isEmpty() && !after.isEmpty() && !before.equals(after);
    }

    /**
     * Signed orbital-epoch age relative to the track midpoint; age is context, never an angular-error estimate.
     */
    private static void addEpochAge(JsonObject provenance, StreakTrackObservation observation) {
        if (!provenance.has("epoch") || provenance.get("epoch").isJsonNull()) return;
        try {
            String epoch = provenance.get("epoch").getAsString().replace(" UTC", "Z").replace(" ", "T");
            long millis = Instant.parse(epoch).toEpochMilli();
            double midpoint = (observation.exposures().getFirst().startJd() + observation.exposures().getLast().endJd()) / 2;
            provenance.addProperty("epochAgeDays", midpoint - StreakTrackObservation.julianDate(millis));
        } catch (
                java.time.format.DateTimeParseException ignored) { /* Preserve unrecognized provider text verbatim. */ }
    }

    /**
     * Required tabular field with a meaningful response-version error.
     */
    private static JsonElement cell(JsonArray row, Map<String, Integer> columns, String key) {
        Integer i = columns.get(key);
        if (i == null || i >= row.size() || row.get(i).isJsonNull())
            throw new IllegalArgumentException("Ephemeris response lacks " + key + ".");
        return row.get(i);
    }

    /**
     * Reads optional legacy/current orbital provenance.
     */
    private static String optionalCell(JsonArray row, Map<String, Integer> columns, String key, String fallback) {
        Integer i = columns.get(key);
        return i == null || i >= row.size() || row.get(i).isJsonNull() ? fallback : row.get(i).getAsString();
    }

    /**
     * Optional provider text.
     */
    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }
}
