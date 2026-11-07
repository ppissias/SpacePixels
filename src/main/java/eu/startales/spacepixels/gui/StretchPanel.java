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
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsVisualizationRenderer;
import eu.startales.spacepixels.fits.StretchAlgorithm;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.prefs.Preferences;
import java.util.function.Consumer;

/**
 * Shared display-stretch controls and the editor's linked, zoomable linear/stretched previews. Viewer controls
 * share these models; settings are remembered between sessions. The editor navigates independently of the Main
 * table selection. All controls and preview requests are owned by the Swing event dispatch thread.
 */
public class StretchPanel extends JPanel {
    private final SessionAccess sessions;
    private final Consumer<FitsFileInformation> editorOpener;
    private FitsFileInformation[] previewFiles = new FitsFileInformation[0];
    private int previewIndex = -1;
    private final FullSizeViewer fullSize;

    // Controls
    private final JSlider stretchSlider = new JSlider();
    private final JCheckBox displayStretchCheckbox = new JCheckBox("Stretch display", true);
    private final JComboBox<StretchAlgorithm> stretchAlgoCombo = new JComboBox<>(StretchAlgorithm.values());
    private final JSlider stretchIterationsSlider = new JSlider();
    private final JSpinner primaryNumber = parameterNumber(stretchSlider);
    private final JSpinner secondaryNumber = parameterNumber(stretchIterationsSlider);
    private final JButton showFullSizeButton = new JButton("Show full size");
    private final JLabel stretchIntensityLabel = new JLabel();
    private final JLabel stretchIterationsLabel = new JLabel();
    private final JButton resetButton = new JButton("Reset to Defaults");
    private final JLabel displayPreviewTitle = new JLabel("Stretched", SwingConstants.CENTER);

    // Preview
    private final FrameView.ViewLink viewState = new FrameView.ViewLink();
    private final FrameView originalView = linkedPreview(viewState);
    private final FrameView stretchedView = linkedPreview(viewState);
    private final HistogramView originalHistogramView = new HistogramView();
    private final HistogramView histogramView = new HistogramView();
    private final JLabel frameLabel = new JLabel(" ");
    private final JButton previousButton = new JButton("◀ Previous");
    private final JButton nextButton = new JButton("Next ▶");

    private final Timer previewDebounceTimer;
    private final StretchPreviewRenderer renderer = new StretchPreviewRenderer(StretchPanel::render, this::showPreviews);
    /** Draws the preview pictures; it keeps no state, so one serves every request. */
    private final FitsVisualizationRenderer previewPictures = new FitsVisualizationRenderer();
    /** True from a change of frame or stretch until its previews are shown; the frame label then says "rendering…". */
    private boolean rendering;
    private final Preferences preferences = preferences();
    /** True while the controls are set programmatically, so the change is not saved or previewed twice. */
    private boolean adjusting;

    private StretchedSequenceFrame sequenceFrame;

    /** A stretch preview with the previous preferred size, sharing zoom and pan with its pair. */
    private static FrameView linkedPreview(FrameView.ViewLink link) {
        FrameView view = new FrameView(link);
        view.setPreferredSize(new Dimension(400, 300));
        view.setToolTipText("Scroll to zoom, drag to pan, double-click to switch between fit and 100 %. Both previews move together.");
        return view;
    }

    /** Captures session access, the guarded export command and the editor/viewer openers on the EDT. */
    public StretchPanel(SessionAccess sessions, Action batchStretch, FullSizeViewer fullSize,
                        Consumer<FitsFileInformation> editorOpener) {
        this.sessions = sessions;
        this.editorOpener = editorOpener;
        this.fullSize = fullSize;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(UiStyle.space(8), UiStyle.space(12), UiStyle.space(10), UiStyle.space(12)));

        previewDebounceTimer = new Timer(250, e -> executePreviewTask());
        previewDebounceTimer.setRepeats(false);

        // ==========================================
        // CONTROLS
        // ==========================================
        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
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
        displayStretchCheckbox.setToolTipText("Apply the display stretch in this editor and all frame viewers. Untick for a linear view; your stretch settings are kept.");
        algorithmRow.add(displayStretchCheckbox);
        algorithmRow.add(Box.createHorizontalStrut(8));
        algorithmRow.add(new JLabel("Algorithm:"));
        algorithmRow.add(stretchAlgoCombo);
        algorithmRow.add(Box.createHorizontalStrut(8));
        algorithmRow.add(resetButton);
        controls.add(left(algorithmRow));

        stretchIntensityLabel.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        stretchIterationsLabel.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        JPanel slidersRow = new JPanel(new GridLayout(1, 2, 40, 0));
        slidersRow.setBorder(new EmptyBorder(6, 0, 6, 0));
        slidersRow.add(sliderPanel(stretchIntensityLabel, stretchSlider, primaryNumber));
        slidersRow.add(sliderPanel(stretchIterationsLabel, stretchIterationsSlider, secondaryNumber));
        controls.add(left(slidersRow));

        add(WindowSizing.viewerControls(controls), BorderLayout.NORTH);

