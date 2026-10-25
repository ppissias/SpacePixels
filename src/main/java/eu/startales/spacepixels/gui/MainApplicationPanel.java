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

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.session.SkyCatalogueModel;
import eu.startales.spacepixels.util.DetectionOverlays;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.IterativeDetectionRequest;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.util.StretchAlgorithm;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.SessionReports;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jtransient.config.DetectionConfig;
import eu.startales.spacepixels.jobs.*;
import eu.startales.spacepixels.tasks.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;


public class MainApplicationPanel extends JPanel implements ActiveSettingsReadout, FrameList {

    private static final Logger LOGGER = Logger.getLogger(MainApplicationPanel.class.getName());

    /** Look of the primary action (Detect Moving Targets): accent colours of the look and feel, larger and bold. */
    static final String PRIMARY_BUTTON_STYLE = "font: +2 bold; background: $Button.default.background; foreground: $Button.default.foreground;"
            + " borderColor: $Button.default.borderColor; margin: 6,16,6,16";
    private static final Color LINK_COLOR = new Color(120, 180, 255);

    private final JFrame windowFrame;
    private final BlinkFrame blink;
    private final JobCoordinator jobs;
    private final SessionAccess sessions;
    private final ApplicationStorage storage;
    private final ApplicationSettings applicationSettings;
    private final FolderImport folderImport;
    private final Consumer<Boolean> sessionTabs;
    /** The stretch and detection panels, wired once before the window is shown. */
    private StretchPanel stretch;
    private DetectionConfigurationPanel detectionSettings;
    private Runnable showDetectionSettings;

    // --- NEW: PROGRESS DIALOG ---
    private final ProcessingProgressDialog progressDialog;

    //the table
    private volatile JTable table;

    private final JProgressBar progressBar = new JProgressBar();
    private final JButton importButton = new JButton("Import Aligned Frames…");
    private final JButton convertMonoButton = new JButton("Convert to Mono");
    private final JButton stretchButton = new JButton("Batch Stretch");
    private static final String BLINK_SELECTED = "Blink Selected";
    private final JButton blinkButton = new JButton(BLINK_SELECTED);
    private final JButton solveButton = new JButton("Plate Solve Selected");
    private final JButton skyCatalogueButton = new JButton(FETCH_SKY_CATALOGUE);
    private final JButton detectSingleButton = new JButton("Preview Frame");
    private final JButton manualTransientInspectionButton = new JButton("Manual Transient Inspection");
    private final JButton detectBatchButton = new JButton("Detect Moving Targets");
    private final JButton detectIterativelyButton = new JButton("Detect Iteratively (large datasets)");
    // The newest report of the session, under the Detect buttons; hidden when there is none.
    private final JButton lastReportButton = new JButton();
    private final JButton outputFolderButton = AccessibleControls.link("Open output folder", "Open last report output folder",
            this::openOutputFolder);
    private final WrappingHelpLabel analysisScope = new WrappingHelpLabel("", 1000);
    private String enabledAnalyses = "moving objects and streaks";
    private final SkyCatalogueModel emptySkyCatalogue = new SkyCatalogueModel();
    private SkyCatalogueModel subscribedSkyCatalogue;
    private final Runnable correctionChanged = this::refreshWorkflowStatus;
    private static final String AUTO_TUNE_TOOLTIP = "<html>Open the Detection Settings and run Auto-Tune on your frames, with the tuner and profile chosen there "
            + "(Ctrl+T).<br>It measures the noise and the faintest stars it can find, and sets the detection settings to match.</html>";

    private final JButton autoTuneButton = new JButton("Auto-Tune Settings", new AutoTuneIcon(20));
    /** Shows the Detection Settings Overview and starts Auto-Tune there; set by the settings panel. */
    private Runnable openOverviewAndRunAutoTune;

    // The commands with several entry points (button, shortcut, table menu, the Overview): one action each, enabled
    // from CommandAvailability.
    private final CommandAction detectAction = new CommandAction("Detect Moving Targets",
            "Run the full pipeline: detect, link and report moving objects across the whole sequence (Ctrl+D).",
            CommandAvailability.Command.DETECT, this::commandFacts, this::startDetection);
    private final CommandAction autoTuneAction = new CommandAction("Auto-Tune Settings", AUTO_TUNE_TOOLTIP,
            CommandAvailability.Command.AUTO_TUNE, this::commandFacts, () -> {
                if (openOverviewAndRunAutoTune != null) {
                    openOverviewAndRunAutoTune.run();
                }
            });
    private final CommandAction previewAction = new CommandAction("Preview Frame",
            "Run the extraction engine on the selected frame to preview detected sources and streaks with the current settings (Ctrl+P).",
            CommandAvailability.Command.PREVIEW, this::commandFacts, this::previewSelectedFrame);
    private final CommandAction blinkAction = new CommandAction(BLINK_SELECTED,
            "Animate the selected frames in a new window for manual visual inspection (Ctrl+B).",
            CommandAvailability.Command.BLINK, this::commandFacts, this::startOrStopBlink);
    private final CommandAction solveAction = new CommandAction("Plate Solve Selected",
            "Calculate the celestial coordinates (WCS) for the selected frame.",
            CommandAvailability.Command.PLATE_SOLVE, this::commandFacts, this::solveSelectedFrame);
    // The commands with one entry point, enabled the same way.
    private final CommandAction importAction = new CommandAction("Import Aligned Frames…",
            "Choose a folder of aligned FITS or XISF frames to import (or drop the folder onto the window). "
                    + "Compressed, 32-bit and XISF data are converted to a working folder first.",
            CommandAvailability.Command.IMPORT, this::commandFacts, this::chooseAndImportFolder);
    private final CommandAction convertMonoAction = new CommandAction("Convert to Mono",
            "Extract luminance and convert all loaded FITS files to 16-bit monochrome. Applies stretch if enabled.",
            CommandAvailability.Command.CONVERT_TO_MONO, this::commandFacts, this::convertToMono);
    private final CommandAction stretchAction = new CommandAction("Batch Stretch",
            "Apply the current non-linear stretch settings to all imported FITS files and save as new files.",
            CommandAvailability.Command.BATCH_STRETCH, this::commandFacts, this::batchStretch);
    private final CommandAction skyCatalogueAction = new CommandAction(FETCH_SKY_CATALOGUE,
            "<html>Download the stars (Gaia), galaxies, nebulae and clusters (SIMBAD) and variable stars "
                    + "(AAVSO VSX)<br>in the field of the plate-solved frames, for the Annotate switch (A) of the viewers. "
                    + "Runs in the background;<br>the star depth is set in the Astrometry Config tab.</html>",
            CommandAvailability.Command.SKY_CATALOGUE, this::commandFacts, this::onSkyCatalogueButton);
    private final CommandAction inspectionAction = new CommandAction("Manual Transient Inspection",
            "Extract purified transients from all frames against the master background and navigate through them using arrow keys.",
            CommandAvailability.Command.INSPECTION, this::commandFacts, this::startTransientInspection);
    private final CommandAction iterativeAction = new CommandAction("Detect Iteratively (large datasets)",
            "For datasets too large for the standard run: multiple temporally spaced passes across the sequence.",
            CommandAvailability.Command.DETECT_ITERATIVELY, this::commandFacts, this::startIterativeDetection);
    private final List<CommandAction> commandActions = List.of(importAction, convertMonoAction, stretchAction, solveAction,
            skyCatalogueAction, blinkAction, previewAction, inspectionAction, autoTuneAction, detectAction, iterativeAction);
    private final JRadioButton astapSolveRadio = new JRadioButton("ASTAP");
    private final JRadioButton astrometryNetSolveRadio = new JRadioButton("Astrometry.net (online)");

    // Workflow-strip status lines, built only from what is already known (headers, selection, solved flags)
    private final JLabel prepareStatus = new JLabel();
    private final JLabel colourNotice = new JLabel("<html><b>Colour dataset · limited analysis</b><br>"
            + "Blink and stretch in colour; convert to mono<br>for Preview, Auto-Tune and detection.</html>",
            UIManager.getIcon("OptionPane.informationIcon"), SwingConstants.LEFT);
    private final JLabel astrometryStatus = new JLabel();
    private final JLabel inspectStatus = new JLabel();
    private final JLabel detectStatus = new JLabel();
    /** Readout of the settings the calibrated auto-tuner sets, shown beside Detect Moving Targets. */
    private final JLabel activeDetectionValue = new JLabel("—");
    private final JLabel activeMaskValue = new JLabel("—");
    private final JLabel activeOverlapValue = new JLabel("—");
    private final JLabel activeJitterValue = new JLabel("—");
    private final List<JPanel> workflowGroups = new ArrayList<>();
    private volatile boolean uiLocked;
    private boolean hasSolvedFrame;

    private final JLabel statusLabel = new JLabel(" Ready");

    private volatile boolean containsColorImages = true;

    /** Set to stop the frames of the current blink from loading. */
    private AtomicBoolean blinkStop = new AtomicBoolean();
    /** The blink window is playing: it holds the window's lock until it closes. */
    private boolean blinkPlaying;

    // Sky catalogue: fetched in the background, shown at the right of the status bar while it loads.
    private static final String FETCH_SKY_CATALOGUE = "Fetch Sky Catalogue";
    private final JLabel skyFetchLabel = new JLabel();
    private final JProgressBar skyFetchBar = new JProgressBar();
    private final JPanel skyFetchIndicator = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private SkyCatalogueController skyController;

    private DetectionSequenceFrame detectionSequenceFrame;

