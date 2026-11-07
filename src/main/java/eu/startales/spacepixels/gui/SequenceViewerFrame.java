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

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.DetectedObject;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.detection.VariableStarOverlay;
import io.github.ppissias.jtransient.execution.CancellationScope;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * A window that steps through frames, laid out like the blink window: controls and the shared stretch on top, the
 * zoomable frame in the middle, the frame and cursor in the status bar. Each frame is prepared in the background by
 * the subclass; while it is, the window says so and keeps the previous frame. ← and → step. A window that shows
 * detections has a "Show detections" switch (D), and can show the tracks (T) and variable-star candidates (V) of the
 * last detection run. Every window has the Annotate switch (A) for the sky catalogue.
 */
abstract class SequenceViewerFrame extends JFrame implements SessionViewer {

    /** What the window shows for one frame. */
    static final class Result {
        final BlinkSequence.Frame frame;
        final List<DetectedObject> detections;
        /** Appended to the frame description, for example "812 stars · 2 streaks". */
        final String summary;
        /** Why the frame cannot be shown, or null. */
        final String problem;

        private Result(BlinkSequence.Frame frame, List<DetectedObject> detections, String summary, String problem) {
            this.frame = frame;
            this.detections = detections;
            this.summary = summary;
            this.problem = problem;
        }

        static Result of(BlinkSequence.Frame frame, List<DetectedObject> detections, String summary) {
            return new Result(frame, detections, summary, null);
        }

        static Result problem(String problem) {
            return new Result(null, null, null, problem);
        }
    }