        // ==========================================
        // PREVIEW
        // ==========================================
        previousButton.setToolTipText("Preview the previous frame.");
        nextButton.setToolTipText("Preview the next frame.");
        previousButton.addActionListener(e -> stepFrame(-1));
        nextButton.addActionListener(e -> stepFrame(1));
        frameLabel.setForeground(UiStyle.helpColor());
        JLabel hint = new JLabel("Scroll to zoom, drag to pan, double-click to fit; both previews move together.");
        hint.setForeground(UiStyle.helpColor());

        JPanel previewHeader = new WrappingControlsPanel(UiStyle.space(8), UiStyle.space(2));
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

        JPanel footer = new JPanel(new BorderLayout(0, UiStyle.space(6)));
        WrappingHelpLabel sharedNote = new WrappingHelpLabel(
                "Changes apply immediately to all frame viewers; detection uses the linear data.", 850);
        footer.add(sharedNote, BorderLayout.NORTH);
        JPanel actionPanel = new WrappingControlsPanel(UiStyle.space(8), UiStyle.space(4));
        JButton export = new JButton(batchStretch);
        actionPanel.add(export);
        showFullSizeButton.setToolTipText("Step through the whole sequence at full size with this stretch, including colour frames.");
        actionPanel.add(showFullSizeButton);
        JButton close = new JButton("Close");
        close.addActionListener(e -> SwingUtilities.getWindowAncestor(this).setVisible(false));
        actionPanel.add(close);
        footer.add(actionPanel, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        loadSettings();

        // ==========================================
        // LISTENERS
        // ==========================================
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

        // Preview only while the editor is visible. Hiding it cancels requests and drops its images; reopening
        // starts from the caller's frame, independently of Main's selection.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            if (isShowing()) {
                updateFrameLabel();
                triggerPreviewUpdate();
            } else {
                previewDebounceTimer.stop();
                closeEditorPreview();
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
        stretchIntensityLabel.setText(algorithm.getPrimaryParameterLabel());
        primaryNumber.getAccessibleContext().setAccessibleName(algorithm.getPrimaryParameterLabel());
        stretchIterationsLabel.setText(algorithm.getSecondaryParameterLabel());
        secondaryNumber.getAccessibleContext().setAccessibleName(algorithm.getSecondaryParameterLabel());
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
        primaryNumber.setEnabled(enabled);
        secondaryNumber.setEnabled(enabled);
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
                        + "signal with an arcsinh curve,<br>keeping star cores from saturating. Strength controls how much faint parts are lifted; "
                        + "above 50 each step lifts them about 10 % more.<br>A black point of 50 % is about the sky level; higher values "
                        + "darken the sky and, near the top, hide faint stars.</html>";
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

    /** Opens or raises the shared editor on the caller's frame; null lets Main choose its selected frame. EDT only. */
    void openEditor(FitsFileInformation frame) {
        editorOpener.accept(frame);
    }

    /** Captures the active sequence and selects the requested frame without changing the Main table. EDT only. */
    boolean selectPreviewFrame(FitsFileInformation requested) {
        if (sessions.current() == null) return false;
        FitsFileInformation[] files = sessions.current().getFiles();
        if (files.length == 0) return false;
        int index = 0;
        if (requested != null) {
            index = -1;
            for (int i = 0; i < files.length; i++) {
                if (java.util.Objects.equals(files[i].getFilePath(), requested.getFilePath())) { index = i; break; }
            }
            if (index < 0) return false;
        }
        previewFiles = files;
        previewIndex = index;
        updateFrameLabel();
        triggerPreviewUpdate();
        return true;
    }

    /** Moves the editor's frame cursor, leaving selection-dependent commands unchanged. EDT only. */
    private void stepFrame(int delta) {
        if (previewFiles.length == 0) return;
        previewIndex = Math.max(0, Math.min(previewFiles.length - 1, previewIndex + delta));
        triggerPreviewUpdate();
    }

    /** The editor's current metadata, or null after the editor closes. EDT only. */
    private FitsFileInformation previewFile() {
        return previewIndex >= 0 && previewIndex < previewFiles.length ? previewFiles[previewIndex] : null;
    }

    /** Updates navigation and the caption from the editor's cursor, never the Main selection. EDT only. */
    private void updateFrameLabel() {
        FitsFileInformation selected = previewFile();
        previousButton.setEnabled(previewIndex > 0);
        nextButton.setEnabled(previewIndex >= 0 && previewIndex < previewFiles.length - 1);
        showFullSizeButton.setEnabled(selected != null);
        frameLabel.setText(selected == null ? "No frame selected"
                : "Frame " + (previewIndex + 1) + " of " + previewFiles.length + " · " + new File(selected.getFilePath()).getName()
                + (rendering ? " · rendering…" : ""));
    }

    /** Schedules a debounced render only while the editor is visible. EDT only. */
    public void triggerPreviewUpdate() {
        // Only a visible editor renders; reopening it renders the requested frame again.
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
        FitsFileInformation selected = previewFile();
        if (selected == null || sessions.current() == null) {
            clearPreviews("Import frames and select one to preview it");
            return;
        }
        originalView.setPlaceholder("Rendering…");
        stretchedView.setPlaceholder("Rendering…");
        renderer.request(new StretchPreviewRenderer.Request(previewPictures, selected.getFilePath(),
                getStretchAlgorithm(), stretchSlider.getValue(), stretchIterationsSlider.getValue(), isDisplayStretchEnabled()));
    }

    /** Renders both previews and their histograms, on the renderer's worker thread. */
    private static StretchPreviewRenderer.Previews render(StretchPreviewRenderer.Request request) throws Exception {
        BufferedImage[] images = new GeneratePreviewsTask(request.renderer, request.filePath, request.strength,
                request.iterations, request.algorithm, request.stretchEnabled).call();
        return new StretchPreviewRenderer.Previews(request, images[0], images[1],
                HistogramView.luminanceHistogram(images[0]), HistogramView.luminanceHistogram(images[1]));
    }

    /** Shows the previews of the newest request; the renderer drops any other. */
    private void showPreviews(StretchPreviewRenderer.Previews previews) {
        originalView.showPicture(previews.original);
        stretchedView.showPicture(previews.stretched);
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
        originalView.showPicture(null);
        stretchedView.showPicture(null);
        originalView.setPlaceholder(placeholder);
        stretchedView.setPlaceholder(placeholder);
        originalHistogramView.setHistogram(null);
        histogramView.setHistogram(null);
        updateFrameLabel();
    }

    /** The previews belong to the session they were rendered for: another session removes them. */
    void sessionChanged(FitsFileInformation[] files) {
        if (sequenceFrame != null) {
            sequenceFrame.closeForSessionChange();
            sequenceFrame = null;
        }
        closeEditorPreview();
    }

    /** Forwards a session frame metadata replacement to the full-size sequence viewer when it is present. */
    void replaceFrameMetadata(FitsFileInformation previous, FitsFileInformation updated) {
        if (sequenceFrame != null) {
            sequenceFrame.replaceFrameMetadata(previous, updated);
        }
        for (int i = 0; i < previewFiles.length; i++) {
            if (previewFiles[i] == previous) previewFiles[i] = updated;
        }
    }

    /** Cancels preview work and releases the editor's images and frame cursor, keeping shared stretch settings. */
    void closeEditorPreview() {
        previewDebounceTimer.stop();
        previewFiles = new FitsFileInformation[0];
        previewIndex = -1;
        clearPreviews("Open the editor on a frame to adjust its stretch");
    }

    /** Opens the linear/colour sequence viewer at the editor's frame. EDT only. */
    private void openFullSize() {
        if (previewFile() == null) return;
        if (sequenceFrame == null || !sequenceFrame.isDisplayable()) sequenceFrame = fullSize.create(this);
        sequenceFrame.openSequence(previewFiles.clone(), previewIndex);
    }

    // ==========================================
    // ACCESSORS (used by Blink, Batch Stretch and the full-size viewer)
    // ==========================================

    public StretchAlgorithm getStretchAlgorithm() {
        return (StretchAlgorithm) stretchAlgoCombo.getSelectedItem();
    }

    public boolean isDisplayStretchEnabled() {
        return displayStretchCheckbox.isSelected();
    }

    /** Shared display choice for every viewer; Batch Stretch is an independent explicit file operation. */
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

    /** A labelled slider with an exact numeric value on the same bounded model. */
    private static JPanel sliderPanel(JLabel label, JSlider slider, JSpinner number) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        panel.add(label, BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);
        panel.add(number, BorderLayout.EAST);
        label.setLabelFor(number);
        return panel;
    }

