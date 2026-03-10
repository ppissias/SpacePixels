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
import eu.startales.spacepixels.events.FullSizeGenerationFinishedEvent;
import eu.startales.spacepixels.events.FullSizeGenerationStartedEvent;
import eu.startales.spacepixels.tasks.GenerateFullSizeTask;
import eu.startales.spacepixels.util.FitsFileInformation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

public class StretchedSequenceFrame extends JFrame {

    private final ApplicationWindow mainAppWindow;
    private final StretchPanel stretchPanel;

    // UI Components
    private final JPanel imagePreviewPanel = new JPanel();
    private final ImageVisualizerComponent imageComponent = new ImageVisualizerComponent();
    private final JScrollPane scrollPane;

    // Sequence State
    private FitsFileInformation[] sequenceFiles;
    private int currentIndex = 0;
    private boolean isGenerating = false;
    private boolean isRegistered = false; // --- NEW: Track EventBus state ---

    public StretchedSequenceFrame(ApplicationWindow mainAppWindow, StretchPanel stretchPanel) {
        this.mainAppWindow = mainAppWindow;
        this.stretchPanel = stretchPanel;

        setTitle("Stretched Sequence Viewer");
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(mainAppWindow.getFrame());

        JPanel contentPane = new JPanel(new BorderLayout(0, 0));
        contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        setContentPane(contentPane);

        scrollPane = new JScrollPane();
        contentPane.add(scrollPane, BorderLayout.CENTER);

        imagePreviewPanel.add(imageComponent);
        scrollPane.setViewportView(imagePreviewPanel);

        // Lifecycle Management: Free up RAM immediately when closed
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                imageComponent.setImage(null);
            }
        });

        setupKeyBindings();
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);

        // Safely manage EventBus registration tied directly to window visibility
        if (visible && !isRegistered) {
            mainAppWindow.getEventBus().register(this);
            isRegistered = true;
        } else if (!visible && isRegistered) {
            mainAppWindow.getEventBus().unregister(this);
            isRegistered = false;
        }
    }

    /**
     * Entry point to launch the viewer with a specific file sequence.
     */
    public void openSequence(FitsFileInformation[] files, int startIndex) {
        this.sequenceFiles = files;
        this.currentIndex = startIndex;

        if (!isVisible()) {
            setVisible(true);
        } else {
            toFront();
        }

        generateCurrentFrame();
    }

    private void setupKeyBindings() {
        JRootPane rootPane = this.getRootPane();
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke("UP"), "prevStretch");
        inputMap.put(KeyStroke.getKeyStroke("LEFT"), "prevStretch");
        inputMap.put(KeyStroke.getKeyStroke("DOWN"), "nextStretch");
        inputMap.put(KeyStroke.getKeyStroke("RIGHT"), "nextStretch");

        actionMap.put("prevStretch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!isGenerating && sequenceFiles != null && currentIndex > 0) {
                    currentIndex--;
                    generateCurrentFrame();
                }
            }
        });

        actionMap.put("nextStretch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!isGenerating && sequenceFiles != null && currentIndex < sequenceFiles.length - 1) {
                    currentIndex++;
                    generateCurrentFrame();
                }
            }
        });
    }

    private void generateCurrentFrame() {
        if (sequenceFiles == null || currentIndex < 0 || currentIndex >= sequenceFiles.length) return;

        FitsFileInformation target = sequenceFiles[currentIndex];

        // Give immediate visual feedback
        setTitle(String.format("Stretched Image: %s [%d/%d] - Processing...",
                target.getFileName(), currentIndex + 1, sequenceFiles.length));

        // Fire the heavy task! Notice it grabs the LIVE values from the StretchPanel
        // so you can tweak sliders while this window is open.
        new Thread(new GenerateFullSizeTask(
                mainAppWindow.getEventBus(),
                mainAppWindow.getImageProcessing(),
                target.getFilePath(),
                target.getSizeWidth(),
                target.getSizeHeight(),
                stretchPanel.getStretchSlider().getValue(),
                stretchPanel.getStretchIterationsSlider().getValue(),
                stretchPanel.getStretchAlgorithm()
        )).start();
    }

    // --- EVENT BUS LISTENERS FOR THIS SPECIFIC FRAME ---

    @Subscribe
    public void onFullSizeGenerationStarted(FullSizeGenerationStartedEvent event) {
        EventQueue.invokeLater(() -> isGenerating = true);
    }

    @Subscribe
    public void onFullSizeGenerationFinished(FullSizeGenerationFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            isGenerating = false;

            if (event.isSuccess()) {
                FitsFileInformation target = sequenceFiles[currentIndex];
                setTitle(String.format("Stretched Image: %s [%d/%d]",
                        target.getFileName(), currentIndex + 1, sequenceFiles.length));

                setImage(event.getFullSizeImage());
            } else {
                setTitle("Stretched Image - ERROR");
                JOptionPane.showMessageDialog(this,
                        "Cannot show full image: " + event.getErrorMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void setImage(BufferedImage image) {
        imageComponent.setImage(image);
        imageComponent.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
        imagePreviewPanel.revalidate();
        imagePreviewPanel.repaint();
    }
}