/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The images of a sequence do not all have the same exposure time. Carries them grouped by exposure, the largest group
 * first, so the SpacePixels window can offer to import only the largest group.
 */
public final class MixedExposureException extends IOException {

    private static final int LISTED_GROUPS = 6;
    private static final int LISTED_NAMES = 3;

    private final transient List<ExposureGroup> groups;

    MixedExposureException(List<ExposureGroup> groups) {
        super(message(groups));
        this.groups = List.copyOf(groups);
    }

    public List<ExposureGroup> getGroups() {
        return groups;
    }

    public ExposureGroup getLargestGroup() {
        return groups.get(0);
    }

    /** Whether the largest group has more than one image, so that importing it alone still makes a sequence. */
    public boolean canImportLargestGroup() {
        return getLargestGroup().size() > 1;
    }

    public int getImageCount() {
        return groups.stream().mapToInt(ExposureGroup::size).sum();
    }

    /**
     * The groups for a message, for example "20 images of 60 s", "2 images of 120 s (C.fit, D.fit)": up to six groups,
     * with up to three file names for each group but the largest.
     */
    public static List<String> describeGroups(List<ExposureGroup> groups) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(groups.size(), LISTED_GROUPS); i++) {
            ExposureGroup group = groups.get(i);
            lines.add(group.describe() + (i == 0 ? "" : " (" + names(group.getFiles()) + ")"));
        }
        if (groups.size() > LISTED_GROUPS) {
            lines.add("and " + (groups.size() - LISTED_GROUPS) + " other exposure times");
        }
        return lines;
    }

    private static String names(List<File> files) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(files.size(), LISTED_NAMES); i++) {
            text.append(i == 0 ? "" : ", ").append(files.get(i).getName());
        }
        if (files.size() > LISTED_NAMES) {
            text.append(" and ").append(files.size() - LISTED_NAMES).append(" more");
        }
        return text.toString();
    }

    private static String message(List<ExposureGroup> groups) {
        ExposureGroup largest = groups.get(0);
        String advice = largest.size() > 1
                ? "Move the other images to another folder, or import the folder in the SpacePixels window, which offers"
                + " to copy the " + largest.describe() + " into a subfolder and import those."
                : "Import a sequence of images that share one exposure time.";
        return "All images must have the same exposure time, but these have " + groups.size() + " different ones: "
                + String.join(", ", describeGroups(groups)) + ". " + advice;
    }
}
