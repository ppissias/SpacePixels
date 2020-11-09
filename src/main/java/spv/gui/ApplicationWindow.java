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

import javax.swing.ImageIcon;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

import org.apache.commons.configuration2.ex.ConfigurationException;

import nom.tam.fits.FitsException;
import spv.util.FitsFileInformation;
import spv.util.ImagePreprocessing;

public class ApplicationWindow {

	private JFrame frame;

	private ImagePreprocessing imagePreProcessing;	
	
	private SPMainApplicationPanel mainApplicationPanel; 
	
	private SPConfigurationApplicationPanel configurationApplicationPanel;
	
	//logger
	public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());
	private JTable table;
	
	private StretchPreviewFrame stretchPreviewFrame = new StretchPreviewFrame();
	/**
	 * Launch the application.
	 */
	public static void main(String[] args) {
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					ApplicationWindow window = new ApplicationWindow();
					window.frame.setVisible(true);
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
		stretchPreviewFrame.setVisible(false);
		frame = new JFrame();
		frame.setBounds(new Rectangle(100, 100, 800, 600));
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.getContentPane().setLayout(new BorderLayout(0, 0));
		
		//the main tabbed pane
		JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
		
		//tabbedPane.addT
		frame.getContentPane().add(tabbedPane, BorderLayout.CENTER);
		
		mainApplicationPanel = new SPMainApplicationPanel(this);
		
		configurationApplicationPanel = new SPConfigurationApplicationPanel(this);
		
		tabbedPane.addTab("Main", mainApplicationPanel);
		tabbedPane.addTab("Configuration", configurationApplicationPanel);
		

		JMenuBar menuBar = new JMenuBar();
		frame.setJMenuBar(menuBar);
		
		JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);

		JMenuItem importMenuItem = new JMenuItem("Import aligned fits files");
		importMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {							
				ApplicationWindow.logger.info("Will try to import fits files!");
								
				//Create a file chooser
				final JFileChooser fc = new JFileChooser();
				fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				
				fc.setDialogTitle("Directory containing aligned fits images");
				int returnVal = fc.showOpenDialog(frame);
				
				if (returnVal == JFileChooser.APPROVE_OPTION) {
		            File file = fc.getSelectedFile();
		            
		            try {
						imagePreProcessing = ImagePreprocessing.getInstance(file);
						FitsFileInformation[] filesInformation = imagePreProcessing.getFitsfileInformation();
						
						//update table
						AbstractTableModel tableModel = new FitsFileTableModel(filesInformation);
						mainApplicationPanel.setTableModel(tableModel);

						configurationApplicationPanel.refreshComponents();
						
					} catch (IOException | FitsException | ConfigurationException e) {
						e.printStackTrace();
					}
		        }
				
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
}
