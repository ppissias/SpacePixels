package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.ImageDisplayUtils;
import eu.startales.spacepixels.util.RawImageAnnotator;
import io.github.ppissias.jtransient.core.SourceExtractor;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.List;

public class TransientInspectionFrame extends JFrame {
    private final List<ImageFrame> frames;
    private final List<JTransientEngine.FrameTransients> allTransients;
    private int currentIndex = 0;

    private final JLabel imageLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();

    public TransientInspectionFrame(List<ImageFrame> frames, List<JTransientEngine.FrameTransients> allTransients) {
        this.frames = frames;
        this.allTransients = allTransients;

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
        contentPane.add(bottomPanel, BorderLayout.SOUTH);

        // Setup robust Keyboard Bindings for Left/Right/Up/Down navigation
        setupKeyBindings();

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
            if (f.identifier != null && f.identifier.equals(ft.filename)) {
                frame = f;
                break;
            }
        }

        if (frame == null) {
            statusLabel.setText("  Frame: [" + (currentIndex + 1) + " / " + allTransients.size() + "]   |   File: " + ft.filename + " (Image data not found)");
            return;
        }

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
}