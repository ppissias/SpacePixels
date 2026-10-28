package eu.startales.spacepixels.preparation;

import java.util.Map;
import java.util.Set;
import eu.startales.spacepixels.preparation.PreparationInputs.Role;

/** Generates fixed, linear Siril recipes over isolated, ASCII-named working inputs. */
public final class SirilPreparationRecipe {
    /** Only explicit recipe values are used; paths to the observer's originals never appear in the script. */
    private SirilPreparationRecipe() { }

    /** A private Siril configuration with deterministic output and bounded memory; no personal settings are read. */
    public static String configuration() {
        return "[core]\nextension=.fit\nmem_mode=0\nmem_ratio=0.35\n"
                + "[debayer]\nuse_bayer_header=true\ninterpolation=8\n[compression]\nenabled=false\n";
    }

    /** Converts each role independently, retaining mosaics and individual exposures for subsequent validation. */
    public static String conversion(Set<Role> roles, Set<Role> masters) {
        StringBuilder script = new StringBuilder(preamble());
        for (Role role : Role.values()) {
            if (!roles.contains(role) || masters.contains(role)) continue;
            script.append("cd input/").append(role.folder()).append('\n')
                    .append("convert ").append(role.folder()).append(" -out=../../converted/").append(role.folder()).append('\n')
                    .append("cd ../..\n");
        }
        return script.append("close\n").toString();
    }

    /** Calibrates in the sensor domain, debayers only CFA lights, and writes each star-registered exposure. */
    public static String processing(PreparationRequest request, Map<Role, Integer> counts, Set<Role> masters) {
        StringBuilder script = new StringBuilder(preamble());
        for (Role role : new Role[]{Role.BIASES, Role.DARKS, Role.DARK_FLATS, Role.FLATS}) {
            if (!counts.containsKey(role) || masters.contains(role)) continue;
            script.append("cd converted/").append(role.folder()).append('\n');
            String sequence = role.folder();
            if (role == Role.FLATS && (counts.containsKey(Role.DARK_FLATS) || counts.containsKey(Role.BIASES))) {
                script.append("calibrate flats ");
                if (counts.containsKey(Role.DARK_FLATS)) script.append("-dark=../../masters/darkflats");
                else script.append("-bias=../../masters/biases");
                script.append('\n');
                sequence = "pp_flats";
            }
            script.append("stack ").append(sequence).append(" rej 3 3 ")
                    .append(role == Role.FLATS ? "-norm=mul" : "-nonorm")
                    .append(" -32b -out=../../masters/").append(role.folder()).append("\ncd ../..\n");
        }
        script.append("cd converted/lights\n");
        boolean calibrate = counts.containsKey(Role.DARKS) || counts.containsKey(Role.FLATS)
                || counts.containsKey(Role.BIASES) || request.sensor() == PreparationRequest.Sensor.CFA;
        if (calibrate) {
            script.append("calibrate lights");
            // A dark master contains its bias. Subtracting a separate bias again would double-subtract it.
            if (counts.containsKey(Role.DARKS)) script.append(" -dark=../../masters/darks");
            else if (counts.containsKey(Role.BIASES)) script.append(" -bias=../../masters/biases");
            if (counts.containsKey(Role.FLATS)) script.append(" -flat=../../masters/flats");
            if (request.sensor() == PreparationRequest.Sensor.CFA) script.append(" -cfa -debayer");
            script.append('\n');
        }
        String sequence = calibrate ? "pp_lights" : "lights";
        // Keep the reference-sized canvas and its registration fill; common-area cropping loses drift evidence.
        script.append("register ").append(sequence).append(" -2pass\n")
                .append("seqapplyreg ").append(sequence).append(" -framing=current -interp=linear\n");
        if (request.referenceStack()) {
            script.append("stack r_").append(sequence).append(" rej 3 3 -nonorm -32b -out=../../reference/reference\n");
        }
        return script.append("close\n").toString();
    }

    /** Uses no more workers than the host exposes, including low-core machines and constrained test JVMs. */
    private static String preamble() {
        return "requires 1.4.0\nsetcpu " + Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()))
                + "\nset32bits\nset compression.enabled=false\n";
    }
}
