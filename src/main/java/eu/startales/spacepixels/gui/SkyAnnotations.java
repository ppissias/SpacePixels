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

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Annotate switch of a frame viewer (key A), with its Layers menu: labels from the session's sky catalogue
 * (stars, deep-sky objects and variable stars) drawn on the frame, and the object under the cursor named in the
 * readout. The viewers only show the catalogue; it is fetched in the main window. When it arrives while the viewer
 * is open, the switch becomes available and a note says so, but nothing changes on the frame until it is turned on.
 * Next to it, Correct plate solution (key C) switches the session's correction to the Gaia stars on or off, for all
 * viewers at once and for what else uses the plate solution.
 */
final class SkyAnnotations {

    static final String NOT_FETCHED_TIP = "Fetch Sky Catalogue in the main window (2 Astrometry) first.";
    /** Marks are kept for this many plate solutions; aligned frames usually share one. */
    private static final int CACHED_SOLUTIONS = 3;
    private static final Color NOTICE_COLOR = new Color(255, 200, 80);

    private final FrameView view;
    private final JCheckBox annotateBox = new JCheckBox("Annotate");
    private final JButton layersButton = new JButton("Layers ▾");
    private final JCheckBoxMenuItem starsItem = new JCheckBoxMenuItem("Stars (Gaia)", true);
    private final JCheckBoxMenuItem deepSkyItem = new JCheckBoxMenuItem("Galaxies, nebulae and clusters (SIMBAD)", true);
    private final JCheckBoxMenuItem variablesItem = new JCheckBoxMenuItem("Variable stars (AAVSO VSX)", true);
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
    private boolean attached;

    SkyAnnotations(FrameView view) {
        this.view = view;
        annotateBox.addActionListener(e -> apply());
        JPopupMenu layers = new JPopupMenu();
        for (JCheckBoxMenuItem item : new JCheckBoxMenuItem[]{starsItem, deepSkyItem, variablesItem}) {
            item.addActionListener(e -> apply());
            layers.add(item);
        }
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
    void attach(FitsFileInformation[] files) {
        this.files = files;
        if (!attached) {
            SkyCatalogueModel.shared().addListener(catalogueListener);
            SkyCatalogueModel.shared().addCorrectionListener(correctionListener);
            attached = true;
        }
        catalogue = SkyCatalogueModel.shared().get();
        marksBySolution.clear();
        notice.setText(" ");
        updateEnabled();
        view.setSkyLayers(starsItem.isSelected(), deepSkyItem.isSelected(), variablesItem.isSelected());
    }

    /** Stops following the catalogue and lets go of the marks, when the viewer closes. */
    void detach() {
        SkyCatalogueModel.shared().removeListener(catalogueListener);
        SkyCatalogueModel.shared().removeCorrectionListener(correctionListener);
        attached = false;
        files = null;
        shownInfo = null;
        catalogue = null;
        marksBySolution.clear();
        view.setSkyMarks(null, 0);
    }

    /** The viewer now shows this frame. */
    void showFrame(FitsFileInformation info) {
        shownInfo = info;
        if (annotateBox.isSelected()) {
            view.setSkyMarks(marksFor(info), catalogue.starMagnitudeLimit);
        }
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
            setCorrection(!SkyCatalogueModel.shared().isCorrectionEnabled());
        }
    }

