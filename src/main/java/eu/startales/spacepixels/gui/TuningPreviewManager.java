package eu.startales.spacepixels.gui;

import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.TuningPreviewFinishedEvent;
import eu.startales.spacepixels.events.TuningPreviewStartedEvent;
import eu.startales.spacepixels.tasks.TuningPreviewTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import io.github.ppissias.jtransient.config.DetectionConfig;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class TuningPreviewManager {

    private final ApplicationWindow mainAppWindow;
    private JDialog previewDialog;
    private JLabel previewImageLabel;
    private JLabel cursorStatusLabel;
    private FitsFileInformation[] previewFiles;
    private int currentPreviewIndex = 0;

    // --- NEW: Thread Lock ---
    private boolean isGenerating = false;

    private DetectionConfig currentConfig;
    private WcsCoordinateTransformer currentWcsTransformer;
    private WcsSolutionResolver.ResolvedWcsSolution currentWcsSolution;

    public TuningPreviewManager(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
    }

    public void showPreview(DetectionConfig config) {
        this.currentConfig = config;

        try {
            previewFiles = mainAppWindow.getImageProcessing().getFitsfileInformation();
        } catch (Exception ex) {
            System.err.println("Could not load FITS files for preview: " + ex.getMessage());
        }

        if (previewFiles == null || previewFiles.length == 0) {
            JOptionPane.showMessageDialog(mainAppWindow.getFrame(), "No FITS files are currently loaded to preview!", "No Images", JOptionPane.WARNING_MESSAGE);
            return;
        }

        currentPreviewIndex = 0;
        FitsFileInformation targetFile = null;
        try {
            targetFile = mainAppWindow.getMainApplicationPanel().getSelectedFileInformation();
        } catch (Exception ignored) {}

        if (targetFile != null) {
            for (int i = 0; i < previewFiles.length; i++) {
                if (previewFiles[i].getFilePath().equals(targetFile.getFilePath())) {
                    currentPreviewIndex = i;
                    break;
                }
            }
        }

        if (previewDialog == null) {
            previewDialog = new JDialog(mainAppWindow.getFrame(), "Tuning Preview", false);
            previewDialog.setLayout(new BorderLayout());

            previewImageLabel = new JLabel();
            previewImageLabel.setHorizontalAlignment(SwingConstants.CENTER);
            installCursorTracking();

            JScrollPane scrollPane = new JScrollPane(previewImageLabel);
            scrollPane.getVerticalScrollBar().setUnitIncrement(16);

            previewDialog.add(scrollPane, BorderLayout.CENTER);

            JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            statusPanel.setBorder(BorderFactory.createEtchedBorder());
            cursorStatusLabel = new JLabel(" Cursor: WCS unavailable");
            statusPanel.add(cursorStatusLabel);
            previewDialog.add(statusPanel, BorderLayout.SOUTH);

            previewDialog.setSize(1024, 768);
            previewDialog.setLocationRelativeTo(mainAppWindow.getFrame());

            // Lifecycle: Register/Unregister from EventBus to prevent memory leaks
            previewDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowOpened(WindowEvent e) {
                    mainAppWindow.getEventBus().register(TuningPreviewManager.this);
                }

                @Override
                public void windowClosed(WindowEvent windowEvent) {
                    mainAppWindow.getEventBus().unregister(TuningPreviewManager.this);
                    previewDialog = null;
                    previewImageLabel = null;
                    cursorStatusLabel = null;
                    previewFiles = null;
                    currentWcsTransformer = null;
                    currentWcsSolution = null;
                }
            });
            previewDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

            setupPreviewKeyBindings(previewDialog.getRootPane());
        }

        if (!previewDialog.isVisible()) {
            previewDialog.setVisible(true);
        } else {
            previewDialog.toFront();
        }

        renderPreviewFrame();
    }

    private void setupPreviewKeyBindings(JRootPane rootPane) {
        InputMap inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap actionMap = rootPane.getActionMap();

        inputMap.put(KeyStroke.getKeyStroke("UP"), "prevFrame");
        inputMap.put(KeyStroke.getKeyStroke("LEFT"), "prevFrame");
        inputMap.put(KeyStroke.getKeyStroke("DOWN"), "nextFrame");
        inputMap.put(KeyStroke.getKeyStroke("RIGHT"), "nextFrame");

        actionMap.put("prevFrame", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Lock check to prevent thread spamming
                if (!isGenerating && previewFiles != null && currentPreviewIndex > 0) {
                    currentPreviewIndex--;
                    renderPreviewFrame();
                }
            }
        });

        actionMap.put("nextFrame", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Lock check to prevent thread spamming
                if (!isGenerating && previewFiles != null && currentPreviewIndex < previewFiles.length - 1) {
                    currentPreviewIndex++;
                    renderPreviewFrame();
                }
            }
        });
    }

    private void renderPreviewFrame() {
        if (previewFiles == null || currentPreviewIndex < 0 || currentPreviewIndex >= previewFiles.length) return;

        FitsFileInformation target = previewFiles[currentPreviewIndex];
        updateCurrentWcsSolution(target);
        if (cursorStatusLabel != null) {
            cursorStatusLabel.setText(buildDefaultCursorStatus());
        }

        // Fire off the decoupled task
        new Thread(new TuningPreviewTask(
                mainAppWindow.getEventBus(),
                target,
                currentConfig,
                currentPreviewIndex + 1,
                previewFiles.length
        )).start();
    }

    // =========================================================================
    // EVENT BUS SUBSCRIBERS
    // =========================================================================

    @Subscribe
    public void onPreviewStarted(TuningPreviewStartedEvent event) {
        EventQueue.invokeLater(() -> {
            isGenerating = true; // Lock the arrow keys
            if (previewDialog != null && previewFiles != null) {
                previewDialog.setTitle(String.format("Processing Frame [%d/%d]...", currentPreviewIndex + 1, previewFiles.length));
            }
        });
    }

    @Subscribe
    public void onPreviewFinished(TuningPreviewFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            isGenerating = false; // Unlock the arrow keys

            if (previewDialog != null) {
                if (event.isSuccess()) {
                    previewDialog.setTitle(event.getWindowTitle());
                    previewImageLabel.setIcon(new ImageIcon(event.getPreviewImage()));
                    previewImageLabel.revalidate();
                    previewImageLabel.repaint();
                    if (cursorStatusLabel != null) {
                        cursorStatusLabel.setText(buildDefaultCursorStatus());
                    }
                } else {
                    previewDialog.setTitle("Tuning Preview - ERROR");
                    JOptionPane.showMessageDialog(previewDialog,
                            "Failed to generate preview: " + event.getErrorMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });
    }

    private void installCursorTracking() {
        previewImageLabel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateCursorStatus(e.getX(), e.getY());
            }
        });
        previewImageLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                if (cursorStatusLabel != null) {
                    cursorStatusLabel.setText(buildDefaultCursorStatus());
                }
            }
        });
    }

    private void updateCursorStatus(int pixelX, int pixelY) {
        if (cursorStatusLabel == null) {
            return;
        }

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
        currentWcsSolution = WcsSolutionResolver.resolve(target, previewFiles);
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
