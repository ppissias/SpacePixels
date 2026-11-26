package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.mpc.FindOrb;
import eu.startales.spacepixels.mpc.OrbitGeometry;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Hashtable;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Resizable, interactive 3D orbit viewer dialog opened from {@link AsteroidsCometsWindow}. Hosts {@link OrbitCanvas3D}
 * alongside a sidebar with orbital elements, camera presets and display switches, and a bottom time-animation bar with
 * play/pause, direction, speed, interactive scrubbing, epoch snap-back and high-resolution PNG export. Owns its
 * Swing animation {@link Timer} and stops it whenever idle or when the dialog is disposed.
 */
final class OrbitViewerDialog extends JDialog {
    /** A selectable playback speed in simulated days per real second. */
    private record SpeedOption(String label, double daysPerSecond) {
        @Override public String toString() { return label; }
    }

    /** Callback used to write exported PNG files off the EDT. */
    @FunctionalInterface
    interface AsyncFileWriter {
        void write(FileWriteAction action, Consumer<String> onDone);
    }

    /** One blocking file-writing step executed on a worker thread. */
    @FunctionalInterface
    interface FileWriteAction {
        Object write() throws Exception;
    }

    private static final SpeedOption[] SPEEDS = {
            new SpeedOption("10 min / s", 10.0 / 1440.0),
            new SpeedOption("1 hr / s", 1.0 / 24.0),
            new SpeedOption("6 hr / s", 0.25),
            new SpeedOption("1 day / s", 1.0),
            new SpeedOption("7 days / s", 7.0),
            new SpeedOption("30 days / s", 30.0),
            new SpeedOption("90 days / s", 90.0),
            new SpeedOption("1 yr / s", 365.25)
    };

    private static final int SLIDER_STEPS = 2000;
    private static final int FRAME_INTERVAL_MS = 16;

    private final File defaultSaveFolder;
    private final AsyncFileWriter fileWriter;
    private final Consumer<String> statusSink;

    private final OrbitCanvas3D canvas = new OrbitCanvas3D();
    private final WrappingHelpLabel elementsLabel = new WrappingHelpLabel(" ", 305);
    private final JComboBox<OrbitCanvas3D.FocusMode> focusCombo = new JComboBox<>(OrbitCanvas3D.FocusMode.values());
    private final JCheckBox gridCheck = new JCheckBox("Ecliptic grid & height stems", true);
    private final JCheckBox labelsCheck = new JCheckBox("Planet & orbit labels", true);
    private final JCheckBox sightlineCheck = new JCheckBox("Earth–object sightline", true);
    private final JCheckBox trailCheck = new JCheckBox("Motion trail", true);
    private final JCheckBox singleNightCheck = new JCheckBox("Compare this night alone", true);
    private final JCheckBox cloudCheck = new JCheckBox("Uncertainty cloud", true);

    private final JToggleButton playButton = new JToggleButton("Play");
    private final JToggleButton reverseButton = new JToggleButton("Rev");
    private final JButton stepBackButton = new JButton("‹");
    private final JButton stepForwardButton = new JButton("›");
    private final JComboBox<SpeedOption> speedCombo = new JComboBox<>(SPEEDS);
    private final JSlider timeSlider = new JSlider(0, SLIDER_STEPS, SLIDER_STEPS / 2);
    private final JButton epochButton = new JButton("Epoch");
    private final JButton savePictureButton = new JButton("Save picture…");

    private final Timer animationTimer;
    private long lastTickNanos;
    private boolean updatingSlider;

    private String currentId = "";
    private FindOrb.Orbit currentOrbit;
    private double sliderMinJd;
    private double sliderMaxJd;
    private double sliderHalfSpanDays = 180.0;

