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

import com.formdev.flatlaf.FlatDarkLaf;
import nom.tam.fits.FitsException;
import org.apache.commons.configuration2.ex.ConfigurationException;
import spv.util.FitsFileInformation;
import spv.util.ImagePreprocessing;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ApplicationWindow {

    private JFrame frmIpodImage;

    private final String version = "Feb-2026-1           ";

    private volatile ImagePreprocessing imagePreProcessing;

    private MainApplicationPanel mainApplicationPanel;
    private ConfigurationPanel configurationApplicationPanel;
    private StretchPanel stretchPanel;

    // --- NEW: The Detection Configuration Panel ---
    private DetectionConfigurationPanel detectionConfigurationPanel;

    //logger
    public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());

    private StretchPreviewFrame stretchPreviewFrame;
    private FullImageViewFrame fullImagePreviewFrame = new FullImageViewFrame();

    private JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

    private JMenu fileMenu = new JMenu("File");
    private JMenuItem importMenuItem = new JMenuItem("Import aligned fits files");

    public void setMenuState(boolean state) {
        importMenuItem.setEnabled(state);
    }

    public FullImageViewFrame getFullImagePreviewFrame() {
        return fullImagePreviewFrame;
    }

    /**
     * Launch the application.
     */
    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            try {
                // 1. Enable rounded corners for all inputs
                UIManager.put("Component.arc", 10);
                // 2. Make buttons even rounder
                UIManager.put("Button.arc", 10);
                // 3. Change the accent color (using a Hex code)
                UIManager.put("AccentColor", "#4285f4");

                // 4. Set the Look and Feel
                FlatDarkLaf.setup();

                ApplicationWindow window = new ApplicationWindow();
                window.frmIpodImage.setVisible(true);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize application UI", e);
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
        stretchPreviewFrame = new StretchPreviewFrame(this);
        stretchPreviewFrame.setVisible(false);
        fullImagePreviewFrame.setVisible(false);

        frmIpodImage = new JFrame();
        frmIpodImage.setTitle("SpacePixels" + " " + version);
        frmIpodImage.setBounds(new Rectangle(50, 50, 1200, 650));
        frmIpodImage.setResizable(false);
        frmIpodImage.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frmIpodImage.getContentPane().setLayout(new BorderLayout(0, 0));

        //the main tabbed pane
        frmIpodImage.getContentPane().add(tabbedPane, BorderLayout.CENTER);

        mainApplicationPanel = new MainApplicationPanel(this);
        configurationApplicationPanel = new ConfigurationPanel(this);
        stretchPanel = new StretchPanel(this);

        // --- NEW: Instantiate the Detection Configuration Panel ---
        detectionConfigurationPanel = new DetectionConfigurationPanel();

        tabbedPane.addTab("Main", mainApplicationPanel);
        tabbedPane.addTab("Configuration", configurationApplicationPanel);
        tabbedPane.addTab("Stretch Parameters", stretchPanel);
        // --- NEW: Add the Detection Settings tab ---
        tabbedPane.addTab("Detection Settings", detectionConfigurationPanel);

        // Disable tabs until data is loaded
        tabbedPane.setEnabledAt(1, false);
        tabbedPane.setEnabledAt(2, false);
        tabbedPane.setEnabledAt(3, false); // Disable Detection tab initially

        JMenuBar menuBar = new JMenuBar();
        frmIpodImage.setJMenuBar(menuBar);

        JMenu fileMenu = new JMenu("File");
        menuBar.add(fileMenu);

        importMenuItem.addActionListener(e -> {
            ApplicationWindow.logger.info("Will try to import fits files!");

            mainApplicationPanel.setProgressBarWorking();

            //Create a file chooser
            final JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Directory containing aligned fits images");

            int returnVal = fc.showOpenDialog(frmIpodImage);

            if (returnVal == JFileChooser.APPROVE_OPTION) {
                File file = fc.getSelectedFile();

                // Modernized thread invocation
                new Thread(() -> {
                    try {
                        imagePreProcessing = ImagePreprocessing.getInstance(file);
                        final FitsFileInformation[] filesInformation = imagePreProcessing.getFitsfileInformation();

                        //update table
                        final AbstractTableModel tableModel = new FitsFileTableModel(filesInformation);

                        // Update UI on Event Dispatch Thread
                        EventQueue.invokeLater(() -> {
                            mainApplicationPanel.setTableModel(tableModel);

                            // Enable all tabs now that data is loaded
                            tabbedPane.setEnabledAt(1, true);
                            tabbedPane.setEnabledAt(2, true);
                            tabbedPane.setEnabledAt(3, true); // Enable Detection tab

                            boolean existsColor = false;
                            for (FitsFileInformation fitsFile: filesInformation) {
                                if (!fitsFile.isMonochrome()) {
                                    existsColor = true;
                                    break;
                                }
                            }

                            if (!existsColor) {
                                mainApplicationPanel.setDetectionEnabled();
                            }
                            configurationApplicationPanel.refreshComponents();

                            // THE FIX: Stop the progress bar ONLY AFTER the load is complete and UI is updated
                            mainApplicationPanel.setProgressBarIdle();
                        });

                    } catch (IOException | FitsException | ConfigurationException ex) {
                        logger.log(Level.SEVERE, "Error loading FITS files", ex);

                        // Stop the progress bar even if it fails!
                        EventQueue.invokeLater(() -> {
                            mainApplicationPanel.setProgressBarIdle();
                            JOptionPane.showMessageDialog(frmIpodImage,
                                    "Error loading files: " + ex.getMessage(),
                                    "Import Error", JOptionPane.ERROR_MESSAGE);
                        });
                    }
                }).start();
            } else {
                // THE FIX: If the user cancels the dialog, stop the progress bar immediately
                mainApplicationPanel.setProgressBarIdle();
            }
        });

        fileMenu.add(importMenuItem);
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

    public MainApplicationPanel getMainApplicationPanel() {
        return mainApplicationPanel;
    }

    public ConfigurationPanel getConfigurationApplicationPanel() {
        return configurationApplicationPanel;
    }

    public StretchPanel getStretchPanel() {
        return stretchPanel;
    }

    // --- NEW: Getter for the Detection Panel (in case you need it elsewhere) ---
    public DetectionConfigurationPanel getDetectionConfigurationPanel() {
        return detectionConfigurationPanel;
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