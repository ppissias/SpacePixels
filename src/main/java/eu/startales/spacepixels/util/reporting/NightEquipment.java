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

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyProjection;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What the frames say about the night and the equipment: seeing and focus, transparency and sky brightness per frame;
 * the pixel scale and focal length; and, across the field, the star sizes and shapes (field curvature, tilt, coma),
 * the star brightness against Gaia and the sky background (flat-field check).
 * <p>
 * The stars are the isolated stars of the master star map, measured at the same place in every kept frame (the frames
 * are aligned), on small cut-outs with {@link StarShape}. Frames taken on the other side of the pier are turned back
 * by 180 degrees for the field maps, since tilt, coma and vignetting belong to the camera, not to the sky.
 */
final class NightEquipment {

    /** Stars measured at most, spread over the field. */
    private static final int MAX_STARS = 2000;
    /** Flux aperture radius in units of each star's own size, so large and small stars lose the same share of light. */
    private static final double APERTURE_FACTOR = 2.2;
    /** Field maps: rows of cells (the columns follow the frame's shape). */
    private static final int MAP_ROWS = 5;
    /** Background boxes: rows (the columns follow the frame's shape). */
    private static final int BACKGROUND_ROWS = 10;
    /** Signal-to-noise needed for the size and shape, the brightness, and the coma offset. */
    private static final double SHAPE_SNR = 30;
    private static final double FLUX_SNR = 20;
    private static final double COMA_SNR = 80;
    /** Stars within this part of the half-diagonal give the frame's centre size (seeing). */
    private static final double CENTRE_RADIUS = 0.35;

    /** One kept frame. */
    static final class Frame {
        int index;
        String fileName;
        long timestamp;
        double exposureSeconds = Double.NaN;
        boolean flipped;
        int stars;
        /** Median star size near the centre, pixels. */
        double seeingPx = Double.NaN;
        /** Brightness of the stars against the session's median frame, magnitudes per second (positive is brighter). */
        double relativeZeroPoint = Double.NaN;
        /** How much fainter the stars are than in the clearest frame, magnitudes. */
        double dimming = Double.NaN;
        /** Gaia G of a star giving 1 unit of flux in this frame. */
        double zeroPoint = Double.NaN;
        /** Sky level per pixel (0..65535). */
        double background = Double.NaN;
        /** Sky brightness, Gaia G magnitudes per square arcsecond. */
        double skyBrightness = Double.NaN;
        /** Focuser or ambient temperature from the FITS header, degrees C. */
        double temperature = Double.NaN;
        /** Median ellipticity of all stars (tracking, guiding, wind). */
        double e1 = Double.NaN;
        double e2 = Double.NaN;
    }

    /** One star measured in one frame, in camera orientation (flipped frames turned back). */
    private static final class Sample {
        int frame;
        int star;
        double u;
        double v;
        double x;
        double y;
        StarShape shape;
        boolean saturated;
        /** Magnitudes dimmer than Gaia predicts, when calibrated. */
        double dim;
        boolean hasDim;
    }

    final List<Frame> frames = new ArrayList<>();
    int width;
    int height;
    int starsSelected;
    long millis;
    final List<String> notes = new ArrayList<>();

    // Setup
    double pixelScale = Double.NaN;
    double cameraAngle = Double.NaN;
    double headerFocalLength = Double.NaN;
    double pixelSizeMicrons = Double.NaN;
    int binning = 1;
    double measuredFocalLength = Double.NaN;
    String temperatureSource;
    boolean pierFlip;

    // Field maps: [row][column], camera orientation; NaN where a cell has too few stars.
    int rows;
    int columns;
    double[][] relativeSize;
    double[][] sizeArcsecOrPx;
    double[][] shapeE1;
    double[][] shapeE2;
    double[][] comaX;
    double[][] comaY;
    double[][] dimming;
    int backgroundRows;
    int backgroundColumns;
    double[][] background;

    // Readings
    double centreSizePx = Double.NaN;
    /** Star size in the corners against the centre, as a fraction (0.12 = 12 % larger), from the bowl fit. */
    double cornerGrowth = Double.NaN;
    /** Star size in each corner against the centre (top left, top right, bottom left, bottom right), as fractions. */
    double[] cornerSizes;
    /** Star size difference between the right and left edges, and between the bottom and top edges, as fractions. */
    double sizeTiltX = Double.NaN;
    double sizeTiltY = Double.NaN;
    /** Ellipticity shared by all stars (session median of the frames): tracking, guiding, wind. */
    double trackingE1 = Double.NaN;
    double trackingE2 = Double.NaN;
    /** Ellipticity along the line to the centre in the outer field (negative: across it). */
    double radialEllipticity = Double.NaN;
    /** Coma: offset of the light from the core, pixels per pixel from the zero-coma point, its error, and that point. */
    double comaSlope = Double.NaN;
    double comaSlopeError = Double.NaN;
    double comaZeroX = Double.NaN;
    double comaZeroY = Double.NaN;
    /** Coma offset at the centre of the frame (constant part), pixels. */
    double comaCentreX = Double.NaN;
    double comaCentreY = Double.NaN;
    // Calibration against Gaia
    int calibrationStars;
    double colourTerm = Double.NaN;
    double linearity = Double.NaN;
    boolean calibrated;
    /** Stars in the corners against the centre, magnitudes (positive: dimmer), and the side-to-side differences. */
    double cornerDimming = Double.NaN;
    double dimmingTiltX = Double.NaN;
    double dimmingTiltY = Double.NaN;
    /** Sky background in the corners against the centre, and the side-to-side differences, as fractions. */
    double backgroundCorner = Double.NaN;
    double backgroundTiltX = Double.NaN;
    double backgroundTiltY = Double.NaN;
    /** The faintest Gaia G that saturates. */
    double saturationMagnitude = Double.NaN;

    boolean isEmpty() {
        return frames.isEmpty() || Double.isNaN(centreSizePx);
    }

    boolean hasPixelScale() {
        return !Double.isNaN(pixelScale);
    }