    private final FrameView view = new FrameView();
    private final JButton previousButton = new JButton("◀");
    private final JButton nextButton = new JButton("▶");
    private final JTextField frameField = new JTextField(3);
    private final JLabel frameCountLabel = new JLabel("of 0");
    /** Toolbar buttons a viewer adds on the right, before the side panel's button. */
    private final JPanel toolbarExtras = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(6), 0));
    private RawDetectionsSection rawDetections;
    private final ViewerLayerState layerState;
    /** Kept here: the shared state holds its listeners weakly. */
    private final Runnable layerListener = this::layersChanged;
    private final FindingsSection findings;
    private final ViewerSidePanel sidePanel = new ViewerSidePanel();
    /** Holds the Display stretch section, which is made when the viewer first opens with the Image Stretch tab. */
    private final JPanel stretchHolder = new JPanel(new BorderLayout());
    private final JPanel controlRows = new JPanel();
    private final JLabel frameLabel = new JLabel(" ");
    private final JLabel cursorLabel = new JLabel(" ");
    private final ViewerSupport.CursorReadout cursorReadout = new ViewerSupport.CursorReadout();
    private final SkyAnnotations sky;
    private final String windowName;
    private final String busyText;
    private final boolean disposeOnClose;
    private final boolean showsDetections;

    private StretchControls stretch;
    private boolean open;
    private boolean cleanedUp = true;
    private int current;
    /** The frame whose result is shown, or -1. */
    private int shownIndex = -1;
    private Result shown;
    private SwingWorker<Result, Void> worker;
    /** Counts the refreshes asked for, so a frame prepared before the last one is prepared again. */
    private int refreshes;
    private Point cursorPixel;

    /**
     * @param windowName     the start of the title, for example "Preview Frame"
     * @param busyText       shown while a frame is prepared, for example "detecting…"
     * @param disposeOnClose whether closing disposes the window (else it hides and is opened again later)
     * @param showsDetections whether the frames come with detections, which get a "Show detections" switch
     * @param layers          the overlay layers shared by the application's viewers
     */
    SequenceViewerFrame(String windowName, String busyText, boolean disposeOnClose, boolean showsDetections,
                        ViewerLayerState layers) {
        this.layerState = layers;
        this.sky = new SkyAnnotations(view, layers);
        this.findings = new FindingsSection(layers);
        this.windowName = windowName;
        this.busyText = busyText;
        this.disposeOnClose = disposeOnClose;
        this.showsDetections = showsDetections;
        setTitle(windowName);
        AppIcon.apply(this);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(null);

        JPanel contentPane = new JPanel(new BorderLayout(0, 6));
        contentPane.setBorder(new EmptyBorder(6, 6, 6, 6));
        setContentPane(contentPane);
        JComponent controls = buildControls();
        java.util.List<ViewerSupport.Shortcut> shortcuts = new java.util.ArrayList<>();
        shortcuts.add(new ViewerSupport.Shortcut("← →", "Previous / next frame"));
        if (showsDetections) shortcuts.add(new ViewerSupport.Shortcut("D", "Show or hide the raw detections"));
        shortcuts.addAll(ViewerSupport.commonShortcuts());
        shortcuts.add(new ViewerSupport.Shortcut("Esc", "Close the window (from the image)"));
        JComponent statusBar = ViewerSupport.statusBar(frameLabel, cursorLabel, getRootPane(), windowName, shortcuts);
        findings.section().setVisible(false);
        stretchHolder.setOpaque(false);
        sidePanel.add(findings.section());
        sidePanel.add(sky.section());
        sidePanel.add(stretchHolder);
        ViewerSupport.prepareControls(sidePanel.component());
        JPanel centre = new JPanel(new BorderLayout());
        centre.add(view, BorderLayout.CENTER);
        centre.add(sidePanel.component(), BorderLayout.EAST);
        contentPane.add(WindowSizing.viewerControls(controls), BorderLayout.NORTH);
        contentPane.add(centre, BorderLayout.CENTER);
        contentPane.add(statusBar, BorderLayout.SOUTH);
        view.setMinimumSize(new Dimension(UiStyle.space(240), UiStyle.space(160)));
        WindowSizing.install(this, 600, 380);

        ViewerSupport.prepareControls(controls);
        ViewerSupport.prepareControls(statusBar);
        view.getAccessibleContext().setAccessibleName(windowName + " image");
        view.getAccessibleContext().setAccessibleDescription("Arrow keys step frames while this image has focus. "
                + "Tab reaches controls; Escape from a control returns here, and Escape here closes the viewer.");
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

        ViewerSupport.bind(getRootPane(), "LEFT", "previous", () -> step(-1));
        ViewerSupport.bind(getRootPane(), "RIGHT", "next", () -> step(1));
        if (showsDetections) {
            ViewerSupport.bind(getRootPane(), "D", "detections", () -> layerState.toggleVisible(ViewerLayer.RAW_DETECTIONS));
        }
        ViewerSupport.bind(getRootPane(), "A", "annotate", sky::toggle);
        ViewerSupport.bind(getRootPane(), "C", "correct", sky::toggleCorrection);
        ViewerSupport.bind(getRootPane(), "T", "tracks", () -> toggleFindingLayer(ViewerLayer.TRACKS));
        ViewerSupport.bind(getRootPane(), "V", "variables", () -> toggleFindingLayer(ViewerLayer.VARIABLE_STARS));
        layerState.addListener(layerListener);
        view.setLayers(layerState.snapshot());
        sky.setOnSolutionChanged(() -> {
            cursorReadout.forgetSolutions();
            updateCursorLabel();
        });
        ViewerSupport.bindZoomKeys(getRootPane(), view);
        ViewerSupport.bind(getRootPane(), "ESCAPE", "close", this::close);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
    }

    /** A key switches a layer of the last run only while the viewer has it to show. */
    private void toggleFindingLayer(ViewerLayer layer) {
        if (findings.section().isVisible() && findings.isAvailable(layer)) {
            layerState.toggleVisible(layer);
        }
    }

    /** Follows the shared layer state, which this or another viewer changed. */
    private void layersChanged() {
        view.setLayers(layerState.snapshot());
        if (rawDetections != null) rawDetections.refresh();
        findings.refresh();
        sky.layersChanged();
        updateCursorLabel();
    }

    /** The number of frames to step through. */
    protected abstract int frameCount();

    /** The file of this frame, for the status bar while it is prepared. */
    protected abstract FitsFileInformation infoOf(int index);

    /** Prepares a frame for display; runs in the background. */
    protected abstract Result prepare(int index) throws Exception;

    /** Called on the Swing thread just before a frame is prepared, to take what {@link #prepare} needs. */
    protected void preparing() {
    }

    /** Called when the window closes, to let go of what the subclass holds. */
    protected void released() {
    }

    /** Hook for subclasses to replace {@code previous} with {@code updated} in their frame array or map. */
    protected void onFrameMetadataReplaced(FitsFileInformation previous, FitsFileInformation updated) {
    }

    /**
     * Updates this viewer's frame metadata after a session WCS write, refreshing the shown frame, sky annotations
     * and cursor readout when the viewer is open. A frame being prepared meanwhile takes the new metadata when it is
     * shown ({@link #show}). Both descriptors must name the same file; anything else is ignored.
     */
    public final void replaceFrameMetadata(FitsFileInformation previous, FitsFileInformation updated) {
        if (previous == null || updated == null || previous.getFilePath() == null
                || !previous.getFilePath().equals(updated.getFilePath())) {
            return;
        }
        onFrameMetadataReplaced(previous, updated);
        if (!open) {
            return;
        }
        if (shown != null && shown.frame != null && shownIndex >= 0 && shownIndex < frameCount()) {
            FitsFileInformation currentInfo = infoOf(shownIndex);
            if (currentInfo != null && shown.frame.getInfo() != currentInfo) {
                shown = Result.of(shown.frame.withInfo(currentInfo), shown.detections, shown.summary);
            }
            frameLabel.setText(frameText(shownIndex, shown.summary));
        }
        sky.replaceFrame(previous, updated);
    }

    /**
     * Shows the tracks of the last detection run on this session, as Blink does: the positions measured by that run,
     * drawn on the frames they were measured in. Nothing is detected again. Call it after {@link #openAt}.
     */
    protected void setTrackOverlay(TrackOverlay overlay, FitsFileInformation[] files) {
        Set<String> names = new HashSet<>();
        if (files != null) {
            for (FitsFileInformation file : files) {
                if (file != null && file.getFilePath() != null) {
                    names.add(new File(file.getFilePath()).getName());
                }
            }
        }
        boolean available = overlay != null && overlay.coversAnyOf(names);
        findings.section().setVisible(true);
        findings.setTracks(overlay, available);
        view.setOverlay(available ? overlay : null);
    }

    /** Preview can show candidates from the last full run, independently of single-frame extraction. */
    protected void setVariableStarOverlay(VariableStarOverlay overlay, FitsFileInformation[] files) {
        boolean available = overlay != null && overlay.coversAnyOf(files);
        findings.section().setVisible(true);
        findings.setVariableStars(overlay, available);
        view.setVariableStarOverlay(available ? overlay : null);
    }

    /** Offers the run's experimental fitted paths independently of per-frame detections. */
    protected void setFaintMoverOverlay(eu.startales.spacepixels.detection.FaintMoverOverlay overlay, FitsFileInformation[] files) {
        boolean available = overlay != null && overlay.coversAnyOf(files);
        findings.section().setVisible(true);
        findings.setFaintMovers(overlay, available);
        view.setFaintMoverOverlay(available ? overlay : null);
    }

    /** Offers all additional report categories, with explicit stack/reference markers where measurements are unavailable. */
    protected void setFindingOverlay(eu.startales.spacepixels.detection.FindingOverlay overlay, FitsFileInformation[] files) {
        boolean available = overlay != null && overlay.coversAnyOf(files);
        findings.section().setVisible(true);
        findings.setFindings(overlay, available);
        view.setFindingOverlay(available ? overlay : null);
    }

    // ==========================================
    // OPEN AND CLOSE
    // ==========================================

    /**
     * Adds the Raw detections section at the top of the side panel, with these trial thresholds; for viewers that
     * extract the sources of their frames.
     */
    protected void setRawDetections(TrialSettingsControls trials) {
        rawDetections = new RawDetectionsSection(layerState, trials);
        sidePanel.addFirst(rawDetections.section());
        ViewerSupport.prepareControls(rawDetections.section());
    }

    /** Adds a button to the right end of the toolbar, before the side panel's button. */
    protected void addToolbarButton(AbstractButton button) {
        button.setRequestFocusEnabled(false);
        toolbarExtras.add(button);
    }

    /** The index of the frame shown, or being prepared. */
    protected int currentIndex() {
        return current;
    }

    /** Adds an editable control row under the toolbar; its keys stay with the focused editor. */
    protected void addControlRow(JComponent row) {
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlRows.add(row, 1);
        controlRows.revalidate();
    }

    /** Gives the keyboard focus back to the frame, so the window's keys work again. */
    protected void focusFrame() {
        view.requestFocusInWindow();
    }

    /** Prepares the frame on show again, for example with changed settings; it stays on show until the new one is ready. */
    protected void refresh() {
        if (!open) {
            return;
        }
        refreshes++;
        frameLabel.setText(frameText(current, null) + " · " + busyText);
        prepareCurrent();
    }

    /** Shows the window at this frame. {@code files} are the frames of the session, which can share a plate solution. */
    public void setSkyCatalogueModel(eu.startales.spacepixels.session.SkyCatalogueModel model) {
        sky.bind(model);
    }

    /** Draws detection markers with the sizes {@code settings} gives at each paint: the settings page's values. */
    void setMarkerSettings(java.util.function.Supplier<VisualizationSettings> settings) {
        view.setMarkerSettings(settings);
    }

    @Override
    public void closeForSessionChange() {
        close();
        dispose();
    }

    protected void openAt(int index, StretchPanel stretchPanel, FitsFileInformation[] files) {
        stretch = ViewerSupport.bindStretch(stretch, stretchPanel, this::redraw, stretchHolder);
        cursorReadout.reset(files, sky::solutionFor);
        sky.attach(files);
        open = true;
        cleanedUp = false;
        shown = null;
        shownIndex = -1;
        view.clear();
        view.setLayers(layerState.snapshot());
        FitsFileInformation first = infoOf(0);
        setTitle(windowName + " · " + ViewerSupport.sessionName(first) + " · " + frameCount() + " frames");
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        view.requestFocusInWindow();
        goTo(Math.max(0, Math.min(index, frameCount() - 1)));
    }

    /** Brings the open window to the front on this frame, keeping its zoom and everything else it shows. */
    void showFrame(int index) {
        ViewerSupport.bringToFront(this);
        view.requestFocusInWindow();
        if (open) {
            goTo(Math.max(0, Math.min(index, frameCount() - 1)));
        }
    }

    @Override
    public void dispose() {
        cleanup();
        super.dispose();
    }

    void close() {
        if (!open && cleanedUp) {
            return;
        }
        cleanup();
        if (disposeOnClose) {
            dispose();
        } else {
            setVisible(false);
        }
    }

    private void cleanup() {
        if (cleanedUp && !open && stretch == null && worker == null) {
            return;
        }
        cleanedUp = true;
        open = false;
        if (worker != null) {
            worker.cancel(false);
            worker = null;
        }
        shown = null;
        shownIndex = -1;
        cursorPixel = null;
        cursorReadout.clear();
        sky.detach();
        view.clear();
        if (stretch != null) {
            stretch.detach();
            stretch = null;
        }
        released();
    }

    // ==========================================
    // CONTROLS
    // ==========================================

    private JComponent buildControls() {
        previousButton.setToolTipText("Previous frame (←).");
        previousButton.addActionListener(e -> step(-1));
        nextButton.setToolTipText("Next frame (→).");
        nextButton.addActionListener(e -> step(1));
        frameField.setHorizontalAlignment(SwingConstants.CENTER);
        frameField.setToolTipText("The frame shown; type a number and press Enter to go to it.");
        frameField.getAccessibleContext().setAccessibleName("Frame number");
        frameField.addActionListener(e -> jumpToTypedFrame());
        frameField.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "restoreFrame");
        frameField.getActionMap().put("restoreFrame", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                frameField.setText(String.valueOf(current + 1));
                view.requestFocusInWindow();
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        left.add(previousButton);
        left.add(new JLabel("Frame"));
        left.add(frameField);
        left.add(frameCountLabel);
        left.add(nextButton);
        left.add(ViewerSupport.toolbarSeparator());
        left.add(ViewerSupport.zoomControl(view));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(6), 0));
        right.add(toolbarExtras);
        right.add(sidePanel.toggleButton());
        JPanel row = new JPanel(new BorderLayout());
        row.add(left, BorderLayout.WEST);
        row.add(right, BorderLayout.EAST);

        controlRows.setLayout(new BoxLayout(controlRows, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        controlRows.add(row);
        return controlRows;
    }

    // ==========================================
    // STEPPING
    // ==========================================

    /** Goes to the frame typed in the toolbar; a number out of range goes to the nearest frame. */
    private void jumpToTypedFrame() {
        try {
            int typed = Integer.parseInt(frameField.getText().trim());
            if (open) goTo(Math.max(0, Math.min(frameCount() - 1, typed - 1)));
        } catch (NumberFormatException notANumber) {
            Toolkit.getDefaultToolkit().beep();
        }
        frameField.setText(String.valueOf(current + 1));
        view.requestFocusInWindow();
    }

    /** Moves one frame back or forward; stops at the first and last frame. */
    private void step(int direction) {
        if (open) {
            goTo(Math.max(0, Math.min(frameCount() - 1, current + direction)));
        }
    }

    private void goTo(int index) {
        if (index == current && shownIndex == index) {
            return;
        }
        current = index;
        previousButton.setEnabled(index > 0);
        nextButton.setEnabled(index < frameCount() - 1);
        frameField.setText(String.valueOf(index + 1));
        frameCountLabel.setText("of " + frameCount());
        if (rawDetections != null) rawDetections.setSummary(null);
        frameLabel.setText(frameText(index, null) + " · " + busyText);
        prepareCurrent();
    }

    /**
     * Prepares the frame asked for. One frame is prepared at a time: keys pressed meanwhile only move the target,
     * and the frame then asked for is prepared next. So is the frame on show after a refresh.
     */
    private void prepareCurrent() {
        if (worker != null) {
            return;
        }
        int index = current;
        int refresh = refreshes;
        preparing();
        worker = new SwingWorker<Result, Void>() {
            @Override
            protected Result doInBackground() throws Exception {
                try (CancellationScope cancellation = CancellationScope.open(this::isCancelled)) {
                    CancellationScope.check();
                    Result result = prepare(index);
                    CancellationScope.check();
                    return result;
                }
            }

            @Override
            protected void done() {
                if (worker != this) {
                    return;
                }
                worker = null;
                if (!open || !isDisplayable()) {
                    return;
                }
                if (index != current || refresh != refreshes) {
                    prepareCurrent();
                    return;
                }
                Result result;
                try {
                    result = get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    result = Result.problem(cause instanceof OutOfMemoryError
                            ? "Not enough memory to show this frame."
                            : "Cannot show this frame: " + cause.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                show(index, result);
            }
        };
        worker.execute();
    }

    /** The metadata of the frame on show, or null; for tests of metadata replacement. */
    FitsFileInformation shownFrameInfo() {
        return shown == null || shown.frame == null ? null : shown.frame.getInfo();
    }

    private void show(int index, Result result) {
        if (!open || !isDisplayable()) {
            return;
        }
        // The metadata can have been replaced (a WCS write) while the frame was prepared: show the current one.
        FitsFileInformation currentInfo = result.frame == null ? null : infoOf(index);
        if (currentInfo != null && result.frame.getInfo() != currentInfo && result.frame.getInfo() != null
                && currentInfo.getFilePath() != null
                && currentInfo.getFilePath().equals(result.frame.getInfo().getFilePath())) {
            result = Result.of(result.frame.withInfo(currentInfo), result.detections, result.summary);
        }
        boolean first = shownIndex < 0;
        shown = result;
        shownIndex = index;
        if (result.frame == null) {
            view.showFrame(null, null, false);
            view.setDetections(null);
            sky.showFrame(null);
            view.setPlaceholder(result.problem);
            frameLabel.setText(frameText(index, null));
        } else {
            view.showFrame(result.frame, stretch.tablesFor(result.frame), stretch.extremeColour(result.frame));
            view.setDetections(result.detections);
            sky.showFrame(result.frame.getInfo());
            if (first) {
                view.fit();
            }
            frameLabel.setText(frameText(index, result.summary));
            if (rawDetections != null) rawDetections.setSummary(result.summary);
        }
        updateCursorLabel();
    }

    /** Draws the frame shown again, after the stretch changed. */
    private void redraw() {
        if (open && shown != null && shown.frame != null) {
            view.showFrame(shown.frame, stretch.tablesFor(shown.frame), stretch.extremeColour(shown.frame));
        }
    }

    private String frameText(int index, String summary) {
        FitsFileInformation info = infoOf(index);
        if (info == null) {
            return String.format("Frame %d of %d", index + 1, frameCount());
        }
        String text = ViewerSupport.frameText(index, frameCount(), info, infoOf(0));
        return summary == null ? text : text + " · " + summary;
    }

    private void updateCursorLabel() {
        cursorLabel.setText(open && shown != null
                ? cursorReadout.describe(shown.frame, cursorPixel) + sky.describe(cursorPixel) : " ");
    }
}
