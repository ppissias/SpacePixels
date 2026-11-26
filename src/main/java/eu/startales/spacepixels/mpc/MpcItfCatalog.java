/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.mpc;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

/**
 * Downloads, caches and streams the Minor Planet Center's Isolated Tracklet File ({@code itf.txt.gz}) to check which
 * moving findings have already been reported to the MPC as unlinked tracklets on the same night, and which unlinked
 * tracklets on nearby nights match a linear continuation of their motion.
 *
 * <p>The MPC does not provide an unauthenticated online cone-search API for the ITF; its public bulk archive
 * ({@value #ITF_URL}, ~130 MB gzipped, updated daily) is therefore downloaded on demand, cached under the user's home
 * directory, refreshed conditionally via {@code If-Modified-Since}, and scanned sequentially through a
 * {@link GZIPInputStream} with bounded memory.
 */
public final class MpcItfCatalog {
    /** Official MPC gzipped Isolated Tracklet File URL (80-column MPC observation lines). */
    public static final String ITF_URL = "https://minorplanetcenter.net/iau/ITF/itf.txt.gz";
    /** Cache directory under the user's home folder. */
    public static final String CACHE_FOLDER = "spacepixels_mpc_itf";
    /** Cached file name inside {@link #CACHE_FOLDER}. */
    public static final String CACHE_FILE = "itf.txt.gz";
    /** Default window (days before and after each finding's epoch) for nearby-night ITF candidate search. */
    public static final int DEFAULT_NEARBY_DAYS = 15;
    /** Maximum time offset (hours) counted as a same-night match. */
    public static final double SAME_NIGHT_MAX_HOURS = 12.0;
    /** Maximum angular offset (arcseconds) for a same-night ITF match when rates agree. */
    public static final double SAME_NIGHT_RADIUS_ARCSEC = 30.0;
    /** Tighter angular offset (arcseconds) for a same-night ITF tracklet without a measurable rate. */
    public static final double SAME_NIGHT_NO_RATE_RADIUS_ARCSEC = 20.0;
    /** Base nearby-night angular tolerance (arcminutes) plus per-day growth for orbital curvature. */
    public static final double NEARBY_BASE_RADIUS_ARCMIN = 0.75;
    public static final double NEARBY_RADIUS_PER_DAY_ARCMIN = 1.2;
    /** Rate tolerances for same-night and nearby-night matches (arcsec/hour floor + fractional allowance). */
    public static final double SAME_NIGHT_RATE_FLOOR = 5.0, SAME_NIGHT_RATE_FRACTION = 0.25, SAME_NIGHT_PA_TOLERANCE_DEG = 15.0;
    public static final double NEARBY_RATE_FLOOR = 6.0, NEARBY_RATE_FRACTION = 0.30, NEARBY_PA_TOLERANCE_DEG = 25.0;
    /** Maximum number of nearby-night candidate tracklets returned per finding (nearest first). */
    public static final int MAX_NEARBY_MATCHES = 5;
    /** Minimum time span (seconds) within a tracklet to compute a meaningful angular rate. */
    private static final double MIN_TRACKLET_SPAN_SECONDS = 60.0;
    private static final long PROGRESS_LINE_INTERVAL = 65_536L;

    /** Status of the locally cached {@code itf.txt.gz} file. */
    public record CacheStatus(Path file, boolean exists, long sizeBytes, Instant lastModified) { }

    /** Outcome of a download/conditional-refresh request. */
    public record DownloadResult(Path file, boolean updated, long sizeBytes, Instant lastModified) { }

    /** Progress callback invoked during file download ({@code totalBytes} is -1 when unknown). */
    @FunctionalInterface
    public interface DownloadProgress {
        void onProgress(long bytesRead, long totalBytes);
    }

    /** One finding to check against the ITF: its mid-epoch, sky position (deg) and angular velocity (arcsec/h). */
    public record FindingQuery(String id, long epochMillis, double raDegrees, double decDegrees,
                               double raRateArcsecPerHour, double decRateArcsecPerHour) {
        public double speedArcsecPerHour() {
            return Math.hypot(raRateArcsecPerHour, decRateArcsecPerHour);
        }

        public double positionAngleDegrees() {
            double pa = Math.toDegrees(Math.atan2(raRateArcsecPerHour, decRateArcsecPerHour));
            return pa < 0 ? pa + 360.0 : pa;
        }
    }

