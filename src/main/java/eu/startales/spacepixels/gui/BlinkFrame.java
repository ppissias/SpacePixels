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

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.detection.VariableStarOverlay;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Plays the selected frames in turn, the classic way of hunting moving objects by eye. The speed, the display
 * stretch (shared with the Image Stretch editor) and the zoom can change while it plays, and the findings of the last
 * detection run and the catalogue can be drawn on top, chosen in the side panel. The list on the left shows every
 * frame, with a band where the capture times have a gap; while paused, frames can be left out of the loop. Under the
 * image, the transport bar steps and plays the loop, with a timeline by capture time. The window opens as soon as the
 * frames start loading and shows the progress.
 */
public class BlinkFrame extends JFrame implements SessionViewer {

    private static final String SPEED_PREFERENCE = "blink.frameMillis";
    /** 30 frames per second. */
    private static final int MIN_MILLIS = 33;
    private static final int MAX_MILLIS = 2000;
    private static final int DEFAULT_MILLIS = 500;
    /** Steps of the speed slider; fine enough that a saved speed comes back as itself. */
    private static final int SPEED_STEPS = 1000;
    private static final String BLINK_CARD = "blink";
    private static final String LOADING_CARD = "loading";

    /** Told when a blink, or the loading before it, ends because the window closed; set by the main window. */
    private Runnable onClose = () -> { };
    private final FrameView view = new FrameView();
    private final JButton playButton = new JButton("❚❚ Pause");
    private final JButton previousButton = new JButton("◀");
    private final JButton nextButton = new JButton("▶");
    private final JSlider speedSlider = new JSlider(0, SPEED_STEPS);
    private final JLabel speedLabel = new JLabel();
    private final BlinkTimeline timeline = new BlinkTimeline();
    private final JLabel positionLabel = new JLabel(" ");
    private final JLabel loopCount = new JLabel(" ");
    private final JButton previewButton = new JButton("Open in Preview ↗");
    private java.util.function.Consumer<FitsFileInformation> openInPreview = shown -> { };
    private final ViewerLayerState layerState;
    /** Kept here: the shared state holds its listeners weakly. */
    private final Runnable layerListener = this::layersChanged;
    private final FindingsSection findings;
    private final ViewerSidePanel sidePanel = new ViewerSidePanel();
    /** Holds the Display stretch section, which is made when the first blink brings the Image Stretch editor. */
    private final JPanel stretchHolder = new JPanel(new BorderLayout());
    private final JLabel frameLabel = new JLabel(" ");
    private final JLabel cursorLabel = new JLabel(" ");
    private final FrameTableModel frameModel = new FrameTableModel();
    private final JTable frameTable = new FrameTable();
    private final JTextArea framesHint = new JTextArea();
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JPanel loadingPanel = new JPanel(new GridBagLayout());
    private final JProgressBar loadBar = new JProgressBar();
    private final JLabel loadLabel = new JLabel(" ");
    private final JLabel loadFileLabel = new JLabel(" ");
    private final JComponent statusBar;
    private final Timer timer;
    private final BlinkGifExport gifExport;

    private StretchControls stretch;
    private final ViewerSupport.CursorReadout cursorReadout = new ViewerSupport.CursorReadout();
    private final SkyAnnotations sky;

    private BlinkSequence sequence;
    private FitsFileInformation[] files;
    private boolean[] skipped;
    /** Frames whose interval to the previous one is a gap in the session. */
    private boolean[] gapBefore;
    private int current;
    private boolean open;
    /** Open in Preview paused a playing blink, and nothing has paused or played it since. */
    private boolean resumeOnReturn;
    /** Frames are being read for the next blink; the window shows the progress. */
    private boolean loading;
    private FitsFileInformation[] loadingFiles;
    private Point cursorPixel;

