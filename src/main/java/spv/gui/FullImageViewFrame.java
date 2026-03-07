package spv.gui;

import com.google.common.eventbus.EventBus;
import spv.events.FullImageViewFrameClosedEvent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;

public class FullImageViewFrame extends JFrame {

    private final JPanel imagePreviewPanel = new JPanel();
    private ImageVisualizerComponent imageComponent = new ImageVisualizerComponent();

    private final EventBus eventBus;

    /**
     * Create the frame.
     */
    public FullImageViewFrame(EventBus eventBus) {
        this.eventBus = eventBus;

        setTitle("Full size");

        // 1. REVERTED TO HIDE_ON_CLOSE so the singleton can be reused
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int) (screenSize.width * 0.8);
        int height = (int) (screenSize.height * 0.8);
        setSize(width, height);
        setLocationRelativeTo(null);

        JPanel contentPane = new JPanel();
        contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
        contentPane.setLayout(new BorderLayout(0, 0));
        setContentPane(contentPane);

        JScrollPane scrollPane = new JScrollPane();
        contentPane.add(scrollPane, BorderLayout.CENTER);

        imagePreviewPanel.add(imageComponent);
        scrollPane.setViewportView(imagePreviewPanel);

        // 2. CHANGED TO windowClosing (which fires right before HIDE_ON_CLOSE acts)
        this.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Free up the RAM by dropping the heavy image reference
                imageComponent.setImage(null);

                // Fire the event so MainApplicationPanel knows to stop the Blink thread
                if (eventBus != null) {
                    eventBus.post(new FullImageViewFrameClosedEvent());
                }
            }
        });
    }

    public void setImage(BufferedImage image) {
        ApplicationWindow.logger.info("setting image");
        imageComponent.setImage(image);

        // This ensures the JScrollPane knows the true size of the image so scrollbars appear
        imageComponent.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));

        imagePreviewPanel.revalidate();
        imagePreviewPanel.repaint();
    }
}