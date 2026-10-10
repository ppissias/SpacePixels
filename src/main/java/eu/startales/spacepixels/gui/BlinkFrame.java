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

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.BlinkFrameClosedEvent;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.DisplayStretch;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.StretchAlgorithm;
import eu.startales.spacepixels.util.TrackOverlay;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Plays the selected frames in turn, the classic way of hunting moving objects by eye. The speed, the display
 * stretch (shared with the Image Stretch tab), the zoom and the frames in the loop can all change while it plays,
 * and the tracks of the last detection run can be drawn on top.
 */
public class BlinkFrame extends JFrame {

    private static final String SPEED_PREFERENCE = "blink.frameMillis";
    private static final int MIN_MILLIS = 50;
    private static final int MAX_MILLIS = 2000;
    private static final int DEFAULT_MILLIS = 500;
    /** Steps of the speed slider; fine enough that a saved speed comes back as itself. */
    private static final int SPEED_STEPS = 1000;

    private final EventBus eventBus;
    private final BlinkView view = new BlinkView();
    private final JButton playButton = new JButton("❚❚ Pause");
    private final JButton previousButton = new JButton("◀");
    private final JButton nextButton = new JButton("▶");
    private final JSlider speedSlider = new JSlider(0, SPEED_STEPS);
    private final JLabel speedLabel = new JLabel();
    private final JCheckBox skipBox = new JCheckBox("Skip this frame");
    private final JCheckBox tracksBox = new JCheckBox("Show tracks");
    private final JPanel stretchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JLabel primaryLabel = new JLabel();
    private final JLabel secondaryLabel = new JLabel();
    private final JLabel frameLabel = new JLabel(" ");
    private final JLabel cursorLabel = new JLabel(" ");
    private final Timer timer;

    private StretchPanel stretchPanel;
    private JComboBox<StretchAlgorithm> algorithmCombo;
    private JSlider primarySlider;
    private JSlider secondarySlider;

    private BlinkSequence sequence;
    private FitsFileInformation[] files;
    private boolean[] skipped;
    private int current;
    private boolean open;
    private boolean adjusting;
    private boolean stretchRefreshPending;
    /** Stretch tables of each frame for the current stretch; cleared when the stretch changes. */
    private final Map<BlinkSequence.Frame, byte[][]> tables = new HashMap<>();
    /** Sky coordinates of each frame, resolved when the cursor first moves over it (null: not solved). */
    private final Map<BlinkSequence.Frame, WcsCoordinateTransformer> skyCoordinates = new HashMap<>();
    private Point cursorPixel;

