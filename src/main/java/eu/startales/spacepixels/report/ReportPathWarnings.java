package eu.startales.spacepixels.report;

import java.io.File;
import java.util.Locale;
import java.util.Objects;

/** Windows shell path advice: name-only preflight estimates and worker-only completed-output inspection. */
public final class ReportPathWarnings {
    /** Legacy Windows MAX_PATH includes a terminating NUL character. */
    static final int WINDOWS_PATH_LIMIT = 260;
    private static final String MAX_NUMBER = "2147483647";
    private static final String RUN_SUFFIX = "20000101_000000_" + MAX_NUMBER;
    // Cross product of the longest category folder and longest generated animation basename is deliberate:
    // it bounds every category, ten-digit finding IDs, and ten-digit paginated animation sheet indices.
    static final String MAX_RELATIVE_ASSET = "unclassified_frames/moving_track_" + MAX_NUMBER
            + "_object_centric.gif.sheet-" + MAX_NUMBER + ".png";

    /** No mutable state or filesystem ownership. */
    private ReportPathWarnings() { }

    /** Returns a Windows warning, or null; safe on the EDT, including for a nonexistent dataset directory. */
    public static String beforeDetection(File dataset, boolean iterative) {
        return beforeDetection(dataset, iterative, System.getProperty("os.name", ""));
    }

    /** Explicit operating system seam keeps tests portable without changing JVM properties. */
    static String beforeDetection(File dataset, boolean iterative, String osName) {
        File root = new File(Objects.requireNonNull(dataset, "dataset"),
                (iterative ? "iterative_detections_" : "detections_") + RUN_SUFFIX);
        String warning = outputDirectoryWarning(root, iterative, osName);
        return warning == null ? null : warning.replace("To view this completed report, copy the entire",
                "If the report cannot be opened after completion, copy the entire");
    }

    /** Inspects completed output on a worker, never the EDT; does not follow directory symlinks. */
    public static String afterDetection(File report) {
        return afterDetection(report, System.getProperty("os.name", ""));
    }

    /** Portable worker-side scan; advisory inspection failures cannot turn completed detection into failure. */
    static String afterDetection(File report, String osName) {
        if (report == null || osName == null || !osName.toLowerCase(Locale.ROOT).startsWith("windows")) return null;
        java.nio.file.Path root = report.toPath().toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(root)) root = root.getParent();
        if (root == null) return null;
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
            long pictures = 0;
            long otherFiles = 0;
            int longest = 0;
            java.util.Iterator<java.nio.file.Path> iterator = paths.iterator();
            while (iterator.hasNext()) {
                java.nio.file.Path path = iterator.next();
                if (!java.nio.file.Files.isRegularFile(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                int length = path.toAbsolutePath().toString().length();
                longest = Math.max(longest, length);
                if (length >= WINDOWS_PATH_LIMIT) {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.matches(".*\\.(?:png|gif|jpe?g|webp|svg|bmp|tiff?)")) pictures++;
                    else otherFiles++;
                }
            }
            long affected = pictures + otherFiles;
            if (affected == 0) return null;
            return "Windows path notice: " + pictures + (pictures == 1 ? " picture and " : " pictures and ") + otherFiles
                    + (otherFiles == 1 ? " other report file have" : " other report files have")
                    + " paths of 260 characters or more; the longest is " + longest
                    + " characters. Windows Explorer and some browsers or tools may have trouble opening or copying"
                    + " paths of 260 characters or more. If needed, copy the entire report folder to a shorter"
                    + " location, keeping all files and subfolders together. Relative report links remain valid.";
        } catch (java.io.IOException | java.io.UncheckedIOException | SecurityException failure) {
            java.util.logging.Logger.getLogger(ReportPathWarnings.class.getName()).warning(
                    "Could not inspect completed report paths: " + failure.getMessage());
            return null;
        }
    }

    /** Adds actual counts near the top of completed HTML on its export worker, preserving atomic replacement. */
    public static void addCompletedReportNote(File report, String warning) throws java.io.IOException {
        if (warning == null) return;
        String note = "\n<aside class='astro-note' role='note'>" + ReportFormat.escapeHtml(warning) + "</aside>\n";
        try (AtomicReportFile output = new AtomicReportFile(report);
             java.io.Reader input = java.nio.file.Files.newBufferedReader(report.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            String body = "<body";
            int matched = 0;
            int character;
            while ((character = input.read()) != -1) {
                output.writer().write(character);
                if (matched < body.length()) {
                    matched = character == body.charAt(matched) ? matched + 1 : character == '<' ? 1 : 0;
                } else if (character == '>') {
                    output.writer().print(note);
                    input.transferTo(output.writer());
                    break;
                }
            }
            if (character == -1) return;
            input.close();
            output.commit();
        }
    }

    /** Estimates assets under an existing output directory without walking it; iterative roots include a pass. */
    public static String outputDirectoryWarning(File directory, boolean iterative) {
        return outputDirectoryWarning(directory, iterative, System.getProperty("os.name", ""));
    }

    /** Applies the legacy shell threshold to a conservative absolute asset path, not just the HTML filename. */
    static String outputDirectoryWarning(File directory, boolean iterative, String osName) {
        Objects.requireNonNull(directory, "directory");
        if (osName == null || !osName.toLowerCase(Locale.ROOT).startsWith("windows")) return null;
        File pass = iterative ? new File(directory, MAX_NUMBER + "_frames") : directory;
        String example = new File(pass, MAX_RELATIVE_ASSET).getAbsolutePath();
        if (example.length() < WINDOWS_PATH_LIMIT) return null;
        return "Windows path notice: report asset paths may reach " + example.length()
                + " characters (conservative estimate). Windows Explorer and some browsers or tools may have"
                + " trouble opening or copying paths of 260 characters or more, even when Java writes them successfully."
                + " Use a shorter dataset path for future runs. To view this completed report, copy the entire"
                + " report folder to a shorter location, keeping all files and subfolders together."
                + " This notice does not indicate a detection failure.";
    }
}
