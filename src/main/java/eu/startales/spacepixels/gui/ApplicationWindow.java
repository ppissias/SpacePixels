/*
 * SpacePixels
 *
 * Copyright (c)2020-2023, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.FitsImportFinishedEvent;
import eu.startales.spacepixels.events.FitsImportStartedEvent;
import eu.startales.spacepixels.tasks.FitsImportTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImagePreprocessing;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ApplicationWindow {

    private JFrame frmIpodImage;
    private final String version = "Mar-2026-1";

    // --- EVENT BUS INSTANCE ---
    private final EventBus eventBus = new EventBus("SpacePixelsBus");

    private volatile ImagePreprocessing imagePreProcessing;

    private MainApplicationPanel mainApplicationPanel;
    private ConfigurationPanel configurationApplicationPanel;
    private StretchPanel stretchPanel;
    private DetectionConfigurationPanel detectionConfigurationPanel;

    public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());

    private final FullImageViewFrame fullImagePreviewFrame = new FullImageViewFrame(getEventBus());
    private final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
    private final JMenu fileMenu = new JMenu("File");
    private final JMenuItem importMenuItem = new JMenuItem("Import aligned fits files");

    public static void main(String[] args) {
        EventQueue.invokeLater(() -> {
            try {
                UIManager.put("Component.arc", 10);
                UIManager.put("Button.arc", 10);
                UIManager.put("AccentColor", "#4285f4");
                FlatDarkLaf.setup();

                ApplicationWindow window = new ApplicationWindow();
                window.frmIpodImage.setVisible(true);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize application UI", e);
            }
        });
    }

    public ApplicationWindow() {
        // Register this class to listen for EventBus events
        eventBus.register(this);
        initialize();
    }

    private void initialize() {

        fullImagePreviewFrame.setVisible(false);

        frmIpodImage = new JFrame();
        frmIpodImage.setTitle("SpacePixels" + " " + version);
        frmIpodImage.setBounds(new Rectangle(50, 50, 1200, 650));
        frmIpodImage.setResizable(false);
        frmIpodImage.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frmIpodImage.getContentPane().setLayout(new BorderLayout(0, 0));
        frmIpodImage.getContentPane().add(tabbedPane, BorderLayout.CENTER);

        mainApplicationPanel = new MainApplicationPanel(this);
        configurationApplicationPanel = new ConfigurationPanel(this);
        stretchPanel = new StretchPanel(this);
        detectionConfigurationPanel = new DetectionConfigurationPanel();

        tabbedPane.addTab("Main", mainApplicationPanel);
        tabbedPane.addTab("Configuration", configurationApplicationPanel);
        tabbedPane.addTab("Stretch Parameters", stretchPanel);
        tabbedPane.addTab("Detection Settings", detectionConfigurationPanel);

        tabbedPane.setEnabledAt(1, false);
        tabbedPane.setEnabledAt(2, false);
        tabbedPane.setEnabledAt(3, false);

        JMenuBar menuBar = new JMenuBar();
        frmIpodImage.setJMenuBar(menuBar);
        menuBar.add(fileMenu);

        // --- REFACTORED MENU LISTENER ---
        importMenuItem.addActionListener(e -> {
            logger.info("Will try to import fits files!");
            final JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Directory containing aligned fits images");

            if (fc.showOpenDialog(frmIpodImage) == JFileChooser.APPROVE_OPTION) {
                File file = fc.getSelectedFile();
                // Hand the work off to the background task
                new Thread(new FitsImportTask(eventBus, file)).start();
            }
        });

        fileMenu.add(importMenuItem);
    }

    // --- EVENT BUS SUBSCRIBERS ---

    @Subscribe
    public void onImportStarted(FitsImportStartedEvent event) {
        EventQueue.invokeLater(() -> {
            mainApplicationPanel.setProgressBarWorking();
            setMenuState(false);
            tabbedPane.setEnabledAt(1, false);
            tabbedPane.setEnabledAt(2, false);
            tabbedPane.setEnabledAt(3, false);
        });
    }

    @Subscribe
    public void onImportFinished(FitsImportFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            mainApplicationPanel.setProgressBarIdle();
            setMenuState(true);

            if (event.isSuccess()) {
                // Update internal state
                this.imagePreProcessing = event.getImagePreProcessing();
                FitsFileInformation[] filesInfo = event.getFilesInformation();

                // Update UI components
                AbstractTableModel tableModel = new FitsFileTableModel(filesInfo);
                mainApplicationPanel.setTableModel(tableModel);

                tabbedPane.setEnabledAt(1, true);
                tabbedPane.setEnabledAt(2, true);
                tabbedPane.setEnabledAt(3, true);

                boolean existsColor = false;
                for (FitsFileInformation fitsFile: filesInfo) {
                    if (!fitsFile.isMonochrome()) {
                        existsColor = true;
                        break;
                    }
                }

                if (!existsColor) {
                    mainApplicationPanel.setDetectionEnabled();
                }
                configurationApplicationPanel.refreshComponents();
            } else {
                // Handle Error
                JOptionPane.showMessageDialog(frmIpodImage,
                        "Error loading files: " + event.getErrorMessage(),
                        "Import Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // --- GETTERS AND SETTERS ---

    public EventBus getEventBus() {
        return eventBus;
    }

    public void setMenuState(boolean state) {
        importMenuItem.setEnabled(state);
    }

    public FullImageViewFrame getFullImagePreviewFrame() {
        return fullImagePreviewFrame;
    }

    public ImagePreprocessing getImagePreProcessing() {
        return imagePreProcessing;
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

    public DetectionConfigurationPanel getDetectionConfigurationPanel() {
        return detectionConfigurationPanel;
    }

    public void setMainViewEnabled(boolean state) {
        tabbedPane.setEnabledAt(0, state);
    }

    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }
}