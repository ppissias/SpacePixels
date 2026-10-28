package eu.startales.spacepixels.preparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves and identifies the explicitly selected Siril CLI without loading personal Siril settings. */
public final class SirilInstallation {
    /** The official download page, opened only by an explicit user action. */
    public static final String WEBSITE = "https://siril.org/download/";

    /** Oldest Siril version supporting the generated preparation recipes; also emitted in their requires command. */
    public static final String MINIMUM_VERSION = "1.4.0";
    private static final Pattern VERSION = Pattern.compile("(?i)\\bsiril(?:-cli)?\\s+(?:version\\s+)?v?(\\d+)\\.(\\d+)\\.(\\d+)\\b");

    /** Stateless installation inspection. */
    private SirilInstallation() { }

    /** Resolves an installation folder or CLI file, refusing missing, graphical or ambiguous candidates. */
    public static Path resolve(Path selected) throws IOException {
        if (selected == null) throw new IOException("Choose the Siril installation or command-line executable.");
        Path path = selected.toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) {
            String name = path.getFileName().toString();
            if (name.equalsIgnoreCase("siril-cli") || name.equalsIgnoreCase("siril-cli.exe")) return path.toRealPath();
            throw new IOException("Choose siril-cli (the command-line application).");
        }
        List<Path> found = new ArrayList<>();
        for (String sub : List.of("", "bin", "Contents/MacOS", "Contents/bin")) {
            for (String name : List.of("siril-cli", "siril-cli.exe")) {
                Path candidate = path.resolve(sub).resolve(name);
                if (Files.isRegularFile(candidate) && !found.contains(candidate.toRealPath())) found.add(candidate.toRealPath());
            }
        }
        if (found.size() == 1) return found.getFirst();
        if (found.isEmpty()) throw new IOException("siril-cli was not found in this installation. Select the CLI file directly.");
        throw new IOException("Several command-line executables were found. Select the file to use.");
    }

    /** A bounded version probe with a private configuration path, without processing any images. */
    public static List<String> probe(Path executable, Path workspace) {
        return List.of(executable.toString(), "-i", workspace.resolve("siril.cfg").toString(), "-v");
    }

    /** Recognizes Siril and refuses versions older than the generated recipes support, for both GUI and worker probes. */
    public static String describeProbe(String output) throws IOException {
        for (String line : output.split("\\R")) {
            String clean = line.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "").strip();
            Matcher version = VERSION.matcher(clean);
            if (!version.find()) continue;
            String[] minimum = MINIMUM_VERSION.split("\\.");
            int comparison = 0;
            try {
                for (int i = 0; i < minimum.length && comparison == 0; i++)
                    comparison = Integer.compare(Integer.parseInt(version.group(i + 1)), Integer.parseInt(minimum[i]));
            } catch (NumberFormatException invalid) {
                throw new IOException("Cannot read the selected Siril version.", invalid);
            }
            if (comparison < 0) throw new IOException("Siril " + MINIMUM_VERSION + " or newer is required for frame preparation; found " + clean + ".");
            return clean;
        }
        throw new IOException("The selected program did not identify a Siril version.");
    }
}
