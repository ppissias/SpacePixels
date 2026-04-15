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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.events.FitsImportFinishedEvent;
import eu.startales.spacepixels.events.FitsImportStartedEvent;
import eu.startales.spacepixels.tasks.FitsImportTask;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.ReportLookupProxyServer;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApplicationWindow {

    private static class ReleaseInfo {
        private final String versionTag;
        private final String htmlUrl;

        private ReleaseInfo(String versionTag, String htmlUrl) {
            this.versionTag = versionTag;
            this.htmlUrl = htmlUrl;
        }
    }

    private static final String RELEASES_PAGE_URL = "https://github.com/ppissias/SpacePixels/releases";
    private static final String LATEST_RELEASE_API_URL = "https://api.github.com/repos/ppissias/SpacePixels/releases/latest";
    private static final int RELEASE_CHECK_TIMEOUT_MS = 4000;

    private JFrame frmIpodImage;
    private final String version = "2026.04-05";

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
    private final JMenuItem importMenuItem = new JMenuItem("Import aligned FITS/XISF files");
    private final JLabel updateNoticeLabel = new JLabel();

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
        frmIpodImage.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                ReportLookupProxyServer.getInstance().stop();
            }
        });
        frmIpodImage.getContentPane().setLayout(new BorderLayout(0, 0));
        frmIpodImage.getContentPane().add(tabbedPane, BorderLayout.CENTER);

        if (!ReportLookupProxyServer.getInstance().start()) {
            logger.warning("Live report rendering proxy is unavailable. Static report links will still work.");
        }

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
        menuBar.add(Box.createHorizontalGlue());
        updateNoticeLabel.setFont(updateNoticeLabel.getFont().deriveFont(Font.PLAIN, 11f));
        updateNoticeLabel.setForeground(new Color(155, 155, 155));
        updateNoticeLabel.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 10));
        updateNoticeLabel.setVisible(false);
        menuBar.add(updateNoticeLabel);

        // --- REFACTORED MENU LISTENER ---
        importMenuItem.addActionListener(e -> {
            logger.info("Will try to import FITS/XISF files.");
            final JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("Directory containing aligned FITS or XISF images");

            if (fc.showOpenDialog(frmIpodImage) == JFileChooser.APPROVE_OPTION) {
                File file = fc.getSelectedFile();
                // Hand the work off to the background task
                new Thread(new FitsImportTask(eventBus, file)).start();
            }
        });

        fileMenu.add(importMenuItem);
        startReleaseCheck();
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
                FitsFileInformation[] filesInfo = event.getFilesInformation();
                if (filesInfo == null) {
                    filesInfo = new FitsFileInformation[0];
                }

                if (filesInfo.length == 0) {
                    clearImportedDataset();
                    return;
                }

                // Update internal state
                this.imagePreProcessing = event.getImagePreProcessing();

                // Update UI components
                AbstractTableModel tableModel = new FitsFileTableModel(filesInfo);
                mainApplicationPanel.setTableModel(tableModel);

                setTabEnabled(configurationApplicationPanel, true);
                setTabEnabled(stretchPanel, true);
                setTabEnabled(detectionConfigurationPanel, true);

                configurationApplicationPanel.refreshComponents();

                if (filesInfo != null) {
                    int unusableTimestampCount = 0;
                    StringBuilder examples = new StringBuilder();
                    for (FitsFileInformation fileInfo : filesInfo) {
                        if (fileInfo != null && fileInfo.hasDisplayableObservationDateWithoutUsableTimestamp()) {
                            unusableTimestampCount++;
                            if (examples.length() < 700) {
                                examples.append(" - ")
                                        .append(fileInfo.getFileName())
                                        .append(": ")
                                        .append(fileInfo.getObservationTimestampDiagnostics())
                                        .append("\n");
                            }
                        }
                    }

                    if (unusableTimestampCount > 0) {
                        JOptionPane.showMessageDialog(
                                frmIpodImage,
                                "SpacePixels imported " + unusableTimestampCount + " file(s) with a visible FITS date/time\n" +
                                        "that still could not be converted into the timestamp passed to JTransient.\n\n" +
                                        "Those frames will be sent with no usable timing information, so time-based linking\n" +
                                        "and any timing-sensitive diagnostics may be skipped or degraded.\n\n" +
                                        "Examples:\n" + examples,
                                "Timestamp Parsing Warning",
                                JOptionPane.WARNING_MESSAGE);
                    }
                }
                
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

    private void clearImportedDataset() {
        this.imagePreProcessing = null;
        mainApplicationPanel.setTableModel(new FitsFileTableModel(new FitsFileInformation[0]));
        setTabEnabled(configurationApplicationPanel, false);
        setTabEnabled(stretchPanel, false);
        setTabEnabled(detectionConfigurationPanel, false);
    }

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

    private void startReleaseCheck() {
        Thread releaseCheckThread = new Thread(() -> {
            try {
                ReleaseInfo latestRelease = fetchLatestReleaseInfo();
                if (latestRelease != null && isVersionNewer(latestRelease.versionTag, version)) {
                    SwingUtilities.invokeLater(() -> showUpdateNotice(latestRelease));
                }
            } catch (Exception e) {
                logger.fine("Release check skipped: " + e.getMessage());
            }
        }, "SpacePixels-ReleaseCheck");
        releaseCheckThread.setDaemon(true);
        releaseCheckThread.start();
    }

    private ReleaseInfo fetchLatestReleaseInfo() throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API_URL).openConnection();
        connection.setConnectTimeout(RELEASE_CHECK_TIMEOUT_MS);
        connection.setReadTimeout(RELEASE_CHECK_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "SpacePixels/" + version);

        int responseCode = connection.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("GitHub release API returned HTTP " + responseCode);
        }

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String latestTag = root.has("tag_name") && !root.get("tag_name").isJsonNull() ? root.get("tag_name").getAsString() : null;
            String latestUrl = root.has("html_url") && !root.get("html_url").isJsonNull() ? root.get("html_url").getAsString() : RELEASES_PAGE_URL;

            if (latestTag == null || latestTag.isBlank()) {
                return null;
            }
            return new ReleaseInfo(latestTag, latestUrl);
        } finally {
            connection.disconnect();
        }
    }

    private boolean isVersionNewer(String candidateVersion, String currentVersion) {
        List<Integer> candidateParts = extractVersionNumbers(candidateVersion);
        List<Integer> currentParts = extractVersionNumbers(currentVersion);

        if (!candidateParts.isEmpty() && !currentParts.isEmpty()) {
            int maxParts = Math.max(candidateParts.size(), currentParts.size());
            for (int i = 0; i < maxParts; i++) {
                int candidatePart = i < candidateParts.size() ? candidateParts.get(i) : 0;
                int currentPart = i < currentParts.size() ? currentParts.get(i) : 0;
                if (candidatePart != currentPart) {
                    return candidatePart > currentPart;
                }
            }
            return false;
        }

        return !candidateVersion.equalsIgnoreCase(currentVersion);
    }

    private List<Integer> extractVersionNumbers(String versionText) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("(\\d+)").matcher(versionText);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }

    private void showUpdateNotice(ReleaseInfo latestRelease) {
        logger.info("New SpacePixels release available: " + latestRelease.versionTag);

        updateNoticeLabel.setText("New version: " + latestRelease.versionTag);
        updateNoticeLabel.setToolTipText("A newer SpacePixels release is available. Click to open the releases page.");
        updateNoticeLabel.setForeground(new Color(202, 162, 72));
        updateNoticeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateNoticeLabel.setVisible(true);
        updateNoticeLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                openReleasePage(latestRelease.htmlUrl);
            }
        });
    }

    private void openReleasePage(String releaseUrl) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(releaseUrl));
        } catch (Exception e) {
            logger.warning("Failed to open releases page: " + e.getMessage());
        }
    }
}
