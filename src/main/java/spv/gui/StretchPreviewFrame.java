package spv.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;
import javax.swing.JSplitPane;

public class StretchPreviewFrame extends JFrame {

	private JPanel contentPane;

	private JPanel originalImagePanel;
	
	private JPanel strethcedImagePanel;
	
	private JLabel originalImageLabel;
	
	private JLabel stretchedImageLabel;

	/**
	 * Create the frame.
	 */
	public StretchPreviewFrame() {
		setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		setBounds(100, 100, 800, 400);
		contentPane = new JPanel();
		contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		setContentPane(contentPane);
		contentPane.setLayout(null);
		
		originalImagePanel = new JPanel();
		originalImagePanel.setBounds(0, 0, 350, 350);
		contentPane.add(originalImagePanel);
		
		strethcedImagePanel = new JPanel();
		strethcedImagePanel.setBounds(400, 0, 350, 350);
		contentPane.add(strethcedImagePanel);
		
        BufferedImage originalBufferedImage = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
        ImageIcon originalImageIcon = new ImageIcon( originalBufferedImage );
        originalImageLabel = new JLabel(originalImageIcon);
        
        originalImagePanel.add(originalImageLabel);
        
        BufferedImage stretchedBufferedImage = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
        ImageIcon stretchedImageIcon = new ImageIcon( stretchedBufferedImage );
        stretchedImageLabel= new JLabel(stretchedImageIcon);
        
        strethcedImagePanel.add(stretchedImageLabel);
        
	}
	
	public void setOriginalImage(BufferedImage image) {
        ImageIcon newImageIcon = new ImageIcon( image );
        originalImageLabel.setIcon(newImageIcon);		
	}
	
	public void setStretchedImage(BufferedImage image) {
        ImageIcon newImageIcon = new ImageIcon( image );
        stretchedImageLabel.setIcon(newImageIcon);
	}	
}
