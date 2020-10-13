package spv.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;

import org.apache.commons.configuration2.ex.ConfigurationException;

import io.github.ppissias.astrolib.PlateSolveResult;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;
import spv.util.FitsFileInformation;
import spv.util.ImagePreprocessing;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.JProgressBar;
import java.awt.Rectangle;

public class ApplicationWindow {

	private JFrame frame;

	private ImagePreprocessing imagePreProcessing;
	
	private File astapExecutableFilePath = null;

	
	//logger
	public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());
	private JTable table;
	
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
		frame = new JFrame();
		frame.setBounds(new Rectangle(100, 100, 800, 600));
		//frame.setBounds(100, 100, 800, 550);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.getContentPane().setLayout(new BorderLayout(0, 0));
		
		JPanel panel = new JPanel();
		frame.getContentPane().add(panel, BorderLayout.NORTH);
		panel.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));
		
		JButton solveButton = new JButton("Solve");

		panel.add(solveButton);
		
		JCheckBox astapSolveCheckbox = new JCheckBox("ASTAP");

		astapSolveCheckbox.setSelected(true);
		panel.add(astapSolveCheckbox);
		
		JCheckBox astrometrynetSolveCheckbox = new JCheckBox("Astrometry.net (online)");
		panel.add(astrometrynetSolveCheckbox);
	 
		JButton applySolutionButton = new JButton("apply solution");
		applySolutionButton.setEnabled(false);
		applySolutionButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				if (table.getValueAt(table.getSelectedRow(), 0) != null) {
					//get header information and apply to all fits
					
				}				
			}
		});
		panel.add(applySolutionButton);
		
		
		JButton showAnnotatedImageButton = new JButton("show annotated image");
		showAnnotatedImageButton.setEnabled(false);
		showAnnotatedImageButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				if (table.getValueAt(table.getSelectedRow(), 6) != null) {
					//check how the image was solved
					FitsFileInformation imageInfo = (FitsFileInformation)table.getValueAt(table.getSelectedRow(), 6); 
					PlateSolveResult result = imageInfo.getSolveResult();
					if (result.isSuccess()) {
						//sanity check
						String annotatedImageLink = result.getSolveInformation().get("annotated_image_link");
						URL annotatedImageURL = null;
						
						switch (result.getSolveInformation().get("source")) {
						case "astrometry.net" : {
							try {
								annotatedImageURL = new URL(annotatedImageLink);
							} catch (MalformedURLException e) {
								JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Cannot understand URL :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
								return;
							}
							break;
						}
						case "astap" : {
							try {
								annotatedImageURL = new File(annotatedImageLink).toURI().toURL();
							} catch (MalformedURLException e) {
								JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Cannot understand URL :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
								return;
							}
							
							break;
						}
						default : {
							JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Cannot understand solve source :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
							return;
						}
						}
						
						//load image
		        		ApplicationWindow.logger.info("loading image: "+annotatedImageURL.toString());
						try {
							BufferedImage image = ImageIO.read(annotatedImageURL);
		                    JLabel label = new JLabel(new ImageIcon(image));
		                    JFrame f = new JFrame();
		                    //f.setDefaultCloseOperation(JFrame.);
		                    f.getContentPane().add(label);
		                    f.pack();
		                    f.setLocation(200, 200);
		                    f.setVisible(true);							
						} catch (IOException e) {
							JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Cannot show image :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);

						}
						
					} else {
						JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Image not solved :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
						return;
					}
				}
			}
		});
		panel.add(showAnnotatedImageButton);
		
		JProgressBar progressBar = new JProgressBar();
		panel.add(progressBar);
		progressBar.setEnabled(false);
		
		astapSolveCheckbox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				if (astapSolveCheckbox.isSelected()) {
					astrometrynetSolveCheckbox.setSelected(false);
				}
			}
		});
		
		astrometrynetSolveCheckbox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				if (astrometrynetSolveCheckbox.isSelected()) {
					astapSolveCheckbox.setSelected(false);
				}
			}
		});
		
		JScrollPane scrollPane = new JScrollPane();
		frame.getContentPane().add(scrollPane);
		
		table = new JTable();
		scrollPane.setViewportView(table);
		
		table.getSelectionModel().addListSelectionListener(new ListSelectionListener(){
	        public void valueChanged(ListSelectionEvent event) {
	            // do some actions here, for example
	            // print first column value from selected row
	        	if (table.getValueAt(table.getSelectedRow(), 0) != null) {
	        		ApplicationWindow.logger.info(table.getValueAt(table.getSelectedRow(), 0).toString());
	        		
		        	 
		        	//make buttons available if the selected row has a solution 
	        		if (table.getValueAt(table.getSelectedRow(), 5).equals("yes")) {
	        			applySolutionButton.setEnabled(true);
	        			showAnnotatedImageButton.setEnabled(true);
	        			solveButton.setEnabled(false);
	        		} else {
	        			applySolutionButton.setEnabled(false);
	        			showAnnotatedImageButton.setEnabled(false);
	        			solveButton.setEnabled(true);
	        		}
	
	        		
	        		//TODO 2 
	        		//implement function on buttons 
	        		//1 show local image 
	        		
	        		//2 open browser to show 
	        		//load image from URL
	        		
	        		//https://docs.oracle.com/javase/tutorial/2d/images/loadimage.html
	        		
	        	}
	        	
	        }
	    });
		
		JMenuBar menuBar = new JMenuBar();
		frame.setJMenuBar(menuBar);
		
		JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);

		solveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				ApplicationWindow.logger.info("Will try to solve image");
				
				//get file
				int row = table.getSelectedRow();
				
				FitsFileInformation seletedFile = (FitsFileInformation)table.getValueAt(row, 6);
				//update progress bar
				progressBar.setEnabled(true);
				progressBar.setIndeterminate(true);
				try {
					Future<PlateSolveResult> solveResult = imagePreProcessing.solve(seletedFile.getFilePath(), astapSolveCheckbox.isSelected(), astrometrynetSolveCheckbox.isSelected());
					if (solveResult != null) {
						PlateSolveResult result = solveResult.get();
						
						if (result.isSuccess()) {
							JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Image was succesfully plate-solved");
						} else {
							JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Image was not plate-solved sccesfully:"+result.getFailureReason()+" "+result.getWarning());
						}
						ApplicationWindow.logger.info(result.toString());
						
						//associate the solve result with the table object
						table.setValueAt(result, row, 5);	
						((FitsFileTableModel)table.getModel()).fireTableDataChanged();
						
						progressBar.setVisible(false);
						progressBar.setIndeterminate(false);
					}					
				} catch (InterruptedException | ExecutionException | FitsException e) {
					JOptionPane.showMessageDialog(ApplicationWindow.this.frame, "Cannot solve image:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
					progressBar.setEnabled(false);
					progressBar.setIndeterminate(false);
				}
								
			}
		});

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
		            //l1.addElement(file.getAbsolutePath());
		            
		            try {
						imagePreProcessing = ImagePreprocessing.getInstance(astapExecutableFilePath, file);
						FitsFileInformation[] filesInformation = imagePreProcessing.getFitsfileInformation();
						
						AbstractTableModel tableModel = new FitsFileTableModel(filesInformation);
						table.setModel(tableModel);
						
						//hide object column
						table.getColumnModel().getColumn(6).setMinWidth(0);
						table.getColumnModel().getColumn(6).setMaxWidth(0);
						table.getColumnModel().getColumn(6).setWidth(0);
						//remove last column (which contains the a link to the object
						//TableColumnModel tcm = table.getColumnModel();
						//tcm.removeColumn( tcm.getColumn(6) );
						
					} catch (IOException | FitsException | ConfigurationException e) {
						e.printStackTrace();
					}
		        }
				
			}
		});
		
		fileMenu.add(importMenuItem);
		
		JMenu settingsMenu = new JMenu("Settings");
		menuBar.add(settingsMenu);
		
		JMenuItem astapSettingsMenuItem = new JMenuItem("ASTAP");
		astapSettingsMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				ApplicationWindow.logger.info("ASTAP settings");
				
				//Create a file chooser
				final JFileChooser fc = new JFileChooser();
				fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
				
				fc.setDialogTitle("ASTAP executable");
				int returnVal = fc.showOpenDialog(frame);
								
				if (returnVal == JFileChooser.APPROVE_OPTION) {
					astapExecutableFilePath = fc.getSelectedFile();
										
					//do a simple test run of astap
					String[] cmdArray = new String[2];
					cmdArray[0] = astapExecutableFilePath.getAbsolutePath();
					cmdArray[1] = "-h";
					
					try {
						Runtime.getRuntime().exec(cmdArray, null, astapExecutableFilePath.getParentFile());
					} catch (IOException e) {
						JOptionPane.showMessageDialog(new JFrame(), "Cannot execute ASTAP:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
						astapExecutableFilePath = null;
					}

				}
			}
		});
		settingsMenu.add(astapSettingsMenuItem);
		
		//initialize properties
	}

}
