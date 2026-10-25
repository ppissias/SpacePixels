/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.report;

import java.io.File;

/** The reports earlier runs wrote beside a session's frames. */
public final class SessionReports {

    private SessionReports() {
    }

    /**
     * The newest finished report in {@code folder}: a {@code detections_} folder's report page or an
     * {@code iterative_detections_} folder's index, by the time it was written. Null when there is none.
     */
    public static File latest(File folder) {
        File[] children = folder == null ? null : folder.listFiles(File::isDirectory);
        if (children == null) {
            return null;
        }
        File latest = null;
        for (File child : children) {
            String name = child.getName();
            File page = name.startsWith("detections_") ? new File(child, ReportExporter.REPORT_FILE_NAME)
                    : name.startsWith("iterative_detections_") ? new File(child, "index.html") : null;
            if (page != null && page.isFile() && (latest == null || page.lastModified() > latest.lastModified())) {
                latest = page;
            }
        }
        return latest;
    }

    /** Whether {@code report} is the index of an iterative run. */
    public static boolean isIterative(File report) {
        return report != null && report.getParentFile() != null
                && report.getParentFile().getName().startsWith("iterative_detections_");
    }

    /** Resolves a completed report's output directory, refusing missing reports and detached parent paths. */
    public static File outputDirectory(File report) {
        if (report == null || !report.isFile()) return null;
        File parent = report.getAbsoluteFile().getParentFile();
        return parent != null && parent.isDirectory() ? parent : null;
    }
}
