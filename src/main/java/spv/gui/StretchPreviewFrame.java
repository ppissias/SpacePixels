package spv.gui;

import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import spv.util.FitsFileInformation;
import spv.util.StretchAlgorithm;

import javax.swing.JSplitPane;
import javax.swing.JButton;
import java.awt.event.ActionListener;
import java.awt.event.ActionEvent;

public class StretchPreviewFrame extends JFrame {

	private JPanel contentPane;

	private JPanel originalImagePanel;
	
	private JPanel strethcedImagePanel;
	
	//private JLabel originalImageLabel;
	
	//private JLabel stretchedImageLabel;

	private ImageVisualizerComponent originalImageComponent = new ImageVisualizerComponent();
	private ImageVisualizerComponent stretchedImageComponent = new ImageVisualizerComponent();

	// link to main window
	private ApplicationWindow mainAppWindow;
	/**
	 * Create the frame.
	 * @param applicationWindow 
	 */
	public StretchPreviewFrame(ApplicationWindow applicationWindow) {
		setTitle("Stretch preview");
		this.mainAppWindow = applicationWindow;
		
		setResizable(false);
		setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		setBounds(100, 100, 800, 440);
		setAlwaysOnTop(true);
		contentPane = new JPanel();
		contentPane.setBorder(new EmptyBorder(5, 5, 5, 5));
		setContentPane(contentPane);
		contentPane.setLayout(null);
		
		originalImagePanel = new JPanel();
		originalImagePanel.setBounds(21, 0, 350, 350);
		contentPane.add(originalImagePanel);
		
		strethcedImagePanel = new JPanel();
		strethcedImagePanel.setBounds(400, 0, 350, 350);
		contentPane.add(strethcedImagePanel);
		
        BufferedImage originalBufferedImage = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
        ImageIcon originalImageIcon = new ImageIcon( originalBufferedImage );
        //originalImageLabel = new JLabel(originalImageIcon);
        
        originalImagePanel.add(originalImageComponent);
        
        BufferedImage stretchedBufferedImage = new BufferedImage(350, 350, BufferedImage.TYPE_INT_RGB);
        ImageIcon stretchedImageIcon = new ImageIcon( stretchedBufferedImage );
        //stretchedImageLabel= new JLabel(stretchedImageIcon);
        
        strethcedImagePanel.add(stretchedImageComponent);
        
        JButton btnNewButton = new JButton("show full size");
        btnNewButton.addActionListener(new ActionListener() {
        	public void actionPerformed(ActionEvent arg0) {
        		int stretchFactor = mainAppWindow.getConfigurationApplicationPanel().getStretchSlider().getValue();
        		int iterations = mainAppWindow.getConfigurationApplicationPanel().getStretchIterationsSlider().getValue();
        		
        		FitsFileInformation selectedFitsFileInfo = mainAppWindow.getSelectedFile();
        		if (selectedFitsFileInfo != null) {
        			Fits selectedFitsImage;
					try {
						selectedFitsImage = new Fits(selectedFitsFileInfo.getFilePath());
        				StretchAlgorithm algo = mainAppWindow.getConfigurationApplicationPanel().getStretchAlgorithm();

        				//get image data
        				Object kernelData = selectedFitsImage.getHDU(0).getKernel();
        				
        				BufferedImage fitsImagePreview = mainAppWindow.getImagePreProcessing().getStretchedImageFullSize(kernelData, 
        						selectedFitsFileInfo.getSizeWidth(), 
        						selectedFitsFileInfo.getSizeHeight(), 
        						stretchFactor, iterations, algo);
        				selectedFitsImage.close();
        				
        				mainAppWindow.getFullImagePreviewFrame().setImage(fitsImagePreview);
                		mainAppWindow.getFullImagePreviewFrame().setVisible(true);

					} catch (FitsException | IOException e) {
						e.printStackTrace();
						JOptionPane.showMessageDialog(StretchPreviewFrame.this,
								"Cannot show full image:" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
						
						return;
					}

        		} else {
        		}
        	}
        });
        btnNewButton.setBounds(410, 361, 135, 23);
        contentPane.add(btnNewButton);
        
	}
	
	public void setOriginalImage(BufferedImage image) {
       // ImageIcon newImageIcon = new ImageIcon( image );
        //originalImageLabel.setIcon(newImageIcon);	
		originalImageComponent.setImage(image);
	}
	
	public void setStretchedImage(BufferedImage image) {
        //ImageIcon newImageIcon = new ImageIcon( image );
        //stretchedImageLabel.setIcon(newImageIcon);
        stretchedImageComponent.setImage(image);
        
	}	
}