    private void setCorrection(boolean enabled) {
        try {
            SkyCatalogueModel.shared().setCorrectionEnabled(enabled);
        } catch (java.io.IOException e) {
            notice.setText("Cannot save the setting: " + e.getMessage());
        }
        correctBox.setSelected(SkyCatalogueModel.shared().isCorrectionEnabled());
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

    private void apply() {
        notice.setText(" ");
        view.setSkyLayers(starsItem.isSelected(), deepSkyItem.isSelected(), variablesItem.isSelected());
        if (annotateBox.isSelected() && catalogue != null && shownInfo != null) {
            view.setSkyMarks(marksFor(shownInfo), catalogue.starMagnitudeLimit);
        } else {
            view.setSkyMarks(null, 0);
        }
    }

    private void catalogueChanged() {
        catalogue = SkyCatalogueModel.shared().get();
        marksBySolution.clear();
        if (catalogue == null) {
            annotateBox.setSelected(false);
        }
        updateEnabled();
        // Labels already on are replaced in place; otherwise the frame stays as it is and the note offers them.
        apply();
        if (catalogue != null && !annotateBox.isSelected()) {
            notice.setText("Sky catalogue ready · A shows it");
        }
    }

    private void correctionChanged() {
        marksBySolution.clear();
        boolean wasAvailable = correctBox.isEnabled();
        updateEnabled();
        apply();
        onSolutionChanged.run();
        if (!wasAvailable && correctBox.isEnabled() && !correctBox.isSelected()) {
            notice.setText("Plate solution check ready · C corrects it");
        }
    }

    private void updateEnabled() {
        eu.startales.spacepixels.util.PlateSolutionCorrection correction = SkyCatalogueModel.shared().getCorrection();
        correctBox.setEnabled(correction != null);
        correctBox.setSelected(correction != null && correction.enabled);
        if (correction == null) {
            correctBox.setToolTipText("<html>Use the plate solution corrected to the Gaia stars (C).<br><i>Not available: "
                    + "it is worked out after Fetch Sky Catalogue, in the background.</i></html>");
        } else {
            correctBox.setToolTipText("<html>Use the plate solution corrected to the Gaia stars (C). Distance from the "
                    + "catalogue stars to the stars<br>in the frames: " + correction.improvementText() + " (median). "
                    + "This applies to all viewers, the cursor RA/Dec<br>and the report of the next Detect Moving Targets; "
                    + "the FITS files are not changed.</html>");
        }
        boolean available = catalogue != null;
        annotateBox.setEnabled(available);
        layersButton.setEnabled(available);
        if (!available) {
            annotateBox.setToolTipText("<html>Labels from the sky catalogue (A).<br><i>Not available: " + NOT_FETCHED_TIP + "</i></html>");
        } else {
            annotateBox.setToolTipText("<html>Labels from the sky catalogue (A): " + catalogue.summary()
                    + (catalogue.starSource == null ? "" : ",<br>stars from " + catalogue.starSource) + ".</html>");
        }
    }

    /** The marks of this frame, from its plate solution (or the one it shares with the aligned frames). */
    private List<SkyMark> marksFor(FitsFileInformation info) {
        if (info == null || catalogue == null) {
            return null;
        }
        WcsSolutionResolver.ResolvedWcsSolution solution;
        try {
            solution = WcsSolutionResolver.resolve(info, files);
        } catch (Exception e) {
            solution = null;
        }
        if (solution == null) {
            return null;
        }
        // A corrected solution differs a little per frame.
        String key = solution.getSourceFileName() + (solution.getTransformer().isCorrected() ? "/" + info.getFileName() : "")
                + "@" + info.getSizeWidth() + "x" + info.getSizeHeight();
        List<SkyMark> marks = marksBySolution.get(key);
        if (marks == null) {
            marks = project(catalogue, solution.getTransformer(), info.getSizeWidth(), info.getSizeHeight(),
                    info.getObservationTimestamp());
            marksBySolution.put(key, marks);
        }
        return marks;
    }

    // ==========================================
    // PROJECTION
    // ==========================================

    /** Room around the frame in which deep-sky marks are still kept (they can reach in), in frame pixels. */
    private static final double EDGE_ROOM = 40;
    private static final double MILLIS_PER_YEAR = 365.25 * 86_400_000.0;

    /**
     * The catalogue objects that fall on a frame of this size. Stars are moved from the Gaia epoch to the frame's
     * date ({@code timestampMillis}, or not at all when it is unknown).
     */
    static List<SkyMark> project(SkyCatalogue catalogue, WcsCoordinateTransformer transformer, int width, int height,
                                 long timestampMillis) {
        List<SkyMark> marks = new ArrayList<>();
        for (SkyCatalogue.DeepSkyObject object : catalogue.deepSky) {
            double[] centre = transformer.skyToPixel(object.ra, object.dec);
            if (centre == null) {
                continue;
            }
            double semiMajor = 0;
            double semiMinor = 0;
            double angle = 0;
            if (object.majorArcmin != null && object.majorArcmin > 0) {
                double radiusDeg = object.majorArcmin / 120.0;
                double positionAngle = Math.toRadians(object.angleDeg == null ? 0 : object.angleDeg);
                double[] edge = transformer.skyToPixel(
                        object.ra + radiusDeg * Math.sin(positionAngle) / Math.max(1e-6, Math.cos(Math.toRadians(object.dec))),
                        object.dec + radiusDeg * Math.cos(positionAngle));
                if (edge != null) {
                    semiMajor = Math.hypot(edge[0] - centre[0], edge[1] - centre[1]);
                    angle = Math.atan2(edge[1] - centre[1], edge[0] - centre[0]);
                    double minor = object.minorArcmin != null && object.minorArcmin > 0 && object.angleDeg != null
                            ? object.minorArcmin : object.majorArcmin;
                    semiMinor = semiMajor * Math.min(1.0, minor / object.majorArcmin);
                }
            }
            if (!onFrame(centre, width, height, semiMajor)) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.DEEP_SKY, centre[0], centre[1], Double.NaN, semiMajor, semiMinor, angle,
                    object.name, deepSkyDescription(object)));
        }
        for (SkyCatalogue.VariableStar variable : catalogue.variables) {
            double[] position = transformer.skyToPixel(variable.ra, variable.dec);
            if (position == null || !onFrame(position, width, height, -EDGE_ROOM)) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.VARIABLE, position[0], position[1],
                    variable.max == null ? Double.NaN : variable.max, 0, 0, 0, variable.name, variableDescription(variable)));
        }
        double years = timestampMillis > 0 ? (timestampMillis - SkyCatalogue.GAIA_EPOCH_MILLIS) / MILLIS_PER_YEAR : 0;
        for (SkyCatalogue.Star star : catalogue.stars) {
            double ra = star.ra;
            double dec = star.dec;
            if (years != 0 && star.pmra != null && star.pmdec != null) {
                dec += star.pmdec * years / 3.6e6;
                ra += star.pmra * years / 3.6e6 / Math.max(1e-6, Math.cos(Math.toRadians(star.dec)));
            }
            double[] position = transformer.skyToPixel(ra, dec);
            if (position == null || !onFrame(position, width, height, -EDGE_ROOM)) {
                continue;
            }
            marks.add(new SkyMark(SkyMark.Kind.STAR, position[0], position[1], star.g, 0, 0, 0,
                    String.format(Locale.US, "%.1f", star.g), starDescription(star)));
        }
        return marks;
    }

    /** Whether a mark is within {@code reach} pixels beyond the frame, plus the room ({@code -EDGE_ROOM}: on the frame). */
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
        StringBuilder text = new StringBuilder(object.name == null ? "?" : object.name);
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

    /** Plain names for the SIMBAD object types a deep-sky label is likely to have. */
    static String typeName(String type) {
        if (type == null) {
            return "object";
        }
        switch (type) {
            case "G": return "galaxy";
            case "OpC": return "open cluster";
            case "GlC": return "globular cluster";
            case "Cl*": return "star cluster";
            case "As*": return "stellar association";
            case "PN": return "planetary nebula";
            case "HII": return "HII region";
            case "RNe": return "reflection nebula";
            case "DNe": return "dark nebula";
            case "GNe": return "nebula";
            case "SNR": return "supernova remnant";
            case "MoC": return "molecular cloud";
            case "ClG": return "galaxy cluster";
            case "GrG": return "group of galaxies";
            case "CGG": return "compact group of galaxies";
            case "PaG": return "pair of galaxies";
            case "IG": return "interacting galaxies";
            case "Sy1": case "Sy2": case "SyG": return "Seyfert galaxy";
            case "AGN": case "LIN": return "active galaxy";
            case "SBG": return "starburst galaxy";
            case "EmG": return "emission-line galaxy";
            case "LSB": return "low surface brightness galaxy";
            case "BiC": return "brightest galaxy of a cluster";
            case "GiC": case "GiG": case "GiP": return "galaxy in a group";
            case "rG": return "radio galaxy";
            case "H2G": return "HII galaxy";
            case "bCG": return "blue compact galaxy";
            case "AG?": case "G?": return "possible galaxy";
            default: return "SIMBAD type " + type;
        }
    }

    private static String trimNumber(double value) {
        String text = String.format(Locale.US, value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.3f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return text;
    }
}
