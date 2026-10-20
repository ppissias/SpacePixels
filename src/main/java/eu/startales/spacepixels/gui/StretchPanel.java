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

import eu.startales.spacepixels.tasks.GeneratePreviewsTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.StretchAlgorithm;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.prefs.Preferences;

/**
 * The Image Stretch tab: the display stretch used by the frame viewers (Blink, Preview Frame, Manual Transient
 * Inspection and "Show full size", whose windows show the same controls) and the optional stretched copies, with a
 * linked, zoomable preview of the whole frame. The settings are remembered between sessions.
 */
public class StretchPanel extends JPanel {
    private final ApplicationWindow mainAppWindow;

    // Controls
    private final JSlider stretchSlider = new JSlider();
    private final JCheckBox stretchCheckbox = new JCheckBox("Enable batch stretch");
    private final JCheckBox displayStretchCheckbox = new JCheckBox("Stretch display", true);
    private final JComboBox<StretchAlgorithm> stretchAlgoCombo = new JComboBox<>(StretchAlgorithm.values());
    private final JSlider stretchIterationsSlider = new JSlider();
    private final JButton showFullSizeButton = new JButton("Show full size");
    private final JLabel stretchIntensityLabel = new JLabel();
    private final JLabel stretchIterationsLabel = new JLabel();
    private final JButton resetButton = new JButton("Reset to Defaults");
    private final JLabel displayPreviewTitle = new JLabel("Stretched", SwingConstants.CENTER);

    // Preview
    private final ZoomableImageView.ViewState viewState = new ZoomableImageView.ViewState();
    private final ZoomableImageView originalView = new ZoomableImageView(viewState);
    private final ZoomableImageView stretchedView = new ZoomableImageView(viewState);
    private final HistogramView originalHistogramView = new HistogramView();
    private final HistogramView histogramView = new HistogramView();
    private final JLabel frameLabel = new JLabel(" ");
    private final JButton previousButton = new JButton("◀ Previous");
    private final JButton nextButton = new JButton("Next ▶");

    private final Timer previewDebounceTimer;
    private final StretchPreviewRenderer renderer = new StretchPreviewRenderer(StretchPanel::render, this::showPreviews);
    /** True from a change of frame or stretch until its previews are shown; the frame label then says "rendering…". */
    private boolean rendering;
    private final Preferences preferences = preferences();
    /** True while the controls are set programmatically, so the change is not saved or previewed twice. */
    private boolean adjusting;

    private StretchedSequenceFrame sequenceFrame;

