/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Where the files of a report lie (F19). The report, its findings data and the run's settings stay at the top of the
 * report folder. Pictures of the whole field go to {@value #FIELD}, the pictures of the pipeline diagnostics to
 * {@value #DIAGNOSTICS}, each finding category's pictures and data files to the folder {@link FindingCategory#folder()}
 * names, and the enhanced stack to {@link EnhancedStackFile#FOLDER}.
 * <p>
 * A link is a path relative to the report folder with {@code /} separators, as the report's HTML uses it. File names
 * are unique across the folders, so a downloaded picture still says what it shows, and the report's animation player
 * finds a GIF's metadata by its file name and its poster and frame sheets beside it. A folder is created when it
 * receives its first file.
 */
final class ReportFiles {
    /** Pictures of the whole field: session picture, star mask, detection maps and their backgrounds. */
    static final String FIELD = "field";
    /** Pictures of the pipeline diagnostics: median master stack, its star mask, drift map and corner animation. */
    static final String DIAGNOSTICS = "diagnostics";

    private ReportFiles() {
    }

    /** The link to a file in a folder of the report. */
    static String link(String folder, String name) {
        return folder + "/" + name;
    }

    /** The link to a file in a finding category's folder. */
    static String link(FindingCategory category, String name) {
        return link(category.folder(), name);
    }

    /** The file name a link ends in, for download names. */
    static String name(String link) {
        return link.substring(link.lastIndexOf('/') + 1);
    }

    /**
     * The file a link names in a report folder, with its folder created.
     *
     * @throws IOException when the folder cannot be created
     */
    static File file(File reportFolder, String link) throws IOException {
        File file = new File(reportFolder, link);
        Files.createDirectories(file.getAbsoluteFile().getParentFile().toPath());
        return file;
    }
}