    /** Measures the session for the report; empty when it cannot be measured, so the report is still written. */
    static NightEquipment measure(DetectionReportContext context, PipelineTelemetry telemetry, List<SourceExtractor.DetectedObject> masterStars) {
        long start = System.currentTimeMillis();
        try {
            List<Integer> kept = new ArrayList<>();
            if (telemetry != null && !telemetry.frameQualityStats.isEmpty()) {
                for (PipelineTelemetry.FrameQualityStat stat : telemetry.frameQualityStats) {
                    if (!stat.rejected && stat.frameIndex >= 0 && stat.frameIndex < context.rawFrames.size()) {
                        kept.add(stat.frameIndex);
                    }
                }
            } else {
                for (int i = 0; i < context.rawFrames.size(); i++) {
                    kept.add(i);
                }
            }
            WcsCoordinateTransformer transformer = context.astrometryContext != null ? context.astrometryContext.getTransformer() : null;
            SkyCatalogue catalogue = SessionCatalogue.of(context).catalogue;
            NightEquipment result = measure(context.rawFrames, kept, context.fitsFiles, masterStars, transformer, catalogue);
            result.millis = System.currentTimeMillis() - start;
            return result;
        } catch (RuntimeException e) {
            System.err.println("Failed to measure the night and equipment: " + e);
            NightEquipment empty = new NightEquipment();
            empty.millis = System.currentTimeMillis() - start;
            return empty;
        }
    }

    static NightEquipment measure(List<short[][]> rawFrames, List<Integer> kept, FitsFileInformation[] files,
                                  List<SourceExtractor.DetectedObject> masterStars, WcsCoordinateTransformer transformer,
                                  SkyCatalogue catalogue) {
        NightEquipment result = new NightEquipment();
        if (rawFrames.isEmpty() || kept.isEmpty() || masterStars == null || masterStars.isEmpty()) {
            return result;
        }
        short[][] first = rawFrames.get(kept.get(0));
        result.height = first.length;
        result.width = first[0].length;
        result.readSetup(files, transformer);

        // The stars: isolated, spread over the field, brightest first.
        double fwhmHint = medianFwhm(masterStars);
        int radius = (int) Math.max(8, Math.min(36, Math.ceil(3.5 * fwhmHint) + 3));
        List<SourceExtractor.DetectedObject> stars = selectStars(masterStars, result.width, result.height, radius);
        result.starsSelected = stars.size();
        if (stars.size() < 20) {
            result.notes.add("Too few isolated stars to measure.");
            return result;
        }
        result.backgroundRows = BACKGROUND_ROWS;
        result.backgroundColumns = Math.max(4, (int) Math.round(BACKGROUND_ROWS * result.width / (double) result.height));
        String majoritySide = majorityPierSide(files, kept);

        // The frames are independent: measured in parallel, a few at a time (frames read on demand take memory).
        FrameWork[] work = new FrameWork[kept.size()];
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        java.util.concurrent.ForkJoinPool pool = new java.util.concurrent.ForkJoinPool(threads);
        try {
            pool.submit(() -> java.util.stream.IntStream.range(0, kept.size()).parallel().forEach(k ->
                    work[k] = result.measureFrame(rawFrames, kept.get(k), files, stars, radius, fwhmHint, majoritySide))).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result;
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } finally {
            pool.shutdown();
        }
        List<Sample> samples = new ArrayList<>();
        List<double[][]> backgroundBoxes = new ArrayList<>();
        for (FrameWork frameWork : work) {
            if (frameWork == null) {
                continue;
            }
            int frameNumber = result.frames.size();
            for (Sample sample : frameWork.samples) {
                sample.frame = frameNumber;
            }
            result.frames.add(frameWork.frame);
            result.pierFlip |= frameWork.frame.flipped;
            samples.addAll(frameWork.samples);
            backgroundBoxes.add(frameWork.boxes);
        }
        if (result.frames.isEmpty()) {
            result.notes.add("No frame had enough measurable stars.");
            return result;
        }

        result.measureTransparency(samples, stars.size());
        result.calibrate(samples, stars, catalogue, transformer, files, kept);
        result.mapField(samples, backgroundBoxes);
        return result;
    }

    /** One frame's result before it is numbered. */
    private static final class FrameWork {
        Frame frame;
        List<Sample> samples;
        double[][] boxes;
    }

    /** Measures every star of one frame, and its sky background; null when the frame cannot be measured. */
    private FrameWork measureFrame(List<short[][]> rawFrames, int index, FitsFileInformation[] files,
                                   List<SourceExtractor.DetectedObject> stars, int radius, double fwhmHint, String majoritySide) {
        short[][] data = rawFrames.get(index);
        if (data == null || data.length != height || data[0].length != width) {
            return null;
        }
        Frame frame = new Frame();
        frame.index = index;
        FitsFileInformation file = files != null && index < files.length ? files[index] : null;
        if (file != null) {
            frame.fileName = file.getFileName();
            frame.timestamp = file.getObservationTimestamp();
            long exposure = file.getExposureDurationMillis();
            frame.exposureSeconds = exposure > 0 ? exposure / 1000.0 : Double.NaN;
            frame.temperature = temperatureSource != null ? headerNumber(file, temperatureSource) : Double.NaN;
            String side = pierSide(file);
            frame.flipped = majoritySide != null && side != null && !side.equals(majoritySide);
        }
        double halfDiagonal = Math.hypot(width, height) / 2;
        double centreX = (width - 1) / 2.0;
        double centreY = (height - 1) / 2.0;
        double peakMax = 0;
        List<Sample> frameSamples = new ArrayList<>();
        for (int s = 0; s < stars.size(); s++) {
            SourceExtractor.DetectedObject star = stars.get(s);
            StarShape shape = StarShape.measure(data, star.x, star.y, radius, fwhmHint, APERTURE_FACTOR);
            if (shape == null || shape.snr < FLUX_SNR) {
                continue;
            }
            Sample sample = new Sample();
            sample.star = s;
            sample.shape = shape;
            double x = shape.x;
            double y = shape.y;
            if (frame.flipped) {
                x = width - 1 - x;
                y = height - 1 - y;
                shape.offsetX = -shape.offsetX;
                shape.offsetY = -shape.offsetY;
            }
            sample.x = x;
            sample.y = y;
            sample.u = (x - centreX) / halfDiagonal;
            sample.v = (y - centreY) / halfDiagonal;
            peakMax = Math.max(peakMax, shape.peak);
            frameSamples.add(sample);
        }
        for (Sample sample : frameSamples) {
            StarShape shape = sample.shape;
            sample.saturated = shape.peak >= 65000 || (shape.flatTopPixels >= 3 && shape.peak > 0.5 * peakMax);
        }
        // A star close to the saturation level is no longer linear.
        List<Double> flatTops = new ArrayList<>();
        for (Sample sample : frameSamples) {
            if (sample.saturated) {
                flatTops.add(sample.shape.peak);
            }
        }
        if (!flatTops.isEmpty()) {
            double saturationLevel = median(flatTops);
            for (Sample sample : frameSamples) {
                if (sample.shape.peak > 0.85 * saturationLevel) {
                    sample.saturated = true;
                }
            }
        }
        frame.stars = frameSamples.size();
        List<Double> centreSizes = new ArrayList<>();
        List<Double> allSizes = new ArrayList<>();
        List<Double> e1 = new ArrayList<>();
        List<Double> e2 = new ArrayList<>();
        for (Sample sample : frameSamples) {
            if (sample.saturated || sample.shape.snr < SHAPE_SNR) {
                continue;
            }
            allSizes.add(sample.shape.fwhm);
            if (Math.hypot(sample.u, sample.v) < CENTRE_RADIUS) {
                centreSizes.add(sample.shape.fwhm);
            }
            e1.add(sample.shape.e1);
            e2.add(sample.shape.e2);
        }
        if (allSizes.size() < 10) {
            return null;
        }
        frame.seeingPx = median(centreSizes.size() >= 10 ? centreSizes : allSizes);
        frame.e1 = median(e1);
        frame.e2 = median(e2);
        FrameWork result = new FrameWork();
        result.frame = frame;
        result.samples = frameSamples;
        result.boxes = backgroundBoxes(data, backgroundRows, backgroundColumns, frame.flipped);
        frame.background = medianOf(result.boxes);
        return result;
    }