    /** {@code layers} are the overlay layers shared by the application's viewers. */
    public BlinkFrame(ViewerLayerState layers) {
        layerState = layers;
        sky = new SkyAnnotations(view, layers);
        findings = new FindingsSection(layers);
        setTitle("Blink");
        AppIcon.apply(this);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(null);

        timer = new Timer(DEFAULT_MILLIS, e -> step(1));
        gifExport = new BlinkGifExport(view, this::captureGifSource, this::pause);

        JPanel contentPane = new JPanel(new BorderLayout(0, 6));
        contentPane.setBorder(new EmptyBorder(6, 6, 6, 6));
        setContentPane(contentPane);
        JComponent controls = buildControls();
        statusBar = buildStatusBar();
        stretchHolder.setOpaque(false);
        sidePanel.add(findings.section());
        sidePanel.add(sky.section());
        sidePanel.add(stretchHolder);
        ViewerSupport.prepareControls(sidePanel.component());
        // Every control above the image: the toolbar, then the transport bar right over the frames.
        JPanel controlRows = new JPanel(new BorderLayout());
        controlRows.add(controls, BorderLayout.NORTH);
        controlRows.add(buildTransport(), BorderLayout.SOUTH);
        JPanel imageColumn = new JPanel(new BorderLayout());
        imageColumn.add(WindowSizing.viewerControls(controlRows), BorderLayout.NORTH);
        imageColumn.add(gifExport.image(), BorderLayout.CENTER);
        JPanel imageAndPanel = new JPanel(new BorderLayout());
        imageAndPanel.add(imageColumn, BorderLayout.CENTER);
        imageAndPanel.add(sidePanel.component(), BorderLayout.EAST);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildFrameList(), imageAndPanel);
        split.setFocusable(false);
        split.setContinuousLayout(true);
        // The split pane would take the arrow keys to move its divider; they step frames here.
        SwingUtilities.replaceUIInputMap(split, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, null);
        center.add(split, BLINK_CARD);
        center.add(buildLoadingPanel(), LOADING_CARD);
        contentPane.add(center, BorderLayout.CENTER);
        contentPane.add(statusBar, BorderLayout.SOUTH);
        view.setMinimumSize(new Dimension(UiStyle.space(240), UiStyle.space(160)));
        // The frame list, the image with its transport bar and the side panel side by side.
        WindowSizing.install(this, 1000, 520);

