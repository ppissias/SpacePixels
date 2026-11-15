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

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.DisplayImageRenderer;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.mpc.AdesPsv;
import eu.startales.spacepixels.mpc.AstrometricReduction;
import eu.startales.spacepixels.mpc.FindOrb;
import eu.startales.spacepixels.mpc.LightCurve;
import eu.startales.spacepixels.mpc.OrbitGeometry;
import eu.startales.spacepixels.mpc.MpcExport;
import eu.startales.spacepixels.mpc.MpcObservation;
import eu.startales.spacepixels.mpc.ReportedObjects;
import io.github.ppissias.jtransient.core.SubStackAstrometry;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.skycatalog.PhotometryStars;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.solarsystem.SolarSystemClient;
import nom.tam.fits.Fits;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Asteroids &amp; Comets window (F33): the moving objects of one report with their status against the asteroid
 * predictions, a blink of the selected object, its observations reduced against the Gaia-checked plate solution with
 * the ones to report, and the ADES file of every object marked for export. The steps are Identify, Measure, Check and
 * Export. It reads each frame only to cut a small picture around the object and keeps no whole frame; frame reading
 * runs on a worker, the newest selection wins, and closing cancels it.
 */
final class AsteroidsCometsWindow extends JFrame implements SessionViewer {
    /** Side of the object-centred pictures, in pixels. */
    static final int CROP = 64;
    /** Largest side of the star-centred picture of a whole path; a longer path is shown object-centred only. */
    static final int MAX_FIXED = 600;
    /** The MPC's guide to reporting observations. */
    static final String REPORTING_GUIDE = "https://docs.minorplanetcenter.net/mpc-ops-docs/astrometry/reporting-observations/";
    /** The MPC's MPChecker form; it has no documented programmatic interface. */
    static final String MPCHECKER = "https://minorplanetcenter.net/cgi-bin/checkmp.cgi";
    /** Bill Gray's online Find_Orb. */
    static final String FIND_ORB = "https://www.projectpluto.com/fo.htm";
    /** The status of a faint mover with a marginal sub-stack. */
    static final String WEAK = "weak";
    /** The most cut-out pixels a sub-stack measurement may read, in bytes. */
    static final long MAX_SUB_STACK_BYTES = 512L * 1024 * 1024;
    /** Gaia depth fetched for comparison stars when the saved catalogue has too few usable ones. */
    static final double DEEPER_COMPARISON_STARS = 20.0;
    /** Largest field, in square degrees, for that fetch: deeper than this, a wide field would bring too many stars. */
    static final double MAX_DEEPER_FIELD_SQUARE_DEGREES = 1.0;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

    private final FitsFileInformation[] files;
    private final ReportedObjects.Report report;
    private final AstrometricReduction.Result reduction;
    private final ApplicationSettings settings;
    private final ApplicationStorage storage;
    private final VisualizationSettings visualization;
    private final ObservingSite site;
    private final WcsCoordinateTransformer header;
    /** The session's sky catalogue, for comparison stars; null without one. */
    private final SkyCatalogue catalogue;
    /** Fainter Gaia stars fetched for comparison stars when the saved catalogue had too few usable ones; empty until then. */
    private volatile List<SkyCatalogue.Star> fetchedStars = List.of();
    private final JComboBox<String> binChoice = new JComboBox<>(new String[]{"Single frames", "Bins of 2", "Bins of 4"});
    private final Map<String, LightCurve.Curve> curves = new HashMap<>();
    private final LightCurvePlot curvePlot = new LightCurvePlot();
    private final JLabel curveLabel = new JLabel(" ");
    private final JButton curveButton = new JButton("Measure light curve");
    private final JButton curveSaveButton = new JButton("Save CSV…");

    private final Map<String, Boolean> exported = new HashMap<>();
    private final Map<String, Set<Integer>> selected = new HashMap<>();
    private final Map<String, List<MpcObservation>> observations = new HashMap<>();
    private final Map<String, FindOrb.Orbit> orbits = new HashMap<>();
    private final Map<String, String> orbitProblems = new HashMap<>();
    /** Each measured faint mover's sub-stacks, in time order. */
    private final Map<String, List<SubStackAstrometry.Measurement>> subStacks = new HashMap<>();
    private final JButton measureButton = new JButton("Measure in sub-stacks");
    /** Sub-stacks a faint mover is measured in; the MPC asks for at least two per night, preferably three to five. */
    static final int SUB_STACKS = 3;
    private final JLabel orbitLabel = new JLabel(" ");
    private final OrbitDiagram orbitDiagram = new OrbitDiagram();
    private final AtomicInteger generation = new AtomicInteger();
    private SwingWorker<List<short[][][]>, Void> worker;
    private boolean closed;
    private String stationProblem;
    private String stationText = "not checked";

    private final ObjectsModel objectsModel = new ObjectsModel();
    private final JTable objectsTable = new JTable(objectsModel);
    private final ObservationsModel observationsModel = new ObservationsModel();
    private final JTable observationsTable = new JTable(observationsModel);
    private final Picture picture = new Picture();
    private final JSlider frameSlider = new JSlider(0, 0, 0);
    private final JToggleButton playButton = new JToggleButton("Play");
    private final JRadioButton objectCentred = new JRadioButton("Object-centred", true);
    private final JRadioButton starCentred = new JRadioButton("Star-centred");
    private final JLabel pictureLabel = new JLabel(" ");
    private final JLabel stretchLabel = new JLabel(" ");
    private final JLabel fitLabel = new JLabel(" ");
    private final JLabel identificationLabel = new JLabel(" ");
    private final JLabel stationLabel = new JLabel(" ");
    private final JTextArea problemsArea = new JTextArea(4, 30);
    private final JTextArea psvArea = new JTextArea(12, 30);
    private final JButton copyButton = new JButton("Copy");
    private final JButton saveButton = new JButton("Save .psv…");
    private final Timer playTimer;
    /** The shown object's cut-outs per frame: {object-centred, star-centred or null}, kept raw so the stretch can change. */
    private List<short[][][]> pictures = List.of();
    /** Shared sky statistics of the object-centred and the star-centred cut-outs, for one stretch across the frames. */
    private DisplayImageRenderer.DisplayStatistics[] pictureStatistics = new DisplayImageRenderer.DisplayStatistics[2];
    private final JSlider blackSlider = new JSlider(0, 60, 20);
    private final JSlider whiteSlider = new JSlider(10, 300, 60);
    private ReportedObjects.ReportedObject shown;

