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
import eu.startales.spacepixels.mpc.MpcExport;
import eu.startales.spacepixels.mpc.MpcObservation;
import eu.startales.spacepixels.mpc.ReportedObjects;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.session.ApplicationStorage;
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
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US).withZone(ZoneOffset.UTC);

    private final FitsFileInformation[] files;
    private final ReportedObjects.Report report;
    private final AstrometricReduction.Result reduction;
    private final ApplicationSettings settings;
    private final ApplicationStorage storage;
    private final VisualizationSettings visualization;
    private final ObservingSite site;

    private final Map<String, Boolean> exported = new HashMap<>();
    private final Map<String, Set<Integer>> selected = new HashMap<>();
    private final Map<String, List<MpcObservation>> observations = new HashMap<>();
    private final AtomicInteger generation = new AtomicInteger();
    private SwingWorker<List<BufferedImage[]>, Void> worker;
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
    private final JLabel fitLabel = new JLabel(" ");
    private final JLabel identificationLabel = new JLabel(" ");
    private final JLabel stationLabel = new JLabel(" ");
    private final JTextArea problemsArea = new JTextArea(4, 30);
    private final JTextArea psvArea = new JTextArea(12, 30);
    private final JButton copyButton = new JButton("Copy");
    private final JButton saveButton = new JButton("Save .psv…");
    private final Timer playTimer;
    private List<BufferedImage[]> pictures = List.of();
    private ReportedObjects.ReportedObject shown;

    private final JTextField submitter = new JTextField(16);
    private final JTextField observers = new JTextField(16);
    private final JTextField measurers = new JTextField(16);
    private final JTextField design = new JTextField(16);
    private final JTextField aperture = new JTextField(6);
    private final JTextField detector = new JTextField(10);
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"CCD", "CMOS"});
    private final JTextField comment = new JTextField(16);

    /**
     * @param files      the session's frames, which must be the report's
     * @param report     the report's objects
     * @param header     the header plate solution of the frames, or null
     * @param correction the session's plate correction, or null
     */
    AsteroidsCometsWindow(Window owner, FitsFileInformation[] files, ReportedObjects.Report report, WcsCoordinateTransformer header,
                          PlateSolutionCorrection correction, ApplicationSettings settings, ApplicationStorage storage,
                          VisualizationSettings visualization, ObservingSite site) {
        super("Asteroids & Comets · " + report.folder().getName());
        AppIcon.apply(this);
        this.files = files.clone();
        this.report = report;
        this.reduction = AstrometricReduction.create(header, correction, this.files);
        this.settings = settings;
        this.storage = storage;
        this.visualization = visualization;
        this.site = site;
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
        JSplitPane centre = new JSplitPane(JSplitPane.VERTICAL_SPLIT, picturePanel(), observationsPanel());
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
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(controls, BorderLayout.NORTH);
        panel.add(picture, BorderLayout.CENTER);
        pictureLabel.setForeground(UiStyle.helpColor());
        panel.add(pictureLabel, BorderLayout.SOUTH);
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
        fitLabel.setForeground(UiStyle.helpColor());
        buttons.add(fitLabel);
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(title("Observations · the MPC asks for a few good ones per night, spread over about an hour"), BorderLayout.NORTH);
        panel.add(new JScrollPane(observationsTable), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    /** Identification, the submission details and the export. */
    private JComponent sidePanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(title("Identification"));
        panel.add(identificationLabel);
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
        if (object == null) {
            identificationLabel.setText(" ");
            return;
        }
        String names = object.names().isEmpty() ? "" : "<b>" + escape(String.join(" or ", object.names())) + "</b><br>";
        String measure = object.measured() ? "" : "<br>A faint mover is measured in independent sub-stacks, a later step; its "
                + "positions here are fitted, not measured, and cannot be reported.";
        String reduced = reduction.reduction() == null ? "<br>" + escape(reduction.problem()) : "";
        identificationLabel.setText(UiStyle.htmlWrappedTo(320, object.id() + " · " + statusText(object.status()) + "<br>" + names
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
        double black = visualization.getAutoStretchBlackSigma(), white = visualization.getAutoStretchWhiteSigma();
        worker = new SwingWorker<>() {
            @Override
            protected List<BufferedImage[]> doInBackground() throws Exception {
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
                double[] objectLevels = DisplayImageRenderer.sharedDisplayLevels(objectCrops, black, white);
                double[] fixedLevels = fixed ? DisplayImageRenderer.sharedDisplayLevels(fixedCrops, black, white) : null;
                var images = new ArrayList<BufferedImage[]>();
                for (int i = 0; i < objectCrops.size(); i++) {
                    images.add(new BufferedImage[]{
                            DisplayImageRenderer.createDisplayImageAtLevels(objectCrops.get(i), objectLevels[0], objectLevels[1]),
                            fixed ? DisplayImageRenderer.createDisplayImageAtLevels(fixedCrops.get(i), fixedLevels[0], fixedLevels[1]) : null});
                }
                return images;
            }

            @Override
            protected void done() {
                if (closed || request != generation.get() || isCancelled()) return;
                try {
                    pictures = get();
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
        short[][] out = new short[height][width];
        for (int row = 0; row < height; row++) {
            java.util.Arrays.fill(out[row], Short.MIN_VALUE);
            int source = y + row;
            if (source < 0 || source >= frame.length) continue;
            int from = Math.max(0, -x), to = Math.min(width, frame[source].length - x);
            if (from < to) System.arraycopy(frame[source], x + from, out[row], from, to - from);
        }
        return out;
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
        observationsModel.fireTableDataChanged();
        objectsModel.fireTableDataChanged();
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

    static String statusText(String status) {
        return switch (status == null ? "" : status) {
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
            return column == 0 && report.objects().get(row).measured() && !observations.get(report.objects().get(row).id()).isEmpty();
        }

        @Override public Object getValueAt(int row, int column) {
            var object = report.objects().get(row);
            return switch (column) {
                case 0 -> exported.get(object.id());
                case 1 -> object.id();
                case 2 -> statusText(object.status());
                case 3 -> String.join(" or ", object.names());
                default -> object.measured() ? selected.get(object.id()).size() + " of " + observations.get(object.id()).size()
                        : "fitted only";
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
            fireTableCellUpdated(row, column);
            objectsModel.fireTableDataChanged();
            updateFit();
            refreshExport();
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
                BufferedImage image = pictures.get(i)[fixed ? 1 : 0];
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