    // ==========================================
    // SETUP
    // ==========================================

    private void readSetup(FitsFileInformation[] files, WcsCoordinateTransformer transformer) {
        if (transformer != null) {
            double cx = width / 2.0;
            double cy = height / 2.0;
            WcsCoordinateTransformer.SkyCoordinate c = transformer.pixelToSky(cx, cy);
            WcsCoordinateTransformer.SkyCoordinate px = transformer.pixelToSky(cx + 1, cy);
            WcsCoordinateTransformer.SkyCoordinate py = transformer.pixelToSky(cx, cy + 1);
            if (c != null && px != null && py != null) {
                double cosDec = Math.cos(Math.toRadians(c.getDecDegrees()));
                double xiX = wrap(px.getRaDegrees() - c.getRaDegrees()) * cosDec;
                double etaX = px.getDecDegrees() - c.getDecDegrees();
                double xiY = wrap(py.getRaDegrees() - c.getRaDegrees()) * cosDec;
                double etaY = py.getDecDegrees() - c.getDecDegrees();
                pixelScale = Math.sqrt(Math.abs(xiX * etaY - xiY * etaX)) * 3600;
                // The frame's +y axis (FITS rows upwards), east of north, as plate solvers report it.
                cameraAngle = (Math.toDegrees(Math.atan2(xiY, etaY)) + 360) % 360;
            }
        }
        FitsFileInformation file = files != null && files.length > 0 ? files[0] : null;
        if (file == null) {
            return;
        }
        headerFocalLength = headerNumber(file, "FOCALLEN");
        pixelSizeMicrons = headerNumber(file, "XPIXSZ");
        double bin = headerNumber(file, "XBINNING");
        binning = Double.isNaN(bin) || bin < 1 ? 1 : (int) Math.round(bin);
        if (!(headerFocalLength > 0)) {
            headerFocalLength = Double.NaN;
        }
        if (!(pixelSizeMicrons > 0)) {
            pixelSizeMicrons = Double.NaN;
        }
        if (hasPixelScale() && !Double.isNaN(pixelSizeMicrons)) {
            measuredFocalLength = 206.264806 * pixelSizeMicrons / pixelScale;
        } else if (!hasPixelScale() && !Double.isNaN(pixelSizeMicrons) && !Double.isNaN(headerFocalLength)) {
            pixelScale = 206.264806 * pixelSizeMicrons / headerFocalLength;
            notes.add("No plate solution: the pixel scale comes from the focal length and pixel size in the FITS header.");
        }
        for (String key : new String[]{"FOCTEMP", "AMBTEMP"}) {
            if (!Double.isNaN(headerNumber(file, key))) {
                temperatureSource = key;
                break;
            }
        }
    }

    private static double wrap(double degrees) {
        double d = degrees % 360;
        if (d > 180) {
            d -= 360;
        } else if (d < -180) {
            d += 360;
        }
        return d;
    }

    static double headerNumber(FitsFileInformation file, String key) {
        Map<String, String> header = file.getFitsHeader();
        String value = header != null ? header.get(key) : null;
        if (value == null) {
            return Double.NaN;
        }
        String text = value.replace("'", "").trim();
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash).trim();
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String pierSide(FitsFileInformation file) {
        Map<String, String> header = file.getFitsHeader();
        String value = header != null ? header.get("PIERSIDE") : null;
        if (value == null) {
            return null;
        }
        String side = value.replace("'", "").trim().toUpperCase(Locale.ROOT);
        return side.startsWith("EAST") ? "EAST" : side.startsWith("WEST") ? "WEST" : null;
    }

    private static String majorityPierSide(FitsFileInformation[] files, List<Integer> kept) {
        if (files == null) {
            return null;
        }
        int east = 0;
        int west = 0;
        for (int index : kept) {
            String side = index < files.length && files[index] != null ? pierSide(files[index]) : null;
            if ("EAST".equals(side)) {
                east++;
            } else if ("WEST".equals(side)) {
                west++;
            }
        }
        if (east == 0 && west == 0) {
            return null;
        }
        return east >= west ? "EAST" : "WEST";
    }

    // ==========================================
    // STARS
    // ==========================================

    private static double medianFwhm(List<SourceExtractor.DetectedObject> stars) {
        List<Double> sizes = new ArrayList<>();
        for (SourceExtractor.DetectedObject star : stars) {
            if (!star.isStreak && !star.isNoise && star.fwhm > 0 && Double.isFinite(star.fwhm)) {
                sizes.add(star.fwhm);
            }
        }
        return sizes.isEmpty() ? 3 : Math.max(1.2, median(sizes));
    }

