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
import java.awt.EventQueue;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.table.AbstractTableModel;

import org.apache.commons.configuration2.ex.ConfigurationException;

import nom.tam.fits.FitsException;
import spv.util.FitsFileInformation;
import spv.util.ImagePreprocessing;

public class ApplicationWindow {

	private JFrame frmIpodImage;

	private volatile ImagePreprocessing imagePreProcessing;	
	
	private SPMainApplicationPanel mainApplicationPanel; 
	
	private SPConfigurationApplicationPanel configurationApplicationPanel;
	
	//logger
	public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());
	
	private StretchPreviewFrame stretchPreviewFrame;
	
	private FullImageStretchPreviewFrame fullImagePreviewFrame = new FullImageStretchPreviewFrame();
	
	private JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
	
	private JMenu fileMenu = new JMenu("File");
	private JMenuItem importMenuItem = new JMenuItem("Import aligned fits files");

	
	public void setMenuState(boolean state) {
		importMenuItem.setEnabled(state);
	}

	public FullImageStretchPreviewFrame getFullImagePreviewFrame() {
		return fullImagePreviewFrame;
	}

	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					ApplicationWindow window = new ApplicationWindow();
					window.frmIpodImage.setVisible(true);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});
	}

	/**
	 * Create the application.
	 */
	public ApplicationWindow() {
		initialize();
	}

	/**
	 * Initialize the contents of the frame.
	 */
	private void initialize() {
		stretchPreviewFrame  = new StretchPreviewFrame(this);
		stretchPreviewFrame.setVisible(false);
		fullImagePreviewFrame.setVisible(false);
		frmIpodImage = new JFrame();
		frmIpodImage.setTitle("SpacePixels");
		frmIpodImage.setBounds(new Rectangle(100, 100, 1000, 650));
		frmIpodImage.setResizable(false);
		frmIpodImage.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frmIpodImage.getContentPane().setLayout(new BorderLayout(0, 0));
		
		//the main tabbed pane
		
		
		//tabbedPane.addT
		frmIpodImage.getContentPane().add(tabbedPane, BorderLayout.CENTER);
		
		mainApplicationPanel = new SPMainApplicationPanel(this);
		
		configurationApplicationPanel = new SPConfigurationApplicationPanel(this);
		
		tabbedPane.addTab("Main", mainApplicationPanel);
		tabbedPane.addTab("Configuration", configurationApplicationPanel);
		tabbedPane.setEnabledAt(1, false);

		JMenuBar menuBar = new JMenuBar();
		frmIpodImage.setJMenuBar(menuBar);
		
		JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);


		importMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {							
				ApplicationWindow.logger.info("Will try to import fits files!");
								
				mainApplicationPanel.setProgressBarWorking();
				//Create a file chooser
				final JFileChooser fc = new JFileChooser();
				fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				
				fc.setDialogTitle("Directory containing aligned fits images");
				int returnVal = fc.showOpenDialog(frmIpodImage);
				
				if (returnVal == JFileChooser.APPROVE_OPTION) {
		            File file = fc.getSelectedFile();
		            

		            new Thread() {
		            	public void run () {
				            try {
								imagePreProcessing = ImagePreprocessing.getInstance(file);
								final FitsFileInformation[] filesInformation = imagePreProcessing.getFitsfileInformation();
								
								//update table
								final AbstractTableModel tableModel = new FitsFileTableModel(filesInformation);
								
								EventQueue.invokeLater(new Runnable() {

									@Override
									public void run() {
										mainApplicationPanel.setTableModel(tableModel);
										tabbedPane.setEnabledAt(1, true);
										configurationApplicationPanel.refreshComponents();	
									}}
								);

								
							} catch (IOException | FitsException | ConfigurationException e) {
								e.printStackTrace();
							}
		            	}
		            }.start();
		        }
				EventQueue.invokeLater(new Runnable() {
					@Override
					public void run() {
						mainApplicationPanel.setProgressBarIdle();	
					}}
				);

				
			}
		});
		
		fileMenu.add(importMenuItem);
		
		//initialize properties
	}

	public ImagePreprocessing getImagePreProcessing() {
		return imagePreProcessing;
	}

	public void setImagePreProcessing(ImagePreprocessing imagePreProcessing) {
		this.imagePreProcessing = imagePreProcessing;
	}
	
	public FitsFileInformation getSelectedFile() {
		return mainApplicationPanel.getSelectedFileInformation();
	}
	public FitsFileInformation[] getSelectedFiles() {
		return mainApplicationPanel.getSelectedFilesInformation();
	}
	public SPMainApplicationPanel getMainApplicationPanel() {
		return mainApplicationPanel;
	}

	public SPConfigurationApplicationPanel getConfigurationApplicationPanel() {
		return configurationApplicationPanel;
	}

	public void setStretchFrameVisible(boolean visibility) {
		stretchPreviewFrame.setVisible(visibility);
	}
	
	public void setOriginalImage(BufferedImage image) {
		stretchPreviewFrame.setOriginalImage(image);		
	}
	
	public void setStretchedImage(BufferedImage image) {
		stretchPreviewFrame.setStretchedImage(image);	
	}
	
	public void setMainViewEnabled(boolean state) {
		tabbedPane.setEnabledAt(0, state);
	}
	
	public JTabbedPane getTabbedPane() {
		return tabbedPane;
	}
}
