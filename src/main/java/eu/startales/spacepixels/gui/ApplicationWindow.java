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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.session.SessionOwner;
import eu.startales.spacepixels.jobs.JobFailures;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.tasks.FitsImportTask;
import eu.startales.spacepixels.util.DebugOutput;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.reporting.ReportLookupProxyServer;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
    private static final String MANUAL_URL = "https://github.com/ppissias/SpacePixels/blob/master/MANUAL.md";
    private static final String LATEST_RELEASE_API_URL = "https://api.github.com/repos/ppissias/SpacePixels/releases/latest";
    private static final int RELEASE_CHECK_TIMEOUT_MS = 4000;

    private JFrame frmIpodImage;
    private final String version = "2026.10-04";

    private final ApplicationSettings applicationSettings = ApplicationSettings.loadDefault();
    private final SessionOwner sessions = new SessionOwner();
    private final ApplicationStorage storage;
    private boolean closing;

    private volatile MainApplicationPanel mainApplicationPanel;
    private volatile ConfigurationPanel configurationApplicationPanel;
    private volatile StretchPanel stretchPanel;
    private volatile DetectionConfigurationPanel detectionConfigurationPanel;

    /** Runs the jobs that have moved off the event bus; the others still lock the window through their own events. */
    private final List<SessionListener> sessionListeners = new ArrayList<>();

    private final JobCoordinator jobCoordinator = new JobCoordinator(
            work -> new Thread(work, "SpacePixels-job").start(),
            EventQueue::invokeLater,
            () -> mainApplicationPanel != null && mainApplicationPanel.isUiLocked());

    public static final Logger logger = Logger.getLogger(ApplicationWindow.class.getName());

    private final BlinkFrame blinkFrame = new BlinkFrame();
    private final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);


    /** The "new version" notice: a pill in the accent colour at the right end of the tab bar, hidden until a newer release is found. */
    private final JButton updateNoticeLabel = new JButton();

    public static void main(String[] args) {
        DebugOutput.configureApplicationLogging();
        // --- Global Uncaught Exception Handler for OutOfMemoryError ---
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            if (JobFailures.isOutOfMemory(e)) {
                // Marking it also stops the running jobs at their next progress report.
                if (JobFailures.markApplicationOutOfMemory()) {
                    logger.log(Level.SEVERE, "Out of Memory on thread " + t.getName(), e);
                    
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
                AppIcon.installForApplication();

                ApplicationWindow window = new ApplicationWindow();
                window.frmIpodImage.setVisible(true);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to initialize application UI", e);
            }
        });
    }

    /** Creates a window with one application-owned queue for settings and session files. */
    public ApplicationWindow() {
        this(new ApplicationStorage());
    }

    /** Uses the application-owned queue, also allowing delayed stores in GUI mechanics tests. */
    public ApplicationWindow(ApplicationStorage storage) {
        this.storage = java.util.Objects.requireNonNull(storage);
        initialize();
    }

    private void initialize() {

        blinkFrame.setVisible(false);

        frmIpodImage = new JFrame();
        frmIpodImage.setTitle("SpacePixels" + " " + version);
        AppIcon.apply(frmIpodImage);
        // Resizable, sized to the screen or restored from the last session.
        WindowPlacement.install(frmIpodImage, storage);
        frmIpodImage.setResizable(true);
        frmIpodImage.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frmIpodImage.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int closeOperation = frmIpodImage.getDefaultCloseOperation();
                frmIpodImage.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                if (closing) return;
                closing = true;
                closeSessionResources();
                finishSessionStorage(closeOperation == JFrame.EXIT_ON_CLOSE);
            }

            @Override
            public void windowClosed(WindowEvent e) {
                closeSessionResources();
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

        // In the order the panels used to hear of a new session.
        addSessionListener(mainApplicationPanel::sessionChanged);
        addSessionListener(files -> configurationApplicationPanel.sessionChanged());
        addSessionListener(stretchPanel::sessionChanged);
        addSessionListener(detectionConfigurationPanel::sessionChanged);

        tabbedPane.addTab("Main", mainApplicationPanel);
        tabbedPane.addTab("Astrometry Config", configurationApplicationPanel);
        tabbedPane.addTab("Image Stretch", stretchPanel);
        tabbedPane.addTab("Detection Settings", detectionConfigurationPanel);

        configurationApplicationPanel.refreshComponents();
        setTabEnabled(configurationApplicationPanel, true);
        setTabEnabled(stretchPanel, false);
        setTabEnabled(detectionConfigurationPanel, false);
        installManualLink();

        // No menu bar: importing is the first button of the Main tab's workflow strip (or a folder dropped on the
        // window), and the "new version" notice sits next to the User Manual link at the right end of the tab bar.

        TransferHandler folderDrop = new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && mainApplicationPanel.isImportEnabled();
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files.isEmpty()) {
                        return false;
                    }
                    File dropped = files.get(0);
                    importFolder(dropped.isDirectory() ? dropped : dropped.getParentFile());
                    return true;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Could not import the dropped folder", e);
                    return false;
                }
            }
        };
        frmIpodImage.setTransferHandler(folderDrop);
        mainApplicationPanel.installFolderDrop(folderDrop);

        startReleaseCheck();
    }

    private void closeSessionResources() {
        closing = true;
        jobCoordinator.cancel();
        if (configurationApplicationPanel != null) configurationApplicationPanel.close();
        if (mainApplicationPanel != null) mainApplicationPanel.closeSessionViews();
        sessions.close();
        storage.close();
        ReportLookupProxyServer.getInstance().stop();
    }

    private void finishSessionStorage(boolean exit) {
        new Thread(() -> {
            try {
                storage.finish();
                EventQueue.invokeLater(() -> {
                    if (storage.getSessionWriteFailure() != null) {
                        JOptionPane.showMessageDialog(frmIpodImage,
                                "A session metadata write failed. Some edits may not have been saved.\n"
                                        + storage.getSessionWriteFailure().getMessage(),
                                "Session save error", JOptionPane.WARNING_MESSAGE);
                    }
                    if (!storage.getSettingsFailures().isEmpty()) {
                        StringBuilder details = new StringBuilder("Some application defaults may not have been saved.\n");
                        storage.getSettingsFailures().forEach((target, failure) ->
                                details.append(target).append(": ").append(failure.getMessage()).append('\n'));
                        JOptionPane.showMessageDialog(frmIpodImage, details.toString(),
                                "Settings save error", JOptionPane.WARNING_MESSAGE);
                    }
                    frmIpodImage.dispose();
                    if (exit) System.exit(0);
                });
            } catch (Exception failure) {
                logger.log(Level.WARNING, "Application data is still being saved", failure);
                EventQueue.invokeLater(() -> {
                    int answer = JOptionPane.showConfirmDialog(frmIpodImage,
                            "Session metadata or settings have not finished saving. Wait and try closing again?\n" + failure.getMessage(),
                            "Pending writes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION) {
                        finishSessionStorage(exit);
                    } else {
                        frmIpodImage.dispose();
                        if (exit) System.exit(1);
                    }
                });
            }
        }, "SpacePixels-session-shutdown").start();
    }

    /** Asks for a folder of aligned frames and imports it. */
    public void chooseAndImportFolder() {
        logger.info("Will try to import FITS/XISF files.");
        final JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Folder containing aligned FITS or XISF images");
        if (fc.showOpenDialog(frmIpodImage) == JFileChooser.APPROVE_OPTION) {
            importFolder(fc.getSelectedFile());
        }
    }

    /** Imports a folder of aligned frames in the background. */
    public void importFolder(File folder) {
        if (folder != null && folder.isDirectory()) {
            JobCoordinator.Admission admission = jobCoordinator.start(JobKind.IMPORT, sessions.current(),
                    new FitsImportTask(folder, applicationSettings, storage, new GuiFitsImportInteraction()),
                    (handle, outcome) -> importFinished(handle.session(), outcome));
            if (!admission.admitted()) {
                mainApplicationPanel.showStatus(admission.refusal());
            }
        }
    }

    /** Registers a panel to hear, on the event thread, when the session changes. */
    void addSessionListener(SessionListener listener) {
        sessionListeners.add(listener);
    }

    private void fireSessionChanged(FitsFileInformation[] files) {
        for (SessionListener listener : sessionListeners) {
            listener.sessionChanged(files);
        }
    }

    /** The end of an import: installs the imported session, or says why there is none. */
    private void importFinished(ProcessingSession expectedSession, JobOutcome<FitsImportTask.Result> outcome) {
        if (outcome instanceof JobOutcome.Completed<FitsImportTask.Result> completed) {
            ProcessingSession imported = new ProcessingSession(completed.result().processing(),
                    completed.result().files(), completed.result().lastReport(), completed.result().metadata(),
                    storage, EventQueue::invokeLater);
            if (sessions.replace(expectedSession, imported)) {
                installSession(imported);
                mainApplicationPanel.showLastReport(imported.getLastReport());
            }
            return;
        }
        if (!sessions.isCurrent(expectedSession)) return;
        if (outcome instanceof JobOutcome.Declined<FitsImportTask.Result>) {
            // Nothing was imported, for example because a conversion was declined: the open session stays.
            mainApplicationPanel.showStatus(sessions.current() != null
                    ? "Nothing imported; the open session is unchanged." : "No files loaded");
        } else if (outcome instanceof JobOutcome.Failed<FitsImportTask.Result> failed) {
            mainApplicationPanel.showStatus("Import failed");
            FailureDialog.show(frmIpodImage, "Import Error", "Error loading files: " + failed.reason(), failed.cause());
        }
        // The session is unchanged, and the end of the lock opened the session tabs only if there is one.
    }

    /** Makes the imported folder the window's session, in place of the one before it. */
    private void installSession(ProcessingSession session) {
        FitsFileInformation[] filesInfo = session.getFiles();

        // Update UI components
        AbstractTableModel tableModel = new FitsFileTableModel(filesInfo);
        mainApplicationPanel.setTableModel(tableModel);

        setSessionTabsEnabled(true);

        fireSessionChanged(filesInfo);

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

        // --- Issue warning for large datasets ---
        if (filesInfo.length > 100) {
            JOptionPane.showMessageDialog(frmIpodImage,
                    "You have imported a large dataset (" + filesInfo.length + " files).\n" +
                    "Please be aware that running the standard detection pipeline on the entire dataset at once may cause the application to run out of memory depending on your system's resources.\n\n" +
                    "Consider using the Iterative Detection option with a safe frame limit.",
                    "Large Dataset Warning", JOptionPane.WARNING_MESSAGE);
        }
    }

    // --- HELPER METHODS ---

    /**
     * The tabs that need a session: Astrometry Config, Image Stretch and Detection Settings. They are found by their
     * panel, not by their position.
     */
    void setSessionTabsEnabled(boolean state) {
        setTabEnabled(configurationApplicationPanel, state || sessions.current() == null && jobCoordinator.current() == null);
        setTabEnabled(stretchPanel, state);
        setTabEnabled(detectionConfigurationPanel, state);
    }

    private void setTabEnabled(Component component, boolean state) {
        int index = tabbedPane.indexOfComponent(component);
        if (index >= 0) {
            tabbedPane.setEnabledAt(index, state);
        }
    }

    // --- GETTERS AND SETTERS ---

    public JobCoordinator getJobCoordinator() {
        return jobCoordinator;
    }

    public BlinkFrame getBlinkFrame() {
        return blinkFrame;
    }

    public ApplicationSettings getApplicationSettings() { return applicationSettings; }

    /** The single application queue for settings writes and ordered session I/O. */
    public ApplicationStorage getStorage() { return storage; }

    boolean isClosing() { return closing; }

    public ProcessingSession getSession() { return sessions.current(); }

    public boolean isCurrentSession(ProcessingSession session) { return sessions.isCurrent(session); }

    public <R> JobCoordinator.OutcomeHandler<R> forCurrentSession(JobCoordinator.OutcomeHandler<R> handler) {
        return (handle, outcome) -> sessions.applyIfCurrent(handle.session(), () -> handler.handle(handle, outcome));
    }

    public ImageProcessing getImageProcessing() {
        ProcessingSession session = sessions.current();
        return session == null ? null : session.getProcessing();
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

        updateNoticeLabel.setText("New version " + latestRelease.versionTag + " ↗");
        updateNoticeLabel.setToolTipText("A newer SpacePixels release is available. Click to open the releases page.");
        updateNoticeLabel.setVisible(true);
        updateNoticeLabel.getParent().revalidate();
        for (var listener : updateNoticeLabel.getActionListeners()) updateNoticeLabel.removeActionListener(listener);
        updateNoticeLabel.addActionListener(event -> openInBrowser(latestRelease.htmlUrl));
    }

    /** A "User Manual" link at the right end of the tab bar, so it is visible from every tab. */
    private void installManualLink() {
        JButton manualLink = AccessibleControls.link("User Manual", "Open SpacePixels user manual",
                () -> openInBrowser(MANUAL_URL));
        manualLink.setForeground(DetectionConfigurationPanel.accentColor());
        manualLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        manualLink.setToolTipText("Open the SpacePixels user manual in your browser: workflows, the report and examples.");
        manualLink.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        updateNoticeLabel.setFont(updateNoticeLabel.getFont().deriveFont(Font.BOLD));
        updateNoticeLabel.setForeground(Color.WHITE);
        updateNoticeLabel.setBackground(DetectionConfigurationPanel.accentColor());
        updateNoticeLabel.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        updateNoticeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateNoticeLabel.setVisible(false);

        // FlatLaf gives the trailing component the remaining width of the tab bar; keep the links at its right end.
        JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        links.setOpaque(false);
        links.add(updateNoticeLabel);
        links.add(manualLink);
        JPanel trailing = new JPanel(new GridBagLayout());
        trailing.setOpaque(false);
        GridBagConstraints right = new GridBagConstraints();
        right.weightx = 1;
        right.anchor = GridBagConstraints.EAST;
        trailing.add(links, right);
        tabbedPane.putClientProperty("JTabbedPane.trailingComponent", trailing);
    }

    private void openInBrowser(String url) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            logger.warning("Failed to open " + url + ": " + e.getMessage());
        }
    }
}
