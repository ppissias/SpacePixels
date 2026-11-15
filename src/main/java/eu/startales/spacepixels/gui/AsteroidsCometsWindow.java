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
import eu.startales.spacepixels.mpc.NightLinks;
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
    /** Bill Gray's page for the downloadable Find_Orb (Windows builds, and how to build it elsewhere). */
    static final String FIND_ORB_DOWNLOAD = "https://www.projectpluto.com/find_orb.htm";
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
    /** The report's objects; replaced when Compare again changes an identification. EDT only. */
    private ReportedObjects.Report report;
    /** The session's current saved asteroid and comet predictions, which may be newer than the report's. */
    private final java.util.function.Supplier<eu.startales.spacepixels.solarsystem.SolarSystemObjects> predictions;
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
    private final WrappingHelpLabel compareLabel = new WrappingHelpLabel(" ", 320);
    /** Identifications made after the report, by object, as saved beside it. EDT only. */
    private final Map<String, eu.startales.spacepixels.mpc.LaterIdentifications.Entry> later;
    private final JButton identifyAllButton = new JButton("Identify all…");
    /** The running Identify all, while its button reads Cancel; null otherwise. EDT only. */
    private SwingWorker<?, ?> identifyWorker;
    /** The running light-curve and sub-stack measurements, while their buttons read Cancel; null otherwise. EDT only. */
    private SwingWorker<?, ?> curveWorker, subStackWorker;
    /** The full result of the last Compare again or Identify all, which the ⓘ link shows; null before one. */
    private String comparisonDetails;
    private final JButton detailsLink = AccessibleControls.link("ⓘ", "Details of the last comparison", this::showComparisonDetails);
    private final JButton showOrbitButton = new JButton("Show orbit");
    /** The open orbit window, or null. */
    private JDialog orbitWindow;
    /** Known objects within 1′ of each object from the last Identify all, by object; empty before it. EDT only. */
    private final Map<String, List<eu.startales.spacepixels.mpc.NearbyKnown.Match>> nearby = new HashMap<>();
    private final JComboBox<String> binChoice = new JComboBox<>(new String[]{"Single frames", "Bins of 2", "Bins of 4"});
    private final Map<String, LightCurve.Curve> curves = new HashMap<>();
    private final LightCurvePlot curvePlot = new LightCurvePlot();
    private final WrappingHelpLabel curveLabel = new WrappingHelpLabel(" ", 560);
    private final JButton curveButton = new JButton("Measure light curve");
    private final JButton curveSaveButton = new JButton("Save CSV…");

    private final Map<String, Boolean> exported = new HashMap<>();
    private final Map<String, Set<Integer>> selected = new HashMap<>();
    private final Map<String, List<MpcObservation>> observations = new HashMap<>();
    private final Map<String, FindOrb.Orbit> orbits = new HashMap<>();
    private final Map<String, String> orbitProblems = new HashMap<>();
    /** The orbit from this night alone, by object, when the orbit in {@link #orbits} also uses linked nights. */
    private final Map<String, FindOrb.Orbit> singleNightOrbits = new HashMap<>();
    /** How many nights each orbit in {@link #orbits} was fitted to. */
    private final Map<String, Integer> orbitNights = new HashMap<>();
    /** This report as one night of a link: its objects, reduction and observing site. */
    private final NightLinks.Night thisNight;
    /** Each linked object's other nights, by object; an object without links is absent. EDT only. */
    private final Map<String, OtherNights> otherNights = new HashMap<>();
    /** Reports opened as other nights, by folder, kept while the window is open; filled on workers. */
    private final Map<String, NightLinks.Night> openedNights = new java.util.concurrent.ConcurrentHashMap<>();
    private final WrappingHelpLabel nightsLabel = new WrappingHelpLabel(" ", 320);
    private final JButton linkButton = new JButton("Link another night…");
    private final JButton unlinkButton = new JButton("Unlink");
    /** The running read of the linked nights; null otherwise. EDT only. */
    private SwingWorker<?, ?> nightsWorker;
    /** UTC date of a night, by the start of its first exposure. */
    private static final DateTimeFormatter NIGHT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(ZoneOffset.UTC);
    /** Each measured faint mover's sub-stacks, in time order. */
    private final Map<String, List<SubStackAstrometry.Measurement>> subStacks = new HashMap<>();
    private final JButton measureButton = new JButton("Measure in sub-stacks");
    /** Sub-stacks a faint mover is measured in; the MPC asks for at least two per night, preferably three to five. */
    static final int SUB_STACKS = 3;
    private final WrappingHelpLabel orbitLabel = new WrappingHelpLabel(" ", 320);
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
    private final WrappingHelpLabel identificationLabel = new WrappingHelpLabel(" ", 320);
    private final WrappingHelpLabel stationLabel = new WrappingHelpLabel(" ", 260);
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
     * @param predictions the session's current saved asteroid and comet predictions (null when none)
     */
    AsteroidsCometsWindow(Window owner, FitsFileInformation[] files, ReportedObjects.Report report, WcsCoordinateTransformer header,
                          PlateSolutionCorrection correction, ApplicationSettings settings, ApplicationStorage storage,
                          VisualizationSettings visualization, ObservingSite site, SkyCatalogue catalogue,
                          java.util.function.Supplier<eu.startales.spacepixels.solarsystem.SolarSystemObjects> predictions) {
        super("Asteroids & Comets · " + report.folder().getName());
        AppIcon.apply(this);
        this.files = files.clone();
        // Identifications made after the report (Compare again, Identify all) come back from the report folder.
        this.later = new java.util.LinkedHashMap<>(eu.startales.spacepixels.mpc.LaterIdentifications.load(report.folder()));
        this.report = later.isEmpty() ? report : report.withObjects(eu.startales.spacepixels.mpc.LaterIdentifications.apply(report.objects(), later));
        if (!later.isEmpty()) {
            compareLabel.setHtml(later.size() + " identification" + (later.size() == 1 ? "" : "s")
                    + " made after the report (Compare again, Identify all) are shown, from "
                    + eu.startales.spacepixels.mpc.LaterIdentifications.FILE_NAME + " beside it.");
        }
        this.reduction = AstrometricReduction.create(header, correction, this.files);
        this.settings = settings;
        this.storage = storage;
        this.visualization = visualization;
        this.site = site;
        this.header = header;
        this.catalogue = catalogue;
        this.predictions = predictions == null ? () -> null : predictions;
        for (var object : report.objects()) {
            exported.put(object.id(), MpcExport.includedByDefault(object));
            List<MpcObservation> reduced = reduction.reduction() == null || !object.measured()
                    ? List.of() : reduction.reduction().reducePositions(object.positions());
            observations.put(object.id(), reduced);
            selected.put(object.id(), new TreeSet<>(MpcExport.spreadSelection(reduced.size(), MpcExport.DEFAULT_SELECTION)));
        }
        this.thisNight = new NightLinks.Night(this.report, reduction, site);
        playTimer =new Timer(Math.max(50, visualization.getGifBlinkSpeedMs()), e -> step(1));

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
        readOtherNights();
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
        JButton compare = new JButton("Compare again");
        compare.setToolTipText("Compares every object with the session's saved asteroid and comet predictions again, by the "
                + "report's rules: after Fetch Asteroids and Comets, without detecting again. Nothing is sent.");
        compare.addActionListener(e -> compareAgain());
        identifyAllButton.setToolTipText("Asks JPL and SkyBoT for every known asteroid and comet in the field at mid-session, "
                + "without a magnitude limit, and lists those within 1′ of each object, as MPChecker would. Sends the field, the time "
                + "and the observatory code.");
        identifyAllButton.addActionListener(e -> identifyAll());
        JPanel identifyButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        identifyButtons.add(compare);
        identifyButtons.add(identifyAllButton);
        detailsLink.setVisible(false);
        identifyButtons.add(detailsLink);
        identifyButtons.setAlignmentX(LEFT_ALIGNMENT);
        compareLabel.setForeground(UiStyle.helpColor());
        compareLabel.setAlignmentX(LEFT_ALIGNMENT);
        JLabel heading = title("Objects · " + report.objects().size());
        heading.setAlignmentX(LEFT_ALIGNMENT);
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(heading);
        north.add(identifyButtons);
        north.add(compareLabel);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
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
        // Clear of the split pane's handle, which would cover the start of the text.
        panel.setBorder(BorderFactory.createEmptyBorder(0, UiStyle.space(8), 0, 0));
        return panel;
    }

    /** Measures the shown object's light in each frame on a worker, with the comparison stars, and keeps the curve. */
    private void measureLightCurve() {
        if (curveWorker != null) {
            curveWorker.cancel(true);
            return;
        }
        var object = shown;
        if (object == null || object.positions().isEmpty()) return;
        double fwhm = LightCurve.fwhm(report, object);
        var samples = LightCurve.samples(report, object);
        double speed = LightCurve.pixelsPerMinute(object, samples);
        curveButton.setText("Cancel light curve");
        curveLabel.setText("Measuring " + object.id() + " in " + samples.size() + " frames…");
        curveWorker = new SwingWorker<LightCurve.Curve, String>() {
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
                        note, () -> closed || isCancelled());
            }

            @Override protected void process(List<String> chunks) {
                if (!closed && shown == object) curveLabel.setText(chunks.getLast());
            }

            @Override protected void done() {
                if (curveWorker == this) curveWorker = null;
                if (closed) return;
                curveButton.setText("Measure light curve");
                if (isCancelled()) {
                    curveLabel.setText(object.id() + "'s light curve was cancelled.");
                    return;
                }
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
        };
        curveWorker.execute();
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
            curveLabel.setHtml("Measure light curve measures " + shown.id() + "'s light in each frame"
                    + (shown.measured() ? "" : " at its fitted positions; a faint mover's single-frame values are noisy")
                    + (catalogue == null ? ". Without a sky catalogue the curve is not corrected for transparency." : ".")
                    + catalogueWarning());
            return;
        }
        int size = binSize();
        var summary = size > 1 ? LightCurve.summarise(LightCurve.bin(curve, size)) : LightCurve.summarise(curve);
        String stars = curve.corrected() ? "corrected with " + curve.comparisonStars() + " comparison stars" : "uncorrected";
        if (curve.fewComparisonStars()) {
            stars += String.format(Locale.US, " (only %d usable comparison stars, %d needed for a reliable correction)",
                    curve.comparisonStars(), PhotometryStars.MIN_USABLE);
        }
        curveLabel.setHtml(String.format(Locale.US, "%s · %d %s · %s%s · %s · aperture %.1f px · "
                        + "median %s · amplitude (5–95 %%) %s mag · scatter %s mag. Relative magnitudes, not calibrated; "
                        + "flagged points (hollow) are left out of the summary%s.", curve.id(), summary.points(),
                size > 1 ? "bins of " + size + " frames" : "frames", stars,
                curve.comparisonNote() == null ? "" : " · " + curve.comparisonNote(),
                curve.onReference() ? "fixed stars subtracted" : "fixed stars not subtracted (path too long)",
                curve.apertureRadius(), magnitude(summary.median()), magnitude(summary.amplitude()), magnitude(summary.scatter()),
                size > 1 ? " and the bins" : ""));
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
        File target = chooser.getSelectedFile();
        String text = LightCurve.csv(curve, binSize());
        writeOnWorker(() -> Files.writeString(target.toPath(), text, StandardCharsets.UTF_8), problem -> curveLabel.setText(problem == null
                ? "Saved " + target.getName() + "." : "The light curve was not saved: " + problem));
    }

    /** Identification, the submission details and the export. */
    private JComponent sidePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(title("Before you report"));
        panel.add(new WrappingHelpLabel("Report under your own observatory code, or for archive data under the telescope's, "
                + "after contacting its reviewer. Report known objects first; never single positions or marginal detections.", 320));
        JButton guide = new JButton("MPC reporting guide");
        guide.setToolTipText(REPORTING_GUIDE);
        guide.addActionListener(e -> browse(REPORTING_GUIDE));
        guide.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(guide);
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Identification"));
        identificationLabel.setForeground(UIManager.getColor("Label.foreground"));
        panel.add(identificationLabel);
        JButton mpchecker = new JButton("MPChecker…");
        mpchecker.setToolTipText("Copies the date, position and code of the middle selected observation and opens the MPC's MPChecker");
        mpchecker.addActionListener(e -> openMpchecker());
        mpchecker.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(mpchecker);
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Other nights"));
        nightsLabel.setForeground(UIManager.getColor("Label.foreground"));
        panel.add(nightsLabel);
        linkButton.setToolTipText("Choose the report of another night and the object there that is this one; the link is kept "
                + "beside both reports (" + NightLinks.FILE_NAME + ")");
        linkButton.addActionListener(e -> linkAnotherNight());
        unlinkButton.setToolTipText("Removes this object's links to other nights, here and beside the other reports");
        unlinkButton.addActionListener(e -> unlinkNights());
        JPanel nightButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        nightButtons.add(linkButton);
        nightButtons.add(unlinkButton);
        nightButtons.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(nightButtons);
        updateNights();
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Orbit · Find_Orb"));
        JButton findOrb = new JButton("Compute orbit");
        findOrb.setToolTipText("Fits a preliminary orbit to the selected observations with Find_Orb on this computer; without "
                + "it, copies them (station code and positions, no names) and opens the online Find_Orb to paste them");
        findOrb.addActionListener(e -> openFindOrb());
        JButton chooseFindOrb = new JButton("Find_Orb program…");
        chooseFindOrb.setToolTipText("Choose fo64.exe (Windows) or fo (Linux, BSD, macOS) in the folder of the console Find_Orb");
        chooseFindOrb.addActionListener(e -> chooseFindOrb());
        showOrbitButton.setToolTipText("Opens the fitted orbit, drawn from above the ecliptic, with its elements");
        showOrbitButton.addActionListener(e -> showOrbitWindow());
        JPanel orbitButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        orbitButtons.add(findOrb);
        orbitButtons.add(chooseFindOrb);
        orbitButtons.add(showOrbitButton);
        orbitButtons.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(orbitButtons);
        panel.add(orbitLabel);
        panel.add(new WrappingHelpLabel("Find_Orb runs on this computer when installed. Windows: download the console Find_Orb "
                + "and fo64.exe into one folder. Linux, BSD and macOS: build fo from Bill Gray's source code.", 320));
        JButton getFindOrb = AccessibleControls.link("Get Find_Orb", "Open the Find_Orb download page", () -> browse(FIND_ORB_DOWNLOAD));
        getFindOrb.setToolTipText(FIND_ORB_DOWNLOAD);
        getFindOrb.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(getFindOrb);
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
        panel.add(new WrappingHelpLabel("SpacePixels never sends observations to the MPC. Check the file, then submit it "
                + "yourself as the MPC describes.", 320));
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
        updateNights();
        if (object == null) {
            identificationLabel.setText(" ");
            return;
        }
        String names = object.names().isEmpty() ? "" : "<b>" + escape(String.join(" or ", object.names())) + "</b><br>";
        measureButton.setEnabled(subStackWorker != null || !object.measured() && object.motion() != null && reduction.reduction() != null);
        String measure = object.measured() ? "" : subStackText(object);
        String reduced = reduction.reduction() == null ? "<br>" + escape(reduction.problem()) : "";
        var near = nearby.get(object.id());
        String nearText = near == null ? "" : near.isEmpty() ? "<br>Identify all: no known object within 1′."
                : "<br>Identify all, within 1′: " + escape(String.join("; ", near.stream().map(AsteroidsCometsWindow::nearbyText).toList())) + ".";
        identificationLabel.setHtml(object.id() + " · " + statusText(status(object)) + "<br>" + names
                + escape(object.note() == null ? "" : object.note()) + nearText + measure + reduced);
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
        var problems = new ArrayList<String>();
        var notes = new ArrayList<String>();
        for (int i = 0; i < report.objects().size(); i++) {
            var object = report.objects().get(i);
            if (!Boolean.TRUE.equals(exported.get(object.id()))) continue;
            var chosen = chosenObservations(object);
            var others = nightsOf(object.id());
            var named = namedObject(object, others);
            if (named != object) notes.add(object.id() + " is reported as " + named.names().getFirst() + ", the identification of its linked night.");
            var tracklet = MpcExport.tracklet(named, chosen, report.sessionStartMillis(), i);
            if (tracklet.permID() == null && tracklet.provID() == null && !others.isEmpty()) {
                // The MPC: the nights of one object share a temporary designation only within one message.
                var all = new ArrayList<>(chosen);
                String trkSub = tracklet.trkSub();
                long first = report.sessionStartMillis();
                for (var other : others) {
                    all.addAll(NightLinks.defaultSelection(other.observations()));
                    long start = other.night().report().sessionStartMillis();
                    if (start > 0 && start < first) {
                        first = start;
                        trkSub = other.night().trkSub(other.object());
                    }
                    if (other.night().lacksMeasurements(other.object())) {
                        problems.add(object.id() + ": the report of " + other.name() + " has no detection sizes and signal-to-noise. "
                                + "Detect that night again to export the nights together.");
                    }
                    if (other.observations().isEmpty()) problems.add(object.id() + ": " + other.name() + " has no measured positions of " + other.object().id() + ".");
                }
                String site = siteProblem(object.id());
                if (site != null) problems.add(site);
                all.sort(java.util.Comparator.comparingLong(MpcObservation::midpointMillis));
                tracklet = new AdesPsv.Tracklet(null, null, trkSub, all);
                notes.add(object.id() + ": " + (others.size() + 1) + " nights in one tracklet, " + trkSub
                        + ". The MPC gives the nights of one unidentified object one designation only within one submission.");
            }
            tracklets.add(tracklet);
        }
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
                + (notes.isEmpty() ? "" : "\n" + String.join("\n", notes))
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
                    stationLabel.setHtml(escape(stationText) + (stationProblem == null
                            ? site == null ? " · no observing site to compare" : " · agrees with the observing site" : " · does not agree"));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    stationProblem = null;
                    stationLabel.setHtml(escape(cause.getMessage()));
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
        var chosen = chosenObservations(shown);
        var others = nightsOf(shown.id());
        String site = others.isEmpty() ? null : siteProblem(shown.id());
        var all = new ArrayList<>(chosen);
        if (site == null) for (var other : others) all.addAll(NightLinks.defaultSelection(other.observations()));
        all.sort(java.util.Comparator.comparingLong(MpcObservation::midpointMillis));
        int nights = site == null ? 1 + (int) others.stream().filter(o -> !o.observations().isEmpty()).count() : 1;
        if (all.size() < 3) {
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
        String text = AdesPsv.writeObservations(context, List.of(MpcExport.tracklet(shown, all, report.sessionStartMillis(), sequence)));
        // With other nights, this night alone is fitted too, to show what they add.
        String single = nights > 1 && chosen.size() >= 3
                ? AdesPsv.writeObservations(context, List.of(MpcExport.tracklet(shown, chosen, report.sessionStartMillis(), sequence))) : null;
        if (site != null) problemsArea.setText(site + " The orbit uses this night only.");
        File program = details.findOrbExecutable == null || details.findOrbExecutable.isBlank() ? null : new File(details.findOrbExecutable);
        if (program == null || !program.isFile()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            problemsArea.setText("Copied " + all.size() + " observations of " + shown.id() + (nights > 1 ? " from " + nights + " nights" : "")
                    + " for Find_Orb: paste them into its box and choose Compute. (With Find_Orb on this computer, choose Find_Orb "
                    + "program… to fit here.)");
            browse(FIND_ORB);
            return;
        }
        var times = all.stream().map(o -> Instant.ofEpochMilli(o.midpointMillis()).toString()).toList();
        var singleTimes = chosen.stream().map(o -> Instant.ofEpochMilli(o.midpointMillis()).toString()).toList();
        var object = shown;
        orbitLabel.setText("Fitting " + object.id() + (nights > 1 ? " on " + nights + " nights" : "") + " with Find_Orb…");
        new SwingWorker<FindOrb.Orbit[], Void>() {
            @Override protected FindOrb.Orbit[] doInBackground() throws Exception {
                FindOrb.Orbit fit = FindOrb.fit(program, text, times);
                FindOrb.Orbit alone = null;
                if (single != null) {
                    try {
                        alone = FindOrb.fit(program, single, singleTimes);
                    } catch (java.io.IOException noSingleNightOrbit) {
                        // The comparison is optional: the orbit of all nights stands without it.
                    }
                }
                return new FindOrb.Orbit[]{fit, alone};
            }

            @Override protected void done() {
                if (closed) return;
                try {
                    var fits = get();
                    orbits.put(object.id(), fits[0]);
                    orbitNights.put(object.id(), nights);
                    if (fits[1] == null) singleNightOrbits.remove(object.id()); else singleNightOrbits.put(object.id(), fits[1]);
                    orbitProblems.remove(object.id());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    orbits.remove(object.id());
                    singleNightOrbits.remove(object.id());
                    orbitProblems.put(object.id(), cause.getMessage());
                }
                if (shown == object) {
                    updateOrbit();
                    if (orbits.containsKey(object.id())) showOrbitWindow();
                }
            }
        }.execute();
    }

    /** A fit belongs to the observations it was made from: a changed selection drops it. */
    private void forgetOrbit() {
        if (shown == null) return;
        forgetOrbit(shown.id());
        updateOrbit();
    }

    private void forgetOrbit(String id) {
        orbits.remove(id);
        singleNightOrbits.remove(id);
        orbitNights.remove(id);
        orbitProblems.remove(id);
    }

    /** Lets the user point to fo64.exe; kept in the settings with the other details. */
    private void chooseFindOrb() {
        JFileChooser chooser = WindowSizing.fileChooser(null);
        chooser.setDialogTitle("Choose fo64.exe (Windows) or fo in the console Find_Orb folder");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        settings.getConfig().findOrbExecutable = chooser.getSelectedFile().getAbsolutePath();
        saveDetails();
        updateOrbit();
    }

    /** Shows a summary of the shown object's fitted orbit, why it could not be fitted, or how to fit one. */
    private void updateOrbit() {
        var orbit = shown == null ? null : orbits.get(shown.id());
        showOrbitButton.setEnabled(orbit != null);
        if (orbitWindow != null) {
            if (orbit == null) {
                orbitWindow.dispose();
                orbitWindow = null;
            } else {
                fillOrbitWindow(orbitWindow, shown.id(), orbit);
            }
        }
        if (shown == null) { orbitLabel.setText(" "); return; }
        if (orbit == null) {
            String problem = orbitProblems.get(shown.id());
            String program = settings.getConfig().findOrbExecutable;
            orbitLabel.setHtml(escape(problem != null ? problem : program == null || program.isBlank()
                    ? "Find_Orb is not set up here: Compute orbit copies the observations for the online Find_Orb."
                    : "Compute orbit fits the selected observations of " + shown.id() + "."));
            return;
        }
        int nights = orbitNights.getOrDefault(shown.id(), 1);
        var alone = singleNightOrbits.get(shown.id());
        orbitLabel.setHtml(String.format(Locale.US, "<b>Preliminary orbit%s</b> · a %s AU · e %s · i %s° · residual RMS %s.%s",
                nights > 1 ? " from " + nights + " nights" : "",
                withSigma(orbit.a(), orbit.aSigma(), 3), withSigma(orbit.e(), orbit.eSigma(), 3), withSigma(orbit.i(), orbit.iSigma(), 1),
                orbit.rmsArcsec() == null ? "?" : String.format(Locale.US, "%.2f″", orbit.rmsArcsec()),
                alone == null ? "" : "<br>This night alone: " + elements(alone) + "."));
    }

    /** "a 3.09 ± 1.31 AU · e 0.348 ± 0.131 · i 7.3 ± 6.0°". */
    private static String elements(FindOrb.Orbit orbit) {
        return "a " + withSigma(orbit.a(), orbit.aSigma(), 3) + " AU · e " + withSigma(orbit.e(), orbit.eSigma(), 3)
                + " · i " + withSigma(orbit.i(), orbit.iSigma(), 1) + "°";
    }

    /** The time the residuals span, in hours. */
    private static double spanHours(FindOrb.Orbit orbit) {
        return orbit.residuals().size() < 2 ? 0 : (Instant.parse(orbit.residuals().getLast().isoTime()).toEpochMilli()
                - Instant.parse(orbit.residuals().getFirst().isoTime()).toEpochMilli()) / 3_600_000.0;
    }

    /**
     * The full description of a fitted orbit, as HTML; with the orbit of this night alone ({@code alone}, else null)
     * when the fit also used {@code nights - 1} linked nights.
     */
    private String orbitText(FindOrb.Orbit orbit, FindOrb.Orbit alone, int nights) {
        long used = orbit.residuals().stream().filter(FindOrb.Residual::used).count();
        double span = spanHours(orbit);
        String arc = span >= 48 ? String.format(Locale.US, "%.1f days", span / 24) : String.format(Locale.US, "%.1f h", span);
        String comparison = alone == null ? "" : "<br><br><b>This night alone</b> (dashed) · " + elements(alone)
                + String.format(Locale.US, "<br>%d observations over %.1f h. The ± values show what one night leaves open; a few nights still leave much of the orbit open.",
                alone.residuals().size(), spanHours(alone));
        return String.format(Locale.US, "<b>Preliminary</b> · %d of %d observations used%s over %s"
                        + " · epoch %s<br>a %s AU · e %s · i %s°<br>q %s AU · node %s° · peri %s°<br>Residual RMS %s · P(NEO) %s · Earth MOID %s"
                        + "<br>An orbit from a short arc is uncertain (±1σ shown); a good fit does not prove the object is real.%s",
                used, orbit.residuals().size(), nights > 1 ? " from " + nights + " nights" : "", arc,
                orbit.epochIso() == null ? "?" : orbit.epochIso().substring(0, 10),
                withSigma(orbit.a(), orbit.aSigma(), 3), withSigma(orbit.e(), orbit.eSigma(), 3), withSigma(orbit.i(), orbit.iSigma(), 1),
                withSigma(orbit.q(), orbit.qSigma(), 3), withSigma(orbit.ascendingNode(), orbit.nodeSigma(), 1),
                withSigma(orbit.argumentOfPerihelion(), orbit.perihelionSigma(), 1),
                orbit.rmsArcsec() == null ? "?" : String.format(Locale.US, "%.2f″", orbit.rmsArcsec()),
                orbit.neoProbability() == null ? "?" : String.format(Locale.US, "%.0f %%", orbit.neoProbability()),
                orbit.earthMoid() == null ? "?" : String.format(Locale.US, "%.3f AU", orbit.earthMoid()), comparison);
    }

    /**
     * Opens, or brings forward, a small window with the shown object's fitted orbit drawn from above the ecliptic, its
     * elements, and Save picture. It follows the shown object and closes when its orbit is dropped.
     */
    private void showOrbitWindow() {
        var orbit = shown == null ? null : orbits.get(shown.id());
        if (orbit == null) return;
        if (orbitWindow == null) {
            orbitWindow = new JDialog(this, "Orbit", false);
            orbitWindow.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            orbitWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) {
                    if (e.getWindow() == orbitWindow) orbitWindow = null;
                }
            });
            fillOrbitWindow(orbitWindow, shown.id(), orbit);
            orbitWindow.pack();
            orbitWindow.setLocationRelativeTo(this);
        } else {
            fillOrbitWindow(orbitWindow, shown.id(), orbit);
        }
        orbitWindow.setVisible(true);
        orbitWindow.toFront();
    }

    /** Puts one orbit's elements, drawing and Save picture into the orbit window. */
    private void fillOrbitWindow(JDialog window, String id, FindOrb.Orbit orbit) {
        window.setTitle("Orbit · " + id);
        var diagram = new OrbitDiagram();
        diagram.orbit = orbit;
        diagram.alone = singleNightOrbits.get(id);
        var text = new WrappingHelpLabel(orbitText(orbit, diagram.alone, orbitNights.getOrDefault(id, 1)), 320);
        text.setForeground(UIManager.getColor("Label.foreground"));
        JButton savePicture = new JButton("Save picture…");
        savePicture.addActionListener(e -> saveOrbitPicture(id, diagram));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        buttons.add(savePicture);
        JPanel content = new JPanel(new BorderLayout(0, UiStyle.space(6)));
        content.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(8), UiStyle.space(8), UiStyle.space(8), UiStyle.space(8)));
        content.add(text, BorderLayout.NORTH);
        content.add(diagram, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        window.setContentPane(content);
        window.revalidate();
        window.repaint();
    }

    /** Saves the orbit drawing as a PNG where the user chooses, the file written on a worker. */
    private void saveOrbitPicture(String id, OrbitDiagram diagram) {
        int size = Math.max(UiStyle.space(300), Math.min(diagram.getWidth(), diagram.getHeight()));
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            diagram.setSize(size, size);
            diagram.paint(g);
        } finally {
            g.dispose();
        }
        JFileChooser chooser = WindowSizing.fileChooser(report.folder());
        chooser.setSelectedFile(new File(report.folder(), id.toLowerCase(Locale.ROOT) + "_orbit.png"));
        if (chooser.showSaveDialog(orbitWindow == null ? this : orbitWindow) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        writeOnWorker(() -> javax.imageio.ImageIO.write(image, "png", target), problem -> orbitLabel.setHtml(escape(problem == null
                ? "Saved " + target.getName() + "." : "The picture was not saved: " + problem)));
    }

    /** An action that writes a file and may fail. */
    private interface FileWrite {
        Object write() throws Exception;
    }

    /**
     * Writes a file on a worker, never on the event thread, and then tells {@code done} on the event thread: null when
     * it was written, else why not.
     */
    private void writeOnWorker(FileWrite write, java.util.function.Consumer<String> done) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                write.write();
                return null;
            }

            @Override protected void done() {
                if (closed) return;
                try {
                    get();
                    done.accept(null);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    done.accept(String.valueOf(cause.getMessage()));
                }
            }
        }.execute();
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
        File target = chooser.getSelectedFile();
        String text = psvArea.getText();
        writeOnWorker(() -> Files.writeString(target.toPath(), text, StandardCharsets.UTF_8), problem -> problemsArea.setText(problem == null
                ? "Saved " + target.getName() + "." : "The file was not saved: " + problem));
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

    /**
     * Compares every object with the session's current predictions by the report's rules and keeps the new
     * identifications in this window; the report itself is unchanged until the next detection.
     */
    private void compareAgain() {
        var objects = predictions.get();
        if (objects == null || objects.bodies == null) {
            compareLabel.setHtml("No saved predictions: Fetch Asteroids and Comets (2 Astrometry) first.");
            return;
        }
        if (header == null) {
            compareLabel.setHtml("The frames have no plate solution to compare positions with.");
            return;
        }
        double diagonal = report.frames().isEmpty() ? 0 : Math.hypot(report.frames().getFirst().width(), report.frames().getFirst().height());
        double floor = report.reviewFloorPixels();
        double fwhm = report.faintMoverFwhm() == null ? Double.NaN : report.faintMoverFwhm();
        var replaced = new ArrayList<ReportedObjects.ReportedObject>();
        var changes = new ArrayList<String>();
        for (var object : report.objects()) {
            var points = new ArrayList<eu.startales.spacepixels.report.PredictionMatch.Point>();
            for (var sample : LightCurve.samples(report, object)) {
                points.add(new eu.startales.spacepixels.report.PredictionMatch.Point(sample.frameNumber(), sample.midpointMillis(), sample.x(), sample.y()));
            }
            var motion = object.motion();
            var result = object.measured()
                    ? eu.startales.spacepixels.report.PredictionMatch.track(points, objects, header, floor, diagonal)
                    : motion == null ? null : eu.startales.spacepixels.report.PredictionMatch.faintMover(motion.x(), motion.y(),
                    motion.referenceMillis(), motion.vxPerMinute(), motion.vyPerMinute(), points, objects, header, fwhm, diagonal);
            if (result == null || (result.status().equals(object.status()) && result.names().equals(object.names()))) {
                replaced.add(object);
                continue;
            }
            // A check for unusual motion stays a check, and a possible identification stays possible, while nothing known agrees.
            boolean unusual = ReportedObjects.CHECK.equals(object.status()) && object.note() != null
                    && object.note().toLowerCase(Locale.ROOT).contains("unusual");
            String status = ReportedObjects.NO_KNOWN_MATCH.equals(result.status()) && (unusual || ReportedObjects.POSSIBLE.equals(object.status()))
                    ? object.status() : result.status();
            if (status.equals(object.status()) && (ReportedObjects.POSSIBLE.equals(status) || result.names().equals(object.names()))) {
                replaced.add(object);
                continue;
            }
            var updated = new ReportedObjects.ReportedObject(object.id(), object.kind(), status, result.names(),
                    result.note() + " (compared again with the predictions fetched " + (objects.fetchedAtUtc == null ? "later" : objects.fetchedAtUtc.substring(0, 10)) + ")",
                    object.positions(), object.speedArcsecPerHour(), object.positionAngleDegrees(), object.motion());
            replaced.add(updated);
            exported.put(object.id(), MpcExport.includedByDefault(updated));
            remember(updated, "Compare again");
            changes.add(object.id() + " " + statusText(object.status()) + " → " + statusText(status)
                    + (result.names().isEmpty() ? "" : " " + String.join(" or ", result.names())));
        }
        report = report.withObjects(replaced);
        if (!changes.isEmpty()) saveLater();
        String head = String.format(Locale.US, "Compared with the saved predictions to V %.1f (%d objects): ", objects.magnitudeLimit, objects.bodies.size());
        if (changes.isEmpty()) {
            showComparison(head + "no identification changed.", null);
        } else {
            showComparison(head + (changes.size() <= 2 ? String.join("; ", changes) + "." : changes.size() + " changed.")
                    + " Saved.", head + "\n\n" + String.join("\n", changes));
        }
        refreshObjects();
        show(shown == null ? null : report.objects().stream().filter(o -> o.id().equals(shown.id())).findFirst().orElse(null));
        refreshExport();
    }

    /**
     * Asks JPL and SkyBoT, on a worker, for every known object in the field at mid-session without a magnitude limit,
     * and lists those within 1′ of each object with their rate difference. The identifications are not changed.
     */
    private void identifyAll() {
        if (identifyWorker != null) {
            identifyWorker.cancel(true);
            return;
        }
        if (header == null || report.frames().isEmpty()) {
            compareLabel.setHtml("The frames have no plate solution to search around.");
            return;
        }
        long first = report.frames().stream().mapToLong(ReportedObjects.Frame::timestampMillis).filter(t -> t > 0).min().orElse(0);
        long last = report.frames().stream().filter(f -> f.timestampMillis() > 0)
                .mapToLong(f -> f.timestampMillis() + Math.max(0, f.exposureMillis())).max().orElse(0);
        if (first <= 0) {
            compareLabel.setHtml("The frames have no times to search at.");
            return;
        }
        long epoch = first + (last - first) / 2;
        var field = eu.startales.spacepixels.skycatalog.SkyField.of(header, report.frames().getFirst().width(), report.frames().getFirst().height());
        String code = settings.getConfig().observatoryCode;
        var objects = report.objects();
        identifyAllButton.setText("Cancel Identify all");
        compareLabel.setText("Asking JPL and SkyBoT for every known object in the field…");
        identifyWorker = new SwingWorker<eu.startales.spacepixels.solarsystem.SolarSystemClient.FieldObjects, Void>() {
            @Override protected eu.startales.spacepixels.solarsystem.SolarSystemClient.FieldObjects doInBackground() throws Exception {
                return new eu.startales.spacepixels.solarsystem.SolarSystemClient().knownInField(field, epoch, code, site,
                        eu.startales.spacepixels.mpc.NearbyKnown.RADIUS_ARCSEC / 3600 * 2);
            }

            @Override protected void done() {
                if (identifyWorker == this) identifyWorker = null;
                if (closed) return;
                identifyAllButton.setText("Identify all…");
                if (isCancelled()) {
                    compareLabel.setText("Identify all was cancelled.");
                    return;
                }
                eu.startales.spacepixels.solarsystem.SolarSystemClient.FieldObjects known;
                try {
                    known = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    compareLabel.setHtml("Identify all could not ask the services: " + escape(String.valueOf(cause.getMessage())));
                    return;
                }
                nearby.clear();
                var found = new ArrayList<String>();
                int empty = 0;
                var byFinding = new java.util.LinkedHashMap<String, List<eu.startales.spacepixels.mpc.NearbyKnown.Match>>();
                for (var object : objects) {
                    var place = eu.startales.spacepixels.mpc.NearbyKnown.place(LightCurve.samples(report, object), object.motion(), epoch, header);
                    byFinding.put(object.id(), eu.startales.spacepixels.mpc.NearbyKnown.near(place, known.objects()));
                }
                nearby.putAll(eu.startales.spacepixels.mpc.NearbyKnown.noteNearer(byFinding));
                for (var object : report.objects()) {
                    var agreeing = nearby.getOrDefault(object.id(), List.of()).stream()
                            .filter(eu.startales.spacepixels.mpc.NearbyKnown.Match::motionAgrees).toList();
                    if (agreeing.isEmpty()) empty++;
                    else found.add(object.id() + ": " + nearbyText(agreeing.getFirst()));
                }
                var before = report.objects();
                var marked = eu.startales.spacepixels.mpc.NearbyKnown.markPossible(before, nearby,
                        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString(), AsteroidsCometsWindow::nearbyText);
                var possible = new ArrayList<String>();
                for (int k = 0; k < marked.size(); k++) {
                    if (marked.get(k) == before.get(k)) continue;
                    exported.put(marked.get(k).id(), MpcExport.includedByDefault(marked.get(k)));
                    remember(marked.get(k), "Identify all");
                    possible.add(marked.get(k).id());
                }
                report = report.withObjects(marked);
                refreshObjects();
                refreshExport();
                // A short summary: each object's matches are in its identification.
                String summary = String.format(Locale.US, "Identify all (%d known objects%s): ", known.objects().size(),
                        known.problems().isEmpty() ? "" : "; " + String.join("; ", known.problems()))
                        + (possible.isEmpty() ? "" : "Possible " + String.join(", ", possible) + "; ")
                        + (found.size() - possible.size() > 0 ? (found.size() - possible.size()) + " known agree; " : "")
                        + empty + " with none within 1′.";
                if (!possible.isEmpty()) saveLater();
                var details = new StringBuilder(summary).append("\n\nKnown objects within 1′ of each object at mid-session:\n");
                for (var object : report.objects()) {
                    var near = nearby.getOrDefault(object.id(), List.of());
                    details.append("\n").append(object.id()).append(" (").append(statusText(object.status())).append("): ")
                            .append(near.isEmpty() ? "none" : String.join("; ", near.stream().map(AsteroidsCometsWindow::nearbyText).toList()));
                }
                showComparison(summary + (possible.isEmpty() ? "" : " Saved."), details.toString());
                if (shown != null) show(report.objects().stream().filter(o -> o.id().equals(shown.id())).findFirst().orElse(null));
            }
        };
        identifyWorker.execute();
    }

    /** Records an object's identification made in this window, to save beside the report. */
    private void remember(ReportedObjects.ReportedObject object, String source) {
        var entry = new eu.startales.spacepixels.mpc.LaterIdentifications.Entry();
        entry.id = object.id();
        entry.status = object.status();
        entry.names = new ArrayList<>(object.names());
        entry.note = object.note();
        entry.source = source;
        entry.madeAtUtc = java.time.Instant.now().toString();
        later.put(object.id(), entry);
    }

    /** Saves the later identifications beside the report and says so, or why not. */
    private void saveLater() {
        var copy = new java.util.LinkedHashMap<>(later);
        File folder = report.folder();
        writeOnWorker(() -> {
            eu.startales.spacepixels.mpc.LaterIdentifications.save(folder, copy);
            return null;
        }, problem -> {
            if (problem != null) compareLabel.setHtml(escape("The identifications were not saved beside the report (" + problem
                    + "): they last until this window closes."));
        });
    }

    /** Shows the full result of the last Compare again or Identify all. */
    private void showComparisonDetails() {
        if (comparisonDetails == null) return;
        JTextArea text = new JTextArea(comparisonDetails, Math.min(24, comparisonDetails.split("\n").length + 1), 70);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Identification details", JOptionPane.PLAIN_MESSAGE);
    }

    /** Shows a short result line and keeps the full text behind the ⓘ link. */
    private void showComparison(String summary, String details) {
        comparisonDetails = details;
        detailsLink.setVisible(details != null);
        compareLabel.setHtml(escape(summary));
    }

    /** One nearby known object in words: name, magnitude, distance and rate difference. */
    static String nearbyText(eu.startales.spacepixels.mpc.NearbyKnown.Match m) {
        return String.format(Locale.US, "%s%s, %.0f″ away, motion %s by %.1f″/h%s", m.name(),
                m.magnitude() == null ? "" : String.format(Locale.US, " (V %.1f)", m.magnitude()), m.offsetArcsec(),
                m.motionAgrees() ? "agreeing" : "differing", m.rateDifferenceArcsecPerHour(),
                m.nearerFinding() == null ? "" : String.format(Locale.US, " (%s is nearer, %.0f″: perhaps a piece of its track)",
                        m.nearerFinding(), m.nearerOffsetArcsec()));
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
        if (subStackWorker != null) {
            subStackWorker.cancel(true);
            return;
        }
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
        measureButton.setText("Cancel sub-stacks");
        problemsArea.setText("Measuring " + object.id() + " in " + SUB_STACKS + " sub-stacks…");
        subStackWorker = new SwingWorker<List<SubStackAstrometry.Measurement>, Void>() {
            private final List<Integer> frameIndexes = new ArrayList<>();

            @Override protected List<SubStackAstrometry.Measurement> doInBackground() throws Exception {
                var cutouts = new ArrayList<SubStackAstrometry.Cutout>();
                for (var p : positions) {
                    if (closed || isCancelled()) return List.of();
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
                if (subStackWorker == this) subStackWorker = null;
                if (closed) return;
                measureButton.setText("Measure in sub-stacks");
                if (isCancelled()) {
                    problemsArea.setText("Measuring " + object.id() + " in sub-stacks was cancelled.");
                    if (shown != null) show(shown);
                    return;
                }
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
        };
        subStackWorker.execute();
    }

    /** One other night of an object: the night, the object there and its measured positions as observations. */
    private record OtherNight(NightLinks.Night night, ReportedObjects.ReportedObject object, List<MpcObservation> observations) {
        /** The night's UTC date, as the start of its first exposure gives it. */
        String name() {
            return NIGHT.format(Instant.ofEpochMilli(night.report().sessionStartMillis()));
        }
    }

    /** An object's other nights, in time order, and why some linked nights could not be read. */
    private record OtherNights(List<OtherNight> nights, List<String> problems) { }

    /** The selected observations of an object on this night, in time order. */
    private List<MpcObservation> chosenObservations(ReportedObjects.ReportedObject object) {
        var list = observations.get(object.id());
        var chosen = new ArrayList<MpcObservation>();
        for (int index : selected.get(object.id())) if (index < list.size()) chosen.add(list.get(index));
        return chosen;
    }

    /** The object's other nights as read; empty without links or before they are read. */
    private List<OtherNight> nightsOf(String id) {
        var linked = otherNights.get(id);
        return linked == null ? List.of() : linked.nights();
    }

    /**
     * The object whose identification names this one in the file: itself when it is Known by one name, else a linked
     * night's object that is, so an object identified on one night is reported by its designation on all.
     */
    private static ReportedObjects.ReportedObject namedObject(ReportedObjects.ReportedObject object, List<OtherNight> others) {
        if (ReportedObjects.KNOWN.equals(object.status()) && object.names().size() == 1) return object;
        for (var other : others) {
            var o = other.object();
            if (ReportedObjects.KNOWN.equals(o.status()) && o.names().size() == 1) return o;
        }
        return object;
    }

    /** Why the object's linked nights cannot share one observatory code with this night; null when they can. */
    private String siteProblem(String id) {
        for (var other : nightsOf(id)) {
            if (!NightLinks.sameSite(site, other.night().site())) {
                return id + ": " + other.name() + " was observed from another site (" + other.night().site().describe()
                        + "). One file and one orbit fit here use one observatory code.";
            }
        }
        return null;
    }

    /** A report opened as another night, read once while the window is open. Called on workers. */
    private NightLinks.Night openNight(File folder, String latitude, String longitude, String altitude) throws Exception {
        String key = folder.getAbsoluteFile().toPath().normalize().toString();
        var night = openedNights.get(key);
        if (night == null) {
            night = NightLinks.open(folder, latitude, longitude, altitude);
            openedNights.put(key, night);
        }
        return night;
    }

    /** Reads every linked object's other nights on a worker, then shows them and rebuilds the file. */
    private void readOtherNights() {
        if (nightsWorker != null) nightsWorker.cancel(true);
        File folder = report.folder();
        var ids = report.objects().stream().map(ReportedObjects.ReportedObject::id).toList();
        AppConfig config = settings.getConfig();
        String latitude = config.siteLat, longitude = config.siteLong, altitude = config.siteAltitude;
        nightsWorker = new SwingWorker<Map<String, OtherNights>, Void>() {
            @Override protected Map<String, OtherNights> doInBackground() {
                var linkedIds = new java.util.HashSet<String>();
                for (var link : NightLinks.load(folder)) linkedIds.add(link.object);
                var result = new HashMap<String, OtherNights>();
                for (String id : ids) {
                    if (!linkedIds.contains(id) || isCancelled()) continue;
                    var linked = NightLinks.linked(folder, id);
                    var nights = new ArrayList<OtherNight>();
                    var problems = new ArrayList<String>();
                    for (String path : linked.missing()) problems.add("A linked report is no longer at " + path + ".");
                    for (var ref : linked.nights()) {
                        try {
                            var night = openNight(ref.folder(), latitude, longitude, altitude);
                            var object = night.object(ref.objectId());
                            if (object == null) problems.add(ref.objectId() + " is not in " + ref.folder().getName() + ".");
                            else nights.add(new OtherNight(night, object, night.observations(object)));
                        } catch (Exception e) {
                            problems.add(ref.folder().getName() + " cannot be read: " + message(e));
                        }
                    }
                    nights.sort(java.util.Comparator.comparingLong(n -> n.night().report().sessionStartMillis()));
                    result.put(id, new OtherNights(List.copyOf(nights), List.copyOf(problems)));
                }
                return result;
            }

            @Override protected void done() {
                if (nightsWorker == this) nightsWorker = null;
                if (closed || isCancelled()) return;
                try {
                    otherNights.clear();
                    otherNights.putAll(get());
                } catch (Exception e) {
                    nightsLabel.setHtml(escape("The linked nights could not be read: " + message(e)));
                    return;
                }
                refreshObjects();
                updateNights();
                refreshExport();
            }
        };
        nightsWorker.execute();
    }

    /** Shows the shown object's other nights, or what linking one does. */
    private void updateNights() {
        var object = shown;
        linkButton.setEnabled(object != null && !observations.get(object.id()).isEmpty());
        unlinkButton.setEnabled(object != null && otherNights.containsKey(object.id()));
        if (object == null) {
            nightsLabel.setText(" ");
            return;
        }
        var linked = otherNights.get(object.id());
        if (linked == null) {
            nightsLabel.setHtml("Seen on another night as well? Link it: Compute orbit then fits all the nights, and an "
                    + "unidentified object's nights go into one file under one tracklet ID, as the MPC asks.");
            return;
        }
        var text = new StringBuilder("This night: ").append(NIGHT.format(Instant.ofEpochMilli(report.sessionStartMillis())));
        for (var other : linked.nights()) {
            text.append("<br><b>").append(other.name()).append("</b> · ").append(escape(other.object().id())).append(" · ")
                    .append(statusText(other.object().status()));
            if (!other.object().names().isEmpty()) text.append(" · ").append(escape(String.join(" or ", other.object().names())));
            text.append(" · ").append(other.observations().size()).append(" observations");
            if (other.night().reduction().reduction() == null) text.append(" · ").append(escape(other.night().reduction().problem()));
        }
        for (String problem : linked.problems()) text.append("<br>").append(escape(problem));
        String site = siteProblem(object.id());
        if (site != null) text.append("<br>").append(escape(site));
        nightsLabel.setHtml(text.toString());
    }

    /**
     * Asks for another night's report folder, reads it on a worker, and asks which of its objects is the shown one,
     * suggesting the one with the same identification or the one nearest where this night's motion leads.
     */
    private void linkAnotherNight() {
        var object = shown;
        if (object == null || observations.get(object.id()).isEmpty()) return;
        File session = report.folder().getAbsoluteFile().getParentFile();
        JFileChooser chooser = WindowSizing.fileChooser(session != null && session.getParentFile() != null ? session.getParentFile() : session);
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose the report folder (detections_…) of the other night");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File chosen = chooser.getSelectedFile();
        AppConfig config = settings.getConfig();
        String latitude = config.siteLat, longitude = config.siteLong, altitude = config.siteAltitude;
        linkButton.setEnabled(false);
        nightsLabel.setHtml(escape("Reading " + chosen.getName() + "…"));
        new SwingWorker<NightLinks.Night, Void>() {
            @Override protected NightLinks.Night doInBackground() throws Exception {
                return openNight(chosen, latitude, longitude, altitude);
            }

            @Override protected void done() {
                if (closed) return;
                NightLinks.Night other;
                try {
                    other = get();
                } catch (Exception e) {
                    updateNights();
                    nightsLabel.setHtml(escape(chosen.getName() + " cannot be read: " + message(e)));
                    return;
                }
                updateNights();
                if (other.report().sessionStartMillis() == report.sessionStartMillis()) {
                    nightsLabel.setHtml(escape("That report is of this night. Choose the report of another night."));
                    return;
                }
                if (shown == object) chooseLinkedObject(object, other);
            }
        }.execute();
    }

    /** Lets the user pick the other night's object that is this one, then writes the link beside both reports. */
    private void chooseLinkedObject(ReportedObjects.ReportedObject object, NightLinks.Night other) {
        String otherName = NIGHT.format(Instant.ofEpochMilli(other.report().sessionStartMillis()));
        var own = observations.get(object.id());
        var candidates = other.report().objects().stream().filter(o -> !other.observations(o).isEmpty()).toList();
        if (candidates.isEmpty()) {
            nightsLabel.setHtml(escape("The report of " + otherName + " has no measured object to link: only tracks, with positions "
                    + "measured frame by frame, can be linked from another night."));
            return;
        }
        var labels = new String[candidates.size()];
        int suggested = -1;
        double nearest = Double.MAX_VALUE;
        for (int k = 0; k < candidates.size(); k++) {
            var candidate = candidates.get(k);
            var list = other.observations(candidate);
            Double miss = NightLinks.extrapolationMissArcmin(own, list);
            labels[k] = candidate.id() + " · " + statusText(candidate.status())
                    + (candidate.names().isEmpty() ? "" : " · " + String.join(" or ", candidate.names()))
                    + " · " + list.size() + " observations"
                    + (miss == null ? "" : String.format(Locale.US, " · %.1f′ from where this night's motion leads", miss));
            boolean sameName = !object.names().isEmpty() && candidate.names().stream().anyMatch(object.names()::contains);
            if (sameName) {
                suggested = k;
                nearest = -1;
            } else if (miss != null && miss < nearest) {
                nearest = miss;
                suggested = k;
            }
        }
        JComboBox<String> choice = new JComboBox<>(labels);
        if (suggested >= 0) choice.setSelectedIndex(suggested);
        JPanel content = new JPanel(new BorderLayout(0, UiStyle.space(8)));
        content.add(new JLabel(UiStyle.htmlWrappedTo(460, "Which object of " + escapeHtml(otherName) + " ("
                + escapeHtml(other.report().folder().getName()) + ") is " + escapeHtml(object.id()) + "?<br>Link only when you are "
                + "sure: an unidentified object's linked nights are reported together under one tracklet ID. Where this night's "
                + "straight motion leads is a hint; over days an asteroid's path bends by a few arcminutes.")), BorderLayout.NORTH);
        content.add(choice, BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, content, "Link another night", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        var target = candidates.get(choice.getSelectedIndex());
        writeOnWorker(() -> {
            NightLinks.link(thisNight, object.id(), other, target.id());
            return null;
        }, problem -> {
            if (problem != null) {
                nightsLabel.setHtml(escape("The link was not saved: " + problem));
                return;
            }
            forgetOrbit(object.id());
            if (shown == object) updateOrbit();
            readOtherNights();
        });
    }

    /** After the user agrees, removes the shown object's links here and beside the other reports. */
    private void unlinkNights() {
        var object = shown;
        if (object == null || !otherNights.containsKey(object.id())) return;
        if (JOptionPane.showConfirmDialog(this, UiStyle.htmlWrappedTo(420, "Remove the links of " + escapeHtml(object.id())
                        + " to other nights? They are removed here and beside the other reports."),
                "Unlink", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        File folder = report.folder();
        writeOnWorker(() -> {
            NightLinks.unlink(folder, object.id());
            return null;
        }, problem -> {
            if (problem != null) {
                nightsLabel.setHtml(escape("The links were not removed: " + problem));
                return;
            }
            forgetOrbit(object.id());
            if (shown == object) updateOrbit();
            otherNights.remove(object.id());
            readOtherNights();
        });
    }

    /** An exception's cause's message. */
    private static String message(Exception e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return String.valueOf(cause.getMessage());
    }

    static String statusText(String status) {
        return switch (status == null ? "" : status) {
            case WEAK -> "Weak";
            case ReportedObjects.KNOWN -> "Known";
            case ReportedObjects.CHECK -> "Check";
            case ReportedObjects.NO_KNOWN_MATCH -> "No known match";
            case ReportedObjects.POSSIBLE -> "Possible";
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
            for (var running : new SwingWorker<?, ?>[]{identifyWorker, curveWorker, subStackWorker, nightsWorker}) if (running != null) running.cancel(true);
            identifyWorker = curveWorker = subStackWorker = nightsWorker = null;
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
                default -> (object.measured() ? selected.get(object.id()).size() + " of " + observations.get(object.id()).size()
                        : subStacks.containsKey(object.id()) ? subStacks.get(object.id()).size() + " sub-stacks" : "fitted only")
                        + (nightsOf(object.id()).isEmpty() ? "" : " · +" + nightsOf(object.id()).size()
                        + (nightsOf(object.id()).size() == 1 ? " night" : " nights"));
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
        /** The orbit from this night alone, drawn dashed under {@link #orbit}; null when there is none. */
        FindOrb.Orbit alone;

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
                if (alone != null) {
                    g.setColor(new Color(200, 160, 230));
                    g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{5f, 4f}, 0f));
                    g.draw(line(alone, extent, scale, cx, cy));
                }
                g.setColor(new Color(80, 225, 210));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(line(orbit, extent, scale, cx, cy));
                double[] object = OrbitGeometry.atEpoch(orbit);
                if (object != null) {
                    double ox = cx + object[0] * scale, oy = cy - object[1] * scale;
                    g.fill(new Ellipse2D.Double(ox - 4, oy - 4, 8, 8));
                }
                g.setColor(new Color(170, 175, 190));
                g.drawString("Ecliptic, from the north · planets on mean circular orbits", 6, size - 6);
                if (alone != null) g.drawString("Solid: all nights · dashed: this night alone", 6, size - 6 - g.getFontMetrics().getHeight());
            } finally {
                g.dispose();
            }
        }

        /** An orbit's path on the drawing: the whole ellipse, or the near part of an unbound orbit. */
        private static java.awt.geom.Path2D line(FindOrb.Orbit orbit, double extent, double scale, double cx, double cy) {
            double[][] path = OrbitGeometry.path(orbit, 240, extent * 1.5);
            var line = new java.awt.geom.Path2D.Double();
            for (int k = 0; k < path.length; k++) {
                double x = cx + path[k][0] * scale, y = cy - path[k][1] * scale;
                if (k == 0) line.moveTo(x, y); else line.lineTo(x, y);
            }
            if (orbit.bound()) line.closePath();
            return line;
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
