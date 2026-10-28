package eu.startales.spacepixels.preparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** External preparation applications and the explicit CLI executable used for each run. */
public enum PreparationTool {
    SIRIL("Siril", "siril-cli", "https://siril.org/download/"),
    DSS("DeepSkyStacker", "DeepSkyStackerCL", "https://github.com/deepskystacker/DSS/releases");

    private final String label;
    private final String executable;
    private final String website;

    /** Defines presentation and executable identity, without a machine-specific installation path. */
    PreparationTool(String label, String executable, String website) {
        this.label = label;
        this.executable = executable;
        this.website = website;
    }

    /** The application name displayed to the observer. */
    @Override public String toString() { return label; }

    /** The official download page, opened only by an explicit user action. */
    public String website() { return website; }

    /** Resolves a selected installation folder or CLI file, refusing missing or ambiguous candidates. */
    public Path resolve(Path selected) throws IOException {
        if (selected == null) throw new IOException("Choose the " + label + " installation or command-line executable.");
        Path path = selected.toAbsolutePath().normalize();
        if (Files.isRegularFile(path)) {
            String name = path.getFileName().toString();
            if (name.equalsIgnoreCase(executable) || name.equalsIgnoreCase(executable + ".exe")) return path.toRealPath();
            throw new IOException("Choose " + executable + " (the command-line application).");
        }
        List<Path> found = new ArrayList<>();
        for (String sub : List.of("", "bin", "Contents/MacOS", "Contents/bin")) {
            for (String name : List.of(executable, executable + ".exe")) {
                Path candidate = path.resolve(sub).resolve(name);
                if (Files.isRegularFile(candidate) && !found.contains(candidate.toRealPath())) found.add(candidate.toRealPath());
            }
        }
        if (found.size() == 1) return found.getFirst();
        if (found.isEmpty()) throw new IOException(executable + " was not found in this installation. Select the CLI file directly.");
        throw new IOException("Several command-line executables were found. Select the file to use.");
    }

    /** A bounded, non-processing command used to check the chosen CLI; DSS intentionally prints usage. */
    public List<String> probe(Path executable, Path workspace) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        if (this == SIRIL) command.addAll(List.of("-i", workspace.resolve("siril.cfg").toString(), "-v"));
        return List.copyOf(command);
    }

    /** Recognizes the expected program's probe response independently of its help-command exit code. */
    public String describeProbe(String output) throws IOException {
        String lower = output.toLowerCase(Locale.ROOT);
        String expected = this == SIRIL ? "siril" : "deepskystacker";
        for (String line : output.split("\\R")) {
            if (line.toLowerCase(Locale.ROOT).contains(expected) && line.matches(".*\\d+\\.\\d+.*")) {
                return line.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "").strip();
            }
        }
        throw new IOException(lower.contains(expected) ? "The CLI responded, but its version could not be identified."
                : "The selected program did not identify itself as " + label + ".");
    }
}
