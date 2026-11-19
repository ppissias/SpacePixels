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
import io.github.ppissias.jtransient.core.SubStackAstrometry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Measurements and observation selections made in the Asteroids &amp; Comets window, kept beside the report as
 * {@value #FILE_NAME}: faint-mover sub-stack measurements, changed observation ticks, and measured light curves.
 * The report's own files are not changed.
 */
public final class SavedMeasurements {
    /** The file in the report folder. */
    public static final String FILE_NAME = "mpc_measurements.json";

    /**
     * One faint-mover sub-stack measurement paired with its reduced astrometric observation (null when the plate
     * solution could not reduce that sub-stack).
     */
    public static final class SavedSubStack {
        public int firstCutout;
        public int lastCutout;
        public int frames;
        public long midpointMillis;
        public long exposureMillis;
        public double x;
        public double y;
        public Double snr;
        public String problem;
        public SavedObservation observation;

        /** Converts a live measurement and its optional reduced observation into a JSON-safe snapshot. */
        public static SavedSubStack from(SubStackAstrometry.Measurement measurement, MpcObservation observation) {
            var saved = new SavedSubStack();
            saved.firstCutout = measurement.firstCutout();
            saved.lastCutout = measurement.lastCutout();
            saved.frames = measurement.frames();
            saved.midpointMillis = measurement.midpointMillis();
            saved.exposureMillis = measurement.exposureMillis();
            saved.x = finiteOrZero(measurement.x());
            saved.y = finiteOrZero(measurement.y());
            saved.snr = finiteOrNull(measurement.snr());
            saved.problem = measurement.problem();
            saved.observation = observation == null ? null : SavedObservation.from(observation);
            return saved;
        }

        /** Restores the sub-stack measurement. */
        public SubStackAstrometry.Measurement toMeasurement() {
            return new SubStackAstrometry.Measurement(firstCutout, lastCutout, frames, midpointMillis, exposureMillis,
                    x, y, snr == null ? Double.NaN : snr, problem);
        }
    }

    /** JSON-safe snapshot of one {@link MpcObservation}. */
    public static final class SavedObservation {
        public int frameNumber;
        public long midpointMillis;
        public long exposureMillis;
        public double x;
        public double y;
        public double raDegrees;
        public double decDegrees;
        public Double rmsRaArcsec;
        public Double rmsDecArcsec;
        public String astCat;
        public String notes;

        /** Converts an {@link MpcObservation} into a JSON-safe snapshot. */
        public static SavedObservation from(MpcObservation o) {
            var saved = new SavedObservation();
            saved.frameNumber = o.frameNumber();
            saved.midpointMillis = o.midpointMillis();
            saved.exposureMillis = o.exposureMillis();
            saved.x = finiteOrZero(o.x());
            saved.y = finiteOrZero(o.y());
            saved.raDegrees = finiteOrZero(o.raDegrees());
            saved.decDegrees = finiteOrZero(o.decDegrees());
            saved.rmsRaArcsec = finiteOrNull(o.rmsRaArcsec());
            saved.rmsDecArcsec = finiteOrNull(o.rmsDecArcsec());
            saved.astCat = o.astCat();
            saved.notes = o.notes();
            return saved;
        }

        /** Restores the {@link MpcObservation}. */
        public MpcObservation toObservation() {
            return new MpcObservation(frameNumber, midpointMillis, exposureMillis, x, y, raDegrees, decDegrees,
                    rmsRaArcsec == null ? 0.0 : rmsRaArcsec, rmsDecArcsec == null ? 0.0 : rmsDecArcsec,
                    astCat == null ? "" : astCat, notes == null ? "" : notes);
        }
    }

    /** JSON-safe snapshot of one {@link LightCurve.Point}; non-finite offsets are stored as null. */
    public static final class SavedCurvePoint {
        public int frameNumber;
        public long midpointMillis;
        public double x;
        public double y;
        public Double flux;
        public Double fluxError;
        public Double offset;
        public int flags;

        /** Converts a {@link LightCurve.Point} into a JSON-safe snapshot. */
        public static SavedCurvePoint from(LightCurve.Point p) {
            var saved = new SavedCurvePoint();
            saved.frameNumber = p.frameNumber();
            saved.midpointMillis = p.midpointMillis();
            saved.x = finiteOrZero(p.x());
            saved.y = finiteOrZero(p.y());
            saved.flux = finiteOrNull(p.flux());
            saved.fluxError = finiteOrNull(p.fluxError());
            saved.offset = finiteOrNull(p.offset());
            saved.flags = p.flags();
            return saved;
        }

        /** Restores the {@link LightCurve.Point}. */
        public LightCurve.Point toPoint() {
            return new LightCurve.Point(frameNumber, midpointMillis, x, y,
                    flux == null ? Double.NaN : flux,
                    fluxError == null ? Double.NaN : fluxError,
                    offset == null ? Double.NaN : offset,
                    flags);
        }
    }

    /** JSON-safe snapshot of a measured {@link LightCurve.Curve}. */
    public static final class SavedCurve {
        public String id;
        public boolean measuredPositions;
        public Double fwhm;
        public Double apertureRadius;
        public int comparisonStars;
        public boolean onReference;
        public String comparisonNote;
        public List<SavedCurvePoint> points = new ArrayList<>();

        /** Converts a {@link LightCurve.Curve} into a JSON-safe snapshot. */
        public static SavedCurve from(LightCurve.Curve curve) {
            var saved = new SavedCurve();
            saved.id = curve.id();
            saved.measuredPositions = curve.measuredPositions();
            saved.fwhm = finiteOrNull(curve.fwhm());
            saved.apertureRadius = finiteOrNull(curve.apertureRadius());
            saved.comparisonStars = curve.comparisonStars();
            saved.onReference = curve.onReference();
            saved.comparisonNote = curve.comparisonNote();
            for (var point : curve.points()) saved.points.add(SavedCurvePoint.from(point));
            return saved;
        }

        /** Restores the {@link LightCurve.Curve}. */
        public LightCurve.Curve toCurve() {
            var restored = new ArrayList<LightCurve.Point>();
            if (points != null) {
                for (var point : points) if (point != null) restored.add(point.toPoint());
            }
            return new LightCurve.Curve(id, measuredPositions,
                    fwhm == null ? Double.NaN : fwhm,
                    apertureRadius == null ? Double.NaN : apertureRadius,
                    comparisonStars, onReference, comparisonNote, List.copyOf(restored));
        }
    }

    /** One object's saved measurements, observation selection and light curve. */
    public static final class Entry {
        public String id;
        /** Measured sub-stacks of a faint mover; null when not measured. */
        public List<SavedSubStack> subStacks;
        /** Selected observation indices (zero-based); null when the default selection applies. */
        public List<Integer> selectedIndices;
        /** Measured light curve; null when not measured. */
        public SavedCurve lightCurve;

        /** Whether this entry carries nothing worth writing to disk. */
        public boolean isEmpty() {
            return (subStacks == null || subStacks.isEmpty()) && selectedIndices == null && lightCurve == null;
        }

        /** Deep copy so a background worker writes a stable snapshot. */
        public Entry copy() {
            var copy = new Entry();
            copy.id = id;
            copy.subStacks = subStacks == null ? null : new ArrayList<>(subStacks);
            copy.selectedIndices = selectedIndices == null ? null : new ArrayList<>(selectedIndices);
            copy.lightCurve = lightCurve;
            return copy;
        }

        /** Restores the sub-stack measurements, or an empty list when absent. */
        public List<SubStackAstrometry.Measurement> restoredSubStacks() {
            if (subStacks == null || subStacks.isEmpty()) return List.of();
            var list = new ArrayList<SubStackAstrometry.Measurement>(subStacks.size());
            for (var s : subStacks) if (s != null) list.add(s.toMeasurement());
            return List.copyOf(list);
        }

        /** Restores the sub-stack observations in time order, or an empty list when absent. */
        public List<MpcObservation> restoredSubStackObservations() {
            if (subStacks == null || subStacks.isEmpty()) return List.of();
            var list = new ArrayList<MpcObservation>(subStacks.size());
            for (var s : subStacks) if (s != null && s.observation != null) list.add(s.observation.toObservation());
            return List.copyOf(list);
        }

        /** Restores the saved selection indices bounded to {@code [0, observationCount)}, or null when absent. */
        public Set<Integer> restoredSelection(int observationCount) {
            if (selectedIndices == null) return null;
            var set = new TreeSet<Integer>();
            for (Integer index : selectedIndices) {
                if (index != null && index >= 0 && index < observationCount) set.add(index);
            }
            return set;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private SavedMeasurements() { }

    /** The saved entries by object; empty when there is no file or it cannot be read. */
    public static Map<String, Entry> load(File reportFolder) {
        if (reportFolder == null) return new LinkedHashMap<>();
        File file = new File(reportFolder, FILE_NAME);
        var entries = new LinkedHashMap<String, Entry>();
        if (!file.isFile()) return entries;
        try {
            Entry[] read = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Entry[].class);
            if (read != null) {
                for (Entry entry : read) {
                    if (entry != null && entry.id != null && !entry.isEmpty()) entries.put(entry.id, entry);
                }
            }
        } catch (IOException | JsonParseException unreadable) {
            return new LinkedHashMap<>();
        }
        return entries;
    }

    /** Saves the non-empty entries beside the report, replacing the file atomically; removes it when none exist. */
    public static void save(File reportFolder, Map<String, Entry> entries) throws IOException {
        File file = new File(reportFolder, FILE_NAME);
        var kept = entries.values().stream().filter(e -> e != null && e.id != null && !e.isEmpty()).toArray(Entry[]::new);
        if (kept.length == 0) {
            Files.deleteIfExists(file.toPath());
            return;
        }
        File partial = new File(reportFolder, FILE_NAME + ".part");
        Files.writeString(partial.toPath(), GSON.toJson(kept), StandardCharsets.UTF_8);
        try {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException fallback) {
            Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Pairs each sub-stack measurement with its matching reduced observation by midpoint timestamp. */
    public static List<SavedSubStack> pairSubStacks(List<SubStackAstrometry.Measurement> stacks,
                                                    List<MpcObservation> observations) {
        var paired = new ArrayList<SavedSubStack>(stacks.size());
        int obsIndex = 0;
        for (var m : stacks) {
            MpcObservation matched = null;
            if (obsIndex < observations.size() && observations.get(obsIndex).midpointMillis() == m.midpointMillis()) {
                matched = observations.get(obsIndex++);
            } else {
                for (var o : observations) {
                    if (o.midpointMillis() == m.midpointMillis()) {
                        matched = o;
                        break;
                    }
                }
            }
            paired.add(SavedSubStack.from(m, matched));
        }
        return paired;
    }

    /** Selects the observations saved in {@code entry}, else falls back to {@link NightLinks#defaultSelection}. */
    public static List<MpcObservation> selectedOrDefault(List<MpcObservation> observations, Entry entry) {
        if (observations.isEmpty()) return List.of();
        if (entry == null || entry.selectedIndices == null) return NightLinks.defaultSelection(observations);
        var indices = entry.restoredSelection(observations.size());
        var picked = new ArrayList<MpcObservation>(indices.size());
        for (int idx : indices) picked.add(observations.get(idx));
        return picked;
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
