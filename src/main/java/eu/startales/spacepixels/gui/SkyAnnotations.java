/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.session.SkyCatalogueModel;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.fits.WcsSolutionResolver;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.skycatalog.SkyProjection;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;

/**
 * The Annotate switch of a frame viewer (key A), with its Layers menu: labels from the session's sky catalogue
 * (stars, deep-sky objects and variable stars) and its predicted asteroids and comets drawn on the frame, and the
 * object under the cursor named in the readout. Asteroids and comets are placed where JPL predicts them at the middle
 * of each frame's exposure, so they move from frame to frame. The viewers only show the catalogue and the
 * predictions; both are fetched in the main window. When either arrives while the viewer is open, the switch becomes
 * available and a note says so, but nothing changes on the frame until it is turned on.
 * Next to it, Correct plate solution (key C) switches the session's correction to the Gaia stars on or off, for all
 * viewers at once and for what else uses the plate solution.
 */
final class SkyAnnotations {

    static final String NOT_FETCHED_TIP = "Fetch Sky Catalogue or Fetch Asteroids and Comets in the main window (2 Astrometry) first.";
    /** Marks are kept for this many plate solutions; aligned frames usually share one. */
    private static final int CACHED_SOLUTIONS = 3;
    private static final Color NOTICE_COLOR = new Color(255, 200, 80);

