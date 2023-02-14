/*
 * SpacePixels
 * 
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package spv.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.image.BufferedImage;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.border.EmptyBorder;

public class FullImageStretchPreviewFrame extends JFrame {

	private JPanel contentPane;

	private JPanel imagePreviewPanel = new JPanel();
	private JLabel imageLabel;
	
	private ImageVisualizerComponent imageComponent = new ImageVisualizerComponent();


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
		
		imageComponent.setPreferredSize(new Dimension(image.getWidth(), image.getHeight()));
		
		//TODO when blinkin for the first time if the window is not openeed before, then its size does not update accordingly...
		this.setSize(image.getWidth(), image.getHeight());
	}
	
	

}
