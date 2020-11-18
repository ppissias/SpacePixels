package spv.gui;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.File;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import org.apache.commons.configuration2.ex.ConfigurationException;

import nom.tam.fits.FitsException;
import spv.util.FitsFileInformation;

import javax.swing.JSeparator;
import java.awt.Font;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.JSlider;
import javax.swing.event.ChangeListener;
import javax.swing.event.ChangeEvent;
import javax.swing.JCheckBox;

public class SPConfigurationApplicationPanel extends JPanel {
	// link to main window
	private ApplicationWindow mainAppWindow;

	private final JLabel astapPathLabel;
	private JTextField focalLengthTextField;
	private JTextField pixelSizeTextfield;
	private JTextField latTextField;
	private JTextField longTextField;
	private JTextField raTextfield;
	private JTextField decTextField;

	private JSlider stretchSlider;

	private JCheckBox stretchCheckbox;
	
	public boolean isStretchEnabled() {
		return stretchCheckbox.isSelected();
	}
	
	public JSlider getStretchSlider() {
		return stretchSlider;
	}

	private JSlider stretchIterationsSlider;

	public JSlider getStretchIterationsSlider() {
		return stretchIterationsSlider;
	}

	//TODO when the inage is shown full size then it cannot be plate solved (there is an oipen Fits file somewhere)
	/**
	 * Create the panel.
	 */
	public SPConfigurationApplicationPanel(ApplicationWindow mainAppWindow) {
		this.mainAppWindow = mainAppWindow;
		GridBagLayout gridBagLayout = new GridBagLayout();
		gridBagLayout.columnWidths = new int[] { 231, 332, 0 };
		gridBagLayout.rowHeights = new int[] { 21, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, };
		gridBagLayout.columnWeights = new double[] { 0.0, 1.0, Double.MIN_VALUE };
		gridBagLayout.rowWeights = new double[] { 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
				0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, Double.MIN_VALUE };
		setLayout(gridBagLayout);

		JLabel astapConfigLabel = new JLabel("ASTAP configuration");
		astapConfigLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_astapConfigLabel = new GridBagConstraints();
		gbc_astapConfigLabel.anchor = GridBagConstraints.WEST;
		gbc_astapConfigLabel.insets = new Insets(0, 0, 5, 5);
		gbc_astapConfigLabel.gridx = 0;
		gbc_astapConfigLabel.gridy = 0;
		add(astapConfigLabel, gbc_astapConfigLabel);

		JButton btnNewButton = new JButton("ASTAP path");
		btnNewButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				ApplicationWindow.logger.info("ASTAP settings");

				// Create a file chooser
				final JFileChooser fc = new JFileChooser();
				fc.setFileSelectionMode(JFileChooser.FILES_ONLY);

				fc.setDialogTitle("ASTAP executable");
				int returnVal = fc.showOpenDialog(SPConfigurationApplicationPanel.this);

				if (returnVal == JFileChooser.APPROVE_OPTION) {
					File astapExecutableFilePath = fc.getSelectedFile();

					// do a simple test run of astap
					String[] cmdArray = new String[2];
					cmdArray[0] = astapExecutableFilePath.getAbsolutePath();
					cmdArray[1] = "-h";

					try {
						Runtime.getRuntime().exec(cmdArray, null, astapExecutableFilePath.getParentFile());
					} catch (IOException e) {
						JOptionPane.showMessageDialog(new JFrame(), "Cannot execute ASTAP:" + e.getMessage(), "Error",
								JOptionPane.ERROR_MESSAGE);
						astapExecutableFilePath = null;
					}

					if (astapExecutableFilePath != null) {
						try {
							mainAppWindow.getImagePreProcessing().setProperty("astap",
									astapExecutableFilePath.getAbsolutePath());
						} catch (ConfigurationException e) {
							JOptionPane.showMessageDialog(new JFrame(),
									"Cannot set configuration file property:" + e.getMessage(), "Error",
									JOptionPane.ERROR_MESSAGE);
						}
					}

				}
			}
		});
		GridBagConstraints gbc_btnNewButton = new GridBagConstraints();
		gbc_btnNewButton.anchor = GridBagConstraints.NORTHWEST;
		gbc_btnNewButton.insets = new Insets(0, 0, 5, 5);
		gbc_btnNewButton.gridx = 0;
		gbc_btnNewButton.gridy = 1;
		add(btnNewButton, gbc_btnNewButton);

		astapPathLabel = new JLabel();
		astapPathLabel.setHorizontalAlignment(SwingConstants.LEFT);
		GridBagConstraints gbc_astapPathLabel = new GridBagConstraints();
		gbc_astapPathLabel.insets = new Insets(0, 0, 5, 0);
		gbc_astapPathLabel.fill = GridBagConstraints.HORIZONTAL;
		gbc_astapPathLabel.gridx = 1;
		gbc_astapPathLabel.gridy = 2;
		add(astapPathLabel, gbc_astapPathLabel);

		JLabel groupLabel = new JLabel("Parameters used to help with plate solving (optional)");
		groupLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_groupLabel = new GridBagConstraints();
		gbc_groupLabel.anchor = GridBagConstraints.WEST;
		gbc_groupLabel.gridwidth = 2;
		gbc_groupLabel.insets = new Insets(0, 0, 5, 0);
		gbc_groupLabel.gridx = 0;
		gbc_groupLabel.gridy = 3;
		add(groupLabel, gbc_groupLabel);

		JLabel telescopeParamsLabel = new JLabel("Telescope parameters");
		telescopeParamsLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_telescopeParamsLabel = new GridBagConstraints();
		gbc_telescopeParamsLabel.anchor = GridBagConstraints.WEST;
		gbc_telescopeParamsLabel.insets = new Insets(0, 0, 5, 5);
		gbc_telescopeParamsLabel.gridx = 0;
		gbc_telescopeParamsLabel.gridy = 4;
		add(telescopeParamsLabel, gbc_telescopeParamsLabel);

		JLabel focalLengthLabel = new JLabel("Focal length");
		focalLengthLabel.setToolTipText("focal length of the telescope");
		GridBagConstraints gbc_focalLengthLabel = new GridBagConstraints();
		gbc_focalLengthLabel.anchor = GridBagConstraints.EAST;
		gbc_focalLengthLabel.insets = new Insets(0, 0, 5, 5);
		gbc_focalLengthLabel.gridx = 0;
		gbc_focalLengthLabel.gridy = 5;
		add(focalLengthLabel, gbc_focalLengthLabel);

		focalLengthTextField = new JTextField();
		GridBagConstraints gbc_focalLengthTextField = new GridBagConstraints();
		gbc_focalLengthTextField.anchor = GridBagConstraints.WEST;
		gbc_focalLengthTextField.insets = new Insets(0, 0, 5, 0);
		gbc_focalLengthTextField.gridx = 1;
		gbc_focalLengthTextField.gridy = 5;
		add(focalLengthTextField, gbc_focalLengthTextField);
		focalLengthTextField.setColumns(10);

		JLabel cameraParamsLabel = new JLabel("Camera parameters");
		cameraParamsLabel.setToolTipText("pixel size in microns");
		cameraParamsLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_cameraParamsLabel = new GridBagConstraints();
		gbc_cameraParamsLabel.anchor = GridBagConstraints.WEST;
		gbc_cameraParamsLabel.insets = new Insets(0, 0, 5, 5);
		gbc_cameraParamsLabel.gridx = 0;
		gbc_cameraParamsLabel.gridy = 6;
		add(cameraParamsLabel, gbc_cameraParamsLabel);

		JLabel lblNewLabel = new JLabel("Pixel size");
		lblNewLabel.setToolTipText("pixel size in microns");
		GridBagConstraints gbc_lblNewLabel = new GridBagConstraints();
		gbc_lblNewLabel.anchor = GridBagConstraints.EAST;
		gbc_lblNewLabel.insets = new Insets(0, 0, 5, 5);
		gbc_lblNewLabel.gridx = 0;
		gbc_lblNewLabel.gridy = 7;
		add(lblNewLabel, gbc_lblNewLabel);

		pixelSizeTextfield = new JTextField();
		GridBagConstraints gbc_pixelSizeTextfield = new GridBagConstraints();
		gbc_pixelSizeTextfield.anchor = GridBagConstraints.WEST;
		gbc_pixelSizeTextfield.insets = new Insets(0, 0, 5, 0);
		gbc_pixelSizeTextfield.gridx = 1;
		gbc_pixelSizeTextfield.gridy = 7;
		add(pixelSizeTextfield, gbc_pixelSizeTextfield);
		pixelSizeTextfield.setColumns(10);

		JLabel fieldLabel = new JLabel("Field coordinates (approx)");
		fieldLabel.setToolTipText("Approx coordinates at the center of the image");
		fieldLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_fieldLabel = new GridBagConstraints();
		gbc_fieldLabel.anchor = GridBagConstraints.WEST;
		gbc_fieldLabel.insets = new Insets(0, 0, 5, 5);
		gbc_fieldLabel.gridx = 0;
		gbc_fieldLabel.gridy = 8;
		add(fieldLabel, gbc_fieldLabel);

		JLabel raLabel = new JLabel("RA");
		raLabel.setToolTipText("HH MM SS.xxx");
		GridBagConstraints gbc_raLabel = new GridBagConstraints();
		gbc_raLabel.anchor = GridBagConstraints.EAST;
		gbc_raLabel.insets = new Insets(0, 0, 5, 5);
		gbc_raLabel.gridx = 0;
		gbc_raLabel.gridy = 9;
		add(raLabel, gbc_raLabel);

		raTextfield = new JTextField();
		GridBagConstraints gbc_raTextfield = new GridBagConstraints();
		gbc_raTextfield.anchor = GridBagConstraints.WEST;
		gbc_raTextfield.insets = new Insets(0, 0, 5, 0);
		gbc_raTextfield.gridx = 1;
		gbc_raTextfield.gridy = 9;
		add(raTextfield, gbc_raTextfield);
		raTextfield.setColumns(10);

		JLabel decLabel = new JLabel("DEC");
		decLabel.setToolTipText("+HH MM SS.xxx");
		GridBagConstraints gbc_decLabel = new GridBagConstraints();
		gbc_decLabel.anchor = GridBagConstraints.EAST;
		gbc_decLabel.insets = new Insets(0, 0, 5, 5);
		gbc_decLabel.gridx = 0;
		gbc_decLabel.gridy = 10;
		add(decLabel, gbc_decLabel);

		decTextField = new JTextField();
		GridBagConstraints gbc_decTextField = new GridBagConstraints();
		gbc_decTextField.anchor = GridBagConstraints.WEST;
		gbc_decTextField.insets = new Insets(0, 0, 5, 0);
		gbc_decTextField.gridx = 1;
		gbc_decTextField.gridy = 10;
		add(decTextField, gbc_decTextField);
		decTextField.setColumns(10);

		JLabel group2Label = new JLabel("Parameters used for object detection and annotation");
		group2Label.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_group2Label = new GridBagConstraints();
		gbc_group2Label.anchor = GridBagConstraints.WEST;
		gbc_group2Label.gridwidth = 2;
		gbc_group2Label.insets = new Insets(0, 0, 5, 0);
		gbc_group2Label.gridx = 0;
		gbc_group2Label.gridy = 11;
		add(group2Label, gbc_group2Label);

		JLabel siteLabel = new JLabel("Site");
		siteLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_siteLabel = new GridBagConstraints();
		gbc_siteLabel.anchor = GridBagConstraints.NORTHWEST;
		gbc_siteLabel.insets = new Insets(0, 0, 5, 5);
		gbc_siteLabel.gridx = 0;
		gbc_siteLabel.gridy = 12;
		add(siteLabel, gbc_siteLabel);

		JLabel latLabel = new JLabel("Latitude (N)");
		GridBagConstraints gbc_latLabel = new GridBagConstraints();
		gbc_latLabel.anchor = GridBagConstraints.EAST;
		gbc_latLabel.insets = new Insets(0, 0, 5, 5);
		gbc_latLabel.gridx = 0;
		gbc_latLabel.gridy = 13;
		add(latLabel, gbc_latLabel);

		latTextField = new JTextField();
		GridBagConstraints gbc_latTextField = new GridBagConstraints();
		gbc_latTextField.anchor = GridBagConstraints.WEST;
		gbc_latTextField.insets = new Insets(0, 0, 5, 0);
		gbc_latTextField.gridx = 1;
		gbc_latTextField.gridy = 13;
		add(latTextField, gbc_latTextField);
		latTextField.setColumns(10);

		JLabel longLabel = new JLabel("Longitude (E)");
		GridBagConstraints gbc_longLabel = new GridBagConstraints();
		gbc_longLabel.anchor = GridBagConstraints.EAST;
		gbc_longLabel.insets = new Insets(0, 0, 5, 5);
		gbc_longLabel.gridx = 0;
		gbc_longLabel.gridy = 14;
		add(longLabel, gbc_longLabel);

		longTextField = new JTextField();
		GridBagConstraints gbc_longTextField = new GridBagConstraints();
		gbc_longTextField.anchor = GridBagConstraints.WEST;
		gbc_longTextField.insets = new Insets(0, 0, 5, 0);
		gbc_longTextField.gridx = 1;
		gbc_longTextField.gridy = 14;
		add(longTextField, gbc_longTextField);
		longTextField.setColumns(10);

		stretchCheckbox = new JCheckBox("Stretch");
		stretchCheckbox.setToolTipText("if checked the images will also be stretched");
		stretchCheckbox.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				mainAppWindow.getMainApplicationPanel().setBatchStretchButtonEnabled(isStretchEnabled());
			}
		});
		stretchCheckbox.addItemListener(new ItemListener() {
			public void itemStateChanged(ItemEvent arg0) {
				if (stretchCheckbox.isSelected()) {
					// Code to execute when it's selected
					stretchSlider.setEnabled(true);
					stretchIterationsSlider.setEnabled(true);
					
					mainAppWindow.setStretchFrameVisible(true);
					try {
						mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
					} catch (FitsException | IOException e) {
						e.printStackTrace();
					}
				} else {
					// Code to execute when not selected
					stretchSlider.setEnabled(false);
					stretchIterationsSlider.setEnabled(false);
					
					mainAppWindow.setStretchFrameVisible(false);

				}
			}
		});
		stretchCheckbox.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_chckbxNewCheckBox = new GridBagConstraints();
		gbc_chckbxNewCheckBox.anchor = GridBagConstraints.WEST;
		gbc_chckbxNewCheckBox.insets = new Insets(0, 0, 5, 5);
		gbc_chckbxNewCheckBox.gridx = 0;
		gbc_chckbxNewCheckBox.gridy = 15;
		add(stretchCheckbox, gbc_chckbxNewCheckBox);

		JLabel stretchIntensityLabel = new JLabel("Intensity");
		GridBagConstraints gbc_stretchIntensityLabel = new GridBagConstraints();
		gbc_stretchIntensityLabel.insets = new Insets(0, 0, 5, 5);
		gbc_stretchIntensityLabel.gridx = 0;
		gbc_stretchIntensityLabel.gridy = 16;
		add(stretchIntensityLabel, gbc_stretchIntensityLabel);

		JLabel stretchIterationsLabel = new JLabel("Iterations");
		GridBagConstraints gbc_stretchIterationsLabel = new GridBagConstraints();
		gbc_stretchIterationsLabel.anchor = GridBagConstraints.WEST;
		gbc_stretchIterationsLabel.insets = new Insets(0, 0, 5, 0);
		gbc_stretchIterationsLabel.gridx = 1;
		gbc_stretchIterationsLabel.gridy = 16;
		add(stretchIterationsLabel, gbc_stretchIterationsLabel);

		stretchSlider = new JSlider();
		stretchSlider.setToolTipText("Intensity");

		stretchSlider.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent arg0) {
				try {
					mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
				} catch (FitsException | IOException e) {
					e.printStackTrace();
				}
			}
		});
		GridBagConstraints gbc_stretchSlider = new GridBagConstraints();
		gbc_stretchSlider.insets = new Insets(0, 0, 5, 5);
		gbc_stretchSlider.gridx = 0;
		gbc_stretchSlider.gridy = 17;
		add(stretchSlider, gbc_stretchSlider);

		stretchIterationsSlider = new JSlider();
		stretchIterationsSlider.setPaintTicks(true);
		stretchIterationsSlider.setSnapToTicks(true);
		stretchIterationsSlider.setMajorTickSpacing(1);
		stretchIterationsSlider.setMinimum(1);
		stretchIterationsSlider.setValue(1);
		stretchIterationsSlider.setMaximum(20);
		stretchIterationsSlider.setToolTipText("Iterations");
		stretchIterationsSlider.addChangeListener(new ChangeListener() {
			public void stateChanged(ChangeEvent arg0) {
				try {
					mainAppWindow.getMainApplicationPanel().updateImageStretchWindow();
				} catch (FitsException | IOException e) {
					e.printStackTrace();
				}
			}
		});

		GridBagConstraints gbc_stretchIterationsSlider = new GridBagConstraints();
		gbc_stretchIterationsSlider.anchor = GridBagConstraints.WEST;
		gbc_stretchIterationsSlider.insets = new Insets(0, 0, 5, 0);
		gbc_stretchIterationsSlider.gridx = 1;
		gbc_stretchIterationsSlider.gridy = 17;
		add(stretchIterationsSlider, gbc_stretchIterationsSlider);

		JLabel importLabel = new JLabel("Import parameters");
		importLabel.setFont(new Font("Tahoma", Font.PLAIN, 13));
		GridBagConstraints gbc_importLabel = new GridBagConstraints();
		gbc_importLabel.anchor = GridBagConstraints.WEST;
		gbc_importLabel.insets = new Insets(0, 0, 5, 5);
		gbc_importLabel.gridx = 0;
		gbc_importLabel.gridy = 18;
		add(importLabel, gbc_importLabel);

		JButton fitsDeduceButton = new JButton("deduce from FITS header");
		fitsDeduceButton.setToolTipText("Try to deduce parameters from the selected image FITS header");
		fitsDeduceButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				FitsFileInformation selectedFile = mainAppWindow.getSelectedFile();

				if (selectedFile != null) {
					// read from FITS header
					double pixelScaleX = 0;
					double pixelScaleY = 0;

					for (String key : selectedFile.getFitsHeader().keySet()) {
						switch (key) {
						case "XPIXSZ": {
							pixelScaleX = Double.parseDouble(selectedFile.getFitsHeader().get(key));
							break;
						}

						case "YPIXSZ": {
							pixelScaleY = Double.parseDouble(selectedFile.getFitsHeader().get(key));
							break;
						}

						case "FOCALLEN": {
							focalLengthTextField.setText(selectedFile.getFitsHeader().get(key));
							break;
						}

						case "SITELAT": {
							latTextField.setText(selectedFile.getFitsHeader().get(key));
							break;
						}

						case "SITELONG": {
							longTextField.setText(selectedFile.getFitsHeader().get(key));
							break;
						}

						case "OBJCTRA": {
							raTextfield.setText(selectedFile.getFitsHeader().get(key));
							break;
						}

						case "OBJCTDEC": {
							decTextField.setText(selectedFile.getFitsHeader().get(key));
							break;
						}

						default: {

						}

						}
					}

					if (pixelScaleX != 0 && pixelScaleY != 0) {
						pixelSizeTextfield.setText("" + (pixelScaleX + pixelScaleY) / 2);
					} else if (pixelScaleX != 0) {
						pixelSizeTextfield.setText("" + pixelScaleX);
					} else if (pixelScaleY != 0) {
						pixelSizeTextfield.setText("" + pixelScaleY);
					}
				}
			}
		});
		GridBagConstraints gbc_fitsDeduceButton = new GridBagConstraints();
		gbc_fitsDeduceButton.insets = new Insets(0, 0, 5, 5);
		gbc_fitsDeduceButton.gridx = 0;
		gbc_fitsDeduceButton.gridy = 19;
		add(fitsDeduceButton, gbc_fitsDeduceButton);

		JLabel saveConfigLabel = new JLabel("Save current configuration");
		GridBagConstraints gbc_saveConfigLabel = new GridBagConstraints();
		gbc_saveConfigLabel.insets = new Insets(0, 0, 0, 5);
		gbc_saveConfigLabel.gridx = 0;
		gbc_saveConfigLabel.gridy = 20;
		add(saveConfigLabel, gbc_saveConfigLabel);

		JButton saveConfigButton = new JButton("save");
		saveConfigButton.setToolTipText("Saves current configuration");
		saveConfigButton.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent arg0) {
				// save config
				try {
					mainAppWindow.getImagePreProcessing().setProperty("ImageRA", raTextfield.getText());
					mainAppWindow.getImagePreProcessing().setProperty("ImageDEC", decTextField.getText());
					mainAppWindow.getImagePreProcessing().setProperty("SiteLat", latTextField.getText());
					mainAppWindow.getImagePreProcessing().setProperty("SiteLong", longTextField.getText());
					mainAppWindow.getImagePreProcessing().setProperty("PixelSize", pixelSizeTextfield.getText());
					mainAppWindow.getImagePreProcessing().setProperty("FocalLength", focalLengthTextField.getText());
				} catch (ConfigurationException e) {
					e.printStackTrace();
					JOptionPane.showMessageDialog(SPConfigurationApplicationPanel.this,
							"Cannot save configuration:" + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);

				}
			}
		});
		GridBagConstraints gbc_saveConfigButton = new GridBagConstraints();
		gbc_saveConfigButton.anchor = GridBagConstraints.WEST;
		gbc_saveConfigButton.gridx = 1;
		gbc_saveConfigButton.gridy = 20;
		add(saveConfigButton, gbc_saveConfigButton);

	}

	public void refreshComponents() {
		// astap path
		if (mainAppWindow.getImagePreProcessing() != null) {
			astapPathLabel.setText(mainAppWindow.getImagePreProcessing().getProperty("astap"));

			raTextfield.setText(mainAppWindow.getImagePreProcessing().getProperty("ImageRA"));
			decTextField.setText(mainAppWindow.getImagePreProcessing().getProperty("ImageDEC"));
			latTextField.setText(mainAppWindow.getImagePreProcessing().getProperty("SiteLat"));
			longTextField.setText(mainAppWindow.getImagePreProcessing().getProperty("SiteLong"));
			pixelSizeTextfield.setText(mainAppWindow.getImagePreProcessing().getProperty("PixelSize"));
			focalLengthTextField.setText(mainAppWindow.getImagePreProcessing().getProperty("FocalLength"));

		}

	}

}
