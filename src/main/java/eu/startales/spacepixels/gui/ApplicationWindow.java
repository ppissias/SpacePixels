/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
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
import eu.startales.spacepixels.util.ImageProcessing;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ApplicationWindow {

    private JFrame frmIpodImage;
    private final String version = "2026.03-beta8";

    // --- EVENT BUS INSTANCE ---
    private final EventBus eventBus = new EventBus("SpacePixelsBus");

    private volatile ImageProcessing imagePreProcessing;

    private volatile MainApplicationPanel mainApplicationPanel;
    private volatile ConfigurationPanel configurationApplicationPanel;
    private volatile StretchPanel stretchPanel;
    private volatile DetectionConfigurationPanel detectionConfigurationPanel;

    public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());

    private final BlinkFrame blinkFrame = new BlinkFrame(getEventBus());
    private final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
    private final JMenu fileMenu = new JMenu("File");
    private final JMenuItem importMenuItem = new JMenuItem("Import aligned fits files");

    public static volatile boolean OOM_FLAG = false;

    public static void main(String[] args) {
        // --- Global Uncaught Exception Handler for OutOfMemoryError ---
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Throwable rootCause = e;
            while (rootCause != null && !(rootCause instanceof OutOfMemoryError)) {
                if (rootCause == rootCause.getCause()) break;
                rootCause = rootCause.getCause();
            }
            if (rootCause instanceof OutOfMemoryError) {
                if (!OOM_FLAG) {
                    OOM_FLAG = true;
                    logger.log(Level.SEVERE, "Out of Memory on thread " + t.getName(), rootCause);
                    
                    // Failsafe exit timer in case the EDT is completely frozen
                    Thread doomThread = new Thread(() -> {
                        try { Thread.sleep(10000); } catch (Exception ignore) {}
                        System.exit(1);
                    });
                    doomThread.setDaemon(true);
                    doomThread.start();

                    SwingUtilities.invokeLater(() -> {
                        try {
                            JOptionPane.showMessageDialog(null,
                                    "SpacePixels has run out of memory and must close.\n" +
                                    "Please process fewer images or increase the Java heap space (e.g., -Xmx8G).",
                                    "Fatal Error: Out of Memory", JOptionPane.ERROR_MESSAGE);
                        } catch (Throwable ignored) {
                        } finally {
                            System.exit(1);
                        }
                    });
                }
            } else {
                logger.log(Level.SEVERE, "Uncaught Exception on thread " + t.getName(), e);
            }
        });

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

        blinkFrame.setVisible(false);

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

        detectionConfigurationPanel = new DetectionConfigurationPanel(ApplicationWindow.this);
        eventBus.register(detectionConfigurationPanel);

        tabbedPane.addTab("Main", mainApplicationPanel);
        tabbedPane.addTab("Astrometry Config", configurationApplicationPanel);
        tabbedPane.addTab("Image Stretch", stretchPanel);
        tabbedPane.addTab("Detection Settings", detectionConfigurationPanel);

        setTabEnabled(configurationApplicationPanel, false);
        setTabEnabled(stretchPanel, false);
        setTabEnabled(detectionConfigurationPanel, false);

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
            setTabEnabled(configurationApplicationPanel, false);
            setTabEnabled(stretchPanel, false);
            setTabEnabled(detectionConfigurationPanel, false);
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

                setTabEnabled(configurationApplicationPanel, true);
                setTabEnabled(stretchPanel, true);
                setTabEnabled(detectionConfigurationPanel, true);

                configurationApplicationPanel.refreshComponents();
                
                // --- Issue warning for large datasets ---
                if (filesInfo != null && filesInfo.length > 100) {
                    JOptionPane.showMessageDialog(frmIpodImage,
                            "You have imported a large dataset (" + filesInfo.length + " files).\n" +
                            "Please be aware that running the standard detection pipeline on the entire dataset at once\n" +
                            "may cause the application to run out of memory depending on your system's resources.\n\n" +
                            "Consider using the Iterative Detection option with a safe frame limit.",
                            "Large Dataset Warning", JOptionPane.WARNING_MESSAGE);
                }
            } else {
                // Handle Error
                JOptionPane.showMessageDialog(frmIpodImage,
                        "Error loading files: " + event.getErrorMessage(),
                        "Import Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    // --- HELPER METHODS ---

    private void setTabEnabled(Component component, boolean state) {
        int index = tabbedPane.indexOfComponent(component);
        if (index >= 0) {
            tabbedPane.setEnabledAt(index, state);
        }
    }

    // --- GETTERS AND SETTERS ---

    public EventBus getEventBus() {
        return eventBus;
    }

    public void setMenuState(boolean state) {
        importMenuItem.setEnabled(state);
    }

    public BlinkFrame getBlinkFrame() {
        return blinkFrame;
    }

    public ImageProcessing getImageProcessing() {
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
        setTabEnabled(mainApplicationPanel, state);
    }

    public JTabbedPane getTabbedPane() {
        return tabbedPane;
    }

    public Frame getFrame() {return frmIpodImage;}
}