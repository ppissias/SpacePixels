package eu.startales.spacepixels.preparation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Conservative file inventories and bounded session-role discovery, without decoding arbitrary camera RAW data. */
public final class PreparationInputs {
    private static final Set<String> RAW = Set.of("cr2", "cr3", "crw", "nef", "nrw", "arw", "sr2", "dng", "raf", "orf", "rw2", "pef", "raw");

    /** Calibration roles are explicit, rather than inferred from image filename extensions. */
    public enum Role {
        LIGHTS("Lights", "lights"), DARKS("Darks", "darks"), FLATS("Flats", "flats"), BIASES("Biases", "biases"), DARK_FLATS("Dark-flats", "darkflats");
        private final String label;
        private final String folder;
        /** Names one input role and its isolated working folder. */
        Role(String label, String folder) { this.label = label; this.folder = folder; }
        /** The role label displayed in the input form. */
        @Override public String toString() { return label; }
        /** The stable ASCII role name used in generated tool recipes. */
        public String folder() { return folder; }
    }

    /** A detached inventory; candidate extensions do not establish decodability or calibration compatibility. */
    public record Inventory(List<Path> files, int rawCandidates, int ignored) {
        /** Protects the file ordering captured for this inspection. */
        public Inventory { files = List.copyOf(files); }
    }

    /** One independently selectable image folder or existing calibration master; counts refer to filename candidates only. */
    public record Candidate(Path path, int images, boolean master) {
        /** Normalizes a captured path before it leaves the scanning worker. */
        public Candidate { path = path.toAbsolutePath().normalize(); }
    }

    /** Detached scan choices preserve separate targets, cameras, filters and exposure groups. */
    public record Scan(Map<Role, List<Candidate>> candidates) {
        /** Copies both collection levels so a window receives a stable scan result. */
        public Scan {
            Map<Role, List<Candidate>> copy = new EnumMap<>(Role.class);
            candidates.forEach((role, choices) -> copy.put(role, List.copyOf(choices)));
            candidates = Map.copyOf(copy);
        }

        /** Only one unambiguous exposure folder is preselected; existing masters always require an explicit choice. */
        public Map<Role, Path> suggestions() {
            Map<Role, Path> selected = new EnumMap<>(Role.class);
            candidates.forEach((role, choices) -> {
                if (choices.size() == 1 && !choices.getFirst().master()) selected.put(role, choices.getFirst().path());
            });
            return Map.copyOf(selected);
        }
    }

    /** No application or session state is needed to inventory folders. */
    private PreparationInputs() { }

    /** Lists immediate regular image candidates in deterministic filename order; links are never followed. */
    public static Inventory inspect(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Choose an existing input folder: " + folder);
        List<Path> files = new ArrayList<>();
        int raw = 0;
        int ignored = 0;
        try (var entries = Files.list(folder)) {
            for (Path entry : entries.sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                String suffix = extension(entry);
                if (suffix != null) { files.add(entry.toAbsolutePath().normalize()); if (RAW.contains(suffix)) raw++; }
                else ignored++;
            }
        }
        return new Inventory(files, raw, ignored);
    }

    /** Suggests only a single recognized exposure group per role; ambiguous groups and masters remain explicit choices. */
    public static Map<Role, Path> suggest(Path root) throws IOException {
        return scan(root).suggestions();
    }