    /** Isolated stars away from the edges, the brightest of each part of the field, at most {@link #MAX_STARS}. */
    private static List<SourceExtractor.DetectedObject> selectStars(List<SourceExtractor.DetectedObject> masterStars,
                                                                    int width, int height, int radius) {
        List<SourceExtractor.DetectedObject> candidates = new ArrayList<>();
        for (SourceExtractor.DetectedObject star : masterStars) {
            if (!star.isStreak && !star.isNoise && star.totalFlux > 0 && star.x >= radius + 2 && star.y >= radius + 2
                    && star.x < width - radius - 3 && star.y < height - radius - 3) {
                candidates.add(star);
            }
        }
        double cell = radius + 2;
        Map<Long, List<SourceExtractor.DetectedObject>> grid = new HashMap<>();
        for (SourceExtractor.DetectedObject star : masterStars) {
            grid.computeIfAbsent(key((int) Math.floor(star.x / cell), (int) Math.floor(star.y / cell)), k -> new ArrayList<>()).add(star);
        }
        List<SourceExtractor.DetectedObject> isolated = new ArrayList<>();
        for (SourceExtractor.DetectedObject star : candidates) {
            boolean alone = true;
            int gx = (int) Math.floor(star.x / cell);
            int gy = (int) Math.floor(star.y / cell);
            for (int i = gx - 1; i <= gx + 1 && alone; i++) {
                for (int j = gy - 1; j <= gy + 1 && alone; j++) {
                    for (SourceExtractor.DetectedObject other : grid.getOrDefault(key(i, j), List.of())) {
                        if (other != star && Math.hypot(other.x - star.x, other.y - star.y) < cell
                                && other.totalFlux > 0.01 * star.totalFlux) {
                            alone = false;
                            break;
                        }
                    }
                }
            }
            if (alone) {
                isolated.add(star);
            }
        }
        isolated.sort((a, b) -> Double.compare(b.totalFlux, a.totalFlux));
        int selectRows = 12;
        int selectColumns = Math.max(4, (int) Math.round(selectRows * width / (double) height));
        int perCell = Math.max(3, (int) Math.ceil(MAX_STARS / (double) (selectRows * selectColumns)));
        int[] counts = new int[selectRows * selectColumns];
        List<SourceExtractor.DetectedObject> selected = new ArrayList<>();
        for (SourceExtractor.DetectedObject star : isolated) {
            int c = Math.min(selectColumns - 1, (int) (star.x / width * selectColumns));
            int r = Math.min(selectRows - 1, (int) (star.y / height * selectRows));
            if (counts[r * selectColumns + c] < perCell) {
                counts[r * selectColumns + c]++;
                selected.add(star);
            }
        }
        return selected;
    }

    // ==========================================
    // TRANSPARENCY AND CALIBRATION
    // ==========================================

