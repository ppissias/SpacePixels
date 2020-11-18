package spv.gui;

import java.awt.BorderLayout;
import java.awt.Color;
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
	private volatile JTable table;
	
	private final JProgressBar progressBar = new JProgressBar();
	
	private final JButton stretchButton = new JButton("batch stretch");
	
	private final JButton blinkButton = new JButton("blink");
	
	private final JButton applySolutionButton = new JButton("apply solution");
	
	private final JButton showAnnotatedImageButton = new JButton("show annotated image");
	
	private final JButton solveButton = new JButton("Solve");
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
		
		
		solveButton.setToolTipText("Solve current image");

		panel.add(solveButton);
		
		JCheckBox astapSolveCheckbox = new JCheckBox("ASTAP");
		astapSolveCheckbox.setToolTipText("Solve the image using ASTAP");

		astapSolveCheckbox.setSelected(true);
		panel.add(astapSolveCheckbox);
		
		JCheckBox astrometrynetSolveCheckbox = new JCheckBox("Astrometry.net (online)");
		astrometrynetSolveCheckbox.setToolTipText("solve the image using the online nova.astrometry.net web services");
		panel.add(astrometrynetSolveCheckbox);
	 
		
		applySolutionButton.setToolTipText("Apply solution to all images, convert to monochrome (if color) and stretch (if checked). It will create separate folders for each file category");
		applySolutionButton.setEnabled(false);
		applySolutionButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent event) {
				//TODO disable / enable controls
				if (table.getValueAt(table.getSelectedRow(), 6) != null) {
					setProgressBarWorking();
					//check how the image was solved
					FitsFileInformation imageInfo = (FitsFileInformation)table.getValueAt(table.getSelectedRow(), 6); 
					final PlateSolveResult result = imageInfo.getSolveResult();
					if (result.isSuccess()) {
						//sanity check
						final String wcsLink = result.getSolveInformation().get("wcs_link");
						
						new Thread() {
							public void run() {
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
										EventQueue.invokeLater(new Runnable() {

											@Override
											public void run() {
												JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand URL :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
												
											}
											
										});
										return;
									}
									break;
								}
								case "astap" : {
									wcsFile = result.getSolveInformation().get("wcs_link");
									
									break;
								}
								default : {
									EventQueue.invokeLater(new Runnable() {

										@Override
										public void run() {
											JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot understand solve source :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
											
										}
										
									});									
									return;
								}
								}
								
								//apply the WCS file
				        		ApplicationWindow.logger.info("applying WCS header "+wcsFile+" to all images");
								try {
									mainAppWindow.getImagePreProcessing().applyWCSHeader(wcsFile, mainAppWindow.getConfigurationApplicationPanel().getStretchSlider().getValue(),
											mainAppWindow.getConfigurationApplicationPanel().getStretchIterationsSlider().getValue(), mainAppWindow.getConfigurationApplicationPanel().isStretchEnabled() );
								} catch (IOException | FitsException e) {
									e.printStackTrace();
									EventQueue.invokeLater(new Runnable() {

										@Override
										public void run() {
											JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot apply WCS header :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);											
										}
										
									});										
		
								}
								EventQueue.invokeLater(new Runnable() {

									@Override
									public void run() {
										setProgressBarIdle();
									}
									
								});									
							}
						}.start();
					} else {
						JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image not solved :"+imageInfo, "Error",JOptionPane.ERROR_MESSAGE);
						return;
					}
				}
							
			}
		});
		panel.add(applySolutionButton);
		
		
		
		showAnnotatedImageButton.setToolTipText("show annotated image");
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
		stretchButton.setToolTipText("Stretch all images as specified (only stretch) and convert to mono (if color)");
		stretchButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				//only stretch images
				//TODO enable disable controls
				setProgressBarWorking();
				
				new Thread() {
					public void run() {

						
						//apply the WCS file
		        		ApplicationWindow.logger.info("Will stretch all images");
						try {
							mainAppWindow.getImagePreProcessing().onlyStretch( mainAppWindow.getConfigurationApplicationPanel().getStretchSlider().getValue(),
									mainAppWindow.getConfigurationApplicationPanel().getStretchIterationsSlider().getValue() );
						} catch (IOException | FitsException e) {
							e.printStackTrace();
							EventQueue.invokeLater(new Runnable() {

								@Override
								public void run() {
									JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot apply WCS header :"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);											
								}
								
							});										

						}
						EventQueue.invokeLater(new Runnable() {

							@Override
							public void run() {
								setProgressBarIdle();
							}
							
						});									
					}
				}.start();
				
			}
		});
		
		panel.add(stretchButton);
		stretchButton.setEnabled(false);
		blinkButton.setToolTipText("Blink 3 or more images");
		
		
		blinkButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				//new thread 
				//TODO enable disable controls
				//TODO disable closing of the blink window
				new Thread() { 
					public void run() {
						if (blinkButton.getText().equals("blink")) {
							//rename button to stop blink 
							EventQueue.invokeLater(new Runnable() {

								@Override
								public void run() {
									mainAppWindow.getFullImagePreviewFrame().setVisible(true);

									blinkButton.setText("stop blinking");
									
								}});
			        		FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
			        		BufferedImage[] images = new BufferedImage[selectedFitsFilesInfo.length];
			
			        		if (selectedFitsFilesInfo != null) {
			        			//read images
			        			int i=0;
			        			for (FitsFileInformation selectedFitsFileInfo : selectedFitsFilesInfo) {
				        			Fits selectedFitsImage;
									try {
										selectedFitsImage = new Fits(selectedFitsFileInfo.getFilePath());
				
				        				//get image data
				        				Object kernelData = selectedFitsImage.getHDU(0).getKernel();
				        				
				        				images[i] = mainAppWindow.getImagePreProcessing().getStretchedImageFullSize(kernelData, mainAppWindow.getConfigurationApplicationPanel().getStretchSlider().getValue(), 
				        						mainAppWindow.getConfigurationApplicationPanel().getStretchIterationsSlider().getValue());
				        						        
				        				
									} catch (FitsException | IOException e) {
										e.printStackTrace();
										EventQueue.invokeLater(new Runnable() {

											@Override
											public void run() {
												JOptionPane.showMessageDialog(mainAppWindow.getFullImagePreviewFrame(),
														"Cannot show full image:" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
												
											}});										
										return;
									}
									i++;
			        			}
			        			
			        			//now we have the images
			        			int j = 0;
			        			while (true) {
			        				BufferedImage image = images[j];
			        				
			        				try {
										Thread.sleep(500);
									} catch (InterruptedException e) {
										e.printStackTrace();
									} //TODO make configurable
			        				
									EventQueue.invokeLater(new Runnable() {

										@Override
										public void run() {
					        				mainAppWindow.getFullImagePreviewFrame().setImage(image);
										}});								

									j++; //increase
									if (j==images.length) {
										j=0;
									}
			        				if (blinkButton.getText().equals("blink")) {
			        					//it was stopped
			        					break;
			        				}
			        			}
			        		} 	
						} else {
							EventQueue.invokeLater(new Runnable() {

								@Override
								public void run() {
									//test = stop blinking
									blinkButton.setText("blink");
									mainAppWindow.getFullImagePreviewFrame().setVisible(false);

								}});								

						}
					}
				}.start();
			}
		});
		blinkButton.setEnabled(false);
		panel.add(blinkButton);
		
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
	        		//ApplicationWindow.logger.info(table.getValueAt(table.getSelectedRow(), 0).toString());

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
	        		
	        		//show stretch window
	        		if (mainAppWindow.getConfigurationApplicationPanel().isStretchEnabled()) {
		        		mainAppWindow.setStretchFrameVisible(true);
		        		try {
							updateImageStretchWindow();
						} catch (FitsException | IOException e) {
							e.printStackTrace();
						}
	        		}
	        	} else {
	        		mainAppWindow.setStretchFrameVisible(false);
	        	}
	        	
	        	//if more than 3 files are selected, set blink button (or 3 exactly) 
	        	FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
	        	if (selectedFitsFilesInfo != null) {
	        		if (selectedFitsFilesInfo.length >= 3) {
	        			blinkButton.setEnabled(true);
	        		} else {
	        			blinkButton.setEnabled(false);

	        		}
	        	} else {
        			blinkButton.setEnabled(false);
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
				setProgressBarWorking();
				//disable controls
				disableControlsSolving();
				//mainAppWindow.setMainViewEnabled(false);
				new Thread() {
					public void run() {

						try {
							Thread.sleep(500);
							Future<PlateSolveResult> solveResult = mainAppWindow.getImagePreProcessing().solve(seletedFile.getFilePath(), astapSolveCheckbox.isSelected(), astrometrynetSolveCheckbox.isSelected());
							if (solveResult != null) {
								final PlateSolveResult result = solveResult.get();
								
								if (result.isSuccess()) {
									EventQueue.invokeLater(new Runnable() {

										@Override
										public void run() {
											JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image was succesfully plate-solved");

										}
									});
									//write results file
									mainAppWindow.getImagePreProcessing().writeSolveResults(seletedFile.getFilePath(), result);
								} else {
									EventQueue.invokeLater(new Runnable() {

										@Override
										public void run() {
											JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Image was not plate-solved sccesfully:"+result.getFailureReason()+" "+result.getWarning());

										}
									});									
								}
								ApplicationWindow.logger.info(result.toString());
								EventQueue.invokeLater(new Runnable() {

									@Override
									public void run() {
										//associate the solve result with the table object
										table.setValueAt(result, row, 5);	
										((FitsFileTableModel)table.getModel()).fireTableDataChanged();
										
										setProgressBarIdle();	
										//mainAppWindow.setMainViewEnabled(true);
										enableControlsSolvingFinished();

}
								});								

		
							}					
						} catch (InterruptedException | ExecutionException | FitsException | IOException | ConfigurationException e) {
							EventQueue.invokeLater(new Runnable() {

								@Override
								public void run() {
									JOptionPane.showMessageDialog(SPMainApplicationPanel.this, "Cannot solve image:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
									setProgressBarIdle();		
									//mainAppWindow.setMainViewEnabled(true);
									enableControlsSolvingFinished();

}
							});									

						} 
					}
				}.start();
								
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
	 * Returns the file information
	 * @return
	 */
	public FitsFileInformation[] getSelectedFilesInformation() {
		//get files
	    int[] selected_rows= table.getSelectedRows();		
	    
	    if (selected_rows != null) {
	    	if (selected_rows.length > 0) {
	    		
	    		FitsFileInformation[] ret = new FitsFileInformation[selected_rows.length];
	    		for (int i=0;i<selected_rows.length;i++) {
	    			ret[i] =  (FitsFileInformation)table.getValueAt(selected_rows[i], 6);
	    		}
	    		return ret;
	    	} else {
	    		return null;
	    	}
	    }else {
	    	return null;
	    }
		
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
				if (mainAppWindow.getFullImagePreviewFrame().isVisible()) {
					//update full size as well
					
					BufferedImage fitsImagePreviewFS = mainAppWindow.getImagePreProcessing().getStretchedImageFullSize(kernelData, stretchFactor, iterations);				
					mainAppWindow.getFullImagePreviewFrame().setImage(fitsImagePreviewFS);
					
				}				
			} else {
			}
			

			selectedFitsImage.close();
		} else {
		}
		
	}
	
	public void setProgressBarWorking() {
		progressBar.setIndeterminate(true);
	}
	
	public void setProgressBarIdle() {
		progressBar.setIndeterminate(false);
	}
	
	public void setBatchStretchButtonEnabled(boolean state) {
		stretchButton.setEnabled(state);
	}
	
	private void disableControlsSolving() {
		this.applySolutionButton.setEnabled(false);
		this.blinkButton.setEnabled(false);
		this.showAnnotatedImageButton.setEnabled(false);
		this.stretchButton.setEnabled(false);
		this.table.setEnabled(false);
		this.solveButton.setEnabled(false);
		mainAppWindow.setMenuState(false);
		mainAppWindow.getTabbedPane().setEnabledAt(1, false);
	}

	private void enableControlsSolvingFinished() {
		this.applySolutionButton.setEnabled(false);
		this.blinkButton.setEnabled(false);
		this.showAnnotatedImageButton.setEnabled(false);
		this.stretchButton.setEnabled(true);
		this.table.setEnabled(true);
		this.solveButton.setEnabled(false);
		mainAppWindow.setMenuState(true);

		mainAppWindow.getTabbedPane().setEnabledAt(1, true);
	}	
	
	private void disableControlsProcessing() {
		this.applySolutionButton.setEnabled(false);
		this.blinkButton.setEnabled(false);
		this.showAnnotatedImageButton.setEnabled(false);
		this.stretchButton.setEnabled(false);
		this.table.setEnabled(false);
		this.solveButton.setEnabled(false);
		mainAppWindow.setMenuState(false);

		mainAppWindow.getTabbedPane().setEnabledAt(1, false);
	}
	
	private void enableControlsProcessingFinished() {
		this.applySolutionButton.setEnabled(false);
		this.blinkButton.setEnabled(false);
		this.showAnnotatedImageButton.setEnabled(false);
		this.stretchButton.setEnabled(true);
		this.table.setEnabled(true);
		this.solveButton.setEnabled(false);
		mainAppWindow.setMenuState(true);

		mainAppWindow.getTabbedPane().setEnabledAt(1, true);
	}
	private void disableControlsBlinking() {
		this.applySolutionButton.setEnabled(false);
		this.blinkButton.setEnabled(false);
		this.showAnnotatedImageButton.setEnabled(false);
		this.stretchButton.setEnabled(false);
		this.table.setEnabled(false);
		this.solveButton.setEnabled(false);
		mainAppWindow.setMenuState(false);

	}
	
	private void enableControlsProcessingBlinkingFinished() {
		this.applySolutionButton.setEnabled(false);
		this.blinkButton.setEnabled(false);
		this.showAnnotatedImageButton.setEnabled(false);
		this.stretchButton.setEnabled(true);
		this.table.setEnabled(true);
		this.solveButton.setEnabled(false);
		mainAppWindow.setMenuState(true);
	}	
	
}
