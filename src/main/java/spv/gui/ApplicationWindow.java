package spv.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import org.apache.commons.configuration2.ex.ConfigurationException;

import io.github.ppissias.astrolib.PlateSolveResult;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;
import spv.util.ImagePreprocessing;

public class ApplicationWindow {

	private JFrame frame;

	private ImagePreprocessing imagePreProcessing;
	
	private File astapExecutableFilePath = null;
	
	private class Pair<A,B> {
		private final A a; 
		private final B b; 
		
		public Pair(A a, B b) {
			this.a = a;
			this.b = b;
		}
		
		public A getTypeA() {
			return a;
		}
		
		public B getTypeB() {
			return b;
		}
		
		@Override
		public String toString() {
			return b.toString();
		}
	}
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
		frame.setBounds(100, 100, 667, 532);
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
		
		final DefaultListModel<Pair<File, String>> l1 = new DefaultListModel<>();
		JList<Pair<File, String>> list = new JList<>(l1);	
		scrollPane.setViewportView(list);
		
		JMenuBar menuBar = new JMenuBar();
		frame.setJMenuBar(menuBar);
		
		JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);

		solveButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				System.out.println("Will try to solve image");
				
				Pair<File, String> selectedFitsEntry = list.getSelectedValue();
				File selectedFitsFile = selectedFitsEntry.getTypeA();
										
				Future<PlateSolveResult> solveResult = imagePreProcessing.solve(selectedFitsFile.getAbsolutePath(), astapSolveCheckbox.isSelected(), astrometrynetSolveCheckbox.isSelected());
				if (solveResult != null) {
					try {
						PlateSolveResult result = solveResult.get();
						
						System.out.println(result);
					} catch (InterruptedException | ExecutionException e) {
						JOptionPane.showMessageDialog(new JFrame(), "Cannot solve image:"+e.getMessage(), "Error",JOptionPane.ERROR_MESSAGE);
					}
				}
				
			}
		});

		JMenuItem importMenuItem = new JMenuItem("Import aligned fits files");
		importMenuItem.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {							
				System.out.println("Will try to import fits files!");
								
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
						Fits[] files = imagePreProcessing.getFitsFiles();
						for (int i=0;i<files.length;i++) {
							int hdu = files[i].getNumberOfHDUs();
							String fpath = imagePreProcessing.getFitsFilesDetails()[i].getAbsolutePath();
							
							String info = "";
							Header fitsHeader = files[i].getHDU(0).getHeader();
							Cursor<String, HeaderCard> iter = fitsHeader.iterator();							 						
							
							int[] axes = files[i].getHDU(0).getAxes();
							if (axes.length == 2) {
								//mono image
								info += "Monochrome image ";
								short[][] data = (short[][])files[i].getHDU(0).getKernel();
								info += "Y length:"+data.length+" ";
								info += "X length:"+data[0].length+" ";
							} else if (axes.length == 3) {
								///color image
								info += "Color image ";
								short[][][] data = (short[][][])files[i].getHDU(0).getKernel();
								info += "Y length:"+data[0].length+" ";
								info += "X length:"+data[0][0].length+" ";
							}
							
													
							while (iter.hasNext()) {
								HeaderCard fitsHeaderCard = iter.next();
								info += fitsHeaderCard.getKey()+":"+fitsHeaderCard.getValue()+" ";
								System.out.println("processing "+fitsHeaderCard.getKey()+" key form fits header");
							}
																					
							//add entry to list
							String listEntryInfo = fpath+" hdu:"+hdu+"header elements:"+fitsHeader.getNumberOfCards()+" info:"+info;
							Pair<File, String> listEntryPair = new Pair<File, String>(imagePreProcessing.getFitsFilesDetails()[i],listEntryInfo);
							l1.addElement(listEntryPair);
						}
						
						//close FITS files so that ASTAP can open them 
						ImagePreprocessing.closeFitsFiles(files);
						
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
				System.out.println("ASTAP settings");
				
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