    /** Summary of one isolated tracklet assembled from its 80-column MPC observation lines. */
    public record Tracklet(String observatoryCode, String trackletId, int observationCount,
                           long startMillis, long endMillis, long midMillis,
                           double midRaDegrees, double midDecDegrees,
                           Double speedArcsecPerHour, Double positionAngleDegrees, String magnitude) {
        /** Short display identifier, e.g. {@code W84 Iuja1KU}. */
        public String label() {
            return observatoryCode + " " + trackletId;
        }

        /** UTC calendar date of the tracklet midpoint, e.g. {@code 2022-08-24}. */
        public String midDateUtc() {
            return Instant.ofEpochMilli(midMillis).atZone(ZoneOffset.UTC).toLocalDate().toString();
        }
    }

    /** A candidate match between a SpacePixels finding and an MPC ITF tracklet. */
    public record Match(Tracklet tracklet, boolean sameNight, double deltaDays, double offsetArcsec,
                        Double speedDiffArcsecPerHour, Double paDiffDegrees) { }

    /** Same-night and nearby-night ITF matches for one finding. */
    public record FindingMatches(String findingId, List<Match> sameNight, List<Match> nearbyNights) {
        public boolean hasSameNight() { return !sameNight.isEmpty(); }
        public boolean hasNearbyNights() { return !nearbyNights.isEmpty(); }
    }

    /** Full result of scanning an ITF archive for a batch of findings. */
    public record SearchResult(Instant snapshotTime, long linesScanned, int candidateTracklets,
                               Map<String, FindingMatches> byFinding) { }

    private record RawObs(long epochMillis, double raDegrees, double decDegrees, String magnitude) { }

    private MpcItfCatalog() { }

    /** Default cached file path under {@code home}. */
    public static Path cacheFile(Path home) {
        return home.resolve(CACHE_FOLDER).resolve(CACHE_FILE);
    }

