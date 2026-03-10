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

import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.PreviewGenerationFinishedEvent;
import eu.startales.spacepixels.tasks.GeneratePreviewsTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.StretchAlgorithm;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;

public class StretchPanel extends JPanel {
    private final ApplicationWindow mainAppWindow;

    // Controls
    private final JSlider stretchSlider;
    private final JCheckBox stretchCheckbox;
    private final JComboBox<StretchAlgorithm> stretchAlgoCombo;
    private final JSlider stretchIterationsSlider;
    private final JButton showFullSizeButton;

    // Dynamic Labels
    private final JLabel stretchIntensityLabel;
    private final JLabel stretchIterationsLabel;

    // Previews
    private final ImageVisualizerComponent originalImageComponent = new ImageVisualizerComponent();
    private final ImageVisualizerComponent stretchedImageComponent = new ImageVisualizerComponent();

    private final Timer previewDebounceTimer;

    // --- NEW: Reference to our specialized frame ---
    private StretchedSequenceFrame sequenceFrame;

    public StretchPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        this.mainAppWindow.getEventBus().register(this);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 20, 20, 20));

        previewDebounceTimer = new Timer(250, e -> executePreviewTask());
        previewDebounceTimer.setRepeats(false);

        // ==========================================
        // MAIN CONTENT CONTAINER
        // ==========================================
        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));

        mainContent.add(createSectionHeader("Stretch Algorithm & Global Settings"));

        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 10));
        stretchCheckbox = new JCheckBox("Enable Stretching (for blinking and batch export)");
        stretchCheckbox.setToolTipText("If checked, exported images will have these settings applied.");
        stretchCheckbox.setFont(stretchCheckbox.getFont().deriveFont(Font.BOLD, 13f));

        stretchAlgoCombo = new JComboBox<>(StretchAlgorithm.values());
        stretchAlgoCombo.setSelectedIndex(0);
        stretchAlgoCombo.setEnabled(false);

        topRow.add(stretchCheckbox);
        topRow.add(Box.createHorizontalStrut(20));
        topRow.add(stretchAlgoCombo);

        topRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        mainContent.add(topRow);
        mainContent.add(Box.createVerticalStrut(10));

        mainContent.add(createSectionHeader("Intensity & Iterations"));

        JPanel slidersRow = new JPanel(new GridLayout(1, 2, 40, 0));
        slidersRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel leftSliderPanel = new JPanel(new BorderLayout(0, 5));
        stretchIntensityLabel = new JLabel("Intensity Threshold");
        stretchIntensityLabel.setFont(stretchIntensityLabel.getFont().deriveFont(Font.BOLD, 12f));
        stretchSlider = new JSlider();
        stretchSlider.setEnabled(false);
        leftSliderPanel.add(stretchIntensityLabel, BorderLayout.NORTH);
        leftSliderPanel.add(stretchSlider, BorderLayout.CENTER);

        JPanel rightSliderPanel = new JPanel(new BorderLayout(0, 5));
        stretchIterationsLabel = new JLabel("Application Iterations");
        stretchIterationsLabel.setFont(stretchIterationsLabel.getFont().deriveFont(Font.BOLD, 12f));
        stretchIterationsSlider = new JSlider();
        stretchIterationsSlider.setPaintTicks(true);
        stretchIterationsSlider.setSnapToTicks(true);
        stretchIterationsSlider.setMajorTickSpacing(1);
        stretchIterationsSlider.setMinimum(1);
        stretchIterationsSlider.setValue(1);
        stretchIterationsSlider.setMaximum(20);
        stretchIterationsSlider.setEnabled(false);
        rightSliderPanel.add(stretchIterationsLabel, BorderLayout.NORTH);
        rightSliderPanel.add(stretchIterationsSlider, BorderLayout.CENTER);

        slidersRow.add(leftSliderPanel);
        slidersRow.add(rightSliderPanel);
        mainContent.add(slidersRow);
        mainContent.add(Box.createVerticalStrut(20));

        add(mainContent, BorderLayout.NORTH);

        // ==========================================
        // CENTER AREA: PREVIEWS
        // ==========================================
        JPanel previewMainContainer = new JPanel(new BorderLayout());
        previewMainContainer.add(createSectionHeader("Real-Time Preview"), BorderLayout.NORTH);

        JPanel previewImagesContainer = new JPanel(new GridLayout(1, 2, 15, 0));

        JPanel originalWrapper = new JPanel(new BorderLayout(0, 5));
        JLabel origTitle = new JLabel("Original Image", SwingConstants.CENTER);
        origTitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        originalWrapper.add(origTitle, BorderLayout.NORTH);
        originalWrapper.add(originalImageComponent, BorderLayout.CENTER);

        JPanel stretchedWrapper = new JPanel(new BorderLayout(0, 5));
        JLabel stretchTitle = new JLabel("Stretched Preview", SwingConstants.CENTER);
        stretchTitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        stretchedWrapper.add(stretchTitle, BorderLayout.NORTH);
        stretchedWrapper.add(stretchedImageComponent, BorderLayout.CENTER);

        previewImagesContainer.add(originalWrapper);
        previewImagesContainer.add(stretchedWrapper);

        previewMainContainer.add(previewImagesContainer, BorderLayout.CENTER);
        add(previewMainContainer, BorderLayout.CENTER);

        // ==========================================
        // 3. BOTTOM AREA: ACTIONS
        // ==========================================
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actionPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        showFullSizeButton = new JButton("Show full size");
        showFullSizeButton.setEnabled(false);
        actionPanel.add(showFullSizeButton);
        add(actionPanel, BorderLayout.SOUTH);

        // ==========================================
        // 4. LISTENERS
        // ==========================================
        stretchCheckbox.addItemListener(e -> {
            boolean enabled = stretchCheckbox.isSelected();
            mainAppWindow.getMainApplicationPanel().setBatchStretchButtonEnabled(enabled);
            stretchAlgoCombo.setEnabled(enabled);
            stretchSlider.setEnabled(enabled);
            stretchIterationsSlider.setEnabled(enabled);
            showFullSizeButton.setEnabled(enabled);

            if (enabled) {
                mainAppWindow.getMainApplicationPanel().selectFirstFileIfNoneSelected();
                triggerPreviewUpdate();
            } else {
                setOriginalImage(null);
                setStretchedImage(null);
            }
        });

        stretchAlgoCombo.addActionListener(e -> {
            if (StretchAlgorithm.EXTREME.equals(stretchAlgoCombo.getSelectedItem())) {
                stretchIntensityLabel.setText("Noise Threshold");
                stretchIterationsLabel.setText("Intensity Factor");
                stretchIterationsSlider.setValue(15);
            } else {
                stretchIntensityLabel.setText("Intensity Threshold");
                stretchIterationsLabel.setText("Application Iterations");
            }
            if (stretchCheckbox.isSelected()) triggerPreviewUpdate();
        });

        stretchSlider.addChangeListener(e -> {
            if (stretchCheckbox.isSelected()) triggerPreviewUpdate();
        });

        stretchIterationsSlider.addChangeListener(e -> {
            if (stretchCheckbox.isSelected()) triggerPreviewUpdate();
        });

        // --- UPDATED: Launch the Specialized Frame ---
        showFullSizeButton.addActionListener(e -> {
            FitsFileInformation[] files;
            try {
                files = mainAppWindow.getImageProcessing().getFitsfileInformation();
            } catch (Exception ex) {
                System.err.println("Could not load FITS files: " + ex.getMessage());
                return;
            }

            if (files == null || files.length == 0) return;

            // Find current starting point
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

            // Lazy initialization of the frame
            if (sequenceFrame == null) {
                sequenceFrame = new StretchedSequenceFrame(mainAppWindow, this);
            }

            sequenceFrame.openSequence(files, startIndex);
        });
    }

    // ==========================================
    // UI HELPER METHODS
    // ==========================================

    private JLabel createSectionHeader(String title) {
        JLabel headerLabel = new JLabel(title);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 16f));

        Color accentColor = UIManager.getColor("Component.accentColor");
        if (accentColor == null) {
            accentColor = Color.decode("#4285f4");
        }
        headerLabel.setForeground(accentColor);
        headerLabel.setBorder(new EmptyBorder(20, 0, 10, 0));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return headerLabel;
    }

    // ==========================================
    // TASK EXECUTION & SUBSCRIBERS
    // ==========================================

    public void triggerPreviewUpdate() {
        if (previewDebounceTimer.isRunning()) {
            previewDebounceTimer.restart();
        } else {
            previewDebounceTimer.start();
        }
    }

    private void executePreviewTask() {
        FitsFileInformation selected = mainAppWindow.getMainApplicationPanel().getSelectedFileInformation();
        if (selected == null) return;

        new Thread(new GeneratePreviewsTask(
                mainAppWindow.getEventBus(),
                mainAppWindow.getImageProcessing(),
                selected.getFilePath(),
                stretchSlider.getValue(),
                stretchIterationsSlider.getValue(),
                (StretchAlgorithm) stretchAlgoCombo.getSelectedItem()
        )).start();
    }

    @Subscribe
    public void onPreviewGenerationFinished(PreviewGenerationFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            if (event.isSuccess()) {
                setOriginalImage(event.getOriginalImage());
                setStretchedImage(event.getStretchedImage());
            } else {
                setOriginalImage(null);
                setStretchedImage(null);
            }
        });
    }

    // ==========================================
    // UTILITIES
    // ==========================================
    public StretchAlgorithm getStretchAlgorithm() { return (StretchAlgorithm) stretchAlgoCombo.getSelectedItem(); }
    public boolean isStretchEnabled() { return stretchCheckbox.isSelected(); }
    public JSlider getStretchSlider() { return stretchSlider; }
    public JSlider getStretchIterationsSlider() { return stretchIterationsSlider; }

    private void setOriginalImage(BufferedImage image) {
        originalImageComponent.setImage(image);
        originalImageComponent.repaint();
    }

    private void setStretchedImage(BufferedImage image) {
        stretchedImageComponent.setImage(image);
        stretchedImageComponent.repaint();
    }
}