    /** Keeps an editor number field and slider in sync, including range changes when the function changes. */
    private static JSpinner parameterNumber(JSlider slider) {
        var range = slider.getModel();
        var number = new SpinnerNumberModel(range.getValue(), range.getMinimum(), range.getMaximum(), 1);
        JSpinner spinner = new JSpinner(number);
        boolean[] syncing = {false};
        range.addChangeListener(e -> {
            if (syncing[0]) return;
            syncing[0] = true;
            try {
                number.setMinimum(range.getMinimum());
                number.setMaximum(range.getMaximum());
                number.setValue(range.getValue());
            } finally { syncing[0] = false; }
        });
        number.addChangeListener(e -> {
            if (syncing[0]) return;
            range.setValue(((Number) number.getValue()).intValue());
        });
        UiStyle.numberInput(spinner);
        return spinner;
    }

    private static JPanel titled(String title, JComponent view, JComponent below) {
        return titled(new JLabel(title, SwingConstants.CENTER), view, below);
    }

    private static JPanel titled(JLabel label, JComponent view, JComponent below) {
        JPanel panel = new JPanel(new BorderLayout(0, 5));
        label.setForeground(UiStyle.helpColor());
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
            g2.setFont(UiStyle.bodyFont());
            int textHeight = g2.getFontMetrics().getHeight() + UiStyle.space(4);
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
                g2.setColor(UiStyle.helpColor());
                g2.drawString(String.format(Locale.US, "Clipped: %.1f %% black · %.1f %% white", black, white), 2, getHeight() - 4);
            }
            g2.dispose();
        }
    }
}
