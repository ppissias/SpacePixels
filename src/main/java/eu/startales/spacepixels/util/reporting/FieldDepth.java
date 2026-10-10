/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyProjection;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How deep each frame goes. In four small patches of the field, the Gaia stars down to magnitude 20.5 are matched
 * with the stars found there with the run's own detection settings. A patch where even the brighter stars are mostly
 * missed (a large galaxy, nebula or dense cluster covers it) is left out. Over the other patches, the depth is the
 * Gaia G magnitude at which the share of stars found falls to half of the best share among the brighter stars; in a
 * crowded field blended stars keep that best share below 100 %. Clouds, haze or a bright sky show as a frame that
 * goes less deep.
 */
final class FieldDepth {

    /** Stars per group (sorted by magnitude) at least, and the most groups. */
    private static final int MIN_GROUP_STARS = 20;
    private static final int MAX_GROUPS = 40;
    /** A Gaia star counts as found when a detection is this close (pixels), after the local offset is removed. */
    private static final double MATCH_RADIUS = 2.5;
    /** A patch is used when at least half of its brightest stars are found; they also give its local offset. */
    private static final int BRIGHTEST = 10;
    private static final int OFFSET_STARS = 20;

    /** The depth of one frame. */
    static final class Frame {
        final int index;
        final String fileName;
        /** Gaia G at which half as many stars are found as among the brighter ones; NaN when it could not be measured. */
        final double depth;
        /** True when even the faintest stars fetched are mostly found, so the frame goes deeper than {@link #depth}. */
        final boolean deeperThanSample;

        Frame(int index, String fileName, double depth, boolean deeperThanSample) {
            this.index = index;
            this.fileName = fileName;
            this.depth = depth;
            this.deeperThanSample = deeperThanSample;
        }
    }

    final List<Frame> frames = new ArrayList<>();
    double sampleLimit;
    double patchArcmin;
    int patchCount;

