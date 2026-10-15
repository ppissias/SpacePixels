package eu.startales.spacepixels.util.reporting;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Read-only report inputs; all comparison rules live in the shared browser evaluator. */
final class CatalogueComparisonWriter {
    private static final Gson JSON = new Gson(); // Default HTML escaping also protects embedded JSON scripts.

    private CatalogueComparisonWriter() { }

    static String key(int number) {
        return "photometry_v" + number + "_vsx.json";
    }

    static String slot(int number, boolean compact, boolean matched) {
        return "<div class='catalogue-comparison' data-catalogue-comparison='" + key(number)
                + "' data-compact='" + compact + "'" + (matched ? "" : " hidden")
                + "><span class='astro-note'>Catalogue comparison: see the observed variation, time span and catalogue values above.</span></div>";
    }

    static void writeData(PrintWriter report, DetectionReportContext context, VariableStarAnalysis analysis,
                          List<StarLightCurve> candidates, SessionCatalogue catalogue) {
        JsonObject root = new JsonObject();
        JsonObject entries = new JsonObject();
        root.addProperty("version", 1);
        root.add("candidates", entries);
        FrameHeaders headers = new FrameHeaders(context.fitsFiles);
        for (int i = 0; i < candidates.size(); i++) {
            StarLightCurve star = candidates.get(i);
            JsonObject entry = new JsonObject();
            entry.add("observation", observation(star, analysis.frames, headers));
            JsonArray matches = new JsonArray();
            PhotometryReportSectionWriter.SkyTarget target = PhotometryReportSectionWriter.skyTarget(context, star.x, star.y);
            if (target != null) {
                for (SessionCatalogue.VariableMatch match : catalogue.variablesNear(target.raDegrees, target.decDegrees, target.radiusArcsec)) {
                    matches.add(match(match.star, match.separationArcsec, catalogue.catalogue.fetchedAtUtc));
                }
            }
            entry.add("matches", matches);
            entries.add(key(i + 1), entry);
        }
        report.println("<script type='application/json' id='catalogue-comparison-data'>" + JSON.toJson(root) + "</script>");
    }

    static JsonObject observation(StarLightCurve star, List<PipelineTelemetry.PhotometryFrameStat> frames,
                                  FrameHeaders headers) {
        JsonObject data = new JsonObject();
        finite(data, "observedRange", star.amplitude);
        finite(data, "expectedScatter", star.expectedScatter);
        List<Double> times = new ArrayList<>();
        TreeSet<String> filters = new TreeSet<>();
        int used = 0;
        boolean missingFilter = false;
        if (star.deltaMag != null && star.flags != null) {
            int count = Math.min(frames.size(), Math.min(star.deltaMag.length, star.flags.length));
            for (int j = 0; j < count; j++) {
                PipelineTelemetry.PhotometryFrameStat frame = frames.get(j);
                if (star.flags[j] != 0 || !Double.isFinite(star.deltaMag[j]) || !frame.used) continue;
                used++;
                if (Double.isFinite(frame.julianDate)) times.add(frame.julianDate);
                String filter = headers.filter(frame);
                if (filter == null) missingFilter = true;
                else filters.add(filter);
            }
        }
        data.addProperty("usablePoints", used);
        data.addProperty("missingFilter", missingFilter || used == 0);
        JsonArray bands = new JsonArray();
        filters.forEach(bands::add);
        data.add("filters", bands);
        // A span from a subset of timestamps would overstate what we know about the sequence.
        if (times.size() == used && used >= 2) {
            Collections.sort(times);
            finite(data, "spanMinutes", (times.get(times.size() - 1) - times.get(0)) * 1440.0);
            double largestGap = 0;
            for (int j = 1; j < times.size(); j++) largestGap = Math.max(largestGap, times.get(j) - times.get(j - 1));
            finite(data, "largestGapMinutes", largestGap * 1440.0);
        }
        return data;
    }

    static JsonObject match(SkyCatalogue.VariableStar star, double separation, String fetchedAt) {
        JsonObject data = new JsonObject();
        data.addProperty("oid", star.oid);
        data.addProperty("name", star.name);
        data.addProperty("type", star.type);
        nullableFinite(data, "maxMag", star.max);
        nullableFinite(data, "minMag", star.min);
        data.addProperty("minIsAmplitude", star.minIsAmplitude);
        boolean legacyBand = star.maxBand == null && star.minBand == null && star.band != null;
        data.addProperty("legacySharedBand", legacyBand);
        data.addProperty("maxBand", legacyBand ? star.band : star.maxBand);
        data.addProperty("minBand", legacyBand ? star.band : star.minBand);
        data.addProperty("maxLimit", star.maxLimit);
        data.addProperty("minLimit", star.minLimit);
        data.addProperty("maxUncertainty", star.maxUncertainty);
        data.addProperty("minUncertainty", star.minUncertainty);
        data.addProperty("periodLimit", star.periodLimit);
        data.addProperty("periodUncertainty", star.periodUncertainty);
        data.addProperty("comparisonMetadataComplete", star.comparisonMetadataComplete);
        data.addProperty("bibliographyAvailable", star.bibliographyAvailable);
        nullableFinite(data, "periodDays", star.periodDays);
        finite(data, "separationArcsec", separation);
        data.addProperty("source", "AAVSO VSX via VizieR");
        data.addProperty("fetchedAtUtc", fetchedAt);
        return data;
    }

    static void appendScript(PrintWriter report) {
        report.println("<script type='application/json' id='catalogue-source-notes'>" + resource("catalogue-source-notes.json").replace("<", "\\u003c") + "</script>");
        report.println("<script>" + resource("catalogue-comparison.js") + "</script>");
    }

    private static String resource(String name) {
        try (InputStream in = CatalogueComparisonWriter.class.getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing report resource " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read report resource " + name, e);
        }
    }

    private static void nullableFinite(JsonObject data, String name, Double value) {
        if (value != null) finite(data, name, value);
    }

    private static void finite(JsonObject data, String name, double value) {
        if (Double.isFinite(value)) data.addProperty(name, value);
    }

    /** Original frame index is authoritative; filename fallback must be unique. */
    static final class FrameHeaders {
        private final FitsFileInformation[] files;
        private final Map<String, FitsFileInformation> byName = new HashMap<>();

        FrameHeaders(FitsFileInformation[] files) {
            this.files = files;
            if (files != null) for (FitsFileInformation file : files) {
                if (file == null || file.getFileName() == null) continue;
                if (byName.containsKey(file.getFileName())) byName.put(file.getFileName(), null);
                else byName.put(file.getFileName(), file);
            }
        }

        String filter(PipelineTelemetry.PhotometryFrameStat frame) {
            FitsFileInformation file = null;
            if (files != null && frame.frameIndex >= 0 && frame.frameIndex < files.length) {
                FitsFileInformation indexed = files[frame.frameIndex];
                if (indexed != null && frame.filename != null && frame.filename.equals(indexed.getFileName())) file = indexed;
            }
            if (file == null) file = byName.get(frame.filename);
            String value = file == null ? null : file.getFitsHeader().get("FILTER");
            if (value == null) return null;
            value = value.trim();
            if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) value = value.substring(1, value.length() - 1).trim();
            return value.isEmpty() ? null : value;
        }
    }
}
