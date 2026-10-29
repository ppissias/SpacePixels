package eu.startales.spacepixels.preparation;

import eu.startales.spacepixels.fits.ExposureGroup;
import eu.startales.spacepixels.preparation.PreparationInputs.Role;

import nom.tam.fits.Header;
import nom.tam.fits.TruncatedFileException;
import nom.tam.util.FitsFile;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finds folders whose images are not one set of individual exposures, without reading pixel data. */
public final class PreparationCohort {

    /** One kind of file found together in a role folder. */
    public record Group(String description, List<Path> files) {
        /** Keeps the file list independent of the classifier's working collection. */
        public Group { files = List.copyOf(files); }
        /** The group label shown in the inspection question, including its file count. */
        @Override public String toString() { return description; }
    }

    /** A role whose folder needs an explicit choice before preparation copies anything. */
    public record Question(Role role, List<Group> groups) {
        /** Rejects a trivial partition; a question always offers at least two choices. */
        public Question {
            groups = List.copyOf(groups);
            if (groups.size() < 2) throw new IllegalArgumentException("An inspection question needs more than one group.");
        }

        /** True when the largest group is bigger than everything else combined. */
        public boolean excludeMinority() {
            int rest = 0;
            for (int i = 1; i < groups.size(); i++) rest += groups.get(i).files().size();
            return groups.get(0).files().size() > rest;
        }

        /** Explains the choice in the terms of the largest group, or asks which group to keep. */
        public String prompt() {
            if (excludeMinority()) return groups.get(0).description() + " are the majority in " + role + ". Exclude the other files and continue?";
            return role + " contains more than one kind of image. Choose the group to keep.";
        }

        /** Lists the files that would be left out of a majority, or every group when the observer must choose. */
        public String details() {
            StringBuilder text = new StringBuilder();
            List<Group> shown = excludeMinority() ? groups.subList(1, groups.size()) : groups;
            for (Group group : shown) {
                if (!text.isEmpty()) text.append('\n');
                text.append(group.description()).append('\n');
                int listed = 0;
                for (Path file : group.files()) {
                    if (listed == 12) {
                        text.append("and ").append(group.files().size() - listed).append(" more\n");
                        break;
                    }
                    text.append("  ").append(file.getFileName()).append('\n');
                    listed++;
                }
            }
            return text.toString().strip();
        }

        /** The files outside the group the observer keeps. */
        public List<Path> omittedByKeeping(int group) {
            List<Path> omitted = new ArrayList<>();
            for (int i = 0; i < groups.size(); i++) if (i != group) omitted.addAll(groups.get(i).files());
            return List.copyOf(omitted);
        }
    }

    private enum Family { RAW, FITS, XISF, TIFF }

    /** Header facts needed to tell an individual exposure from a stack or another image family. */
    private record Fact(Path path, Family family, int planes, long exposureMillis, String rowOrder) { }

    /** The classifier has no mutable state. */
    private PreparationCohort() { }

    /** Questions for folder roles only. A calibration master selected as a file is already explicit. */
    public static List<Question> questions(FramePreparationService.Inputs inputs) throws IOException {
        List<Question> questions = new ArrayList<>();
        for (Role role : Role.values()) {
            if (inputs.masters().contains(role) || !inputs.files().containsKey(role)) continue;
            Question question = question(role, inputs.files().get(role));
            if (question != null) questions.add(question);
        }
        return List.copyOf(questions);
    }

    /** Returns the coarsest difference in this folder, or null when the files are one exposure set. */
    static Question question(Role role, List<Path> files) throws IOException {
        List<Fact> facts = new ArrayList<>();
        for (Path file : files) facts.add(fact(file));
        Map<Family, List<Fact>> families = partition(facts, fact -> fact.family);
        if (families.size() > 1) return asked(role, families.values().stream().map(PreparationCohort::familyGroup).toList());
        if (facts.get(0).family != Family.FITS) return null;
        Map<Integer, List<Fact>> planes = partition(facts, fact -> fact.planes <= 1 ? fact.planes : 3);
        if (planes.size() > 1) return asked(role, planes.values().stream().map(PreparationCohort::planeGroup).toList());
        List<List<Fact>> exposures = exposureGroups(facts);
        if (exposures.size() < 2) return null;
        return asked(role, exposures.stream().map(PreparationCohort::exposureGroup).toList());
    }