    /** The brightness of the stars in each frame against the same stars in the median frame, per second of exposure. */
    private void measureTransparency(List<Sample> samples, int starCount) {
        double[][] magnitudes = new double[starCount][frames.size()];
        for (double[] row : magnitudes) {
            Arrays.fill(row, Double.NaN);
        }
        for (Sample sample : samples) {
            if (!sample.saturated && sample.shape.flux > 0) {
                double seconds = frames.get(sample.frame).exposureSeconds;
                double perSecond = sample.shape.flux / (Double.isNaN(seconds) ? 1 : seconds);
                magnitudes[sample.star][sample.frame] = -2.5 * Math.log10(perSecond);
            }
        }
        double[] reference = new double[starCount];
        for (int s = 0; s < starCount; s++) {
            List<Double> values = new ArrayList<>();
            for (double m : magnitudes[s]) {
                if (!Double.isNaN(m)) {
                    values.add(m);
                }
            }
            reference[s] = values.size() >= Math.max(2, frames.size() / 2) ? median(values) : Double.NaN;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (int f = 0; f < frames.size(); f++) {
            List<Double> differences = new ArrayList<>();
            for (int s = 0; s < starCount; s++) {
                if (!Double.isNaN(reference[s]) && !Double.isNaN(magnitudes[s][f])) {
                    differences.add(reference[s] - magnitudes[s][f]);
                }
            }
            if (differences.size() >= 10) {
                frames.get(f).relativeZeroPoint = median(differences);
                best = Math.max(best, frames.get(f).relativeZeroPoint);
            }
        }
        for (Frame frame : frames) {
            frame.dimming = Double.isNaN(frame.relativeZeroPoint) ? Double.NaN : best - frame.relativeZeroPoint;
        }
    }

    /** One star matched with Gaia. */
    private static final class GaiaMatch {
        double g;
        double colour;
    }

    /**
     * Matches the stars with Gaia and fits, per frame, the zero point (with one colour term for the session); checks
     * that the brightness follows Gaia over the magnitudes (linear data); then gives the sky brightness of each frame.
     */
    private void calibrate(List<Sample> samples, List<SourceExtractor.DetectedObject> stars, SkyCatalogue catalogue,
                           WcsCoordinateTransformer transformer, FitsFileInformation[] files, List<Integer> kept) {
        if (catalogue == null || transformer == null || catalogue.stars.isEmpty()) {
            return;
        }
        long timestamp = files != null && !kept.isEmpty() && kept.get(0) < files.length && files[kept.get(0)] != null
                ? files[kept.get(0)].getObservationTimestamp() : 0;
        GaiaMatch[] matches = matchGaia(stars, catalogue, transformer, timestamp, width, height);
        int matched = 0;
        for (GaiaMatch match : matches) {
            if (match != null) {
                matched++;
            }
        }
        calibrationStars = matched;
        if (matched < 20) {
            notes.add("Too few stars matched with Gaia to calibrate the brightness.");
            return;
        }

        // Saturation: the faintest Gaia magnitude that saturates in most frames.
        int[] saturatedCount = new int[stars.size()];
        int[] seenCount = new int[stars.size()];
        for (Sample sample : samples) {
            seenCount[sample.star]++;
            if (sample.saturated) {
                saturatedCount[sample.star]++;
            }
        }
        // In half-magnitude steps: the faintest step in which most stars saturate (one odd star does not count).
        List<double[]> byMagnitude = new ArrayList<>(); // G, 1 when saturated in most frames
        for (int s = 0; s < stars.size(); s++) {
            if (matches[s] != null && seenCount[s] > 0) {
                byMagnitude.add(new double[]{matches[s].g, saturatedCount[s] * 2 > seenCount[s] ? 1 : 0});
            }
        }
        double faintestSaturated = Double.NaN;
        for (double step = 4; step < 20; step += 0.5) {
            int count = 0;
            int saturated = 0;
            for (double[] star : byMagnitude) {
                if (star[0] >= step && star[0] < step + 0.5) {
                    count++;
                    saturated += (int) star[1];
                }
            }
            if (count >= 3 && saturated * 2 >= count) {
                faintestSaturated = step + 0.5;
            }
        }
        saturationMagnitude = faintestSaturated;

        List<Sample> usable = new ArrayList<>();
        for (Sample sample : samples) {
            if (!sample.saturated && sample.shape.flux > 0 && matches[sample.star] != null && sample.shape.snr >= FLUX_SNR) {
                usable.add(sample);
            }
        }
        if (usable.size() < 20) {
            return;
        }
        double[] zeroPoints = new double[frames.size()];
        double colour = 0;
        for (int iteration = 0; iteration < 4; iteration++) {
            List<List<Double>> perFrame = new ArrayList<>();
            for (int f = 0; f < frames.size(); f++) {
                perFrame.add(new ArrayList<>());
            }
            for (Sample sample : usable) {
                GaiaMatch match = matches[sample.star];
                perFrame.get(sample.frame).add(match.g + 2.5 * Math.log10(sample.shape.flux) - colour * colourOrZero(match));
            }
            for (int f = 0; f < frames.size(); f++) {
                zeroPoints[f] = perFrame.get(f).size() >= 5 ? median(perFrame.get(f)) : Double.NaN;
            }
            // Colour term: residual against BP-RP, with outliers clipped.
            List<double[]> points = new ArrayList<>();
            for (Sample sample : usable) {
                GaiaMatch match = matches[sample.star];
                if (!Double.isNaN(zeroPoints[sample.frame]) && !Double.isNaN(match.colour)) {
                    points.add(new double[]{match.colour, match.g + 2.5 * Math.log10(sample.shape.flux) - zeroPoints[sample.frame]});
                }
            }
            double fitted = robustSlope(points);
            if (!Double.isNaN(fitted)) {
                colour = Math.max(-1, Math.min(1, fitted));
            }
        }
        colourTerm = colour;

        // Linearity: Gaia G against the instrumental magnitude has a slope of 1 for linear data. The line of each frame
        // also gives each star's residual below, so a slight non-linearity does not show up as a pattern on the field.
        List<Double> slopes = new ArrayList<>();
        double[][] lines = new double[frames.size()][];
        boolean[] linearFrames = new boolean[frames.size()];
        for (int f = 0; f < frames.size(); f++) {
            List<double[]> points = new ArrayList<>();
            double low = Double.POSITIVE_INFINITY;
            double high = Double.NEGATIVE_INFINITY;
            for (Sample sample : usable) {
                if (sample.frame == f) {
                    GaiaMatch match = matches[sample.star];
                    points.add(new double[]{-2.5 * Math.log10(sample.shape.flux), match.g - colour * colourOrZero(match)});
                    low = Math.min(low, match.g);
                    high = Math.max(high, match.g);
                }
            }
            lines[f] = robustLine(points);
            if (lines[f] != null && high - low >= 2) {
                slopes.add(lines[f][1]);
                linearFrames[f] = Double.isFinite(lines[f][1]) && Math.abs(lines[f][1] - 1) <= 0.12;
            }
        }
        linearity = slopes.isEmpty() ? Double.NaN : median(slopes);
        calibrated = !Double.isNaN(linearity) && Math.abs(linearity - 1) <= 0.12;
        if (!calibrated) {
            notes.add(Double.isFinite(linearity) ? String.format(Locale.US, "The star brightness does not follow Gaia in proportion (slope %.2f instead of 1): "
                    + "the frames are probably stretched or not linear, so the sky brightness and the flat-field check are left out.", linearity)
                    : "The Gaia matches do not span enough brightness to validate linearity; sky brightness and the flat-field check are unavailable.");
            return;
        }
        for (int f = 0; f < frames.size(); f++) {
            if (!linearFrames[f]) continue;
            Frame frame = frames.get(f);
            frame.zeroPoint = zeroPoints[f];
            if (!Double.isNaN(zeroPoints[f]) && hasPixelScale() && frame.background > 0) {
                frame.skyBrightness = zeroPoints[f] - 2.5 * Math.log10(frame.background / (pixelScale * pixelScale));
            }
        }
        // Each star against its Gaia magnitude: positive when it is dimmer than expected.
        for (Sample sample : usable) {
            if (!linearFrames[sample.frame]) continue;
            GaiaMatch match = matches[sample.star];
            double[] line = lines[sample.frame];
            if (line != null) {
                double expected = line[0] + line[1] * -2.5 * Math.log10(sample.shape.flux) + colour * colourOrZero(match);
                sample.dim = expected - match.g;
                sample.hasDim = true;
            }
        }
    }

    private static double colourOrZero(GaiaMatch match) {
        return Double.isNaN(match.colour) ? 0 : match.colour;
    }

    /** Each star's Gaia match (or null): the nearest Gaia star, after the local offset is removed, with no bright neighbour. */
    private static GaiaMatch[] matchGaia(List<SourceExtractor.DetectedObject> stars, SkyCatalogue catalogue,
                                         WcsCoordinateTransformer transformer, long timestamp, int width, int height) {
        List<double[]> gaia = new ArrayList<>(); // x, y, G, BP-RP
        for (SkyCatalogue.Star star : catalogue.stars) {
            double[] p = SkyProjection.starPixel(transformer, star, timestamp);
            if (p != null && p[0] >= -10 && p[1] >= -10 && p[0] < width + 10 && p[1] < height + 10) {
                gaia.add(new double[]{p[0], p[1], star.g, star.bpRp != null ? star.bpRp : Double.NaN});
            }
        }
        double cell = 10;
        Map<Long, List<double[]>> grid = new HashMap<>();
        for (double[] p : gaia) {
            grid.computeIfAbsent(key((int) Math.floor(p[0] / cell), (int) Math.floor(p[1] / cell)), k -> new ArrayList<>()).add(p);
        }
        // First pass: the local offset in 3 x 3 parts of the frame, from wide matches.
        List<List<Double>> dxs = new ArrayList<>();
        List<List<Double>> dys = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            dxs.add(new ArrayList<>());
            dys.add(new ArrayList<>());
        }
        for (SourceExtractor.DetectedObject star : stars) {
            double[] nearest = nearest(grid, cell, star.x, star.y, 8);
            if (nearest != null) {
                int part = part(star.x, star.y, width, height);
                dxs.get(part).add(star.x - nearest[0]);
                dys.get(part).add(star.y - nearest[1]);
            }
        }
        double[] offsetX = new double[9];
        double[] offsetY = new double[9];
        for (int i = 0; i < 9; i++) {
            offsetX[i] = dxs.get(i).size() >= 5 ? median(dxs.get(i)) : 0;
            offsetY[i] = dys.get(i).size() >= 5 ? median(dys.get(i)) : 0;
        }
        GaiaMatch[] matches = new GaiaMatch[stars.size()];
        for (int s = 0; s < stars.size(); s++) {
            SourceExtractor.DetectedObject star = stars.get(s);
            int part = part(star.x, star.y, width, height);
            double x = star.x - offsetX[part];
            double y = star.y - offsetY[part];
            double[] nearest = nearest(grid, cell, x, y, 2.5);
            if (nearest == null) {
                continue;
            }
            // A neighbour within 8 px brighter than 2.5 magnitudes fainter adds more than 10 % to the light.
            boolean blended = false;
            for (double[] other : near(grid, cell, nearest[0], nearest[1], 8)) {
                if (other != nearest && other[2] < nearest[2] + 2.5) {
                    blended = true;
                    break;
                }
            }
            if (!blended) {
                GaiaMatch match = new GaiaMatch();
                match.g = nearest[2];
                match.colour = nearest[3];
                matches[s] = match;
            }
        }
        return matches;
    }

