package eu.startales.spacepixels.mpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs Bill Gray's command-line Find_Orb ({@code fo64.exe} on Windows, {@code fo} elsewhere) on observations and reads
 * the preliminary orbit it fits. Find_Orb must run in its own folder, where it keeps its settings and data and writes
 * its results ({@code total.json}); runs are therefore one at a time. Before reading the result, the residual times are
 * matched to the observations sent, so an old result in that folder is never taken for a new one.
 */
public final class FindOrb {
    /** One observation's residual: observed minus computed, in arcseconds (RA times cos Dec), and whether it was used. */
    public record Residual(String isoTime, double raArcsec, double decArcsec, boolean used) { }

    /**
     * A fitted orbit: heliocentric elements in the J2000 ecliptic at {@code epochIso} (angles in degrees, distances in
     * AU), each with its 1-sigma uncertainty where Find_Orb gives one (else null), and the fit's residuals.
     */
    public record Orbit(String epochIso, double epochJd, double a, Double aSigma, double e, Double eSigma, double i, Double iSigma,
                        double ascendingNode, Double nodeSigma, double argumentOfPerihelion, Double perihelionSigma,
                        double meanAnomaly, Double meanAnomalySigma, double q, Double qSigma, Double absoluteMagnitude,
                        Double rmsArcsec, int residualCount, Double neoProbability, Double earthMoid, Double uncertainty,
                        List<Residual> residuals, String version) {
        /** Whether the orbit is bound (an ellipse). */
        public boolean bound() { return e < 1 && a > 0; }
    }

    /** How long one fit may take before it is stopped. */
    static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final Object ONE_RUN_AT_A_TIME = new Object();

    private FindOrb() { }

    /**
     * Fits an orbit to ADES PSV or 80-column observations with the Find_Orb program at {@code executable}.
     *
     * @throws IOException when the program cannot run, does not finish, or its result does not belong to the observations
     */
    public static Orbit fit(File executable, String observations, List<String> observationTimesIso) throws IOException, InterruptedException {
        if (executable == null || !executable.isFile()) {
            throw new IOException("Find_Orb is not at " + executable + ".");
        }
        File folder = executable.getAbsoluteFile().getParentFile();
        Path input = Files.createTempFile("spacepixels-findorb-", ".psv");
        try {
            Files.writeString(input, observations, StandardCharsets.UTF_8);
            synchronized (ONE_RUN_AT_A_TIME) {
                File total = new File(folder, "total.json");
                Files.deleteIfExists(total.toPath());
                Process process = new ProcessBuilder(executable.getAbsolutePath(), input.toAbsolutePath().toString())
                        .directory(folder).redirectInput(ProcessBuilder.Redirect.from(emptyFile()))
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                try {
                    if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IOException("Find_Orb did not finish within " + TIMEOUT.toMinutes() + " minutes.");
                    }
                } finally {
                    process.destroyForcibly();
                }
                if (!total.isFile()) {
                    throw new IOException("Find_Orb wrote no result; check that it runs from its folder (" + folder + ").");
                }
                return parse(Files.readString(total.toPath(), StandardCharsets.UTF_8), observationTimesIso);
            }
        } finally {
            Files.deleteIfExists(input);
        }
    }

    /** An empty file to read as the program's input, so a "press any key" prompt ends at once. */
    private static File emptyFile() throws IOException {
        File empty = Files.createTempFile("spacepixels-empty-", ".txt").toFile();
        empty.deleteOnExit();
        return empty;
    }

    /**
     * The first object of a Find_Orb {@code total.json}. Its residual times must be the observation times given (to
     * the second), else the result is not for these observations.
     */
    static Orbit parse(String json, List<String> observationTimesIso) throws IOException {
        JsonObject objects;
        try {
            objects = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("objects");
        } catch (RuntimeException unreadable) {
            throw new IOException("Find_Orb's result cannot be read.");
        }
        if (objects == null || objects.isEmpty()) throw new IOException("Find_Orb found no orbit.");
        JsonObject object = objects.entrySet().iterator().next().getValue().getAsJsonObject();
        JsonObject e = object.getAsJsonObject("elements");
        var residuals = new ArrayList<Residual>();
        JsonObject observations = object.has("observations") ? object.getAsJsonObject("observations") : null;
        if (observations != null && observations.has("residuals")) {
            for (JsonElement element : observations.getAsJsonArray("residuals")) {
                JsonObject r = element.getAsJsonObject();
                residuals.add(new Residual(text(r, "iso date"), number(r, "dRA"), number(r, "dDec"), !r.has("incl") || r.get("incl").getAsInt() == 1));
            }
        }
        var sent = observationTimesIso.stream().map(FindOrb::toSecond).sorted().toList();
        var answered = residuals.stream().map(r -> toSecond(r.isoTime())).sorted().toList();
        if (!sent.equals(answered)) {
            throw new IOException("Find_Orb's result is not for the observations sent (" + answered.size() + " residuals for "
                    + sent.size() + " observations); nothing is shown.");
        }
        JsonObject moids = e.has("MOIDs") && e.get("MOIDs").isJsonObject() ? e.getAsJsonObject("MOIDs") : null;
        return new Orbit(text(e, "epoch_iso"), number(e, "epoch"), number(e, "a"), optional(e, "a sigma"), number(e, "e"), optional(e, "e sigma"),
                number(e, "i"), optional(e, "i sigma"), number(e, "asc_node"), optional(e, "asc_node sigma"),
                number(e, "arg_per"), optional(e, "arg_per sigma"), number(e, "M"), optional(e, "M sigma"),
                number(e, "q"), optional(e, "q sigma"), optional(e, "H"), optional(e, "rms_residual"),
                e.has("n_resids") ? e.get("n_resids").getAsInt() : residuals.size(), optional(e, "p_NEO"),
                moids == null ? null : optional(moids, "Earth"), optional(e, "U"), List.copyOf(residuals),
                text(object, "Find_Orb_version_iso"));
    }

    /** An ISO time to whole seconds, so Find_Orb's rounding of milliseconds does not matter. */
    private static long toSecond(String iso) {
        try {
            return Math.round(Instant.parse(iso).toEpochMilli() / 1000.0);
        } catch (RuntimeException unreadable) {
            return Long.MIN_VALUE;
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static double number(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : Double.NaN;
    }

    private static Double optional(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : null;
    }
}