    /** Finds populated role folders through four directory levels, including nested filter/exposure groups, without merging them. */
    public static Scan scan(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Choose an existing session folder.");
        Map<Role, List<Candidate>> result = new EnumMap<>(Role.class);
        Map<Path, Role> inherited = new HashMap<>();
        Files.walkFileTree(root, Set.of(), 5, new SimpleFileVisitor<>() {
            /** Inherits a role through group containers, but skips known processed outputs before inspecting their files. */
            @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) throws IOException {
                String name = folder.getFileName() == null ? "" : folder.getFileName().toString();
                if (processedFolder(name)) return FileVisitResult.SKIP_SUBTREE;
                Role role = roleName(name);
                if (role == null && !folder.equals(root)) role = inherited.get(folder.getParent());
                if (role != null) inherited.put(folder, role);
                Inventory inventory = inspect(folder);
                if (role != null && !inventory.files().isEmpty()) {
                    List<Path> masters = role == Role.LIGHTS ? List.of() : inventory.files().stream().filter(PreparationInputs::namedMaster).toList();
                    if (masters.isEmpty()) result.computeIfAbsent(role, ignored -> new ArrayList<>()).add(new Candidate(folder, inventory.files().size(), false));
                    else for (Path master : masters) if (fitsFile(master)) result.computeIfAbsent(role, ignored -> new ArrayList<>()).add(new Candidate(master, 1, true));
                } else if (role == null) {
                    for (Path file : inventory.files()) {
                        Role masterRole = roleName(file.getFileName().toString().replaceFirst("(?i)\\.(?:fits?|fts)(?:\\.fz)?$", ""));
                        if (namedMaster(file) && fitsFile(file) && masterRole != null && masterRole != Role.LIGHTS)
                            result.computeIfAbsent(masterRole, ignored -> new ArrayList<>()).add(new Candidate(file, 1, true));
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        result.values().forEach(choices -> choices.sort(Comparator.comparing(choice -> choice.path().toString())));
        return new Scan(result);
    }

    /** Recognizes conventional role prefixes/suffixes and compact filter/number suffixes without guessing arbitrary target names. */
    private static Role roleName(String original) {
        String name = original.toLowerCase(Locale.ROOT).replaceAll("[-_\\s]+", " ").strip();
        name = name.replaceFirst("^master\\s*", "");
        if (name.matches("(?:.* )?(?:dark ?flats?|flat ?darks?)(?: .*|[0-9].*)?")) return Role.DARK_FLATS;
        for (Role role : List.of(Role.LIGHTS, Role.DARKS, Role.FLATS, Role.BIASES)) {
            String word = switch (role) { case LIGHTS -> "lights?"; case DARKS -> "darks?"; case FLATS -> "flats?"; default -> "(?:bias(?:es)?|offsets?)"; };
            if (name.matches(word + "(?: .*|[bgrl]|ha|oiii|sii|[0-9].*|m[0-9].*|ngc[0-9].*)?") || name.matches(".* " + word)) return role;
        }
        return null;
    }

    /** Excludes processing trees, including role-prefixed aligned/calibrated folders, before assigning inherited roles. */
    private static boolean processedFolder(String original) {
        String name = original.toLowerCase(Locale.ROOT);
        return name.equals("siril") || name.equals("_siril") || name.equals("iris")
                || name.startsWith("spacepixels_prepared_") || name.startsWith("detections_")
                || name.matches("(?:.*[ _-])?(?:registered|aligned|calibrated|processed|reg)(?:[ _-].*)?");
    }

    /** A FITS master must be selected as a file. A TIFF or other product with "master" in its name is not one. */
    static boolean namedMaster(Path file) {
        return fitsFile(file) && file.getFileName().toString().toLowerCase(Locale.ROOT).matches("(?:master.*|.*[ _-]master(?:[ _.-].*)?)");
    }

    /** Identifies the existing-master formats accepted by the preparation service. */
    private static boolean fitsFile(Path file) {
        return Set.of("fit", "fits", "fts", "fz", "fit.fz", "fits.fz", "fts.fz").contains(extension(file));
    }

    /** Recognizes image filename families, including compound FITS suffixes, without promising decoder support. */
    public static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String suffix : List.of("fits.fz", "fit.fz", "fts.fz", "fits", "fit", "fts", "fz", "xisf", "tif", "tiff")) {
            if (name.endsWith("." + suffix)) return suffix;
        }
        int dot = name.lastIndexOf('.');
        String suffix = dot < 0 ? "" : name.substring(dot + 1);
        return RAW.contains(suffix) ? suffix : null;
    }
}