    /** The median depth over the frames measured; NaN when none was. */
    double median() {
        List<Double> values = new ArrayList<>();
        for (Frame frame : frames) {
            if (!Double.isNaN(frame.depth)) {
                values.add(frame.depth);
            }
        }
        if (values.isEmpty()) {
            return Double.NaN;
        }
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    boolean isEmpty() {
        return Double.isNaN(median());
    }

    /** One patch on the frame: its Gaia stars in frame pixels, and the part of the frame it covers. */
    private static final class Patch {
        final List<double[]> stars = new ArrayList<>(); // x, y, G
        int x0;
        int y0;
        int x1;
        int y1;
    }

    /** Measures the kept frames; empty without a catalogue with depth samples, a plate solution or frame data. */
    static FieldDepth measure(DetectionReportContext context, PipelineTelemetry telemetry) {
        FieldDepth result = new FieldDepth();
        SkyCatalogue catalogue = SessionCatalogue.of(context).catalogue;
        WcsCoordinateTransformer transformer = context.astrometryContext != null ? context.astrometryContext.getTransformer() : null;
        if (catalogue == null || catalogue.depthSamples.isEmpty() || transformer == null || context.rawFrames.isEmpty() || telemetry == null) {
            return result;
        }
        long timestamp = context.fitsFiles.length > 0 ? context.fitsFiles[0].getObservationTimestamp() : 0;
        int width = context.rawFrames.get(0)[0].length;
        int height = context.rawFrames.get(0).length;
        List<Patch> patches = new ArrayList<>();
        for (SkyCatalogue.DepthSample sample : catalogue.depthSamples) {
            Patch patch = new Patch();
            List<double[]> all = new ArrayList<>();
            for (SkyCatalogue.Star star : sample.stars) {
                double[] p = SkyProjection.starPixel(transformer, star, timestamp);
                if (p != null && p[0] >= 15 && p[1] >= 15 && p[0] < width - 15 && p[1] < height - 15) {
                    all.add(new double[]{p[0], p[1], star.g});
                }
            }
            if (all.size() < 30) {
                continue;
            }
            patch.stars.addAll(isolated(all));
            patch.x0 = Integer.MAX_VALUE;
            patch.y0 = Integer.MAX_VALUE;
            for (double[] s : all) {
                patch.x0 = Math.min(patch.x0, (int) Math.floor(s[0]) - 10);
                patch.y0 = Math.min(patch.y0, (int) Math.floor(s[1]) - 10);
                patch.x1 = Math.max(patch.x1, (int) Math.ceil(s[0]) + 10);
                patch.y1 = Math.max(patch.y1, (int) Math.ceil(s[1]) + 10);
            }
            patch.x0 = Math.max(0, patch.x0);
            patch.y0 = Math.max(0, patch.y0);
            patch.x1 = Math.min(width - 1, patch.x1);
            patch.y1 = Math.min(height - 1, patch.y1);
            patches.add(patch);
            result.sampleLimit = sample.magnitudeLimit;
            result.patchArcmin = sample.sideDeg * 60;
        }
        result.patchCount = patches.size();
        if (patches.isEmpty()) {
            return result;
        }
        DetectionConfig config = patchConfig(context.config);
        for (PipelineTelemetry.FrameQualityStat stat : telemetry.frameQualityStats) {
            if (stat.rejected || stat.frameIndex < 0 || stat.frameIndex >= context.rawFrames.size()) {
                continue;
            }
            short[][] data = context.rawFrames.get(stat.frameIndex);
            if (data == null || data.length != height) {
                continue;
            }
            List<double[]> stars = new ArrayList<>(); // G, 1 when found
            for (Patch patch : patches) {
                count(patch, data, config, stars);
            }
            double[] depth = depth(stars);
            result.frames.add(new Frame(stat.frameIndex, stat.filename, depth[0], depth[1] > 0));
        }
        return result;
    }

    /**
     * The run's detection settings for a small cut-out of a frame: the same thresholds, but the checks against the
     * frame borders scaled back, since the borders of a cut-out are not empty sky (a void radius grown for the drift
     * would reject every star of a small patch).
     */
    static DetectionConfig patchConfig(DetectionConfig config) {
        com.google.gson.Gson gson = new com.google.gson.Gson();
        DetectionConfig copy = gson.fromJson(gson.toJson(config), DetectionConfig.class);
        DetectionConfig defaults = new DetectionConfig();
        copy.voidProximityRadius = Math.min(copy.voidProximityRadius, defaults.voidProximityRadius);
        copy.edgeMarginPixels = Math.min(copy.edgeMarginPixels, 5);
        return copy;
    }

    /** Adds the stars of a patch, with whether each was found; leaves the patch out when its brightest stars are mostly missed. */
    private static void count(Patch patch, short[][] data, DetectionConfig config, List<double[]> stars) {
        if (patch.x1 - patch.x0 < 40 || patch.y1 - patch.y0 < 40) {
            return;
        }
        short[][] crop = new short[patch.y1 - patch.y0 + 1][];
        for (int y = patch.y0; y <= patch.y1; y++) {
            crop[y - patch.y0] = Arrays.copyOfRange(data[y], patch.x0, patch.x1 + 1);
        }
        List<double[]> detections = new ArrayList<>();
        for (SourceExtractor.DetectedObject object : SourceExtractor.extractSources(crop, config.detectionSigmaMultiplier,
                config.minDetectionPixels, config).objects) {
            if (!object.isStreak) {
                detections.add(new double[]{object.x + patch.x0, object.y + patch.y0});
            }
        }
        Map<Long, List<double[]>> grid = grid(detections);
        // The local offset between the catalogue and the frame, from the brightest stars, which any frame finds.
        List<double[]> brightest = new ArrayList<>(patch.stars);
        brightest.sort((a, b) -> Double.compare(a[2], b[2]));
        List<Double> dxs = new ArrayList<>();
        List<Double> dys = new ArrayList<>();
        int brightestFound = 0;
        for (int i = 0; i < Math.min(OFFSET_STARS, brightest.size()); i++) {
            double[] s = brightest.get(i);
            double[] nearest = nearest(grid, s[0], s[1], 8);
            if (nearest != null) {
                dxs.add(nearest[0] - s[0]);
                dys.add(nearest[1] - s[1]);
                if (i < BRIGHTEST) {
                    brightestFound++;
                }
            }
        }
        if (brightest.size() < BRIGHTEST || brightestFound < BRIGHTEST / 2 || dxs.size() < 5) {
            return; // a galaxy, nebula or dense cluster covers the patch, or clouds
        }
        Collections.sort(dxs);
        Collections.sort(dys);
        double dx = dxs.get(dxs.size() / 2);
        double dy = dys.get(dys.size() / 2);
        for (double[] s : patch.stars) {
            stars.add(new double[]{s[2], nearest(grid, s[0] + dx, s[1] + dy, MATCH_RADIUS) != null ? 1 : 0});
        }
    }

    /**
     * The depth from the stars {G, 1 when found}, as {G, 1 when the faintest stars are still above the half}. The stars
     * are sorted by magnitude and grouped, each group with enough stars to give a share; the depth is the magnitude,
     * after the group with the best share, at which the share falls to half of that best share. NaN when it cannot
     * be told (too few stars, or even the best share is small).
     */
    static double[] depth(List<double[]> stars) {
        List<double[]> sorted = new ArrayList<>(stars);
        sorted.sort((a, b) -> Double.compare(a[0], b[0]));
        int size = Math.max(MIN_GROUP_STARS, (int) Math.ceil(sorted.size() / (double) MAX_GROUPS));
        List<double[]> groups = new ArrayList<>(); // median G, share found
        for (int from = 0; from + size <= sorted.size(); from += size) {
            int found = 0;
            for (int i = from; i < from + size; i++) {
                found += (int) sorted.get(i)[1];
            }
            groups.add(new double[]{sorted.get(from + size / 2)[0], found / (double) size});
        }
        int bestGroup = -1;
        double best = 0;
        for (int g = 0; g < groups.size(); g++) {
            if (groups.get(g)[1] > best) {
                best = groups.get(g)[1];
                bestGroup = g;
            }
        }
        if (bestGroup < 0 || best < 0.3) {
            return new double[]{Double.NaN, 0};
        }
        double half = best / 2;
        for (int g = bestGroup + 1; g < groups.size(); g++) {
            double[] previous = groups.get(g - 1);
            double[] group = groups.get(g);
            if (group[1] < half) {
                double t = (previous[1] - half) / (previous[1] - group[1]);
                return new double[]{previous[0] + t * (group[0] - previous[0]), 0};
            }
        }
        return new double[]{groups.get(groups.size() - 1)[0], 1};
    }

    /** The stars without a star of similar or greater brightness within 4 pixels, whose detection could be the neighbour's. */
    private static List<double[]> isolated(List<double[]> stars) {
        Map<Long, List<double[]>> grid = grid(stars);
        List<double[]> isolated = new ArrayList<>();
        for (double[] s : stars) {
            boolean alone = true;
            for (int i = (int) Math.floor((s[0] - 4) / CELL); i <= (int) Math.floor((s[0] + 4) / CELL) && alone; i++) {
                for (int j = (int) Math.floor((s[1] - 4) / CELL); j <= (int) Math.floor((s[1] + 4) / CELL) && alone; j++) {
                    for (double[] o : grid.getOrDefault(key(i, j), List.of())) {
                        if (o != s && o[2] < s[2] + 1.0 && Math.abs(o[0] - s[0]) < 4 && Math.abs(o[1] - s[1]) < 4) {
                            alone = false;
                            break;
                        }
                    }
                }
            }
            if (alone) {
                isolated.add(s);
            }
        }
        return isolated;
    }

    private static final double CELL = 8;

    private static Map<Long, List<double[]>> grid(List<double[]> points) {
        Map<Long, List<double[]>> cells = new HashMap<>();
        for (double[] p : points) {
            cells.computeIfAbsent(key((int) Math.floor(p[0] / CELL), (int) Math.floor(p[1] / CELL)), k -> new ArrayList<>()).add(p);
        }
        return cells;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static double[] nearest(Map<Long, List<double[]>> cells, double x, double y, double radius) {
        double[] best = null;
        double bestDistance = radius;
        for (int i = (int) Math.floor((x - radius) / CELL); i <= (int) Math.floor((x + radius) / CELL); i++) {
            for (int j = (int) Math.floor((y - radius) / CELL); j <= (int) Math.floor((y + radius) / CELL); j++) {
                List<double[]> cell = cells.get(key(i, j));
                if (cell == null) {
                    continue;
                }
                for (double[] p : cell) {
                    double distance = Math.hypot(p[0] - x, p[1] - y);
                    if (distance <= bestDistance) {
                        best = p;
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }
}