    public BlinkFrame(EventBus eventBus) {
        this.eventBus = eventBus;
        setTitle("Blink");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(null);

        timer = new Timer(DEFAULT_MILLIS, e -> step(1, true));

        JPanel contentPane = new JPanel(new BorderLayout(0, 6));
        contentPane.setBorder(new EmptyBorder(6, 6, 6, 6));
        setContentPane(contentPane);
        contentPane.add(buildControls(), BorderLayout.NORTH);
        contentPane.add(view, BorderLayout.CENTER);
        contentPane.add(buildStatusBar(), BorderLayout.SOUTH);

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
     * Shows the loaded frames and starts playing. {@code overlay} holds the tracks of the last detection run on this
     * session, or is null.
     */
    void open(BlinkSequence sequence, FitsFileInformation[] files, StretchPanel stretchPanel, TrackOverlay overlay) {
        bindStretch(stretchPanel);
        this.sequence = sequence;
        this.files = files;
        this.skipped = new boolean[sequence.getFrames().size()];
        this.current = 0;
        this.open = true;
        tables.clear();
        skyCoordinates.clear();
        cursorPixel = null;

        Set<String> names = new HashSet<>();
        for (BlinkSequence.Frame frame : sequence.getFrames()) {
            names.add(new File(frame.getInfo().getFilePath()).getName());
        }
        boolean hasTracks = overlay != null && overlay.coversAnyOf(names);
        view.setOverlay(hasTracks ? overlay : null);
        tracksBox.setEnabled(hasTracks);
        if (!hasTracks) {
            tracksBox.setSelected(false);
        }
        tracksBox.setToolTipText(hasTracks
                ? "Draw the tracks of the last detection run, numbered as in the report: T for moving objects, ST for streak tracks (T)."
                : "Run Detect Moving Targets on this session to see its tracks here.");
        view.setOverlayVisible(tracksBox.isSelected());

        File first = new File(sequence.getFrames().get(0).getInfo().getFilePath());
        String session = first.getParentFile() != null ? first.getParentFile().getName() : first.getName();
        setTitle("Blink · " + session + " · " + sequence.getFrames().size() + " frames");
        view.fit();
        showCurrent();
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        play();
    }

    /** Ends the blink: stops, frees the frames and tells the main window. Does nothing when not blinking. */
    void close() {
        if (!open) {
            setVisible(false);
            return;
        }
        open = false;
        timer.stop();
        sequence = null;
        files = null;
        tables.clear();
        skyCoordinates.clear();
        view.clear();
        setVisible(false);
        eventBus.post(new BlinkFrameClosedEvent());
    }

    // ==========================================
    // CONTROLS
    // ==========================================

    private JComponent buildControls() {
        playButton.setToolTipText("Play or pause (Space).");
        playButton.addActionListener(e -> togglePlay());
        previousButton.setToolTipText("Previous frame (←). Stepping pauses the blink.");
        previousButton.addActionListener(e -> stepByHand(-1));
        nextButton.setToolTipText("Next frame (→). Stepping pauses the blink.");
        nextButton.addActionListener(e -> stepByHand(1));

        speedSlider.setToolTipText("Time each frame is shown (↑ faster, ↓ slower).");
        speedSlider.setPreferredSize(new Dimension(160, speedSlider.getPreferredSize().height));
        speedSlider.addChangeListener(e -> speedChanged());
        fixWidth(speedLabel, "2000 ms (20.0 frames/s)");

        skipBox.setToolTipText("Leave this frame out of the loop, for example a cloudy or trailed frame (S). "
                + "Stepping by hand still shows it.");
        skipBox.addActionListener(e -> toggleSkip());
        tracksBox.addActionListener(e -> view.setOverlayVisible(tracksBox.isSelected()));

        JButton fitButton = new JButton("Fit");
        fitButton.setToolTipText("Show the whole frame (F or double-click).");
        fitButton.addActionListener(e -> view.fit());

        JPanel playbackRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        playbackRow.add(playButton);
        playbackRow.add(previousButton);
        playbackRow.add(nextButton);
        playbackRow.add(Box.createHorizontalStrut(10));
        playbackRow.add(new JLabel("Speed:"));
        playbackRow.add(speedSlider);
        playbackRow.add(speedLabel);
        playbackRow.add(Box.createHorizontalStrut(10));
        playbackRow.add(skipBox);
        playbackRow.add(tracksBox);
        playbackRow.add(Box.createHorizontalStrut(10));
        playbackRow.add(new JLabel("Zoom:"));
        playbackRow.add(fitButton);
        for (int percent : new int[]{100, 200, 400}) {
            JButton zoomButton = new JButton(percent + " %");
            zoomButton.setToolTipText(percent == 100 ? "One frame pixel per screen pixel (1)." : "Zoom to " + percent + " %.");
            zoomButton.addActionListener(e -> view.zoomToScale(percent / 100.0));
            playbackRow.add(zoomButton);
        }

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        playbackRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        stretchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        stretchRow.setBorder(new EmptyBorder(4, 0, 0, 0));
        controls.add(playbackRow);
        controls.add(stretchRow);
        return controls;
    }

    private JComponent buildStatusBar() {
        JLabel hint = new JLabel("Space play/pause · ← → step · ↑ ↓ speed · S skip frame · T tracks · F fit · 1 100 % · "
                + "scroll to zoom, drag to pan · Esc closes");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel status = new JPanel(new BorderLayout(12, 2));
        status.add(frameLabel, BorderLayout.CENTER);
        status.add(cursorLabel, BorderLayout.EAST);
        status.add(hint, BorderLayout.SOUTH);
        return status;
    }

    /**
     * The stretch controls use the models of the Image Stretch tab, so both always show the same stretch and a change
     * here is remembered like a change there.
     */
    private void bindStretch(StretchPanel panel) {
        if (stretchPanel == panel) {
            return;
        }
        stretchPanel = panel;
        algorithmCombo = new JComboBox<>(panel.getStretchAlgorithmModel());
        algorithmCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                list.setToolTipText(value == null ? null : StretchPanel.algorithmDescription((StretchAlgorithm) value));
                return c;
            }
        });
        algorithmCombo.setToolTipText("The display stretch, shared with the Image Stretch tab. It only changes how frames look.");
        primarySlider = new JSlider(panel.getStretchSlider().getModel());
        secondarySlider = new JSlider(panel.getStretchIterationsSlider().getModel());
        for (JSlider slider : new JSlider[]{primarySlider, secondarySlider}) {
            slider.setPreferredSize(new Dimension(150, slider.getPreferredSize().height));
            slider.getModel().addChangeListener(e -> scheduleStretchRefresh());
        }
        algorithmCombo.addActionListener(e -> scheduleStretchRefresh());
        JButton resetButton = new JButton("Reset");
        resetButton.setToolTipText("Return the sliders to the defaults of the selected algorithm.");
        resetButton.addActionListener(e -> panel.resetStretchToDefaults());

        stretchRow.removeAll();
        stretchRow.add(new JLabel("Stretch:"));
        stretchRow.add(algorithmCombo);
        stretchRow.add(Box.createHorizontalStrut(8));
        stretchRow.add(primaryLabel);
        stretchRow.add(primarySlider);
        stretchRow.add(Box.createHorizontalStrut(8));
        stretchRow.add(secondaryLabel);
        stretchRow.add(secondarySlider);
        stretchRow.add(resetButton);
        for (Component component : stretchRow.getComponents()) {
            component.setFocusable(false);
        }
        String widestPrimary = "";
        String widestSecondary = "";
        for (StretchAlgorithm algorithm : StretchAlgorithm.values()) {
            widestPrimary = longer(widestPrimary, algorithm.getPrimaryParameterLabel() + ": " + algorithm.getPrimaryMaximum());
            widestSecondary = longer(widestSecondary, algorithm.getSecondaryParameterLabel() + ": " + algorithm.getSecondaryMaximum());
        }
        fixWidth(primaryLabel, widestPrimary);
        fixWidth(secondaryLabel, widestSecondary);
        updateStretchLabels();
        stretchRow.revalidate();
    }

    /** Keeps a label as wide as this text, so the controls next to it do not move when its value changes. */
    private static void fixWidth(JLabel label, String widestText) {
        String text = label.getText();
        label.setText(widestText);
        Dimension size = label.getPreferredSize();
        label.setPreferredSize(new Dimension(size.width + 4, size.height));
        label.setText(text);
    }

    private static String longer(String a, String b) {
        return b.length() > a.length() ? b : a;
    }

    private void installKeyBindings() {
        for (JComponent component : new JComponent[]{playButton, previousButton, nextButton, speedSlider, skipBox, tracksBox}) {
            component.setFocusable(false);
        }
        bind("SPACE", "playPause", this::togglePlay);
        bind("LEFT", "previous", () -> stepByHand(-1));
        bind("RIGHT", "next", () -> stepByHand(1));
        bind("UP", "faster", () -> speedSlider.setValue(Math.min(SPEED_STEPS, speedSlider.getValue() + SPEED_STEPS / 20)));
        bind("DOWN", "slower", () -> speedSlider.setValue(Math.max(0, speedSlider.getValue() - SPEED_STEPS / 20)));
        bind("S", "skip", () -> {
            skipBox.setSelected(!skipBox.isSelected());
            toggleSkip();
        });
        bind("T", "tracks", () -> {
            if (tracksBox.isEnabled()) {
                tracksBox.setSelected(!tracksBox.isSelected());
                view.setOverlayVisible(tracksBox.isSelected());
            }
        });
        bind("F", "fit", view::fit);
        bind("1", "actualSize", () -> view.zoomToScale(1.0));
        bind("ESCAPE", "close", this::close);
    }

    private void bind(String key, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    // ==========================================
    // PLAYBACK
    // ==========================================

    private void play() {
        if (!open) {
            return;
        }
        timer.start();
        playButton.setText("❚❚ Pause");
    }

    private void pause() {
        timer.stop();
        playButton.setText("▶ Play");
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
        step(direction, false);
    }

    /** Moves to the next frame in this direction; while playing, skipped frames are passed over. */
    private void step(int direction, boolean playing) {
        if (!open) {
            return;
        }
        int count = skipped.length;
        int next = current;
        for (int i = 0; i < count; i++) {
            next = Math.floorMod(next + direction, count);
            if (!playing || !skipped[next]) {
                break;
            }
        }
        current = next;
        showCurrent();
    }

    private void toggleSkip() {
        if (!open || adjusting) {
            return;
        }
        boolean skip = skipBox.isSelected();
        if (skip && includedCount() <= 2) {
            Toolkit.getDefaultToolkit().beep();
            skipBox.setSelected(false);
            frameLabel.setText(frameText() + " · at least two frames must stay in the loop");
            return;
        }
        skipped[current] = skip;
        frameLabel.setText(frameText());
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
        speedLabel.setText(String.format(Locale.US, "%d ms (%.1f frames/s)", millis, 1000.0 / millis));
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
        StretchAlgorithm algorithm = stretchPanel.getStretchAlgorithm();
        view.showFrame(frame, tablesFor(frame), algorithm == StretchAlgorithm.EXTREME && frame.getChannelCount() == 3);
        adjusting = true;
        try {
            skipBox.setSelected(skipped[current]);
        } finally {
            adjusting = false;
        }
        frameLabel.setText(frameText());
        updateCursorLabel();
    }

    private byte[][] tablesFor(BlinkSequence.Frame frame) {
        return tables.computeIfAbsent(frame, f -> {
            StretchAlgorithm algorithm = stretchPanel.getStretchAlgorithm();
            int primary = stretchPanel.getStretchSlider().getValue();
            int secondary = stretchPanel.getStretchIterationsSlider().getValue();
            byte[][] channelTables = new byte[f.getChannelCount()][];
            for (int channel = 0; channel < channelTables.length; channel++) {
                channelTables[channel] = DisplayStretch.lookupTable(f.getHistogram(channel), algorithm, primary, secondary);
            }
            return channelTables;
        });
    }

    /** Redraws once after a burst of stretch changes (a slider drag, or an algorithm switch that moves the sliders). */
    private void scheduleStretchRefresh() {
        if (stretchRefreshPending) {
            return;
        }
        stretchRefreshPending = true;
        EventQueue.invokeLater(() -> {
            stretchRefreshPending = false;
            tables.clear();
            updateStretchLabels();
            showCurrent();
        });
    }

    private void updateStretchLabels() {
        StretchAlgorithm algorithm = stretchPanel == null ? null : stretchPanel.getStretchAlgorithm();
        if (algorithm == null) {
            return;
        }
        primaryLabel.setText(algorithm.getPrimaryParameterLabel() + ": " + primarySlider.getValue());
        secondaryLabel.setText(algorithm.getSecondaryParameterLabel() + ": " + secondarySlider.getValue());
    }

    /** "Frame 7 of 20 · name · time · +12 min 30 s", and whether the frame is skipped. */
    private String frameText() {
        List<BlinkSequence.Frame> frames = sequence.getFrames();
        FitsFileInformation info = frames.get(current).getInfo();
        StringBuilder text = new StringBuilder(String.format(Locale.US, "Frame %d of %d · %s", current + 1, frames.size(), info.getFileName()));
        long timestamp = info.getObservationTimestamp();
        if (timestamp > 0) {
            text.append(" · ").append(info.getObservationDate());
            long start = frames.get(0).getInfo().getObservationTimestamp();
            if (start > 0 && current > 0) {
                long seconds = Math.round((timestamp - start) / 1000.0);
                text.append(seconds >= 0 ? " · +" : " · −").append(formatElapsed(Math.abs(seconds)));
            }
        }
        if (skipped[current]) {
            text.append(" · skipped");
        }
        return text.toString();
    }

    static String formatElapsed(long seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min " + (seconds % 60) + " s";
        }
        return (minutes / 60) + " h " + (minutes % 60) + " min";
    }

    private void updateCursorLabel() {
        if (!open || cursorPixel == null) {
            cursorLabel.setText(" ");
            return;
        }
        BlinkSequence.Frame frame = sequence.getFrames().get(current);
        if (cursorPixel.x >= frame.getWidth() || cursorPixel.y >= frame.getHeight()) {
            cursorLabel.setText(" ");
            return;
        }
        StringBuilder text = new StringBuilder(String.format(Locale.US, "x %d  y %d  value %d",
                cursorPixel.x, cursorPixel.y, frame.valueAt(cursorPixel.x, cursorPixel.y)));
        WcsCoordinateTransformer transformer = skyCoordinatesOf(frame);
        if (transformer != null) {
            WcsCoordinateTransformer.SkyCoordinate sky = transformer.pixelToSky(cursorPixel.x, cursorPixel.y);
            if (sky != null) {
                text.append("  RA ").append(WcsCoordinateTransformer.formatRa(sky.getRaDegrees()))
                        .append("  Dec ").append(WcsCoordinateTransformer.formatDec(sky.getDecDegrees()));
            }
        }
        cursorLabel.setText(text.toString());
    }

    private WcsCoordinateTransformer skyCoordinatesOf(BlinkSequence.Frame frame) {
        if (!skyCoordinates.containsKey(frame)) {
            WcsCoordinateTransformer transformer = null;
            try {
                WcsSolutionResolver.ResolvedWcsSolution solution = WcsSolutionResolver.resolve(frame.getInfo(), files);
                transformer = solution != null ? solution.getTransformer() : null;
            } catch (Exception ignored) {
                // No usable plate solution: the cursor shows pixel coordinates only.
            }
            skyCoordinates.put(frame, transformer);
        }
        return skyCoordinates.get(frame);
    }
}