    private final JTextField submitter = new JTextField(16);
    private final JTextField observers = new JTextField(16);
    private final JTextField measurers = new JTextField(16);
    private final JTextField design = new JTextField(16);
    private final JTextField aperture = new JTextField(6);
    private final JTextField detector = new JTextField(10);
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"CCD", "CMOS"});
    private final JTextField comment = new JTextField(16);
    private final JTextField email = new JTextField(16);
    private final JButton schemaButton = new JButton("Check schema");
    private final JButton mpcTestButton = new JButton("Test with the MPC…");

    /**
     * @param files      the session's frames, which must be the report's
     * @param report     the report's objects
     * @param header     the header plate solution of the frames, or null
     * @param correction the session's plate correction, or null
     * @param catalogue  the session's sky catalogue, or null
     */
    AsteroidsCometsWindow(Window owner, FitsFileInformation[] files, ReportedObjects.Report report, WcsCoordinateTransformer header,
                          PlateSolutionCorrection correction, ApplicationSettings settings, ApplicationStorage storage,
                          VisualizationSettings visualization, ObservingSite site, SkyCatalogue catalogue) {
        super("Asteroids & Comets · " + report.folder().getName());
        AppIcon.apply(this);
        this.files = files.clone();
        this.report = report;
        this.reduction = AstrometricReduction.create(header, correction, this.files);
        this.settings = settings;
        this.storage = storage;
        this.visualization = visualization;
        this.site = site;
        this.header = header;
        this.catalogue = catalogue;
        for (var object : report.objects()) {
            exported.put(object.id(), MpcExport.includedByDefault(object));
            List<MpcObservation> reduced = reduction.reduction() == null || !object.measured()
                    ? List.of() : reduction.reduction().reducePositions(object.positions());
            observations.put(object.id(), reduced);
            selected.put(object.id(), new TreeSet<>(MpcExport.spreadSelection(reduced.size(), MpcExport.DEFAULT_SELECTION)));
        }
        playTimer = new Timer(Math.max(50, visualization.getGifBlinkSpeedMs()), e -> step(1));

        setLayout(new BorderLayout(UiStyle.space(8), UiStyle.space(8)));
        add(header(), BorderLayout.NORTH);
        JTabbedPane lower = new JTabbedPane();
        lower.addTab("Observations", observationsPanel());
        lower.addTab("Light curve", lightCurvePanel());
        JSplitPane centre = new JSplitPane(JSplitPane.VERTICAL_SPLIT, picturePanel(), lower);
        centre.setResizeWeight(0.55);
        JSplitPane left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectsPanel(), centre);
        left.setResizeWeight(0.3);
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, WindowSizing.form(sidePanel()));
        main.setResizeWeight(0.75);
        add(main, BorderLayout.CENTER);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1500, screen.width - 80), Math.min(950, screen.height - 80));
        WindowSizing.install(this, 900, 600);
        setLocationRelativeTo(owner);

        objectsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) show(selectedObject());
        });
        if (!report.objects().isEmpty()) objectsTable.setRowSelectionInterval(0, 0);
        refreshExport();
    }

    /** The steps and where the report came from. */
    private JComponent header() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(8), UiStyle.space(10), 0, UiStyle.space(10)));
        JLabel steps = new JLabel("1 Identify  ·  2 Measure  ·  3 Check  ·  4 Export");
        steps.setFont(UiStyle.sectionFont());
        panel.add(steps, BorderLayout.WEST);
        JLabel source = new JLabel("Report: " + report.folder().getName());
        source.setForeground(UiStyle.helpColor());
        panel.add(source, BorderLayout.EAST);
        if (!lacking().isEmpty()) {
            JLabel old = new JLabel("This report was written before SpacePixels kept each detection's size and signal-to-noise: "
                    + "review, measure and fit orbits as usual, but detect again to export its tracks.");
            old.setForeground(UiStyle.helpColor());
            panel.add(old, BorderLayout.SOUTH);
        }
        return panel;
    }

    /** The objects, with whether each is exported. */
    private JComponent objectsPanel() {
        objectsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        objectsTable.setFillsViewportHeight(true);
        objectsTable.getColumnModel().getColumn(0).setMaxWidth(UiStyle.space(60));
        objectsTable.getColumnModel().getColumn(1).setMaxWidth(UiStyle.space(60));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(title("Objects · " + report.objects().size()), BorderLayout.NORTH);
        panel.add(new JScrollPane(objectsTable), BorderLayout.CENTER);
        // Wide enough for the five columns; the split panes would otherwise squeeze the list to a strip.
        panel.setMinimumSize(new Dimension(UiStyle.space(340), UiStyle.space(200)));
        panel.setPreferredSize(new Dimension(UiStyle.space(380), UiStyle.space(400)));
        return panel;
    }

    /** The blink of the selected object. */
    private JComponent picturePanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(objectCentred);
        group.add(starCentred);
        objectCentred.addActionListener(e -> picture.repaint());
        starCentred.addActionListener(e -> picture.repaint());
        playButton.addActionListener(e -> {
            if (playButton.isSelected()) playTimer.start(); else playTimer.stop();
            playButton.setText(playButton.isSelected() ? "Pause" : "Play");
        });
        JButton previous = new JButton("‹");
        previous.addActionListener(e -> step(-1));
        JButton next = new JButton("›");
        next.addActionListener(e -> step(1));
        frameSlider.addChangeListener(e -> {
            picture.repaint();
            updatePictureLabel();
        });
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        for (JComponent c : new JComponent[]{playButton, previous, next, frameSlider, objectCentred, starCentred}) controls.add(c);
        blackSlider.setValue((int) Math.round(10 * visualization.getAutoStretchBlackSigma()));
        whiteSlider.setValue((int) Math.round(10 * visualization.getAutoStretchWhiteSigma()));
        for (JSlider slider : new JSlider[]{blackSlider, whiteSlider}) {
            slider.setPreferredSize(new Dimension(UiStyle.space(140), slider.getPreferredSize().height));
            slider.addChangeListener(e -> { picture.repaint(); updateStretchLabel(); });
        }
        blackSlider.setToolTipText("Black point: the sky level minus this many noise sigmas shows as black");
        whiteSlider.setToolTipText("White point: the sky level plus this many noise sigmas shows as white");
        JButton resetStretch = new JButton("Reset");
        resetStretch.addActionListener(e -> {
            blackSlider.setValue((int) Math.round(10 * visualization.getAutoStretchBlackSigma()));
            whiteSlider.setValue((int) Math.round(10 * visualization.getAutoStretchWhiteSigma()));
        });
        JPanel stretch = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        stretch.add(new JLabel("Black"));
        stretch.add(blackSlider);
        stretch.add(new JLabel("White"));
        stretch.add(whiteSlider);
        stretch.add(stretchLabel);
        stretch.add(resetStretch);
        stretchLabel.setForeground(UiStyle.helpColor());
        updateStretchLabel();
        pictureLabel.setForeground(UiStyle.helpColor());
        JPanel south = new JPanel(new BorderLayout());
        south.add(stretch, BorderLayout.NORTH);
        south.add(pictureLabel, BorderLayout.SOUTH);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(controls, BorderLayout.NORTH);
        panel.add(picture, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    /** The observations of the selected object and which are reported. */
    private JComponent observationsPanel() {
        observationsTable.setFillsViewportHeight(true);
        observationsTable.getColumnModel().getColumn(0).setMaxWidth(UiStyle.space(45));
        JButton spread = new JButton("Six spread over the arc");
        spread.addActionListener(e -> select(o -> new TreeSet<>(MpcExport.spreadSelection(o.size(), MpcExport.DEFAULT_SELECTION))));
        JButton all = new JButton("All");
        all.addActionListener(e -> select(o -> new TreeSet<>(java.util.stream.IntStream.range(0, o.size()).boxed().toList())));
        JButton none = new JButton("None");
        none.addActionListener(e -> select(o -> new TreeSet<>()));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        buttons.add(spread);
        buttons.add(all);
        buttons.add(none);
        measureButton.setToolTipText("Stacks the frames in three independent groups along the faint mover's motion and measures "
                + "its centroid in each, for the MPC (noted K, stacked)");
        measureButton.addActionListener(e -> measureSubStacks());
        measureButton.setEnabled(false);
        buttons.add(measureButton);
        fitLabel.setForeground(UiStyle.helpColor());
        buttons.add(fitLabel);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(title("Observations · the MPC asks for a few good ones per night, spread over about an hour"), BorderLayout.NORTH);
        panel.add(new JScrollPane(observationsTable), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    /** The light curve of the shown object: measure, summary, plot and CSV. */
    private JComponent lightCurvePanel() {
        curveButton.setToolTipText("Measures the object's light in every frame with aperture photometry (1.5 FWHM, sky from 3 to "
                + "5 FWHM), corrected with comparison stars from the sky catalogue when there are enough; with too few, fainter "
                + "Gaia stars are fetched for this window");
        binChoice.setToolTipText("Averages consecutive frames: less noise for a faint object, but a change faster than a bin is smoothed away");
        binChoice.addActionListener(e -> updateCurve());
        curveButton.addActionListener(e -> measureLightCurve());
        curveSaveButton.addActionListener(e -> saveCurve());
        curveSaveButton.setEnabled(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        buttons.add(curveButton);
        buttons.add(curveSaveButton);
        buttons.add(binChoice);
        curveLabel.setForeground(UiStyle.helpColor());
        JPanel north = new JPanel(new BorderLayout());
        north.add(buttons, BorderLayout.NORTH);
        north.add(curveLabel, BorderLayout.CENTER);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(curvePlot, BorderLayout.CENTER);
        return panel;
    }

    /** Measures the shown object's light in each frame on a worker, with the comparison stars, and keeps the curve. */
    private void measureLightCurve() {
        var object = shown;
        if (object == null || object.positions().isEmpty()) return;
        double fwhm = LightCurve.fwhm(report, object);
        var samples = LightCurve.samples(report, object);
        double speed = LightCurve.pixelsPerMinute(object, samples);
        curveButton.setEnabled(false);
        curveLabel.setText("Measuring " + object.id() + " in " + samples.size() + " frames…");
        new SwingWorker<LightCurve.Curve, String>() {
            @Override protected LightCurve.Curve doInBackground() throws Exception {
                LightCurve.Frames frames = AsteroidsCometsWindow.this::readFrame;
                double[] box = LightCurve.pathBox(samples);
                short[][] middle = frames.pixels(samples.get(samples.size() / 2).frameNumber());
                var candidates = PhotometryStars.candidates(catalogue);
                var comparisons = LightCurve.comparisonStars(middle, header, candidates, box, fwhm);
                String note = null;
                if (comparisons.size() < PhotometryStars.MIN_USABLE && catalogue != null && catalogue.field != null && header != null
                        && catalogue.field.widthDeg * catalogue.field.heightDeg <= MAX_DEEPER_FIELD_SQUARE_DEGREES) {
                    // Too few usable stars in the saved catalogue (all saturated on a large telescope): fetch fainter ones once.
                    if (fetchedStars.isEmpty()) {
                        publish("Fetching fainter Gaia stars for comparison stars…");
                        try {
                            fetchedStars = new eu.startales.spacepixels.skycatalog.SkyCatalogueClient()
                                    .fetchStars(catalogue.field, DEEPER_COMPARISON_STARS);
                        } catch (java.io.IOException cannotFetch) {
                            note = "fainter Gaia stars could not be fetched (" + cannotFetch.getMessage() + ")";
                        }
                    }
                    if (!fetchedStars.isEmpty()) {
                        var deeper = new ArrayList<>(candidates);
                        deeper.addAll(fetchedStars);
                        var more = LightCurve.comparisonStars(middle, header, deeper, box, fwhm);
                        if (more.size() > comparisons.size()) {
                            comparisons = more;
                            note = String.format(Locale.US, "comparison stars from Gaia to G %.1f, fetched for this window", DEEPER_COMPARISON_STARS);
                        }
                    }
                }
                publish("Measuring " + object.id() + " in " + samples.size() + " frames with " + comparisons.size() + " comparison stars…");
                return LightCurve.measure(object.id(), object.measured(), samples, frames, comparisons, fwhm, speed, MAX_SUB_STACK_BYTES,
                        note, () -> closed);
            }

            @Override protected void process(List<String> chunks) {
                if (!closed && shown == object) curveLabel.setText(chunks.getLast());
            }

            @Override protected void done() {
                if (closed) return;
                curveButton.setEnabled(true);
                try {
                    var curve = get();
                    if (curve != null) curves.put(object.id(), curve);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    curveLabel.setText(object.id() + "'s light curve could not be measured: " + cause.getMessage());
                    return;
                }
                if (shown == object) updateCurve();
            }
        }.execute();
    }

    /** One frame's pixels, read from its file. */
    private short[][] readFrame(int frameNumber) throws java.io.IOException {
        var file = files[frameNumber - 1];
        try (Fits fits = new Fits(new File(file.getFilePath()))) {
            Object kernel = FitsInput.getImageHDU(fits).getKernel();
            if (!(kernel instanceof short[][] pixels)) throw new java.io.IOException(file.getFileName() + " is not a 16-bit monochrome frame");
            return pixels;
        } catch (nom.tam.fits.FitsException e) {
            throw new java.io.IOException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /** The bin size chosen, in frames. */
    private int binSize() {
        return LightCurve.BIN_SIZES[Math.max(0, binChoice.getSelectedIndex())];
    }

    /** The fetch's comparison-star warning, as a sentence after the label's text, or empty. */
    private String catalogueWarning() {
        String warning = catalogue == null || catalogue.photometryCheck == null ? null : catalogue.photometryCheck.warning();
        return warning == null ? "" : " " + warning;
    }

    /** Shows the shown object's curve and its summary, or what measuring it would do. */
    private void updateCurve() {
        var curve = shown == null ? null : curves.get(shown.id());
        curvePlot.curve = curve;
        curvePlot.binSize = binSize();
        curvePlot.repaint();
        curveSaveButton.setEnabled(curve != null);
        if (shown == null) { curveLabel.setText(" "); return; }
        if (curve == null) {
            curveLabel.setText(UiStyle.htmlWrappedTo(560, "Measure light curve measures " + shown.id() + "'s light in each frame"
                    + (shown.measured() ? "" : " at its fitted positions; a faint mover's single-frame values are noisy")
                    + (catalogue == null ? ". Without a sky catalogue the curve is not corrected for transparency." : ".")
                    + catalogueWarning()));
            return;
        }
        int size = binSize();
        var summary = size > 1 ? LightCurve.summarise(LightCurve.bin(curve, size)) : LightCurve.summarise(curve);
        String stars = curve.corrected() ? "corrected with " + curve.comparisonStars() + " comparison stars" : "uncorrected";
        if (curve.fewComparisonStars()) {
            stars += String.format(Locale.US, " (only %d usable comparison stars, %d needed for a reliable correction)",
                    curve.comparisonStars(), PhotometryStars.MIN_USABLE);
        }
        curveLabel.setText(UiStyle.htmlWrappedTo(560, String.format(Locale.US, "%s · %d %s · %s%s · %s · aperture %.1f px · "
                        + "median %s · amplitude (5–95 %%) %s mag · scatter %s mag. Relative magnitudes, not calibrated; "
                        + "flagged points (hollow) are left out of the summary%s.", curve.id(), summary.points(),
                size > 1 ? "bins of " + size + " frames" : "frames", stars,
                curve.comparisonNote() == null ? "" : " · " + curve.comparisonNote(),
                curve.onReference() ? "fixed stars subtracted" : "fixed stars not subtracted (path too long)",
                curve.apertureRadius(), magnitude(summary.median()), magnitude(summary.amplitude()), magnitude(summary.scatter()),
                size > 1 ? " and the bins" : "")));
    }

    /** A magnitude to two decimals, or a dash when there is none. */
    private static String magnitude(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.2f", value) : "–";
    }

    /** Writes the curve's CSV where the user chooses. */
    private void saveCurve() {
        var curve = shown == null ? null : curves.get(shown.id());
        if (curve == null) return;
        JFileChooser chooser = WindowSizing.fileChooser(report.folder());
        chooser.setSelectedFile(new File(report.folder(), curve.id().toLowerCase(Locale.ROOT) + "_lightcurve.csv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), LightCurve.csv(curve, binSize()), StandardCharsets.UTF_8);
            curveLabel.setText("Saved " + chooser.getSelectedFile().getName() + ".");
        } catch (Exception e) {
            curveLabel.setText("The light curve was not saved: " + e.getMessage());
        }
    }

    /** Identification, the submission details and the export. */
    private JComponent sidePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(title("Before you report"));
        JLabel guidance = new JLabel(UiStyle.htmlWrappedTo(320, "Anyone may report to the MPC, under an observatory code. "
                + "<b>Your own observations</b> need your own code: apply for one by reporting at least ten numbered "
                + "near-Earth asteroids on two nights each under code XXX. <b>Archival data</b> (such as DECam, W84) uses the "
                + "telescope's code; contact the archive's member of the MPC's SARC list first. Report known objects first, "
                + "to show your astrometry is good; check a possible new object with MPChecker; never send single "
                + "positions or marginal detections."));
        guidance.setForeground(UiStyle.helpColor());
        guidance.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(guidance);
        JButton guide = new JButton("MPC reporting guide");
        guide.setToolTipText(REPORTING_GUIDE);
        guide.addActionListener(e -> browse(REPORTING_GUIDE));
        guide.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(guide);
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Identification"));
        identificationLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(identificationLabel);
        JButton mpchecker = new JButton("MPChecker…");
        mpchecker.setToolTipText("Copies the date, position and code of the middle selected observation and opens the MPC's MPChecker");
        mpchecker.addActionListener(e -> openMpchecker());
        mpchecker.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(mpchecker);
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Orbit · Find_Orb"));
        JButton findOrb = new JButton("Compute orbit");
        findOrb.setToolTipText("Fits a preliminary orbit to the selected observations with Find_Orb on this computer; without "
                + "it, copies them (station code and positions, no names) and opens the online Find_Orb to paste them");
        findOrb.addActionListener(e -> openFindOrb());
        JButton chooseFindOrb = new JButton("Find_Orb program…");
        chooseFindOrb.setToolTipText("Choose fo64.exe in the folder of the console Find_Orb (projectpluto.com)");
        chooseFindOrb.addActionListener(e -> chooseFindOrb());
        JPanel orbitButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        orbitButtons.add(findOrb);
        orbitButtons.add(chooseFindOrb);
        orbitButtons.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(orbitButtons);
        orbitLabel.setForeground(UiStyle.helpColor());
        orbitLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(orbitLabel);
        orbitDiagram.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(orbitDiagram);
        updateOrbit();
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Details for the MPC"));
        AppConfig config = settings.getConfig();
        submitter.setText(config.mpcSubmitter);
        observers.setText(config.mpcObservers);
        measurers.setText(config.mpcMeasurers);
        design.setText(config.mpcTelescopeDesign);
        aperture.setText(config.mpcAperture);
        detector.setText(config.mpcDetector);
        mode.setSelectedItem("CMO".equals(config.mpcMode) ? "CMOS" : "CCD");
        comment.setText(config.mpcComment);
        email.setText(config.mpcEmail);
        email.setToolTipText("Where the MPC sends its replies. Sent only when you test the file with the MPC; never written into the file.");
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(2, 0, 2, UiStyle.space(6));
        String code = config.observatoryCode == null || config.observatoryCode.isBlank() ? "not set (Astrometry Config)" : config.observatoryCode.trim();
        row(form, c, "Observatory code", new JLabel(code));
        JButton check = new JButton("Check with JPL");
        check.setToolTipText("Asks JPL Horizons where this code's station is and compares it with the observing site.");
        check.addActionListener(e -> checkStation(config.observatoryCode));
        check.setEnabled(!code.startsWith("not set"));
        row(form, c, "", check);
        stationLabel.setForeground(UiStyle.helpColor());
        row(form, c, "", stationLabel);
        row(form, c, "Submitter", submitter);
        row(form, c, "Observers", observers);
        row(form, c, "Measurers", measurers);
        row(form, c, "Telescope design", design);
        row(form, c, "Aperture (m)", aperture);
        row(form, c, "Detector", detector);
        row(form, c, "Detector type", mode);
        row(form, c, "Comment", comment);
        row(form, c, "E-mail for MPC replies", email);
        JButton saveDetails = new JButton("Save details");
        saveDetails.addActionListener(e -> saveDetails());
        row(form, c, "", saveDetails);
        form.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(form);
        for (JTextField field : new JTextField[]{submitter, observers, measurers, design, aperture, detector, comment}) {
            field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshExport(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshExport(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshExport(); }
            });
        }
        mode.addActionListener(e -> refreshExport());
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Export · ADES PSV"));
        problemsArea.setEditable(false);
        problemsArea.setLineWrap(true);
        problemsArea.setWrapStyleWord(true);
        problemsArea.setForeground(UiStyle.helpColor());
        problemsArea.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(problemsArea);
        psvArea.setEditable(false);
        psvArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiStyle.bodyFont().getSize() - 1));
        JScrollPane psvScroll = new JScrollPane(psvArea);
        psvScroll.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(psvScroll);
        copyButton.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(psvArea.getText()), null));
        saveButton.addActionListener(e -> savePsv());
        JPanel exportButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        exportButtons.add(copyButton);
        exportButtons.add(saveButton);
        schemaButton.setToolTipText("Checks the file against the ADES submission schema (submit.xsd), fetched once from the IAU's ADES repository");
        schemaButton.addActionListener(e -> checkSchema());
        mpcTestButton.setToolTipText("Sends the file and your e-mail address to the MPC's test service, which checks it like a "
                + "submission but does not process it");
        mpcTestButton.addActionListener(e -> testWithMpc());
        exportButtons.add(schemaButton);
        exportButtons.add(mpcTestButton);
        exportButtons.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(exportButtons);
        JLabel note = new JLabel(UiStyle.htmlWrappedTo(320, "SpacePixels never sends observations to the MPC. Check the "
                + "file, then submit it yourself as the MPC describes."));
        note.setForeground(UiStyle.helpColor());
        panel.add(note);
        panel.setBorder(BorderFactory.createEmptyBorder(0, UiStyle.space(4), UiStyle.space(8), UiStyle.space(10)));
        return panel;
    }

    private static void row(JPanel form, GridBagConstraints c, String label, JComponent control) {
        c.gridx = 0;
        c.gridy++;
        c.weightx = 0;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.weightx = 1;
        form.add(control, c);
    }

    private static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiStyle.sectionFont());
        label.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(4), UiStyle.space(4), UiStyle.space(4), 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private ReportedObjects.ReportedObject selectedObject() {
        int row = objectsTable.getSelectedRow();
        return row < 0 ? null : report.objects().get(objectsTable.convertRowIndexToModel(row));
    }

    /** Shows one object: its identification, observations and pictures, read on a worker. */
    private void show(ReportedObjects.ReportedObject object) {
        shown = object;
        playTimer.stop();
        playButton.setSelected(false);
        playButton.setText("Play");
        observationsModel.fireTableDataChanged();
        updateFit();
        updateOrbit();
        updateCurve();
        if (object == null) {
            identificationLabel.setText(" ");
            return;
        }
        String names = object.names().isEmpty() ? "" : "<b>" + escape(String.join(" or ", object.names())) + "</b><br>";
        measureButton.setEnabled(!object.measured() && object.motion() != null && reduction.reduction() != null);
        String measure = object.measured() ? "" : subStackText(object);
        String reduced = reduction.reduction() == null ? "<br>" + escape(reduction.problem()) : "";
        identificationLabel.setText(UiStyle.htmlWrappedTo(320, object.id() + " · " + statusText(status(object)) + "<br>" + names
                + escape(object.note() == null ? "" : object.note()) + measure + reduced));
        loadPictures(object);
    }

    /** Reads each frame of the object once, cutting its object-centred and star-centred pictures. */
    private void loadPictures(ReportedObjects.ReportedObject object) {
        int request = generation.incrementAndGet();
        if (worker != null) worker.cancel(true);
        pictures = List.of();
        picture.repaint();
        pictureLabel.setText("Reading the frames…");
        var positions = object.positions();
        double minX = positions.stream().mapToDouble(ReportedObjects.Position::x).min().orElse(0);
        double maxX = positions.stream().mapToDouble(ReportedObjects.Position::x).max().orElse(0);
        double minY = positions.stream().mapToDouble(ReportedObjects.Position::y).min().orElse(0);
        double maxY = positions.stream().mapToDouble(ReportedObjects.Position::y).max().orElse(0);
        int fx = (int) Math.floor(minX) - CROP / 2, fy = (int) Math.floor(minY) - CROP / 2;
        int fw = (int) Math.ceil(maxX - minX) + CROP, fh = (int) Math.ceil(maxY - minY) + CROP;
        boolean fixed = fw <= MAX_FIXED && fh <= MAX_FIXED;
        starCentred.setEnabled(fixed);
        if (!fixed) objectCentred.setSelected(true);
        var statistics = new DisplayImageRenderer.DisplayStatistics[2];
        worker = new SwingWorker<>() {
            @Override
            protected List<short[][][]> doInBackground() throws Exception {
                var objectCrops = new ArrayList<short[][]>();
                var fixedCrops = new ArrayList<short[][]>();
                for (var p : positions) {
                    if (isCancelled()) return List.of();
                    int index = p.frameNumber() - 1;
                    if (index < 0 || index >= files.length) throw new java.io.IOException("frame " + p.frameNumber() + " is not in this session");
                    short[][] frame;
                    try (Fits fits = new Fits(new File(files[index].getFilePath()))) {
                        Object kernel = FitsInput.getImageHDU(fits).getKernel();
                        if (!(kernel instanceof short[][] pixels)) {
                            throw new java.io.IOException(files[index].getFileName() + " is not a 16-bit monochrome frame");
                        }
                        frame = pixels;
                    }
                    objectCrops.add(crop(frame, (int) Math.round(p.x()) - CROP / 2, (int) Math.round(p.y()) - CROP / 2, CROP, CROP));
                    if (fixed) fixedCrops.add(crop(frame, fx, fy, fw, fh));
                }
                statistics[0] = DisplayImageRenderer.sharedDisplayStatistics(objectCrops);
                statistics[1] = fixed ? DisplayImageRenderer.sharedDisplayStatistics(fixedCrops) : null;
                var crops = new ArrayList<short[][][]>();
                for (int i = 0; i < objectCrops.size(); i++) crops.add(new short[][][]{objectCrops.get(i), fixed ? fixedCrops.get(i) : null});
                return crops;
            }

            @Override
            protected void done() {
                if (closed || request != generation.get() || isCancelled()) return;
                try {
                    pictures = get();
                    pictureStatistics = statistics;
                    picture.fixedOrigin = new int[]{fx, fy};
                    frameSlider.setMaximum(Math.max(0, pictures.size() - 1));
                    frameSlider.setValue(0);
                    updatePictureLabel();
                    picture.repaint();
                } catch (Exception e) {
                    pictureLabel.setText("The frames could not be read: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    /** A copy of a rectangle of a frame; outside the frame is registration fill. */
    static short[][] crop(short[][] frame, int x, int y, int width, int height) {
        return LightCurve.crop(frame, x, y, width, height);
    }

    /** Names the stretch the sliders set, in sky noise sigmas. */
    private void updateStretchLabel() {
        stretchLabel.setText(String.format(Locale.US, "sky −%.1fσ to +%.1fσ", blackSlider.getValue() / 10.0, whiteSlider.getValue() / 10.0));
    }

    private void step(int delta) {
        if (pictures.isEmpty()) return;
        int n = pictures.size();
        frameSlider.setValue(((frameSlider.getValue() + delta) % n + n) % n);
    }

    private void updatePictureLabel() {
        if (shown == null || pictures.isEmpty()) return;
        int i = Math.min(frameSlider.getValue(), shown.positions().size() - 1);
        var p = shown.positions().get(i);
        var file = p.frameNumber() - 1 < files.length ? files[p.frameNumber() - 1] : null;
        long time = file == null ? 0 : file.getObservationTimestamp() + Math.max(0, file.getExposureDurationMillis()) / 2;
        pictureLabel.setText(String.format(Locale.US, "%s · frame %d of the session · %s UTC · %d of %d · %s", shown.id(),
                p.frameNumber(), time > 0 ? UTC.format(Instant.ofEpochMilli(time)) : "time unknown", i + 1, pictures.size(),
                shown.measured() ? "measured centroid" : "fitted position"));
    }

    /** Applies a new selection rule to the shown object's observations. */
    private void select(java.util.function.Function<List<MpcObservation>, Set<Integer>> rule) {
        if (shown == null) return;
        selected.put(shown.id(), rule.apply(observations.get(shown.id())));
        forgetOrbit();
        observationsModel.fireTableDataChanged();
        refreshObjects();
        updateFit();
        refreshExport();
    }

    /** The straight-line residual RMS of the selected and of all observations. */
    private void updateFit() {
        if (shown == null) { fitLabel.setText(" "); return; }
        var list = observations.get(shown.id());
        double[][] residuals = MpcExport.linearResiduals(list);
        if (residuals.length == 0) { fitLabel.setText(" "); return; }
        var all = java.util.stream.IntStream.range(0, list.size()).boxed().toList();
        fitLabel.setText(String.format(Locale.US, "Straight-line motion RMS: %.2f″ selected, %.2f″ all",
                MpcExport.rms(residuals, new ArrayList<>(selected.get(shown.id()))), MpcExport.rms(residuals, all)));
    }

    /** Rebuilds the file and its problems from the marked objects and the details as typed. */
    private void refreshExport() {
        AppConfig details = settings.getConfig().copy();
        readDetails(details);
        AdesPsv.Context context = MpcExport.context(details);
        var tracklets = new ArrayList<AdesPsv.Tracklet>();
        for (int i = 0; i < report.objects().size(); i++) {
            var object = report.objects().get(i);
            if (!Boolean.TRUE.equals(exported.get(object.id()))) continue;
            var list = observations.get(object.id());
            var chosen = new ArrayList<MpcObservation>();
            for (int index : selected.get(object.id())) if (index < list.size()) chosen.add(list.get(index));
            tracklets.add(MpcExport.tracklet(object, chosen, report.sessionStartMillis(), i));
        }
        var problems = new ArrayList<String>();
        var lacking = lacking().stream().filter(id -> Boolean.TRUE.equals(exported.get(id))).toList();
        if (!lacking.isEmpty()) {
            problems.add(String.join(", ", lacking) + ": this report has no detection sizes and signal-to-noise, so the uncertainties "
                    + "would count only the plate error. Detect again to export.");
        }
        for (var object : report.objects()) {
            if (!Boolean.TRUE.equals(exported.get(object.id()))) continue;
            var stacks = subStacks.getOrDefault(object.id(), List.of());
            for (int k = 0; k < stacks.size(); k++) {
                if (!stacks.get(k).clear()) problems.add(object.id() + " is weak in sub-stack " + (k + 1) + ": " + stacks.get(k).problem()
                        + ". The MPC asks not to report marginal detections.");
            }
        }
        if (reduction.reduction() == null) problems.add(reduction.problem());
        if (stationProblem != null) problems.add(stationProblem);
        problems.addAll(AdesPsv.problems(context, tracklets));
        problemsArea.setText(problems.isEmpty() ? "Ready: " + tracklets.size() + " object" + (tracklets.size() == 1 ? "" : "s")
                + " with " + tracklets.stream().mapToInt(t -> t.observations().size()).sum() + " observations."
                : String.join("\n", problems));
        boolean ready = problems.isEmpty();
        psvArea.setText(ready ? AdesPsv.write(context, tracklets) : "");
        psvArea.setCaretPosition(0);
        copyButton.setEnabled(ready);
        saveButton.setEnabled(ready);
        schemaButton.setEnabled(ready);
        mpcTestButton.setEnabled(ready);
    }

    /** Checks the shown file against the ADES schema on a worker, fetching the schema on first use. */
    private void checkSchema() {
        String psv = psvArea.getText();
        if (psv.isBlank()) return;
        schemaButton.setEnabled(false);
        problemsArea.setText("Checking the file against the ADES submission schema…");
        new SwingWorker<java.util.List<String>, Void>() {
            @Override protected java.util.List<String> doInBackground() throws Exception {
                var folder = eu.startales.spacepixels.mpc.AdesValidation.schemaFolder(java.nio.file.Path.of(System.getProperty("user.home")));
                return eu.startales.spacepixels.mpc.AdesValidation.validate(psv, folder);
            }

            @Override protected void done() {
                if (closed) return;
                schemaButton.setEnabled(!psvArea.getText().isBlank());
                try {
                    var problems = get();
                    problemsArea.setText(problems.isEmpty()
                            ? "The file passes the ADES submission schema. The MPC's test service checks more; Test with the MPC tries it there."
                            : "The ADES submission schema rejects the file:\n" + String.join("\n", problems));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    problemsArea.setText("The schema check could not run: " + cause.getMessage());
                }
            }
        }.execute();
    }

    /** After the user agrees, sends the shown file and the e-mail address to the MPC's test service and shows its answer. */
    private void testWithMpc() {
        String psv = psvArea.getText();
        String address = email.getText().trim();
        if (psv.isBlank()) return;
        if (!address.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            problemsArea.setText("Enter the e-mail address for the MPC's replies first.");
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this, UiStyle.htmlWrappedTo(420,
                        "This sends the file shown, with the names in it, and your e-mail address " + escapeHtml(address)
                                + " to the Minor Planet Center's test service (" + eu.startales.spacepixels.mpc.MpcTestSubmission.TEST_URL
                                + "). The MPC checks it as it would a submission but does not process it: nothing is reported."),
                "Test with the MPC", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        mpcTestButton.setEnabled(false);
        problemsArea.setText("Sending the file to the MPC's test service…");
        new SwingWorker<eu.startales.spacepixels.mpc.MpcTestSubmission.Reply, Void>() {
            @Override protected eu.startales.spacepixels.mpc.MpcTestSubmission.Reply doInBackground() throws Exception {
                return eu.startales.spacepixels.mpc.MpcTestSubmission.send(psv, "SpacePixels test", address, null);
            }

            @Override protected void done() {
                if (closed) return;
                mpcTestButton.setEnabled(!psvArea.getText().isBlank());
                try {
                    var reply = get();
                    problemsArea.setText((reply.received() ? "The MPC's test service answered:\n" : "The MPC's test service refused the request (HTTP "
                            + reply.status() + "):\n") + plainText(reply.text()));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    problemsArea.setText("The MPC's test service could not be reached: " + cause.getMessage());
                }
            }
        }.execute();
    }

    /** An HTML answer as plain text, at most a few hundred characters. */
    private static String plainText(String text) {
        String plain = text.replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").strip();
        return plain.length() > 800 ? plain.substring(0, 800) + "…" : plain;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Copies the details as typed into a settings copy. */
    private void readDetails(AppConfig config) {
        config.mpcSubmitter = submitter.getText().trim();
        config.mpcObservers = observers.getText().trim();
        config.mpcMeasurers = measurers.getText().trim();
        config.mpcTelescopeDesign = design.getText().trim();
        config.mpcAperture = aperture.getText().trim();
        config.mpcDetector = detector.getText().trim();
        config.mpcMode = "CMOS".equals(mode.getSelectedItem()) ? "CMO" : "CCD";
        config.mpcComment = comment.getText().trim();
        config.mpcEmail = email.getText().trim();
    }

    /** Keeps the details in the application settings, written on the settings queue. */
    private void saveDetails() {
        readDetails(settings.getConfig());
        AppConfig snapshot = settings.getConfig().copy();
        storage.submit("Application defaults", () -> {
            settings.save(snapshot);
            return null;
        }).whenComplete((ignored, failure) -> EventQueue.invokeLater(() -> {
            if (!closed) problemsArea.setText(failure == null ? "Details saved." : "The details were not saved: " + failure.getMessage());
            if (!closed && failure == null) refreshExport();
        }));
    }

    /** Asks JPL Horizons where the code's station is, on a worker, and compares it with the observing site. */
    private void checkStation(String code) {
        stationLabel.setText("Asking JPL…");
        new SwingWorker<SolarSystemClient.Station, Void>() {
            @Override protected SolarSystemClient.Station doInBackground() throws Exception {
                return new SolarSystemClient().station(code);
            }

            @Override protected void done() {
                if (closed) return;
                try {
                    var station = get();
                    stationProblem = AdesPsv.stationProblem(station, site);
                    stationText = station.code() + " · " + station.name();
                    stationLabel.setText(UiStyle.htmlWrappedTo(260, escape(stationText) + (stationProblem == null
                            ? site == null ? " · no observing site to compare" : " · agrees with the observing site" : " · does not agree")));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    stationProblem = null;
                    stationLabel.setText(UiStyle.htmlWrappedTo(260, escape(cause.getMessage())));
                }
                refreshExport();
            }
        }.execute();
    }

    /**
     * Copies the MPChecker values of the shown object's middle selected observation (else its middle observation)
     * and opens MPChecker in the browser, where the user pastes them; nothing is sent from here.
     */
    private void openMpchecker() {
        if (shown == null) return;
        var list = observations.get(shown.id());
        if (list.isEmpty()) {
            problemsArea.setText("MPChecker needs a measured position: " + shown.id() + " has none.");
            return;
        }
        var chosen = new ArrayList<>(selected.get(shown.id()));
        int index = chosen.isEmpty() ? list.size() / 2 : chosen.get(chosen.size() / 2);
        String values = MpcExport.mpcheckerValues(list.get(index), settings.getConfig().observatoryCode);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(values), null);
        problemsArea.setText("Copied for MPChecker (" + shown.id() + ", frame " + list.get(index).frameNumber() + "):\n" + values);
        browse(MPCHECKER);
    }

    /**
     * Copies the shown object's selected observations, with the station code only, and opens Find_Orb's page, where the
     * user pastes them. Find_Orb's online service did not read observations sent by a program in tests (it returned
     * another user's earlier result), so the user submits them in the browser and sees the answer there.
     */
    private void openFindOrb() {
        if (shown == null) return;
        var list = observations.get(shown.id());
        var chosen = new ArrayList<MpcObservation>();
        for (int index : selected.get(shown.id())) if (index < list.size()) chosen.add(list.get(index));
        if (chosen.size() < 3) {
            problemsArea.setText("An orbit needs at least three selected observations of " + shown.id() + ".");
            return;
        }
        AppConfig details = settings.getConfig().copy();
        readDetails(details);
        AdesPsv.Context context = MpcExport.context(details);
        if (context.stationCode().isBlank()) {
            problemsArea.setText("Set the IAU observatory code in Astrometry Config first: Find_Orb needs the station.");
            return;
        }
        int sequence = report.objects().indexOf(shown);
        String text = AdesPsv.writeObservations(context, List.of(MpcExport.tracklet(shown, chosen, report.sessionStartMillis(), sequence)));
        File program = details.findOrbExecutable == null || details.findOrbExecutable.isBlank() ? null : new File(details.findOrbExecutable);
        if (program == null || !program.isFile()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            problemsArea.setText("Copied " + chosen.size() + " observations of " + shown.id() + " for Find_Orb: paste them into "
                    + "its box and choose Compute. (With Find_Orb on this computer, choose Find_Orb program… to fit here.)");
            browse(FIND_ORB);
            return;
        }
        var times = chosen.stream().map(o -> Instant.ofEpochMilli(o.midpointMillis()).toString()).toList();
        var object = shown;
        orbitLabel.setText("Fitting " + object.id() + " with Find_Orb…");
        new SwingWorker<FindOrb.Orbit, Void>() {
            @Override protected FindOrb.Orbit doInBackground() throws Exception {
                return FindOrb.fit(program, text, times);
            }

            @Override protected void done() {
                if (closed) return;
                try {
                    orbits.put(object.id(), get());
                    orbitProblems.remove(object.id());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    orbits.remove(object.id());
                    orbitProblems.put(object.id(), cause.getMessage());
                }
                if (shown == object) updateOrbit();
            }
        }.execute();
    }

    /** A fit belongs to the observations it was made from: a changed selection drops it. */
    private void forgetOrbit() {
        if (shown == null) return;
        orbits.remove(shown.id());
        orbitProblems.remove(shown.id());
        updateOrbit();
    }

    /** Lets the user point to fo64.exe; kept in the settings with the other details. */
    private void chooseFindOrb() {
        JFileChooser chooser = WindowSizing.fileChooser(null);
        chooser.setDialogTitle("Choose fo64.exe in the console Find_Orb folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        settings.getConfig().findOrbExecutable = chooser.getSelectedFile().getAbsolutePath();
        saveDetails();
        updateOrbit();
    }

    /** Shows the shown object's fitted orbit, why it could not be fitted, or how to fit one. */
    private void updateOrbit() {
        var orbit = shown == null ? null : orbits.get(shown.id());
        orbitDiagram.orbit = orbit;
        orbitDiagram.repaint();
        if (shown == null) { orbitLabel.setText(" "); return; }
        if (orbit == null) {
            String problem = orbitProblems.get(shown.id());
            String program = settings.getConfig().findOrbExecutable;
            orbitLabel.setText(UiStyle.htmlWrappedTo(320, escape(problem != null ? problem : program == null || program.isBlank()
                    ? "Find_Orb is not set up here: Compute orbit copies the observations for the online Find_Orb."
                    : "Compute orbit fits the selected observations of " + shown.id() + ".")));
            return;
        }
        long used = orbit.residuals().stream().filter(FindOrb.Residual::used).count();
        double span = observations.get(shown.id()).isEmpty() ? 0 : orbit.residuals().size() < 2 ? 0
                : (Instant.parse(orbit.residuals().getLast().isoTime()).toEpochMilli() - Instant.parse(orbit.residuals().getFirst().isoTime()).toEpochMilli()) / 3_600_000.0;
        orbitLabel.setText(UiStyle.htmlWrappedTo(320, String.format(Locale.US, "<b>Preliminary</b> · %d of %d observations used over %.1f h"
                        + " · epoch %s<br>a %s AU · e %s · i %s°<br>q %s AU · node %s° · peri %s°<br>Residual RMS %s · P(NEO) %s · Earth MOID %s"
                        + "<br>An orbit from a short arc is uncertain (±1σ shown); a good fit does not prove the object is real.",
                used, orbit.residuals().size(), span, orbit.epochIso() == null ? "?" : orbit.epochIso().substring(0, 10),
                withSigma(orbit.a(), orbit.aSigma(), 3), withSigma(orbit.e(), orbit.eSigma(), 3), withSigma(orbit.i(), orbit.iSigma(), 1),
                withSigma(orbit.q(), orbit.qSigma(), 3), withSigma(orbit.ascendingNode(), orbit.nodeSigma(), 1),
                withSigma(orbit.argumentOfPerihelion(), orbit.perihelionSigma(), 1),
                orbit.rmsArcsec() == null ? "?" : String.format(Locale.US, "%.2f″", orbit.rmsArcsec()),
                orbit.neoProbability() == null ? "?" : String.format(Locale.US, "%.0f %%", orbit.neoProbability()),
                orbit.earthMoid() == null ? "?" : String.format(Locale.US, "%.3f AU", orbit.earthMoid()))));
    }

    /** "2.36 ± 1.09", or the value alone when Find_Orb gave no uncertainty. */
    private static String withSigma(double value, Double sigma, int decimals) {
        String format = "%." + decimals + "f";
        return String.format(Locale.US, format, value) + (sigma == null ? "" : " ± " + String.format(Locale.US, format, sigma));
    }

    /** Opens an address in the user's browser, when the desktop allows it. */
    private void browse(String address) {
        if (!Desktop.isDesktopSupported()) return;
        try {
            Desktop.getDesktop().browse(java.net.URI.create(address));
        } catch (Exception e) {
            problemsArea.setText("The browser could not be opened: " + e.getMessage() + "\n" + address);
        }
    }

    /** Writes the file where the user chooses. */
    private void savePsv() {
        JFileChooser chooser = WindowSizing.fileChooser(report.folder());
        chooser.setSelectedFile(new File(report.folder(), "mpc_observations.psv"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), psvArea.getText(), StandardCharsets.UTF_8);
            problemsArea.setText("Saved " + chooser.getSelectedFile().getName() + ".");
        } catch (Exception e) {
            problemsArea.setText("The file was not saved: " + e.getMessage());
        }
    }

    /**
     * Repaints the object rows without replacing them: a full table change would clear the selection, and with it the
     * shown object and its observations.
     */
    private void refreshObjects() {
        if (!report.objects().isEmpty()) objectsModel.fireTableRowsUpdated(0, report.objects().size() - 1);
    }

    /** The tracks whose measured positions lack the detection's size or signal-to-noise (reports before 2026.11-03). */
    private List<String> lacking() {
        return report.objects().stream().filter(ReportedObjects.ReportedObject::measured)
                .filter(o -> o.positions().stream().anyMatch(p -> p.fwhm() == null || p.snr() == null))
                .map(ReportedObjects.ReportedObject::id).toList();
    }

    /** The report's status, or Weak for a faint mover with a marginal sub-stack. */
    private String status(ReportedObjects.ReportedObject object) {
        var stacks = subStacks.get(object.id());
        return stacks != null && stacks.stream().anyMatch(m -> !m.clear()) ? WEAK : object.status();
    }

    /** What the faint mover's sub-stacks showed, or how to measure them. */
    private String subStackText(ReportedObjects.ReportedObject object) {
        var stacks = subStacks.get(object.id());
        if (stacks == null) {
            return "<br>A faint mover's positions here are fitted, not measured. <b>Measure in sub-stacks</b> stacks its frames "
                    + "in " + SUB_STACKS + " independent groups and measures it in each, as the MPC requires for stacked observations.";
        }
        var text = new StringBuilder("<br>Sub-stacks:");
        for (int k = 0; k < stacks.size(); k++) {
            var m = stacks.get(k);
            text.append(String.format(Locale.US, "<br>%d · %d frames · %s UTC · SNR %.1f · %s", k + 1, m.frames(),
                    UTC.format(Instant.ofEpochMilli(m.midpointMillis())), m.snr(), m.clear() ? "clear" : escape(m.problem())));
        }
        return text.toString();
    }

    /**
     * Reads the faint mover's frames once, cutting one region around its whole path from each, and measures it in
     * independent sub-stacks on a worker; the centroids become its observations, noted K.
     */
    private void measureSubStacks() {
        var object = shown;
        if (object == null || object.motion() == null || reduction.reduction() == null) return;
        var motion = object.motion();
        double fwhm = report.faintMoverFwhm() == null ? 3 : report.faintMoverFwhm();
        int margin = (int) Math.ceil(Math.max(10, 5 * fwhm)) + 4;
        var positions = object.positions();
        int x0 = (int) Math.floor(positions.stream().mapToDouble(ReportedObjects.Position::x).min().orElse(0)) - margin;
        int y0 = (int) Math.floor(positions.stream().mapToDouble(ReportedObjects.Position::y).min().orElse(0)) - margin;
        int width = (int) Math.ceil(positions.stream().mapToDouble(ReportedObjects.Position::x).max().orElse(0)) + margin - x0;
        int height = (int) Math.ceil(positions.stream().mapToDouble(ReportedObjects.Position::y).max().orElse(0)) + margin - y0;
        if ((long) width * height * positions.size() * 2 > MAX_SUB_STACK_BYTES) {
            problemsArea.setText(object.id() + "'s path is too long to stack here (" + width + " × " + height + " px over "
                    + positions.size() + " frames).");
            return;
        }
        measureButton.setEnabled(false);
        problemsArea.setText("Measuring " + object.id() + " in " + SUB_STACKS + " sub-stacks…");
        new SwingWorker<List<SubStackAstrometry.Measurement>, Void>() {
            private final List<Integer> frameIndexes = new ArrayList<>();

            @Override protected List<SubStackAstrometry.Measurement> doInBackground() throws Exception {
                var cutouts = new ArrayList<SubStackAstrometry.Cutout>();
                for (var p : positions) {
                    if (closed) return List.of();
                    int index = p.frameNumber() - 1;
                    var file = files[index];
                    short[][] frame;
                    try (Fits fits = new Fits(new File(file.getFilePath()))) {
                        Object kernel = FitsInput.getImageHDU(fits).getKernel();
                        if (!(kernel instanceof short[][] pixels)) throw new java.io.IOException(file.getFileName() + " is not a 16-bit monochrome frame");
                        frame = pixels;
                    }
                    long midpoint = file.getObservationTimestamp() + Math.max(0, file.getExposureDurationMillis()) / 2;
                    cutouts.add(new SubStackAstrometry.Cutout(x0, y0, crop(frame, x0, y0, width, height), midpoint, file.getExposureDurationMillis()));
                    frameIndexes.add(index);
                }
                return SubStackAstrometry.measure(cutouts, motion.x(), motion.y(), motion.referenceMillis(), motion.vxPerMinute(),
                        motion.vyPerMinute(), SUB_STACKS, fwhm);
            }

            @Override protected void done() {
                if (closed) return;
                try {
                    var stacks = get();
                    var list = new ArrayList<MpcObservation>();
                    for (var m : stacks) {
                        int nearest = frameIndexes.get((m.firstCutout() + m.lastCutout()) / 2);
                        var o = reduction.reduction().observeStack(nearest, m.midpointMillis(), m.exposureMillis(), m.x(), m.y(), fwhm, m.snr());
                        if (o != null) list.add(o);
                    }
                    subStacks.put(object.id(), stacks);
                    observations.put(object.id(), list);
                    selected.put(object.id(), new TreeSet<>(java.util.stream.IntStream.range(0, list.size()).boxed().toList()));
                    problemsArea.setText(" ");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    problemsArea.setText(object.id() + " could not be measured: " + cause.getMessage());
                }
                refreshObjects();
                if (shown == object) show(object);
                refreshExport();
            }
        }.execute();
    }

    static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case WEAK -> "Weak";
            case ReportedObjects.KNOWN -> "Known";
            case ReportedObjects.CHECK -> "Check";
            case ReportedObjects.NO_KNOWN_MATCH -> "No known match";
            default -> "Not compared";
        };
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void closeForSessionChange() {
        dispose();
    }

    @Override
    public void dispose() {
        if (!closed) {
            closed = true;
            generation.incrementAndGet();
            playTimer.stop();
            if (worker != null) worker.cancel(true);
            worker = null;
            pictures = List.of();
        }
        super.dispose();
    }

    /** The objects: export mark, ID, status, identification and selected observations. */
    private final class ObjectsModel extends AbstractTableModel {
        private final String[] columns = {"Export", "Finding", "Status", "Identification", "Observations"};

        @Override public int getRowCount() { return report.objects().size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }

        @Override public boolean isCellEditable(int row, int column) {
            return column == 0 && !observations.get(report.objects().get(row).id()).isEmpty();
        }

        @Override public Object getValueAt(int row, int column) {
            var object = report.objects().get(row);
            return switch (column) {
                case 0 -> exported.get(object.id());
                case 1 -> object.id();
                case 2 -> statusText(status(object));
                case 3 -> String.join(" or ", object.names());
                default -> object.measured() ? selected.get(object.id()).size() + " of " + observations.get(object.id()).size()
                        : subStacks.containsKey(object.id()) ? subStacks.get(object.id()).size() + " sub-stacks" : "fitted only";
            };
        }

        @Override public void setValueAt(Object value, int row, int column) {
            exported.put(report.objects().get(row).id(), Boolean.TRUE.equals(value));
            fireTableCellUpdated(row, column);
            refreshExport();
        }
    }

    /** The shown object's observations: use, frame, time, position, straight-line residual, SNR and uncertainty. */
    private final class ObservationsModel extends AbstractTableModel {
        private final String[] columns = {"Use", "Frame", "UTC (mid)", "RA (°)", "Dec (°)", "Line O−C RA″", "Line O−C Dec″", "SNR", "±″"};

        private List<MpcObservation> list() { return shown == null ? List.of() : observations.get(shown.id()); }

        @Override public int getRowCount() { return list().size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return column == 0; }

        @Override public Object getValueAt(int row, int column) {
            var o = list().get(row);
            double[][] residuals = MpcExport.linearResiduals(list());
            var position = shown.positions().stream().filter(p -> p.frameNumber() == o.frameNumber()).findFirst().orElse(null);
            return switch (column) {
                case 0 -> selected.get(shown.id()).contains(row);
                case 1 -> Integer.toString(o.frameNumber());
                case 2 -> UTC.format(Instant.ofEpochMilli(o.midpointMillis()));
                case 3 -> String.format(Locale.US, "%.6f", o.raDegrees());
                case 4 -> String.format(Locale.US, "%+.6f", o.decDegrees());
                case 5 -> residuals.length == 0 ? "" : String.format(Locale.US, "%+.2f", residuals[row][0]);
                case 6 -> residuals.length == 0 ? "" : String.format(Locale.US, "%+.2f", residuals[row][1]);
                case 7 -> position == null || position.snr() == null ? "" : String.format(Locale.US, "%.1f", position.snr());
                default -> String.format(Locale.US, "%.3f", o.rmsRaArcsec());
            };
        }

        @Override public void setValueAt(Object value, int row, int column) {
            Set<Integer> set = selected.get(shown.id());
            if (Boolean.TRUE.equals(value)) set.add(row); else set.remove(row);
            forgetOrbit();
            fireTableCellUpdated(row, column);
            refreshObjects();
            updateFit();
            refreshExport();
        }
    }

    /**
     * The fitted orbit seen from above the ecliptic: the Sun, the planets' mean orbits to Jupiter with their places on
     * the epoch date, the orbit (the near part of an unbound one) and the object's place. Scaled to the orbit, at least
     * to Mars and at most to Jupiter's distance.
     */
    private static final class OrbitDiagram extends JComponent {
        FindOrb.Orbit orbit;

        OrbitDiagram() {
            setPreferredSize(new Dimension(UiStyle.space(300), UiStyle.space(300)));
            setMaximumSize(new Dimension(UiStyle.space(320), UiStyle.space(320)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (orbit == null) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int size = Math.min(getWidth(), getHeight());
                g.setColor(new Color(16, 18, 24));
                g.fillRect(0, 0, size, size);
                double extent = Math.max(1.7, Math.min(5.6, orbit.bound() ? orbit.a() * (1 + orbit.e()) * 1.08 : 3));
                double scale = size / 2.0 / extent, cx = size / 2.0, cy = size / 2.0;
                g.setFont(UiStyle.bodyFont().deriveFont(UiStyle.bodyFont().getSize2D() - 2));
                for (int p = 0; p < OrbitGeometry.PLANET_DISTANCES.length; p++) {
                    double r = OrbitGeometry.PLANET_DISTANCES[p] * scale;
                    if (OrbitGeometry.PLANET_DISTANCES[p] > extent * 1.02) continue;
                    g.setColor(new Color(90, 100, 120));
                    g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
                    double[] place = OrbitGeometry.planetAt(p, orbit.epochJd());
                    double px = cx + place[0] * scale, py = cy - place[1] * scale;
                    g.setColor(p == 2 ? new Color(90, 160, 255) : new Color(160, 170, 190));
                    g.fill(new Ellipse2D.Double(px - 3, py - 3, 6, 6));
                    if (p >= 2) g.drawString(OrbitGeometry.PLANET_NAMES[p], (float) px + 5, (float) py - 4);
                }
                g.setColor(new Color(255, 210, 90));
                g.fill(new Ellipse2D.Double(cx - 4, cy - 4, 8, 8));
                double[][] path = OrbitGeometry.path(orbit, 240, extent * 1.5);
                var line = new java.awt.geom.Path2D.Double();
                for (int k = 0; k < path.length; k++) {
                    double x = cx + path[k][0] * scale, y = cy - path[k][1] * scale;
                    if (k == 0) line.moveTo(x, y); else line.lineTo(x, y);
                }
                if (orbit.bound()) line.closePath();
                g.setColor(new Color(80, 225, 210));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(line);
                double[] object = OrbitGeometry.atEpoch(orbit);
                if (object != null) {
                    double ox = cx + object[0] * scale, oy = cy - object[1] * scale;
                    g.fill(new Ellipse2D.Double(ox - 4, oy - 4, 8, 8));
                }
                g.setColor(new Color(170, 175, 190));
                g.drawString("Ecliptic, from the north · planets on mean circular orbits", 6, size - 6);
            } finally {
                g.dispose();
            }
        }
    }

    /**
     * A light curve: relative magnitude against UTC, brighter upwards, with 1-sigma error bars; flagged points hollow;
     * the median as a dashed line. With bins, the single frames are faint and the bins drawn over them.
     */
    private static final class LightCurvePlot extends JComponent {
        LightCurve.Curve curve;
        int binSize = 1;

        LightCurvePlot() {
            setPreferredSize(new Dimension(UiStyle.space(500), UiStyle.space(220)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (curve == null) return;
            var points = curve.points().stream().filter(p -> Double.isFinite(p.magnitude())).toList();
            if (points.size() < 2) return;
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setFont(UiStyle.bodyFont().deriveFont(UiStyle.bodyFont().getSize2D() - 2));
                int left = UiStyle.space(56), right = UiStyle.space(12), top = UiStyle.space(10), bottom = UiStyle.space(28);
                int w = getWidth() - left - right, h = getHeight() - top - bottom;
                if (w < 50 || h < 50) return;
                long t0 = points.getFirst().midpointMillis(), t1 = points.getLast().midpointMillis();
                var bins = binSize > 1 ? LightCurve.bin(curve, binSize) : List.<LightCurve.Bin>of();
                var summary = binSize > 1 ? LightCurve.summarise(bins) : LightCurve.summarise(curve);
                double[] mags = points.stream().filter(LightCurve::counted).mapToDouble(LightCurve.Point::magnitude).sorted().toArray();
                if (mags.length == 0) mags = points.stream().mapToDouble(LightCurve.Point::magnitude).sorted().toArray();
                double low = mags[(int) (0.02 * (mags.length - 1))], high = mags[(int) Math.ceil(0.98 * (mags.length - 1))];
                double pad = Math.max(0.05, (high - low) * 0.25);
                double bright = low - pad, faint = high + pad;
                g.setColor(new Color(16, 18, 24));
                g.fillRect(left, top, w, h);
                g.setColor(new Color(170, 175, 190));
                g.drawString(String.format(Locale.US, "%.2f", bright), 4, top + 10);
                g.drawString(String.format(Locale.US, "%.2f", faint), 4, top + h);
                g.drawString(UTC.format(Instant.ofEpochMilli(t0)), left, top + h + UiStyle.space(16));
                String end = UTC.format(Instant.ofEpochMilli(t1)) + " UTC";
                g.drawString(end, left + w - g.getFontMetrics().stringWidth(end), top + h + UiStyle.space(16));
                java.util.function.DoubleUnaryOperator yOf = m -> top + h * (m - bright) / (faint - bright);
                java.util.function.LongToDoubleFunction xOf = t -> left + (t1 > t0 ? w * (t - t0) / (double) (t1 - t0) : w / 2.0);
                if (Double.isFinite(summary.median())) {
                    g.setColor(new Color(120, 125, 140));
                    g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{4, 4}, 0));
                    double y = yOf.applyAsDouble(summary.median());
                    g.draw(new java.awt.geom.Line2D.Double(left, y, left + w, y));
                }
                g.setStroke(new BasicStroke(1));
                boolean binned = !bins.isEmpty();
                for (var p : points) {
                    double x = xOf.applyAsDouble(p.midpointMillis()), y = yOf.applyAsDouble(p.magnitude());
                    double e = p.magnitudeError();
                    g.setColor(new Color(80, 225, 210, binned ? 50 : 140));
                    if (Double.isFinite(e)) g.draw(new java.awt.geom.Line2D.Double(x, yOf.applyAsDouble(p.magnitude() - e), x, yOf.applyAsDouble(p.magnitude() + e)));
                    g.setColor(new Color(80, 225, 210, binned ? 90 : 255));
                    var dot = new Ellipse2D.Double(x - 2.5, y - 2.5, 5, 5);
                    if (LightCurve.counted(p)) g.fill(dot); else g.draw(dot);
                }
                g.setStroke(new BasicStroke(1.5f));
                for (var b : bins) {
                    if (!Double.isFinite(b.magnitude())) continue;
                    double x = xOf.applyAsDouble(b.midpointMillis()), y = yOf.applyAsDouble(b.magnitude());
                    double e = b.magnitudeError();
                    g.setColor(new Color(255, 196, 90));
                    if (Double.isFinite(e)) g.draw(new java.awt.geom.Line2D.Double(x, yOf.applyAsDouble(b.magnitude() - e), x, yOf.applyAsDouble(b.magnitude() + e)));
                    g.fill(new Ellipse2D.Double(x - 3.5, y - 3.5, 7, 7));
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Draws the shown picture, enlarged without smoothing, with a ring at the object. */
    private final class Picture extends JComponent {
        int[] fixedOrigin = {0, 0};

        Picture() {
            setPreferredSize(new Dimension(UiStyle.space(360), UiStyle.space(300)));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                if (pictures.isEmpty() || shown == null) return;
                int i = Math.min(frameSlider.getValue(), pictures.size() - 1);
                boolean fixed = starCentred.isSelected() && pictures.get(i)[1] != null;
                var statistics = pictureStatistics[fixed ? 1 : 0];
                if (statistics == null) return;
                double[] levels = statistics.levels(blackSlider.getValue() / 10.0, whiteSlider.getValue() / 10.0);
                BufferedImage image = DisplayImageRenderer.createDisplayImageAtLevels(pictures.get(i)[fixed ? 1 : 0], levels[0], levels[1]);
                double scale = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
                int w = (int) (image.getWidth() * scale), h = (int) (image.getHeight() * scale);
                int left = (getWidth() - w) / 2, top = (getHeight() - h) / 2;
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(image, left, top, w, h, null);
                var p = shown.positions().get(Math.min(i, shown.positions().size() - 1));
                double px = fixed ? p.x() - fixedOrigin[0] : p.x() - (Math.round(p.x()) - CROP / 2);
                double py = fixed ? p.y() - fixedOrigin[1] : p.y() - (Math.round(p.y()) - CROP / 2);
                double r = Math.max(6, 4 * scale);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(80, 225, 210));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(new Ellipse2D.Double(left + (px + 0.5) * scale - r, top + (py + 0.5) * scale - r, 2 * r, 2 * r));
            } finally {
                g.dispose();
            }
        }
    }
}