    private static int part(double x, double y, int width, int height) {
        int c = Math.min(2, (int) (x / width * 3));
        int r = Math.min(2, (int) (y / height * 3));
        return r * 3 + c;
    }

    // ==========================================
    // FIELD MAPS
    // ==========================================

    private void mapField(List<Sample> samples, List<double[][]> backgroundBoxes) {
        rows = MAP_ROWS;
        columns = Math.max(3, (int) Math.round(MAP_ROWS * width / (double) height));
        List<List<Double>> size = cells();
        List<List<Double>> sizeAbsolute = cells();
        List<List<Double>> shape1 = cells();
        List<List<Double>> shape2 = cells();
        List<List<Double>> offsetX = cells();
        List<List<Double>> offsetY = cells();
        List<List<Double>> dim = cells();
        List<Double> centreSizes = new ArrayList<>();
        List<Double> e1 = new ArrayList<>();
        List<Double> e2 = new ArrayList<>();
        for (Frame frame : frames) {
            if (!Double.isNaN(frame.e1)) {
                e1.add(frame.e1);
                e2.add(frame.e2);
            }
            centreSizes.add(frame.seeingPx);
        }
        centreSizePx = median(centreSizes);
        trackingE1 = median(e1);
        trackingE2 = median(e2);
        for (Sample sample : samples) {
            if (sample.saturated) {
                continue;
            }
            Frame frame = frames.get(sample.frame);
            int cell = cell(sample.x, sample.y);
            if (sample.hasDim) {
                dim.get(cell).add(sample.dim);
            }
            if (sample.shape.snr < SHAPE_SNR || Double.isNaN(frame.seeingPx)) {
                continue;
            }
            size.get(cell).add(sample.shape.fwhm / frame.seeingPx);
            sizeAbsolute.get(cell).add(sample.shape.fwhm);
            // The shape that all stars of the frame share (tracking) is taken out.
            shape1.get(cell).add(sample.shape.e1 - frame.e1);
            shape2.get(cell).add(sample.shape.e2 - frame.e2);
            if (sample.shape.snr >= COMA_SNR) {
                offsetX.get(cell).add(sample.shape.offsetX);
                offsetY.get(cell).add(sample.shape.offsetY);
            }
        }
        relativeSize = medians(size, 8);
        sizeArcsecOrPx = medians(sizeAbsolute, 8);
        if (hasPixelScale()) {
            for (double[] row : sizeArcsecOrPx) {
                for (int c = 0; c < row.length; c++) {
                    row[c] *= pixelScale;
                }
            }
        }
        shapeE1 = medians(shape1, 8);
        shapeE2 = medians(shape2, 8);
        comaX = medians(offsetX, 8);
        comaY = medians(offsetY, 8);
        dimming = medians(dim, 8);

        // Curvature and tilt: size = a + b u + c v + d (u^2 + v^2), u and v in half-diagonals from the centre.
        cornerSizes = corners(relativeSize);
        double[] sizeFit = fitBowl(relativeSize, counts(size));
        if (sizeFit != null) {
            cornerGrowth = sizeFit[3] / sizeFit[0];
            sizeTiltX = 2 * sizeFit[1] * (width / Math.hypot(width, height)) / sizeFit[0];
            sizeTiltY = 2 * sizeFit[2] * (height / Math.hypot(width, height)) / sizeFit[0];
        }
        // Elongation along the line to the centre, in the outer field.
        double sum = 0;
        double weight = 0;
        int[][] shapeCounts = counts(shape1);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                double[] uv = cellCentre(r, c);
                double distance = Math.hypot(uv[0], uv[1]);
                if (distance < 0.55 || Double.isNaN(shapeE1[r][c])) {
                    continue;
                }
                double phi = Math.atan2(uv[1], uv[0]);
                double radial = shapeE1[r][c] * Math.cos(2 * phi) + shapeE2[r][c] * Math.sin(2 * phi);
                sum += radial * shapeCounts[r][c];
                weight += shapeCounts[r][c];
            }
        }
        radialEllipticity = weight > 0 ? sum / weight : Double.NaN;
        fitComa(counts(offsetX));
        if (calibrated) {
            double[] dimFit = fitBowl(dimming, counts(dim));
            if (dimFit != null) {
                cornerDimming = dimFit[3];
                dimmingTiltX = 2 * dimFit[1] * (width / Math.hypot(width, height));
                dimmingTiltY = 2 * dimFit[2] * (height / Math.hypot(width, height));
                // Shown against the centre of the field.
                for (double[] row : dimming) {
                    for (int c = 0; c < row.length; c++) {
                        row[c] -= dimFit[0];
                    }
                }
            }
        } else {
            dimming = null;
        }
        mapBackground(backgroundBoxes);
    }

    /**
     * Each corner of a map (top left, top right, bottom left, bottom right) minus 1: the corner cell, or the median of
     * the 2 x 2 cells at the corner when it has too few stars; null when a corner has none.
     */
    private static double[] corners(double[][] map) {
        int rows = map.length;
        int columns = map[0].length;
        int[][] at = {{0, 0}, {0, columns - 1}, {rows - 1, 0}, {rows - 1, columns - 1}};
        double[] result = new double[4];
        for (int k = 0; k < 4; k++) {
            int r = at[k][0];
            int c = at[k][1];
            double value = map[r][c];
            if (Double.isNaN(value)) {
                List<Double> block = new ArrayList<>();
                int dr = r == 0 ? 1 : -1;
                int dc = c == 0 ? 1 : -1;
                for (double v : new double[]{map[r + dr][c], map[r][c + dc], map[r + dr][c + dc]}) {
                    if (!Double.isNaN(v)) {
                        block.add(v);
                    }
                }
                value = median(block);
            }
            if (Double.isNaN(value)) {
                return null;
            }
            result[k] = value - 1;
        }
        return result;
    }

    /** Coma: offset = k (position - zero point), fitted to the cell medians; k is pixels of offset per pixel. */
    private void fitComa(int[][] counts) {
        List<double[]> rowsX = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double halfDiagonal = Math.hypot(width, height) / 2;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                if (Double.isNaN(comaX[r][c])) {
                    continue;
                }
                double[] uv = cellCentre(r, c);
                double x = uv[0] * halfDiagonal;
                double y = uv[1] * halfDiagonal;
                rowsX.add(new double[]{x, 1, 0});
                values.add(comaX[r][c]);
                weights.add((double) counts[r][c]);
                rowsX.add(new double[]{y, 0, 1});
                values.add(comaY[r][c]);
                weights.add((double) counts[r][c]);
            }
        }
        if (rowsX.size() < 12) {
            return;
        }
        double[][] design = rowsX.toArray(new double[0][]);
        double[] y = toArray(values);
        double[] w = toArray(weights);
        double[] fit = leastSquares(design, y, w);
        if (fit == null) {
            return;
        }
        // The error of k from the scatter of the cells around the fit.
        double residual = 0;
        double sumW = 0;
        double sxx = 0;
        for (int i = 0; i < design.length; i++) {
            double model = fit[0] * design[i][0] + fit[1] * design[i][1] + fit[2] * design[i][2];
            residual += w[i] * (y[i] - model) * (y[i] - model);
            sumW += w[i];
            sxx += w[i] * design[i][0] * design[i][0];
        }
        double variance = residual / Math.max(1e-9, sumW) * design.length / Math.max(1, design.length - 3);
        comaSlope = fit[0];
        comaSlopeError = Math.sqrt(variance / Math.max(1e-9, sxx / sumW) / design.length);
        comaCentreX = fit[1];
        comaCentreY = fit[2];
        if (Math.abs(fit[0]) > 1e-9) {
            comaZeroX = (width - 1) / 2.0 - fit[1] / fit[0];
            comaZeroY = (height - 1) / 2.0 - fit[2] / fit[0];
        }
    }

    private void mapBackground(List<double[][]> boxes) {
        if (boxes.isEmpty()) {
            return;
        }
        List<List<Double>> perBox = new ArrayList<>();
        for (int i = 0; i < backgroundRows * backgroundColumns; i++) {
            perBox.add(new ArrayList<>());
        }
        for (int f = 0; f < boxes.size(); f++) {
            double level = medianOf(boxes.get(f));
            if (!(level > 0)) {
                continue;
            }
            for (int r = 0; r < backgroundRows; r++) {
                for (int c = 0; c < backgroundColumns; c++) {
                    double value = boxes.get(f)[r][c];
                    if (!Double.isNaN(value)) {
                        perBox.get(r * backgroundColumns + c).add(value / level - 1);
                    }
                }
            }
        }
        background = new double[backgroundRows][backgroundColumns];
        int[][] counts = new int[backgroundRows][backgroundColumns];
        for (int r = 0; r < backgroundRows; r++) {
            for (int c = 0; c < backgroundColumns; c++) {
                List<Double> values = perBox.get(r * backgroundColumns + c);
                background[r][c] = values.isEmpty() ? Double.NaN : median(values);
                counts[r][c] = values.size();
            }
        }
        double[] fit = fitBowl(background, counts, backgroundRows, backgroundColumns);
        if (fit != null) {
            backgroundCorner = fit[3];
            backgroundTiltX = 2 * fit[1] * (width / Math.hypot(width, height));
            backgroundTiltY = 2 * fit[2] * (height / Math.hypot(width, height));
        }
    }

    /**
     * The sky level in boxes over the frame (sigma-clipped medians, pixel units), in camera orientation; NaN for boxes
     * with empty (padded) pixels.
     */
    private static double[][] backgroundBoxes(short[][] data, int boxRows, int boxColumns, boolean flipped) {
        int height = data.length;
        int width = data[0].length;
        int box = Math.max(8, Math.min(48, Math.min(width / boxColumns, height / boxRows) / 2));
        double[][] result = new double[boxRows][boxColumns];
        double[] values = new double[box * box];
        for (int r = 0; r < boxRows; r++) {
            for (int c = 0; c < boxColumns; c++) {
                int cx = (int) ((c + 0.5) * width / boxColumns);
                int cy = (int) ((r + 0.5) * height / boxRows);
                int n = 0;
                boolean empty = false;
                for (int y = cy - box / 2; y < cy - box / 2 + box && !empty; y++) {
                    for (int x = cx - box / 2; x < cx - box / 2 + box; x++) {
                        int value = data[y][x] + 32768;
                        if (value <= 0) {
                            empty = true;
                            break;
                        }
                        values[n++] = value;
                    }
                }
                double level = empty ? Double.NaN : clippedMedian(values, n);
                if (flipped) {
                    result[boxRows - 1 - r][boxColumns - 1 - c] = level;
                } else {
                    result[r][c] = level;
                }
            }
        }
        return result;
    }

    private static double clippedMedian(double[] values, int n) {
        double[] sorted = Arrays.copyOf(values, n);
        Arrays.sort(sorted);
        double median = sorted[n / 2];
        double[] deviations = new double[n];
        for (int i = 0; i < n; i++) {
            deviations[i] = Math.abs(sorted[i] - median);
        }
        Arrays.sort(deviations);
        double sigma = 1.4826 * deviations[n / 2];
        if (sigma <= 0) {
            return median;
        }
        int from = 0;
        int to = n;
        while (from < n && sorted[from] < median - 2.5 * sigma) {
            from++;
        }
        while (to > from && sorted[to - 1] > median + 2.5 * sigma) {
            to--;
        }
        return to > from ? sorted[(from + to) / 2] : median;
    }

    private List<List<Double>> cells() {
        List<List<Double>> cells = new ArrayList<>();
        for (int i = 0; i < rows * columns; i++) {
            cells.add(new ArrayList<>());
        }
        return cells;
    }

    private int cell(double x, double y) {
        int c = Math.max(0, Math.min(columns - 1, (int) (x / width * columns)));
        int r = Math.max(0, Math.min(rows - 1, (int) (y / height * rows)));
        return r * columns + c;
    }

    /** The centre of a map cell in half-diagonals from the frame centre. */
    double[] cellCentre(int r, int c) {
        return cellCentre(r, c, rows, columns);
    }

    double[] cellCentre(int r, int c, int cellRows, int cellColumns) {
        double halfDiagonal = Math.hypot(width, height) / 2;
        double x = (c + 0.5) * width / cellColumns - width / 2.0;
        double y = (r + 0.5) * height / cellRows - height / 2.0;
        return new double[]{x / halfDiagonal, y / halfDiagonal};
    }

    private double[][] medians(List<List<Double>> cells, int minimum) {
        double[][] result = new double[rows][columns];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                List<Double> values = cells.get(r * columns + c);
                result[r][c] = values.size() >= minimum ? median(values) : Double.NaN;
            }
        }
        return result;
    }

    private int[][] counts(List<List<Double>> cells) {
        int[][] result = new int[rows][columns];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                result[r][c] = cells.get(r * columns + c).size();
            }
        }
        return result;
    }

    private double[] fitBowl(double[][] values, int[][] counts) {
        return fitBowl(values, counts, rows, columns);
    }

    /** Fits a + b u + c v + d (u^2 + v^2) to a map, weighted by the square root of the counts; null with too few cells. */
    private double[] fitBowl(double[][] values, int[][] counts, int cellRows, int cellColumns) {
        List<double[]> design = new ArrayList<>();
        List<Double> y = new ArrayList<>();
        List<Double> w = new ArrayList<>();
        for (int r = 0; r < cellRows; r++) {
            for (int c = 0; c < cellColumns; c++) {
                if (Double.isNaN(values[r][c]) || counts[r][c] == 0) {
                    continue;
                }
                double[] uv = cellCentre(r, c, cellRows, cellColumns);
                design.add(new double[]{1, uv[0], uv[1], uv[0] * uv[0] + uv[1] * uv[1]});
                y.add(values[r][c]);
                w.add(Math.sqrt(counts[r][c]));
            }
        }
        if (design.size() < 8) {
            return null;
        }
        return leastSquares(design.toArray(new double[0][]), toArray(y), toArray(w));
    }

    // ==========================================
    // HELPERS
    // ==========================================

    /** Weighted least squares; null when the system is singular. */
    static double[] leastSquares(double[][] design, double[] y, double[] w) {
        int p = design[0].length;
        double[][] a = new double[p][p + 1];
        for (int i = 0; i < design.length; i++) {
            for (int j = 0; j < p; j++) {
                for (int k = 0; k < p; k++) {
                    a[j][k] += w[i] * design[i][j] * design[i][k];
                }
                a[j][p] += w[i] * design[i][j] * y[i];
            }
        }
        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            double[] swap = a[col];
            a[col] = a[pivot];
            a[pivot] = swap;
            if (Math.abs(a[col][col]) < 1e-12) {
                return null;
            }
            for (int r = 0; r < p; r++) {
                if (r != col) {
                    double factor = a[r][col] / a[col][col];
                    for (int k = col; k <= p; k++) {
                        a[r][k] -= factor * a[col][k];
                    }
                }
            }
        }
        double[] result = new double[p];
        for (int j = 0; j < p; j++) {
            result[j] = a[j][p] / a[j][j];
        }
        return result;
    }

    /** Slope of y against x by least squares, after clipping points more than 3 MADs from a first fit. */
    static double robustSlope(List<double[]> points) {
        double[] line = robustLine(points);
        return line == null ? Double.NaN : line[1];
    }

    /** {intercept, slope} of y against x by least squares, after clipping points more than 3 MADs from a first fit. */
    static double[] robustLine(List<double[]> points) {
        if (points.size() < 10) {
            return null;
        }
        double[] fit = line(points, null);
        if (fit == null) {
            return null;
        }
        List<Double> residuals = new ArrayList<>();
        for (double[] p : points) {
            residuals.add(Math.abs(p[1] - fit[0] - fit[1] * p[0]));
        }
        double limit = 3 * 1.4826 * median(residuals);
        double[] clipped = line(points, limit > 0 ? new double[]{fit[0], fit[1], limit} : null);
        return clipped != null ? clipped : fit;
    }

    private static double[] line(List<double[]> points, double[] clip) {
        double n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        for (double[] p : points) {
            if (clip != null && Math.abs(p[1] - clip[0] - clip[1] * p[0]) > clip[2]) {
                continue;
            }
            n++;
            sx += p[0];
            sy += p[1];
            sxx += p[0] * p[0];
            sxy += p[0] * p[1];
        }
        double denominator = n * sxx - sx * sx;
        if (n < 5 || Math.abs(denominator) < 1e-12) {
            return null;
        }
        double slope = (n * sxy - sx * sy) / denominator;
        return new double[]{(sy - slope * sx) / n, slope};
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private static double medianOf(double[][] grid) {
        List<Double> values = new ArrayList<>();
        for (double[] row : grid) {
            for (double value : row) {
                if (!Double.isNaN(value)) {
                    values.add(value);
                }
            }
        }
        return median(values);
    }

    private static double[] toArray(List<Double> values) {
        double[] result = new double[values.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    private static double[] nearest(Map<Long, List<double[]>> grid, double cell, double x, double y, double radius) {
        double[] best = null;
        double bestDistance = radius;
        for (double[] p : near(grid, cell, x, y, radius)) {
            double distance = Math.hypot(p[0] - x, p[1] - y);
            if (distance <= bestDistance) {
                best = p;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static List<double[]> near(Map<Long, List<double[]>> grid, double cell, double x, double y, double radius) {
        List<double[]> result = new ArrayList<>();
        for (int i = (int) Math.floor((x - radius) / cell); i <= (int) Math.floor((x + radius) / cell); i++) {
            for (int j = (int) Math.floor((y - radius) / cell); j <= (int) Math.floor((y + radius) / cell); j++) {
                for (double[] p : grid.getOrDefault(key(i, j), List.of())) {
                    if (Math.hypot(p[0] - x, p[1] - y) <= radius) {
                        result.add(p);
                    }
                }
            }
        }
        return result;
    }
}