    /**
     * Builds the main panel from the window's session, jobs and import. The stretch and detection panels are attached
     * afterwards; they do not exist yet.
     */
    public MainApplicationPanel(JFrame windowFrame, BlinkFrame blink, JobCoordinator jobs, SessionAccess sessions,
            ApplicationStorage storage, ApplicationSettings applicationSettings, FolderImport folderImport,
            Consumer<Boolean> sessionTabs) {
        this.windowFrame = windowFrame;
        this.blink = blink;
        this.jobs = jobs;
        this.sessions = sessions;
        this.storage = storage;
        this.applicationSettings = applicationSettings;
        this.folderImport = folderImport;
        this.sessionTabs = sessionTabs;

        this.progressDialog = new ProcessingProgressDialog(windowFrame);
        restoreLiveViewPreference();
        blink.setOnClose(this::blinkWindowClosed);
        jobs.setListener(new JobCoordinator.Listener() {
            @Override
            public void jobStarted(JobHandle handle) {
                JobTexts texts = JobTexts.of(handle.kind());
                if (texts.initializing() == null) {
                    // The blink window opens in front once this returns. Disabling the control that has the focus
                    // would move the focus to another control here, which brings this window back in front of it.
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
                }
                disableControlsProcessing();
                statusLabel.setText(texts.started());
                if (texts.initializing() == null) {
                    // Blink shows its loading in its own window; its command, now Stop Blinking, stays available.
                    return;
                }
                setProgressBarWorking();
                progressDialog.beginPreview(handle.kind() == JobKind.DETECTION || handle.kind() == JobKind.ITERATIVE_DETECTION);
                progressDialog.offerCancel(handle.kind().cancellable() ? () -> jobs.cancel() : null);
                if (texts.indeterminate()) {
                    progressDialog.showIndeterminateProgress(texts.initializing());
                } else {
                    progressDialog.updateProgress(0, texts.initializing());
                }
                // Shown after this call returns: the dialog is modal and would hold the click until the job ends.
                EventQueue.invokeLater(() -> {
                    if (jobs.current() == handle) {
                        progressDialog.setVisible(true);
                    }
                });
            }

            @Override
            public void jobProgress(JobHandle handle, int percent, String message) {
                if (!sessions.isCurrent(handle.session())) return;
                if (JobTexts.of(handle.kind()).initializing() == null) {
                    statusLabel.setText(message);
                } else if (progressDialog.isVisible()) {
                    progressDialog.updateProgress(percent, message);
                }
            }

            @Override
            public void jobEnded(JobHandle handle, JobOutcome<?> outcome) {
                progressDialog.setVisible(false);
                progressDialog.endPreview();
                setProgressBarIdle();
                enableControlsProcessingFinished();
                if (!sessions.isCurrent(handle.session())) return;
                statusLabel.setText(outcome instanceof JobOutcome.Cancelled<?>
                        ? handle.kind().title() + " cancelled."
                        : JobTexts.of(handle.kind()).ended());
            }
        });

        setLayout(new BorderLayout(0, 0));

        // ==========================================
        // TOP CONTROL AREA
        // ==========================================
        // A workflow strip: four labelled groups in the order of use, wrapping onto a second line when narrow.
        // Every workflow button takes its caption, tooltip and enabled state from its command (see commandFacts).
        importButton.setAction(importAction);
        convertMonoButton.setAction(convertMonoAction);
        stretchButton.setAction(stretchAction);
        skyCatalogueButton.setAction(skyCatalogueAction);
        manualTransientInspectionButton.setAction(inspectionAction);
        detectIterativelyButton.setAction(iterativeAction);
        solveButton.setAction(solveAction);
        blinkButton.setAction(blinkAction);
        detectSingleButton.setAction(previewAction);
        detectBatchButton.setAction(detectAction);
        autoTuneAction.putValue(Action.SMALL_ICON, new AutoTuneIcon(20));
        autoTuneButton.setAction(autoTuneAction);

        astapSolveRadio.setToolTipText("Solve with ASTAP (configured in the Astrometry Config tab).");
        astrometryNetSolveRadio.setToolTipText("Solve with the online nova.astrometry.net web service.");
        ButtonGroup solverGroup = new ButtonGroup();
        solverGroup.add(astapSolveRadio);
        solverGroup.add(astrometryNetSolveRadio);
        astapSolveRadio.setSelected(true);

        // The primary action: accent colours of the look and feel, larger and bold.
        detectBatchButton.putClientProperty("FlatLaf.style", PRIMARY_BUTTON_STYLE);
        // As tall as the primary button next to it, without its accent colours.
        autoTuneButton.putClientProperty("FlatLaf.style", "font: +2; margin: 5,10,5,14; iconTextGap: 6");

        JPanel prepareGroup = workflowGroup("1  Prepare", prepareStatus,
                buttonRow(importButton),
                buttonRow(convertMonoButton),
                buttonRow(stretchButton));
        JPanel astrometryGroup = workflowGroup("2  Astrometry", astrometryStatus,
                buttonRow(solveButton),
                buttonRow(skyCatalogueButton),
                buttonRow(new JLabel("Solver:"), astapSolveRadio, astrometryNetSolveRadio));
        JPanel inspectGroup = workflowGroup("3  Inspect", inspectStatus,
                buttonRow(blinkButton, detectSingleButton),
                buttonRow(manualTransientInspectionButton));
        detectStatus.setForeground(UIManager.getColor("Label.disabledForeground"));
        detectStatus.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
        detectStatus.setAlignmentX(Component.LEFT_ALIGNMENT);
        // The note wraps under the button. Its own preferred width must not push the Detect button away.
        JPanel tuneColumn = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                int width = autoTuneButton.getPreferredSize().width;
                int height = autoTuneButton.getPreferredSize().height + wrappedStatusHeight(width);
                Insets insets = getInsets();
                return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(getPreferredSize().width, Integer.MAX_VALUE);
            }
        };
        tuneColumn.setLayout(new BoxLayout(tuneColumn, BoxLayout.Y_AXIS));
        tuneColumn.setOpaque(false);
        autoTuneButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        tuneColumn.add(autoTuneButton);
        tuneColumn.add(detectStatus);
        JComponent settingsCard = activeSettingsCard();
        JPanel detectColumn = new JPanel();
        detectColumn.setLayout(new BoxLayout(detectColumn, BoxLayout.Y_AXIS));
        detectColumn.setOpaque(false);
        detectBatchButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        detectIterativelyButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        settingsCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        detectColumn.add(detectBatchButton);
        detectColumn.add(Box.createVerticalStrut(4));
        detectColumn.add(detectIterativelyButton);
        detectColumn.add(Box.createVerticalStrut(6));
        detectColumn.add(settingsCard);
        // A button, so the keyboard reaches it, drawn like the Edit settings… link.
        lastReportButton.putClientProperty("JButton.buttonType", "borderless");
        lastReportButton.setForeground(LINK_COLOR);
        lastReportButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lastReportButton.setHorizontalAlignment(SwingConstants.LEFT);
        lastReportButton.setMargin(new Insets(2, 2, 2, 2));
        lastReportButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        lastReportButton.setVisible(false);
        lastReportButton.addActionListener(e -> openLastReport());
        detectColumn.add(Box.createVerticalStrut(2));
        detectColumn.add(lastReportButton);
        outputFolderButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        outputFolderButton.setVisible(false);
        detectColumn.add(outputFolderButton);
        JPanel detectBody = new JPanel();
        detectBody.setLayout(new BoxLayout(detectBody, BoxLayout.X_AXIS));
        detectBody.setOpaque(false);
        detectBody.setAlignmentX(Component.LEFT_ALIGNMENT);
        tuneColumn.setAlignmentY(Component.TOP_ALIGNMENT);
        detectColumn.setAlignmentY(Component.TOP_ALIGNMENT);
        detectBody.add(tuneColumn);
        detectBody.add(Box.createHorizontalStrut(8));
        detectBody.add(detectColumn);
        JPanel detectGroup = workflowGroup("4  Detect", null, detectBody);

        JPanel workflowStrip = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
        workflowStrip.add(prepareGroup);
        workflowStrip.add(astrometryGroup);
        workflowStrip.add(inspectGroup);
        workflowStrip.add(detectGroup);
        colourNotice.setBorder(BorderFactory.createEmptyBorder(2, 12, 8, 12));
        colourNotice.setVisible(false);
        colourNotice.setToolTipText("<html>Colour frames support Blink Selected, Image Stretch and Batch Stretch, and Convert to Mono."
                + "<br>Astrometry remains available. Preview Frame, Manual Transient Inspection, Auto-Tune and all detection"
                + "<br>(including variable-star photometry) need monochrome frames. Import the converted mono folder to use them.</html>");
        JPanel topArea = new JPanel(new BorderLayout());
        topArea.add(workflowStrip, BorderLayout.CENTER);
        JPanel notices = new JPanel();
        notices.setLayout(new BoxLayout(notices, BoxLayout.Y_AXIS));
        analysisScope.setBorder(BorderFactory.createEmptyBorder(0, UiStyle.space(12), UiStyle.space(6), UiStyle.space(12)));
        analysisScope.getAccessibleContext().setAccessibleName("Analysis plan and frame scope");
        notices.add(analysisScope);
        notices.add(colourNotice);
        topArea.add(notices, BorderLayout.SOUTH);
        add(topArea, BorderLayout.NORTH);
        refreshWorkflowStatus();

        // ==========================================
        // MAIN TABLE AREA
        // ==========================================
        JScrollPane scrollPane = new JScrollPane();
        add(scrollPane, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new BorderLayout(10, 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEtchedBorder(), BorderFactory.createEmptyBorder(2, 4, 2, 6)));
        statusBar.add(statusLabel, BorderLayout.CENTER);
        progressBar.setPreferredSize(new Dimension(150, 16));
        // Right end: the sky catalogue fetch indicator and the progress bar.
        JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        statusRight.setOpaque(false);
        skyFetchBar.setIndeterminate(true);
        skyFetchBar.setPreferredSize(new Dimension(80, 12));
        skyFetchIndicator.setOpaque(false);
        skyFetchIndicator.add(skyFetchLabel);
        skyFetchIndicator.add(skyFetchBar);
        skyFetchIndicator.setVisible(false);
        statusRight.add(skyFetchIndicator);
        statusRight.add(progressBar);
        statusBar.add(statusRight, BorderLayout.EAST);
        add(statusBar, BorderLayout.SOUTH);

        table = new JTable();
        scrollPane.setViewportView(table);
        installEarthLinkSupport();
        installTableContextMenu();
        installShortcuts();

        // Plate Solve and Blink follow the selection through refreshWorkflowStatus.
        table.getSelectionModel().addListSelectionListener(event -> {
            if (sessions.current() != null) sessions.current().setSelection(getSelectedFilesInformation());
            stretch.onFrameSelectionChanged();
            refreshWorkflowStatus();
        });

        // A viewer switches the correction on or off; the Astrometry status follows.


    }

    // ==========================================
    // THE SHARED COMMANDS
    // ==========================================

    /** Wires the panels created after this one. Called once on the EDT before the window is shown. */
    void attach(StretchPanel stretch, DetectionConfigurationPanel detectionSettings, Runnable showDetectionSettings) {
        this.stretch = stretch;
        this.detectionSettings = detectionSettings;
        this.showDetectionSettings = showDetectionSettings;
    }

    /** The facts the shared commands are enabled from, as they are now. */
    private CommandAvailability.Facts commandFacts() {
        int frames = table == null || table.getModel() == null ? 0 : table.getRowCount();
        int selectedRow = table == null ? -1 : table.getSelectedRow();
        int solved = 0;
        for (int row = 0; row < frames; row++) {
            if (isSolvedRow(row)) {
                solved++;
            }
        }
        JobCoordinator coordinator = jobs;
        JobHandle running = coordinator == null ? null : coordinator.current();
        boolean blinking = blinkPlaying || (running != null && running.kind() == JobKind.BLINK);
        StretchPanel stretchPanel = stretch;
        return new CommandAvailability.Facts(frames, !containsColorImages,
                table == null ? 0 : table.getSelectedRowCount(), selectedRow >= 0 && isSolvedRow(selectedRow), solved,
                stretchPanel != null && stretchPanel.isStretchEnabled(), uiLocked, blinking, skyController != null && skyController.isRunning());
    }

    private boolean isSolvedRow(int row) {
        return "Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(row, FitsFileTableModel.COL_SOLVED)));
    }

    /**
     * Enables every command from the facts as they are now. While a blink loads or plays, Blink stops it, and while
     * the sky catalogue is fetched, its command cancels the fetch. Call it when a fact changes outside this panel,
     * such as the batch-stretch switch.
     */
    @Override
    public void refreshCommands() {
        for (CommandAction action : commandActions) {
            action.refresh();
        }
        CommandAvailability.Facts facts = commandFacts();
        blinkAction.putValue(Action.NAME, facts.blinking() ? "Stop Blinking" : BLINK_SELECTED);
        skyCatalogueAction.putValue(Action.NAME, facts.skyFetching() ? "Cancel Sky Catalogue" : FETCH_SKY_CATALOGUE);
    }

    /** Whether a folder can be imported now, from the button or by dropping it on the window. */
    boolean isImportEnabled() {
        return CommandAvailability.of(CommandAvailability.Command.IMPORT, commandFacts()).enabled();
    }

    /** The Detect Moving Targets command, for the Overview's button and the Ctrl+D of the settings tab. */
    @Override
    public Action detectAction() {
        return detectAction;
    }

    /** The Preview Frame command, for the Overview's button. */
    @Override
    public Action previewAction() {
        return previewAction;
    }

    /** Java preference (node of this package) for the progress dialog's Hide/Show picture choice. */
    static final String LIVE_VIEW_PREFERENCE = "progressDialog.liveView";

    /** Applies the saved Hide/Show picture choice; a change is saved in the background, like the window placement. */
    private void restoreLiveViewPreference() {
        Preferences preferences;
        try {
            preferences = Preferences.userNodeForPackage(MainApplicationPanel.class);
        } catch (RuntimeException unavailable) {
            return;
        }
        progressDialog.setPreviewPreference(preferences.getBoolean(LIVE_VIEW_PREFERENCE, true), shown ->
                storage.submit("Live view", () -> {
                    preferences.putBoolean(LIVE_VIEW_PREFERENCE, shown);
                    preferences.flush();
                    return null;
                }));
    }

    /** Detect Moving Targets, with the detection settings as they are now; a setting with invalid text stops it. */
    private void startDetection() {
        DetectionConfigurationPanel configuration = detectionSettings;
        DetectionConfig settings = configuration.checkedSettings("Detect Moving Targets did not start.");
        if (settings == null) {
            return;
        }
        ProcessingSession owner = sessions.current();
        if (owner == null) return;
        GuiDetectionReportPrompt reportPrompt = new GuiDetectionReportPrompt(windowFrame);
        DetectionTask detection = new DetectionTask(owner.getFiles(), settings,
                DetectionRunOptions.capture(applicationSettings.getConfig(), configuration.autoTuneMaxCandidateFrames())
                        .withVisualization(configuration.visualizationSettings()),
                owner.getSkyCatalogue().snapshot(),
                summary -> reportPrompt.confirm(summary, owner.hasPlateSolvedFrame(), 50, null, settings))
                        .showingPreviews(progressDialog.previewSink());
        // The overlays first: they belong to the session whatever became of the report.
        startJob(JobKind.DETECTION, owner, detection, (handle, outcome) -> {
            showDetectionOverlays(handle.session(), detection.detectedOverlays());
            showDetectionOutcome(handle, outcome);
        });
    }

    /** Blinks the selected frames, or stops the blink that is loading or playing. */
    private void startOrStopBlink() {
        JobHandle running = jobs.current();
        if (blinkPlaying || (running != null && running.kind() == JobKind.BLINK)) {
            // Stop: closing the window ends the blink, or the loading, which then stops at the next frame.
            blink.close();
            return;
        }
        FitsFileInformation[] selectedFitsFilesInfo = getSelectedFilesInformation();
        if (selectedFitsFilesInfo == null || selectedFitsFilesInfo.length == 0 || !confirmBlinkMemory(selectedFitsFilesInfo)) {
            return;
        }
        AtomicBoolean stop = new AtomicBoolean();
        BlinkFrame blinkFrame = blink;
        BlinkImagesTask task = new BlinkImagesTask(selectedFitsFilesInfo, stop,
                (read, total) -> EventQueue.invokeLater(() -> blinkFrame.showLoadProgress(read, total)));
        JobCoordinator.Admission admission = jobs.start(JobKind.BLINK,
                sessions.current(), task,
                sessions.forCurrent((handle, outcome) -> blinkLoaded(selectedFitsFilesInfo, outcome)));
        if (!admission.admitted()) {
            statusLabel.setText(admission.refusal());
            return;
        }
        blinkStop = stop;
        blinkFrame.showLoading(selectedFitsFilesInfo);
    }

    private void chooseAndImportFolder() {
        folderImport.choose();
    }

    /** Converts every frame to 16-bit monochrome in a new folder, stretched when the Image Stretch tab says so. */
    private void convertToMono() {
        int stretchFactor = stretch.getStretchSlider().getValue();
        int iterations = stretch.getStretchIterationsSlider().getValue();
        boolean stretchEnabled = stretch.isStretchEnabled();
        StretchAlgorithm algo = stretch.getStretchAlgorithm();

        ProcessingSession owner = sessions.current();
        if (owner == null) return;
        startJob(JobKind.CONVERT_TO_MONO, owner,
                new BatchConvertMonoTask(owner.getBatchService(), stretchEnabled, stretchFactor, iterations, algo),
                this::showConversionOutcome);
    }

    /** Writes stretched copies of every frame, with the Image Stretch tab's stretch. */
    private void batchStretch() {
        int stretchFactor = stretch.getStretchSlider().getValue();
        int iterations = stretch.getStretchIterationsSlider().getValue();
        StretchAlgorithm algo = stretch.getStretchAlgorithm();

        ProcessingSession owner = sessions.current();
        if (owner == null) return;
        startJob(JobKind.BATCH_STRETCH, owner, new BatchStretchTask(owner.getBatchService(), stretchFactor, iterations, algo),
                this::showStretchOutcome);
    }

    /** Manual Transient Inspection, with the detection settings as they are now; a setting with invalid text stops it. */
    private void startTransientInspection() {
        DetectionConfig settings = detectionSettings.checkedSettings("Manual Transient Inspection did not start.");
        if (settings == null) {
            return;
        }
        ProcessingSession owner = sessions.current();
        if (owner == null) return;
        startJob(JobKind.TRANSIENT_INSPECTION, owner, new ManualTransientInspectionTask(owner.getFiles(), settings), (handle, outcome) -> {
            if (outcome instanceof JobOutcome.Completed<ManualTransientInspectionTask.Result> completed) {
                ManualTransientInspectionTask.Result result = completed.result();
                TransientInspectionFrame frame = new TransientInspectionFrame(result.frames(), result.transients(), result.files(),
                        stretch);
                frame.setSkyCatalogueModel(skyCatalogue());
                frame.setMarkerSettings(() -> detectionSettings.visualizationSettings());
                if (!frame.open(sessions.current() == null ? null : sessions.current().getTrackOverlay())) {
                    JOptionPane.showMessageDialog(null, "The detection kept no frames to inspect.",
                            "Manual Transient Inspection", JOptionPane.INFORMATION_MESSAGE);
                }
            } else {
                showDetectionProblem(outcome);
            }
        });
    }

    private void startIterativeDetection() {
        File initial = sessions.current() == null || sessions.current().getFiles().length == 0 ? null
                : new File(sessions.current().getFiles()[0].getFilePath()).getParentFile();
        IterativeDetectionDialog dialog = new IterativeDetectionDialog((Window) windowFrame, initial);
        dialog.setVisible(true);
        IterativeDetectionDialog.Selection selected = dialog.selection();
        if (selected == null) return;
        DetectionConfigurationPanel configuration = detectionSettings;
        DetectionConfig settings = configuration.checkedSettings("Detect Iteratively did not start.");
        if (settings == null) return;
        AppConfig application = applicationSettings.getConfig().copy();
        IterativeDetectionRequest request = new IterativeDetectionRequest(selected.directory(), settings, application,
                configuration.visualizationSettings(), selected.plan(),
                selected.autoTuneProfile(), selected.autoTuneFrames(), selected.autoPrepare(), selected.keepNegative(), selected.availableFrames(),
                sessions.current() == null ? null : sessions.current().getSkyCatalogue().snapshot());
        GuiDetectionReportPrompt reportPrompt = new GuiDetectionReportPrompt(windowFrame);
        IterativeDetectionTask task = new IterativeDetectionTask(request, (summary, files, passSettings) ->
                reportPrompt.confirm(summary, java.util.Arrays.stream(files).anyMatch(FitsFileInformation::isWcsSolved),
                        50, "Iterative pass: " + files.length + " frames", passSettings))
                .showingPreviews(progressDialog.previewSink());
        JobCoordinator.Admission admission = jobs.start(JobKind.ITERATIVE_DETECTION,
                sessions.current(), task,
                sessions.forCurrent((handle, outcome) -> showIterativeOutcome(outcome, task)));
        if (!admission.admitted()) statusLabel.setText(admission.refusal());
    }

    private void showIterativeOutcome(JobOutcome<File> outcome, IterativeDetectionTask task) {
        if (outcome instanceof JobOutcome.Completed<File> completed) {
            statusLabel.setText("Iterative results: " + completed.result().getParent());
            promptUserToOpenReport(completed.result());
        } else {
            File index = task.getIndexFile();
            if (index != null && index.isFile()) {
                statusLabel.setText("Iterative run stopped; results: " + index.getParent());
                int choice = JOptionPane.showConfirmDialog(this,
                        "The iterative run stopped. Completed reports and pass outcomes are available at:\n"
                                + index.getAbsolutePath() + "\n\nOpen the results index?",
                        "Iterative results", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) openHtmlReport(index);
            }
            showDetectionProblem(outcome);
        }
    }

    /** Plate-solves the selected frame; the result belongs to that file, wherever its row is when the solve ends. */
    private void solveSelectedFrame() {
        LOGGER.info("Will try to solve image");
        FitsFileInformation selectedFile = getSelectedFileInformation();
        if (selectedFile == null) {
            return;
        }
        ProcessingSession owner = sessions.current();
        if (owner == null) return;
        startJob(JobKind.PLATE_SOLVE, owner, new PlateSolveTask(owner.getPlateSolveService(), selectedFile.getFilePath(),
                        astapSolveRadio.isSelected(), astrometryNetSolveRadio.isSelected()),
                (handle, outcome) -> showSolveOutcome(selectedFile, outcome));
    }

    private void resizeTableColumns(JTable table) {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        for (int column = 0; column < table.getColumnCount(); column++) {
            int width = 50;

            Component headerComp = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
                    table, table.getColumnModel().getColumn(column).getHeaderValue(), false, false, 0, column);
            width = Math.max(headerComp.getPreferredSize().width, width);

            for (int row = 0; row < table.getRowCount(); row++) {
                Component cellComp = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                width = Math.max(cellComp.getPreferredSize().width, width);
            }

            table.getColumnModel().getColumn(column).setPreferredWidth(width + 15);
        }

        if (table.getColumnModel().getColumnCount() > FitsFileTableModel.COL_EARTH) {
            table.getColumnModel().getColumn(FitsFileTableModel.COL_EARTH).setMinWidth(65);
            table.getColumnModel().getColumn(FitsFileTableModel.COL_EARTH).setPreferredWidth(65);
            table.getColumnModel().getColumn(FitsFileTableModel.COL_EARTH).setMaxWidth(90);
        }
    }

    public void setTableModel(AbstractTableModel tableModel) {
        table.setModel(tableModel);
        table.clearSelection();
        configureTableRenderers();
        resizeTableColumns(table);
        containsColorImages = false;
        FitsFileTableModel model = (FitsFileTableModel) tableModel;
        if (subscribedSkyCatalogue != null) subscribedSkyCatalogue.removeCorrectionListener(correctionChanged);
        subscribedSkyCatalogue = skyCatalogue();
        subscribedSkyCatalogue.addCorrectionListener(correctionChanged);
        restoreSkyCatalogue();

        if (model.getRowCount() == 0) {
            clearLoadedControls();
            return;
        }

        for (int i = 0; i < model.getRowCount(); i++) {
            FitsFileInformation fileInfo = model.getFitsFileAt(i);

            if (fileInfo != null && !fileInfo.isMonochrome()) {
                containsColorImages = true;
                break;
            }
        }

        // The commands follow: Convert to Mono for colour frames, the detection commands for monochrome ones.
        LOGGER.info(containsColorImages ? "Color images detected." : "All images are Monochrome.");
        // Plate solving updates the table; keep the solved count current.
        model.addTableModelListener(e -> refreshWorkflowStatus());
        refreshWorkflowStatus();
    }

    private void clearLoadedControls() {
        containsColorImages = false;
        refreshWorkflowStatus();
    }

    // ==========================================
    // WORKFLOW STRIP
    // ==========================================

    private JPanel workflowGroup(String title, JLabel status, JComponent... rows) {
        // Every group is as tall as the tallest one, so the tops and the status lines line up.
        JPanel group = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                Dimension own = super.getPreferredSize();
                int height = own.height;
                for (JPanel other : workflowGroups) {
                    if (other != this) {
                        height = Math.max(height, other.getLayout().preferredLayoutSize(other).height);
                    }
                }
                return new Dimension(own.width, height);
            }
        };
        workflowGroups.add(group);
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title), BorderFactory.createEmptyBorder(0, 4, 4, 4)));
        for (JComponent row : rows) {
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            group.add(row);
        }
        group.add(Box.createVerticalGlue());
        if (status != null) {
            status.setAlignmentX(Component.LEFT_ALIGNMENT);
            status.setForeground(UIManager.getColor("Label.disabledForeground"));
            status.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
            group.add(status);
        }
        return group;
    }

    private static JPanel buttonRow(JComponent... components) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (JComponent c : components) {
            row.add(c);
        }
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** Updates the status line of every group, and the commands with their disabled-button reasons. */
    @Override
    public void refreshWorkflowStatus() {
        int frames = table == null || table.getModel() == null ? 0 : table.getRowCount();
        colourNotice.setVisible(frames > 0 && containsColorImages);
        // The import button is the primary action until frames are loaded, then an ordinary button for another dataset.
        importButton.putClientProperty("FlatLaf.style", frames == 0 ? PRIMARY_BUTTON_STYLE : null);
        if (frames == 0) {
            prepareStatus.setText("No frames imported");
            astrometryStatus.setText(" ");
            astrometryStatus.setToolTipText(null);
            hasSolvedFrame = false;
            inspectStatus.setText(" ");
            setDetectStatus("Import aligned frames to start");
        } else {
            prepareStatus.setText(containsColorImages ? "Colour frames: convert first" : "✓ Monochrome, " + frames + " frames");
            int solved = 0;
            for (int row = 0; row < frames; row++) {
                if ("Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(row, FitsFileTableModel.COL_SOLVED)))) {
                    solved++;
                }
            }
            // One solved frame is enough: the frames are aligned, so the report uses its solution for all of them.
            hasSolvedFrame = solved > 0;
            if (hasSolvedFrame) {
                astrometryStatus.setText("<html>✓ " + solved + " / " + frames + " solved<br>" + skyCatalogueStatusText() + "</html>");
                astrometryStatus.setToolTipText(skyCatalogueTooltip());
            } else {
                astrometryStatus.setText("Solve one frame to identify objects");
                astrometryStatus.setToolTipText("<html>No frame is plate-solved yet. One solved frame gives the report sky coordinates:<br>"
                        + "moving objects are identified with JPL and SkyBoT, and variable-star candidates are matched against AAVSO VSX.<br>"
                        + "It also lets " + FETCH_SKY_CATALOGUE + " download the stars and deep-sky objects of the field.</html>");
            }
            int selected = table.getSelectedRowCount();
            inspectStatus.setText(selected == 0 ? "No frames selected" : selected + (selected == 1 ? " frame selected" : " frames selected"));
            if (containsColorImages) {
                setDetectStatus("Convert to monochrome first");
            } else {
                String saved = savedAutoTuneLine();
                setDetectStatus(saved == null ? "Settings: " + detectionSettingsSummary()
                        : "Settings: " + detectionSettingsSummary() + "<br>" + saved);
            }
        }
        updateAutoTuneTooltip();
        refreshAnalysisScope();
        refreshCommands();
    }

    /** Updates the configured analyses after settings apply, without committing editor text during selection changes. */
    @Override
    public void showEnabledAnalyses(String enabled) {
        enabledAnalyses = enabled;
        refreshAnalysisScope();
    }

    /** Makes whole-run versus selected-frame behavior visible before any processing starts. */
    private void refreshAnalysisScope() {
        int frames = table == null ? 0 : table.getRowCount();
        int selected = table == null ? 0 : table.getSelectedRowCount();
        analysisScope.setVisible(frames > 0);
        if (frames == 0) return;
        String scopes = "Blink: " + selected + " selected frames · Preview: sequence from "
                + (selected == 0 ? "the first frame" : "the selected frame") + " · " + AnalysisScopeText.autoTune(frames, selected);
        analysisScope.setText("<html><b>Run:</b> all " + frames + " imported frames · " + enabledAnalyses
                + "<br>" + scopes + "</html>");
        analysisScope.setToolTipText("Analyses use current settings; report-category choices only affect export. "
                + "Auto-Tune's configured candidate limit still applies. Manual Transient Inspection runs a new analysis.");
    }

    /**
     * Copies the eight Overview values during this EDT call and does not retain {@code config}.
     */
    @Override
    public void showActiveDetectionSettings(DetectionConfig config) {
        activeDetectionValue.setText(String.format(Locale.US, "%.2f / %.2f / %d",
                config.detectionSigmaMultiplier, config.growSigmaMultiplier, config.minDetectionPixels));
        activeMaskValue.setText(String.format(Locale.US, "%.2f / %.2f / %d",
                config.masterSigmaMultiplier, config.masterGrowSigmaMultiplier, config.masterMinDetectionPixels));
        activeOverlapValue.setText(String.format(Locale.US, "%.2f", config.maxMaskOverlapFraction));
        activeJitterValue.setText(String.format(Locale.US, "%.2f px", config.maxStarJitter));
    }

    /** Small rectangle of the active detection settings, with the link that opens Detection Settings. */
    private JComponent activeSettingsCard() {
        Font small = UIManager.getFont("Label.font");
        small = small == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 11) : small.deriveFont(Math.max(10f, small.getSize2D() - 2f));
        Color nameColor = UIManager.getColor("Label.disabledForeground");
        JPanel card = new JPanel(new GridBagLayout());
        Color border = UIManager.getColor("Component.borderColor");
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border != null ? border : new Color(0x666666)),
                BorderFactory.createEmptyBorder(3, 6, 2, 6)));
        card.setToolTipText("<html>The detection settings in use, the ones the calibrated Auto-Tuner sets.<br>"
                + "Detection and star mask are sigma / grow sigma / minimum pixels.</html>");
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(0, 0, 1, 0);
        addReadoutRow(card, c, 0, small, nameColor, "Detection", activeDetectionValue,
                "Detection sigma, grow sigma and minimum pixels.");
        addReadoutRow(card, c, 1, small, nameColor, "Star mask", activeMaskValue,
                "Master sigma, master grow sigma and master minimum pixels.");
        addReadoutRow(card, c, 2, small, nameColor, "Overlap", activeOverlapValue,
                "Maximum share of a detection that may overlap the star mask.");
        addReadoutRow(card, c, 3, small, nameColor, "Jitter", activeJitterValue,
                "Base star jitter radius, in pixels. Detections that move less than this are treated as star jitter.");
        JButton editSettingsLink = AccessibleControls.link("Edit settings…", "Edit detection settings", () -> {
            if (!uiLocked) {
                showDetectionSettings.run();
            }
        });
        editSettingsLink.setFont(small);
        editSettingsLink.setForeground(LINK_COLOR);
        editSettingsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        editSettingsLink.setToolTipText("Open Detection Settings to change these values.");
        c.gridy = 4;
        c.gridx = 0;
        c.gridwidth = 2;
        c.insets = new Insets(3, 0, 0, 0);
        card.add(editSettingsLink, c);
        return card;
    }

    private static void addReadoutRow(JPanel card, GridBagConstraints c, int row, Font font, Color nameColor,
                                      String name, JLabel value, String tip) {
        JLabel nameLabel = new JLabel(name);
        nameLabel.setFont(font);
        nameLabel.setForeground(nameColor);
        nameLabel.setToolTipText(tip);
        value.setFont(font);
        value.setToolTipText(tip);
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 1;
        c.insets = new Insets(0, 0, 1, 8);
        card.add(nameLabel, c);
        c.gridx = 1;
        c.insets = new Insets(0, 0, 1, 0);
        card.add(value, c);
    }

    /** The note under Auto-Tune: which settings are in use, and when a saved auto-tune was run. */
    private void setDetectStatus(String body) {
        int width = Math.max(120, autoTuneButton.getPreferredSize().width - 4);
        detectStatus.setText("<html><div style='width:" + width + "px'>" + body + "</div></html>");
    }

    /** Height of the Auto-Tune note when wrapped to the button width. */
    private int wrappedStatusHeight(int width) {
        javax.swing.text.View view = (javax.swing.text.View) detectStatus.getClientProperty(
                javax.swing.plaf.basic.BasicHTML.propertyKey);
        if (view == null) {
            return detectStatus.getPreferredSize().height;
        }
        view.setSize(width, 0);
        Insets insets = detectStatus.getInsets();
        return (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)) + insets.top + insets.bottom;
    }

    private String detectionSettingsSummary() {
        DetectionConfigurationPanel settings = detectionSettings;
        return settings == null ? "Manual settings" : settings.getSettingsSummary();
    }

    /** The saved auto-tune line for the imported dataset, or null when there is none. */
    private String savedAutoTuneLine() {
        DetectionConfigurationPanel settings = detectionSettings;
        return settings == null ? null : settings.getSavedAutoTuneLine();
    }

    /** Adds the saved-run date to the Auto-Tune button, and says that a click measures again. */
    private void updateAutoTuneTooltip() {
        int frames = table == null ? 0 : table.getRowCount();
        String saved = frames == 0 ? null : savedAutoTuneLine();
        int selected = table == null ? 0 : table.getSelectedRowCount();
        String scope = frames == 0 ? "" : "<br>" + AnalysisScopeText.autoTune(frames, selected) + ".";
        autoTuneAction.setDescription(AUTO_TUNE_TOOLTIP.replace("</html>", scope
                + (saved == null ? "" : "<br>" + saved + ". A click measures the frames again.") + "</html>"));
    }

    /** Right-click menu on the frame table for the actions on selected frames. */
    private void installTableContextMenu() {
        // The same actions as the buttons: the items are enabled with them, and the selection made by the right-click
        // below refreshes them before the menu shows.
        JPopupMenu menu = new JPopupMenu();
        menu.add(new JMenuItem(blinkAction));
        menu.add(new JMenuItem(previewAction));
        menu.add(new JMenuItem(solveAction));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger() || uiLocked) {
                    return;
                }
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) {
                    table.setRowSelectionInterval(row, row);
                }
                menu.show(table, e.getX(), e.getY());
            }
        });
    }

    /** Ctrl+D detect, Ctrl+B blink, Ctrl+P preview, Ctrl+T Auto-Tune: the same actions as the buttons. */
    private void installShortcuts() {
        bindShortcut("detect", KeyStroke.getKeyStroke("ctrl D"), detectAction);
        bindShortcut("blink", KeyStroke.getKeyStroke("ctrl B"), blinkAction);
        bindShortcut("preview", KeyStroke.getKeyStroke("ctrl P"), previewAction);
        bindShortcut("autoTune", KeyStroke.getKeyStroke("ctrl T"), autoTuneAction);
    }

    private void bindShortcut(String name, KeyStroke key, Action action) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(key, name);
        getActionMap().put(name, action);
    }

    private void configureTableRenderers() {
        if (table.getColumnModel().getColumnCount() <= FitsFileTableModel.COL_EARTH) {
            return;
        }

        table.getColumnModel().getColumn(FitsFileTableModel.COL_EARTH).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                boolean hasLink = value != null && !String.valueOf(value).isBlank();
                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setText(hasLink ? String.valueOf(value) : "");
                if (!isSelected) {
                    label.setForeground(hasLink ? LINK_COLOR : table.getForeground());
                }
                label.setToolTipText(hasLink ? "Open the FITS site coordinates in Google Earth Web" : null);
                return label;
            }
        });
    }

    private void installEarthLinkSupport() {
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int viewRow = table.rowAtPoint(e.getPoint());
                int viewColumn = table.columnAtPoint(e.getPoint());
                boolean overEarthLink = hasEarthLinkAt(viewRow, viewColumn);
                table.setCursor(Cursor.getPredefinedCursor(overEarthLink ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });

        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                table.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }

                int viewRow = table.rowAtPoint(e.getPoint());
                int viewColumn = table.columnAtPoint(e.getPoint());
                if (!hasEarthLinkAt(viewRow, viewColumn)) {
                    return;
                }

                openEarthLinkForRow(viewRow);
            }
        });
    }

    private boolean hasEarthLinkAt(int viewRow, int viewColumn) {
        if (viewRow < 0 || viewColumn < 0 || table.getModel() == null) {
            return false;
        }
        int modelColumn = table.convertColumnIndexToModel(viewColumn);
        if (modelColumn != FitsFileTableModel.COL_EARTH) {
            return false;
        }
        FitsFileTableModel model = (FitsFileTableModel) table.getModel();
        int modelRow = table.convertRowIndexToModel(viewRow);
        FitsFileInformation fileInfo = model.getFitsFileAt(modelRow);
        return fileInfo != null && fileInfo.hasGoogleEarthLocation();
    }

    private void openEarthLinkForRow(int viewRow) {
        if (viewRow < 0 || table.getModel() == null) {
            return;
        }

        FitsFileTableModel model = (FitsFileTableModel) table.getModel();
        FitsFileInformation fileInfo = model.getFitsFileAt(table.convertRowIndexToModel(viewRow));
        if (fileInfo == null) {
            return;
        }

        String googleEarthUrl = fileInfo.getGoogleEarthUrl();
        if (googleEarthUrl == null || googleEarthUrl.isBlank()) {
            return;
        }

        openExternalUrl(googleEarthUrl, "Google Earth");
    }

    private void openExternalUrl(String url, String label) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not open " + label + ": " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(this, label + " links are not supported on your system.", "Info", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ==========================================
    // SKY CATALOGUE
    // ==========================================

    /** Delegates catalogue fetch/cancel to the current session's workflow; EDT only. */
    private void onSkyCatalogueButton() {
        if (skyController != null) {
            skyController.fetchOrCancel(applicationSettings.getConfig().skyCatalogueStarMagnitude());
        }
    }

    /** The current session's model, or the empty model before an import. */
    private SkyCatalogueModel skyCatalogue() {
        ProcessingSession session = sessions.current();
        return session == null ? emptySkyCatalogue : session.getSkyCatalogue();
    }

    /** Replaces the session workflow and renders its progress without owning background execution. */
    private void restoreSkyCatalogue() {
        if (skyController != null) skyController.close();
        skyController = null;
        ProcessingSession session = sessions.current();
        if (session == null) return;
        skyController = new SkyCatalogueController(session, () -> sessions.isCurrent(session),
                new SkyCatalogueController.Listener() {
                    /** Updates only Swing presentation from the controller's EDT-owned state. */
                    @Override public void changed() {
                        skyFetchIndicator.setVisible(skyController != null && skyController.isRunning());
                        if (skyController != null) skyFetchLabel.setText(skyController.progressText());
                        refreshWorkflowStatus();
                    }

                    /** Shares catalogue workflow outcomes with the main status line. */
                    @Override public void message(String text) {
                        statusLabel.setText(text);
                    }
                });
        skyController.restore();
    }

    /** Current catalogue problem for status rendering, or none when no session is open. */
    private String skyCatalogueProblem() {
        return skyController == null ? null : skyController.catalogueProblem();
    }

    /** Current correction problem for status rendering, or none when no session is open. */
    private String correctionProblem() {
        return skyController == null ? null : skyController.correctionProblem();
    }

    /** The second line of the Astrometry status, and a third for the plate-solution correction. */
    private String skyCatalogueStatusText() {
        String catalogueText = catalogueStatusText();
        PlateSolutionCorrection correction = skyCatalogue().getCorrection();
        if (skyCatalogue().getSaveFailure() != null) {
            return catalogueText + "<br>⚠ Correction could not be saved; the last saved setting is in use";
        }
        if (correction != null) {
            return catalogueText + "<br>" + (!correction.isNeeded()
                    ? "✓ Plate solution checked · " + String.format(Locale.US, "%.2f px", correction.beforeMedianPx)
                    : correction.enabled
                    ? "✓ Plate solution corrected · " + correction.improvementText()
                    : "Correction available · " + correction.improvementText());
        }
        if (correctionProblem() != null) {
            return catalogueText + "<br>⚠ Plate solution not checked";
        }
        return catalogueText;
    }

    private String catalogueStatusText() {
        if (skyController != null && skyController.isRunning()) {
            return skyController.stage().startsWith("Checking") ? "Checking the plate solution…" : "Fetching sky catalogue…";
        }
        SkyCatalogue catalogue = skyCatalogue().get();
        if (catalogue == null) {
            return skyCatalogueProblem() != null ? "⚠ Sky catalogue not fetched" : "No sky catalogue yet";
        }
        return (catalogue.problems.isEmpty() && skyCatalogueProblem() == null ? "✓ Sky catalogue · " : "⚠ Sky catalogue · ")
                + String.format(Locale.US, "%,d stars", catalogue.stars.size());
    }

    private String skyCatalogueTooltip() {
        StringBuilder text = new StringBuilder("<html>The report has sky coordinates: moving objects are identified with JPL and SkyBoT,<br>"
                + "and variable-star candidates are matched against AAVSO VSX.");
        SkyCatalogue catalogue = skyCatalogue().get();
        if (catalogue != null) {
            text.append("<br><br>Sky catalogue for the Annotate switch: ").append(catalogue.summary())
                    .append(String.format(Locale.US, "<br>Stars to Gaia G %.1f", catalogue.starMagnitudeLimit))
                    .append(catalogue.starSource == null ? "" : " from " + catalogue.starSource)
                    .append(catalogue.fetchedAtUtc == null ? "" : ", fetched " + fetchedText(catalogue.fetchedAtUtc));
            double depth = sessions.current() == null ? catalogue.starMagnitudeLimit
                    : applicationSettings.getConfig().skyCatalogueStarMagnitude();
            if (Math.abs(depth - catalogue.starMagnitudeLimit) > 0.01) {
                text.append(String.format(Locale.US, "<br><i>The star depth is now set to %.1f: fetch again to use it.</i>", depth));
            }
            if (!catalogue.hasStarIdentifiers()) {
                text.append("<br><b>This saved catalogue has no Gaia identifiers.</b> Fetch it again to name unmatched variable candidates.");
            }
        } else {
            text.append("<br><br>").append(FETCH_SKY_CATALOGUE).append(" downloads the stars, deep-sky objects and variables of the field<br>"
                    + "for the Annotate switch (A) of the viewers.");
        }
        if (skyCatalogueProblem() != null) {
            text.append("<br><br><b>").append(catalogue == null ? "Not fetched:" : "Incomplete:").append("</b> ")
                    .append(escapeHtml(skyCatalogueProblem()).replace("\n", "<br>"));
        }
        if (skyCatalogue().getSaveFailure() != null) {
            text.append("<br><br><b>Correction save failed:</b> ")
                    .append(escapeHtml(String.valueOf(skyCatalogue().getSaveFailure().getMessage())))
                    .append(". The last saved setting is in use.");
        }
        PlateSolutionCorrection correction = skyCatalogue().getCorrection();
        if (correction != null && !correction.isNeeded()) {
            text.append(String.format(Locale.US, "<br><br>Plate solution checked against the Gaia stars: %,d stars per frame in %d frames.<br>"
                            + "Distance from the catalogue stars to the stars found in the frames: %.2f px (90 %%: %.2f px).<br>"
                            + "The FITS header solution already matches the stars: a correction would not bring them closer, so none is offered.",
                    correction.starsPerFrame, correction.framesFitted, correction.beforeMedianPx, correction.beforeP90Px));
        } else if (correction != null) {
            text.append(String.format(Locale.US, "<br><br>Plate solution checked against the Gaia stars: %,d stars per frame in %d frames.<br>"
                            + "Distance from the catalogue stars to the stars found in the frames: %.2f px (90 %%: %.2f px)<br>"
                            + "as in the FITS header, %.2f px (90 %%: %.2f px) corrected.",
                    correction.starsPerFrame, correction.framesFitted, correction.beforeMedianPx, correction.beforeP90Px,
                    correction.afterMedianPx, correction.afterP90Px));
            text.append(correction.enabled
                    ? "<br>The correction is <b>in use</b>: in the viewers, the cursor RA/Dec and the report of the next Detect Moving Targets."
                    : "<br>The correction is <b>not in use</b>. Switch it on in a viewer (Correct plate solution, C).");
            text.append("<br>New fits are applied automatically when median accuracy improves by more than 1 px"
                    + "<br>and the 90th-percentile error also improves. A manual on/off choice is kept when refitting.");
            text.append("<br>The FITS files are not changed.");
        } else if (correctionProblem() != null) {
            text.append("<br><br><b>Plate solution not checked:</b> ").append(escapeHtml(correctionProblem()));
        }
        return text.append("</html>").toString();
    }

    /** "2026-10-10 14:03 UTC" from an ISO instant. */
    private static String fetchedText(String isoInstant) {
        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.parse(isoInstant));
        } catch (RuntimeException e) {
            return isoInstant;
        }
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ==========================================
    // DATA ACCESS HELPERS (For Auto-Tuner & External Panels)
    // ==========================================

    public FitsFileInformation[] getImportedFiles() {
        ProcessingSession session = sessions.current();
        if (session == null) return null;
        FitsFileInformation[] files = session.getFiles();
        return files.length == 0 ? null : files;
    }

    public FitsFileInformation[] getSelectedFilesInformation() {
        int[] selected_rows = table.getSelectedRows();
        if (selected_rows != null && selected_rows.length > 0) {
            FitsFileInformation[] ret = new FitsFileInformation[selected_rows.length];
            FitsFileTableModel model = (FitsFileTableModel) table.getModel();

            for (int i = 0; i < selected_rows.length; i++) {
                ret[i] = model.getFitsFileAt(selected_rows[i]);
            }
            return ret;
        }
        return null;
    }

    public FitsFileInformation getSelectedFileInformation() {
        int row = table.getSelectedRow();
        if (row < 0) return null;

        FitsFileTableModel model = (FitsFileTableModel) table.getModel();
        return model.getFitsFileAt(row);
    }

    /** Moves the frame selection by {@code delta} rows (one frame selected), staying within the table. */
    public void selectFrameOffset(int delta) {
        int rows = table.getRowCount();
        if (rows == 0) {
            return;
        }
        int row = Math.max(0, Math.min(rows - 1, Math.max(0, table.getSelectedRow()) + delta));
        table.setRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 0, true));
    }

    /** The selected row (or -1) and the number of rows. */
    public int[] frameSelection() {
        return new int[]{table == null ? -1 : table.getSelectedRow(), table == null ? 0 : table.getRowCount()};
    }

    /** Accepts a folder (or a file in it) dropped on the frame table or the empty area, and imports it. */
    void installFolderDrop(TransferHandler handler) {
        table.setTransferHandler(handler);
        Container parent = table.getParent();
        while (parent != null && !(parent instanceof JScrollPane)) {
            parent = parent.getParent();
        }
        if (parent != null) {
            ((JScrollPane) parent).setTransferHandler(handler);
        }
        setTransferHandler(handler);
    }

    /** What the Auto-Tune command runs: show the Overview and start Auto-Tune there with its tuner and profile. */
    @Override
    public void setAutoTuneCommand(Runnable openOverviewAndRunAutoTune) {
        this.openOverviewAndRunAutoTune = openOverviewAndRunAutoTune;
    }

    public void selectFirstFileIfNoneSelected() {
        if (table.getRowCount() > 0 && table.getSelectedRow() == -1) {
            table.setRowSelectionInterval(0, 0);
        }
    }

    public void setProgressBarWorking() {
        progressBar.setIndeterminate(true);
    }

    public void setProgressBarIdle() {
        progressBar.setIndeterminate(false);
    }

    /**
     * Ends the lock: the other controls are enabled again, the commands follow the facts, and the session tabs open
     * when there is a session. Nothing is restored from before the lock, so a second lock cannot leave stale states.
     */
    private void unlockUI() {
        uiLocked = false;
        setControlsEnabled(this, true);
        refreshWorkflowStatus();
        if (detectionSequenceFrame != null) {
            detectionSequenceFrame.setSettingsEditable(true);
        }
        sessionTabs.accept(sessions.current() != null);
    }

    /** While a job runs (or a blink plays), the window's controls and the session tabs are locked. */
    private void lockUI() {
        uiLocked = true;
        setControlsEnabled(this, false);
        refreshCommands();
        // The settings do not change while a job runs: the Detection Settings tab is locked, and so is Preview Frame.
        if (detectionSequenceFrame != null) {
            detectionSequenceFrame.setSettingsEditable(false);
        }
        sessionTabs.accept(false);
    }

    /** Enables or disables every control of the panel for the lock; the commands follow their actions instead. */
    private static void setControlsEnabled(Container container, boolean enabled) {
        for (Component c : container.getComponents()) {
            if (c instanceof AbstractButton button && button.getAction() instanceof CommandAction) {
                continue;
            }
            c.setEnabled(enabled);
            if (c instanceof Container inner) {
                setControlsEnabled(inner, enabled);
            }
        }
    }

    /** Whether a task holds the window, so another must wait. */
    boolean isUiLocked() {
        return uiLocked;
    }

    /**
     * Starts a job of {@code owner}, the session it was built from, under the coordinator; nothing starts when that
     * session is no longer the current one. When it cannot start now, the status bar says why.
     */
    <R> void startJob(JobKind kind, ProcessingSession owner, Job<R> job, JobCoordinator.OutcomeHandler<R> handler) {
        if (owner == null || !sessions.isCurrent(owner)) return;
        JobCoordinator.Admission admission = jobs.start(kind, owner, job,
                sessions.forCurrent(handler));
        if (!admission.admitted()) {
            statusLabel.setText(admission.refusal());
        }
    }



    /**
     * Gives a finished detection's overlays to its session, when that session is still the current one, and shows
     * them in an open Preview Frame. Null overlays (the detection did not finish) leave the last run's in place.
     */
    public void showDetectionOverlays(ProcessingSession owner, DetectionOverlays overlays) {
        if (overlays == null || !sessions.isCurrent(owner)) {
            return;
        }
        owner.setLastRun(overlays);
        if (detectionSequenceFrame != null) {
            detectionSequenceFrame.showLastRun();
        }
    }

    /** The end of Detect Moving Targets and Detect Iteratively: offers the report, or says what went wrong. */
    private void showDetectionOutcome(JobHandle handle, JobOutcome<File> outcome) {
        if (outcome instanceof JobOutcome.Completed<File> completed) {
            if (sessions.isCurrent(handle.session())) {
                showLastReport(completed.result());
            }
            promptUserToOpenReport(completed.result());
        } else {
            showDetectionProblem(outcome);
        }
    }

    /** Shows a line in the status bar, for an outcome handled outside this panel. */
    @Override
    public void showStatus(String text) {
        statusLabel.setText(text);
    }

    /** Tells the user that a detection job was declined or failed, as the detection jobs always have. */
    @Override
    public void showDetectionProblem(JobOutcome<?> outcome) {
        if (outcome instanceof JobOutcome.Failed<?> failed) {
            FailureDialog.show(this, "Error", "Detection failed: " + failed.reason(), failed.cause());
        } else if (outcome instanceof JobOutcome.Declined<?> declined) {
            JOptionPane.showMessageDialog(this, "Detection failed: " + declined.reason(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disableControlsProcessing() { lockUI(); }
    private void enableControlsProcessingFinished() { unlockUI(); }
    private void disableControlsBlinking() { lockUI(); }
    private void enableControlsProcessingBlinkingFinished() { unlockUI(); }

    /**
     * Opens Preview Frame on the selected frame. It detects each frame with the settings in use at that moment, and
     * changes its per-frame thresholds itself. The Detection Settings button uses this same window.
     */
    void previewSelectedFrame() {
        FitsFileTableModel model = (FitsFileTableModel) table.getModel();
        if (model == null || model.getRowCount() == 0) {
            return;
        }

        FitsFileInformation[] allFiles = new FitsFileInformation[model.getRowCount()];
        for (int i = 0; i < model.getRowCount(); i++) {
            allFiles[i] = model.getFitsFileAt(i);
        }

        int startIndex = table.getSelectedRow();
        if (startIndex < 0) {
            startIndex = 0;
        }

        // Invalid text in a settings field is shown there first, as for a run.
        if (detectionSettings.checkedSettings("Preview Frame did not open.") == null) {
            return;
        }
        if (detectionSequenceFrame == null) {
            detectionSequenceFrame = new DetectionSequenceFrame(windowFrame, sessions, detectionSettings);
            detectionSequenceFrame.setSettingsEditable(!uiLocked);
        }

        detectionSequenceFrame.openSequence(allFiles, startIndex, stretch);
    }

    // ==========================================
    // JOB AND SESSION OUTCOMES
    // ==========================================

    /** A new session, or none: the status line says how many frames it has. */
    void sessionChanged(FitsFileInformation[] files) {
        if (detectionSequenceFrame != null) {
            detectionSequenceFrame.closeForSessionChange();
            detectionSequenceFrame = null;
        }
        blink.close();
        statusLabel.setText(files.length == 0 ? "No files loaded" : "Imported " + files.length + " file(s)");
        // The import names the new session's last report, if it has one.
        showLastReport(sessions.current() == null ? null : sessions.current().getLastReport());
    }

    void closeSessionViews() {
        if (skyController != null) skyController.close();
        skyController = null;
        if (subscribedSkyCatalogue != null) subscribedSkyCatalogue.removeCorrectionListener(correctionChanged);
        subscribedSkyCatalogue = null;
        if (detectionSequenceFrame != null) {
            detectionSequenceFrame.closeForSessionChange();
            detectionSequenceFrame = null;
        }
        blink.close();
    }

    /**
     * Offers the newest report of the session under the Detect buttons, so it can be opened again after the question
     * at the end of a run, or after a restart. Null hides the link.
     */
    void showLastReport(File report) {
        if (sessions.current() != null) sessions.current().setLastReport(report);
        if (report == null) {
            lastReportButton.setVisible(false);
            outputFolderButton.setVisible(false);
            return;
        }
        java.time.LocalDateTime written = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(report.lastModified()), java.time.ZoneId.systemDefault());
        String pattern = written.getYear() == java.time.LocalDate.now().getYear() ? "d MMM HH:mm" : "d MMM yyyy";
        lastReportButton.setText("<html><u>Open last report</u> · " + written.format(java.time.format.DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH))
                + (SessionReports.isIterative(report) ? " (iterative)" : "") + "</html>");
        lastReportButton.setToolTipText(report.getAbsolutePath());
        lastReportButton.setVisible(true);
        outputFolderButton.setToolTipText(report.getAbsoluteFile().getParent());
        outputFolderButton.setVisible(true);
    }

    /** Opens the current session's completed output directory without starting or changing any analysis. */
    private void openOutputFolder() {
        File report = sessions.current() == null ? null : sessions.current().getLastReport();
        File directory = SessionReports.outputDirectory(report);
        if (directory == null) {
            statusLabel.setText("The last report or its output folder was moved or deleted.");
            showLastReport(null);
            return;
        }
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                statusLabel.setText("Output folder: " + directory.getAbsolutePath());
                return;
            }
            Desktop.getDesktop().open(directory);
        } catch (IOException | RuntimeException failure) {
            statusLabel.setText("Cannot open output folder: " + directory.getAbsolutePath() + " · " + failure.getMessage());
        }
    }

    private void openLastReport() {
        File lastReport = sessions.current() == null ? null : sessions.current().getLastReport();
        if (lastReport == null) {
            return;
        }
        if (!lastReport.isFile()) {
            statusLabel.setText("The last report was moved or deleted.");
            showLastReport(null);
            return;
        }
        openHtmlReport(lastReport);
    }

    /**
     * The blink keeps its frames in memory; when they would not fit, says so and lets the user choose. Returns whether
     * to go ahead.
     */
    private boolean confirmBlinkMemory(FitsFileInformation[] files) {
        long needed = BlinkSequence.estimateBytes(files);
        Runtime runtime = Runtime.getRuntime();
        long available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
        if (needed <= available * 0.9) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(this,
                String.format(java.util.Locale.US, "<html>Blinking %d frames needs about %.1f GB of memory, but only about %.1f GB is free.<br>"
                                + "Select fewer frames, or start SpacePixels with more memory.<br><br>Try anyway?</html>",
                        files.length, needed / 1e9, available / 1e9),
                "Not Enough Memory to Blink", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        return choice == JOptionPane.YES_OPTION;
    }

    /**
     * The end of loading a blink: plays the frames, or says why it did not. While the blink plays, its window holds
     * the main window's lock, as the loading job did; closing it ends the blink.
     */
    private void blinkLoaded(FitsFileInformation[] files, JobOutcome<BlinkSequence> outcome) {
        BlinkFrame blinkFrame = blink;
        if (outcome instanceof JobOutcome.Completed<BlinkSequence> completed && !blinkStop.get()) {
            // Playing first, so the lock leaves Blink available as Stop Blinking.
            blinkPlaying = true;
            disableControlsBlinking();
            statusLabel.setText("Blinking " + completed.result().getFrames().size()
                    + " frames. Close the blink window or click Stop Blinking to end.");
            blinkFrame.setSkyCatalogueModel(skyCatalogue());
            blinkFrame.open(completed.result(), files, getImportedFiles(), stretch,
                    sessions.current() == null ? null : sessions.current().getTrackOverlay(),
                    sessions.current() == null ? null : sessions.current().getVariableStarOverlay());
            return;
        }
        // Stopped, or failed: hides the window if it still shows the loading progress.
        blinkFrame.close();
        refreshCommands();
        statusLabel.setText("Blinking stopped.");
        if (outcome instanceof JobOutcome.Failed<BlinkSequence> failed) {
            FailureDialog.show(this, "Error", "Blink process failed: " + failed.reason(), failed.cause());
        }
    }

    /** The blink window closed: ends the blink, or stops the loading, whose job then ends at the next frame. */
    private void blinkWindowClosed() {
        blinkStop.set(true);
        if (blinkPlaying) {
            blinkPlaying = false;
            enableControlsProcessingBlinkingFinished();
            statusLabel.setText("Blinking stopped.");
        }
    }

    /**
     * The end of Plate Solve: offers to write the solution into the frame's FITS header, or says why the frame was
     * not solved. Failed or cancelled solves have already cleaned up their own temporary files.
     */
    private void showSolveOutcome(FitsFileInformation fileInfo, JobOutcome<PlateSolveResult> outcome) {
        if (outcome instanceof JobOutcome.Cancelled<?>) {
            statusLabel.setText("Plate solve cancelled; local solver work has stopped");
            refreshCommands();
            return;
        }
        PlateSolveResult result = outcome instanceof JobOutcome.Completed<PlateSolveResult> completed ? completed.result() : null;
        if (result != null && result.isSuccess()) {
            LOGGER.info(result.toString());
            statusLabel.setText("Image was successfully plate-solved");

            int choice = JOptionPane.showConfirmDialog(this,
                    "Image was successfully plate-solved.\n\nWould you like to write the WCS coordinate solution directly into the FITS header?\n(This permanently modifies the file)",
                    "Update FITS Header",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                // Writing rewrites the whole frame, which takes a while for a large one: it is a job, off this thread.
                ProcessingSession owner = sessions.current();
                JobCoordinator.Admission admission = jobs.start(JobKind.WRITE_WCS, owner,
                        new WriteWcsTask(owner.getPlateSolveService(), fileInfo.getFilePath(), result),
                        sessions.forCurrent((handle, written) -> showWcsWriteOutcome(fileInfo, written)));
                if (admission.admitted()) {
                    return;
                }
                statusLabel.setText(admission.refusal());
            } else {
                statusLabel.setText("Plate solve completed; WCS was not written to the FITS header");
            }
            sessions.current().getPlateSolveService().cleanupSolveArtifacts(fileInfo.getFilePath(), result);
        } else {
            String errorMsg = result != null ? result.getFailureReason() + " " + result.getWarning()
                    : outcome instanceof JobOutcome.Failed<PlateSolveResult> failed ? failed.reason() : "Internal Error";
            statusLabel.setText("Plate solve failed");
            FailureDialog.show(this, "Error", "Image was not plate-solved successfully: " + errorMsg,
                    outcome instanceof JobOutcome.Failed<PlateSolveResult> failed ? failed.cause() : null);
        }
        refreshCommands();
    }

    /** The end of writing the WCS solution: the frame's row shows its new header, or the error says why it is unchanged. */
    private void showWcsWriteOutcome(FitsFileInformation fileInfo, JobOutcome<Map<String, String>> outcome) {
        if (outcome instanceof JobOutcome.Completed<Map<String, String>> completed) {
            fileInfo.getFitsHeader().clear();
            fileInfo.getFitsHeader().putAll(completed.result());
            FitsFileTableModel model = (FitsFileTableModel) table.getModel();
            model.refreshRow(model.rowOf(fileInfo));
            statusLabel.setText("FITS header updated with WCS solution");
            JOptionPane.showMessageDialog(this, "FITS header successfully updated.", "Success", JOptionPane.INFORMATION_MESSAGE);
        } else if (outcome instanceof JobOutcome.Failed<Map<String, String>> failed) {
            statusLabel.setText("Failed to update FITS header");
            FailureDialog.show(this, "Error", "Failed to update FITS header: " + failed.reason(), failed.cause());
        }
        refreshCommands();
    }

    /** The end of Convert to Mono: offers to import the new mono folder, or says what went wrong. */
    private void showConversionOutcome(JobHandle handle, JobOutcome<File> outcome) {
        if (outcome instanceof JobOutcome.Completed<File> completed) {
            File generatedMonoDirectory = completed.result();
            if (generatedMonoDirectory != null) {
                int choice = JOptionPane.showConfirmDialog(
                        this,
                        "Batch conversion to mono completed successfully.\n\n" +
                                "Import the newly created mono directory now?\n" +
                                generatedMonoDirectory.getAbsolutePath(),
                        "Conversion Complete",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);
                if (choice == JOptionPane.YES_OPTION) {
                    statusLabel.setText("Importing converted mono directory");
                    folderImport.importFolder(generatedMonoDirectory);
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Batch conversion to mono completed successfully.",
                        "Conversion Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        } else if (outcome instanceof JobOutcome.Failed<File> failed) {
            FailureDialog.show(this, "Error", "Cannot convert images: " + failed.reason(), failed.cause());
        }
    }

    /** The end of Batch Stretch: says where the stretched copies are, or what went wrong. */
    private void showStretchOutcome(JobHandle handle, JobOutcome<Void> outcome) {
        if (outcome instanceof JobOutcome.Completed<Void>) {
            JOptionPane.showMessageDialog(this,
                    "Batch stretch completed successfully.\n" +
                            "If you wish to work with these new images, please import the newly created directory.",
                    "Stretch Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        } else if (outcome instanceof JobOutcome.Failed<Void> failed) {
            FailureDialog.show(this, "Error", "Cannot stretch images: " + failed.reason(), failed.cause());
        }
    }

    /**
     * What the status bar and the progress dialog say while a job of this kind runs, and after it; an indeterminate
     * job reports no percentage, and a job without {@code initializing} text shows no progress dialog.
     */
    private record JobTexts(String started, String initializing, boolean indeterminate, String ended) {
        static JobTexts of(JobKind kind) {
            return switch (kind) {
                case BLINK -> new JobTexts("Loading frames for blinking...", null, false, "Blink frames loaded");
                case IMPORT -> new JobTexts("Importing FITS/XISF sequence...", "Loading FITS/XISF sequence in the background...", true, "Import finished");
                case WRITE_WCS -> new JobTexts("Writing the WCS solution into the FITS header...", "Writing the WCS solution into the FITS header...", true, "FITS header write finished");
                case CONVERT_TO_MONO -> new JobTexts("Batch conversion started", "Initializing batch conversion...", false, "Batch conversion finished");
                case BATCH_STRETCH -> new JobTexts("Batch stretch started", "Initializing batch stretch...", false, "Batch stretch finished");
                case PLATE_SOLVE -> new JobTexts("Plate solving image...", "Plate solving selected image...", true, "Plate solve finished");
                case AUTO_TUNE -> new JobTexts("Auto-Tuning mathematical sequence...", "Initializing Auto-Tuner...", false, "Auto-Tune finished");
                default -> new JobTexts("Object detection started...", "Initializing object detection...", false, "Object detection completed");
            };
        }
    }

    private void promptUserToOpenReport(File reportFile) {
        if (reportFile != null && reportFile.exists()) {

            String message = reportFile.isDirectory()
                    ? "Iterative pipeline completed.\n\nWould you like to open the results folder to view the separate reports?"
                    : "Detection pipeline completed successfully.\n\nWould you like to open the generated HTML report?";

            int response = JOptionPane.showConfirmDialog(
                    null,
                    message,
                    "Pipeline Complete",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE
            );

            if (response == JOptionPane.YES_OPTION) {
                openHtmlReport(reportFile);
            }
        }
    }

    private void openHtmlReport(File reportFile) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(reportFile.toURI());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null, "Could not open the report: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        } else {
            JOptionPane.showMessageDialog(null, "Opening files is not supported on your system. Report saved at: " + reportFile.getAbsolutePath(), "Info", JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
