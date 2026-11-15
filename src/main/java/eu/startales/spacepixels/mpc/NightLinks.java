/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.mpc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.WcsSolutionResolver;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SkyMath;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Objects of other nights that the user says are the same object, kept beside each report as {@value #FILE_NAME}. A
 * link is written into both report folders, so either night shows it; the nights of a third report linked to either
 * are found by following the links. The reports themselves are not changed.
 * <p>
 * A linked night is read from its own report folder: its frames' headers in the session folder above it, their plate
 * solution and the session's Gaia plate correction give its positions, exactly as when that session is open.
 */
public final class NightLinks {
    /** The file in the report folder. */
    public static final String FILE_NAME = "linked_nights.json";
    /** The most nights followed from one object, this one included. */
    static final int MAX_NIGHTS = 20;

    /** One link of an object of this report to an object of another report. */
    public static final class Link {
        /** The object of this report. */
        public String object;
        /** The other report folder: relative to this one, with forward slashes, or absolute on another drive. */
        public String report;
        /** The object of the other report. */
        public String otherObject;
        /** The start of the other session's first exposure, UTC epoch milliseconds, to name it when it is missing. */
        public long otherSessionStartMillis;
        /** When the link was made, UTC, ISO 8601. */
        public String linkedAtUtc;
    }

    /** Another night of an object: its report folder and the object's identifier there. */
    public record Ref(File folder, String objectId) { }

    /** The other nights of an object, nearest link first, and the linked report folders that are not where the links say. */
    public record Linked(List<Ref> nights, List<String> missing) { }

    /** A report opened for its positions: its objects (with later identifications), their reduction and the observing site. */
    public record Night(ReportedObjects.Report report, AstrometricReduction.Result reduction, ObservingSite site) {
        /** The object of that identifier, or null. */
        public ReportedObjects.ReportedObject object(String id) {
            return report.objects().stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
        }

        /** An object's measured positions as observations, in time order; empty for a faint mover or without a reduction. */
        public List<MpcObservation> observations(ReportedObjects.ReportedObject object) {
            if (object == null || !object.measured() || reduction.reduction() == null) return List.of();
            return reduction.reduction().reducePositions(object.positions());
        }

        /** Whether the object's positions lack the detection sizes and signal-to-noise an export needs. */
        public boolean lacksMeasurements(ReportedObjects.ReportedObject object) {
            return object != null && object.measured() && object.positions().stream().anyMatch(p -> p.fwhm() == null || p.snr() == null);
        }

        /** The object's own tracklet ID in its report. */
        public String trkSub(ReportedObjects.ReportedObject object) {
            return AdesPsv.trkSub(report.sessionStartMillis(), Math.max(0, report.objects().indexOf(object)));
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private NightLinks() { }

    /** The links saved beside a report; empty when there is no file or it cannot be read. */
    public static List<Link> load(File reportFolder) {
        File file = new File(reportFolder, FILE_NAME);
        var links = new ArrayList<Link>();
        if (!file.isFile()) return links;
        try {
            Link[] read = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Link[].class);
            if (read != null) {
                for (Link link : read) {
                    if (link != null && link.object != null && link.report != null && link.otherObject != null) links.add(link);
                }
            }
        } catch (IOException | JsonParseException unreadable) {
            return new ArrayList<>();
        }
        return links;
    }

    /** Saves the links, replacing the file in one step; removes the file when there are none. */
    public static void save(File reportFolder, List<Link> links) throws IOException {
        File file = new File(reportFolder, FILE_NAME);
        if (links.isEmpty()) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        File partial = new File(reportFolder, FILE_NAME + ".part");
        Files.writeString(partial.toPath(), GSON.toJson(links.toArray(new Link[0])), StandardCharsets.UTF_8);
        Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Links an object of one report to an object of another, in both report folders. An earlier link of either object
     * to the other report is replaced: an object is one object on each night.
     */
    public static void link(Night one, String oneObject, Night other, String otherObject) throws IOException {
        File a = one.report().folder(), b = other.report().folder();
        if (same(a, b)) throw new IOException("Both objects are in the same report.");
        String time = Instant.now().toString();
        var linksA = load(a);
        linksA.removeIf(l -> l.object.equals(oneObject) && same(resolve(a, l.report), b));
        linksA.add(link(oneObject, path(a, b), otherObject, other.report().sessionStartMillis(), time));
        var linksB = load(b);
        linksB.removeIf(l -> l.object.equals(otherObject) && same(resolve(b, l.report), a));
        linksB.add(link(otherObject, path(b, a), oneObject, one.report().sessionStartMillis(), time));
        save(a, linksA);
        save(b, linksB);
    }

    /** Removes every link of an object, here and the matching link in each other report that is still where it was. */
    public static void unlink(File reportFolder, String object) throws IOException {
        var links = load(reportFolder);
        for (Link link : links) {
            if (!link.object.equals(object)) continue;
            File other = resolve(reportFolder, link.report);
            if (!other.isDirectory()) continue;
            var back = load(other);
            if (back.removeIf(l -> l.object.equals(link.otherObject) && same(resolve(other, l.report), reportFolder))) save(other, back);
        }
        if (links.removeIf(l -> l.object.equals(object))) save(reportFolder, links);
    }

    /**
     * The other nights of an object: its links, then the links of those nights' objects, and so on, each report once,
     * at most {@value #MAX_NIGHTS} nights in all.
     */
    public static Linked linked(File reportFolder, String object) {
        var nights = new ArrayList<Ref>();
        var missing = new ArrayList<String>();
        Set<String> seen = new HashSet<>();
        seen.add(key(reportFolder));
        var queue = new ArrayDeque<Ref>();
        queue.add(new Ref(reportFolder, object));
        while (!queue.isEmpty() && nights.size() < MAX_NIGHTS - 1) {
            Ref from = queue.poll();
            for (Link link : load(from.folder())) {
                if (!link.object.equals(from.objectId())) continue;
                File other = resolve(from.folder(), link.report);
                if (!seen.add(key(other))) continue;
                if (!new File(other, "report-data.json").isFile()) {
                    missing.add(link.report);
                    continue;
                }
                Ref ref = new Ref(other, link.otherObject);
                nights.add(ref);
                queue.add(ref);
                if (nights.size() >= MAX_NIGHTS - 1) break;
            }
        }
        return new Linked(List.copyOf(nights), List.copyOf(missing));
    }

    /**
     * Opens a report folder for its positions: the report (with its later identifications), its frames' headers from
     * the session folder above it, their plate solution and the session's plate correction. The observing site comes
     * from the headers, else from the fallback values as typed in Astrometry Config.
     *
     * @throws IOException when the report or one of its frames cannot be read
     */
    public static Night open(File reportFolder, String fallbackLatitude, String fallbackLongitude, String fallbackAltitude) throws Exception {
        var report = ReportedObjects.load(reportFolder);
        var later = LaterIdentifications.load(reportFolder);
        if (!later.isEmpty()) report = report.withObjects(LaterIdentifications.apply(report.objects(), later));
        File session = reportFolder.getAbsoluteFile().getParentFile();
        File[] frames = new File[report.frames().size()];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new File(session, report.frames().get(i).fileName());
            if (!frames[i].isFile()) {
                throw new IOException("Frame " + report.frames().get(i).fileName() + " of " + reportFolder.getName()
                        + " is not in " + session + ".");
            }
        }
        FitsInput.Input[] inputs = FitsInput.inspect(frames, null, false);
        FitsFileInformation[] files = new FitsFileInformation[inputs.length];
        for (int i = 0; i < inputs.length; i++) files[i] = inputs[i].information();
        var resolved = WcsSolutionResolver.resolveUncorrected(null, files);
        var reduction = AstrometricReduction.create(resolved == null ? null : resolved.getTransformer(),
                PlateSolutionCorrection.load(session), files);
        return new Night(report, reduction, ObservingSite.resolve(files, fallbackLatitude, fallbackLongitude, fallbackAltitude));
    }

    /**
     * How far, in arcminutes, the other night's middle observation lies from where this night's straight motion leads
     * at that time; null when either night has fewer than two observations. A hint for which object is the same one:
     * over a few days a main-belt asteroid's path bends, so a few arcminutes off is still a good candidate.
     */
    public static Double extrapolationMissArcmin(List<MpcObservation> thisNight, List<MpcObservation> otherNight) {
        if (thisNight.size() < 2 || otherNight.size() < 2) return null;
        MpcObservation first = thisNight.getFirst(), last = thisNight.getLast();
        double hours = (last.midpointMillis() - first.midpointMillis()) / 3_600_000.0;
        if (!(hours > 0)) return null;
        MpcObservation target = otherNight.get(otherNight.size() / 2);
        double cosDec = Math.cos(Math.toRadians((first.decDegrees() + last.decDegrees()) / 2));
        double raRate = SkyMath.normalizeDegrees(last.raDegrees() - first.raDegrees() + 180) - 180;
        double ahead = (target.midpointMillis() - first.midpointMillis()) / 3_600_000.0 / hours;
        double ra = SkyMath.normalizeDegrees(first.raDegrees() + raRate * ahead);
        double dec = first.decDegrees() + (last.decDegrees() - first.decDegrees()) * ahead;
        if (cosDec <= 0 || Math.abs(dec) > 90) return null;
        return SkyMath.separationArcsec(ra, dec, target.raDegrees(), target.decDegrees()) / 60;
    }

    /**
     * Whether two nights were observed from one site, as far as is known: within {@value AdesPsv#STATION_TOLERANCE_KM}
     * km, or either site unknown. One file, and one Find_Orb run here, carries one observatory code.
     */
    public static boolean sameSite(ObservingSite one, ObservingSite other) {
        if (one == null || other == null) return true;
        double lat1 = Math.toRadians(one.latitudeDeg()), lat2 = Math.toRadians(other.latitudeDeg());
        double dLat = lat2 - lat1, dLon = Math.toRadians(other.longitudeDeg() - one.longitudeDeg());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(a))) <= AdesPsv.STATION_TOLERANCE_KM;
    }

    /** The observations at the indexes {@link MpcExport#spreadSelection} picks by default, as a linked night contributes them. */
    public static List<MpcObservation> defaultSelection(List<MpcObservation> observations) {
        var picked = new ArrayList<MpcObservation>();
        for (int index : MpcExport.spreadSelection(observations.size(), MpcExport.DEFAULT_SELECTION)) picked.add(observations.get(index));
        return picked;
    }

    /** The path from one report folder to another: relative with forward slashes, or absolute when there is none. */
    static String path(File from, File to) {
        Path a = from.toPath().toAbsolutePath().normalize(), b = to.toPath().toAbsolutePath().normalize();
        try {
            return a.relativize(b).toString().replace(File.separatorChar, '/');
        } catch (IllegalArgumentException otherRoot) {
            return b.toString();
        }
    }

    /** The folder a saved path names, relative to the report folder holding it. */
    public static File resolve(File from, String path) {
        File file = new File(path);
        return (file.isAbsolute() ? file.toPath() : from.toPath().resolve(path)).toAbsolutePath().normalize().toFile();
    }

    private static boolean same(File a, File b) {
        return key(a).equals(key(b));
    }

    private static String key(File folder) {
        String path = folder.toPath().toAbsolutePath().normalize().toString();
        return File.separatorChar == '\\' ? path.toLowerCase(java.util.Locale.ROOT) : path;
    }

    private static Link link(String object, String report, String otherObject, long otherStart, String time) {
        Link link = new Link();
        link.object = object;
        link.report = report;
        link.otherObject = otherObject;
        link.otherSessionStartMillis = otherStart;
        link.linkedAtUtc = time;
        return link;
    }
}
