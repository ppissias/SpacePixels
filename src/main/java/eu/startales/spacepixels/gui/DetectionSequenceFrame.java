package eu.startales.spacepixels.gui;

import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.DetectionFinishedEvent;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.tasks.DetectionTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import io.github.ppissias.jtransient.config.DetectionConfig;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

public class DetectionSequenceFrame extends JFrame {

    private final ApplicationWindow mainAppWindow;

    // UI Components
    private final JPanel imagePreviewPanel = new JPanel();
    private final ImageVisualizerComponent imageComponent = new ImageVisualizerComponent();
    private final JLabel frameStatusLabel = new JLabel(" Ready");
    private final JLabel cursorStatusLabel = new JLabel(" Cursor: WCS unavailable");
    private JScrollPane scrollPane;

    // Sequence State
    private FitsFileInformation[] sequenceFiles;
    private int currentIndex = 0;
    private boolean isGenerating = false;
    private boolean isRegistered = false; // --- NEW: Track EventBus state ---

    private DetectionConfig currentConfig;
    private WcsCoordinateTransformer currentWcsTransformer;
    private WcsSolutionResolver.ResolvedWcsSolution currentWcsSolution;

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

        JPanel bottomPanel = new JPanel(new BorderLayout(10, 0));
        bottomPanel.setBorder(BorderFactory.createEtchedBorder());
        bottomPanel.add(frameStatusLabel, BorderLayout.WEST);
        bottomPanel.add(cursorStatusLabel, BorderLayout.EAST);
        contentPane.add(bottomPanel, BorderLayout.SOUTH);

        // Lifecycle Management: Free up RAM immediately when closed
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                imageComponent.setImage(null);
                currentWcsTransformer = null;
                currentWcsSolution = null;
                cursorStatusLabel.setText(" Cursor: WCS unavailable");
            }
        });

        installCursorTracking();
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
        updateCurrentWcsSolution(target);
        cursorStatusLabel.setText(buildDefaultCursorStatus());

        // Give immediate visual feedback
        setTitle(String.format("Detecting: %s [%d/%d] - Processing...",
                target.getFileName(), currentIndex + 1, sequenceFiles.length));
        frameStatusLabel.setText(String.format(" Frame: %s [%d/%d] - Processing...",
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
                frameStatusLabel.setText(String.format(" Frame: %s [%d/%d] | Stars: %d | Streaks: %d",
                        target.getFileName(), currentIndex + 1, sequenceFiles.length,
                        event.getStarCount(), event.getStreakCount()));
                updateCurrentWcsSolution(target);
                cursorStatusLabel.setText(buildDefaultCursorStatus());

                setImage(event.getAnnotatedImage());
            } else {
                setTitle("Detection - ERROR");
                frameStatusLabel.setText(" Detection - ERROR");
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

    private void installCursorTracking() {
        imageComponent.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateCursorStatus(e.getX(), e.getY());
            }
        });
        imageComponent.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                cursorStatusLabel.setText(buildDefaultCursorStatus());
            }
        });
    }

    private void updateCursorStatus(int pixelX, int pixelY) {
        if (currentWcsTransformer == null) {
            cursorStatusLabel.setText(String.format(" Cursor: x=%d y=%d | WCS unavailable", pixelX, pixelY));
            return;
        }

        WcsCoordinateTransformer.SkyCoordinate skyCoordinate = currentWcsTransformer.pixelToSky(pixelX, pixelY);
        cursorStatusLabel.setText(String.format(
                " Cursor: x=%d y=%d | RA %s | Dec %s",
                pixelX,
                pixelY,
                WcsCoordinateTransformer.formatRa(skyCoordinate.getRaDegrees()),
                WcsCoordinateTransformer.formatDec(skyCoordinate.getDecDegrees())
        ));
    }

    private void updateCurrentWcsSolution(FitsFileInformation target) {
        currentWcsSolution = WcsSolutionResolver.resolve(target, sequenceFiles);
        currentWcsTransformer = currentWcsSolution != null ? currentWcsSolution.getTransformer() : null;
    }

    private String buildDefaultCursorStatus() {
        if (currentWcsSolution == null) {
            return " Cursor: WCS unavailable";
        }
        return currentWcsSolution.isSharedAcrossAlignedSet()
                ? " Cursor: move over the image for RA/Dec (shared aligned WCS)"
                : " Cursor: move over the image for RA/Dec";
    }
}
