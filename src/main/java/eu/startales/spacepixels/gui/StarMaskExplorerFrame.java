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

import io.github.ppissias.jtransient.model.DetectedObject;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.config.CoreDetectionSettings;
import eu.startales.spacepixels.util.DisplayImageRenderer;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.MasterReferenceAnalyzer;
import io.github.ppissias.jtransient.core.MasterVetoMask;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.execution.CancellationScope;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Shows the session's master stack with the stationary-star veto mask the detection run would use, for trial star
 * mask settings, with coverage statistics. The mask is built by the same JTransient code as in the pipeline.
 */
final class StarMaskExplorerFrame extends JFrame implements SessionViewer {

    @FunctionalInterface
    interface ComputeMask {
        Object[] compute(short[][] stack, DetectionConfig config, Settings settings);
    }

    /** The star mask values the explorer varies. */
    static final class Settings {
        final double masterSigma;
        final double masterGrowSigma;
        final int masterMinPixels;
        final double starJitter;

        Settings(double masterSigma, double masterGrowSigma, int masterMinPixels, double starJitter) {
            this.masterSigma = masterSigma;
            this.masterGrowSigma = masterGrowSigma;
            this.masterMinPixels = masterMinPixels;
            this.starJitter = starJitter;
        }
    }

    /** Coverage statistics of one mask. */
    static final class MaskStatistics {
        final double coverage;
        final int stars;
        final int largestStarPixels;

        MaskStatistics(double coverage, int stars, int largestStarPixels) {
            this.coverage = coverage;
            this.stars = stars;
            this.largestStarPixels = largestStarPixels;
        }
    }

    private static final Color MASK_COLOR = new Color(255, 140, 0, 125);

    private short[][] stack;
    private final DetectionConfig baseConfig;
    private final ComputeMask computeMask;

    private final FrameView view = new FrameView();
    private final TrialSettingsControls trials;
    private final JLabel coverageLabel = new JLabel(" ");
    private final JLabel starsLabel = new JLabel(" ");
    private final JLabel largestLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel widthLabel = new JLabel(" ");
    private final AtomicInteger generation = new AtomicInteger();
    private MaskStatistics initialStatistics;
    private SwingWorker<Object[], Void> worker;
    private boolean closed;

    /** Shows the master stack with the display stretch of {@code visualization}. */
    StarMaskExplorerFrame(Window owner, String sessionName, JTransientEngine.MasterStackResult stackResult,
                          DetectionConfig baseConfig, VisualizationSettings visualization, Settings initial,
                          Function<Map<String, Number>, DetectionConfig> apply) {
        this(owner, sessionName, stackResult, baseConfig, visualization, initial, apply, StarMaskExplorerFrame::computeMask);
    }