    /** Sorts the largest group first so a majority offer always names that group. */
    private static Question asked(Role role, List<Group> groups) {
        List<Group> ordered = new ArrayList<>(groups);
        ordered.sort(Comparator.comparingInt((Group group) -> group.files().size()).reversed().thenComparing(Group::description));
        return ordered.size() < 2 ? null : new Question(role, ordered);
    }

    private static Group familyGroup(List<Fact> facts) {
        Family family = facts.get(0).family;
        String kind = switch (family) {
            case RAW -> facts.size() == 1 ? "camera raw exposure" : "camera raw exposures";
            case FITS -> fitsKind(facts) + exposureSuffix(facts);
            case XISF -> facts.size() == 1 ? "XISF image" : "XISF images";
            case TIFF -> facts.size() == 1 ? "TIFF image" : "TIFF images";
        };
        return new Group(facts.size() + " " + kind, paths(facts));
    }

    private static Group planeGroup(List<Fact> facts) {
        return new Group(facts.size() + " " + fitsKind(facts) + exposureSuffix(facts), paths(facts));
    }

    private static Group exposureGroup(List<Fact> facts) {
        return new Group(facts.size() + " " + fitsKind(facts) + exposureSuffix(facts), paths(facts));
    }

    /** Describes FITS files that share a plane count. Mixed or unread planes stay unnamed. */
    private static String fitsKind(List<Fact> facts) {
        int planes = facts.get(0).planes;
        boolean same = facts.stream().allMatch(fact -> (fact.planes <= 1 ? fact.planes : 3) == (planes <= 1 ? planes : 3));
        if (!same || planes <= 0) return facts.size() == 1 ? "FITS image" : "FITS images";
        if (planes == 1) return facts.size() == 1 ? "FITS exposure, 1 plane" : "FITS exposures, 1 plane";
        return (facts.size() == 1 ? "colour FITS image, " : "colour FITS images, ") + planes + " planes";
    }

    private static String exposureSuffix(List<Fact> facts) {
        long minimum = Long.MAX_VALUE;
        long maximum = Long.MIN_VALUE;
        boolean known = false;
        for (Fact fact : facts) {
            if (fact.exposureMillis < 0) continue;
            known = true;
            minimum = Math.min(minimum, fact.exposureMillis);
            maximum = Math.max(maximum, fact.exposureMillis);
        }
        if (!known) return "";
        return ", " + (minimum == maximum ? seconds(minimum) : seconds(minimum) + "–" + seconds(maximum));
    }