    private final FrameView view;
    private final JCheckBox annotateBox = new JCheckBox("Annotate");
    private final JButton layersButton = new JButton("Layers ▾");
    private final JCheckBoxMenuItem starsItem = new JCheckBoxMenuItem("Stars (Gaia)", true);
    private final JCheckBoxMenuItem deepSkyItem = new JCheckBoxMenuItem("Galaxies, nebulae and clusters (SIMBAD)", true);
    private final JCheckBoxMenuItem variablesItem = new JCheckBoxMenuItem("Variable stars (AAVSO VSX)", true);
    private final JCheckBoxMenuItem solarSystemItem = new JCheckBoxMenuItem("Asteroids and comets (JPL)", true);
    private final JCheckBoxMenuItem namesItem = new JCheckBoxMenuItem("Names and magnitude labels", true);
    private ViewerAnnotationPreferences preferences = ViewerAnnotationPreferences.DEFAULT;
    private final JCheckBox correctBox = new JCheckBox("Correct plate solution");
    private final JLabel notice = new JLabel(" ");
    private final Runnable catalogueListener = this::catalogueChanged;
    private final Runnable correctionListener = this::correctionChanged;
    /** Told when the plate solution in use changes, so the viewer can update its cursor readout. */
    private Runnable onSolutionChanged = () -> { };
    /** Marks per plate solution, by the file that holds it; the least recently used is dropped. */
    private final Map<String, List<SkyMark>> marksBySolution = new LinkedHashMap<String, List<SkyMark>>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<SkyMark>> eldest) {
            return size() > CACHED_SOLUTIONS;
        }
    };

    private FitsFileInformation[] files;
    private FitsFileInformation shownInfo;
    private SkyCatalogue catalogue;
    /** The session's predicted asteroids and comets, or null. */
    private SolarSystemObjects solarSystem;
    private boolean attached;
    private SkyCatalogueModel model = new SkyCatalogueModel();

    /** Creates viewer-owned controls, restoring display preferences without changing the session catalogue. */
    SkyAnnotations(FrameView view) {
        this.view = view;
        try {
            preferences = ViewerAnnotationPreferences.load(ViewerAnnotationPreferences.storage());
        } catch (RuntimeException unavailable) {
            notice.setText("Cannot load annotation preferences; defaults are in use.");
        }
        namesItem.setSelected(preferences.names());
        view.setSkyPreferences(preferences);
        annotateBox.addActionListener(e -> apply());
        JPopupMenu layers = new JPopupMenu();
        for (JCheckBoxMenuItem item : new JCheckBoxMenuItem[]{starsItem, deepSkyItem, variablesItem, solarSystemItem}) {
            item.addActionListener(e -> apply());
            layers.add(item);
        }
        layers.addSeparator();
        namesItem.addActionListener(e -> updatePreferences(new ViewerAnnotationPreferences(
                preferences.faintestStar(), preferences.faintestVariable(), namesItem.isSelected())));
        layers.add(namesItem);
        JMenuItem limitsItem = new JMenuItem("Faintest magnitude…");
        limitsItem.addActionListener(e -> editMagnitudeLimits());
        layers.add(limitsItem);
        layersButton.setToolTipText("Choose what Annotate shows.");
        layersButton.addActionListener(e -> layers.show(layersButton, 0, layersButton.getHeight()));
        correctBox.addActionListener(e -> setCorrection(correctBox.isSelected()));
        notice.setForeground(NOTICE_COLOR);
        updateEnabled();
    }

    /** Runs when the plate solution in use changes (the correction is switched on or off, or a new one arrives). */
    void setOnSolutionChanged(Runnable onSolutionChanged) {
        this.onSolutionChanged = onSolutionChanged;
    }

    /** The switch, the Layers button and the note, for the viewer's row of controls. */
    List<JComponent> controls() {
        List<JComponent> controls = new ArrayList<>();
        controls.add(annotateBox);
        controls.add(layersButton);
        controls.add(correctBox);
        controls.add(notice);
        return controls;
    }

    /** Starts following the session's catalogue; {@code files} are the frames, which can share a plate solution. */
    void bind(SkyCatalogueModel catalogueModel) {
        if (model == catalogueModel) return;
        detach();
        model = java.util.Objects.requireNonNull(catalogueModel);
        updateEnabled();
    }

    void attach(FitsFileInformation[] files) {
        this.files = files == null ? null : files.clone();
        if (!attached) {
            model.addListener(catalogueListener);
            model.addCorrectionListener(correctionListener);
            attached = true;
        }
        catalogue = model.get();
        solarSystem = model.getSolarSystem();
        marksBySolution.clear();
        notice.setText(" ");
        updateEnabled();
        setLayers();
    }

    /** Stops following the catalogue and lets go of the marks, when the viewer closes. */
    void detach() {
        model.removeListener(catalogueListener);
        model.removeCorrectionListener(correctionListener);
        attached = false;
        files = null;
        shownInfo = null;
        catalogue = null;
        solarSystem = null;
        marksBySolution.clear();
        view.setSkyMarks(null, 0);
    }

    /** The viewer now shows this frame. */
    void showFrame(FitsFileInformation info) {
        shownInfo = info;
        if (annotateBox.isSelected() && hasContent()) {
            view.setSkyMarks(marksFor(info), starLimit());
        }
    }

    /** Replaces a session frame's metadata after a WCS write and refreshes sky marks and solution listeners. */
    void replaceFrame(FitsFileInformation previous, FitsFileInformation updated) {
        if (files != null && previous != null && updated != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i] == previous || sameFrame(files[i], previous)) {
                    files[i] = updated;
                }
            }
        }
        if (shownInfo == previous || sameFrame(shownInfo, previous)) {
            shownInfo = updated;
        }
        marksBySolution.clear();
        apply();
        onSolutionChanged.run();
    }

    private static boolean sameFrame(FitsFileInformation first, FitsFileInformation second) {
        return first != null && second != null
                && first.getFilePath() != null
                && first.getFilePath().equals(second.getFilePath());
    }

    /** Key A. */
    void toggle() {
        if (annotateBox.isEnabled()) {
            annotateBox.setSelected(!annotateBox.isSelected());
            apply();
        }
    }

    /** Key C. */
    void toggleCorrection() {
        if (correctBox.isEnabled()) {
            setCorrection(!model.isCorrectionEnabled());
        }
    }

    private void setCorrection(boolean enabled) {
        try {
            model.setCorrectionEnabled(enabled);
        } catch (java.io.IOException e) {
            notice.setText("Cannot save the setting: " + e.getMessage());
        }
        correctBox.setSelected(model.isCorrectionEnabled());
    }

    /** "  · NGC 2264 · open cluster" for the object under the cursor while Annotate is on, else "". */
    String describe(Point pixel) {
        if (!annotateBox.isSelected() || pixel == null) {
            return "";
        }
        SkyMark mark = view.skyMarkAt(pixel);
        return mark == null ? "" : "  · " + mark.description;
    }

    boolean isShowing() {
        return annotateBox.isSelected();
    }

    /** Freezes this frame's currently enabled projected catalogue marks on the EDT, without changing the viewer. */
    List<SkyMark> exportMarks(FitsFileInformation info) {
        if (!annotateBox.isSelected() || !hasContent()) return List.of();
        List<SkyMark> marks = marksFor(info);
        return marks == null ? List.of() : List.copyOf(marks);
    }

    private void apply() {
        notice.setText(" ");
        setLayers();
        if (annotateBox.isSelected() && hasContent() && shownInfo != null) {
            view.setSkyMarks(marksFor(shownInfo), starLimit());
        } else {
            view.setSkyMarks(null, 0);
        }
    }

    /** Edits independent catalogue limits in a dialog so numeric keys belong to its editors, not navigation. */
    private void editMagnitudeLimits() {
        JSpinner stars = UiStyle.spinner(new DecimalSpinnerModel(preferences.faintestStar(), -10, 99, 0.5));
        JSpinner variables = UiStyle.spinner(new DecimalSpinnerModel(preferences.faintestVariable(), -10, 99, 0.5));
        JPanel fields = new JPanel(new GridLayout(0, 2, 12, 8));
        JLabel starLabel = new JLabel("Faintest star (Gaia G / named-star V):");
        starLabel.setLabelFor(stars);
        fields.add(starLabel);
        fields.add(stars);
        JLabel variableLabel = new JLabel("Faintest variable (catalogue maximum):");
        variableLabel.setLabelFor(variables);
        fields.add(variableLabel);
        fields.add(variables);
        Object[] message = {"99 shows all available magnitudes. Unknown magnitudes remain visible.", fields,
                "These display filters do not change catalogue downloads, detection or reports."};
        while (JOptionPane.showConfirmDialog(view, message, "Catalogue magnitude limits",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            try {
                stars.commitEdit();
                variables.commitEdit();
                updatePreferences(new ViewerAnnotationPreferences(((Number) stars.getValue()).doubleValue(),
                        ((Number) variables.getValue()).doubleValue(), namesItem.isSelected()));
                return;
            } catch (java.text.ParseException | IllegalArgumentException invalid) {
                JOptionPane.showMessageDialog(view, "Enter a magnitude between -10 and 99.",
                        "Invalid magnitude", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    /** Applies this viewer's filters immediately and persists them for viewers opened later. */
    private void updatePreferences(ViewerAnnotationPreferences updated) {
        preferences = updated;
        view.setSkyPreferences(preferences);
        apply();
        try {
            preferences.save(ViewerAnnotationPreferences.storage());
        } catch (RuntimeException unavailable) {
            notice.setText("Annotation preferences apply here but could not be saved.");
        }
    }

    private void catalogueChanged() {
        SkyCatalogue previousCatalogue = catalogue;
        SolarSystemObjects previousSolarSystem = solarSystem;
        catalogue = model.get();
        solarSystem = model.getSolarSystem();
        marksBySolution.clear();
        if (!hasContent()) {
            annotateBox.setSelected(false);
        }
        updateEnabled();
        // Labels already on are replaced in place; otherwise the frame stays as it is and the note offers them.
        apply();
        if (!annotateBox.isSelected()) {
            if (solarSystem != null && solarSystem != previousSolarSystem) {
                notice.setText("Asteroids and comets ready · A shows them");
            } else if (catalogue != null && catalogue != previousCatalogue) {
                notice.setText("Sky catalogue ready · A shows it");
            }
        }
    }

    /** Whether there is anything to annotate: the sky catalogue, the predicted asteroids and comets, or both. */
    private boolean hasContent() {
        return catalogue != null || solarSystem != null;
    }

    /** The star depth of the catalogue, which sizes the star circles; 0 without a catalogue. */
    private double starLimit() {
        return catalogue == null ? 0 : catalogue.starMagnitudeLimit;
    }

    private void setLayers() {
        view.setSkyLayers(starsItem.isSelected(), deepSkyItem.isSelected(), variablesItem.isSelected(), solarSystemItem.isSelected());
    }

    private void correctionChanged() {
        marksBySolution.clear();
        boolean wasAvailable = correctBox.isEnabled();
        updateEnabled();
        apply();
        onSolutionChanged.run();
        if (!wasAvailable && correctBox.isEnabled() && !correctBox.isSelected()) {
            notice.setText("Plate solution check ready · C corrects it");
        } else if (!wasAvailable && correctBox.isSelected()) {
            notice.setText("Plate solution corrected · " + model.getCorrection().improvementText() + " · C switches it off");
        }
        if (model.getSaveFailure() != null) {
            notice.setText("Cannot save the correction: " + model.getSaveFailure().getMessage() + "; it stays in use until SpacePixels closes.");
        }
    }

    WcsSolutionResolver.ResolvedWcsSolution solutionFor(FitsFileInformation info) {
        return WcsSolutionResolver.resolve(info, files, model.getCorrection());
    }

    private void updateEnabled() {
        eu.startales.spacepixels.platesolve.PlateSolutionCorrection correction = model.getCorrection();
        correctBox.setEnabled(!model.isClosed() && correction != null && correction.isNeeded());
        correctBox.setSelected(correction != null && correction.enabled);
        if (correction == null) {
            correctBox.setToolTipText("<html>Use the plate solution corrected to the Gaia stars (C).<br><i>Not available: "
                    + "it is worked out after Fetch Sky Catalogue, in the background.</i></html>");
        } else if (!correction.isNeeded()) {
            correctBox.setToolTipText(String.format(java.util.Locale.US, "<html>Use the plate solution corrected to the Gaia stars (C)."
                    + "<br><i>Not needed: the FITS header solution already matches the stars (%.2f px median).</i></html>",
                    correction.beforeMedianPx));
        } else {
            correctBox.setToolTipText("<html>Use the plate solution corrected to the Gaia stars (C). Distance from the "
                    + "catalogue stars to the stars<br>in the frames: " + correction.improvementText() + " (median). "
                    + "This applies to all viewers, the cursor RA/Dec<br>and the report of the next Detect Moving Targets; "
                    + "the FITS files are not changed.</html>");
        }
        boolean available = hasContent();
        annotateBox.setEnabled(available);
        layersButton.setEnabled(available);
        if (!available) {
            annotateBox.setToolTipText("<html>Labels from the sky catalogue and the predicted asteroids and comets (A).<br><i>Not available: "
                    + NOT_FETCHED_TIP + "</i></html>");
        } else {
            StringBuilder tip = new StringBuilder("<html>Labels on the frame (A):");
            if (catalogue != null) {
                tip.append("<br>sky catalogue: ").append(catalogue.summary())
                        .append(catalogue.starSource == null ? "" : ", stars from " + catalogue.starSource);
            }
            if (solarSystem != null) {
                tip.append("<br>predicted by JPL: ").append(solarSystem.summary());
            }
            annotateBox.setToolTipText(tip.append(".</html>").toString());
        }
        solarSystemItem.setToolTipText(solarSystem == null
                ? "Fetch Asteroids and Comets in the main window (2 Astrometry) first."
                : "Where JPL predicts the known asteroids and comets at the middle of each exposure, with their paths.");
    }

    /**
     * The marks of this frame, from its plate solution (or the one it shares with the aligned frames): the
     * catalogue's, kept per plate solution, and the asteroids and comets at this frame's time.
     */
    private List<SkyMark> marksFor(FitsFileInformation info) {
        if (info == null || !hasContent()) {
            return null;
        }
        WcsSolutionResolver.ResolvedWcsSolution solution;
        try {
            solution = solutionFor(info);
        } catch (Exception e) {
            solution = null;
        }
        if (solution == null) {
            return null;
        }
        List<SkyMark> marks = List.of();
        if (catalogue != null) {
            // A corrected solution differs a little per frame.
            String key = solution.getSourceFileName() + (solution.getTransformer().isCorrected() ? "/" + info.getFileName() : "")
                    + "@" + info.getSizeWidth() + "x" + info.getSizeHeight();
            marks = marksBySolution.get(key);
            if (marks == null) {
                marks = project(catalogue, solution.getTransformer(), info.getSizeWidth(), info.getSizeHeight(),
                        info.getObservationTimestamp());
                marksBySolution.put(key, marks);
            }
        }
        if (solarSystem == null) {
            return marks;
        }
        List<SkyMark> all = new ArrayList<>(marks);
        all.addAll(projectSolarSystem(solarSystem, solution.getTransformer(), info.getSizeWidth(), info.getSizeHeight(),
                info.getObservationTimestamp(), info.getExposureDurationMillis()));
        return all;
    }

    // ==========================================
    // PROJECTION
    // ==========================================

    /** Room around the frame in which deep-sky marks are still kept (they can reach in), in frame pixels. */
    private static final double EDGE_ROOM = 40;

    /**
     * The catalogue objects that fall on a frame of this size. Stars are moved from the Gaia epoch to the frame's
     * date ({@code timestampMillis}, or not at all when it is unknown).
     */
    static List<SkyMark> project(SkyCatalogue catalogue, WcsCoordinateTransformer transformer, int width, int height,
                                 long timestampMillis) {
        List<SkyMark> marks = new ArrayList<>();
        for (SkyCatalogue.DeepSkyObject object : catalogue.deepSky) {
            double[] shape = SkyProjection.deepSkyShape(transformer, object);
            if (shape == null || !onFrame(shape, width, height, shape[2])) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.DEEP_SKY, shape[0], shape[1], Double.NaN, shape[2], shape[3], shape[4],
                    SkyProjection.deepSkyLabel(object), deepSkyDescription(object)));
        }
        for (SkyCatalogue.NamedStar named : catalogue.namedStars) {
            double[] position = transformer.skyToPixel(named.ra, named.dec);
            if (position == null || !onFrame(position, width, height, -EDGE_ROOM)) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.NAMED_STAR, position[0], position[1], named.vmag, 0, 0, 0, named.name,
                    String.format(Locale.US, "%s · V %.2f", named.name, named.vmag)));
        }
        for (SkyCatalogue.VariableStar variable : catalogue.variables) {
            double[] position = transformer.skyToPixel(variable.ra, variable.dec);
            if (position == null || !onFrame(position, width, height, -EDGE_ROOM)) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.VARIABLE, position[0], position[1],
                    variable.max == null ? Double.NaN : variable.max, 0, 0, 0, variable.name, variableDescription(variable)));
        }
        for (SkyCatalogue.Star star : catalogue.stars) {
            double[] position = SkyProjection.starPixel(transformer, star, timestampMillis);
            if (position == null || !onFrame(position, width, height, -EDGE_ROOM)) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.STAR, position[0], position[1], star.g, 0, 0, 0,
                    String.format(Locale.US, "%.1f", star.g), starDescription(star)));
        }
        return marks;
    }

    /**
     * The predicted asteroids and comets on a frame whose exposure starts at {@code startMillis} (0 or less when
     * unknown) and lasts {@code exposureMillis}: each at the middle of the exposure, with its path over the session,
     * its motion during the exposure and its 3-sigma uncertainty in pixels. An object whose path crosses the frame
     * is kept even when it is elsewhere at this time, unplaced (NaN position).
     */
    static List<SkyMark> projectSolarSystem(SolarSystemObjects objects, WcsCoordinateTransformer transformer, int width,
                                            int height, long startMillis, long exposureMillis) {
        List<SkyMark> marks = new ArrayList<>();
        long exposure = Math.max(0, exposureMillis);
        long middle = startMillis > 0 ? startMillis + exposure / 2 : -1;
        for (SolarSystemObjects.Body body : objects.bodies) {
            double[] path = pathOf(body, transformer);
            SolarSystemObjects.Position position = middle > 0 ? SolarSystemObjects.positionAt(body, middle) : null;
            double[] pixel = position == null ? null : transformer.skyToPixel(position.ra(), position.dec());
            boolean placed = pixel != null && onFrame(pixel, width, height, -EDGE_ROOM);
            if (!placed && !pathCrosses(path, width, height)) {
                continue;
            }
            double semiMajor = 0;
            double semiMinor = 0;
            double angle = 0;
            double[] trail = null;
            if (placed) {
                double[] ellipse = uncertaintyEllipse(transformer, position, pixel);
                if (ellipse != null) {
                    semiMajor = ellipse[0];
                    semiMinor = ellipse[1];
                    angle = ellipse[2];
                }
                if (exposure > 0) {
                    SolarSystemObjects.Position first = SolarSystemObjects.positionAt(body, startMillis);
                    SolarSystemObjects.Position last = SolarSystemObjects.positionAt(body, startMillis + exposure);
                    double[] from = first == null ? null : transformer.skyToPixel(first.ra(), first.dec());
                    double[] to = last == null ? null : transformer.skyToPixel(last.ra(), last.dec());
                    if (from != null && to != null) {
                        trail = new double[]{from[0], from[1], to[0], to[1]};
                    }
                }
            }
            Double magnitude = position != null ? position.magnitude() : body.samples.get(0).magnitude;
            String label = SolarSystemObjects.label(body) + (magnitude == null ? "" : String.format(Locale.US, " %.1f", magnitude));
            marks.add(new SkyMark(SkyMark.Kind.SOLAR_SYSTEM, placed ? pixel[0] : Double.NaN, placed ? pixel[1] : Double.NaN,
                    magnitude == null ? Double.NaN : magnitude, semiMajor, semiMinor, angle, label,
                    solarSystemDescription(body, position, placed), path, trail));
        }
        return marks;
    }

    /** The body's sampled positions in frame pixels, as x0, y0, x1, y1, …; positions outside the projection are left out. */
    private static double[] pathOf(SolarSystemObjects.Body body, WcsCoordinateTransformer transformer) {
        double[] path = new double[body.samples.size() * 2];
        int length = 0;
        for (SolarSystemObjects.Sample sample : body.samples) {
            double[] pixel = transformer.skyToPixel(sample.ra, sample.dec);
            if (pixel != null && Double.isFinite(pixel[0]) && Double.isFinite(pixel[1])) {
                path[length++] = pixel[0];
                path[length++] = pixel[1];
            }
        }
        return java.util.Arrays.copyOf(path, length);
    }

    /** Whether a path comes onto the frame: a point on it, or a step whose bounding box overlaps it. */
    private static boolean pathCrosses(double[] path, int width, int height) {
        for (int i = 0; i + 1 < path.length; i += 2) {
            double minX = path[i];
            double maxX = path[i];
            double minY = path[i + 1];
            double maxY = path[i + 1];
            if (i + 3 < path.length) {
                minX = Math.min(minX, path[i + 2]);
                maxX = Math.max(maxX, path[i + 2]);
                minY = Math.min(minY, path[i + 3]);
                maxY = Math.max(maxY, path[i + 3]);
            }
            if (maxX >= -0.5 && minX <= width - 0.5 && maxY >= -0.5 && minY <= height - 0.5) {
                return true;
            }
        }
        return false;
    }

    /**
     * The 3-sigma uncertainty as {semi-major, semi-minor, angle} in frame pixels and radians, from the RA and Dec
     * uncertainties on the sky; null when they are unknown.
     */
    private static double[] uncertaintyEllipse(WcsCoordinateTransformer transformer, SolarSystemObjects.Position position,
                                               double[] pixel) {
        if (position.raSigmaArcsec() == null || position.decSigmaArcsec() == null) {
            return null;
        }
        double cosDec = Math.max(1e-6, Math.cos(Math.toRadians(position.dec())));
        double[] alongRa = transformer.skyToPixel(position.ra() + position.raSigmaArcsec() / 3600.0 / cosDec, position.dec());
        double[] alongDec = transformer.skyToPixel(position.ra(), position.dec() + position.decSigmaArcsec() / 3600.0);
        if (alongRa == null || alongDec == null) {
            return null;
        }
        double raLength = Math.hypot(alongRa[0] - pixel[0], alongRa[1] - pixel[1]);
        double decLength = Math.hypot(alongDec[0] - pixel[0], alongDec[1] - pixel[1]);
        double[] major = raLength >= decLength ? alongRa : alongDec;
        return new double[]{Math.max(raLength, decLength), Math.min(raLength, decLength),
                Math.atan2(major[1] - pixel[1], major[0] - pixel[0])};
    }

    static String solarSystemDescription(SolarSystemObjects.Body body, SolarSystemObjects.Position position, boolean placed) {
        StringBuilder text = new StringBuilder(body.name).append(body.comet ? " · comet" : " · asteroid");
        if (!placed) {
            return text.append(" · elsewhere at this frame's time (path shown)").toString();
        }
        if (position.magnitude() != null) {
            text.append(String.format(Locale.US, body.comet ? " · total magnitude %.1f" : " · V %.1f", position.magnitude()));
        }
        if (position.raSigmaArcsec() != null && position.decSigmaArcsec() != null) {
            double sigma = Math.max(position.raSigmaArcsec(), position.decSigmaArcsec());
            text.append(sigma < 1 ? " · within 1″ (3σ)" : String.format(Locale.US, " · ±%s″ (3σ)", trimNumber(sigma)));
        }
        text.append(body.orbitSolution == null ? " · predicted from JPL's field search (straight-line motion)"
                : " · predicted by JPL Horizons, orbit " + body.orbitSolution);
        return text.toString();
    }

    private static boolean onFrame(double[] position, int width, int height, double reach) {
        double room = EDGE_ROOM + reach;
        return position[0] >= -0.5 - room && position[1] >= -0.5 - room
                && position[0] <= width - 0.5 + room && position[1] <= height - 0.5 + room;
    }

    static String starDescription(SkyCatalogue.Star star) {
        String text = String.format(Locale.US, "Gaia G %.1f", star.g);
        return star.bpRp == null ? text : text + String.format(Locale.US, " · BP−RP %.2f", star.bpRp);
    }

    static String variableDescription(SkyCatalogue.VariableStar variable) {
        StringBuilder text = new StringBuilder(variable.name);
        if (variable.type != null) {
            text.append(" · ").append(variable.type);
        }
        if (variable.max != null) {
            String band = variable.band == null ? "" : " " + variable.band;
            if (variable.min == null) {
                text.append(String.format(Locale.US, " · %.2f%s", variable.max, band));
            } else if (variable.minIsAmplitude) {
                text.append(String.format(Locale.US, " · %.2f%s, amplitude %.2f", variable.max, band, variable.min));
            } else {
                text.append(String.format(Locale.US, " · %.2f–%.2f%s", variable.max, variable.min, band));
            }
        }
        if (variable.periodDays != null) {
            text.append(String.format(Locale.US, " · period %s d", trimNumber(variable.periodDays)));
        }
        return text.toString();
    }

    static String deepSkyDescription(SkyCatalogue.DeepSkyObject object) {
        StringBuilder text = new StringBuilder(SkyProjection.deepSkyLabel(object));
        text.append(" · ").append(typeName(object.type));
        if (object.majorArcmin != null && object.majorArcmin > 0) {
            text.append(" · ").append(trimNumber(object.majorArcmin));
            if (object.minorArcmin != null && object.minorArcmin > 0) {
                text.append(" × ").append(trimNumber(object.minorArcmin));
            }
            text.append('′');
        }
        return text.toString();
    }

    static String typeName(String type) {
        return SkyProjection.deepSkyTypeName(type);
    }

    private static String trimNumber(double value) {
        String text = String.format(Locale.US, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.3f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }
}
