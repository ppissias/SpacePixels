package spv.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.JScrollPane;

public class FullImageStretchPreviewFrame extends JFrame {

	private JPanel contentPane;

	private JPanel imagePreviewPanel = new JPanel();
	private JLabel imageLabel;
	
	private ImageVisualizerComponent imageComponent = new ImageVisualizerComponent();
	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					FullImageStretchPreviewFrame frame = new FullImageStretchPreviewFrame();
					frame.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the frame.
	 */
	public FullImageStretchPreviewFrame() {
		setTitle("Full size");
		setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
		setBounds(100, 100, 800, 600);
		contentPane = new JPanel();
		contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		contentPane.setLayout(new BorderLayout(0, 0));
		setContentPane(contentPane);
		
		JScrollPane scrollPane = new JScrollPane();
		contentPane.add(scrollPane, BorderLayout.CENTER);
		imageLabel = new JLabel();
        
		imagePreviewPanel.add(imageComponent);		
		scrollPane.setViewportView(imagePreviewPanel);
		
	}
	
	
	public void setImage(BufferedImage image) {
		//https://docs.oracle.com/javase/tutorial/2d/images/drawimage.html
        //ImageIcon newImageIcon = new ImageIcon( image );
        //imageLabel.setIcon(newImageIcon);
		ApplicationWindow.logger.info("setting image");
		imageComponent.setImage(image);
	}

}
