package spv.gui;

import java.awt.BorderLayout;
import java.awt.Color;
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

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;

import org.apache.commons.configuration2.ex.ConfigurationException;

import io.github.ppissias.astrolib.PlateSolveResult;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import spv.util.FitsFileInformation;
import spv.util.ImagePreprocessing;

public class SPMainApplicationPanel extends JPanel {

	//link to main window
	private ApplicationWindow mainAppWindow;	

	//the table
	private JTable table;
	
	/**
	 * Create the panel.
	 */
	public SPMainApplicationPanel(ApplicationWindow mainAppWindow) {
		this.mainAppWindow = mainAppWindow;
		
		setLayout(new BorderLayout(0, 0));

		JPanel panel = new JPanel();
		//frame.getContentPane().add(panel, BorderLayout.NORTH);
		
		add(panel, BorderLayout.NORTH);
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
				if (table.getValueAt(table.getSelectedRow(), 6) != null) {
					//check how the image was solved
					FitsFileInformation imageInfo = (FitsFileInformation)table.getValueAt(table.getSelectedRow(), 6); 
					PlateSolveResult result = imageInfo.getSolveResult();
					if (result.isSuccess()) {
						//sanity check
						String wcsLink = result.getSolveInformation().get("wcs_link");
						String wcsFile;
						
						switch (result.getSolveInformation().get("source")) {
						case "astrometry.net" : {
							try {
								URL wcsURL = new URL(wcsLink);
								int lastSepPosition = imageInfo.getFilePath().lastIndexOf(".");	
								wcsFile = imageInfo.getFilePath().substring(0, lastSepPosition)+".wcs";
								//download file
								ImagePreprocessing.downloadFile(wcsURL, wcsFile);
								
							} catch (IOException e) {
								JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand URL :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
								return;
							}
							break;
						}
						case "astap" : {
							wcsFile = result.getSolveInformation().get("wcs_link");
							
							break;
						}
						default : {
							JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand solve source :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
							return;
						}
						}
						
						//apply the WCS file
		        		ApplicationWindow.logger.info("applying WCS header "+wcsFile+" to all images");
						try {
							mainAppWindow.getImagePreProcessing().applyWCSHeader(wcsFile, mainAppWindow.getConfigurationApplicationPanel().getStretchSlider().getValue(), mainAppWindow.getConfigurationApplicationPanel().getStretchIterationsSlider().getValue() );
						} catch (IOException | FitsException e) {
							e.printStackTrace();
							JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot apply WCS header :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);

						}
						
					} else {
						JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image not solved :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
						return;
					}
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
								JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand URL :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
								return;
							}
							break;
						}
						case "astap" : {
							try {
								annotatedImageURL = new File(annotatedImageLink).toURI().toURL();
							} catch (MalformedURLException e) {
								JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand URL :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
								return;
							}
							
							break;
						}
						default : {
							JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand solve source :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
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
							JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot show image :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);

						}
						
					} else {
						JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image not solved :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
						return;
					}
				}
			}
		});
		panel.add(showAnnotatedImageButton);
		
		JProgressBar progressBar = new JProgressBar();
		panel.add(progressBar);
		progressBar.setEnabled(true);
		
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
		//frame.getContentPane().add(scrollPane);		
		add(scrollPane, BorderLayout.CENTER);
				
		table = new JTable();
		scrollPane.setViewportView(table);
		
		table.getSelectionModel().addListSelectionListener(new ListSelectionListener(){
	        public void valueChanged(ListSelectionEvent event) {
	        	
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
	        		
	        		mainAppWindow.setStretchFrameVisible(true);
	        		try {
						updateImageStretchWindow();
					} catch (FitsException | IOException e) {
						e.printStackTrace();
					}
	        	} else {
	        		mainAppWindow.setStretchFrameVisible(false);
	        		
	        	}
	        	
	        	
	        	
	        }
	    });
		

		solveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				ApplicationWindow.logger.info("Will try to solve image");
				
				//get file
				int row = table.getSelectedRow();
				if (row < 0) {
					return;
				}
				
				FitsFileInformation seletedFile = (FitsFileInformation)table.getValueAt(row, 6);
				//update progress bar
				progressBar.setIndeterminate(true);
				try {
					Thread.sleep(500);
					Future<PlateSolveResult> solveResult = mainAppWindow.getImagePreProcessing().solve(seletedFile.getFilePath(), astapSolveCheckbox.isSelected(), astrometrynetSolveCheckbox.isSelected());
					if (solveResult != null) {
						PlateSolveResult result = solveResult.get();
						
						if (result.isSuccess()) {
							JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image was succesfully plate-solved");
							//write results file
							mainAppWindow.getImagePreProcessing().writeSolveResults(seletedFile.getFilePath(), result);
						} else {
							JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image was not plate-solved sccesfully:"+result.getFailureReason()+" "+result.getWarning());
						}
						ApplicationWindow.logger.info(result.toString());
						
						//associate the solve result with the table object
						table.setValueAt(result, row, 5);	
						((FitsFileTableModel)table.getModel()).fireTableDataChanged();
						
						progressBar.setIndeterminate(false);

					}					
				} catch (InterruptedException | ExecutionException | FitsException | IOException | ConfigurationException e) {
					JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot solve image:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
					progressBar.setIndeterminate(false);
				} 
								
			}
		});		
	}
	
	/**
	 * Sets the table model
	 * @param tableModel
	 */
	public void setTableModel(AbstractTableModel tableModel) {
		table.setModel(tableModel);
		
		//hide object column
		table.getColumnModel().getColumn(6).setMinWidth(0);
		table.getColumnModel().getColumn(6).setMaxWidth(0);
		table.getColumnModel().getColumn(6).setWidth(0);		
	}
	
	/**
	 * Returns the file information
	 * @return
	 */
	public FitsFileInformation getSelectedFileInformation() {
		//get file
		int row = table.getSelectedRow();
		if (row < 0) {
			return null;
		}
		
		FitsFileInformation selectedFile = (FitsFileInformation)table.getValueAt(row, 6);
		return selectedFile;		
	}

	/**
	 * Updates the image preview stretch window
	 * @throws FitsException
	 * @throws IOException 
	 */
	public void updateImageStretchWindow() throws FitsException, IOException {
		int stretchFactor = mainAppWindow.getConfigurationApplicationPanel().getStretchSlider().getValue();
		int iterations = mainAppWindow.getConfigurationApplicationPanel().getStretchIterationsSlider().getValue();
		
		FitsFileInformation selectedFitsFileInfo = getSelectedFileInformation();
		if (selectedFitsFileInfo != null) {
			Fits selectedFitsImage = new Fits(selectedFitsFileInfo.getFilePath());
			if (selectedFitsFileInfo.getSizeHeight() > 350 && selectedFitsFileInfo.getSizeWidth()>350) {
				//get image data
				Object kernelData = selectedFitsImage.getHDU(0).getKernel();
				
				BufferedImage fitsImagePreview = mainAppWindow.getImagePreProcessing().getImagePreview(kernelData);
				BufferedImage fitsImagePreviewStretch = mainAppWindow.getImagePreProcessing().getStretchedImagePreview(kernelData, stretchFactor, iterations);
						        
				mainAppWindow.setOriginalImage(fitsImagePreview);
				mainAppWindow.setStretchedImage(fitsImagePreviewStretch);
				
			} else {
			}
		} else {
		}
		
	}

}