        // Only the frame view takes the keyboard focus: a focused button would take Space for itself.
        ViewerSupport.prepareControls(controls);
        ViewerSupport.prepareControls(statusBar);
        view.getAccessibleContext().setAccessibleName("Blink image");
        view.getAccessibleContext().setAccessibleDescription("Space plays or pauses and arrow keys step frames "
                + "while the image has focus. G starts Export GIF; drag a rectangle, then Export GIF or Ctrl+E. "
                + "Tab reaches controls; Escape from controls returns to the image.");
        view.setFocusable(true);
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                view.requestFocusInWindow();
            }
        });

        view.setCursorListener(pixel -> {
            cursorPixel = pixel;
            updateCursorLabel();
        });
        installKeyBindings();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        setSpeedMillis(savedSpeedMillis());
    }

    // ==========================================
    // OPEN AND CLOSE
    // ==========================================

    /**
     * Shows the loaded frames and starts playing. {@code sessionFiles} are all frames of the session: one plate-solved
     * frame among them gives sky coordinates and labels to the frames blinked, also when none of those is solved.
     * {@code overlay} holds the tracks of the last detection run on this session, or is null.
     */
    void setSkyCatalogueModel(eu.startales.spacepixels.session.SkyCatalogueModel model) {
        sky.bind(model);
    }

    void open(BlinkSequence sequence, FitsFileInformation[] files, FitsFileInformation[] sessionFiles,
              StretchPanel stretchPanel, TrackOverlay overlay, VariableStarOverlay variables, FitsFileInformation startAt) {
        stretch = ViewerSupport.bindStretch(stretch, stretchPanel, this::showCurrent, stretchHolder,
                () -> open && this.sequence != null ? this.sequence.getFrames().get(current).getInfo() : null);
        release();
        this.sequence = sequence;
        this.files = files == null ? null : files.clone();
        FitsFileInformation[] alignedFiles = sessionFiles != null ? sessionFiles : this.files;
        cursorReadout.reset(alignedFiles, sky::solutionFor);
        sky.attach(alignedFiles);
        this.skipped = new boolean[sequence.getFrames().size()];
        long[] timestamps = new long[skipped.length];
        for (int i = 0; i < timestamps.length; i++) timestamps[i] = sequence.getFrames().get(i).getInfo().getObservationTimestamp();
        this.gapBefore = BlinkTimeline.gaps(timestamps);
        timeline.setFrames(timestamps, skipped, gapBefore);
        this.current = 0;
        for (int i = 0; startAt != null && i < sequence.getFrames().size(); i++) {
            String path = sequence.getFrames().get(i).getInfo().getFilePath();
            if (path != null && path.equals(startAt.getFilePath())) this.current = i;
        }
        this.open = true;

        Set<String> names = new HashSet<>();
        for (BlinkSequence.Frame frame : sequence.getFrames()) {
            names.add(new File(frame.getInfo().getFilePath()).getName());
        }
        boolean hasTracks = overlay != null && overlay.coversAnyOf(names);
        view.setOverlay(hasTracks ? overlay : null);
        findings.setTracks(overlay, hasTracks);
        boolean hasVariables = variables != null && variables.coversAnyOf(files);
        findings.setVariableStars(variables, hasVariables);
        view.setVariableStarOverlay(hasVariables ? variables : null);
        view.setLayers(layerState.snapshot());

        setTitle("Blink · " + ViewerSupport.sessionName(sequence.getFrames().get(0).getInfo().getFilePath()) + " · "
                + sequence.getFrames().size() + " frames");
        frameModel.fireTableDataChanged();
        for (int row = 0; row < gapBefore.length; row++) {
            if (gapBefore[row]) frameTable.setRowHeight(row, frameTable.getRowHeight() + gapBand());
        }
        updateLoopCount();
        statusBar.setVisible(true);
        centerCards.show(center, BLINK_CARD);
        view.fit();
        showCurrent();
        gifExport.setAvailable(true);
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        view.requestFocusInWindow();
        play();
    }

    /** Updates the blink viewer's frame metadata after a session WCS write when the viewer is open. */
    void replaceFrameMetadata(FitsFileInformation previous, FitsFileInformation updated) {
        if (previous == null || updated == null || !open || sequence == null) {
            return;
        }
        sequence = sequence.replaceFrameInfo(previous, updated);
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i] == previous || (files[i] != null && files[i].getFilePath() != null
                        && files[i].getFilePath().equals(previous.getFilePath()))) {
                    files[i] = updated;
                }
            }
        }
        sky.replaceFrame(previous, updated);
    }

    /** Shows the window at once with the progress of reading these frames; {@link #open} then starts the blink. */
    void showLoading(FitsFileInformation[] files) {
        release();
        loading = true;
        loadingFiles = files;
        loadBar.setMaximum(files.length);
        showLoadProgress(0, files.length);
        setTitle("Blink · " + ViewerSupport.sessionName(files[0].getFilePath()) + " · reading " + files.length + " frames");
        statusBar.setVisible(false);
        centerCards.show(center, LOADING_CARD);
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        loadingPanel.requestFocusInWindow();
    }

    /** {@code loaded} of {@code total} frames are read. */
    void showLoadProgress(int loaded, int total) {
        if (!loading) {
            return;
        }
        loadBar.setValue(loaded);
        loadBar.setString(loaded + " / " + total);
        loadLabel.setText(String.format(Locale.US, "Reading frame %d of %d…", Math.min(loaded + 1, total), total));
        loadFileLabel.setText(loadingFiles != null && loaded < loadingFiles.length ? loadingFiles[loaded].getFileName() : " ");
    }

    /**
     * Ends the blink, or the loading before it: stops, frees the frames and tells the main window. Only hides the
     * window when neither is going on.
     */
    @Override
    public void closeForSessionChange() {
        close();
    }

    @Override
    public void dispose() {
        close();
        super.dispose();
    }

    void close() {
        boolean active = open || loading;
        release();
        if (stretch != null) {
            stretch.detach();
            stretch = null;
        }
        setVisible(false);
        if (active && onClose != null) {
            onClose.run();
        }
    }

    /** Sets what Open in Preview does with the frame shown. */
    void setOpenInPreview(java.util.function.Consumer<FitsFileInformation> openInPreview) {
        this.openInPreview = openInPreview;
    }

    /** Open in Preview: pauses on the frame shown and hands it over; the blink stays open with its frames. */
    private void openShownInPreview() {
        if (!open) {
            return;
        }
        boolean wasPlaying = timer.isRunning();
        pause();
        resumeOnReturn = wasPlaying;
        openInPreview.accept(sequence.getFrames().get(current).getInfo());
    }

    /**
     * Preview Frame's way back to this open blink: comes to the front on the frame Preview shows, when that frame is
     * blinked, and plays again if Open in Preview paused it. Reads nothing.
     */
    void returnFromPreview(FitsFileInformation shown) {
        if (!open) {
            return;
        }
        for (int i = 0; shown != null && shown.getFilePath() != null && i < sequence.getFrames().size(); i++) {
            if (shown.getFilePath().equals(sequence.getFrames().get(i).getInfo().getFilePath())) {
                current = i;
                showCurrent();
                break;
            }
        }
        ViewerSupport.bringToFront(this);
        view.requestFocusInWindow();
        if (resumeOnReturn) {
            play();
        }
    }

    /** Sets what to do when the window ends a blink or its loading. */
    void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    /** Stops and lets go of the frames of the current blink, if any. */
    private void release() {
        view.setFaintMoverOverlay(null);
        view.setFindingOverlay(null);
        gifExport.release();
        open = false;
        resumeOnReturn = false;
        loading = false;
        loadingFiles = null;
        timer.stop();
        sequence = null;
        files = null;
        gapBefore = null;
        view.setCaption(null);
        if (stretch != null) {
            stretch.clearTables();
        }
        cursorReadout.clear();
        sky.detach();
        cursorPixel = null;
        view.clear();
        frameModel.fireTableDataChanged();
    }

    /** Installs the last completed run's fitted paths after a blink opens, using full frame identities. */
    void setFaintMoverOverlay(eu.startales.spacepixels.detection.FaintMoverOverlay overlay) {
        boolean available = overlay != null && overlay.coversAnyOf(files);
        findings.setFaintMovers(overlay, available);
        view.setFaintMoverOverlay(available ? overlay : null);
    }

    /** Installs the saved or completed run's remaining categories after a blink opens. */
    void setFindingOverlay(eu.startales.spacepixels.detection.FindingOverlay overlay) {
        boolean available = overlay != null && overlay.coversAnyOf(files);
        findings.setFindings(overlay, available);
        view.setFindingOverlay(available ? overlay : null);
    }

    /** Follows the shared layer state, which this or another viewer changed. */
    private void layersChanged() {
        view.setLayers(layerState.snapshot());
        findings.refresh();
        sky.layersChanged();
        updateCursorLabel();
    }

    /** A key switches a layer of the last run only while the blink has it to show. */
    private void toggleFindingLayer(ViewerLayer layer) {
        if (findings.isAvailable(layer)) {
            layerState.toggleVisible(layer);
        }
    }

    // ==========================================
    // CONTROLS
    // ==========================================

    /**
     * The toolbar directly above the image: the zoom on the left; Export GIF, Open in Preview and the side panel's
     * button on the right. On a narrow image column the right group goes to a second row.
     */
    private JComponent buildControls() {
        previewButton.setToolTipText("Pause the blink and show this frame in Preview Frame. Both windows stay open; "
                + "Preview's Blink button comes back here without reading the frames again.");
        previewButton.addActionListener(e -> openShownInPreview());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        left.add(ViewerSupport.zoomControl(view));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(6), 0));
        right.add(gifExport.toolbarButton());
        right.add(previewButton);
        right.add(sidePanel.toggleButton());
        JPanel toolbar = new WidthFollowingPanel();
        toolbar.setLayout(new ToolbarRowLayout(left, right));
        toolbar.setBorder(new EmptyBorder(0, 0, UiStyle.space(4), 0));
        toolbar.add(left);
        toolbar.add(right);
        return toolbar;
    }

    /**
     * The transport bar over the image: step back, play or pause, step forward, the timeline, the frame's place in
     * the sequence, and the speed.
     */
    private JComponent buildTransport() {
        playButton.setToolTipText("Play or pause (Space).");
        playButton.putClientProperty("FlatLaf.style", MainApplicationPanel.PRIMARY_BUTTON_STYLE);
        playButton.addActionListener(e -> togglePlay());
        previousButton.setToolTipText("Previous frame in the loop (← or, while paused, ↑). Stepping pauses the blink.");
        previousButton.addActionListener(e -> stepByHand(-1));
        nextButton.setToolTipText("Next frame in the loop (→ or, while paused, ↓). Stepping pauses the blink.");
        nextButton.addActionListener(e -> stepByHand(1));
        timeline.setOnSelect(this::goTo);

        speedSlider.setToolTipText("Frames per second (↑ faster, ↓ slower while playing).");
        Dimension speedSize = speedSlider.getPreferredSize();
        speedSlider.setPreferredSize(new Dimension(UiStyle.space(120), speedSize.height));
        speedSlider.addChangeListener(e -> speedChanged());
        speedSlider.getAccessibleContext().setAccessibleName("Speed");
        ViewerSupport.fixWidth(speedLabel, "30.0 fps · 2000 ms");
        ViewerSupport.fixWidth(positionLabel, "888 / 888 · +8:88:88");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(4), 0));
        buttons.add(previousButton);
        buttons.add(playButton);
        buttons.add(nextButton);
        JPanel speed = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        speed.add(new JLabel("Speed"));
        speed.add(speedSlider);
        speed.add(speedLabel);
        JPanel transport = new WidthFollowingPanel();
        transport.setLayout(new TransportLayout(buttons, timeline, positionLabel, speed));
        transport.setBorder(new EmptyBorder(0, 0, UiStyle.space(4), 0));
        transport.add(buttons);
        transport.add(timeline);
        transport.add(positionLabel);
        transport.add(speed);
        ViewerSupport.prepareControls(transport);
        return transport;
    }

    /**
     * The frames of the blink: the one shown is highlighted, the tick says whether a frame is in the loop, and a band
     * above a frame marks a gap in the capture times.
     */
    private JComponent buildFrameList() {
        frameTable.setFocusable(false);
        frameTable.setRowSelectionAllowed(false);
        frameTable.setShowGrid(false);
        frameTable.setIntercellSpacing(new Dimension(0, 0));
        frameTable.setFillsViewportHeight(true);
        frameTable.setTableHeader(null);
        TableColumnModel columns = frameTable.getColumnModel();
        fixColumnWidth(columns.getColumn(0), 30);
        fixColumnWidth(columns.getColumn(1), 36);
        fixColumnWidth(columns.getColumn(3), 70);
        columns.getColumn(0).setCellRenderer(new LoopCellRenderer());
        TextCellRenderer textRenderer = new TextCellRenderer();
        for (int column = 1; column < columns.getColumnCount(); column++) {
            columns.getColumn(column).setCellRenderer(textRenderer);
        }
        frameTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = frameTable.rowAtPoint(e.getPoint());
                if (!open || row < 0 || !SwingUtilities.isLeftMouseButton(e) || inGapBand(row, e.getY())) {
                    return;
                }
                // The tick works only while paused: during play the frame shown changes too fast to aim at.
                boolean toggle = frameTable.columnAtPoint(e.getPoint()) == 0 && !timer.isRunning();
                goTo(row);
                if (toggle) {
                    setSkipped(row, !skipped[row]);
                }
            }
        });
        JScrollPane scroll = new JScrollPane(frameTable);
        scroll.setFocusable(false);

        framesHint.setEditable(false);
        framesHint.setFocusable(false);
        framesHint.setOpaque(false);
        framesHint.setLineWrap(true);
        framesHint.setWrapStyleWord(true);
        framesHint.setFont(UIManager.getFont("Label.font"));
        framesHint.setForeground(UiStyle.helpColor());

        JLabel title = new JLabel("Frames");
        title.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        loopCount.setForeground(UiStyle.helpColor());
        JPanel header = new JPanel(new BorderLayout());
        header.setBorder(new EmptyBorder(0, UiStyle.space(4), UiStyle.space(4), UiStyle.space(4)));
        header.add(title, BorderLayout.WEST);
        header.add(loopCount, BorderLayout.EAST);

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(new EmptyBorder(0, 0, 0, 4));
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(framesHint, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(UiStyle.space(270), 100));
        panel.setMinimumSize(new Dimension(UiStyle.space(200), 0));
        return panel;
    }

    /** Whether a click in this row is in the gap band above the frame. */
    private boolean inGapBand(int row, int y) {
        return gapBefore != null && row < gapBefore.length && gapBefore[row]
                && y - frameTable.getCellRect(row, 0, true).y < gapBand();
    }

    private static int gapBand() {
        return UiStyle.space(22);
    }

    private static void fixColumnWidth(TableColumn column, int width) {
        column.setMinWidth(width);
        column.setMaxWidth(width);
        column.setPreferredWidth(width);
    }

    private JComponent buildLoadingPanel() {
        loadLabel.setFont(loadLabel.getFont().deriveFont(Font.BOLD, loadLabel.getFont().getSize2D() + 2f));
        loadBar.setStringPainted(true);
        Dimension barSize = new Dimension(380, loadBar.getPreferredSize().height);
        loadBar.setPreferredSize(barSize);
        loadBar.setMaximumSize(barSize);
        loadFileLabel.setForeground(UiStyle.helpColor());
        JLabel note = new JLabel("Each frame is read once; the blink then plays from memory.");
        note.setForeground(UiStyle.helpColor());
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setFocusable(false);
        cancelButton.setToolTipText("Stop reading the frames (Esc).");
        cancelButton.addActionListener(e -> close());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (JComponent component : new JComponent[]{loadLabel, loadBar, loadFileLabel, note, cancelButton}) {
            component.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        column.add(loadLabel);
        column.add(Box.createVerticalStrut(10));
        column.add(loadBar);
        column.add(Box.createVerticalStrut(6));
        column.add(loadFileLabel);
        column.add(Box.createVerticalStrut(18));
        column.add(note);
        column.add(Box.createVerticalStrut(12));
        column.add(cancelButton);
        // Focusable, so that Esc works while nothing else in the window can take the focus.
        loadingPanel.setFocusable(true);
        loadingPanel.add(column);
        return loadingPanel;
    }

    private JComponent buildStatusBar() {
        java.util.List<ViewerSupport.Shortcut> shortcuts = new java.util.ArrayList<>(java.util.List.of(
                new ViewerSupport.Shortcut("Space", "Play or pause"),
                new ViewerSupport.Shortcut("← →", "Previous / next frame of the loop"),
                new ViewerSupport.Shortcut("↑ ↓", "Faster / slower while playing; previous / next frame while paused"),
                new ViewerSupport.Shortcut("S", "Leave the frame out of the loop, or put it back (while paused)"),
                new ViewerSupport.Shortcut("G", "Export GIF: drag a rectangle on the image"),
                new ViewerSupport.Shortcut("Ctrl+E", "Export the selected region as a GIF")));
        shortcuts.addAll(ViewerSupport.commonShortcuts());
        shortcuts.add(new ViewerSupport.Shortcut("Esc", "Stop an export, leave Export GIF, or close the window"));
        return ViewerSupport.statusBar(frameLabel, cursorLabel, getRootPane(), "Blink", shortcuts);
    }

    private void installKeyBindings() {
        bind("SPACE", "playPause", this::togglePlay);
        bind("LEFT", "previous", () -> stepByHand(-1));
        bind("RIGHT", "next", () -> stepByHand(1));
        // While playing, up and down change the speed; while paused, they step through the frames as the list does.
        bind("UP", "fasterOrPrevious", () -> {
            if (timer.isRunning()) speedSlider.setValue(Math.min(SPEED_STEPS, speedSlider.getValue() + SPEED_STEPS / 20));
            else stepByHand(-1);
        });
        bind("DOWN", "slowerOrNext", () -> {
            if (timer.isRunning()) speedSlider.setValue(Math.max(0, speedSlider.getValue() - SPEED_STEPS / 20));
            else stepByHand(1);
        });
        bind("S", "skip", () -> {
            if (open) {
                setSkipped(current, !skipped[current]);
            }
        });
        bind("T", "tracks", () -> toggleFindingLayer(ViewerLayer.TRACKS));
        bind("V", "variables", () -> toggleFindingLayer(ViewerLayer.VARIABLE_STARS));
        bind("A", "annotate", sky::toggle);
        layerState.addListener(layerListener);
        view.setLayers(layerState.snapshot());
        bind("C", "correct", sky::toggleCorrection);
        sky.setOnSolutionChanged(() -> {
            cursorReadout.forgetSolutions();
            updateCursorLabel();
        });
        ViewerSupport.bindZoomKeys(getRootPane(), view);
        bind("G", "selectGifRegion", gifExport::toggleSelection);
        bind("ctrl E", "exportGifRegion", gifExport::chooseFile);
        bind("meta E", "exportGifRegionMac", gifExport::chooseFile);
        bind("ESCAPE", "close", () -> { if (!gifExport.escape()) close(); });
    }

    private void bind(String key, String name, Runnable action) {
        ViewerSupport.bind(getRootPane(), key, name, action);
    }

    // ==========================================
    // PLAYBACK
    // ==========================================

    private void play() {
        if (!open) {
            return;
        }
        resumeOnReturn = false;
        timer.start();
        updatePlayState();
    }

    private void pause() {
        resumeOnReturn = false;
        timer.stop();
        updatePlayState();
    }

    /** Frames can be left out of the loop only while paused. */
    private void updatePlayState() {
        boolean playing = timer.isRunning();
        playButton.setText(playing ? "❚❚ Pause" : "▶ Play");
        framesHint.setText(playing
                ? "Pause (Space) to choose the frames in the loop. Click a frame to show it."
                : "Untick or press S to leave a frame out of the loop. Click a frame to show it.");
        frameTable.repaint();
    }

    /** Pauses and shows this frame. */
    private void goTo(int index) {
        pause();
        current = index;
        showCurrent();
    }

    private void togglePlay() {
        if (timer.isRunning()) {
            pause();
        } else {
            play();
        }
    }

    private void stepByHand(int direction) {
        pause();
        step(direction);
    }

    /** Moves to the next frame of the loop in this direction, passing over the frames left out. */
    private void step(int direction) {
        if (!open) {
            return;
        }
        int count = skipped.length;
        int next = current;
        for (int i = 0; i < count; i++) {
            next = Math.floorMod(next + direction, count);
            if (!skipped[next]) {
                break;
            }
        }
        current = next;
        showCurrent();
    }

    /** Puts a frame in or out of the loop. Ignored while playing; at least two frames stay in. */
    private void setSkipped(int index, boolean skip) {
        if (!open || timer.isRunning() || skipped[index] == skip) {
            return;
        }
        if (skip && includedCount() <= 2) {
            Toolkit.getDefaultToolkit().beep();
            frameLabel.setText(frameText() + " · at least two frames must stay in the loop");
            return;
        }
        skipped[index] = skip;
        frameModel.fireTableRowsUpdated(index, index);
        timeline.loopChanged();
        updateLoopCount();
        if (index == current) {
            frameLabel.setText(frameText());
        }
    }

    private void updateLoopCount() {
        loopCount.setText(String.format(Locale.US, "%d of %d in loop", includedCount(), skipped.length));
    }

    private int includedCount() {
        int included = 0;
        for (boolean skip : skipped) {
            if (!skip) {
                included++;
            }
        }
        return included;
    }

    private void speedChanged() {
        int millis = speedMillis();
        timer.setDelay(millis);
        timer.setInitialDelay(millis);
        double fps = 1000.0 / millis;
        speedLabel.setText(String.format(Locale.US, fps < 10 ? "%.1f fps · %d ms" : "%.0f fps · %d ms", fps, millis));
        try {
            Preferences.userNodeForPackage(BlinkFrame.class).putInt(SPEED_PREFERENCE, millis);
        } catch (Exception ignored) {
            // The speed is a convenience; not remembering it is harmless.
        }
    }

    /** The slider runs on a logarithmic scale from {@link #MIN_MILLIS} to {@link #MAX_MILLIS}, fastest on the right. */
    private int speedMillis() {
        double fraction = (SPEED_STEPS - speedSlider.getValue()) / (double) SPEED_STEPS;
        return (int) Math.round(MIN_MILLIS * Math.pow((double) MAX_MILLIS / MIN_MILLIS, fraction));
    }

    private void setSpeedMillis(int millis) {
        double fraction = Math.log((double) millis / MIN_MILLIS) / Math.log((double) MAX_MILLIS / MIN_MILLIS);
        speedSlider.setValue((int) Math.round(SPEED_STEPS * (1 - Math.max(0, Math.min(1, fraction)))));
        speedChanged();
    }

    private static int savedSpeedMillis() {
        try {
            int millis = Preferences.userNodeForPackage(BlinkFrame.class).getInt(SPEED_PREFERENCE, DEFAULT_MILLIS);
            return Math.max(MIN_MILLIS, Math.min(MAX_MILLIS, millis));
        } catch (Exception e) {
            return DEFAULT_MILLIS;
        }
    }

    // ==========================================
    // DISPLAY
    // ==========================================

    private void showCurrent() {
        if (!open) {
            return;
        }
        BlinkSequence.Frame frame = sequence.getFrames().get(current);
        view.showFrame(frame, stretch.tablesFor(frame), stretch.extremeColour(frame));
        sky.showFrame(frame.getInfo());
        String offset = offsetText(current);
        String place = (current + 1) + " / " + skipped.length + (offset.isEmpty() ? "" : " · " + offset);
        positionLabel.setText(place);
        view.setCaption(place);
        timeline.setCurrent(current);
        frameLabel.setText(frameText());
        updateCursorLabel();
        frameTable.repaint();
        frameTable.scrollRectToVisible(frameTable.getCellRect(current, 0, true));
    }

    /** Captures the included loop in viewing order and freezes per-frame catalogue projections on the EDT. */
    private BlinkGifExport.Source captureGifSource() {
        if (!open || sequence == null || stretch == null) throw new IllegalStateException("No Blink sequence is loaded.");
        List<BlinkGifExport.FrameInput> frames = new java.util.ArrayList<>();
        for (int index = 0; index < skipped.length; index++) if (!skipped[index]) {
            BlinkSequence.Frame frame = sequence.getFrames().get(index);
            frames.add(new BlinkGifExport.FrameInput(frame, sky.exportMarks(frame.getInfo())));
        }
        return new BlinkGifExport.Source(frames, stretch.snapshot(), speedMillis());
    }

    /** "Frame 7 of 20 · name · time · +12 min 30 s", and whether the frame is skipped. */
    private String frameText() {
        List<BlinkSequence.Frame> frames = sequence.getFrames();
        String text = ViewerSupport.frameText(current, frames.size(), frames.get(current).getInfo(), frames.get(0).getInfo());
        return skipped[current] ? text + " · skipped" : text;
    }

    private void updateCursorLabel() {
        cursorLabel.setText(open ? cursorReadout.describe(sequence.getFrames().get(current), cursorPixel) + sky.describe(cursorPixel) : " ");
    }

    /** Time since the first frame, "+12:30" or "+1:02:03"; empty when a capture time is missing. */
    private String offsetText(int index) {
        long start = sequence.getFrames().get(0).getInfo().getObservationTimestamp();
        long timestamp = sequence.getFrames().get(index).getInfo().getObservationTimestamp();
        if (start <= 0 || timestamp <= 0) {
            return "";
        }
        long seconds = Math.round((timestamp - start) / 1000.0);
        long abs = Math.abs(seconds);
        String time = abs >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", abs / 3600, abs / 60 % 60, abs % 60)
                : String.format(Locale.US, "%d:%02d", abs / 60, abs % 60);
        return (seconds < 0 ? "−" : "+") + time;
    }

    // ==========================================
    // FRAME LIST
    // ==========================================

    private final class FrameTableModel extends AbstractTableModel {
        private final String[] names = {"In loop", "#", "Frame", "Time"};

        @Override
        public int getRowCount() {
            return sequence == null ? 0 : sequence.getFrames().size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int column) {
            return names[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : column == 1 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            switch (column) {
                case 0:
                    return !skipped[row];
                case 1:
                    return row + 1;
                case 2:
                    return frameName(row);
                default:
                    return offsetText(row);
            }
        }
    }

    /** The row of the frame shown is highlighted; the table's own selection is not used. */
    private void paintRow(JComponent cell, JTable table, int row) {
        boolean shown = row == current;
        cell.setBackground(shown ? UIManager.getColor("Table.selectionBackground") : table.getBackground());
        cell.setForeground(shown ? UIManager.getColor("Table.selectionForeground") : table.getForeground());
    }

    /** Room for the gap band above a frame that follows a gap; the band itself is painted by the table. */
    private javax.swing.border.Border cellBorder(int row, int horizontal) {
        int top = gapBefore != null && row < gapBefore.length && gapBefore[row] ? gapBand() : 0;
        return new EmptyBorder(top, horizontal, 0, horizontal);
    }

    private final class LoopCellRenderer extends JCheckBox implements TableCellRenderer {
        LoopCellRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorderPainted(true);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            boolean playing = timer.isRunning();
            setSelected(Boolean.TRUE.equals(value));
            setEnabled(!playing);
            paintRow(this, table, row);
            setBorder(cellBorder(row, 0));
            setToolTipText(playing ? "Pause (Space) to change the frames in the loop."
                    : "Untick to leave this frame out of the loop (S).");
            return this;
        }
    }

    private final class TextCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setHorizontalAlignment(column == 2 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            setVerticalAlignment(SwingConstants.CENTER);
            setBorder(cellBorder(row, 6));
            paintRow(this, table, row);
            boolean excluded = skipped != null && row < skipped.length && skipped[row];
            if (row != current && excluded) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            // Struck through while out of the loop; the text is escaped as it is put in HTML.
            if (excluded && value != null) {
                setText("<html><s>" + value.toString().replace("&", "&amp;").replace("<", "&lt;") + "</s></html>");
            }
            if (column == 2 && value != null) {
                String shown = elideStart(value.toString(), table.getColumnModel().getColumn(2).getWidth() - UiStyle.space(14));
                setText(excluded ? "<html><s>" + shown.replace("&", "&amp;").replace("<", "&lt;") + "</s></html>" : shown);
            }
            setToolTipText(column == 2 ? frameName(row) + (excluded ? " (not in the loop)" : "") : null);
            return this;
        }

        /** Keeps the end of a name that does not fit, where the frame numbers are: "…00017_mono". */
        private String elideStart(String text, int width) {
            FontMetrics metrics = getFontMetrics(getFont());
            if (width <= 0 || metrics.stringWidth(text) <= width) return text;
            for (int start = 1; start < text.length(); start++) {
                String tail = "…" + text.substring(start);
                if (metrics.stringWidth(tail) <= width) return tail;
            }
            return "…";
        }
    }

    /** The frame table, with a band above each frame that follows a gap in the capture times. */
    private final class FrameTable extends JTable {
        FrameTable() {
            super(frameModel);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (gapBefore == null || sequence == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(UiStyle.bodyFont().deriveFont(UiStyle.bodyFont().getSize2D() - 1f));
                FontMetrics metrics = g2.getFontMetrics();
                for (int row = 1; row < gapBefore.length && row < getRowCount(); row++) {
                    if (!gapBefore[row]) continue;
                    Rectangle cell = getCellRect(row, 0, true);
                    int band = gapBand();
                    int inset = UiStyle.space(4);
                    java.awt.geom.RoundRectangle2D.Double box = new java.awt.geom.RoundRectangle2D.Double(
                            inset, cell.y + 2, getWidth() - 2.0 * inset, band - 4, 6, 6);
                    g2.setColor(getBackground());
                    g2.fillRect(0, cell.y, getWidth(), band);
                    g2.setColor(new Color(BlinkTimeline.GAP_COLOUR.getRed(), BlinkTimeline.GAP_COLOUR.getGreen(),
                            BlinkTimeline.GAP_COLOUR.getBlue(), 40));
                    g2.fill(box);
                    g2.setColor(BlinkTimeline.GAP_COLOUR);
                    g2.draw(box);
                    String text = gapText(row);
                    g2.drawString(text, (getWidth() - metrics.stringWidth(text)) / 2f,
                            cell.y + (band + metrics.getAscent() - metrics.getDescent()) / 2f);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /** "5 min 17 s gap": the interval from the previous frame. */
    private String gapText(int row) {
        long previous = sequence.getFrames().get(row - 1).getInfo().getObservationTimestamp();
        long here = sequence.getFrames().get(row).getInfo().getObservationTimestamp();
        return ViewerSupport.formatElapsed(Math.round((here - previous) / 1000.0)) + " gap";
    }

    /** The file name of a frame without its extension, for the list. */
    private String frameName(int row) {
        String name = sequence.getFrames().get(row).getInfo().getFileName();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : String.valueOf(name);
    }
}