    /** Groups known durations with the import tolerance. Unrecorded durations stay together and apart from known ones. */
    private static List<List<Fact>> exposureGroups(List<Fact> facts) {
        List<Fact> ordered = new ArrayList<>(facts);
        ordered.sort(Comparator.comparingLong(Fact::exposureMillis));
        List<List<Fact>> groups = new ArrayList<>();
        List<Fact> current = new ArrayList<>();
        long shortest = 0;
        for (Fact fact : ordered) {
            if (!current.isEmpty() && !ExposureGroup.sameExposure(shortest, fact.exposureMillis)) {
                groups.add(current);
                current = new ArrayList<>();
            }
            if (current.isEmpty()) shortest = fact.exposureMillis;
            current.add(fact);
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private static <K> Map<K, List<Fact>> partition(List<Fact> facts, java.util.function.Function<Fact, K> key) {
        Map<K, List<Fact>> groups = new LinkedHashMap<>();
        for (Fact fact : facts) groups.computeIfAbsent(key.apply(fact), ignored -> new ArrayList<>()).add(fact);
        return groups;
    }

    private static List<Path> paths(List<Fact> facts) {
        return facts.stream().map(Fact::path).toList();
    }

    /** Reads FITS headers with nom.tam. An unreadable file stays in the FITS family with unknown dimensions. */
    private static Fact fact(Path path) {
        Family family = family(path);
        if (family != Family.FITS) return new Fact(path, family, 0, -1, null);
        try { return fitsFact(path); }
        catch (IOException | RuntimeException unread) { return new Fact(path, Family.FITS, 0, -1, null); }
    }

    private static Family family(Path path) {
        String suffix = PreparationInputs.extension(path);
        if (suffix == null) return Family.RAW;
        if (suffix.equals("xisf")) return Family.XISF;
        if (suffix.equals("tif") || suffix.equals("tiff")) return Family.TIFF;
        if (suffix.equals("fit") || suffix.equals("fits") || suffix.equals("fts") || suffix.equals("fz")
                || suffix.endsWith(".fz")) return Family.FITS;
        return Family.RAW;
    }

    /**
     * Reads the image header and skips its data. {@code readHDU} would load the pixels, which is unnecessary
     * for a folder that may contain a large stack.
     */
    private static Fact fitsFact(Path path) throws IOException {
        try (FitsFile file = new FitsFile(path.toFile(), "r")) {
            Header primary = Header.readHeader(file);
            Header image = primary;
            if (primary.getIntValue("NAXIS", 0) == 0) {
                file.skipAllBytes(Math.max(0L, primary.getDataSize()));
                try {
                    Header next = Header.readHeader(file);
                    if (next != null) image = next;
                } catch (EOFException | TruncatedFileException end) { /* An empty primary with no extension keeps that header. */ }
            }
            long exposure = exposure(image);
            if (exposure < 0) exposure = exposure(primary);
            String row = image.getStringValue("ROWORDER");
            if (row == null) row = primary.getStringValue("ROWORDER");
            return new Fact(path, Family.FITS, planes(image), exposure, row == null ? null : row.strip().toUpperCase(Locale.ROOT));
        }
    }

    /** FITS lights whose header does not name a top-down or bottom-up row order. Raw files are not included. */
    public static Set<Path> fitsMissingRowOrder(Iterable<Path> files) {
        Set<Path> missing = new LinkedHashSet<>();
        for (Path file : files) {
            Fact fact = fact(file);
            if (fact.family != Family.FITS) continue;
            if (!"TOP-DOWN".equals(fact.rowOrder) && !"BOTTOM-UP".equals(fact.rowOrder)) missing.add(file.toAbsolutePath().normalize());
        }
        return Set.copyOf(missing);
    }

    /** Tile-compressed images store the table as NAXIS 2; the image shape is ZNAXIS when ZIMAGE is set. */
    private static int planes(Header header) {
        if (header.getBooleanValue("ZIMAGE", false)) {
            int compressed = header.getIntValue("ZNAXIS", 0);
            if (compressed >= 3) return Math.max(header.getIntValue("ZNAXIS3", 1), 1);
            if (compressed == 2) return 1;
        }
        int axes = header.getIntValue("NAXIS", 0);
        if (axes >= 3) return Math.max(header.getIntValue("NAXIS3", 1), 1);
        if (axes == 2) return 1;
        return 0;
    }

    private static long exposure(Header header) {
        double seconds = header.getDoubleValue("EXPTIME", Double.NaN);
        if (!Double.isFinite(seconds)) seconds = header.getDoubleValue("EXPOSURE", Double.NaN);
        if (!Double.isFinite(seconds) || seconds < 0) return -1;
        return (long) (seconds * 1000.0);
    }

    private static String seconds(long millis) {
        String text = String.format(Locale.ROOT, "%.3f", millis / 1000.0).replaceAll("0+$", "").replaceAll("\\.$", "");
        return text + " s";
    }
}
