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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Images of one exposure time. Exposures within 2 % (at least 0.1 s) of the shortest one in the group count as the
 * same, because many cameras record the measured exposure, which differs by a fraction of a second from frame to
 * frame (60.0, 60.1 and 60.2 s in one sequence).
 */
public final class ExposureGroup {

    private static final double RELATIVE_TOLERANCE = 0.02;
    private static final long MIN_TOLERANCE_MILLIS = 100;

    private final List<File> files;
    private final long shortestMillis;
    private final long longestMillis;

    private ExposureGroup(List<File> files, long shortestMillis, long longestMillis) {
        files.sort(Comparator.comparing(File::getName));
        this.files = Collections.unmodifiableList(files);
        this.shortestMillis = shortestMillis;
        this.longestMillis = longestMillis;
    }

    /**
     * Groups images by exposure time, the largest group first (the longer exposure first when two groups are the same
     * size, images without an exposure time last). An exposure of -1 means the image has none.
     */
    static List<ExposureGroup> of(File[] files, long[] exposureMillis) {
        Integer[] order = new Integer[files.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingLong(index -> exposureMillis[index]));

        List<ExposureGroup> groups = new ArrayList<>();
        List<File> current = new ArrayList<>();
        long shortest = 0;
        long longest = 0;
        for (int index : order) {
            long exposure = exposureMillis[index];
            if (!current.isEmpty() && !sameExposure(shortest, exposure)) {
                groups.add(new ExposureGroup(current, shortest, longest));
                current = new ArrayList<>();
            }
            if (current.isEmpty()) {
                shortest = exposure;
            }
            current.add(files[index]);
            longest = exposure;
        }
        if (!current.isEmpty()) {
            groups.add(new ExposureGroup(current, shortest, longest));
        }
        groups.sort(Comparator.comparingInt(ExposureGroup::size).reversed()
                .thenComparing(Comparator.comparingLong((ExposureGroup group) -> group.longestMillis).reversed()));
        return groups;
    }

    private static boolean sameExposure(long shortest, long exposure) {
        if (shortest < 0 || exposure < 0) {
            return shortest < 0 && exposure < 0;
        }
        return exposure - shortest <= Math.max(MIN_TOLERANCE_MILLIS, Math.round(shortest * RELATIVE_TOLERANCE));
    }

    public List<File> getFiles() {
        return files;
    }

    public int size() {
        return files.size();
    }

    public boolean hasExposureTime() {
        return shortestMillis >= 0;
    }

    /** For example "60 s", or "60–60.2 s" when the recorded exposures differ slightly. */
    public String getExposureLabel() {
        if (!hasExposureTime()) {
            return "no exposure time";
        }
        String shortest = seconds(shortestMillis);
        String longest = seconds(longestMillis);
        return (shortest.equals(longest) ? shortest : shortest + "–" + longest) + " s";
    }

    /** For example "20 images of 60 s", or "2 images without an exposure time". */
    public String describe() {
        String images = size() + (size() == 1 ? " image" : " images");
        return hasExposureTime() ? images + " of " + getExposureLabel() : images + " without an exposure time";
    }

    /** The subfolder {@link #copyIntoSubfolder} copies the images into, for example "_exposure_60s". */
    public String getFolderName() {
        return hasExposureTime() ? "_exposure_" + seconds(shortestMillis) + "s" : "_no_exposure_time";
    }

    /**
     * Copies the images into {@link #getFolderName()} next to them and returns that folder. Images already there are
     * kept, since they may carry a plate solution written after an earlier copy.
     */
    public File copyIntoSubfolder(IntConsumer progress) throws IOException {
        Path target = files.get(0).getParentFile().toPath().resolve(getFolderName());
        Files.createDirectories(target);
        for (int i = 0; i < files.size(); i++) {
            if (progress != null) {
                progress.accept(100 * i / files.size());
            }
            Path destination = target.resolve(files.get(i).getName());
            if (Files.exists(destination)) {
                continue;
            }
            // Copied under another name first, so an interrupted copy never looks like an image.
            Path partial = target.resolve(files.get(i).getName() + ".part");
            Files.copy(files.get(i).toPath(), partial, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            Files.move(partial, destination);
        }
        return target.toFile();
    }

    private static String seconds(long millis) {
        return BigDecimal.valueOf(millis, 3).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