    StarMaskExplorerFrame(Window owner, String sessionName, JTransientEngine.MasterStackResult stackResult,
                          DetectionConfig baseConfig, VisualizationSettings visualization, Settings initial,
                          Function<Map<String, Number>, DetectionConfig> apply, ComputeMask computeMask) {
        super("Star Mask Explorer · " + sessionName);
        AppIcon.apply(this);
        this.stack = stackResult.masterStack;
        this.baseConfig = baseConfig.clone();
        this.computeMask = computeMask;

        trials = new TrialSettingsControls(List.of(
                new TrialSettingsControls.Input(CoreDetectionSettings.MASTER_SIGMA_MULTIPLIER, initial.masterSigma, 0.5, 15.0, 0.1),
                new TrialSettingsControls.Input(CoreDetectionSettings.MASTER_GROW_SIGMA_MULTIPLIER, initial.masterGrowSigma, 0.0, 15.0, 0.1),
                new TrialSettingsControls.Input(CoreDetectionSettings.MASTER_MIN_DETECTION_PIXELS, initial.masterMinPixels, 1, 2000, 1),
                new TrialSettingsControls.Input(CoreDetectionSettings.MAX_STAR_JITTER, initial.starJitter, 0.0, 20.0, 0.1)),
                baseConfig, () -> { updateWideningLabel(); recompute(); }, view::requestFocusInWindow, apply);

        view.setPlaceholder("Rendering the master stack…");
        view.getAccessibleContext().setAccessibleName("Star Mask Explorer image");
        view.setToolTipText("Scroll to zoom down to single pixels, drag to pan, double-click to switch between fit and 100 %.");
        // Full resolution: zooming in shows the real pixels of the master stack.
        view.setFocusable(true);
        view.showPicture(DisplayImageRenderer.createDisplayImage(stack, visualization.getAutoStretchBlackSigma(),
                visualization.getAutoStretchWhiteSigma()));
        ViewerSupport.bindZoomKeys(getRootPane(), view);

        setLayout(new BorderLayout());
        add(view, BorderLayout.CENTER);
        add(buildControls(stackResult), BorderLayout.EAST);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1400, screen.width - 80), Math.min(900, screen.height - 80));
        setLocationRelativeTo(owner);

        statusLabel.setText("Computing the mask…");
        initialStatistics = null;
        recompute();
    }

    private JComponent buildControls(JTransientEngine.MasterStackResult stackResult) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(12, 14, 12, 14));
        panel.setPreferredSize(new Dimension(340, 10));

        panel.add(left(DetectionConfigurationPanel.createSectionHeader("Star Mask")));
        panel.add(left(muted(basisText(stackResult))));
        panel.add(Box.createVerticalStrut(10));

        panel.add(left(trials.grid()));
        widthLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        updateWideningLabel();
        panel.add(left(widthLabel));

        JCheckBox showMask = new JCheckBox("Show mask", true);
        showMask.addItemListener(e -> view.setImageOverlayVisible(showMask.isSelected()));
        // Hold to compare: the bare median stack while pressed, the mask again when released (also the Space bar).
        JButton holdButton = new JButton("Hold to See the Stack Only");
        holdButton.setToolTipText("Press and hold (or hold the Space bar) to hide the mask and see the median stack; release to see the mask again.");
        holdButton.getModel().addChangeListener(e -> view.setImageOverlayVisible(showMask.isSelected() && !holdButton.getModel().isPressed()));
        ViewerSupport.bind(getRootPane(), "pressed SPACE", "hideMask", () -> view.setImageOverlayVisible(false));
        ViewerSupport.bind(getRootPane(), "released SPACE", "showMask", () -> view.setImageOverlayVisible(showMask.isSelected()));
        view.addFocusListener(new java.awt.event.FocusAdapter() {
            /** Ending image focus also ends a held Space comparison, even if its key release goes to another control. */
            @Override public void focusLost(java.awt.event.FocusEvent event) {
                view.setImageOverlayVisible(showMask.isSelected());
            }
        });
        ViewerSupport.bind(getRootPane(), "ESCAPE", "close", this::dispose);
        JPanel maskRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        maskRow.add(showMask);
        maskRow.add(Box.createHorizontalStrut(10));
        maskRow.add(holdButton);
        maskRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, maskRow.getPreferredSize().height));
        panel.add(Box.createVerticalStrut(6));
        panel.add(left(maskRow));
        panel.add(left(muted("Scroll to zoom down to single pixels, drag to pan, double-click to switch between fit and 100 %. "
                + "Orange areas are masked: a detection that overlaps them by more than the Max Mask Overlap Fraction is vetoed.")));

        panel.add(Box.createVerticalStrut(8));
        panel.add(left(DetectionConfigurationPanel.createSectionHeader("Statistics")));
        panel.add(left(coverageLabel));
        panel.add(left(starsLabel));
        panel.add(left(largestLabel));
        panel.add(Box.createVerticalStrut(8));
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(left(statusLabel));

        panel.add(Box.createVerticalGlue());
        panel.add(left(trials.actions()));
        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(closeButton);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        panel.add(left(buttons));
        ViewerSupport.prepareControls(panel);
        return panel;
    }

    private static String basisText(JTransientEngine.MasterStackResult result) {
        StringBuilder text = new StringBuilder("Median of " + result.keptFrames.size() + " frames, built as in a detection run.");
        if (!result.rejectedFrames.isEmpty()) {
            text.append(" Quality control left out frame");
            text.append(result.rejectedFrames.size() == 1 ? " " : "s ");
            for (int i = 0; i < result.rejectedFrames.size(); i++) {
                PipelineTelemetry.FrameRejectionStat rejected = result.rejectedFrames.get(i);
                text.append(i == 0 ? "" : ", ").append(rejected.frameIndex + 1);
            }
            text.append('.');
        }
        return text.toString();
    }

    private Settings currentSettings() {
        return new Settings(trials.value(CoreDetectionSettings.MASTER_SIGMA_MULTIPLIER).doubleValue(),
                trials.value(CoreDetectionSettings.MASTER_GROW_SIGMA_MULTIPLIER).doubleValue(),
                trials.value(CoreDetectionSettings.MASTER_MIN_DETECTION_PIXELS).intValue(),
                trials.value(CoreDetectionSettings.MAX_STAR_JITTER).doubleValue());
    }

    // ==========================================
    // MASK COMPUTATION
    // ==========================================

    /** Builds the mask for the current values in the background; a newer request makes older results obsolete. */
    private void recompute() {
        if (closed) {
            return;
        }
        Settings settings = currentSettings();
        int request = generation.incrementAndGet();
        short[][] requestedStack = stack;
        if (worker != null) {
            worker.cancel(false);
        }
        statusLabel.setText("Computing the mask…");
        worker = new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                try (CancellationScope cancellation = CancellationScope.open(() -> isCancelled() || request != generation.get())) {
                    CancellationScope.check();
                    DetectionConfig config = configFor(baseConfig, settings);
                    Object[] result = computeMask.compute(requestedStack, config, settings);
                    CancellationScope.check();
                    return result;
                }
            }

            @Override
            protected void done() {
                if (closed || request != generation.get() || worker != this) {
                    return;
                }
                worker = null;
                try {
                    Object[] result = get();
                    MaskStatistics statistics = (MaskStatistics) result[0];
                    if (initialStatistics == null) {
                        initialStatistics = statistics;
                    }
                    @SuppressWarnings("unchecked")
                    List<BufferedImage> levels = (List<BufferedImage>) result[1];
                    view.setImageOverlayLevels(levels);
                    showStatistics(statistics);
                    statusLabel.setText(" ");
                } catch (Exception e) {
                    statusLabel.setText("The mask could not be computed: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private static Object[] computeMask(short[][] stack, DetectionConfig config, Settings settings) {
        List<DetectedObject> stars = MasterReferenceAnalyzer.analyzeFromMasterStack(stack, config).masterStars;
        boolean[][] mask = MasterVetoMask.build(stars, stack[0].length, stack.length, settings.starJitter);
        return new Object[]{statistics(stars, mask), overlayLevels(mask, MASK_COLOR)};
    }

    /** Pauses trial edits and Apply while an application job owns the settings. */
    @Override
    public void setSettingsEditable(boolean editable) {
        trials.setEditable(editable);
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
            trials.stop();
            if (worker != null) {
                worker.cancel(false);
                worker = null;
            }
            stack = null;
            initialStatistics = null;
            view.showPicture(null);
            view.setImageOverlayLevels(java.util.Collections.emptyList());
        }
        super.dispose();
    }

    private void showStatistics(MaskStatistics s) {
        MaskStatistics c = initialStatistics;
        coverageLabel.setText(String.format(Locale.US, "Sky masked: %.1f %%  (current %.1f %%)", 100 * s.coverage, 100 * c.coverage));
        starsLabel.setText(String.format(Locale.US, "Masked stars: %,d  (current %,d)", s.stars, c.stars));
        largestLabel.setText(String.format(Locale.US, "Largest star: %,d px  (current %,d px)", s.largestStarPixels, c.largestStarPixels));
    }

    /** The detection configuration with the trial star mask values. */
    static DetectionConfig configFor(DetectionConfig base, Settings settings) {
        DetectionConfig config = base.clone();
        config.masterSigmaMultiplier = settings.masterSigma;
        config.masterGrowSigmaMultiplier = settings.masterGrowSigma;
        config.masterMinDetectionPixels = settings.masterMinPixels;
        config.maxStarJitter = settings.starJitter;
        return config;
    }

    static MaskStatistics statistics(List<DetectedObject> stars, boolean[][] mask) {
        int largest = 0;
        for (DetectedObject star : stars) {
            if (star.rawPixels != null) {
                largest = Math.max(largest, star.rawPixels.size());
            }
        }
        return new MaskStatistics(MasterVetoMask.coverage(mask), stars.size(), largest);
    }

    /**
     * A one-bit image of the mask (transparent or the mask colour), reduced by {@code step} so a reduced pixel is
     * masked when any pixel of its block is.
     */
    static BufferedImage overlay(boolean[][] mask, int step, Color color) {
        int height = (mask.length + step - 1) / step;
        int width = (mask[0].length + step - 1) / step;
        IndexColorModel palette = new IndexColorModel(1, 2,
                new byte[]{0, (byte) color.getRed()}, new byte[]{0, (byte) color.getGreen()},
                new byte[]{0, (byte) color.getBlue()}, new byte[]{0, (byte) color.getAlpha()});
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY, palette);
        byte[] bits = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        int rowBytes = (width + 7) / 8;
        for (int y = 0; y < mask.length; y++) {
            CancellationScope.check();
            boolean[] row = mask[y];
            int outRow = (y / step) * rowBytes;
            for (int x = 0; x < row.length; x++) {
                if (row[x]) {
                    int outX = x / step;
                    bits[outRow + (outX >> 3)] |= (byte) (0x80 >> (outX & 7));
                }
            }
        }
        return image;
    }

    /**
     * The mask overlay at full resolution and reduced by 2, 4, 8 … (down to about 512 pixels), for drawing zoomed
     * out; every reduced pixel that covers a masked pixel stays masked.
     */
    static List<BufferedImage> overlayLevels(boolean[][] mask, Color color) {
        List<BufferedImage> levels = new ArrayList<>();
        int longest = Math.max(mask.length, mask[0].length);
        for (int step = 1; ; step *= 2) {
            levels.add(overlay(mask, step, color));
            if (longest / step <= 512) {
                break;
            }
        }
        return levels;
    }

    /** Shows how many pixels the jitter radius widens the mask by, and where the next step is. */
    private void updateWideningLabel() {
        int radius = MasterVetoMask.dilationRadius(trials.value(CoreDetectionSettings.MAX_STAR_JITTER).doubleValue());
        widthLabel.setText("Mask widened by " + radius + " px · next step at " + (2 * radius + 1) + ".0");
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private static JLabel muted(String text) {
        JLabel label = new JLabel("<html><div style='width: 230px;'>" + text + "</div></html>");
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        return label;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