    /**
     * Creates the modeless 3D orbit viewer window owned by {@code owner}. Must be constructed on the EDT.
     */
    OrbitViewerDialog(Window owner, File defaultSaveFolder, AsyncFileWriter fileWriter, Consumer<String> statusSink) {
        super(owner, "Orbit", ModalityType.MODELESS);
        AppIcon.apply(this);
        this.defaultSaveFolder = defaultSaveFolder;
        this.fileWriter = fileWriter;
        this.statusSink = statusSink == null ? ignored -> { } : statusSink;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        animationTimer = new Timer(FRAME_INTERVAL_MS, e -> onAnimationTick());
        animationTimer.setCoalesce(true);
        canvas.setOnCameraChanged(this::ensureTimerRunning);

        JPanel content = new JPanel(new BorderLayout(UiStyle.space(8), UiStyle.space(8)));
        content.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(8), UiStyle.space(8), UiStyle.space(8), UiStyle.space(8)));
        content.add(canvas, BorderLayout.CENTER);
        content.add(buildSidebar(), BorderLayout.EAST);
        content.add(buildAnimationBar(), BorderLayout.SOUTH);
        setContentPane(content);
        installKeyboardShortcuts();

        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(UiStyle.space(1060), screen.width - 80), Math.min(UiStyle.space(700), screen.height - 80));
        WindowSizing.install(this, 760, 500);
        setLocationRelativeTo(owner);
    }

    /** Updates the viewer in place without a sample uncertainty cloud. */
    void setOrbit(String id, String displayLabel, FindOrb.Orbit orbit, FindOrb.Orbit alone, int nights, String elementsHtml) {
        setOrbit(id, displayLabel, orbit, alone, java.util.List.of(), nights, elementsHtml);
    }

    /**
     * Updates the viewer in place with a fitted orbit, optional single-night comparison orbit and optional sample
     * orbits for the 3D uncertainty cloud, resetting the timeline to the new orbit's epoch. Must be called on the EDT.
     */
    void setOrbit(String id, String displayLabel, FindOrb.Orbit orbit, FindOrb.Orbit alone,
                  java.util.List<FindOrb.Orbit> samples, int nights, String elementsHtml) {
        boolean sameObject = id != null && id.equals(currentId) && currentOrbit != null;
        this.currentId = id == null ? "" : id;
        this.currentOrbit = orbit;
        setTitle("Orbit · " + (displayLabel == null || displayLabel.isBlank() ? currentId : displayLabel));
        stopPlayback();
        elementsLabel.setHtml(elementsHtml == null ? " " : elementsHtml);
        singleNightCheck.setVisible(alone != null);
        singleNightCheck.setEnabled(alone != null);
        if (alone != null) {
            singleNightCheck.setSelected(true);
            canvas.setShowSingleNight(true);
        }
        boolean hasCloud = samples != null && !samples.isEmpty();
        cloudCheck.setVisible(hasCloud);
        cloudCheck.setEnabled(hasCloud);
        if (hasCloud) {
            cloudCheck.setText("Uncertainty cloud (" + samples.size() + " samples)");
            cloudCheck.setSelected(true);
            canvas.setShowUncertaintyCloud(true);
        }

        configureTimelineFor(orbit);
        boolean canAnimate = orbit != null && OrbitGeometry.positionAt(orbit, orbit.epochJd()) != null;
        setAnimationControlsEnabled(canAnimate);
        canvas.setOrbit(displayLabel == null || displayLabel.isBlank() ? currentId : displayLabel,
                orbit, alone, samples, nights, !sameObject);
        if (!sameObject) {
            focusCombo.setSelectedItem(OrbitCanvas3D.FocusMode.SUN);
        }
        revalidate();
        repaint();
    }

    /** Exposes the 3D canvas for testing and direct snapshot verification. */
    OrbitCanvas3D canvas() { return canvas; }

    /** Whether time playback is currently running. */
    boolean isPlaying() { return playButton.isSelected(); }

    /** Whether the internal Swing animation timer is currently active. */
    boolean isTimerRunning() { return animationTimer.isRunning(); }

    /** Programmatically starts or pauses time playback (EDT only). */
    void setPlaying(boolean play) {
        if (!playButton.isEnabled()) play = false;
        playButton.setSelected(play);
        playButton.setText(play ? "Pause" : "Play");
        if (play) {
            lastTickNanos = System.nanoTime();
            ensureTimerRunning();
        } else if (!canvas.camera().isTransitioning()) {
            animationTimer.stop();
        }
    }

    @Override
    public void dispose() {
        animationTimer.stop();
        super.dispose();
    }

    private JComponent buildSidebar() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(0, UiStyle.space(4), 0, UiStyle.space(4)));

        side.add(sectionTitle("Orbital elements"));
        elementsLabel.setForeground(UIManager.getColor("Label.foreground"));
        elementsLabel.setAlignmentX(LEFT_ALIGNMENT);
        side.add(elementsLabel);

        side.add(Box.createVerticalStrut(UiStyle.space(10)));
        side.add(separator());
        side.add(Box.createVerticalStrut(UiStyle.space(6)));
        side.add(sectionTitle("Camera"));

        JButton view3d = new JButton("3D");
        view3d.setToolTipText("Angled 3D perspective above the ecliptic (1)");
        view3d.addActionListener(e -> canvas.animateTo3DView());
        JButton viewTop = new JButton("Top");
        viewTop.setToolTipText("North ecliptic pole view (2)");
        viewTop.addActionListener(e -> canvas.animateToTopView());
        JButton viewEdge = new JButton("Edge-on");
        viewEdge.setToolTipText("Edge-on to the ecliptic plane to inspect inclination (3)");
        viewEdge.addActionListener(e -> canvas.animateToEdgeOnView());
        JButton resetView = new JButton("Reset");
        resetView.setToolTipText("Reset angle, zoom and pan (R or double-click)");
        resetView.addActionListener(e -> {
            focusCombo.setSelectedItem(OrbitCanvas3D.FocusMode.SUN);
            canvas.setFocusMode(OrbitCanvas3D.FocusMode.SUN);
            canvas.animateResetView();
        });
        JPanel presets = flowRow(view3d, viewTop, viewEdge, resetView);
        side.add(presets);

        JButton zoomOut = new JButton("−");
        zoomOut.setToolTipText("Zoom out (-)");
        zoomOut.addActionListener(e -> canvas.zoomStep(false));
        JButton zoomIn = new JButton("+");
        zoomIn.setToolTipText("Zoom in (+)");
        zoomIn.addActionListener(e -> canvas.zoomStep(true));
        JButton fitZoom = new JButton("Fit orbit");
        fitZoom.setToolTipText("Fit zoom to the orbit without changing angle");
        fitZoom.addActionListener(e -> canvas.animateFitZoom());
        side.add(Box.createVerticalStrut(UiStyle.space(4)));
        side.add(flowRow(new JLabel("Zoom"), zoomOut, zoomIn, fitZoom));

        focusCombo.setToolTipText("Point the 3D camera follows during animation: Sun, Object, or Earth (ideal for NEO flybys)");
        focusCombo.addActionListener(e -> {
            var chosen = (OrbitCanvas3D.FocusMode) focusCombo.getSelectedItem();
            if (chosen != null) canvas.setFocusMode(chosen);
        });
        side.add(Box.createVerticalStrut(UiStyle.space(4)));
        side.add(flowRow(new JLabel("Center"), focusCombo));

        side.add(Box.createVerticalStrut(UiStyle.space(10)));
        side.add(separator());
        side.add(Box.createVerticalStrut(UiStyle.space(6)));
        side.add(sectionTitle("Display"));

        gridCheck.setAlignmentX(LEFT_ALIGNMENT);
        gridCheck.setToolTipText("Draws the ecliptic polar grid, orbit footprint shadow, and vertical height stems");
        gridCheck.addActionListener(e -> canvas.setShowGridAndStems(gridCheck.isSelected()));
        side.add(gridCheck);

        labelsCheck.setAlignmentX(LEFT_ALIGNMENT);
        labelsCheck.addActionListener(e -> canvas.setShowLabels(labelsCheck.isSelected()));
        side.add(labelsCheck);

        sightlineCheck.setAlignmentX(LEFT_ALIGNMENT);
        sightlineCheck.setToolTipText("Draws a subtle line between Earth and the object to show viewing geometry");
        sightlineCheck.addActionListener(e -> canvas.setShowSightline(sightlineCheck.isSelected()));
        side.add(sightlineCheck);

        trailCheck.setAlignmentX(LEFT_ALIGNMENT);
        trailCheck.setToolTipText("Draws a fading trail behind the moving object to show its direction of travel");
        trailCheck.addActionListener(e -> canvas.setShowTrail(trailCheck.isSelected()));
        side.add(trailCheck);

        singleNightCheck.setAlignmentX(LEFT_ALIGNMENT);
        singleNightCheck.setToolTipText("Shows and animates this night's preliminary orbit alongside the multi-night revised orbit");
        singleNightCheck.addActionListener(e -> canvas.setShowSingleNight(singleNightCheck.isSelected()));
        singleNightCheck.setVisible(false);
        side.add(singleNightCheck);

        cloudCheck.setAlignmentX(LEFT_ALIGNMENT);
        cloudCheck.setToolTipText("Draws alternative sample orbits consistent with the observations to show short-arc uncertainty");
        cloudCheck.addActionListener(e -> canvas.setShowUncertaintyCloud(cloudCheck.isSelected()));
        cloudCheck.setVisible(false);
        side.add(cloudCheck);

        side.add(Box.createVerticalGlue());

        JScrollPane scroll = new JScrollPane(side);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(UiStyle.space(330), UiStyle.space(480)));
        scroll.setMinimumSize(new Dimension(UiStyle.space(275), UiStyle.space(240)));
        return scroll;
    }

    private JComponent buildAnimationBar() {
        stepBackButton.setToolTipText("Step backward in time (Left arrow)");
        stepBackButton.addActionListener(e -> stepTime(-1));
        playButton.setToolTipText("Animate the orbit and planets in time (Space)");
        // Reserve width for "Pause" so toggling Play/Pause never shifts adjacent controls.
        playButton.setText("Pause");
        Dimension playSize = playButton.getPreferredSize();
        playButton.setText("Play");
        playButton.setPreferredSize(new Dimension(Math.max(playSize.width, playButton.getPreferredSize().width), playSize.height));
        playButton.addActionListener(e -> setPlaying(playButton.isSelected()));
        stepForwardButton.setToolTipText("Step forward in time (Right arrow)");
        stepForwardButton.addActionListener(e -> stepTime(1));
        reverseButton.setToolTipText("Play animation backward in time");

        speedCombo.setToolTipText("Simulated time per real second during playback");
        epochButton.setToolTipText("Jump back to the elements' observation epoch (E)");
        epochButton.addActionListener(e -> jumpToEpoch());
        savePictureButton.setToolTipText("Saves the current 3D view as a high-resolution PNG image");
        savePictureButton.addActionListener(e -> savePicture());

        timeSlider.setToolTipText("Scrub forward or backward in time around the observation epoch");
        timeSlider.setPaintTicks(true);
        timeSlider.setMajorTickSpacing(SLIDER_STEPS / 2);
        timeSlider.addChangeListener(e -> {
            if (updatingSlider || currentOrbit == null) return;
            double frac = timeSlider.getValue() / (double) SLIDER_STEPS;
            double jd = sliderMinJd + frac * (sliderMaxJd - sliderMinJd);
            canvas.setSimulationJd(jd);
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(4), 0));
        left.add(stepBackButton);
        left.add(playButton);
        left.add(stepForwardButton);
        left.add(reverseButton);
        left.add(speedCombo);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(6), 0));
        right.add(epochButton);
        right.add(savePictureButton);

        JPanel bar = new JPanel(new BorderLayout(UiStyle.space(8), 0));
        bar.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(4), 0, 0, 0));
        bar.add(left, BorderLayout.WEST);
        bar.add(timeSlider, BorderLayout.CENTER);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    private void configureTimelineFor(FindOrb.Orbit orbit) {
        double epochJd = orbit != null && Double.isFinite(orbit.epochJd()) ? orbit.epochJd() : OrbitGeometry.J2000;
        double period = OrbitGeometry.orbitalPeriodDays(orbit);
        if (Double.isFinite(period)) {
            sliderHalfSpanDays = Math.max(45.0, Math.min(3652.5, period * 0.75));
        } else {
            sliderHalfSpanDays = 365.25;
        }
        sliderMinJd = epochJd - sliderHalfSpanDays;
        sliderMaxJd = epochJd + sliderHalfSpanDays;
        updateSliderLabels();
        syncSliderToJd(epochJd);

        // Choose a sensible default playback speed tailored to the orbit (especially close NEOs).
        int defaultSpeedIndex = pickDefaultSpeedIndex(orbit, period, epochJd);
        speedCombo.setSelectedIndex(defaultSpeedIndex);
    }

    private static int pickDefaultSpeedIndex(FindOrb.Orbit orbit, double periodDays, double epochJd) {
        if (orbit == null) return 5; // 30 days / s
        double[] obj = OrbitGeometry.positionAt(orbit, epochJd);
        double[] earth = OrbitGeometry.planetPosition3D(OrbitGeometry.EARTH_INDEX, epochJd);
        if (obj != null && earth != null) {
            double delta = Math.sqrt(
                    (obj[0] - earth[0]) * (obj[0] - earth[0])
                            + (obj[1] - earth[1]) * (obj[1] - earth[1])
                            + (obj[2] - earth[2]) * (obj[2] - earth[2]));
            if (delta < 0.03) return 2; // 6 hr / s for very close NEO flybys
            if (delta < 0.15) return 3; // 1 day / s for close NEOs
        }
        if (OrbitGeometry.isNeo(orbit)) return 4; // 7 days / s
        if (Double.isFinite(periodDays) && periodDays > 4500.0) return 6; // 90 days / s for outer Solar System
        return 5; // 30 days / s for main-belt / typical comets
    }

    private void updateSliderLabels() {
        Hashtable<Integer, JLabel> labels = new Hashtable<>();
        JLabel left = new JLabel(formatSpan(-sliderHalfSpanDays));
        JLabel mid = new JLabel("Epoch");
        JLabel right = new JLabel(formatSpan(sliderHalfSpanDays));
        var font = UiStyle.bodyFont().deriveFont(Math.max(9.5f, UiStyle.bodyFont().getSize2D() - 2.5f));
        for (JLabel l : new JLabel[]{left, mid, right}) {
            l.setFont(font);
            l.setForeground(UiStyle.helpColor());
        }
        labels.put(0, left);
        labels.put(SLIDER_STEPS / 2, mid);
        labels.put(SLIDER_STEPS, right);
        timeSlider.setLabelTable(labels);
        timeSlider.setPaintLabels(true);
    }

    private static String formatSpan(double days) {
        String sign = days >= 0 ? "+" : "−";
        double abs = Math.abs(days);
        if (abs >= 365.0) return String.format(Locale.US, "%s%.1f yr", sign, abs / 365.25);
        return String.format(Locale.US, "%s%.0f d", sign, abs);
    }

    private void syncSliderToJd(double jd) {
        updatingSlider = true;
        try {
            if (jd < sliderMinJd || jd > sliderMaxJd) {
                // Smoothly recenter the slider window around the live date so playback never stalls or jumps planets back.
                double shift = jd - 0.5 * (sliderMinJd + sliderMaxJd);
                sliderMinJd += shift;
                sliderMaxJd += shift;
            }
            double frac = (jd - sliderMinJd) / Math.max(1e-9, sliderMaxJd - sliderMinJd);
            timeSlider.setValue((int) Math.round(Math.max(0.0, Math.min(1.0, frac)) * SLIDER_STEPS));
        } finally {
            updatingSlider = false;
        }
    }

    private void onAnimationTick() {
        long now = System.nanoTime();
        boolean transitioning = canvas.stepCameraTransition(now);
        boolean playing = playButton.isSelected();
        if (playing && currentOrbit != null) {
            double dtSeconds = Math.min(0.1, Math.max(0.0, (now - lastTickNanos) / 1_000_000_000.0));
            lastTickNanos = now;
            SpeedOption speed = (SpeedOption) speedCombo.getSelectedItem();
            double rate = speed == null ? 30.0 : speed.daysPerSecond();
            double direction = reverseButton.isSelected() ? -1.0 : 1.0;
            double nextJd = canvas.simulationJd() + direction * rate * dtSeconds;
            canvas.setSimulationJd(nextJd);
            syncSliderToJd(nextJd);
        }
        if (!playing && !transitioning) {
            animationTimer.stop();
        }
    }

    private void stepTime(int sign) {
        if (currentOrbit == null || !stepForwardButton.isEnabled()) return;
        SpeedOption speed = (SpeedOption) speedCombo.getSelectedItem();
        double stepDays = speed == null ? 1.0 : speed.daysPerSecond();
        double nextJd = canvas.simulationJd() + sign * stepDays;
        canvas.setSimulationJd(nextJd);
        syncSliderToJd(nextJd);
    }

    private void jumpToEpoch() {
        if (currentOrbit == null) return;
        double epochJd = currentOrbit.epochJd();
        sliderMinJd = epochJd - sliderHalfSpanDays;
        sliderMaxJd = epochJd + sliderHalfSpanDays;
        canvas.setSimulationJd(epochJd);
        syncSliderToJd(epochJd);
    }

    private void stopPlayback() {
        playButton.setSelected(false);
        playButton.setText("Play");
        if (!canvas.camera().isTransitioning()) animationTimer.stop();
    }

    private void ensureTimerRunning() {
        lastTickNanos = System.nanoTime();
        if (!animationTimer.isRunning()) animationTimer.start();
    }

    private void setAnimationControlsEnabled(boolean enabled) {
        playButton.setEnabled(enabled);
        reverseButton.setEnabled(enabled);
        stepBackButton.setEnabled(enabled);
        stepForwardButton.setEnabled(enabled);
        speedCombo.setEnabled(enabled);
        timeSlider.setEnabled(enabled);
        epochButton.setEnabled(enabled);
    }

    private void savePicture() {
        // Export at 2x supersampled HiDPI scale while preserving crisp proportional typography and markers.
        int lw = Math.max(760, canvas.getWidth());
        int lh = Math.max(560, canvas.getHeight());
        BufferedImage image = canvas.renderSnapshot(lw, lh, 2.0, true);
        JFileChooser chooser = WindowSizing.fileChooser(defaultSaveFolder);
        String baseName = currentId.isBlank() ? "orbit" : currentId.toLowerCase(Locale.ROOT) + "_orbit_3d";
        chooser.setSelectedFile(new File(defaultSaveFolder, baseName + ".png"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        fileWriter.write(() -> ImageIO.write(image, "png", target), problem -> statusSink.accept(problem == null
                ? "Saved " + target.getName() + "." : "The picture was not saved: " + problem));
    }

    private void installKeyboardShortcuts() {
        var input = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actions = getRootPane().getActionMap();
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "orbit.togglePlay",
                () -> { if (playButton.isEnabled()) setPlaying(!playButton.isSelected()); });
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "orbit.view3d", canvas::animateTo3DView);
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "orbit.viewTop", canvas::animateToTopView);
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "orbit.viewEdge", canvas::animateToEdgeOnView);
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_R, 0), "orbit.reset", () -> {
            focusCombo.setSelectedItem(OrbitCanvas3D.FocusMode.SUN);
            canvas.setFocusMode(OrbitCanvas3D.FocusMode.SUN);
            canvas.animateResetView();
        });
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_E, 0), "orbit.epoch", this::jumpToEpoch);
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, 0), "orbit.zoomIn", () -> canvas.zoomStep(true));
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0), "orbit.zoomInPad", () -> canvas.zoomStep(true));
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, 0), "orbit.zoomOut", () -> canvas.zoomStep(false));
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT, 0), "orbit.zoomOutPad", () -> canvas.zoomStep(false));
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "orbit.stepBack", () -> stepTime(-1));
        bindKey(input, actions, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "orbit.stepForward", () -> stepTime(1));
    }

    private static void bindKey(javax.swing.InputMap input, javax.swing.ActionMap actions,
                                KeyStroke key, String name, Runnable action) {
        input.put(key, name);
        actions.put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    private static JLabel sectionTitle(String text) {
        JLabel label = new JLabel(text);
        label.setFont(UiStyle.sectionFont());
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, UiStyle.space(4), 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    private static JSeparator separator() {
        JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiStyle.space(2)));
        sep.setAlignmentX(LEFT_ALIGNMENT);
        return sep;
    }

    private static JPanel flowRow(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(4), UiStyle.space(2)));
        for (JComponent c : components) row.add(c);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height + UiStyle.space(4)));
        return row;
    }
}
