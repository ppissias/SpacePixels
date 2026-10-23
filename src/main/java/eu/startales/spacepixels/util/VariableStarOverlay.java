package eu.startales.spacepixels.util;

import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The last run's reported variable candidates, without retaining its frames or light-curve arrays. */
public final class VariableStarOverlay {
    public static final class Candidate {
        public final String label;
        public final String name;
        public final String description;
        public final double x;
        public final double y;
        public final boolean highConfidence;

        private Candidate(String label, StarLightCurve star, String name) {
            this.label = label;
            this.name = name;
            this.x = star.x;
            this.y = star.y;
            this.highConfidence = star.tier == VariabilityTier.HIGH_CONFIDENCE;
            this.description = label + (name == null ? "" : " · " + name) + " · "
                    + (highConfidence ? "High confidence" : "Possible variable")
                    + (Double.isFinite(star.amplitude) ? String.format(Locale.US, " · variation %.3f mag", star.amplitude) : "");
        }
    }

    private final List<Candidate> candidates;
    private final Set<String> framePaths;
    private final int width;
    private final int height;

    private VariableStarOverlay(List<Candidate> candidates, Set<String> framePaths, int width, int height) {
        this.candidates = Collections.unmodifiableList(candidates);
        this.framePaths = Collections.unmodifiableSet(framePaths);
        this.width = width;
        this.height = height;
    }

    public List<Candidate> getCandidates() {
        return candidates;
    }

    /** Full paths and dimensions prevent an overlay from another session with the same FITS names. */
    public boolean covers(FitsFileInformation file) {
        return file != null && file.getFilePath() != null && file.getSizeWidth() == width && file.getSizeHeight() == height
                && framePaths.contains(new File(file.getFilePath()).getAbsolutePath());
    }

    public boolean coversAnyOf(FitsFileInformation[] files) {
        if (candidates.isEmpty() || files == null) return false;
        for (FitsFileInformation file : files) if (covers(file)) return true;
        return false;
    }

    public static VariableStarOverlay from(PipelineResult result, FitsFileInformation[] files) {
        return fromCandidates(result == null ? Collections.emptyList() : result.variableStarAnalysis.candidates, files);
    }

    /** Builds an overlay from explicit run-owned astrometry/catalogue data without reading session files. */
    public static VariableStarOverlay from(PipelineResult result, FitsFileInformation[] files,
            WcsCoordinateTransformer transformer, SkyCatalogue catalogue) {
        return fromCandidates(result == null ? Collections.emptyList() : result.variableStarAnalysis.candidates,
                files, transformer, catalogue);
    }

    /** Captures current session context for standalone viewer callers. */
    static VariableStarOverlay fromCandidates(List<StarLightCurve> stars, FitsFileInformation[] files) {
        var solution = WcsSolutionResolver.resolve(null, files);
        var transformer = solution == null ? null : solution.getTransformer();
        FitsFileInformation first = files != null && files.length > 0 ? files[0] : null;
        SkyCatalogue catalogue = first == null || first.getFilePath() == null || transformer == null ? null
                : SkyCatalogueStore.load(new File(first.getFilePath()).getAbsoluteFile().getParentFile());
        return fromCandidates(stars, files, transformer, catalogue);
    }

    /** Copies candidate display state using only the supplied metadata and read-only catalogue. */
    private static VariableStarOverlay fromCandidates(List<StarLightCurve> stars, FitsFileInformation[] files,
            WcsCoordinateTransformer transformer, SkyCatalogue catalogue) {
        List<Candidate> candidates = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        FitsFileInformation first = files != null && files.length > 0 ? files[0] : null;
        int width = first == null ? 0 : first.getSizeWidth();
        int height = first == null ? 0 : first.getSizeHeight();
        if (files != null) {
            for (FitsFileInformation file : files) {
                if (file != null && file.getFilePath() != null) paths.add(new File(file.getFilePath()).getAbsolutePath());
            }
        }
        if (stars.isEmpty() || width <= 0 || height <= 0) {
            return new VariableStarOverlay(candidates, paths, width, height);
        }
        if (catalogue != null && (catalogue.field == null || !SkyField.of(transformer, width, height).isSameFieldAs(catalogue.field))) {
            catalogue = null;
        }
        for (StarLightCurve star : stars) {
            if (star.tier != VariabilityTier.HIGH_CONFIDENCE && star.tier != VariabilityTier.POSSIBLE) continue;
            candidates.add(new Candidate("V" + (candidates.size() + 1), star, catalogueName(catalogue, transformer, star.x, star.y)));
        }
        return new VariableStarOverlay(candidates, paths, width, height);
    }

    /** The report's VSX search radius: four pixels, bounded to 10–60 arcseconds. */
    private static String catalogueName(SkyCatalogue catalogue, WcsCoordinateTransformer transformer, double x, double y) {
        if (catalogue == null || transformer == null) return null;
        WcsCoordinateTransformer.SkyCoordinate centre = transformer.pixelToSky(x, y);
        WcsCoordinateTransformer.SkyCoordinate next = transformer.pixelToSky(x + 1, y);
        if (centre == null || next == null) return null;
        double radius = Math.max(10, Math.min(60, 4 * separation(centre.getRaDegrees(), centre.getDecDegrees(),
                next.getRaDegrees(), next.getDecDegrees())));
        if (!Double.isFinite(radius)) radius = 10;
        String name = null;
        for (SkyCatalogue.VariableStar variable : catalogue.variables) {
            double distance = separation(centre.getRaDegrees(), centre.getDecDegrees(), variable.ra, variable.dec);
            if (Double.isFinite(distance) && distance <= radius) {
                radius = distance;
                name = variable.name;
            }
        }
        return name;
    }

    private static double separation(double ra1, double dec1, double ra2, double dec2) {
        double a = Math.pow(Math.sin(Math.toRadians(dec2 - dec1) / 2), 2)
                + Math.cos(Math.toRadians(dec1)) * Math.cos(Math.toRadians(dec2))
                * Math.pow(Math.sin(Math.toRadians(ra2 - ra1) / 2), 2);
        return Math.toDegrees(2 * Math.asin(Math.min(1, Math.sqrt(a)))) * 3600;
    }
}
