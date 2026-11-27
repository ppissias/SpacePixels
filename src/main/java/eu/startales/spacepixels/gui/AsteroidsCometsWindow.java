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
import eu.startales.spacepixels.fits.DisplayStretch;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.StretchAlgorithm;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.mpc.AdesPsv;
import eu.startales.spacepixels.mpc.AstrometricReduction;
import eu.startales.spacepixels.mpc.FaintMoverReviewAssets;
import eu.startales.spacepixels.mpc.FindOrb;
import eu.startales.spacepixels.mpc.LightCurve;
import eu.startales.spacepixels.mpc.OrbitDetermination;
import eu.startales.spacepixels.mpc.OrbitGeometry;
import eu.startales.spacepixels.mpc.MpcExport;
import eu.startales.spacepixels.mpc.MpcItfCatalog;
import eu.startales.spacepixels.mpc.MpcObservation;
import eu.startales.spacepixels.mpc.MpcRecords;
import eu.startales.spacepixels.mpc.NightLinks;
import eu.startales.spacepixels.mpc.ReportedObjects;
import eu.startales.spacepixels.mpc.SavedMeasurements;
import io.github.ppissias.jtransient.core.SubStackAstrometry;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.skycatalog.PhotometryStars;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.solarsystem.JplSbdbLookup;
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
    /**
     * The session's plate correction now, which a sky catalogue fetched while this window is open fits again; null
     * without one. {@link #sky()} follows it. The MPC reduction keeps {@link #openedCorrection}.
     */
    private final java.util.function.Supplier<PlateSolutionCorrection> correction;
    /** The plate correction when the window opened, which {@link #reduction} applies; null without one. */
    private final PlateSolutionCorrection openedCorrection;
    /** Saves and installs the session's predictions after Identify all added objects to them. EDT only. */
    private final java.util.function.Consumer<eu.startales.spacepixels.solarsystem.SolarSystemObjects> savePredictions;
    /** The session's sky catalogue, for comparison stars; null without one. */
    private final SkyCatalogue catalogue;
    /** Fainter Gaia stars fetched for comparison stars when the saved catalogue had too few usable ones; empty until then. */
    private volatile List<SkyCatalogue.Star> fetchedStars = List.of();
    private final WrappingHelpLabel compareLabel = new WrappingHelpLabel(" ", 320);
    /** Identifications made after the report, by object, as saved beside it. EDT only. */
    private final Map<String, eu.startales.spacepixels.mpc.LaterIdentifications.Entry> later;
    private final JButton identifyAllButton = new JButton("Identify all…");
    private final JButton itfButton = new JButton("Check MPC ITF…");
    /** The running Identify all or MPC ITF check, while its button reads Cancel; null otherwise. EDT only. */
    private SwingWorker<?, ?> identifyWorker, itfWorker;
    /** The running light-curve and sub-stack measurements, while their Cancel buttons are shown; null otherwise. EDT only. */
    private SwingWorker<?, ?> curveWorker, subStackWorker;
    /** Object IDs currently being measured by {@link #curveWorker} and {@link #subStackWorker}. EDT only. */
    private String curveTargetId, subStackTargetId;
    /** Objects whose automatic sub-stack or light-curve measurement has already been started in this window. EDT only. */
    private final Set<String> autoSubStackTried = new java.util.HashSet<>(), autoCurveTried = new java.util.HashSet<>();
    /** Objects whose last sub-stack or light-curve measurement failed or was cancelled (offers Retry). EDT only. */
    private final Set<String> subStackNeedsRetry = new java.util.HashSet<>(), curveNeedsRetry = new java.util.HashSet<>();
    /** The full result of the last Compare again, Identify all or MPC ITF check, which the ⓘ link shows; null before one. */
    private String comparisonDetails;
    private final JButton detailsLink = AccessibleControls.link("ⓘ", "Details of the last comparison", this::showComparisonDetails);
    private final JButton showOrbitButton = new JButton("Show orbit");
    /** The open 3D orbit viewer window, or null. */
    private OrbitViewerDialog orbitWindow;
    /** Known objects within 1′ of each object from the last Identify all, by object; empty before it. EDT only. */
    private final Map<String, List<eu.startales.spacepixels.mpc.NearbyKnown.Match>> nearby = new HashMap<>();
    /** Unlinked MPC Isolated Tracklet File matches by object from the last Check MPC ITF; empty before it. EDT only. */
    private final Map<String, MpcItfCatalog.FindingMatches> itfMatches = new HashMap<>();
    /** Timestamp of the cached MPC ITF snapshot used for {@link #itfMatches}; null before Check MPC ITF. EDT only. */
    private Instant itfSnapshotTime;
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
    /** Alternative sample orbits consistent with the observations (from the built-in solver), by object. */
    private final Map<String, List<FindOrb.Orbit>> orbitSamples = new HashMap<>();
    /** Short description of the site-coordinate source used by the built-in orbit fit, by object. */
    private final Map<String, String> orbitSiteSources = new HashMap<>();
    /** How many nights each orbit in {@link #orbits} was fitted to. */
    private final Map<String, Integer> orbitNights = new HashMap<>();
    private final JComboBox<String> orbitSolverCombo = new JComboBox<>(new String[]{"Built-in (SpacePixels)", "Find_Orb (external)"});
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
    /** The running Find_Orb fit or JPL station check; null otherwise. EDT only. */
    private SwingWorker<?, ?> findOrbWorker, stationWorker;
    /** What the user recorded about each object and the MPC, as saved beside the report; EDT only. */
    private final Map<String, MpcRecords.Entry> records;
    /** Sub-stacks, changed observation ticks and measured light curves, as saved beside the report; EDT only. */
    private final Map<String, SavedMeasurements.Entry> measurements;
    /** Objects whose linked nights are left out of the file (This night only); EDT only. */
    private final Set<String> thisNightOnly = new java.util.HashSet<>();
    private final JCheckBox thisNightOnlyBox = new JCheckBox("This night only");
    private final JTextField designationField = new JTextField(10);
    private final JButton designationSave = new JButton("Save");
    private final JCheckBox submittedBox = new JCheckBox("Submitted from this night");
    private final WrappingHelpLabel recordLabel = new WrappingHelpLabel(" ", 320);
    /** UTC date of a night, by the start of its first exposure. */
    private static final DateTimeFormatter NIGHT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).withZone(ZoneOffset.UTC);
    /** Each measured faint mover's sub-stacks, in time order. */
    private final Map<String, List<SubStackAstrometry.Measurement>> subStacks = new HashMap<>();
    private final JButton measureButton = new JButton("Measure in sub-stacks");
    /** Sub-stacks a faint mover is measured in; the MPC asks for at least two per night, preferably three to five. */
    static final int SUB_STACKS = 3;
    private final WrappingHelpLabel findOrbPathLabel = new WrappingHelpLabel("Program: Not set", 320);
    private final WrappingHelpLabel orbitLabel = new WrappingHelpLabel(" ", 320);
    private static final String CARD_PICTURE = "picture";
    private static final String CARD_LOADING = "loading";
    /** Maximum number of objects whose cutouts stay cached in memory while this window is open. */
    private static final int MAX_CACHED_OBJECTS = 12;

    /** Progress published while reading an object's frames on a background worker. */
    private record LoadProgress(int completed, int total, String fileName) { }

    /** Cutouts and optional faint-mover review evidence kept for one object while this window is open. */
    private record ObjectPictures(List<short[][][]> crops, int[] fixedOrigin, boolean fixedAvailable,
                                  FaintMoverReviewAssets reviewAssets) { }

    private final AtomicInteger generation = new AtomicInteger();
    private SwingWorker<ObjectPictures, LoadProgress> worker;
    private boolean closed;
    private String checkedStationCode;
    private String stationProblem;
    private String stationText = "not checked";

    private final ObjectsModel objectsModel = new ObjectsModel();
    private final JTable objectsTable = new JTable(objectsModel);
    private final ObservationsModel observationsModel = new ObservationsModel();
    private final JTable observationsTable = new JTable(observationsModel);
    private final CardLayout pictureCards = new CardLayout();
    private final JPanel pictureStage = new JPanel(pictureCards);
    private final JLabel loadTitleLabel = new JLabel(" ");
    private final JProgressBar loadProgressBar = new JProgressBar();
    private final JLabel loadFileLabel = new JLabel(" ");
    private final Picture picture = new Picture();
    private final JSlider frameSlider = new JSlider(0, 0, 0);
    private final JToggleButton playButton = new JToggleButton("Play");
    private final JRadioButton objectCentred = new JRadioButton("Object-centred", true);
    private final JRadioButton starCentred = new JRadioButton("Star-centred");
    private final JCheckBox showFaintEvidenceBox = new JCheckBox("Residual & stack", true);
    private final JLabel pictureLabel = new JLabel(" ");
    private final JCheckBox stretchEnabledBox = new JCheckBox("Stretch", true);
    private final JComboBox<StretchAlgorithm> stretchAlgorithmCombo = new JComboBox<>(StretchAlgorithm.values());
    private final JLabel stretchPrimaryLabel = new JLabel();
    private final JSlider stretchPrimarySlider = new JSlider();
    private final JLabel stretchPrimaryValueLabel = new JLabel();
    private final JLabel stretchSecondaryLabel = new JLabel();
    private final JSlider stretchSecondarySlider = new JSlider();
    private final JLabel stretchSecondaryValueLabel = new JLabel();
    private final JButton stretchResetButton = new JButton("Reset");
    private final JLabel residualRangeLabel = new JLabel("Residual ±");
    private final JSlider residualRangeSlider = new JSlider(5, 80, (int) Math.round(FaintMoverReviewAssets.DEFAULT_RESIDUAL_BOUND * 10));
    private final JLabel residualRangeValueLabel = new JLabel("3.0");
    /** Local per-algorithm slider values remembered within this window only, independent of other viewers. */
    private final Map<StretchAlgorithm, int[]> localStretchValues = new java.util.EnumMap<>(StretchAlgorithm.class);
    private boolean adjustingStretch;
    /** Cached per-frame valid-pixel histograms across the session so each FITS frame is histogrammed at most once. */
    private final java.util.concurrent.atomic.AtomicReferenceArray<int[]> sessionFrameHistograms;
    /** Cached per-frame display LUTs for the current local stretch; cleared whenever stretch controls change. */
    private final Map<Integer, byte[]> frameLutCache = new HashMap<>();
    /** Bounded LRU cache of loaded objects' cutouts and faint-mover review assets while this window stays open. */
    private final Map<String, ObjectPictures> pictureCache = new java.util.LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ObjectPictures> eldest) {
            return size() > MAX_CACHED_OBJECTS;
        }
    };
    private final JLabel fitLabel = new JLabel(" ");
    private final IdentificationSummaryModel identificationModel = new IdentificationSummaryModel();
    private final JTable identificationTable = new JTable(identificationModel);
    private JScrollPane identificationScroll;
    private final JLabel identificationMatchesTitle = new JLabel("Nearby & MPC ITF matches");
    private final JButton identificationMatchesInfoLink = AccessibleControls.link(
            "ⓘ", "How Nearby and MPC ITF Offset and Motion are calculated", this::showNearbyMatchesInfo);
    private final JPanel identificationMatchesHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(4), 0)) {
        @Override public Dimension getMaximumSize() {
            Dimension pref = getPreferredSize();
            return new Dimension(Integer.MAX_VALUE, pref.height);
        }
    };
    private final IdentificationMatchesModel identificationMatchesModel = new IdentificationMatchesModel();
    private final JTable identificationMatchesTable = new JTable(identificationMatchesModel);
    private JScrollPane identificationMatchesScroll;
    private final JPanel identificationLinksPanel = new JPanel();
    private JScrollPane sideScroll;
    private final WrappingHelpLabel stationLabel = new WrappingHelpLabel(" ", 260);
    private final JTextArea problemsArea = new JTextArea(4, 30);
    private final JTextArea psvArea = new JTextArea(12, 30);
    private final JButton copyButton = new JButton("Copy");
    private final JButton saveButton = new JButton("Save .psv…");
    private final Timer playTimer;
    /** The shown object's cut-outs per frame: {object-centred, star-centred or null}, kept raw so the stretch can change. */
    private List<short[][][]> pictures = List.of();
    /** Optional faint-mover review assets (PSF-filtered residual cube and whole-session shift stack) for the shown object. */
    private FaintMoverReviewAssets shownReviewAssets;
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
        this(owner, files, report, header, () -> correction, settings, storage, visualization, site, catalogue, predictions, null);
    }

    /**
     * @param correction       the session's plate correction now (null when none), followed while the window is open
     * @param savePredictions  saves and installs the session's predictions with the objects Identify all added; null
     *                         keeps them in this window only
     */
    AsteroidsCometsWindow(Window owner, FitsFileInformation[] files, ReportedObjects.Report report, WcsCoordinateTransformer header,
                          java.util.function.Supplier<PlateSolutionCorrection> correction, ApplicationSettings settings,
                          ApplicationStorage storage, VisualizationSettings visualization, ObservingSite site, SkyCatalogue catalogue,
                          java.util.function.Supplier<eu.startales.spacepixels.solarsystem.SolarSystemObjects> predictions,
                          java.util.function.Consumer<eu.startales.spacepixels.solarsystem.SolarSystemObjects> savePredictions) {
        super("Asteroids & Comets · " + report.folder().getName());
        AppIcon.apply(this);
        this.files = files.clone();
        this.sessionFrameHistograms = new java.util.concurrent.atomic.AtomicReferenceArray<>(this.files.length);
        // Identifications made after the report (Compare again, Identify all) come back from the report folder.
        this.later = new java.util.LinkedHashMap<>(eu.startales.spacepixels.mpc.LaterIdentifications.load(report.folder()));
        this.report = later.isEmpty() ? report : report.withObjects(eu.startales.spacepixels.mpc.LaterIdentifications.apply(report.objects(), later));
        if (!later.isEmpty()) {
            compareLabel.setHtml(later.size() + " identification" + (later.size() == 1 ? "" : "s")
                    + " made after the report (Compare again, Identify all) are shown, from "
                    + eu.startales.spacepixels.mpc.LaterIdentifications.FILE_NAME + " beside it.");
        }
        this.records = new java.util.LinkedHashMap<>(MpcRecords.load(report.folder()));
        this.measurements = new java.util.LinkedHashMap<>(SavedMeasurements.load(report.folder()));
        this.correction = correction == null ? () -> null : correction;
        this.openedCorrection = this.correction.get();
        this.savePredictions = savePredictions;
        this.reduction = AstrometricReduction.create(header, openedCorrection, this.files);
        this.settings = settings;
        this.storage = storage;
        this.visualization = visualization;
        this.site = site;
        this.header = header;
        this.catalogue = catalogue;
        this.predictions = predictions == null ? () -> null : predictions;
        for (var object : report.objects()) {
            // A night already submitted is not offered again by default.
            var record = records.get(object.id());
            exported.put(object.id(), MpcExport.includedByDefault(object) && (record == null || record.submittedUtc == null));
            SavedMeasurements.Entry saved = measurements.get(object.id());
            List<MpcObservation> reduced;
            if (object.measured()) {
                reduced = reduction.reduction() == null ? List.of() : reduction.reduction().reducePositions(object.positions());
            } else if (saved != null && saved.subStacks != null && !saved.subStacks.isEmpty()) {
                subStacks.put(object.id(), saved.restoredSubStacks());
                reduced = saved.restoredSubStackObservations();
            } else {
                reduced = List.of();
            }
            observations.put(object.id(), reduced);
            Set<Integer> restoredSelection = saved == null ? null : saved.restoredSelection(reduced.size());
            selected.put(object.id(), restoredSelection != null
                    ? restoredSelection
                    : new TreeSet<>(object.measured()
                            ? MpcExport.spreadSelection(reduced.size(), MpcExport.DEFAULT_SELECTION)
                            : java.util.stream.IntStream.range(0, reduced.size()).boxed().toList()));
            if (saved != null && saved.lightCurve != null) {
                curves.put(object.id(), saved.lightCurve.toCurve());
            }
        }
        this.thisNight = new NightLinks.Night(this.report, reduction, site);
        playTimer =new Timer(Math.max(50, visualization.getGifBlinkSpeedMs()), e -> step(1));

        setLayout(new BorderLayout(UiStyle.space(8), UiStyle.space(8)));
        add(header(), BorderLayout.NORTH);
        JTabbedPane lower = new JTabbedPane();
        lower.addTab("Observations", observationsPanel());
        lower.addTab("Light curve", lightCurvePanel());
        lower.setMinimumSize(new Dimension(UiStyle.space(180), UiStyle.space(120)));
        JComponent pictureComp = picturePanel();
        pictureComp.setMinimumSize(new Dimension(UiStyle.space(180), UiStyle.space(140)));
        JSplitPane centre = new JSplitPane(JSplitPane.VERTICAL_SPLIT, pictureComp, lower);
        centre.setMinimumSize(new Dimension(UiStyle.space(200), UiStyle.space(200)));
        centre.setResizeWeight(0.55);
        centre.setContinuousLayout(true);
        centre.setOneTouchExpandable(true);
        JSplitPane left = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, objectsPanel(), centre);
        left.setMinimumSize(new Dimension(UiStyle.space(360), UiStyle.space(200)));
        left.setResizeWeight(0.32);
        left.setContinuousLayout(true);
        left.setOneTouchExpandable(true);
        sideScroll = buildSideScroll(sidePanel());
        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, sideScroll);
        main.setResizeWeight(0.68);
        main.setContinuousLayout(true);
        main.setOneTouchExpandable(true);
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
        scrollSideToTop();
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                int totalW = main.getWidth();
                if (totalW > UiStyle.space(600)) {
                    main.setDividerLocation((int) Math.round(totalW * 0.68));
                    left.setDividerLocation((int) Math.round(totalW * 0.25));
                }
                int centreH = centre.getHeight();
                if (centreH > UiStyle.space(300)) {
                    centre.setDividerLocation((int) Math.round(centreH * 0.55));
                }
                scrollSideToTop();
            }
        });
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
        itfButton.setToolTipText("Checks every object against the Minor Planet Center's Isolated Tracklet File (ITF) — "
                + "unlinked tracklets already submitted worldwide (same night and ±" + MpcItfCatalog.DEFAULT_NEARBY_DAYS
                + " days). Downloads and caches itf.txt.gz (~130 MB) on demand; the MPC has no online cone-search API for the ITF.");
        itfButton.addActionListener(e -> checkMpcItf());
        JPanel identifyButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        identifyButtons.add(compare);
        identifyButtons.add(identifyAllButton);
        identifyButtons.setAlignmentX(LEFT_ALIGNMENT);
        JPanel itfButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), UiStyle.space(3)));
        itfButtons.add(itfButton);
        detailsLink.setVisible(false);
        itfButtons.add(detailsLink);
        itfButtons.setAlignmentX(LEFT_ALIGNMENT);
        compareLabel.setForeground(UiStyle.helpColor());
        compareLabel.setAlignmentX(LEFT_ALIGNMENT);
        JLabel heading = title("Objects · " + report.objects().size());
        heading.setAlignmentX(LEFT_ALIGNMENT);
        JPanel north = new JPanel();
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        north.add(heading);
        north.add(identifyButtons);
        north.add(itfButtons);
        north.add(compareLabel);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(north, BorderLayout.NORTH);
        panel.add(new JScrollPane(objectsTable), BorderLayout.CENTER);
        // Keep a modest minimum width so the three main split panes can always be dragged freely.
        panel.setMinimumSize(new Dimension(UiStyle.space(180), UiStyle.space(140)));
        panel.setPreferredSize(new Dimension(UiStyle.space(360), UiStyle.space(400)));
        return panel;
    }

    /** The blink of the selected object, with local per-frame stretch controls and a loading progress card. */
    private JComponent picturePanel() {
        ButtonGroup group = new ButtonGroup();
        group.add(objectCentred);
        group.add(starCentred);
        objectCentred.addActionListener(e -> picture.repaint());
        starCentred.addActionListener(e -> picture.repaint());
        showFaintEvidenceBox.setToolTipText("Show the faint mover's synchronized PSF-filtered residual and whole-session shift stack beside the source frames");
        showFaintEvidenceBox.setVisible(false);
        showFaintEvidenceBox.addActionListener(e -> {
            updateResidualRangeVisibility();
            picture.repaint();
        });
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
        JPanel controls = new WrappingControlsPanel(UiStyle.space(6), UiStyle.space(2));
        for (JComponent c : new JComponent[]{playButton, previous, next, frameSlider, objectCentred, starCentred, showFaintEvidenceBox}) {
            controls.add(c);
        }

        // Independent per-frame stretch controls (same algorithms as Preview Frame & Blink, never affecting other windows).
        stretchEnabledBox.setToolTipText("Apply the per-frame display stretch in this window only (does not change Preview Frame, Blink or Image Stretch). Untick for a linear view.");
        stretchEnabledBox.addActionListener(e -> {
            frameLutCache.clear();
            updateStretchControlStates();
            picture.repaint();
        });
        stretchAlgorithmCombo.setSelectedItem(StretchAlgorithm.ASINH);
        stretchAlgorithmCombo.setToolTipText("Display stretch function for this window only; applied per frame so sky background changes do not darken frames.");
        stretchAlgorithmCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                list.setToolTipText(value == null ? null : StretchPanel.algorithmDescription((StretchAlgorithm) value));
                return c;
            }
        });
        stretchAlgorithmCombo.addActionListener(e -> {
            if (adjustingStretch) return;
            applyStretchAlgorithm(false);
            frameLutCache.clear();
            picture.repaint();
        });
        for (JSlider slider : new JSlider[]{stretchPrimarySlider, stretchSecondarySlider}) {
            slider.setPreferredSize(new Dimension(UiStyle.space(110), slider.getPreferredSize().height));
            slider.addChangeListener(e -> {
                if (adjustingStretch) return;
                StretchAlgorithm algorithm = selectedStretchAlgorithm();
                localStretchValues.put(algorithm, new int[]{stretchPrimarySlider.getValue(), stretchSecondarySlider.getValue()});
                updateStretchValueLabels();
                frameLutCache.clear();
                picture.repaint();
            });
        }
        stretchResetButton.setToolTipText("Return the sliders to the defaults of the selected stretch function.");
        stretchResetButton.addActionListener(e -> {
            applyStretchAlgorithm(true);
            frameLutCache.clear();
            picture.repaint();
        });
        residualRangeSlider.setPreferredSize(new Dimension(UiStyle.space(90), residualRangeSlider.getPreferredSize().height));
        residualRangeSlider.setToolTipText("Symmetric display scale for the PSF-filtered residual panel (± processed units).");
        residualRangeSlider.addChangeListener(e -> {
            residualRangeValueLabel.setText(String.format(Locale.US, "%.1f", residualRangeSlider.getValue() / 10.0));
            picture.repaint();
        });
        stretchPrimaryValueLabel.setForeground(UiStyle.helpColor());
        stretchSecondaryValueLabel.setForeground(UiStyle.helpColor());
        residualRangeValueLabel.setForeground(UiStyle.helpColor());
        applyStretchAlgorithm(false);
        updateStretchControlStates();
        updateResidualRangeVisibility();

        JPanel stretch = new WrappingControlsPanel(UiStyle.space(6), UiStyle.space(2));
        stretch.add(stretchEnabledBox);
        stretch.add(stretchAlgorithmCombo);
        stretch.add(stretchPrimaryLabel);
        stretch.add(stretchPrimarySlider);
        stretch.add(stretchPrimaryValueLabel);
        stretch.add(stretchSecondaryLabel);
        stretch.add(stretchSecondarySlider);
        stretch.add(stretchSecondaryValueLabel);
        stretch.add(stretchResetButton);
        stretch.add(residualRangeLabel);
        stretch.add(residualRangeSlider);
        stretch.add(residualRangeValueLabel);

        pictureLabel.setForeground(UiStyle.helpColor());
        JPanel south = new JPanel(new BorderLayout(0, UiStyle.space(2)));
        south.add(stretch, BorderLayout.NORTH);
        south.add(pictureLabel, BorderLayout.SOUTH);

        pictureStage.add(picture, CARD_PICTURE);
        pictureStage.add(buildPictureLoadingPanel(), CARD_LOADING);
        pictureCards.show(pictureStage, CARD_PICTURE);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(controls, BorderLayout.NORTH);
        panel.add(pictureStage, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    /** Centred progress card shown in the animation area while frames are read from disk, matching BlinkFrame. */
    private JComponent buildPictureLoadingPanel() {
        loadTitleLabel.setFont(loadTitleLabel.getFont().deriveFont(Font.BOLD, loadTitleLabel.getFont().getSize2D() + 2f));
        loadProgressBar.setStringPainted(true);
        Dimension barSize = new Dimension(UiStyle.space(320), loadProgressBar.getPreferredSize().height);
        loadProgressBar.setPreferredSize(barSize);
        loadProgressBar.setMaximumSize(barSize);
        loadFileLabel.setForeground(UiStyle.helpColor());
        JLabel note = new JLabel("Each frame is read once per object; the animation then plays from memory.");
        note.setForeground(UiStyle.helpColor());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (JComponent component : new JComponent[]{loadTitleLabel, loadProgressBar, loadFileLabel, note}) {
            component.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        column.add(loadTitleLabel);
        column.add(Box.createVerticalStrut(UiStyle.space(8)));
        column.add(loadProgressBar);
        column.add(Box.createVerticalStrut(UiStyle.space(6)));
        column.add(loadFileLabel);
        column.add(Box.createVerticalStrut(UiStyle.space(12)));
        column.add(note);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.add(column);
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
        measureButton.setToolTipText("Cancel or retry stacking the frames in three independent groups along the faint mover's motion");
        measureButton.addActionListener(e -> {
            if (subStackWorker != null) {
                subStackWorker.cancel(true);
            } else if (shown != null) {
                autoSubStackTried.remove(shown.id());
                subStackNeedsRetry.remove(shown.id());
                measureSubStacks(shown);
            }
        });
        measureButton.setVisible(false);
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
        curveButton.setToolTipText("Cancel or retry measuring the object's light curve across the session");
        binChoice.setToolTipText("Averages consecutive frames: less noise for a faint object, but a change faster than a bin is smoothed away");
        binChoice.addActionListener(e -> updateCurve());
        curveButton.addActionListener(e -> {
            if (curveWorker != null) {
                curveWorker.cancel(true);
            } else if (shown != null) {
                autoCurveTried.remove(shown.id());
                curveNeedsRetry.remove(shown.id());
                measureLightCurve(shown);
            }
        });
        curveButton.setVisible(false);
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

    /** Measures {@code object}'s light in each frame on a worker, with the comparison stars, and keeps the curve. */
    private void measureLightCurve(ReportedObjects.ReportedObject object) {
        if (object == null || object.positions().isEmpty()) return;
        if (curveWorker != null) {
            if (object.id().equals(curveTargetId)) return;
            curveWorker.cancel(true);
        }
        autoCurveTried.add(object.id());
        curveNeedsRetry.remove(object.id());
        curveTargetId = object.id();
        double fwhm = LightCurve.fwhm(report, object);
        var samples = LightCurve.samples(report, object);
        double speed = LightCurve.pixelsPerMinute(object, samples);
        WcsCoordinateTransformer sky = sky();
        updateAutoMeasureButtons();
        if (shown == object) curveLabel.setText("Measuring " + object.id() + "'s light curve in " + samples.size() + " frames…");
        curveWorker = new SwingWorker<LightCurve.Curve, String>() {
            @Override protected LightCurve.Curve doInBackground() throws Exception {
                LightCurve.Frames frames = AsteroidsCometsWindow.this::readFrame;
                double[] box = LightCurve.pathBox(samples);
                short[][] middle = frames.pixels(samples.get(samples.size() / 2).frameNumber());
                var candidates = PhotometryStars.candidates(catalogue);
                var comparisons = LightCurve.comparisonStars(middle, sky, candidates, box, fwhm);
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
                        var more = LightCurve.comparisonStars(middle, sky, deeper, box, fwhm);
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
                if (curveWorker == this) {
                    curveWorker = null;
                    curveTargetId = null;
                }
                if (closed) return;
                if (isCancelled()) {
                    if (shown == object) {
                        curveNeedsRetry.add(object.id());
                        curveLabel.setText(object.id() + "'s light curve was cancelled.");
                    } else {
                        // Cancelled automatically when switching to another object: allow auto-measure if selected again.
                        autoCurveTried.remove(object.id());
                    }
                    updateAutoMeasureButtons();
                    return;
                }
                try {
                    var curve = get();
                    if (curve != null) {
                        curves.put(object.id(), curve);
                        measurementEntry(object.id()).lightCurve = SavedMeasurements.SavedCurve.from(curve);
                        saveMeasurements();
                    }
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    curveNeedsRetry.add(object.id());
                    updateAutoMeasureButtons();
                    if (shown == object) curveLabel.setText(object.id() + "'s light curve could not be measured: " + cause.getMessage());
                    return;
                }
                updateAutoMeasureButtons();
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
            if (curveWorker != null && shown.id().equals(curveTargetId)) {
                curveLabel.setText("Measuring " + shown.id() + "'s light curve…");
            } else if (subStackWorker != null && shown.id().equals(subStackTargetId)) {
                curveLabel.setText("Measuring " + shown.id() + " in sub-stacks first; light curve starts next…");
            } else {
                curveLabel.setHtml(shown.id() + "'s light curve is measured automatically when selected"
                        + (shown.measured() ? "" : " (at its fitted positions; single-frame faint-mover values are noisy)")
                        + (catalogue == null ? ". Without a sky catalogue the curve is not corrected for transparency." : ".")
                        + catalogueWarning());
            }
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

    /** Wraps the right side panel in a resizable width-tracking scroll pane so splitters are never blocked. */
    private static JScrollPane buildSideScroll(JComponent content) {
        WidthTrackingPanel host = new WidthTrackingPanel();
        host.add(content, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(host);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setMinimumSize(new Dimension(UiStyle.space(180), UiStyle.space(160)));
        scroll.setPreferredSize(new Dimension(UiStyle.space(440), UiStyle.space(600)));
        scroll.getVerticalScrollBar().setUnitIncrement(UiStyle.space(20));
        return scroll;
    }

    /** Scrolls the right panel cleanly back to the top ("Before you report"). */
    private void scrollSideToTop() {
        if (sideScroll == null) return;
        SwingUtilities.invokeLater(() -> {
            if (closed || sideScroll == null) return;
            sideScroll.getViewport().setViewPosition(new Point(0, 0));
            sideScroll.getVerticalScrollBar().setValue(0);
            sideScroll.getHorizontalScrollBar().setValue(0);
        });
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
        configureIdentificationTables();
        panel.add(identificationScroll);
        identificationMatchesTitle.setForeground(UiStyle.helpColor());
        identificationMatchesInfoLink.setToolTipText(
                "How Offset and Motion are calculated for Nearby & MPC ITF matches, and possible uncertainties");
        identificationMatchesInfoLink.setMargin(new Insets(0, UiStyle.space(2), 0, UiStyle.space(2)));
        identificationMatchesHeader.setOpaque(false);
        identificationMatchesHeader.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(6), UiStyle.space(2), UiStyle.space(2), 0));
        identificationMatchesHeader.setAlignmentX(LEFT_ALIGNMENT);
        identificationMatchesHeader.add(identificationMatchesTitle);
        identificationMatchesHeader.add(identificationMatchesInfoLink);
        identificationMatchesHeader.setVisible(false);
        panel.add(identificationMatchesHeader);
        panel.add(identificationMatchesScroll);
        identificationLinksPanel.setLayout(new BoxLayout(identificationLinksPanel, BoxLayout.Y_AXIS));
        identificationLinksPanel.setOpaque(false);
        identificationLinksPanel.setAlignmentX(LEFT_ALIGNMENT);
        identificationLinksPanel.setVisible(false);
        panel.add(identificationLinksPanel);
        panel.add(Box.createVerticalStrut(UiStyle.space(4)));
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
        thisNightOnlyBox.setToolTipText("Puts only this night's observations in the file, without the linked nights");
        thisNightOnlyBox.setAlignmentX(LEFT_ALIGNMENT);
        thisNightOnlyBox.addActionListener(e -> {
            if (shown == null) return;
            if (thisNightOnlyBox.isSelected()) thisNightOnly.add(shown.id()); else thisNightOnly.remove(shown.id());
            refreshObjects();
            refreshExport();
        });
        panel.add(thisNightOnlyBox);
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("At the MPC"));
        JPanel designationRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        designationRow.add(new JLabel("MPC designation"));
        designationField.setToolTipText("The designation the MPC gave this object: a provisional designation, a number, or "
                + "the temporary designation on the NEO Confirmation Page. The file then names the object by it, on every linked night.");
        designationField.addActionListener(e -> saveDesignation());
        designationRow.add(designationField);
        designationSave.addActionListener(e -> saveDesignation());
        designationRow.add(designationSave);
        designationRow.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(designationRow);
        submittedBox.setToolTipText("Tick after you have submitted this night's observations of this object to the MPC. "
                + "It is not offered for export again by default, and is left out when a linked night is exported.");
        submittedBox.setAlignmentX(LEFT_ALIGNMENT);
        submittedBox.addActionListener(e -> saveSubmitted());
        panel.add(submittedBox);
        recordLabel.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(recordLabel);
        updateNights();
        panel.add(Box.createVerticalStrut(UiStyle.space(10)));
        panel.add(title("Orbit · Built-in & Find_Orb"));
        orbitSolverCombo.setToolTipText("Built-in runs SpacePixels' own preliminary orbit solver with no external program; "
                + "Find_Orb uses Bill Gray's local fo64/fo executable or opens the online Find_Orb page");
        orbitSolverCombo.addActionListener(e -> updateOrbit());
        JPanel solverRow = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        solverRow.add(new JLabel("Solver"));
        solverRow.add(orbitSolverCombo);
        solverRow.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(solverRow);
        JButton findOrb = new JButton("Compute orbit");
        findOrb.setToolTipText("Fits a preliminary orbit to the selected observations using the selected solver (Built-in or Find_Orb)");
        findOrb.addActionListener(e -> openFindOrb());
        JButton chooseFindOrb = new JButton("Find_Orb program…");
        chooseFindOrb.setToolTipText("Optional: choose fo64.exe or fo32.exe (Windows) or fo (Linux, BSD, macOS) in the console Find_Orb folder");
        chooseFindOrb.addActionListener(e -> chooseFindOrb());
        showOrbitButton.setToolTipText("Opens the interactive 3D orbit viewer with elements, zoom and time animation");
        showOrbitButton.addActionListener(e -> showOrbitWindow());
        JPanel orbitButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        orbitButtons.add(findOrb);
        orbitButtons.add(chooseFindOrb);
        orbitButtons.add(showOrbitButton);
        orbitButtons.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(orbitButtons);
        panel.add(findOrbPathLabel);
        panel.add(orbitLabel);
        panel.add(new WrappingHelpLabel("Built-in fits preliminary short- and multi-night orbits directly in SpacePixels. "
                + "Optional local Find_Orb (fo64.exe or fo32.exe on Windows, fo on Linux, BSD and macOS) adds full planetary perturbations.", 320));
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
        preventAutoScroll(problemsArea);
        JScrollPane problemsScroll = new JScrollPane(problemsArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        int problemsHeight = UiStyle.space(84);
        problemsScroll.setAlignmentX(LEFT_ALIGNMENT);
        problemsScroll.setMinimumSize(new Dimension(UiStyle.space(120), problemsHeight));
        problemsScroll.setPreferredSize(new Dimension(UiStyle.space(320), problemsHeight));
        problemsScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, problemsHeight));
        panel.add(problemsScroll);
        panel.add(Box.createVerticalStrut(UiStyle.space(4)));
        psvArea.setEditable(false);
        psvArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiStyle.bodyFont().getSize() - 1));
        preventAutoScroll(psvArea);
        JScrollPane psvScroll = new JScrollPane(psvArea,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        int psvHeight = UiStyle.space(180);
        psvScroll.setAlignmentX(LEFT_ALIGNMENT);
        psvScroll.setMinimumSize(new Dimension(UiStyle.space(120), psvHeight));
        psvScroll.setPreferredSize(new Dimension(UiStyle.space(320), psvHeight));
        psvScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, psvHeight));
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
            identificationModel.setRows(List.of());
            identificationMatchesModel.setRows(List.of());
            updateIdentificationTableSizes();
            updateIdentificationLinks(null);
            updateAutoMeasureButtons();
            return;
        }
        // Cancel any background sub-stack or light-curve measurement left over from a previously selected object.
        if (subStackWorker != null && !object.id().equals(subStackTargetId)) {
            subStackWorker.cancel(true);
        }
        if (curveWorker != null && !object.id().equals(curveTargetId)) {
            curveWorker.cancel(true);
        }
        boolean needsSubStacks = !object.measured()
                && !subStacks.containsKey(object.id())
                && !autoSubStackTried.contains(object.id())
                && object.motion() != null
                && reduction.reduction() != null;
        boolean needsCurve = !curves.containsKey(object.id())
                && !autoCurveTried.contains(object.id())
                && !object.positions().isEmpty();
        if (needsSubStacks) {
            measureSubStacks(object);
            updateCurve();
        } else if (needsCurve && subStackWorker == null) {
            measureLightCurve(object);
        }
        updateAutoMeasureButtons();
        updateIdentificationTables(object);
        updateIdentificationLinks(object);
        loadPictures(object);
    }

    /** Shows Cancel only while a measurement is running, Retry only after cancellation/failure, and hides both when idle. */
    private void updateAutoMeasureButtons() {
        if (subStackWorker != null) {
            measureButton.setText("Cancel sub-stacks");
            measureButton.setVisible(true);
            measureButton.setEnabled(true);
        } else if (shown != null && !shown.measured() && !subStacks.containsKey(shown.id())
                && subStackNeedsRetry.contains(shown.id()) && shown.motion() != null && reduction.reduction() != null) {
            measureButton.setText("Retry sub-stacks");
            measureButton.setVisible(true);
            measureButton.setEnabled(true);
        } else {
            measureButton.setVisible(false);
        }

        if (curveWorker != null) {
            curveButton.setText("Cancel light curve");
            curveButton.setVisible(true);
            curveButton.setEnabled(true);
        } else if (shown != null && !curves.containsKey(shown.id())
                && curveNeedsRetry.contains(shown.id()) && !shown.positions().isEmpty()) {
            curveButton.setText("Retry light curve");
            curveButton.setVisible(true);
            curveButton.setEnabled(true);
        } else {
            curveButton.setVisible(false);
        }
    }

    /** Prevents programmatic {@code setText} updates on bottom text areas from scrolling the outer right pane down. */
    private static void preventAutoScroll(JTextArea area) {
        if (area.getCaret() instanceof javax.swing.text.DefaultCaret caret) {
            caret.setUpdatePolicy(javax.swing.text.DefaultCaret.NEVER_UPDATE);
        }
    }

    /** Configures the structured Identification summary and candidate-matches tables with wrapped cells. */
    private void configureIdentificationTables() {
        var wrapRenderer = new WrappingTableCellRenderer();
        identificationTable.setDefaultRenderer(Object.class, wrapRenderer);
        identificationTable.setFillsViewportHeight(true);
        identificationTable.setRowSelectionAllowed(true);
        identificationTable.setShowHorizontalLines(true);
        identificationTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        identificationTable.getTableHeader().setReorderingAllowed(false);
        identificationTable.getColumnModel().getColumn(0).setPreferredWidth(UiStyle.space(115));
        identificationTable.getColumnModel().getColumn(0).setMinWidth(UiStyle.space(90));
        identificationTable.getColumnModel().getColumn(0).setMaxWidth(UiStyle.space(160));
        identificationTable.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                adjustWrappedRowHeights(identificationTable);
                updateIdentificationTableSizes();
            }
        });
        identificationScroll = new JScrollPane(identificationTable);
        identificationScroll.setAlignmentX(LEFT_ALIGNMENT);
        identificationScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        identificationMatchesTable.setDefaultRenderer(Object.class, wrapRenderer);
        identificationMatchesTable.setFillsViewportHeight(true);
        identificationMatchesTable.setRowSelectionAllowed(true);
        identificationMatchesTable.setShowHorizontalLines(true);
        identificationMatchesTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        identificationMatchesTable.getTableHeader().setReorderingAllowed(false);
        int[] widths = {88, 100, 96, 78, 62, 118};
        for (int i = 0; i < widths.length; i++) {
            var col = identificationMatchesTable.getColumnModel().getColumn(i);
            col.setPreferredWidth(UiStyle.space(widths[i]));
            col.setMinWidth(UiStyle.space(Math.min(54, widths[i])));
        }
        identificationMatchesTable.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                adjustWrappedRowHeights(identificationMatchesTable);
                updateIdentificationTableSizes();
            }
        });
        identificationMatchesScroll = new JScrollPane(identificationMatchesTable);
        identificationMatchesScroll.setAlignmentX(LEFT_ALIGNMENT);
        identificationMatchesScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        identificationMatchesScroll.setVisible(false);
    }

    /** Populates both the Identification summary table and the nearby/MPC-ITF candidate matches table for {@code object}. */
    private void updateIdentificationTables(ReportedObjects.ReportedObject object) {
        var rows = new ArrayList<String[]>();
        String kindLabel = object.measured() ? "Track" : "Faint mover";
        rows.add(new String[]{"Object", object.id() + " · " + kindLabel});
        rows.add(new String[]{"Status", statusText(status(object))});
        if (!object.names().isEmpty()) {
            rows.add(new String[]{"Known name", String.join(" or ", object.names()) + predictedMagnitudeSuffix(object)});
        }
        if (object.speedArcsecPerHour() != null && Double.isFinite(object.speedArcsecPerHour())) {
            String motion = String.format(Locale.US, "%.1f″/h", object.speedArcsecPerHour())
                    + (object.positionAngleDegrees() != null && Double.isFinite(object.positionAngleDegrees())
                    ? String.format(Locale.US, " · PA %.1f°", object.positionAngleDegrees()) : "");
            rows.add(new String[]{"Motion", motion});
        }
        if (object.note() != null && !object.note().isBlank()) {
            rows.add(new String[]{"Report note", object.note()});
        }
        var near = nearby.get(object.id());
        if (near != null) {
            rows.add(new String[]{"Identify all", near.isEmpty() ? "No known object within 1′"
                    : near.size() + " known object" + (near.size() == 1 ? "" : "s") + " within 1′ (see table below)"});
        }
        if (itfSnapshotTime != null) {
            var fm = itfMatches.get(object.id());
            String snapDate = itfSnapshotTime.atZone(ZoneOffset.UTC).toLocalDate().toString();
            if (fm != null) {
                if (fm.hasSameNight()) {
                    rows.add(new String[]{"MPC ITF (same night)", "Already reported: "
                            + String.join(", ", fm.sameNight().stream().map(m -> m.tracklet().label()).toList())
                            + " (snapshot " + snapDate + ")"});
                } else {
                    rows.add(new String[]{"MPC ITF (same night)", "Not reported in ITF (snapshot " + snapDate + ")"});
                }
                if (fm.hasNearbyNights()) {
                    rows.add(new String[]{"MPC ITF (±" + MpcItfCatalog.DEFAULT_NEARBY_DAYS + " d)",
                            fm.nearbyNights().size() + " linear candidate" + (fm.nearbyNights().size() == 1 ? "" : "s")
                                    + " — verify with orbit fit (see table below)"});
                } else {
                    rows.add(new String[]{"MPC ITF (±" + MpcItfCatalog.DEFAULT_NEARBY_DAYS + " d)", "No nearby-night candidates"});
                }
            }
        }
        if (!object.measured()) {
            var stacks = subStacks.get(object.id());
            if (stacks == null) {
                if (subStackWorker != null && object.id().equals(subStackTargetId)) {
                    rows.add(new String[]{"Sub-stacks", "Measuring in " + SUB_STACKS + " independent sub-stacks…"});
                } else if (reduction.reduction() == null) {
                    rows.add(new String[]{"Sub-stacks", "Plate solution needed before measuring sub-stacks."});
                } else {
                    rows.add(new String[]{"Sub-stacks", "Not measured (" + SUB_STACKS + " independent sub-stacks)."});
                }
            } else {
                var sb = new StringBuilder();
                for (int k = 0; k < stacks.size(); k++) {
                    var m = stacks.get(k);
                    if (k > 0) sb.append('\n');
                    sb.append(String.format(Locale.US, "#%d · %d fr · %s UTC · SNR %.1f · %s", k + 1, m.frames(),
                            UTC.format(Instant.ofEpochMilli(m.midpointMillis())), m.snr(), m.clear() ? "clear" : m.problem()));
                }
                rows.add(new String[]{"Sub-stacks", sb.toString()});
            }
        }
        if (reduction.reduction() == null && reduction.problem() != null && !reduction.problem().isBlank()) {
            rows.add(new String[]{"Astrometry", reduction.problem()});
        }
        identificationModel.setRows(rows);

        var matchRows = new ArrayList<String[]>();
        if (near != null) {
            for (var m : near) {
                String mag = m.magnitude() == null ? "–" : String.format(Locale.US, "V %.1f", m.magnitude());
                String offset = String.format(Locale.US, "%.0f″", m.offsetArcsec());
                String motion = String.format(Locale.US, "%s (Δv %.1f″/h)%s",
                        m.motionAgrees() ? "agreeing" : "differing", m.rateDifferenceArcsecPerHour(),
                        m.nearerFinding() == null ? "" : String.format(Locale.US, "; %s nearer (%.0f″)", m.nearerFinding(), m.nearerOffsetArcsec()));
                matchRows.add(new String[]{"Identify all", m.name(), "Mid-session", mag, offset, motion});
            }
        }
        if (itfSnapshotTime != null) {
            var fm = itfMatches.get(object.id());
            if (fm != null) {
                for (var m : fm.sameNight()) {
                    matchRows.add(itfMatchRow("ITF same night", m));
                }
                for (var m : fm.nearbyNights()) {
                    matchRows.add(itfMatchRow("ITF nearby", m));
                }
            }
        }
        identificationMatchesModel.setRows(matchRows);
        boolean hasMatches = !matchRows.isEmpty();
        identificationMatchesHeader.setVisible(hasMatches);
        identificationMatchesScroll.setVisible(hasMatches);
        adjustWrappedRowHeights(identificationTable);
        if (hasMatches) adjustWrappedRowHeights(identificationMatchesTable);
        updateIdentificationTableSizes();
    }

    private static String[] itfMatchRow(String source, MpcItfCatalog.Match m) {
        var t = m.tracklet();
        String dateCol = m.sameNight() ? t.midDateUtc()
                : String.format(Locale.US, "%+.1fd (%s)", m.deltaDays(), t.midDateUtc());
        String obsMag = t.observationCount() + " obs" + (t.magnitude().isEmpty() ? "" : " · " + t.magnitude());
        String offset = m.offsetArcsec() < 120.0
                ? String.format(Locale.US, "%.1f″", m.offsetArcsec())
                : String.format(Locale.US, "%.2f′", m.offsetArcsec() / 60.0);
        var motion = new StringBuilder();
        if (m.speedDiffArcsecPerHour() != null) {
            motion.append(String.format(Locale.US, "Δv %.1f″/h", m.speedDiffArcsecPerHour()));
        }
        if (m.paDiffDegrees() != null) {
            if (!motion.isEmpty()) motion.append(", ");
            motion.append(String.format(Locale.US, "ΔPA %.0f°", m.paDiffDegrees()));
        }
        if (motion.isEmpty()) motion.append("–");
        return new String[]{source, t.label(), dateCol, obsMag, offset, motion.toString()};
    }

    /** Computes exact wrapped row heights for a multi-line table based on its current column widths. */
    private static void adjustWrappedRowHeights(JTable table) {
        int rows = table.getRowCount();
        int cols = table.getColumnCount();
        int baseHeight = Math.max(UiStyle.space(20), table.getFontMetrics(table.getFont()).getHeight() + UiStyle.space(6));
        for (int r = 0; r < rows; r++) {
            int maxH = baseHeight;
            for (int c = 0; c < cols; c++) {
                var renderer = table.getCellRenderer(r, c);
                var comp = table.prepareRenderer(renderer, r, c);
                int colW = Math.max(UiStyle.space(40), table.getColumnModel().getColumn(c).getWidth());
                comp.setSize(new Dimension(colW, Short.MAX_VALUE));
                int prefH = comp.getPreferredSize().height + UiStyle.space(4);
                if (prefH > maxH) maxH = prefH;
            }
            if (table.getRowHeight(r) != maxH) table.setRowHeight(r, maxH);
        }
    }

    /** Sizes the Identification table scroll panes neatly to their content (capped so long match lists scroll). */
    private void updateIdentificationTableSizes() {
        if (identificationScroll != null) {
            int sumH = totalTableHeight(identificationTable, UiStyle.space(90), UiStyle.space(260));
            identificationScroll.setPreferredSize(new Dimension(UiStyle.space(320), sumH));
            identificationScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, sumH));
            identificationScroll.revalidate();
        }
        if (identificationMatchesScroll != null && identificationMatchesScroll.isVisible()) {
            int matchH = totalTableHeight(identificationMatchesTable, UiStyle.space(70), UiStyle.space(180));
            identificationMatchesScroll.setPreferredSize(new Dimension(UiStyle.space(320), matchH));
            identificationMatchesScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, matchH));
            identificationMatchesScroll.revalidate();
        }
        if (sideScroll != null) {
            sideScroll.revalidate();
        }
    }

    private static int totalTableHeight(JTable table, int minHeight, int maxHeight) {
        int headerH = table.getTableHeader() == null ? UiStyle.space(22) : Math.max(UiStyle.space(22), table.getTableHeader().getPreferredSize().height);
        int rowsH = 0;
        for (int r = 0; r < table.getRowCount(); r++) rowsH += table.getRowHeight(r);
        int h = headerH + rowsH + UiStyle.space(8);
        return Math.max(minHeight, Math.min(maxHeight, h));
    }

    /** Rebuilds the external JPL SBDB lookup link(s) for the selected object's identified names. */
    private void updateIdentificationLinks(ReportedObjects.ReportedObject object) {
        identificationLinksPanel.removeAll();
        boolean hasLinks = false;
        if (object != null && !object.names().isEmpty()) {
            for (String name : object.names()) {
                String url = informationUrlFor(report, name);
                if (url == null) continue;
                JButton link = AccessibleControls.link(
                        "JPL details: " + name + " ↗",
                        "Open " + name + " in JPL Small-Body Database (magnitude, orbit and physical data)",
                        () -> browse(url));
                link.setToolTipText(url);
                link.setAlignmentX(LEFT_ALIGNMENT);
                identificationLinksPanel.add(link);
                hasLinks = true;
            }
        }
        identificationLinksPanel.setVisible(hasLinks);
        identificationLinksPanel.revalidate();
        identificationLinksPanel.repaint();
    }

    /** Resolves the external JPL Small-Body Database URL for an identified object name, matching the HTML report. */
    static String informationUrlFor(ReportedObjects.Report report, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (report != null && report.knownBodies() != null) {
            ReportedObjects.KnownBody body = report.knownBodies().get(name);
            if (body != null) {
                if (body.informationUrl() != null && !body.informationUrl().isBlank()) {
                    return body.informationUrl();
                }
                return JplSbdbLookup.informationUrl(name, body.comet());
            }
        }
        return JplSbdbLookup.informationUrl(name);
    }

    /** Short predicted V-magnitude suffix from {@code report-data.json}, or empty when unavailable. */
    private String predictedMagnitudeSuffix(ReportedObjects.ReportedObject object) {
        if (object == null || object.names().size() != 1 || report.knownBodies() == null) {
            return "";
        }
        ReportedObjects.KnownBody body = report.knownBodies().get(object.names().getFirst());
        if (body == null || body.minimumMagnitude() == null || !Double.isFinite(body.minimumMagnitude())) {
            return "";
        }
        double min = body.minimumMagnitude();
        Double maxObj = body.maximumMagnitude();
        if (maxObj != null && Double.isFinite(maxObj) && Math.abs(maxObj - min) >= 0.05) {
            return String.format(Locale.US, " (predicted V %.1f–%.1f)", min, maxObj);
        }
        return String.format(Locale.US, " (predicted V %.1f)", min);
    }

    /** Reads each frame of the object once (with progress feedback and session cache), plus faint-mover review assets. */
    private void loadPictures(ReportedObjects.ReportedObject object) {
        int request = generation.incrementAndGet();
        if (worker != null) worker.cancel(true);
        pictures = List.of();
        shownReviewAssets = null;

        ObjectPictures cached = pictureCache.get(object.id());
        if (cached != null) {
            applyObjectPictures(cached);
            pictureCards.show(pictureStage, CARD_PICTURE);
            return;
        }

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
        showFaintEvidenceBox.setVisible(false);
        updateResidualRangeVisibility();

        int total = positions.size();
        loadTitleLabel.setText("Reading frames for " + object.id() + "…");
        loadProgressBar.setIndeterminate(total == 0);
        loadProgressBar.setMinimum(0);
        loadProgressBar.setMaximum(Math.max(1, total));
        loadProgressBar.setValue(0);
        loadProgressBar.setString(total == 0 ? "Reading…" : "0 / " + total + " frames");
        loadFileLabel.setText(" ");
        pictureCards.show(pictureStage, CARD_LOADING);
        pictureLabel.setText(total == 0 ? "Reading frames for " + object.id() + "…"
                : "Reading " + total + " frames for " + object.id() + "…");

        ReportedObjects.FaintMoverAssetPaths assetPaths = object.measured() ? null : report.faintMoverAssets().get(object.id());
        File reportFolder = report.folder();
        worker = new SwingWorker<>() {
            @Override
            protected ObjectPictures doInBackground() throws Exception {
                var objectCrops = new ArrayList<short[][]>(total);
                var fixedCrops = new ArrayList<short[][]>(fixed ? total : 0);
                for (int k = 0; k < total; k++) {
                    if (isCancelled()) return null;
                    var p = positions.get(k);
                    int index = p.frameNumber() - 1;
                    if (index < 0 || index >= files.length) {
                        throw new java.io.IOException("frame " + p.frameNumber() + " is not in this session");
                    }
                    String fileName = files[index].getFileName();
                    publish(new LoadProgress(k, total, fileName));
                    short[][] frame;
                    try (Fits fits = new Fits(new File(files[index].getFilePath()))) {
                        Object kernel = FitsInput.getImageHDU(fits).getKernel();
                        if (!(kernel instanceof short[][] pixels)) {
                            throw new java.io.IOException(fileName + " is not a 16-bit monochrome frame");
                        }
                        frame = pixels;
                    }
                    if (sessionFrameHistograms.get(index) == null) {
                        sessionFrameHistograms.compareAndSet(index, null, DisplayStretch.validHistogram(frame));
                    }
                    objectCrops.add(crop(frame, (int) Math.round(p.x()) - CROP / 2, (int) Math.round(p.y()) - CROP / 2, CROP, CROP));
                    if (fixed) fixedCrops.add(crop(frame, fx, fy, fw, fh));
                    publish(new LoadProgress(k + 1, total, fileName));
                }
                if (isCancelled()) return null;
                FaintMoverReviewAssets reviewAssets = object.measured()
                        ? null : FaintMoverReviewAssets.load(reportFolder, object.id(), assetPaths);
                var crops = new ArrayList<short[][][]>(objectCrops.size());
                for (int i = 0; i < objectCrops.size(); i++) {
                    crops.add(new short[][][]{objectCrops.get(i), fixed ? fixedCrops.get(i) : null});
                }
                return new ObjectPictures(List.copyOf(crops), new int[]{fx, fy}, fixed, reviewAssets);
            }

            @Override
            protected void process(List<LoadProgress> chunks) {
                if (closed || request != generation.get() || isCancelled() || chunks.isEmpty()) return;
                LoadProgress latest = chunks.getLast();
                loadProgressBar.setValue(latest.completed());
                loadProgressBar.setString(latest.completed() + " / " + latest.total() + " frames");
                loadFileLabel.setText(latest.fileName());
            }

            @Override
            protected void done() {
                if (closed || request != generation.get() || isCancelled()) return;
                try {
                    ObjectPictures loaded = get();
                    if (loaded == null) return;
                    pictureCache.put(object.id(), loaded);
                    applyObjectPictures(loaded);
                    pictureCards.show(pictureStage, CARD_PICTURE);
                } catch (Exception e) {
                    pictureCards.show(pictureStage, CARD_PICTURE);
                    picture.repaint();
                    pictureLabel.setText("The frames could not be read: " + message(e));
                }
            }
        };
        worker.execute();
    }

    /** Displays loaded (or cached) cutouts and optional faint-mover review assets for the current object. */
    private void applyObjectPictures(ObjectPictures loaded) {
        pictures = loaded.crops();
        shownReviewAssets = loaded.reviewAssets();
        picture.fixedOrigin = loaded.fixedOrigin();
        starCentred.setEnabled(loaded.fixedAvailable());
        if (!loaded.fixedAvailable()) objectCentred.setSelected(true);
        boolean hasEvidence = shownReviewAssets != null && shownReviewAssets.hasAny();
        showFaintEvidenceBox.setVisible(hasEvidence);
        updateResidualRangeVisibility();
        frameSlider.setMaximum(Math.max(0, pictures.size() - 1));
        frameSlider.setValue(0);
        updatePictureLabel();
        picture.repaint();
    }

    /** A copy of a rectangle of a frame; outside the frame is registration fill. */
    static short[][] crop(short[][] frame, int x, int y, int width, int height) {
        return LightCurve.crop(frame, x, y, width, height);
    }

    private StretchAlgorithm selectedStretchAlgorithm() {
        StretchAlgorithm algorithm = (StretchAlgorithm) stretchAlgorithmCombo.getSelectedItem();
        return algorithm == null ? StretchAlgorithm.ASINH : algorithm;
    }

    /** Configures the local stretch sliders for the selected algorithm (or restores its defaults when {@code reset}). */
    private void applyStretchAlgorithm(boolean reset) {
        StretchAlgorithm algorithm = selectedStretchAlgorithm();
        int[] saved = reset ? null : localStretchValues.get(algorithm);
        int primary = saved != null ? saved[0] : algorithm.getPrimaryDefault();
        int secondary = saved != null ? saved[1] : algorithm.getSecondaryDefault();
        adjustingStretch = true;
        try {
            stretchPrimarySlider.setMinimum(algorithm.getPrimaryMinimum());
            stretchPrimarySlider.setMaximum(algorithm.getPrimaryMaximum());
            stretchPrimarySlider.setValue(Math.max(algorithm.getPrimaryMinimum(), Math.min(algorithm.getPrimaryMaximum(), primary)));
            stretchSecondarySlider.setMinimum(algorithm.getSecondaryMinimum());
            stretchSecondarySlider.setMaximum(algorithm.getSecondaryMaximum());
            stretchSecondarySlider.setValue(Math.max(algorithm.getSecondaryMinimum(), Math.min(algorithm.getSecondaryMaximum(), secondary)));
            stretchPrimaryLabel.setText(algorithm.getPrimaryParameterLabel());
            stretchSecondaryLabel.setText(algorithm.getSecondaryParameterLabel());
        } finally {
            adjustingStretch = false;
        }
        localStretchValues.put(algorithm, new int[]{stretchPrimarySlider.getValue(), stretchSecondarySlider.getValue()});
        updateStretchValueLabels();
    }

    private void updateStretchControlStates() {
        boolean enabled = stretchEnabledBox.isSelected();
        stretchAlgorithmCombo.setEnabled(enabled);
        stretchPrimaryLabel.setEnabled(enabled);
        stretchPrimarySlider.setEnabled(enabled);
        stretchPrimaryValueLabel.setEnabled(enabled);
        stretchSecondaryLabel.setEnabled(enabled);
        stretchSecondarySlider.setEnabled(enabled);
        stretchSecondaryValueLabel.setEnabled(enabled);
        stretchResetButton.setEnabled(enabled);
    }

    private void updateStretchValueLabels() {
        StretchAlgorithm algorithm = selectedStretchAlgorithm();
        stretchPrimaryValueLabel.setText(algorithm == StretchAlgorithm.ASINH
                ? stretchPrimarySlider.getValue() + "%" : Integer.toString(stretchPrimarySlider.getValue()));
        stretchSecondaryValueLabel.setText(Integer.toString(stretchSecondarySlider.getValue()));
    }

    private void updateResidualRangeVisibility() {
        boolean visible = showFaintEvidenceBox.isVisible() && showFaintEvidenceBox.isSelected()
                && shownReviewAssets != null && shownReviewAssets.residuals() != null;
        residualRangeLabel.setVisible(visible);
        residualRangeSlider.setVisible(visible);
        residualRangeValueLabel.setVisible(visible);
    }

    /** Computes (or returns cached) per-frame lookup table for the current independent stretch controls. */
    private byte[] currentStretchLut(int fileIndex, short[][] fallbackCrop) {
        if (!stretchEnabledBox.isSelected()) {
            return DisplayStretch.linearLookupTable();
        }
        return frameLutCache.computeIfAbsent(fileIndex, idx -> {
            int[] histogram = idx >= 0 && idx < sessionFrameHistograms.length() ? sessionFrameHistograms.get(idx) : null;
            if (histogram == null) {
                histogram = DisplayStretch.validHistogram(fallbackCrop);
            }
            return DisplayStretch.lookupTable(histogram, selectedStretchAlgorithm(),
                    stretchPrimarySlider.getValue(), stretchSecondarySlider.getValue());
        });
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
        String maskNote = "";
        if (showFaintEvidenceBox.isVisible() && showFaintEvidenceBox.isSelected()
                && shownReviewAssets != null && shownReviewAssets.residuals() != null) {
            var resFrame = shownReviewAssets.residuals().frameForNumber(p.frameNumber());
            if (resFrame != null) {
                maskNote = resFrame.unmasked() ? " · centre unmasked" : " · centre masked";
            }
        }
        pictureLabel.setText(String.format(Locale.US, "%s · frame %d of the session · %s UTC · %d of %d · %s%s", shown.id(),
                p.frameNumber(), time > 0 ? UTC.format(Instant.ofEpochMilli(time)) : "time unknown", i + 1, pictures.size(),
                shown.measured() ? "measured centroid" : "fitted position", maskNote));
    }

    /** Applies a new selection rule to the shown object's observations. */
    private void select(java.util.function.Function<List<MpcObservation>, Set<Integer>> rule) {
        if (shown == null) return;
        Set<Integer> updated = new TreeSet<>(rule.apply(observations.get(shown.id())));
        selected.put(shown.id(), updated);
        measurementEntry(shown.id()).selectedIndices = new ArrayList<>(updated);
        saveMeasurements();
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
            var record = records.get(object.id());
            if (record != null && record.submittedUtc != null) {
                notes.add(object.id() + " of this night was marked submitted on " + record.submittedUtc + ": sending it again duplicates it.");
            }
            String designation = designation(object.id());
            if (designation != null) {
                // A designation from the MPC names the object on every night; each night is then sent on its own.
                var designated = MpcRecords.tracklet(designation, chosen);
                if (designated == null) {
                    problems.add(object.id() + ": " + MpcRecords.problem(designation));
                    continue;
                }
                notes.add(object.id() + " is reported as " + designation + ", its designation from the MPC"
                        + (others.isEmpty() ? "." : " (" + omittedNightsPhrase(others.size()) + ")."));
                tracklets.add(designated);
                continue;
            }
            var named = namedObject(object, others);
            if (named != object) {
                notes.add(object.id() + " is reported as " + named.names().getFirst()
                        + ", the identification of its linked night"
                        + (others.isEmpty() ? "." : " (" + omittedNightsPhrase(others.size()) + ")."));
            }
            var tracklet = MpcExport.tracklet(named, chosen, report.sessionStartMillis(), i);
            var unsent = others.stream().filter(o -> !o.submitted()).toList();
            boolean unidentified = tracklet.permID() == null && tracklet.provID() == null;
            if (!unidentified && named == object && !others.isEmpty()) {
                String label = tracklet.permID() != null ? tracklet.permID() : tracklet.provID();
                notes.add(object.id() + " is reported as " + label + " (" + omittedNightsPhrase(others.size()) + ").");
            }
            if (unidentified && !others.isEmpty() && thisNightOnly.contains(object.id())) {
                notes.add(object.id() + ": " + omittedNightsPhrase(others.size()) + ".");
            }
            for (var other : others) {
                if (unidentified && other.submitted() && !thisNightOnly.contains(object.id())) {
                    notes.add(object.id() + ": " + other.name() + " was marked submitted on " + other.record().submittedUtc
                            + " and is left out; the MPC links separately sent nights itself.");
                }
            }
            if (tracklet.permID() == null && tracklet.provID() == null && !unsent.isEmpty() && !thisNightOnly.contains(object.id())) {
                // The MPC: the nights of one object share a temporary designation only within one message.
                var all = new ArrayList<>(chosen);
                String trkSub = tracklet.trkSub();
                long first = report.sessionStartMillis();
                for (var other : unsent) {
                    all.addAll(SavedMeasurements.selectedOrDefault(other.observations(), other.saved()));
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
                notes.add(object.id() + ": " + (unsent.size() + 1) + " nights in one tracklet, " + trkSub
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
        else if (correctionChangedSinceOpening()) problems.add("The plate correction was fitted again after this window opened: "
                + "close and reopen the window, so the positions are reduced with the new one.");
        String currentCode = context.stationCode() == null ? "" : context.stationCode().trim().toUpperCase(Locale.ROOT);
        if (stationProblem != null && currentCode.equals(checkedStationCode)) {
            problems.add(stationProblem);
        } else if (!currentCode.isBlank() && site != null && !currentCode.equals(checkedStationCode)) {
            notes.add("Observatory code " + currentCode + " has not been checked against the observing site with JPL yet.");
        }
        problems.addAll(AdesPsv.problems(context, tracklets));
        problemsArea.setText(problems.isEmpty() ? "Ready: " + tracklets.size() + " object" + (tracklets.size() == 1 ? "" : "s")
                + " with " + tracklets.stream().mapToInt(t -> t.observations().size()).sum() + " observations."
                + (notes.isEmpty() ? "" : "\n" + String.join("\n", notes))
                : String.join("\n", problems));
        problemsArea.setCaretPosition(0);
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
                    String id = reply.submissionId();
                    String head = !reply.received() ? "The MPC's test service refused the request (HTTP " + reply.status() + "):\n"
                            : id != null ? "Received by the MPC's test service: submission ID " + id + ". This answer is the "
                            + "confirmation: the MPC documents no e-mail for test batches, which it checks but never processes "
                            + "(a real submission is acknowledged by e-mail). Its answer:\n"
                            : "The MPC's test service answered (HTTP " + reply.status() + ") without a submission ID, so the "
                            + "batch may not have been received:\n";
                    problemsArea.setText(head + plainText(reply.text()));
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
        if (stationWorker != null) stationWorker.cancel(true);
        String normalizedCode = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        stationLabel.setText("Asking JPL…");
        stationWorker = new SwingWorker<SolarSystemClient.Station, Void>() {
            @Override protected SolarSystemClient.Station doInBackground() throws Exception {
                return new SolarSystemClient().station(normalizedCode);
            }

            @Override protected void done() {
                if (stationWorker == this) stationWorker = null;
                if (closed || isCancelled()) return;
                try {
                    var station = get();
                    checkedStationCode = normalizedCode;
                    stationProblem = AdesPsv.stationProblem(station, site);
                    stationText = station.code() + " · " + station.name();
                    stationLabel.setHtml(escape(stationText) + (stationProblem == null
                            ? site == null ? " · no observing site to compare" : " · agrees with the observing site" : " · does not agree"));
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    // Keep an earlier disagreement for the same code until a later check actually agrees.
                    if (!normalizedCode.equals(checkedStationCode)) {
                        checkedStationCode = null;
                        stationProblem = null;
                    }
                    String detail = cause.getMessage();
                    if (stationProblem != null && normalizedCode.equals(checkedStationCode)) {
                        detail += " · earlier disagreement for " + normalizedCode + " still applies";
                    }
                    stationLabel.setHtml(escape(detail));
                }
                refreshExport();
            }
        };
        stationWorker.execute();
    }

    /**
     * Copies the MPChecker values of the shown object's middle selected observation (else its middle observation)
     * and opens MPChecker in the browser, where the user pastes them; nothing is sent from here. When no observatory
     * code is configured, asks whether to use {@code 500} (geocentric) or enter a valid MPC code explicitly.
     */
    private void openMpchecker() {
        if (shown == null) return;
        var list = observations.get(shown.id());
        if (list.isEmpty()) {
            problemsArea.setText("MPChecker needs a measured position: " + shown.id() + " has none.");
            return;
        }
        String stationCode = settings.getConfig().observatoryCode;
        if (!MpcExport.isValidStationCode(stationCode)) {
            stationCode = askMpcheckerStationCode();
            if (stationCode == null) return;
        }
        var chosen = new ArrayList<>(selected.get(shown.id()));
        int index = chosen.isEmpty() ? list.size() / 2 : chosen.get(chosen.size() / 2);
        String values = MpcExport.mpcheckerValues(list.get(index), stationCode);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(values), null);
        problemsArea.setText("Copied for MPChecker (" + shown.id() + ", frame " + list.get(index).frameNumber() + "):\n" + values);
        browse(MPCHECKER);
    }

    /**
     * Prompts for an observatory code when none is set in Astrometry Config: either explicit {@code 500} (geocentric)
     * or a user-entered 3- or 4-character MPC code. Returns null if cancelled.
     */
    private String askMpcheckerStationCode() {
        String message = "<html><body style='width:420px'>"
                + "No IAU observatory code is set in Astrometry Config. MPChecker requires a 3- or 4-character MPC "
                + "observatory code (it does not accept latitude/longitude directly).<br><br>"
                + "<b>Code 500 (Geocentric)</b> calculates positions from the Earth's centre: usually accurate within "
                + "MPChecker's search radius for main-belt asteroids, but parallax can shift close Near-Earth Objects "
                + "by arcminutes.<br><br>"
                + "<i>Tip: Compare again / Identify all in SpacePixels already uses your exact site coordinates via JPL Horizons.</i>"
                + "</body></html>";
        Object[] options = {"Use 500 (Geocentric)", "Enter code…", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, message, "Observatory code for MPChecker",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 0) return "500";
        if (choice != 1) return null;
        String entered = "";
        while (true) {
            Object reply = JOptionPane.showInputDialog(this,
                    "Enter a 3- or 4-character MPC observatory code (yours or a nearby station):",
                    "Enter MPC observatory code", JOptionPane.PLAIN_MESSAGE, null, null, entered);
            if (reply == null) return null;
            entered = reply.toString().trim().toUpperCase(Locale.ROOT);
            if (MpcExport.isValidStationCode(entered)) return entered;
            JOptionPane.showMessageDialog(this,
                    "An MPC observatory code must be 3 or 4 letters/digits (for example W84 or 500).",
                    "Invalid observatory code", JOptionPane.WARNING_MESSAGE);
        }
    }

    /** Whether the Orbit solver selector is set to SpacePixels' built-in solver (rather than external Find_Orb). */
    private boolean useBuiltInOrbitSolver() {
        return orbitSolverCombo.getSelectedIndex() == 0;
    }

    /**
     * Resolves the current session's observing site: FITS header coordinates first, else the live configured site in
     * Astrometry Config, else null (geocentric). Used by the built-in solver so an IAU observatory code is not needed.
     */
    private ObservingSite resolvedSite() {
        if (site != null && ObservingSite.FROM_HEADER.equals(site.source())) return site;
        AppConfig config = settings.getConfig();
        ObservingSite live = ObservingSite.resolve(files, config.siteLat, config.siteLong, config.siteAltitude);
        return live != null ? live : site;
    }

    /** Human-readable provenance label for an observing site (never exposing raw coordinates). */
    private static String siteSourceLabel(ObservingSite resolved) {
        if (resolved == null) return "geocentric (no site coordinates set)";
        return ObservingSite.FROM_HEADER.equals(resolved.source()) ? "FITS header" : "Astrometry Config";
    }

    /** Summarises the site-coordinate sources across one or more linked nights for the built-in orbit summary. */
    private static String combinedSiteSourceLabel(ObservingSite primarySite, List<OtherNight> others) {
        boolean anyHeader = primarySite != null && ObservingSite.FROM_HEADER.equals(primarySite.source());
        boolean anyConfig = primarySite != null && !ObservingSite.FROM_HEADER.equals(primarySite.source());
        boolean anyGeocentric = primarySite == null;
        boolean multiLocation = false;
        for (var other : others) {
            if (other.observations().isEmpty()) continue;
            ObservingSite s = other.night().site() != null ? other.night().site() : primarySite;
            if (s == null) anyGeocentric = true;
            else if (ObservingSite.FROM_HEADER.equals(s.source())) anyHeader = true;
            else anyConfig = true;
            if (!NightLinks.sameSite(primarySite, s)) multiLocation = true;
        }
        String src = anyHeader && !anyConfig && !anyGeocentric ? "FITS headers"
                : !anyHeader && anyConfig && !anyGeocentric ? "Astrometry Config"
                : !anyHeader && !anyConfig ? "geocentric (no site coordinates)"
                : "FITS headers & Astrometry Config";
        return multiLocation ? src + " (multi-site)" : src;
    }

    /**
     * Fits a preliminary orbit to the shown object's selected observations (plus linked nights) using either
     * SpacePixels' built-in clean-room solver (which uses each night's FITS-header or configured coordinates with no
     * observatory code required) or external Find_Orb (running locally when configured, else copying observations and
     * opening the online Find_Orb web page).
     */
    private void openFindOrb() {
        if (shown == null) return;
        var chosen = chosenObservations(shown);
        var others = nightsOf(shown.id());
        var object = shown;

        if (useBuiltInOrbitSolver()) {
            ObservingSite thisSite = resolvedSite();
            var allSited = new ArrayList<OrbitDetermination.SitedObservation>();
            for (var o : chosen) allSited.add(new OrbitDetermination.SitedObservation(o, thisSite));
            int nights = 1;
            for (var other : others) {
                var picked = SavedMeasurements.selectedOrDefault(other.observations(), other.saved());
                if (picked.isEmpty()) continue;
                nights++;
                ObservingSite nightSite = other.night().site() != null ? other.night().site() : thisSite;
                for (var o : picked) allSited.add(new OrbitDetermination.SitedObservation(o, nightSite));
            }
            if (allSited.size() < 3) {
                orbitLabel.setHtml(escape("An orbit needs at least three selected observations of " + shown.id()
                        + (shown.measured() ? "." : " (wait for sub-stack measurement to finish).")));
                return;
            }
            final int totalNights = nights;
            final String siteNote = totalNights > 1 ? combinedSiteSourceLabel(thisSite, others) : siteSourceLabel(thisSite);
            List<OrbitDetermination.SitedObservation> allCopy = List.copyOf(allSited);
            List<MpcObservation> singleCopy = totalNights > 1 && chosen.size() >= 3 ? List.copyOf(chosen) : null;
            if (findOrbWorker != null) findOrbWorker.cancel(true);
            orbitLabel.setText("Fitting " + object.id() + (totalNights > 1 ? " on " + totalNights + " nights" : "") + " with built-in solver…");
            findOrbWorker = new SwingWorker<OrbitDetermination.Solution[], Void>() {
                @Override protected OrbitDetermination.Solution[] doInBackground() {
                    OrbitDetermination.Solution fit = OrbitDetermination.fitSited(allCopy);
                    OrbitDetermination.Solution alone = null;
                    if (singleCopy != null && !isCancelled()) {
                        try {
                            alone = OrbitDetermination.fit(singleCopy, thisSite);
                        } catch (IllegalArgumentException noSingleNightOrbit) {
                            // The comparison is optional: the orbit of all nights stands without it.
                        }
                    }
                    return new OrbitDetermination.Solution[]{fit, alone};
                }

                @Override protected void done() {
                    if (findOrbWorker == this) findOrbWorker = null;
                    if (closed || isCancelled()) return;
                    try {
                        var fits = get();
                        orbits.put(object.id(), fits[0].orbit());
                        orbitSamples.put(object.id(), fits[0].sampleOrbits());
                        orbitSiteSources.put(object.id(), siteNote);
                        orbitNights.put(object.id(), totalNights);
                        if (fits[1] == null) singleNightOrbits.remove(object.id()); else singleNightOrbits.put(object.id(), fits[1].orbit());
                        orbitProblems.remove(object.id());
                    } catch (Exception e) {
                        Throwable cause = e.getCause() == null ? e : e.getCause();
                        orbits.remove(object.id());
                        orbitSamples.remove(object.id());
                        orbitSiteSources.remove(object.id());
                        singleNightOrbits.remove(object.id());
                        orbitProblems.put(object.id(), cause.getMessage());
                    }
                    if (shown == object) {
                        updateOrbit();
                        if (orbits.containsKey(object.id())) showOrbitWindow();
                    }
                }
            };
            findOrbWorker.execute();
            return;
        }

        String siteIssue = others.isEmpty() ? null : siteProblem(shown.id());
        var all = new ArrayList<>(chosen);
        if (siteIssue == null) for (var other : others) all.addAll(SavedMeasurements.selectedOrDefault(other.observations(), other.saved()));
        all.sort(java.util.Comparator.comparingLong(MpcObservation::midpointMillis));
        int nights = siteIssue == null ? 1 + (int) others.stream().filter(o -> !o.observations().isEmpty()).count() : 1;
        if (all.size() < 3) {
            orbitLabel.setHtml(escape("An orbit needs at least three selected observations of " + shown.id()
                    + (shown.measured() ? "." : " (wait for sub-stack measurement to finish).")));
            return;
        }
        if (siteIssue != null) problemsArea.setText(siteIssue + " The orbit uses this night only.");

        AppConfig details = settings.getConfig().copy();
        readDetails(details);
        AdesPsv.Context context = MpcExport.context(details);
        if (context.stationCode().isBlank()) {
            orbitLabel.setHtml(escape("Set the IAU observatory code in Astrometry Config first: Find_Orb needs the station."));
            return;
        }
        int sequence = report.objects().indexOf(shown);
        String text = AdesPsv.writeObservations(context, List.of(MpcExport.tracklet(shown, all, report.sessionStartMillis(), sequence)));
        // With other nights, this night alone is fitted too, to show what they add.
        String single = nights > 1 && chosen.size() >= 3
                ? AdesPsv.writeObservations(context, List.of(MpcExport.tracklet(shown, chosen, report.sessionStartMillis(), sequence))) : null;
        File program = details.findOrbExecutable == null || details.findOrbExecutable.isBlank() ? null : new File(details.findOrbExecutable);
        if (program == null || !program.isFile()) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
            String browserError = browseWithProblem(FIND_ORB);
            String note = "Copied " + all.size() + " observation" + (all.size() == 1 ? "" : "s") + " of " + shown.id()
                    + (nights > 1 ? " from " + nights + " nights" : "") + " to the clipboard";
            if (siteIssue != null) note = siteIssue + " (using this night only). " + note;
            orbitLabel.setHtml(escape(browserError == null
                    ? note + " and opened the online Find_Orb web page: paste them into its box and click Compute."
                    : note + ", but the browser could not be opened (" + browserError + "). Open " + FIND_ORB + " and paste them."));
            return;
        }
        var times = all.stream().map(o -> Instant.ofEpochMilli(o.midpointMillis()).toString()).toList();
        var singleTimes = chosen.stream().map(o -> Instant.ofEpochMilli(o.midpointMillis()).toString()).toList();
        if (findOrbWorker != null) findOrbWorker.cancel(true);
        orbitLabel.setText("Fitting " + object.id() + (nights > 1 ? " on " + nights + " nights" : "") + " with Find_Orb…");
        findOrbWorker = new SwingWorker<FindOrb.Orbit[], Void>() {
            @Override protected FindOrb.Orbit[] doInBackground() throws Exception {
                FindOrb.Orbit fit = FindOrb.fit(program, text, times);
                FindOrb.Orbit alone = null;
                if (single != null && !isCancelled()) {
                    try {
                        alone = FindOrb.fit(program, single, singleTimes);
                    } catch (java.io.IOException noSingleNightOrbit) {
                        // The comparison is optional: the orbit of all nights stands without it.
                    }
                }
                return new FindOrb.Orbit[]{fit, alone};
            }

            @Override protected void done() {
                if (findOrbWorker == this) findOrbWorker = null;
                if (closed || isCancelled()) return;
                try {
                    var fits = get();
                    orbits.put(object.id(), fits[0]);
                    orbitSamples.remove(object.id());
                    orbitSiteSources.put(object.id(), "station " + context.stationCode().trim());
                    orbitNights.put(object.id(), nights);
                    if (fits[1] == null) singleNightOrbits.remove(object.id()); else singleNightOrbits.put(object.id(), fits[1]);
                    orbitProblems.remove(object.id());
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    orbits.remove(object.id());
                    orbitSamples.remove(object.id());
                    orbitSiteSources.remove(object.id());
                    singleNightOrbits.remove(object.id());
                    orbitProblems.put(object.id(), cause.getMessage());
                }
                if (shown == object) {
                    updateOrbit();
                    if (orbits.containsKey(object.id())) showOrbitWindow();
                }
            }
        };
        findOrbWorker.execute();
    }

    /** A fit belongs to the observations it was made from: a changed selection drops it. */
    private void forgetOrbit() {
        if (shown == null) return;
        forgetOrbit(shown.id());
        updateOrbit();
    }

    private void forgetOrbit(String id) {
        orbits.remove(id);
        orbitSamples.remove(id);
        orbitSiteSources.remove(id);
        singleNightOrbits.remove(id);
        orbitNights.remove(id);
        orbitProblems.remove(id);
    }

    /** Lets the user point to fo64.exe / fo32.exe (Windows) or fo; kept in the settings with the other details. */
    private void chooseFindOrb() {
        String existing = settings.getConfig().findOrbExecutable;
        File startDir = existing == null || existing.isBlank() ? null : new File(existing).getParentFile();
        JFileChooser chooser = WindowSizing.fileChooser(startDir != null && startDir.isDirectory() ? startDir : null);
        chooser.setDialogTitle("Choose Find_Orb executable (fo64.exe / fo32.exe on Windows, fo on Linux/macOS)");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        settings.getConfig().findOrbExecutable = chooser.getSelectedFile().getAbsolutePath();
        orbitSolverCombo.setSelectedIndex(1);
        saveDetails();
        updateOrbit();
    }

    /** Shows the configured local Find_Orb executable path, or "Not set" when unconfigured. */
    private void updateFindOrbPath() {
        String program = settings.getConfig().findOrbExecutable;
        if (program == null || program.isBlank()) {
            findOrbPathLabel.setForeground(UiStyle.helpColor());
            findOrbPathLabel.setToolTipText(null);
            findOrbPathLabel.setHtml("Program: Not set");
            return;
        }
        boolean exists = new File(program).isFile();
        Color warning = UIManager.getColor("Actions.Yellow");
        findOrbPathLabel.setForeground(exists || warning == null ? UiStyle.helpColor() : warning);
        findOrbPathLabel.setToolTipText(program);
        findOrbPathLabel.setHtml("Program: " + escape(program) + (exists ? "" : " (not found)"));
    }

    /** Shows a summary of the shown object's fitted orbit, why it could not be fitted, or how to fit one. */
    private void updateOrbit() {
        updateFindOrbPath();
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
            if (problem != null) {
                orbitLabel.setHtml(escape(problem));
                return;
            }
            if (useBuiltInOrbitSolver()) {
                orbitLabel.setHtml(escape("Compute orbit fits the selected observations of " + shown.id()
                        + " with SpacePixels' built-in solver (no observatory code needed; location: "
                        + siteSourceLabel(resolvedSite()) + ")."));
                return;
            }
            String program = settings.getConfig().findOrbExecutable;
            boolean configured = program != null && !program.isBlank() && new File(program).isFile();
            orbitLabel.setHtml(escape(!configured
                    ? "Local Find_Orb is not set: Compute orbit copies at least 3 selected observations to the clipboard "
                    + "and opens the online Find_Orb web page so you can paste them."
                    : "Compute orbit fits the selected observations of " + shown.id() + " with local Find_Orb."));
            return;
        }
        int nights = orbitNights.getOrDefault(shown.id(), 1);
        var alone = singleNightOrbits.get(shown.id());
        String solver = OrbitDetermination.SOLVER_TAG.equals(orbit.version()) ? "built-in" : "Find_Orb";
        orbitLabel.setHtml(String.format(Locale.US, "<b>Preliminary orbit (%s%s)</b> · a %s AU · e %s · i %s° · residual RMS %s.%s",
                solver, nights > 1 ? ", " + nights + " nights" : "",
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
    private String orbitText(FindOrb.Orbit orbit, FindOrb.Orbit alone, int nights, String siteSource) {
        long used = orbit.residuals().stream().filter(FindOrb.Residual::used).count();
        double span = spanHours(orbit);
        String arc = span >= 48 ? String.format(Locale.US, "%.1f days", span / 24) : String.format(Locale.US, "%.1f h", span);
        double periodDays = OrbitGeometry.orbitalPeriodDays(orbit);
        String periodText = !Double.isFinite(periodDays) ? "unbound"
                : periodDays >= 730 ? String.format(Locale.US, "%.2f yr", periodDays / 365.25)
                : String.format(Locale.US, "%.1f d", periodDays);
        String aphelionText = orbit.bound() ? String.format(Locale.US, " · Q %.3f AU", orbit.a() * (1 + orbit.e())) : "";
        String solver = OrbitDetermination.SOLVER_TAG.equals(orbit.version()) ? "built-in" : "Find_Orb";
        String locationLine = siteSource == null || siteSource.isBlank() ? "" : "<br>Location: " + escape(siteSource);
        String comparison = alone == null ? "" : "<br><br><b>This night alone</b> (dashed violet) · " + elements(alone)
                + String.format(Locale.US, "<br>%d observations over %.1f h. The ± values show what one night leaves open; a few nights still leave much of the orbit open.",
                alone.residuals().size(), spanHours(alone));
        return String.format(Locale.US, "<b>Preliminary (%s)</b> · %s · %d of %d observations used%s over %s"
                        + " · epoch %s%s<br>a %s AU · e %s · i %s°<br>q %s AU%s · P %s<br>node %s° · peri %s°"
                        + "<br>Residual RMS %s · P(NEO) %s · Earth MOID %s"
                        + "<br>An orbit from a short arc is uncertain (±1σ shown); a good fit does not prove the object is real.%s",
                solver, OrbitGeometry.orbitClass(orbit), used, orbit.residuals().size(), nights > 1 ? " from " + nights + " nights" : "", arc,
                orbit.epochIso() == null ? "?" : orbit.epochIso().substring(0, 10), locationLine,
                withSigma(orbit.a(), orbit.aSigma(), 3), withSigma(orbit.e(), orbit.eSigma(), 3), withSigma(orbit.i(), orbit.iSigma(), 1),
                withSigma(orbit.q(), orbit.qSigma(), 3), aphelionText, periodText,
                withSigma(orbit.ascendingNode(), orbit.nodeSigma(), 1), withSigma(orbit.argumentOfPerihelion(), orbit.perihelionSigma(), 1),
                orbit.rmsArcsec() == null ? "?" : String.format(Locale.US, "%.2f″", orbit.rmsArcsec()),
                orbit.neoProbability() == null ? "?" : String.format(Locale.US, "%.0f%%", orbit.neoProbability()),
                orbit.earthMoid() == null ? "?" : String.format(Locale.US, "%.3f&nbsp;AU", orbit.earthMoid()), comparison);
    }

    /**
     * Opens, or brings forward, the interactive 3D orbit viewer for the shown object, with its elements, zoom,
     * animation in time, and Save picture. It follows the shown object and closes when its orbit is dropped.
     */
    private void showOrbitWindow() {
        var orbit = shown == null ? null : orbits.get(shown.id());
        if (orbit == null) return;
        if (orbitWindow == null) {
            orbitWindow = new OrbitViewerDialog(this, report.folder(),
                    (action, done) -> writeOnWorker(action::write, done),
                    message -> orbitLabel.setHtml(escape(message)));
            orbitWindow.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent e) {
                    if (e.getWindow() == orbitWindow) orbitWindow = null;
                }
            });
        }
        fillOrbitWindow(orbitWindow, shown.id(), orbit);
        orbitWindow.setVisible(true);
        orbitWindow.toFront();
    }

    /** Updates the open 3D orbit viewer in place with one object's fitted orbit(s) and elements. */
    private void fillOrbitWindow(OrbitViewerDialog window, String id, FindOrb.Orbit orbit) {
        var alone = singleNightOrbits.get(id);
        var samples = orbitSamples.getOrDefault(id, List.of());
        int nights = orbitNights.getOrDefault(id, 1);
        String label = id;
        if (shown != null && id.equals(shown.id()) && !shown.names().isEmpty()) {
            label = id + " (" + shown.names().getFirst() + ")";
        }
        window.setOrbit(id, label, orbit, alone, samples, nights, orbitText(orbit, alone, nights, orbitSiteSources.get(id)));
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
        String problem = browseWithProblem(address);
        if (problem != null) {
            problemsArea.setText("The browser could not be opened: " + problem + "\n" + address);
        }
    }

    /** Opens an address in the user's browser and returns null on success, else the reason it could not open. */
    private static String browseWithProblem(String address) {
        if (!Desktop.isDesktopSupported()) return "desktop browser launch is unavailable";
        try {
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) return "browser action is not supported";
            desktop.browse(java.net.URI.create(address));
            return null;
        } catch (Exception e) {
            return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
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
        String suffix = " (compared again with the predictions fetched " + (objects.fetchedAtUtc == null ? "later" : objects.fetchedAtUtc.substring(0, 10)) + ")";
        PredictionUpdate update = applyPredictions(objects, report.objects(), "Compare again", suffix, false);
        report = report.withObjects(update.replaced());
        if (!update.changes().isEmpty()) saveLater();
        String head = String.format(Locale.US, "Compared with the saved predictions to V %.1f (%d objects): ", objects.magnitudeLimit, objects.bodies.size());
        if (update.changes().isEmpty()) {
            showComparison(head + "no identification changed.", null);
        } else {
            showComparison(head + (update.changes().size() <= 2 ? String.join("; ", update.changes()) + "." : update.changes().size() + " changed.")
                    + " Saved.", head + "\n\n" + String.join("\n", update.changes()));
        }
        refreshObjects();
        show(shown == null ? null : report.objects().stream().filter(o -> o.id().equals(shown.id())).findFirst().orElse(null));
        refreshExport();
    }

    private record PredictionUpdate(List<ReportedObjects.ReportedObject> replaced, List<String> changes) { }

    /**
     * Compares every finding against {@code objects} using {@link eu.startales.spacepixels.report.PredictionMatch}.
     * When {@code onlyPromoteUnidentified} is true, existing {@code KNOWN} findings and non-matches are preserved so
     * caller-supplied partial ephemeris sets (such as from Identify all) never downgrade earlier detections.
     */
    private PredictionUpdate applyPredictions(eu.startales.spacepixels.solarsystem.SolarSystemObjects objects,
                                              List<ReportedObjects.ReportedObject> sourceObjects,
                                              String rememberSource, String noteSuffix,
                                              boolean onlyPromoteUnidentified) {
        if (onlyPromoteUnidentified && (objects == null || objects.bodies == null || objects.bodies.isEmpty())) {
            return new PredictionUpdate(new ArrayList<>(sourceObjects), List.of());
        }
        double diagonal = report.frames().isEmpty() ? 0 : Math.hypot(report.frames().getFirst().width(), report.frames().getFirst().height());
        double floor = report.reviewFloorPixels();
        double fwhm = report.faintMoverFwhm() == null ? Double.NaN : report.faintMoverFwhm();
        WcsCoordinateTransformer solution = sky();
        var replaced = new ArrayList<ReportedObjects.ReportedObject>();
        var changes = new ArrayList<String>();
        for (var object : sourceObjects) {
            if (onlyPromoteUnidentified && ReportedObjects.KNOWN.equals(object.status())) {
                replaced.add(object);
                continue;
            }
            var points = new ArrayList<eu.startales.spacepixels.report.PredictionMatch.Point>();
            for (var sample : LightCurve.samples(report, object)) {
                points.add(new eu.startales.spacepixels.report.PredictionMatch.Point(sample.frameNumber(), sample.midpointMillis(), sample.x(), sample.y()));
            }
            var motion = object.motion();
            var result = object.measured()
                    ? eu.startales.spacepixels.report.PredictionMatch.track(points, objects, solution, floor, diagonal)
                    : motion == null ? null : eu.startales.spacepixels.report.PredictionMatch.faintMover(motion.x(), motion.y(),
                    motion.referenceMillis(), motion.vxPerMinute(), motion.vyPerMinute(), points, objects, solution, fwhm, diagonal);
            if (result == null
                    || (onlyPromoteUnidentified && ReportedObjects.NO_KNOWN_MATCH.equals(result.status()))
                    || (result.status().equals(object.status()) && result.names().equals(object.names()))) {
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
                    result.note() + noteSuffix,
                    object.positions(), object.speedArcsecPerHour(), object.positionAngleDegrees(), object.motion());
            replaced.add(updated);
            exported.put(object.id(), MpcExport.includedByDefault(updated));
            remember(updated, rememberSource);
            changes.add(object.id() + " " + statusText(object.status()) + " → " + statusText(status)
                    + (result.names().isEmpty() ? "" : " " + String.join(" or ", result.names())));
        }
        return new PredictionUpdate(replaced, changes);
    }

    /**
     * Identify all: the predictions of every known object near the findings, without a magnitude limit, fetched as the
     * main window fetches the field (the same searches, observer and Horizons positions:
     * {@link SolarSystemClient#fetchNear}), then the same match as the report and Compare again. The objects found are
     * added to the session's predictions, so the report, the viewers and Compare again know them too; without saved
     * predictions they are used in this window only. A finding that no prediction confirms becomes Possible when a known
     * object lies within 1′ of it with agreeing motion.
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
        // The fetch's field search is at the middle of the span: the findings' places are taken there too.
        long epoch = first + (Math.max(first, last) - first) / 2;
        eu.startales.spacepixels.solarsystem.SolarSystemQuery.Observer observer;
        try {
            observer = eu.startales.spacepixels.solarsystem.SolarSystemQuery.observer(files, settings.getConfig());
        } catch (IllegalArgumentException noSite) {
            compareLabel.setHtml(escape(noSite.getMessage()));
            return;
        }
        var query = new eu.startales.spacepixels.solarsystem.SolarSystemQuery(header,
                report.frames().getFirst().width(), report.frames().getFirst().height(),
                first, Math.max(first, last), Double.POSITIVE_INFINITY, observer);
        WcsCoordinateTransformer solution = sky();
        var places = new java.util.LinkedHashMap<String, eu.startales.spacepixels.mpc.NearbyKnown.Place>();
        for (var object : report.objects()) {
            var place = eu.startales.spacepixels.mpc.NearbyKnown.place(LightCurve.samples(report, object), object.motion(), epoch, solution);
            if (place != null) places.put(object.id(), place);
        }
        if (places.isEmpty()) {
            compareLabel.setHtml("No object has a place on the sky to search around.");
            return;
        }
        List<double[]> around = places.values().stream().map(p -> new double[]{p.ra(), p.dec()}).toList();
        identifyAllButton.setText("Cancel Identify all");
        compareLabel.setText("Asking JPL and SkyBoT for the known objects near the findings…");
        identifyWorker = new SwingWorker<eu.startales.spacepixels.solarsystem.SolarSystemObjects, String>() {
            @Override protected eu.startales.spacepixels.solarsystem.SolarSystemObjects doInBackground() throws Exception {
                return new SolarSystemClient().fetchNear(query, around, eu.startales.spacepixels.mpc.NearbyKnown.RADIUS_ARCSEC,
                        text -> publish(text));
            }

            @Override protected void process(List<String> stages) {
                if (!closed && identifyWorker == this && !stages.isEmpty()) {
                    compareLabel.setText("Identify all: " + stages.getLast() + "…");
                }
            }

            @Override protected void done() {
                if (identifyWorker == this) identifyWorker = null;
                if (closed) return;
                identifyAllButton.setText("Identify all…");
                if (isCancelled()) {
                    compareLabel.setText("Identify all was cancelled.");
                    return;
                }
                eu.startales.spacepixels.solarsystem.SolarSystemObjects fetched;
                try {
                    fetched = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    compareLabel.setHtml("Identify all could not ask the services: " + escape(String.valueOf(cause.getMessage())));
                    return;
                }
                applyIdentifyOutcome(new IdentifyOutcome(fetched, places, epoch));
            }
        };
        identifyWorker.execute();
    }

    /**
     * What Identify all fetched (the predictions of the objects near the findings), the findings' places on the sky at
     * the middle of the span by object, and that time.
     */
    record IdentifyOutcome(eu.startales.spacepixels.solarsystem.SolarSystemObjects fetched,
                           Map<String, eu.startales.spacepixels.mpc.NearbyKnown.Place> places, long epochMillis) { }

    /** The plate solution for places on the sky now: the header's, with the session's current correction while it is on. */
    private WcsCoordinateTransformer sky() {
        return sessionSolution(header, correction.get(), files);
    }

    /** Whether the session's correction was fitted again since this window opened, so its MPC reduction is out of date. */
    private boolean correctionChangedSinceOpening() {
        PlateSolutionCorrection now = correction.get();
        return now != openedCorrection && (now == null || openedCorrection == null
                || !java.util.Objects.equals(now.fittedAtUtc, openedCorrection.fittedAtUtc));
    }

    /**
     * The header solution with the session's plate correction, for the session as a whole, while the correction is
     * switched on (as {@link eu.startales.spacepixels.fits.WcsSolutionResolver} gives it to the report and the
     * viewers); the header solution itself without one, or for a correction fitted to another solution or frame size.
     */
    static WcsCoordinateTransformer sessionSolution(WcsCoordinateTransformer header, PlateSolutionCorrection correction,
                                                    FitsFileInformation[] files) {
        if (header == null || correction == null || !correction.enabled || files.length == 0 || files[0] == null) return header;
        return correction.apply(header, null, files[0].getSizeWidth(), files[0].getSizeHeight());
    }

    /**
     * Applies what Identify all fetched: adds the objects to the session's predictions (when there are saved ones),
     * matches every finding as the report and Compare again do, and marks Possible the unconfirmed findings with a known
     * object within 1′ and agreeing motion. EDT only.
     */
    void applyIdentifyOutcome(IdentifyOutcome outcome) {
        var fetched = outcome.fetched();
        if (fetched == null || fetched.source == null || fetched.source.isEmpty()) {
            String why = fetched == null || fetched.problems == null || fetched.problems.isEmpty() ? "JPL did not answer."
                    : String.join("; ", fetched.problems);
            compareLabel.setHtml("Identify all could not ask the services: " + escape(why));
            return;
        }
        var saved = predictions.get();
        boolean merged = saved != null && saved.bodies != null;
        var used = merged ? saved.withAdded(fetched, "Identify all") : fetched;
        int added = merged ? used.bodies.size() - saved.bodies.size() : 0;
        if (added > 0 && savePredictions != null) savePredictions.accept(used);

        var known = eu.startales.spacepixels.mpc.NearbyKnown.at(fetched.bodies, outcome.epochMillis());
        var rawNearby = new java.util.LinkedHashMap<String, List<eu.startales.spacepixels.mpc.NearbyKnown.Match>>();
        for (var object : report.objects()) {
            rawNearby.put(object.id(), eu.startales.spacepixels.mpc.NearbyKnown.near(outcome.places().get(object.id()), known));
        }
        nearby.clear();
        nearby.putAll(eu.startales.spacepixels.mpc.NearbyKnown.noteNearer(rawNearby));
        int empty = 0;
        int alreadyKnownAgree = 0;
        for (var object : report.objects()) {
            var agreeing = nearby.getOrDefault(object.id(), List.of()).stream()
                    .filter(eu.startales.spacepixels.mpc.NearbyKnown.Match::motionAgrees).toList();
            if (agreeing.isEmpty()) empty++;
            else if (ReportedObjects.KNOWN.equals(object.status())) alreadyKnownAgree++;
        }
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        String suffix = " (Identify all, " + today + ", JPL and SkyBoT without a magnitude limit)";
        // With the session's predictions this is Compare again with the objects added; without them only promotions count.
        PredictionUpdate confirmed = applyPredictions(used, report.objects(), "Identify all", suffix, !merged);
        var beforePossible = confirmed.replaced();
        var marked = eu.startales.spacepixels.mpc.NearbyKnown.markPossible(beforePossible, nearby,
                today, AsteroidsCometsWindow::nearbyText);
        var possible = new ArrayList<String>();
        for (int k = 0; k < marked.size(); k++) {
            if (marked.get(k) == beforePossible.get(k)) continue;
            exported.put(marked.get(k).id(), MpcExport.includedByDefault(marked.get(k)));
            remember(marked.get(k), "Identify all");
            possible.add(marked.get(k).id());
        }
        report = report.withObjects(marked);
        refreshObjects();
        refreshExport();
        var problems = fetched.problems == null ? List.<String>of() : fetched.problems;
        boolean changed = !confirmed.changes().isEmpty() || !possible.isEmpty();
        if (changed) saveLater();
        // A short summary: each object's matches are in its identification.
        String summary = String.format(Locale.US, "Identify all (%d known object%s near the findings%s): ", fetched.bodies.size(),
                fetched.bodies.size() == 1 ? "" : "s", problems.isEmpty() ? "" : "; " + String.join("; ", problems))
                + (confirmed.changes().isEmpty() ? "" : String.join("; ", confirmed.changes()) + "; ")
                + (possible.isEmpty() ? "" : "Possible " + String.join(", ", possible) + "; ")
                + (alreadyKnownAgree > 0 ? alreadyKnownAgree + " known agree; " : "")
                + empty + " with none within 1′."
                + (added > 0 ? String.format(Locale.US, " %d added to the session's predictions.", added) : "")
                + (merged ? "" : " Fetch Asteroids and Comets in the main window to keep predictions of the whole field;"
                + " these are used in this window only.");
        var details = new StringBuilder(summary);
        if (!confirmed.changes().isEmpty()) {
            details.append("\n\nConfirmed by ephemeris comparison:\n").append(String.join("\n", confirmed.changes()));
        }
        details.append("\n\nKnown objects within 1′ of each object at mid-session (JPL Horizons positions):\n");
        for (var object : report.objects()) {
            var near = nearby.getOrDefault(object.id(), List.of());
            details.append("\n").append(object.id()).append(" (").append(statusText(object.status())).append("): ")
                    .append(near.isEmpty() ? "none" : String.join("; ", near.stream().map(AsteroidsCometsWindow::nearbyText).toList()));
        }
        showComparison(summary + (changed ? " Saved." : ""), details.toString());
        if (shown != null) show(report.objects().stream().filter(o -> o.id().equals(shown.id())).findFirst().orElse(null));
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

    /** Shows the full result of the last Compare again, Identify all or MPC ITF check. */
    private void showComparisonDetails() {
        if (comparisonDetails == null) return;
        JTextArea text = new JTextArea(comparisonDetails, Math.min(24, comparisonDetails.split("\n").length + 1), 70);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text), "Identification details", JOptionPane.PLAIN_MESSAGE);
    }

    /** Explains how Offset and Motion are calculated in the Nearby &amp; MPC ITF matches table, and their uncertainties. */
    private void showNearbyMatchesInfo() {
        String explanation = nearbyMatchesExplanationText();
        JTextArea text = new JTextArea(explanation, 22, 68);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(text),
                "Nearby & MPC ITF matches — Offset & Motion", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Plain-language explanation of how Offset, Motion and uncertainties work for Identify all and MPC ITF matches. */
    static String nearbyMatchesExplanationText() {
        int nearbyRatePct = (int) Math.round(eu.startales.spacepixels.mpc.NearbyKnown.RATE_FRACTION * 100);
        int itfSamePct = (int) Math.round(MpcItfCatalog.SAME_NIGHT_RATE_FRACTION * 100);
        int itfNearPct = (int) Math.round(MpcItfCatalog.NEARBY_RATE_FRACTION * 100);
        return String.format(Locale.US,
                "HOW OFFSET AND MOTION ARE CALCULATED\n\n"
                + "1. Identify all (JPL SB-Ident, IMCCE SkyBoT and JPL Horizons, as Fetch Asteroids and Comets)\n"
                + "• Known objects: the field search finds every known object near the findings, without a magnitude "
                + "limit, and JPL Horizons gives each its positions over the session for the observing site, as the main "
                + "window's fetch does. Each finding is then matched as the report and Compare again match it: Known "
                + "when it follows one prediction in every frame within the ephemeris uncertainty. The objects found are "
                + "added to the session's predictions.\n"
                + "• Finding position & motion: a track's measured pixel positions are fitted with a straight line "
                + "against frame midpoint times (a faint mover uses its fitted shift-and-stack motion), evaluated at "
                + "mid-session UTC and projected to sky coordinates (RA, Dec) and sky velocity (dRA·cos Dec, dDec) "
                + "through the session's plate solution.\n"
                + "• Offset (″): angular separation on the sky at mid-session between the finding and the known object's "
                + "JPL Horizons position. Known objects within %.0f″ (1′) are listed, nearest first; one with "
                + "agreeing motion makes an unconfirmed finding Possible.\n"
                + "• Motion (agreeing / differing, Δv): magnitude of the 2D sky-velocity vector difference between the "
                + "finding and the known object (combining both speed and direction). Motion is marked agreeing when "
                + "Δv ≤ max(%.0f″/h, %d%% of the known object's speed). If another finding in this report lies closer "
                + "to the same known object, that is noted (two findings near one object are often pieces of one track).\n\n"
                + "2. MPC ITF — same night and nearby nights (±%d days)\n"
                + "• Tracklet summary: each 80-column MPC ITF tracklet is summarized by its midpoint time, mean (RA, Dec), "
                + "and—when it has at least 2 observations spanning ≥ 60 s—its sky speed and position angle (PA, east of "
                + "north) from its first to last observation.\n"
                + "• Offset (″ or ′): the finding's position at its own mid-epoch is linearly extrapolated at constant "
                + "(dRA/dt, dDec/dt) to the ITF tracklet's midpoint time (Δt). Offset is the angular separation between "
                + "that straight-line prediction and the ITF tracklet's midpoint.\n"
                + "  – Same night (|Δt| ≤ %.0f h): shown within %.0f″ (or %.0f″ if the ITF tracklet has no rate).\n"
                + "  – Nearby nights (|Δt| ≤ %d d, up to %d nearest): requires a measured ITF rate, within "
                + "%.2f′ + %.1f′/day × |Δd| to allow for orbital curvature.\n"
                + "• Motion (Δv, ΔPA): Δv is the scalar sky-speed difference and ΔPA is the direction difference "
                + "(checked when finding speed ≥ 2″/h; shown as – when the ITF tracklet has no rate). Accepted within "
                + "max(%.0f″/h, %d%%) and %.0f° on the same night, or max(%.0f″/h, %d%%) and %.0f° on nearby nights.\n\n"
                + "POSSIBLE UNCERTAINTIES & LIMITATIONS\n"
                + "• Linear extrapolation & orbital curvature: real sky motion curves over time—especially for near-Earth "
                + "objects, near stationary points, or over several days. Small single-night rate errors also grow "
                + "linearly with time (for example, a 1″/h rate error becomes 24″ per day, or 6′ after 15 days).\n"
                + "• Orbit uncertainty: the 1′ list does not model the ephemeris 3σ uncertainty. A poorly known "
                + "orbit can sit tens of arcseconds off, so a Possible match is a candidate to check, not an identity.\n"
                + "• Topocentric parallax: MPC ITF tracklets may come from another observatory on Earth (different MPC "
                + "code). For closer asteroids, parallax shifts both apparent position and rate.\n"
                + "• Measurement & timing errors: short observing arcs, faint centroids, plate-solution distortion, or "
                + "frame clock offsets increase the uncertainty of the finding's position and motion. Short ITF "
                + "tracklets (only a few minutes long) can also have noisy end-to-end speeds and position angles.\n"
                + "• Candidate screen only: a nearby-night ITF match or a 1′ Possible match is a geometric candidate, not "
                + "a confirmed identification or linkage. Always verify multi-night linkages with an orbit fit, and keep "
                + "in mind that ITF results reflect the cached itf.txt.gz snapshot date.",
                eu.startales.spacepixels.mpc.NearbyKnown.RADIUS_ARCSEC,
                eu.startales.spacepixels.mpc.NearbyKnown.MIN_RATE_TOLERANCE, nearbyRatePct,
                MpcItfCatalog.DEFAULT_NEARBY_DAYS,
                MpcItfCatalog.SAME_NIGHT_MAX_HOURS,
                MpcItfCatalog.SAME_NIGHT_RADIUS_ARCSEC, MpcItfCatalog.SAME_NIGHT_NO_RATE_RADIUS_ARCSEC,
                MpcItfCatalog.DEFAULT_NEARBY_DAYS, MpcItfCatalog.MAX_NEARBY_MATCHES,
                MpcItfCatalog.NEARBY_BASE_RADIUS_ARCMIN, MpcItfCatalog.NEARBY_RADIUS_PER_DAY_ARCMIN,
                MpcItfCatalog.SAME_NIGHT_RATE_FLOOR, itfSamePct, MpcItfCatalog.SAME_NIGHT_PA_TOLERANCE_DEG,
                MpcItfCatalog.NEARBY_RATE_FLOOR, itfNearPct, MpcItfCatalog.NEARBY_PA_TOLERANCE_DEG);
    }

    /**
     * Checks every object in this report against the Minor Planet Center's Isolated Tracklet File (ITF) — both
     * same-night tracklets (already reported to the MPC as unlinked observations) and nearby-night candidates
     * (±{@link MpcItfCatalog#DEFAULT_NEARBY_DAYS} days). Prompts before downloading or updating the ~130 MB file.
     */
    private void checkMpcItf() {
        if (itfWorker != null) {
            itfWorker.cancel(true);
            return;
        }
        if (header == null || report.frames().isEmpty()) {
            compareLabel.setHtml("The frames have no plate solution to compare with the MPC ITF.");
            return;
        }
        long first = report.frames().stream().mapToLong(ReportedObjects.Frame::timestampMillis).filter(t -> t > 0).min().orElse(0);
        long last = report.frames().stream().filter(f -> f.timestampMillis() > 0)
                .mapToLong(f -> f.timestampMillis() + Math.max(0, f.exposureMillis())).max().orElse(0);
        if (first <= 0) {
            compareLabel.setHtml("The frames have no times to compare with the MPC ITF.");
            return;
        }
        long sessionMid = first + (last - first) / 2;
        var queries = new ArrayList<MpcItfCatalog.FindingQuery>();
        for (var object : report.objects()) {
            var samples = LightCurve.samples(report, object);
            long objEpoch = findingMidEpoch(samples, object.motion(), sessionMid);
            var place = eu.startales.spacepixels.mpc.NearbyKnown.place(samples, object.motion(), objEpoch, sky());
            if (place != null) {
                queries.add(new MpcItfCatalog.FindingQuery(object.id(), objEpoch,
                        place.ra(), place.dec(), place.raRateArcsecPerHour(), place.decRateArcsecPerHour()));
            }
        }
        if (queries.isEmpty()) {
            compareLabel.setHtml("No objects have a sky position and motion to compare with the MPC ITF.");
            return;
        }

        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        MpcItfCatalog.CacheStatus cache = MpcItfCatalog.cacheStatus(home);
        boolean shouldDownload;
        if (!cache.exists()) {
            Object[] options = {"Download & check", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this,
                    "The Minor Planet Center Isolated Tracklet File (ITF) is a public ~130 MB gzipped archive\n"
                            + "(itf.txt.gz) of all unlinked tracklets submitted worldwide; the MPC has no online\n"
                            + "cone-search API for it.\n\n"
                            + "Download and cache it locally at:\n" + cache.file() + "\n\n"
                            + "Once cached, checking takes a few seconds offline (same night and ±"
                            + MpcItfCatalog.DEFAULT_NEARBY_DAYS + " days).",
                    "Download MPC Isolated Tracklet File",
                    JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (choice != 0) return;
            shouldDownload = true;
        } else {
            String cachedDate = cache.lastModified() == null ? "unknown date"
                    : cache.lastModified().atZone(ZoneOffset.UTC).toLocalDate().toString();
            String cachedSize = String.format(Locale.US, "%.1f MB", cache.sizeBytes() / (1024.0 * 1024.0));
            Object[] options = {"Use cached", "Update & check", "Cancel"};
            int choice = JOptionPane.showOptionDialog(this,
                    "Cached MPC Isolated Tracklet File found (" + cachedSize + ", snapshot " + cachedDate + " UTC).\n\n"
                            + "Use the cached copy (offline, ~2–4 s), or check the MPC for a newer daily snapshot first?",
                    "Check MPC Isolated Tracklet File",
                    JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
            if (choice != 0 && choice != 1) return;
            shouldDownload = (choice == 1);
        }

        itfButton.setText("Cancel ITF");
        compareLabel.setText(shouldDownload ? "Connecting to Minor Planet Center for itf.txt.gz…" : "Scanning cached MPC ITF…");
        itfWorker = new SwingWorker<MpcItfCatalog.SearchResult, String>() {
            @Override protected MpcItfCatalog.SearchResult doInBackground() throws Exception {
                java.nio.file.Path itfFile = cache.file();
                if (shouldDownload) {
                    long[] lastUi = {0L};
                    MpcItfCatalog.DownloadResult dl = MpcItfCatalog.download(home, false, (read, total) -> {
                        long now = System.nanoTime();
                        if (now - lastUi[0] >= 150_000_000L || (total > 0 && read >= total)) {
                            lastUi[0] = now;
                            if (total > 0) {
                                int pct = (int) Math.min(100, (read * 100L) / total);
                                publish(String.format(Locale.US, "Downloading MPC ITF… %.1f / %.1f MB (%d%%)",
                                        read / (1024.0 * 1024.0), total / (1024.0 * 1024.0), pct));
                            } else {
                                publish(String.format(Locale.US, "Downloading MPC ITF… %.1f MB", read / (1024.0 * 1024.0)));
                            }
                        }
                    });
                    itfFile = dl.file();
                    publish(dl.updated() ? "MPC ITF downloaded. Scanning tracklets…" : "MPC ITF is already up to date. Scanning tracklets…");
                }
                long[] lastScanUi = {0L};
                return MpcItfCatalog.search(itfFile, queries, MpcItfCatalog.DEFAULT_NEARBY_DAYS, lines -> {
                    long now = System.nanoTime();
                    if (now - lastScanUi[0] >= 150_000_000L) {
                        lastScanUi[0] = now;
                        publish(String.format(Locale.US, "Scanning MPC ITF… %.1fM lines", lines / 1_000_000.0));
                    }
                });
            }

            @Override protected void process(List<String> chunks) {
                if (!closed && !chunks.isEmpty() && itfWorker == this) {
                    compareLabel.setText(chunks.getLast());
                }
            }

            @Override protected void done() {
                if (itfWorker == this) itfWorker = null;
                if (closed) return;
                itfButton.setText("Check MPC ITF…");
                if (isCancelled()) {
                    compareLabel.setText("MPC ITF check was cancelled.");
                    return;
                }
                MpcItfCatalog.SearchResult result;
                try {
                    result = get();
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    compareLabel.setHtml("MPC ITF check failed: " + escape(String.valueOf(cause.getMessage())));
                    return;
                }
                applyItfResult(result);
            }
        };
        itfWorker.execute();
    }

    private static long findingMidEpoch(List<LightCurve.Sample> samples, ReportedObjects.Motion motion, long fallback) {
        if (samples != null && !samples.isEmpty()) {
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (var s : samples) {
                if (s.midpointMillis() > 0) {
                    min = Math.min(min, s.midpointMillis());
                    max = Math.max(max, s.midpointMillis());
                }
            }
            if (min <= max) return min + (max - min) / 2;
        }
        if (motion != null && motion.referenceMillis() > 0) return motion.referenceMillis();
        return fallback;
    }

    void applyItfResult(MpcItfCatalog.SearchResult result) {
        itfMatches.clear();
        itfMatches.putAll(result.byFinding());
        itfSnapshotTime = result.snapshotTime();
        String snapDate = itfSnapshotTime == null ? "cached"
                : itfSnapshotTime.atZone(ZoneOffset.UTC).toLocalDate().toString();

        int sameNightCount = 0;
        int nearbyOnlyCount = 0;
        int unmatchedTotal = 0;
        int unmatchedInItf = 0;
        for (var object : report.objects()) {
            var fm = itfMatches.get(object.id());
            boolean isUnmatched = !ReportedObjects.KNOWN.equals(object.status()) && object.names().isEmpty();
            if (isUnmatched) unmatchedTotal++;
            if (fm != null && fm.hasSameNight()) {
                sameNightCount++;
                if (isUnmatched) unmatchedInItf++;
            } else if (fm != null && fm.hasNearbyNights()) {
                nearbyOnlyCount++;
            }
        }
        int unreported = Math.max(0, unmatchedTotal - unmatchedInItf);
        String summary = String.format(Locale.US,
                "MPC ITF (%s, %d nearby-window tracklets): %d reported same-night (%d of %d unmatched); %d unmatched not in ITF%s.",
                snapDate, result.candidateTracklets(), sameNightCount, unmatchedInItf, unmatchedTotal, unreported,
                nearbyOnlyCount > 0 ? "; " + nearbyOnlyCount + " more with nearby-night candidates" : "");

        var details = new StringBuilder(summary);
        details.append(String.format(Locale.US, "\nScanned %,d MPC ITF observation lines (window ±%d days).",
                result.linesScanned(), MpcItfCatalog.DEFAULT_NEARBY_DAYS));
        details.append("\n\nSame-night ITF matches (already reported as unlinked tracklets) and nearby-night candidates:\n");
        for (var object : report.objects()) {
            var fm = itfMatches.get(object.id());
            details.append("\n").append(object.id()).append(" (").append(statusText(status(object)));
            if (!object.names().isEmpty()) details.append(": ").append(String.join(" or ", object.names()));
            details.append("):");
            if (fm == null) {
                details.append(" not checked (no sky motion)");
                continue;
            }
            if (fm.hasSameNight()) {
                details.append(" REPORTED SAME NIGHT → ")
                        .append(String.join("; ", fm.sameNight().stream().map(MpcItfCatalog::describeMatch).toList()));
            } else {
                details.append(" not in ITF on this night");
            }
            if (fm.hasNearbyNights()) {
                details.append("\n    Nearby nights (±").append(MpcItfCatalog.DEFAULT_NEARBY_DAYS).append(" d): ")
                        .append(String.join("; ", fm.nearbyNights().stream().map(MpcItfCatalog::describeMatch).toList()));
            }
        }
        showComparison(summary, details.toString());
        refreshObjects();
        if (shown != null) show(report.objects().stream().filter(o -> o.id().equals(shown.id())).findFirst().orElse(null));
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

    /**
     * Reads the faint mover's frames once, cutting one region around its whole path from each, and measures it in
     * independent sub-stacks on a worker; the centroids become its observations, noted K.
     */
    private void measureSubStacks(ReportedObjects.ReportedObject object) {
        if (object == null || object.motion() == null || reduction.reduction() == null) return;
        if (subStackWorker != null) {
            if (object.id().equals(subStackTargetId)) return;
            subStackWorker.cancel(true);
        }
        autoSubStackTried.add(object.id());
        subStackNeedsRetry.remove(object.id());
        var motion = object.motion();
        double fwhm = report.faintMoverFwhm() == null ? 3 : report.faintMoverFwhm();
        int margin = (int) Math.ceil(Math.max(10, 5 * fwhm)) + 4;
        var positions = object.positions();
        int x0 = (int) Math.floor(positions.stream().mapToDouble(ReportedObjects.Position::x).min().orElse(0)) - margin;
        int y0 = (int) Math.floor(positions.stream().mapToDouble(ReportedObjects.Position::y).min().orElse(0)) - margin;
        int width = (int) Math.ceil(positions.stream().mapToDouble(ReportedObjects.Position::x).max().orElse(0)) + margin - x0;
        int height = (int) Math.ceil(positions.stream().mapToDouble(ReportedObjects.Position::y).max().orElse(0)) + margin - y0;
        if ((long) width * height * positions.size() * 2 > MAX_SUB_STACK_BYTES) {
            subStackNeedsRetry.add(object.id());
            updateAutoMeasureButtons();
            problemsArea.setText(object.id() + "'s path is too long to stack here (" + width + " × " + height + " px over "
                    + positions.size() + " frames).");
            return;
        }
        subStackTargetId = object.id();
        updateAutoMeasureButtons();
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
                if (subStackWorker == this) {
                    subStackWorker = null;
                    subStackTargetId = null;
                }
                if (closed) return;
                if (isCancelled()) {
                    if (shown == object) {
                        subStackNeedsRetry.add(object.id());
                        problemsArea.setText("Measuring " + object.id() + " in sub-stacks was cancelled.");
                        updateIdentificationTables(object);
                    } else {
                        // Cancelled automatically when switching to another object: allow auto-measure if selected again.
                        autoSubStackTried.remove(object.id());
                    }
                    updateAutoMeasureButtons();
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
                    var allIndices = new TreeSet<>(java.util.stream.IntStream.range(0, list.size()).boxed().toList());
                    selected.put(object.id(), allIndices);
                    var entry = measurementEntry(object.id());
                    entry.subStacks = SavedMeasurements.pairSubStacks(stacks, list);
                    entry.selectedIndices = new ArrayList<>(allIndices);
                    saveMeasurements();
                    problemsArea.setText(" ");
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    subStackNeedsRetry.add(object.id());
                    problemsArea.setText(object.id() + " could not be measured: " + cause.getMessage());
                }
                updateAutoMeasureButtons();
                refreshObjects();
                if (shown == object) show(object);
                refreshExport();
            }
        };
        subStackWorker.execute();
    }

    /**
     * One other night of an object: the night, the object there, its measured positions as observations, what the
     * user recorded about it and the MPC on that night (null when nothing), and any saved measurements/ticks.
     */
    private record OtherNight(NightLinks.Night night, ReportedObjects.ReportedObject object, List<MpcObservation> observations,
                              MpcRecords.Entry record, SavedMeasurements.Entry saved) {
        /** Whether that night's observations were marked submitted. */
        boolean submitted() {
            return record != null && record.submittedUtc != null;
        }

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
                            else {
                                var saved = SavedMeasurements.load(ref.folder()).get(ref.objectId());
                                var obs = object.measured() ? night.observations(object)
                                        : saved == null ? List.<MpcObservation>of() : saved.restoredSubStackObservations();
                                nights.add(new OtherNight(night, object, obs,
                                        MpcRecords.load(ref.folder()).get(ref.objectId()), saved));
                            }
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
        updateRecord();
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
        for (var other : linked.nights()) {
            if (other.submitted()) text.append("<br>").append(other.name()).append(" was marked submitted on ").append(other.record().submittedUtc).append(".");
        }
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

    /**
     * The designation the MPC gave an object, as the user entered it on this night or, else, on a linked night (the
     * earliest that has one); null when there is none.
     */
    private String designation(String id) {
        var own = records.get(id);
        if (own != null && MpcRecords.tidy(own.designation) != null) return MpcRecords.tidy(own.designation);
        for (var other : nightsOf(id)) {
            if (other.record() != null && MpcRecords.tidy(other.record().designation) != null) return MpcRecords.tidy(other.record().designation);
        }
        return null;
    }

    /** Shows the shown object's MPC designation and submitted mark, and where a designation from another night comes from. */
    private void updateRecord() {
        var object = shown;
        designationField.setEnabled(object != null);
        designationSave.setEnabled(object != null);
        submittedBox.setEnabled(object != null && !observations.get(object.id()).isEmpty());
        thisNightOnlyBox.setEnabled(object != null && !nightsOf(object.id()).isEmpty());
        thisNightOnlyBox.setSelected(object != null && thisNightOnly.contains(object.id()));
        if (object == null) {
            designationField.setText("");
            submittedBox.setSelected(false);
            recordLabel.setText(" ");
            return;
        }
        var own = records.get(object.id());
        designationField.setText(own == null || own.designation == null ? "" : own.designation);
        submittedBox.setSelected(own != null && own.submittedUtc != null);
        var text = new StringBuilder();
        if (own != null && own.submittedUtc != null) text.append("Marked submitted on ").append(own.submittedUtc).append(". ");
        String designation = designation(object.id());
        if (designation != null && (own == null || MpcRecords.tidy(own.designation) == null)) {
            text.append("Reported as ").append(escape(designation)).append(", the designation entered on a linked night. ");
        }
        if (text.isEmpty()) {
            text.append("When the MPC designates the object, or lists it on the NEO Confirmation Page, enter that designation: "
                    + "later nights are then reported by it. Tick Submitted once you have sent this night.");
        }
        recordLabel.setHtml(text.toString().trim());
    }

    /** Saves the typed designation of the shown object beside the report, or clears it when the field is empty. */
    private void saveDesignation() {
        var object = shown;
        if (object == null) return;
        String typed = MpcRecords.tidy(designationField.getText());
        String problem = MpcRecords.problem(typed);
        if (problem != null) {
            recordLabel.setHtml(escape(problem));
            return;
        }
        var entry = records.computeIfAbsent(object.id(), id -> {
            var created = new MpcRecords.Entry();
            created.id = id;
            return created;
        });
        entry.designation = typed;
        saveRecords();
    }

    /** Marks, or unmarks, this night's observations of the shown object as submitted, with today's UTC date. */
    private void saveSubmitted() {
        var object = shown;
        if (object == null) return;
        var entry = records.computeIfAbsent(object.id(), id -> {
            var created = new MpcRecords.Entry();
            created.id = id;
            return created;
        });
        entry.submittedUtc = submittedBox.isSelected() ? java.time.LocalDate.now(ZoneOffset.UTC).toString() : null;
        if (submittedBox.isSelected()) {
            exported.put(object.id(), false);
            refreshObjects();
        }
        saveRecords();
    }

    /** Writes the records beside the report on a worker, then shows them and rebuilds the file. */
    private void saveRecords() {
        File folder = report.folder();
        var snapshot = new java.util.LinkedHashMap<String, MpcRecords.Entry>();
        for (var entry : records.entrySet()) {
            var copy = new MpcRecords.Entry();
            copy.id = entry.getValue().id;
            copy.designation = entry.getValue().designation;
            copy.submittedUtc = entry.getValue().submittedUtc;
            snapshot.put(entry.getKey(), copy);
        }
        writeOnWorker(() -> {
            MpcRecords.save(folder, snapshot);
            return null;
        }, problem -> {
            updateRecord();
            refreshObjects();
            if (problem != null) recordLabel.setHtml(escape("Not saved: " + problem));
            refreshExport();
        });
    }

    /** Returns the mutable saved-measurement entry for {@code id}, creating one if absent. */
    private SavedMeasurements.Entry measurementEntry(String id) {
        return measurements.computeIfAbsent(id, key -> {
            var created = new SavedMeasurements.Entry();
            created.id = key;
            return created;
        });
    }

    /** Writes saved sub-stacks, observation selections and light curves beside the report on a worker. */
    private void saveMeasurements() {
        File folder = report.folder();
        var snapshot = new java.util.LinkedHashMap<String, SavedMeasurements.Entry>();
        for (var entry : measurements.entrySet()) {
            if (entry.getValue() != null && !entry.getValue().isEmpty()) {
                snapshot.put(entry.getKey(), entry.getValue().copy());
            }
        }
        writeOnWorker(() -> {
            SavedMeasurements.save(folder, snapshot);
            return null;
        }, problem -> {
            if (problem != null) problemsArea.setText("Measurements could not be saved: " + problem);
        });
    }

    /** How many of {@code object}'s linked nights will actually be written into this ADES file. */
    private int includedLinkedNightsCount(ReportedObjects.ReportedObject object) {
        var others = nightsOf(object.id());
        if (others.isEmpty() || thisNightOnly.contains(object.id()) || designation(object.id()) != null) return 0;
        var named = namedObject(object, others);
        var tracklet = MpcExport.tracklet(named, List.of(), report.sessionStartMillis(), Math.max(0, report.objects().indexOf(object)));
        if (tracklet.permID() != null || tracklet.provID() != null) return 0;
        return (int) others.stream().filter(o -> !o.submitted()).count();
    }

    /** Table-cell suffix showing how many linked nights are included in, or omitted from, this file. */
    static String linkedNightsSuffix(int totalLinked, int includedLinked) {
        if (totalLinked <= 0) return "";
        if (includedLinked <= 0) {
            return " · " + totalLinked + (totalLinked == 1 ? " linked night (not in file)" : " linked nights (not in file)");
        }
        String included = " · +" + includedLinked + (includedLinked == 1 ? " night" : " nights");
        int omitted = totalLinked - includedLinked;
        return omitted <= 0 ? included : included + " (" + omitted + " not in file)";
    }

    /** Export-note clause stating that linked nights are not written into this file. */
    static String omittedNightsPhrase(int linkedCount) {
        return linkedCount == 1
                ? "this night only; 1 linked night is not in this file"
                : "this night only; " + linkedCount + " linked nights are not in this file";
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
            if (orbitWindow != null) {
                orbitWindow.dispose();
                orbitWindow = null;
            }
            if (worker != null) worker.cancel(true);
            worker = null;
            for (var running : new SwingWorker<?, ?>[]{identifyWorker, itfWorker, curveWorker, subStackWorker, nightsWorker, findOrbWorker, stationWorker}) {
                if (running != null) running.cancel(true);
            }
            identifyWorker = itfWorker = curveWorker = subStackWorker = nightsWorker = findOrbWorker = stationWorker = null;
            pictures = List.of();
            shownReviewAssets = null;
            pictureCache.clear();
            frameLutCache.clear();
            for (int i = 0; i < sessionFrameHistograms.length(); i++) {
                sessionFrameHistograms.set(i, null);
            }
        }
        super.dispose();
    }

    /** Two-column key/value table model for the selected object's Identification summary. */
    private static final class IdentificationSummaryModel extends AbstractTableModel {
        private final String[] columns = {"Field", "Details"};
        private List<String[]> rows = List.of();

        void setRows(List<String[]> next) {
            this.rows = List.copyOf(next);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) { return rows.get(row)[column]; }
    }

    /** Structured table model for Identify all and MPC ITF (same-night &amp; nearby-night) candidate matches. */
    private static final class IdentificationMatchesModel extends AbstractTableModel {
        private final String[] columns = {"Source", "Object / Tracklet", "Date / Δd", "Obs / Mag", "Offset", "Motion"};
        private List<String[]> rows = List.of();

        void setRows(List<String[]> next) {
            this.rows = List.copyOf(next);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int column) { return columns[column]; }
        @Override public Object getValueAt(int row, int column) { return rows.get(row)[column]; }
    }

    /** Multi-line wrapping cell renderer for the Identification tables so long notes and match details never clip. */
    private static final class WrappingTableCellRenderer extends JTextArea implements javax.swing.table.TableCellRenderer {
        WrappingTableCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
            setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            String text = value == null ? "" : value.toString();
            setText(text);
            setToolTipText(text.isEmpty() ? null : text);
            setFont(column == 0 && table.getColumnCount() == 2
                    ? table.getFont().deriveFont(Font.BOLD)
                    : table.getFont());
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(table.getBackground());
                setForeground(table.getForeground());
            }
            return this;
        }
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
                case 3 -> {
                    if (!object.names().isEmpty()) {
                        yield String.join(" or ", object.names());
                    }
                    var fm = itfMatches.get(object.id());
                    if (fm != null && fm.hasSameNight()) {
                        yield "ITF: " + fm.sameNight().getFirst().tracklet().label()
                                + (fm.hasNearbyNights() ? " (+" + fm.nearbyNights().size() + " nearby)" : "");
                    }
                    if (fm != null && fm.hasNearbyNights()) {
                        yield "ITF nearby (" + fm.nearbyNights().size() + ")";
                    }
                    yield "";
                }
                default -> (object.measured() ? selected.get(object.id()).size() + " of " + observations.get(object.id()).size()
                        : subStacks.containsKey(object.id()) ? subStacks.get(object.id()).size() + " sub-stacks" : "fitted only")
                        + linkedNightsSuffix(nightsOf(object.id()).size(), includedLinkedNightsCount(object));
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
            measurementEntry(shown.id()).selectedIndices = new ArrayList<>(set);
            saveMeasurements();
            forgetOrbit();
            fireTableCellUpdated(row, column);
            refreshObjects();
            updateFit();
            refreshExport();
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

    /**
     * Draws the shown source picture (per-frame DisplayStretch, enlarged without smoothing, with a cyan ring at the
     * object) and, for faint movers with saved report assets, the synchronized PSF-filtered residual and whole-session
     * shift stack side by side.
     */
    private final class Picture extends JComponent {
        private static final Color OVERLAY_CYAN = new Color(80, 225, 210);
        private static final Color CAPTION_COLOR = new Color(185, 192, 204);
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
                var p = shown.positions().get(Math.min(i, shown.positions().size() - 1));
                boolean fixed = starCentred.isSelected() && pictures.get(i)[1] != null;
                short[][] crop = pictures.get(i)[fixed ? 1 : 0];
                if (crop == null) return;

                byte[] lut = currentStretchLut(p.frameNumber() - 1, crop);
                BufferedImage sourceImage = DisplayStretch.createDisplayImage(crop, lut);
                double px = fixed ? p.x() - fixedOrigin[0] : p.x() - (Math.round(p.x()) - CROP / 2);
                double py = fixed ? p.y() - fixedOrigin[1] : p.y() - (Math.round(p.y()) - CROP / 2);

                boolean showEvidence = showFaintEvidenceBox.isVisible() && showFaintEvidenceBox.isSelected()
                        && shownReviewAssets != null && shownReviewAssets.hasAny();
                FaintMoverReviewAssets.ResidualCube cube = showEvidence ? shownReviewAssets.residuals() : null;
                FaintMoverReviewAssets.ResidualFrame resFrame = cube == null ? null : cube.frameForNumber(p.frameNumber());
                BufferedImage stackImage = showEvidence ? shownReviewAssets.shiftStack() : null;

                int panelCount = 1 + (resFrame != null ? 1 : 0) + (stackImage != null ? 1 : 0);
                if (panelCount == 1) {
                    drawSourceTile(g, 0, 0, getWidth(), getHeight(), null, sourceImage, px, py);
                    return;
                }

                int gap = UiStyle.space(8);
                int pad = UiStyle.space(4);
                int tileW = Math.max(20, (getWidth() - 2 * pad - (panelCount - 1) * gap) / panelCount);
                int tileH = Math.max(20, getHeight() - 2 * pad);
                int slot = 0;
                drawSourceTile(g, pad + slot * (tileW + gap), pad, tileW, tileH, "Source frames", sourceImage, px, py);
                slot++;
                if (resFrame != null) {
                    drawResidualTile(g, pad + slot * (tileW + gap), pad, tileW, tileH,
                            "PSF-filtered residual", cube, resFrame, !fixed);
                    slot++;
                }
                if (stackImage != null) {
                    drawShiftStackTile(g, pad + slot * (tileW + gap), pad, tileW, tileH,
                            "Whole-session shift stack", stackImage);
                }
            } finally {
                g.dispose();
            }
        }

        private void drawSourceTile(Graphics2D g, int x, int y, int width, int height, String caption,
                                    BufferedImage image, double px, double py) {
            int topOffset = drawCaption(g, x, y, width, caption);
            int availH = Math.max(1, height - topOffset);
            double scale = Math.min(width / (double) image.getWidth(), availH / (double) image.getHeight());
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int left = x + (width - w) / 2;
            int top = y + topOffset + (availH - h) / 2;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, left, top, w, h, null);
            double r = Math.max(6, 4 * scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(OVERLAY_CYAN);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(left + (px + 0.5) * scale - r, top + (py + 0.5) * scale - r, 2 * r, 2 * r));
        }

        private void drawResidualTile(Graphics2D g, int x, int y, int width, int height, String caption,
                                      FaintMoverReviewAssets.ResidualCube cube,
                                      FaintMoverReviewAssets.ResidualFrame frame, boolean objectCentredMode) {
            int topOffset = drawCaption(g, x, y, width, caption);
            int availH = Math.max(1, height - topOffset);
            double bound = residualRangeSlider.getValue() / 10.0;
            BufferedImage image = cube.render(frame, objectCentredMode, bound);
            double scale = Math.min(width / (double) image.getWidth(), availH / (double) image.getHeight());
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int left = x + (width - w) / 2;
            int top = y + topOffset + (availH - h) / 2;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, left, top, w, h, null);

            Shape previousClip = g.getClip();
            g.clipRect(left, top, w, h);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(OVERLAY_CYAN);
            double dx = objectCentredMode ? frame.x() - (cube.width() - 1) / 2.0 : 0.0;
            double dy = objectCentredMode ? frame.y() - (cube.height() - 1) / 2.0 : 0.0;
            if (cube.frames().size() >= 2) {
                var first = cube.frames().getFirst();
                var last = cube.frames().getLast();
                double x0 = first.x() + (cube.followsPath() ? first.originX() - frame.originX() : 0) - dx;
                double y0 = first.y() + (cube.followsPath() ? first.originY() - frame.originY() : 0) - dy;
                double x1 = last.x() + (cube.followsPath() ? last.originX() - frame.originX() : 0) - dx;
                double y1 = last.y() + (cube.followsPath() ? last.originY() - frame.originY() : 0) - dy;
                g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{4f, 4f}, 0f));
                g.draw(new java.awt.geom.Line2D.Double(
                        left + (x0 + 0.5) * scale, top + (y0 + 0.5) * scale,
                        left + (x1 + 0.5) * scale, top + (y1 + 0.5) * scale));
            }
            double cx = frame.x() - dx;
            double cy = frame.y() - dy;
            double r = Math.max(6, 4 * scale);
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(left + (cx + 0.5) * scale - r, top + (cy + 0.5) * scale - r, 2 * r, 2 * r));
            g.setClip(previousClip);
        }

        private void drawShiftStackTile(Graphics2D g, int x, int y, int width, int height, String caption,
                                        BufferedImage image) {
            int topOffset = drawCaption(g, x, y, width, caption);
            int availH = Math.max(1, height - topOffset);
            double scale = Math.min(width / (double) image.getWidth(), availH / (double) image.getHeight());
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            int left = x + (width - w) / 2;
            int top = y + topOffset + (availH - h) / 2;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(image, left, top, w, h, null);

            double cx = left + w / 2.0;
            double cy = top + h / 2.0;
            double arm = Math.max(8, 3.5 * scale);
            double inner = Math.max(3, 1.2 * scale);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(OVERLAY_CYAN);
            g.setStroke(new BasicStroke(1.3f));
            g.draw(new java.awt.geom.Line2D.Double(cx - arm, cy, cx - inner, cy));
            g.draw(new java.awt.geom.Line2D.Double(cx + inner, cy, cx + arm, cy));
            g.draw(new java.awt.geom.Line2D.Double(cx, cy - arm, cx, cy - inner));
            g.draw(new java.awt.geom.Line2D.Double(cx, cy + inner, cx, cy + arm));
        }

        private int drawCaption(Graphics2D g, int x, int y, int width, String caption) {
            if (caption == null) return 0;
            Font font = UiStyle.bodyFont().deriveFont(Font.PLAIN, Math.max(10f, UiStyle.bodyFont().getSize2D() - 1f));
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            int textW = fm.stringWidth(caption);
            g.setColor(CAPTION_COLOR);
            g.drawString(caption, x + Math.max(0, (width - textW) / 2), y + fm.getAscent());
            return fm.getHeight() + UiStyle.space(4);
        }
    }
}