    /** Reads the local cache status without network access. */
    public static CacheStatus cacheStatus(Path home) {
        Path file = cacheFile(home);
        try {
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                if (size > 0) {
                    return new CacheStatus(file, true, size, Files.getLastModifiedTime(file).toInstant());
                }
            }
        } catch (IOException ignored) {
        }
        return new CacheStatus(file, false, 0L, null);
    }

    /**
     * Downloads or conditionally refreshes {@code itf.txt.gz} into {@link #cacheFile(Path)}.
     *
     * @param home         user home directory holding the cache folder
     * @param forceRefresh when false and a cached file exists, sends {@code If-Modified-Since} so unchanged snapshots
     *                     return immediately via HTTP 304
     * @param progress     optional byte-count progress callback
     */
    public static DownloadResult download(Path home, boolean forceRefresh, DownloadProgress progress)
            throws IOException, InterruptedException {
        return downloadFrom(URI.create(ITF_URL), cacheFile(home), forceRefresh, progress);
    }

    /** Package-visible download helper allowing tests to target a local HTTP server. */
    static DownloadResult downloadFrom(URI uri, Path destination, boolean forceRefresh, DownloadProgress progress)
            throws IOException, InterruptedException {
        Files.createDirectories(destination.getParent());
        boolean exists = Files.isRegularFile(destination) && Files.size(destination) > 0;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(15))
                .header("User-Agent", "SpacePixels")
                .GET();
        if (exists && !forceRefresh) {
            Instant mtime = Files.getLastModifiedTime(destination).toInstant();
            reqBuilder.header("If-Modified-Since", DateTimeFormatter.RFC_1123_DATE_TIME.format(mtime.atZone(ZoneOffset.UTC)));
        }
        HttpResponse<InputStream> response = client.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 304 && exists) {
            try (InputStream ignored = response.body()) {
                return new DownloadResult(destination, false, Files.size(destination), Files.getLastModifiedTime(destination).toInstant());
            }
        }
        if (status != 200) {
            try (InputStream ignored = response.body()) {
                throw new IOException("MPC ITF download failed (HTTP " + status + ").");
            }
        }
        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        Instant remoteModified = response.headers().firstValue("Last-Modified")
                .map(MpcItfCatalog::parseHttpDate).orElse(Instant.now());
        Path partial = destination.resolveSibling(destination.getFileName() + ".part");
        boolean completed = false;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(partial)) {
            byte[] buf = new byte[64 * 1024];
            long read = 0L;
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("MPC ITF download cancelled.");
                }
                out.write(buf, 0, n);
                read += n;
                if (progress != null) progress.onProgress(read, totalBytes);
            }
            if (read <= 0) {
                throw new IOException("MPC ITF download returned an empty file.");
            }
            completed = true;
        } finally {
            if (!completed) {
                Files.deleteIfExists(partial);
            }
        }
        try {
            Files.setLastModifiedTime(partial, FileTime.from(remoteModified));
        } catch (IOException ignored) {
        }
        try {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return new DownloadResult(destination, true, Files.size(destination),
                Files.getLastModifiedTime(destination).toInstant());
    }

    /**
     * Streams a gzipped MPC ITF file and matches every finding in {@code queries} against same-night and nearby-night
     * unlinked tracklets.
     */
    public static SearchResult search(Path itfGzipFile, List<FindingQuery> queries, int nearbyDays, LongConsumer lineProgress)
            throws IOException, InterruptedException {
        Instant snapshotTime = Files.getLastModifiedTime(itfGzipFile).toInstant();
        if (queries == null || queries.isEmpty()) {
            return new SearchResult(snapshotTime, 0L, 0, Map.of());
        }
        int windowDays = Math.max(0, nearbyDays);
        long minEpoch = Long.MAX_VALUE, maxEpoch = Long.MIN_VALUE;
        double maxSpeedArcsecPerHour = 0.0;
        double minDec = 90.0, maxDec = -90.0;
        for (FindingQuery q : queries) {
            if (q.epochMillis() < minEpoch) minEpoch = q.epochMillis();
            if (q.epochMillis() > maxEpoch) maxEpoch = q.epochMillis();
            maxSpeedArcsecPerHour = Math.max(maxSpeedArcsecPerHour, q.speedArcsecPerHour());
            minDec = Math.min(minDec, q.decDegrees());
            maxDec = Math.max(maxDec, q.decDegrees());
        }

        // Allowed integer dates YYYYMMDD (covering [minEpoch - windowDays - 1, maxEpoch + windowDays + 1])
        LocalDate startDate = Instant.ofEpochMilli(minEpoch).atZone(ZoneOffset.UTC).toLocalDate().minusDays(windowDays + 1L);
        LocalDate endDate = Instant.ofEpochMilli(maxEpoch).atZone(ZoneOffset.UTC).toLocalDate().plusDays(windowDays + 1L);
        Set<Integer> allowedDates = new HashSet<>();
        for (LocalDate d = startDate; !d.isAfter(endDate); d = d.plusDays(1)) {
            allowedDates.add(d.getYear() * 10_000 + d.getMonthValue() * 100 + d.getDayOfMonth());
        }

        // Conservative sky padding for fast pre-filtering: max motion over windowDays + curvature margin (deg)
        double maxTravelDeg = (maxSpeedArcsecPerHour * windowDays * 24.0) / 3600.0
                + (NEARBY_BASE_RADIUS_ARCMIN + NEARBY_RADIUS_PER_DAY_ARCMIN * windowDays) / 60.0 + 1.0;
        double decLo = Math.max(-90.0, minDec - maxTravelDeg);
        double decHi = Math.min(90.0, maxDec + maxTravelDeg);
        double cosMin = Math.cos(Math.toRadians(Math.min(85.0, Math.max(Math.abs(decLo), Math.abs(decHi)))));
        double raPadDeg = Math.min(180.0, maxTravelDeg / Math.max(0.08, cosMin));

        boolean[] allowedRaHours = new boolean[24];
        for (FindingQuery q : queries) {
            if (raPadDeg >= 179.0) {
                java.util.Arrays.fill(allowedRaHours, true);
                break;
            }
            int hStart = (int) Math.floor(normalizeRa(q.raDegrees() - raPadDeg) / 15.0) % 24;
            int hEnd = (int) Math.floor(normalizeRa(q.raDegrees() + raPadDeg) / 15.0) % 24;
            int h = hStart;
            for (int step = 0; step < 24; step++) {
                allowedRaHours[h] = true;
                if (h == hEnd) break;
                h = (h + 1) % 24;
            }
        }

        Map<String, List<RawObs>> grouped = new HashMap<>();
        long lines = 0L;
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(itfGzipFile), 64 * 1024);
             GZIPInputStream gz = new GZIPInputStream(raw, 64 * 1024);
             BufferedReader reader = new BufferedReader(new InputStreamReader(gz, StandardCharsets.US_ASCII), 64 * 1024)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines++;
                if ((lines & (PROGRESS_LINE_INTERVAL - 1)) == 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("MPC ITF scan cancelled.");
                    }
                    if (lineProgress != null) lineProgress.accept(lines);
                }
                if (line.length() < 80) continue;
                int ymd = parseYmdFast(line);
                if (ymd < 0 || !allowedDates.contains(ymd)) continue;
                int raHour = parseTwoDigits(line, 32);
                if (raHour < 0 || raHour >= 24 || !allowedRaHours[raHour]) continue;

                RawObs obs = parseObservation(line);
                if (obs == null) continue;
                if (obs.decDegrees() < decLo || obs.decDegrees() > decHi) continue;
                if (!withinAnyRaBox(obs.raDegrees(), queries, raPadDeg)) continue;

                String obsCode = line.substring(77, 80).trim();
                String trackletId = line.substring(0, 12).trim();
                if (obsCode.isEmpty() || trackletId.isEmpty()) continue;
                String key = obsCode + "\t" + trackletId;
                grouped.computeIfAbsent(key, k -> new ArrayList<>(4)).add(obs);
            }
        }
        if (lineProgress != null) lineProgress.accept(lines);

        List<Tracklet> tracklets = new ArrayList<>(grouped.size());
        for (Map.Entry<String, List<RawObs>> entry : grouped.entrySet()) {
            int tab = entry.getKey().indexOf('\t');
            String obsCode = entry.getKey().substring(0, tab);
            String tid = entry.getKey().substring(tab + 1);
            Tracklet summarized = summarizeTracklet(obsCode, tid, entry.getValue());
            if (summarized != null) tracklets.add(summarized);
        }

        Map<String, FindingMatches> byFinding = new LinkedHashMap<>();
        for (FindingQuery q : queries) {
            byFinding.put(q.id(), matchFinding(q, tracklets, windowDays));
        }
        return new SearchResult(snapshotTime, lines, tracklets.size(), Map.copyOf(byFinding));
    }

    private static FindingMatches matchFinding(FindingQuery q, List<Tracklet> tracklets, int windowDays) {
        double qSpeed = q.speedArcsecPerHour();
        double qPa = q.positionAngleDegrees();
        double cosQ = Math.cos(Math.toRadians(q.decDegrees()));
        double dRaDegPerHour = cosQ > 1e-6 ? (q.raRateArcsecPerHour() / 3600.0) / cosQ : 0.0;
        double dDecDegPerHour = q.decRateArcsecPerHour() / 3600.0;

        List<Match> sameNight = new ArrayList<>();
        List<Match> nearby = new ArrayList<>();

        for (Tracklet tr : tracklets) {
            double dtHours = (tr.midMillis() - q.epochMillis()) / 3_600_000.0;
            double absHours = Math.abs(dtHours);
            double dtDays = dtHours / 24.0;
            if (absHours > windowDays * 24.0 && absHours > SAME_NIGHT_MAX_HOURS) continue;

            double predRa = normalizeRa(q.raDegrees() + dRaDegPerHour * dtHours);
            double predDec = q.decDegrees() + dDecDegPerHour * dtHours;
            double dRa = wrapDeltaDeg(tr.midRaDegrees() - predRa) * Math.cos(Math.toRadians(predDec));
            double dDec = tr.midDecDegrees() - predDec;
            double offsetArcsec = Math.hypot(dRa, dDec) * 3600.0;

            Double speedDiff = tr.speedArcsecPerHour() == null ? null : Math.abs(tr.speedArcsecPerHour() - qSpeed);
            Double paDiff = (tr.positionAngleDegrees() == null || qSpeed < 0.5) ? null
                    : Math.abs(wrapDeltaDeg(tr.positionAngleDegrees() - qPa));

            if (absHours <= SAME_NIGHT_MAX_HOURS) {
                if (tr.speedArcsecPerHour() != null) {
                    if (offsetArcsec > SAME_NIGHT_RADIUS_ARCSEC) continue;
                    double speedTol = Math.max(SAME_NIGHT_RATE_FLOOR, SAME_NIGHT_RATE_FRACTION * Math.max(qSpeed, tr.speedArcsecPerHour()));
                    if (speedDiff > speedTol) continue;
                    if (paDiff != null && qSpeed >= 2.0 && paDiff > SAME_NIGHT_PA_TOLERANCE_DEG) continue;
                } else {
                    if (offsetArcsec > SAME_NIGHT_NO_RATE_RADIUS_ARCSEC) continue;
                }
                sameNight.add(new Match(tr, true, dtDays, offsetArcsec, speedDiff, paDiff));
            } else if (windowDays > 0 && absHours <= windowDays * 24.0) {
                // Require a measured rate on nearby nights to avoid single-observation coincidences.
                if (tr.speedArcsecPerHour() == null) continue;
                double maxOffsetArcsec = (NEARBY_BASE_RADIUS_ARCMIN + NEARBY_RADIUS_PER_DAY_ARCMIN * Math.abs(dtDays)) * 60.0;
                if (offsetArcsec > maxOffsetArcsec) continue;
                double speedTol = Math.max(NEARBY_RATE_FLOOR, NEARBY_RATE_FRACTION * Math.max(qSpeed, tr.speedArcsecPerHour()));
                if (speedDiff > speedTol) continue;
                if (paDiff != null && qSpeed >= 2.0 && paDiff > NEARBY_PA_TOLERANCE_DEG) continue;
                nearby.add(new Match(tr, false, dtDays, offsetArcsec, speedDiff, paDiff));
            }
        }

        sameNight.sort(Comparator.comparingDouble(Match::offsetArcsec));
        nearby.sort(Comparator.comparingDouble(Match::offsetArcsec));
        if (nearby.size() > MAX_NEARBY_MATCHES) {
            nearby = new ArrayList<>(nearby.subList(0, MAX_NEARBY_MATCHES));
        }
        return new FindingMatches(q.id(), List.copyOf(sameNight), List.copyOf(nearby));
    }

    private static Tracklet summarizeTracklet(String obsCode, String tid, List<RawObs> list) {
        if (list.isEmpty()) return null;
        list.sort(Comparator.comparingLong(RawObs::epochMillis));
        RawObs first = list.getFirst();
        RawObs last = list.getLast();
        long midMillis = first.epochMillis() + (last.epochMillis() - first.epochMillis()) / 2L;
        double ra0 = first.raDegrees();
        double sumRa = 0.0, sumDec = 0.0;
        String mag = "";
        for (RawObs o : list) {
            sumRa += ra0 + wrapDeltaDeg(o.raDegrees() - ra0);
            sumDec += o.decDegrees();
            if (mag.isEmpty() && !o.magnitude().isEmpty()) mag = o.magnitude();
        }
        double midRa = normalizeRa(sumRa / list.size());
        double midDec = sumDec / list.size();
        Double speed = null;
        Double pa = null;
        double spanSeconds = (last.epochMillis() - first.epochMillis()) / 1000.0;
        if (list.size() >= 2 && spanSeconds >= MIN_TRACKLET_SPAN_SECONDS) {
            double spanHours = spanSeconds / 3600.0;
            double dRaCos = wrapDeltaDeg(last.raDegrees() - first.raDegrees()) * Math.cos(Math.toRadians(midDec)) * 3600.0 / spanHours;
            double dDec = (last.decDegrees() - first.decDegrees()) * 3600.0 / spanHours;
            speed = Math.hypot(dRaCos, dDec);
            double angle = Math.toDegrees(Math.atan2(dRaCos, dDec));
            pa = angle < 0 ? angle + 360.0 : angle;
        }
        return new Tracklet(obsCode, tid, list.size(), first.epochMillis(), last.epochMillis(), midMillis,
                midRa, midDec, speed, pa, mag);
    }

    private static boolean withinAnyRaBox(double raDeg, List<FindingQuery> queries, double raPadDeg) {
        if (raPadDeg >= 179.0) return true;
        for (FindingQuery q : queries) {
            if (Math.abs(wrapDeltaDeg(raDeg - q.raDegrees())) <= raPadDeg) return true;
        }
        return false;
    }

    /** Fast zero-allocation parse of YYYY MM DD at columns 15..25 of an MPC 80-column record. */
    private static int parseYmdFast(String line) {
        if (line.charAt(19) != ' ' || line.charAt(22) != ' ') return -1;
        int y1 = parseTwoDigits(line, 15);
        int y2 = parseTwoDigits(line, 17);
        int mo = parseTwoDigits(line, 20);
        int d = parseTwoDigits(line, 23);
        if (y1 < 0 || y2 < 0 || mo < 1 || mo > 12 || d < 1 || d > 31) return -1;
        return (y1 * 100 + y2) * 10_000 + mo * 100 + d;
    }

    private static int parseTwoDigits(String s, int offset) {
        char a = s.charAt(offset);
        char b = s.charAt(offset + 1);
        if (a < '0' || a > '9' || b < '0' || b > '9') return -1;
        return (a - '0') * 10 + (b - '0');
    }

    /** Parses an 80-column MPC observation line (UTC time, RA, Dec, magnitude+band). */
    static RawObs parseObservation(String line) {
        try {
            int year = Integer.parseInt(line.substring(15, 19).trim());
            int month = Integer.parseInt(line.substring(20, 22).trim());
            double dayFrac = Double.parseDouble(line.substring(23, 32).trim());
            int day = (int) Math.floor(dayFrac);
            double frac = dayFrac - day;
            long baseMillis = LocalDate.of(year, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            long epochMillis = baseMillis + Math.round(frac * 86_400_000.0);

            String[] raParts = line.substring(32, 44).trim().split("\\s+");
            String[] decParts = line.substring(44, 56).trim().split("\\s+");
            if (raParts.length < 3 || decParts.length < 3) return null;
            double ra = (Double.parseDouble(raParts[0]) + Double.parseDouble(raParts[1]) / 60.0
                    + Double.parseDouble(raParts[2]) / 3600.0) * 15.0;
            boolean south = decParts[0].startsWith("-");
            double decAbs = Math.abs(Double.parseDouble(decParts[0])) + Double.parseDouble(decParts[1]) / 60.0
                    + Double.parseDouble(decParts[2]) / 3600.0;
            double dec = south ? -decAbs : decAbs;
            if (!Double.isFinite(ra) || !Double.isFinite(dec) || dec < -90.0 || dec > 90.0) return null;

            String mag = line.length() >= 71 ? line.substring(65, 71).trim().replaceAll("\\s+", " ") : "";
            return new RawObs(epochMillis, normalizeRa(ra), dec, mag);
        } catch (RuntimeException badLine) {
            return null;
        }
    }

    private static Instant parseHttpDate(String header) {
        try {
            return ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (RuntimeException ignored) {
            return Instant.now();
        }
    }

    private static double normalizeRa(double raDeg) {
        double r = raDeg % 360.0;
        return r < 0 ? r + 360.0 : r;
    }

    private static double wrapDeltaDeg(double delta) {
        double d = (delta + 180.0) % 360.0;
        if (d < 0) d += 360.0;
        return d - 180.0;
    }

    /** Formats a single ITF match into a compact human-readable description. */
    public static String describeMatch(Match m) {
        Tracklet t = m.tracklet();
        var sb = new StringBuilder(t.label());
        sb.append(" (");
        if (!m.sameNight()) {
            sb.append(String.format(Locale.US, "%+.1fd, %s, ", m.deltaDays(), t.midDateUtc()));
        }
        sb.append(t.observationCount()).append(t.observationCount() == 1 ? " obs" : " obs");
        if (!t.magnitude().isEmpty()) {
            sb.append(", mag ").append(t.magnitude());
        }
        if (m.offsetArcsec() < 120.0) {
            sb.append(String.format(Locale.US, ", %.1f″ away", m.offsetArcsec()));
        } else {
            sb.append(String.format(Locale.US, ", %.2f′ from linear", m.offsetArcsec() / 60.0));
        }
        if (m.speedDiffArcsecPerHour() != null) {
            sb.append(String.format(Locale.US, ", Δv %.1f″/h", m.speedDiffArcsecPerHour()));
        }
        if (m.paDiffDegrees() != null) {
            sb.append(String.format(Locale.US, ", ΔPA %.0f°", m.paDiffDegrees()));
        }
        sb.append(')');
        return sb.toString();
    }
}
