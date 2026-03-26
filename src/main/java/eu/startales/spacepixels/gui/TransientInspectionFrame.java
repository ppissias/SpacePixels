package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageDisplayUtils;
import eu.startales.spacepixels.util.RawImageAnnotator;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TransientInspectionFrame extends JFrame {
    private final List<ImageFrame> frames;
    private final List<JTransientEngine.FrameTransients> allTransients;
    private final Map<String, FitsFileInformation> fileInfoByName = new HashMap<>();
    private final FitsFileInformation[] allFilesInfo;
    private int currentIndex = 0;
    private WcsCoordinateTransformer currentWcsTransformer;
    private WcsSolutionResolver.ResolvedWcsSolution currentWcsSolution;

    private final JLabel imageLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final JLabel cursorStatusLabel = new JLabel(" Cursor: WCS unavailable");

    public TransientInspectionFrame(List<ImageFrame> frames, List<JTransientEngine.FrameTransients> allTransients, FitsFileInformation[] filesInfo) {
        this.frames = frames;
        this.allTransients = allTransients;
        this.allFilesInfo = filesInfo;
        if (filesInfo != null) {
            for (FitsFileInformation info : filesInfo) {
                if (info != null && info.getFileName() != null) {
                    fileInfoByName.put(info.getFileName(), info);
                }
            }
        }

        setTitle("Manual Transient Inspection");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(null); // Center on screen

        JPanel contentPane = new JPanel(new BorderLayout(0, 0));
        contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        setContentPane(contentPane);

        // Setup Scroll Pane
        JScrollPane scrollPane = new JScrollPane(imageLabel);
        contentPane.add(scrollPane, BorderLayout.CENTER);

        // Setup Status Bar
        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomPanel.setBorder(BorderFactory.createEtchedBorder());
        statusLabel.setFont(new Font("Segoe UI", Font.BOLD, 14));
        bottomPanel.add(statusLabel);
        bottomPanel.add(Box.createHorizontalStrut(20));
        bottomPanel.add(cursorStatusLabel);
        contentPane.add(bottomPanel, BorderLayout.SOUTH);

        // Setup robust Keyboard Bindings for Left/Right/Up/Down navigation
        setupKeyBindings();
        installCursorTracking();

        if (!frames.isEmpty()) {
            renderCurrentFrame();
        }
    }

    private void setupKeyBindings() {
        InputMap im = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = getRootPane().getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "nextFrame");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "nextFrame");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "prevFrame");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "prevFrame");

        am.put("nextFrame", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // Navigate based on the purified list, not the raw frames list
                if (allTransients != null && currentIndex < allTransients.size() - 1) {
                    currentIndex++;
                    renderCurrentFrame();
                }
            }
        });

        am.put("prevFrame", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (allTransients != null && currentIndex > 0) {
                    currentIndex--;
                    renderCurrentFrame();
                }
            }
        });
    }

    private void renderCurrentFrame() {
        if (allTransients == null || currentIndex < 0 || currentIndex >= allTransients.size()) {
            return;
        }

        JTransientEngine.FrameTransients ft = allTransients.get(currentIndex);
        List<SourceExtractor.DetectedObject> frameTransients = ft.transients != null ? ft.transients : java.util.Collections.emptyList();
        
        // Find the corresponding original ImageFrame using the filename provided by the engine
        ImageFrame frame = null;
        for (ImageFrame f : frames) {
            if (f.filename != null && f.filename.equals(ft.filename)) {
                frame = f;
                break;
            }
        }

        if (frame == null) {
            statusLabel.setText("  Frame: [" + (currentIndex + 1) + " / " + allTransients.size() + "]   |   File: " + ft.filename + " (Image data not found)");
            currentWcsTransformer = null;
            cursorStatusLabel.setText(" Cursor: WCS unavailable");
            return;
        }

        FitsFileInformation fileInfo = fileInfoByName.get(ft.filename);
        updateCurrentWcsSolution(fileInfo);
        cursorStatusLabel.setText(buildDefaultCursorStatus());

        BufferedImage grayImage = ImageDisplayUtils.createDisplayImage(frame.pixelData);
        BufferedImage displayImage = new BufferedImage(grayImage.getWidth(), grayImage.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = displayImage.createGraphics();
        g2d.drawImage(grayImage, 0, 0, null);
        g2d.dispose();

        // Draw the highly visible ARGB footprints we created previously
        RawImageAnnotator.drawExactBlobs(displayImage, frameTransients);
        RawImageAnnotator.drawDetections(displayImage, frameTransients);

        imageLabel.setIcon(new ImageIcon(displayImage));
        statusLabel.setText("  Frame: [" + (currentIndex + 1) + " / " + allTransients.size() + "]   |   File: " + ft.filename + "   |   Transients Detected: " + frameTransients.size() + "   |   (Use Arrow Keys to Navigate)");
        
        repaint();
    }

    private void installCursorTracking() {
        imageLabel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                updateCursorStatus(e.getX(), e.getY());
            }
        });
        imageLabel.addMouseListener(new MouseAdapter() {
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

    private void updateCurrentWcsSolution(FitsFileInformation fileInfo) {
        currentWcsSolution = WcsSolutionResolver.resolve(fileInfo, allFilesInfo);
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