    public StretchPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 20, 20, 20));

        previewDebounceTimer = new Timer(250, e -> executePreviewTask());
        previewDebounceTimer.setRepeats(false);

        // ==========================================
        // CONTROLS
        // ==========================================
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(createSectionHeader("Display Stretch"));
        JLabel explanation = new JLabel("<html><div style='color: #999999; width: 620px;'>"
                + "Used by <b>Blink Selected</b>, <b>Preview Frame</b>, <b>Manual Transient Inspection</b>, "
                + "<b>Show full size</b> and the optional stretched copies. "
                + "Detection always works on the linear data, and report images use their own stretch "
                + "(Detection Settings → Report Visualization).</div></html>");
        controls.add(left(explanation));

        stretchAlgoCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                list.setToolTipText(value == null ? null : algorithmDescription((StretchAlgorithm) value));
                return c;
            }
        });
        resetButton.setToolTipText("Return the sliders to the defaults of the selected algorithm.");
        resetButton.addActionListener(e -> resetStretchToDefaults());
        JPanel algorithmRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        algorithmRow.setBorder(new EmptyBorder(12, 0, 6, 0));
        displayStretchCheckbox.setToolTipText("Apply the display stretch in this tab and all frame viewers. Untick for a linear view; your stretch settings are kept.");
        algorithmRow.add(displayStretchCheckbox);
        algorithmRow.add(Box.createHorizontalStrut(8));
        algorithmRow.add(new JLabel("Algorithm:"));
        algorithmRow.add(stretchAlgoCombo);
        algorithmRow.add(Box.createHorizontalStrut(8));
        algorithmRow.add(resetButton);
        controls.add(left(algorithmRow));

        stretchIntensityLabel.setFont(stretchIntensityLabel.getFont().deriveFont(Font.BOLD, 12f));
        stretchIterationsLabel.setFont(stretchIterationsLabel.getFont().deriveFont(Font.BOLD, 12f));
        JPanel slidersRow = new JPanel(new GridLayout(1, 2, 40, 0));
        slidersRow.setBorder(new EmptyBorder(6, 0, 6, 0));
        slidersRow.add(sliderPanel(stretchIntensityLabel, stretchSlider));
        slidersRow.add(sliderPanel(stretchIterationsLabel, stretchIterationsSlider));
        controls.add(left(slidersRow));

        stretchCheckbox.setToolTipText("<html>Enable this to batch stretch a dataset from the main screen by clicking <b>Batch Stretch</b>.<br>"
                + "Uses the selected algorithm and sliders, independently of Stretch display.<br>"
                + "Convert to Mono also writes stretched copies in a separate _mono_stretched folder.<br>"
                + "The original linear files used for detection are never changed.</html>");
        JPanel outputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        outputRow.setBorder(new EmptyBorder(4, 0, 0, 0));
        outputRow.add(stretchCheckbox);
        controls.add(left(outputRow));
        add(controls, BorderLayout.NORTH);

        // ==========================================
        // PREVIEW
        // ==========================================
        previousButton.setToolTipText("Preview the previous frame.");
        nextButton.setToolTipText("Preview the next frame.");
        previousButton.addActionListener(e -> mainAppWindow.getMainApplicationPanel().selectFrameOffset(-1));
        nextButton.addActionListener(e -> mainAppWindow.getMainApplicationPanel().selectFrameOffset(1));
        frameLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel hint = new JLabel("Scroll to zoom, drag to pan, double-click to fit; both previews move together.");
        hint.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel previewHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        previewHeader.add(createSectionHeader("Preview"));
        previewHeader.add(previousButton);
        previewHeader.add(nextButton);
        previewHeader.add(frameLabel);
        JPanel previewTop = new JPanel(new BorderLayout());
        previewTop.add(previewHeader, BorderLayout.NORTH);
        previewTop.add(hint, BorderLayout.SOUTH);

        JPanel images = new JPanel(new GridLayout(1, 2, 15, 0));
        images.add(titled("Original (linear)", originalView, originalHistogramView));
        images.add(titled(displayPreviewTitle, stretchedView, histogramView));

        JPanel previewPanel = new JPanel(new BorderLayout(0, 8));
        previewPanel.add(previewTop, BorderLayout.NORTH);
        previewPanel.add(images, BorderLayout.CENTER);
        add(previewPanel, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actionPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        showFullSizeButton.setToolTipText("Step through the whole sequence at full size with this stretch.");
        actionPanel.add(showFullSizeButton);
        add(actionPanel, BorderLayout.SOUTH);

        loadSettings();

        // ==========================================
        // LISTENERS
        // ==========================================
        stretchCheckbox.addItemListener(e -> {
            mainAppWindow.getMainApplicationPanel().refreshCommands();
        });
        displayStretchCheckbox.addItemListener(e -> {
            updateDisplayControls();
            if (!adjusting) {
                saveSettings();
                triggerPreviewUpdate();
            }
        });
        stretchAlgoCombo.addActionListener(e -> {
            if (adjusting) {
                return;
            }
            applySelectedAlgorithmConfiguration(false);
            saveSettings();
            triggerPreviewUpdate();
        });
        stretchSlider.addChangeListener(e -> sliderChanged());
        stretchIterationsSlider.addChangeListener(e -> sliderChanged());
        showFullSizeButton.addActionListener(e -> openFullSize());

        // Preview whenever the tab becomes visible; selection changes elsewhere only refresh a visible tab. A hidden
        // tab shows no render that was under way: it renders again when it is shown.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            if (isShowing()) {
                mainAppWindow.getMainApplicationPanel().selectFirstFileIfNoneSelected();
                updateFrameLabel();
                triggerPreviewUpdate();
            } else {
                previewDebounceTimer.stop();
                renderer.cancel();
                rendering = false;
            }
        });
    }

    // ==========================================
    // SETTINGS
    // ==========================================

    private void sliderChanged() {
        updateValueLabels();
        if (adjusting) {
            return;
        }
        saveSettings();
        triggerPreviewUpdate();
    }

    private void updateValueLabels() {
        StretchAlgorithm algorithm = getStretchAlgorithm();
        if (algorithm == null) {
            return;
        }
        stretchIntensityLabel.setText(algorithm.getPrimaryParameterLabel() + ": " + stretchSlider.getValue());
        stretchIterationsLabel.setText(algorithm.getSecondaryParameterLabel() + ": " + stretchIterationsSlider.getValue());
    }

    /** Sets the slider ranges for the selected algorithm, with its saved values or, when {@code reset}, its defaults. */
    private void applySelectedAlgorithmConfiguration(boolean reset) {
        StretchAlgorithm algorithm = getStretchAlgorithm();
        if (algorithm == null) {
            return;
        }
        adjusting = true;
        try {
            stretchSlider.setMinimum(algorithm.getPrimaryMinimum());
            stretchSlider.setMaximum(algorithm.getPrimaryMaximum());
            stretchSlider.setValue(reset ? algorithm.getPrimaryDefault()
                    : savedValue(algorithm, "primary", algorithm.getPrimaryDefault(), algorithm.getPrimaryMinimum(), algorithm.getPrimaryMaximum()));
            stretchIterationsSlider.setMinimum(algorithm.getSecondaryMinimum());
            stretchIterationsSlider.setMaximum(algorithm.getSecondaryMaximum());
            stretchIterationsSlider.setValue(reset ? algorithm.getSecondaryDefault()
                    : savedValue(algorithm, "secondary", algorithm.getSecondaryDefault(), algorithm.getSecondaryMinimum(), algorithm.getSecondaryMaximum()));
            // Ticks only when there are few steps; many unlabelled ticks just look like noise.
            int steps = algorithm.getSecondaryMaximum() - algorithm.getSecondaryMinimum();
            stretchIterationsSlider.setMajorTickSpacing(1);
            stretchIterationsSlider.setPaintTicks(steps <= 20);
            stretchIterationsSlider.setSnapToTicks(true);
        } finally {
            adjusting = false;
        }
        updateValueLabels();
    }

    private void loadSettings() {
        adjusting = true;
        try {
            String saved = preferences == null ? null : preferences.get("stretch.algorithm", null);
            StretchAlgorithm algorithm = StretchAlgorithm.ASINH;
            if (saved != null) {
                try {
                    algorithm = StretchAlgorithm.valueOf(saved);
                } catch (IllegalArgumentException ignored) {
                    // Unknown algorithm from another version: keep the default.
                }
            }
            stretchAlgoCombo.setSelectedItem(algorithm);
            displayStretchCheckbox.setSelected(preferences == null || preferences.getBoolean("stretch.enabled", true));
        } finally {
            adjusting = false;
        }
        applySelectedAlgorithmConfiguration(false);
        updateDisplayControls();
    }

    private void saveSettings() {
        StretchAlgorithm algorithm = getStretchAlgorithm();
        if (preferences == null || algorithm == null) {
            return;
        }
        preferences.put("stretch.algorithm", algorithm.name());
        preferences.putBoolean("stretch.enabled", isDisplayStretchEnabled());
        preferences.putInt("stretch." + algorithm.name() + ".primary", stretchSlider.getValue());
        preferences.putInt("stretch." + algorithm.name() + ".secondary", stretchIterationsSlider.getValue());
    }

    private int savedValue(StretchAlgorithm algorithm, String which, int fallback, int min, int max) {
        int value = preferences == null ? fallback : preferences.getInt("stretch." + algorithm.name() + "." + which, fallback);
        return Math.max(min, Math.min(max, value));
    }

    private void updateDisplayControls() {
        boolean enabled = isDisplayStretchEnabled();
        stretchAlgoCombo.setEnabled(enabled);
        stretchSlider.setEnabled(enabled);
        stretchIterationsSlider.setEnabled(enabled);
        stretchIntensityLabel.setEnabled(enabled);
        stretchIterationsLabel.setEnabled(enabled);
        resetButton.setEnabled(enabled);
        displayPreviewTitle.setText(enabled ? "Stretched" : "Linear (stretch off)");
    }

    /** What each algorithm does, for its tooltip. */
    static String algorithmDescription(StretchAlgorithm algorithm) {
        switch (algorithm) {
            case ASINH:
                return "<html><b>Asinh</b> (recommended): sets the black point at a percentile of the histogram and lifts faint "
                        + "signal with an arcsinh curve,<br>keeping star cores from saturating. Strength controls how much faint parts are lifted.</html>";
            case ENHANCE_LOW:
                return "<html><b>Enhance Low</b>: brightens pixels below the threshold, more strongly the fainter they are; "
                        + "each iteration repeats it.</html>";
            case ENHANCE_HIGH:
                return "<html><b>Enhance High</b>: multiplies the brighter pixels above the threshold; each iteration repeats it.</html>";
            case EXTREME:
            default:
                return "<html><b>Extreme</b>: shows every pixel above the noise level plus the threshold at one bright value.<br>"
                        + "Harsh, but makes very faint objects stand out when blinking.</html>";
        }
    }

    // ==========================================
    // PREVIEW
    // ==========================================

    /** Called by the main window whenever the frame selection changes. */
    public void onFrameSelectionChanged() {
        updateFrameLabel();
        if (isShowing()) {
            triggerPreviewUpdate();
        }
    }

    private void updateFrameLabel() {
        MainApplicationPanel mainPanel = mainAppWindow.getMainApplicationPanel();
        int[] selection = mainPanel == null ? new int[]{-1, 0} : mainPanel.frameSelection();
        FitsFileInformation selected = mainPanel == null ? null : mainPanel.getSelectedFileInformation();
        previousButton.setEnabled(selection[0] > 0);
        nextButton.setEnabled(selection[0] >= 0 && selection[0] < selection[1] - 1);
        frameLabel.setText(selection[0] < 0 || selected == null ? "No frame selected"
                : "Frame " + (selection[0] + 1) + " of " + selection[1] + " · " + new File(selected.getFilePath()).getName()
                + (rendering ? " · rendering…" : ""));
    }

    public void triggerPreviewUpdate() {
        // Only a visible tab renders; the tab renders again when it is shown (for example after blinking).
        if (!isShowing()) {
            return;
        }
        // A render under way is for the previous frame or stretch, so it is not shown.
        renderer.cancel();
        rendering = true;
        updateFrameLabel();
        if (previewDebounceTimer.isRunning()) {
            previewDebounceTimer.restart();
        } else {
            previewDebounceTimer.start();
        }
    }

    private void executePreviewTask() {
        FitsFileInformation selected = mainAppWindow.getMainApplicationPanel().getSelectedFileInformation();
        if (selected == null || mainAppWindow.getImageProcessing() == null) {
            clearPreviews("Import frames and select one to preview it");
            return;
        }
        originalView.setPlaceholder("Rendering…");
        stretchedView.setPlaceholder("Rendering…");
        renderer.request(new StretchPreviewRenderer.Request(mainAppWindow.getImageProcessing(), selected.getFilePath(),
                getStretchAlgorithm(), stretchSlider.getValue(), stretchIterationsSlider.getValue(), isDisplayStretchEnabled()));
    }

    /** Renders both previews and their histograms, on the renderer's worker thread. */
    private static StretchPreviewRenderer.Previews render(StretchPreviewRenderer.Request request) throws Exception {
        BufferedImage[] images = new GeneratePreviewsTask(request.session, request.filePath, request.strength,
                request.iterations, request.algorithm, request.stretchEnabled).call();
        return new StretchPreviewRenderer.Previews(request, images[0], images[1],
                HistogramView.luminanceHistogram(images[0]), HistogramView.luminanceHistogram(images[1]));
    }

    /** Shows the previews of the newest request; the renderer drops any other. */
    private void showPreviews(StretchPreviewRenderer.Previews previews) {
        originalView.setImage(previews.original);
        stretchedView.setImage(previews.stretched);
        if (!previews.isRendered()) {
            stretchedView.setPlaceholder("The preview could not be rendered");
        }
        originalHistogramView.setHistogram(previews.originalHistogram);
        histogramView.setHistogram(previews.stretchedHistogram);
        rendering = false;
        updateFrameLabel();
    }

    /** Removes the previews (and any render under way), showing {@code placeholder} instead. */
    private void clearPreviews(String placeholder) {
        renderer.cancel();
        rendering = false;
        originalView.setImage(null);
        stretchedView.setImage(null);
        originalView.setPlaceholder(placeholder);
        stretchedView.setPlaceholder(placeholder);
        originalHistogramView.setHistogram(null);
        histogramView.setHistogram(null);
        updateFrameLabel();
    }

    /** The previews belong to the session they were rendered for: another session removes them. */
    void sessionChanged(FitsFileInformation[] files) {
        clearPreviews("Select a frame to preview it");
        triggerPreviewUpdate();
    }

    private void openFullSize() {
        FitsFileInformation[] files;
        try {
            files = mainAppWindow.getImageProcessing().getImportedFiles();
        } catch (Exception ex) {
            System.err.println("Could not load FITS files: " + ex.getMessage());
            return;
        }
        if (files == null || files.length == 0) {
            return;
        }
        FitsFileInformation selectedFile = mainAppWindow.getMainApplicationPanel().getSelectedFileInformation();
        int startIndex = 0;
        if (selectedFile != null) {
            for (int i = 0; i < files.length; i++) {
                if (files[i].getFilePath().equals(selectedFile.getFilePath())) {
                    startIndex = i;
                    break;
                }
            }
        }
        if (sequenceFrame == null) {
            sequenceFrame = new StretchedSequenceFrame(mainAppWindow, this);
        }
        sequenceFrame.openSequence(files, startIndex);
    }

    // ==========================================
    // ACCESSORS (used by Blink, Batch Stretch, Convert to Mono and the full-size viewer)
    // ==========================================

    public StretchAlgorithm getStretchAlgorithm() {
        return (StretchAlgorithm) stretchAlgoCombo.getSelectedItem();
    }

    /** Whether stretched copies are written (Batch Stretch, Convert to Mono). */
    public boolean isStretchEnabled() {
        return stretchCheckbox.isSelected();
    }

    public boolean isDisplayStretchEnabled() {
        return displayStretchCheckbox.isSelected();
    }

    /** Shared with each viewer; the separate batch-stretch checkbox only controls disk output. */
    ButtonModel getDisplayStretchModel() {
        return displayStretchCheckbox.getModel();
    }

    public JSlider getStretchSlider() {
        return stretchSlider;
    }

    public JSlider getStretchIterationsSlider() {
        return stretchIterationsSlider;
    }

    /** The algorithm choice, shared with the blink window so both show and change the same stretch. */
    ComboBoxModel<StretchAlgorithm> getStretchAlgorithmModel() {
        return stretchAlgoCombo.getModel();
    }

    /** Returns the sliders to the defaults of the selected algorithm (the Reset to Defaults button). */
    void resetStretchToDefaults() {
        applySelectedAlgorithmConfiguration(true);
        saveSettings();
        triggerPreviewUpdate();
    }

    // ==========================================
    // UI HELPERS
    // ==========================================

    private static JPanel sliderPanel(JLabel label, JSlider slider) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.add(label, BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);
        return panel;
    }

    private static JPanel titled(String title, JComponent view, JComponent below) {
        return titled(new JLabel(title, SwingConstants.CENTER), view, below);
    }

    private static JPanel titled(JLabel label, JComponent view, JComponent below) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        label.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(label, BorderLayout.NORTH);
        panel.add(view, BorderLayout.CENTER);
        if (below != null) {
            panel.add(below, BorderLayout.SOUTH);
        }
        return panel;
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    private JLabel createSectionHeader(String title) {
        JLabel headerLabel = new JLabel(title);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerLabel.setForeground(DetectionConfigurationPanel.accentColor());
        headerLabel.setBorder(new EmptyBorder(10, 0, 6, 0));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return headerLabel;
    }

    private static Preferences preferences() {
        try {
            return Preferences.userNodeForPackage(StretchPanel.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** Histogram of the stretched preview (log counts), with the share of pixels clipped to black and to white. */
    static final class HistogramView extends JComponent {
        private int[] histogram;

        HistogramView() {
            setPreferredSize(new Dimension(300, 74));
            setToolTipText("Brightness histogram of the stretched preview (logarithmic counts). "
                    + "A tall bar at either end means pixels are clipped to pure black or white.");
        }

        void setHistogram(int[] histogram) {
            this.histogram = histogram;
            repaint();
        }

        /** 256-bin histogram of the mean of red, green and blue. */
        static int[] luminanceHistogram(BufferedImage image) {
            if (image == null) {
                return null;
            }
            int[] bins = new int[256];
            int width = image.getWidth();
            int[] row = new int[width];
            for (int y = 0; y < image.getHeight(); y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int rgb : row) {
                    bins[(((rgb >> 16) & 0xff) + ((rgb >> 8) & 0xff) + (rgb & 0xff)) / 3]++;
                }
            }
            return bins;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            int textHeight = 16;
            int plotHeight = getHeight() - textHeight - 2;
            g2.setColor(new Color(0x14121f));
            g2.fillRect(0, 0, getWidth(), plotHeight);
            if (histogram != null) {
                long total = 0;
                int max = 1;
                for (int count : histogram) {
                    total += count;
                    max = Math.max(max, count);
                }
                double logMax = Math.log1p(max);
                g2.setColor(new Color(0x8fb4e8));
                for (int bin = 0; bin < 256; bin++) {
                    int x0 = bin * getWidth() / 256;
                    int x1 = (bin + 1) * getWidth() / 256;
                    int barHeight = (int) Math.round(Math.log1p(histogram[bin]) / logMax * (plotHeight - 2));
                    g2.fillRect(x0, plotHeight - barHeight, Math.max(1, x1 - x0), barHeight);
                }
                double black = total == 0 ? 0 : 100.0 * histogram[0] / total;
                double white = total == 0 ? 0 : 100.0 * histogram[255] / total;
                g2.setColor(UIManager.getColor("Label.disabledForeground"));
                g2.drawString(String.format(Locale.US, "Clipped: %.1f %% black · %.1f %% white", black, white), 2, getHeight() - 4);
            }
            g2.dispose();
        }
    }
}
