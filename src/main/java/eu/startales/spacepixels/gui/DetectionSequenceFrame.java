package eu.startales.spacepixels.gui;

import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.tasks.DetectionTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

public class DetectionSequenceFrame extends JFrame {

    private final ApplicationWindow mainAppWindow;

    // UI Components
    private final JPanel imagePreviewPanel = new JPanel();
    private final ImageVisualizerComponent imageComponent = new ImageVisualizerComponent();
    private JScrollPane scrollPane;

    // Sequence State
    private FitsFileInformation[] sequenceFiles;
    private int currentIndex = 0;
    private boolean isGenerating = false;
    private boolean isRegistered = false; // --- NEW: Track EventBus state ---

    private DetectionConfig currentConfig;

    public DetectionSequenceFrame(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;

        setTitle("Detection Sequence Viewer");
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
     * Entry point to launch the viewer with a specific file sequence and configuration.
     */
    public void openSequence(FitsFileInformation[] files, int startIndex, DetectionConfig config) {
        this.sequenceFiles = files;
        this.currentIndex = startIndex;
        this.currentConfig = config;

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

        inputMap.put(KeyStroke.getKeyStroke("UP"), "prevFrame");
        inputMap.put(KeyStroke.getKeyStroke("LEFT"), "prevFrame");
        inputMap.put(KeyStroke.getKeyStroke("DOWN"), "nextFrame");
        inputMap.put(KeyStroke.getKeyStroke("RIGHT"), "nextFrame");

        actionMap.put("prevFrame", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!isGenerating && sequenceFiles != null && currentIndex > 0) {
                    currentIndex--;
                    generateCurrentFrame();
                }
            }
        });

        actionMap.put("nextFrame", new AbstractAction() {
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
        setTitle(String.format("Detecting: %s [%d/%d] - Processing...",
                target.getFileName(), currentIndex + 1, sequenceFiles.length));

        // Fire the detection task for this specific frame
        new Thread(new DetectionTask(
                mainAppWindow.getEventBus(),
                mainAppWindow.getImageProcessing(),
                new FitsFileInformation[]{target},
                currentConfig
        )).start();
    }

    // --- EVENT BUS LISTENERS FOR THIS SPECIFIC FRAME ---

    @Subscribe
    public void onDetectionStarted(DetectionStartedEvent event) {
        EventQueue.invokeLater(() -> isGenerating = true);
    }

    @Subscribe
    public void onDetectionFinished(DetectionFinishedEvent event) {
        // We only care about quick/single detection events here, not the batch report
        if (!event.isQuickDetection()) return;

        EventQueue.invokeLater(() -> {
            isGenerating = false;

            if (event.isSuccess()) {
                FitsFileInformation target = sequenceFiles[currentIndex];
                setTitle(String.format("Detections: %s [%d/%d] | Stars: %d | Streaks: %d",
                        target.getFileName(), currentIndex + 1, sequenceFiles.length,
                        event.getStarCount(), event.getStreakCount()));

                setImage(event.getAnnotatedImage());
            } else {
                setTitle("Detection - ERROR");
                JOptionPane.showMessageDialog(this,
                        "Detection failed: " + event.getErrorMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    private void setImage(BufferedImage image) {
        if (image == null) return;
        imageComponent.setImage(image);
        imageComponent.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
        imagePreviewPanel.revalidate();
        imagePreviewPanel.repaint();
    }
}