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

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.StretchAlgorithm;
import eu.startales.spacepixels.util.PlateSolutionCorrection;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import eu.startales.spacepixels.util.skycatalog.PlateCorrectionFitter;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueClient;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jtransient.config.DetectionConfig;
import eu.startales.spacepixels.events.*;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.eventbus.Subscribe;

public class MainApplicationPanel extends JPanel {

    /** Look of the primary action (Detect Moving Targets): accent colours of the look and feel, larger and bold. */
    static final String PRIMARY_BUTTON_STYLE = "font: +2 bold; background: $Button.default.background; foreground: $Button.default.foreground;"
            + " borderColor: $Button.default.borderColor; margin: 6,16,6,16";
    private static final Color LINK_COLOR = new Color(120, 180, 255);

    //link to main window
    private final ApplicationWindow mainAppWindow;

    // --- NEW: PROGRESS DIALOG ---
    private final ProcessingProgressDialog progressDialog;

    //the table
    private volatile JTable table;

    private final JProgressBar progressBar = new JProgressBar();
    private final JButton importButton = new JButton("Import Aligned Frames…");
    private final JButton convertMonoButton = new JButton("Convert to Mono");
    private final JButton stretchButton = new JButton("Batch Stretch");
    private final JButton blinkButton = new JButton("Blink Selected");
    private final JButton solveButton = new JButton("Plate Solve Selected");
    private final JButton skyCatalogueButton = new JButton(FETCH_SKY_CATALOGUE);
    private final JButton detectSingleButton = new JButton("Preview Frame");
    private final JButton manualTransientInspectionButton = new JButton("Manual Transient Inspection");
    private final JButton detectBatchButton = new JButton("Detect Moving Targets");
    private final JButton detectIterativelyButton = new JButton("Detect Iteratively (large datasets)");
    private static final String AUTO_TUNE_TOOLTIP = "<html>Open the Detection Settings and run Auto-Tune on your frames, with the tuner and profile chosen there "
            + "(Ctrl+T).<br>It measures the noise and the faintest stars it can find, and sets the detection settings to match.</html>";

    private final JButton autoTuneButton = new JButton("Auto-Tune Settings", new AutoTuneIcon(20));
    /** The Run Auto-Tune button of the Detection Settings Overview, whose enabling the Auto-Tune button follows. */
    private JButton overviewRunAutoTuneButton;
    private final JRadioButton astapSolveRadio = new JRadioButton("ASTAP");
    private final JRadioButton astrometryNetSolveRadio = new JRadioButton("Astrometry.net (online)");

    // Workflow-strip status lines, built only from what is already known (headers, selection, solved flags)
    private final JLabel prepareStatus = new JLabel();
    private final JLabel astrometryStatus = new JLabel();
    private final JLabel inspectStatus = new JLabel();
    private final JLabel detectStatus = new JLabel();
    /** Readout of the settings the calibrated auto-tuner sets, shown beside Detect Moving Targets. */
    private final JLabel activeDetectionValue = new JLabel("—");
    private final JLabel activeMaskValue = new JLabel("—");
    private final JLabel activeOverlapValue = new JLabel("—");
    private final JLabel activeJitterValue = new JLabel("—");
    // Tooltip of each action button while it is enabled; a disabled button adds the reason
    private final Map<JButton, String> actionTooltips = new HashMap<>();
    private final List<JPanel> workflowGroups = new ArrayList<>();
    private volatile boolean uiLocked;
    private boolean hasSolvedFrame;

    private final JLabel statusLabel = new JLabel(" Ready");
    // Map to hold the state of UI components
    private final Map<Component, Boolean> savedComponentStates = new HashMap<>();

    private volatile boolean containsColorImages = true;

    // --- CHANGED to AtomicBoolean for thread safety ---
    private final AtomicBoolean isBlinking = new AtomicBoolean(false);
    /** The thread reading the frames of the current or last blink. */
    private Thread blinkLoader;

    // Sky catalogue: fetched in the background, shown at the right of the status bar while it loads.
    private static final String FETCH_SKY_CATALOGUE = "Fetch Sky Catalogue";
    private final JLabel skyFetchLabel = new JLabel();
    private final JProgressBar skyFetchBar = new JProgressBar();
    private final JPanel skyFetchIndicator = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final javax.swing.Timer skyFetchTimer = new javax.swing.Timer(1000, e -> updateSkyFetchText());
    /** The fetch running, or null. */
    private Thread skyFetchThread;
    private String skyFetchStage = "";
    private long skyFetchStageStart;
    /** Why the last fetch failed or is incomplete, or null. */
    private String skyFetchProblem;
    /** Why the plate solution could not be checked, or null. */
    private String correctionProblem;

    private DetectionSequenceFrame detectionSequenceFrame;

    public MainApplicationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        this.mainAppWindow.getEventBus().register(this);

        // --- NEW: PROGRESS DIALOG INITIALIZATION ---
        // Pass the main app frame so the dialog knows who to lock and where to center itself
        this.progressDialog = new ProcessingProgressDialog((JFrame) mainAppWindow.getFrame());
        mainAppWindow.getJobCoordinator().setListener(new JobCoordinator.Listener() {
            @Override
            public void jobStarted(JobHandle handle) {
                JobTexts texts = JobTexts.of(handle.kind());
                setProgressBarWorking();
                disableControlsProcessing();
                statusLabel.setText(texts.started());
                if (texts.indeterminate()) {
                    progressDialog.showIndeterminateProgress(texts.initializing());
                } else {
                    progressDialog.updateProgress(0, texts.initializing());
                }
                // Shown after this call returns: the dialog is modal and would hold the click until the job ends.
                EventQueue.invokeLater(() -> {
                    if (mainAppWindow.getJobCoordinator().current() == handle) {
                        progressDialog.setVisible(true);
                    }
                });
            }

            @Override
            public void jobProgress(JobHandle handle, int percent, String message) {
                if (progressDialog.isVisible()) {
                    progressDialog.updateProgress(percent, message);
                }
            }

            @Override
            public void jobEnded(JobHandle handle) {
                progressDialog.setVisible(false);
                setProgressBarIdle();
                enableControlsProcessingFinished();
                statusLabel.setText(JobTexts.of(handle.kind()).ended());
            }
        });

        setLayout(new BorderLayout(0, 0));

        // ==========================================
        // TOP CONTROL AREA
        // ==========================================
        // A workflow strip: four labelled groups in the order of use, wrapping onto a second line when narrow.
        setActionTooltip(importButton, "Choose a folder of aligned FITS or XISF frames to import (or drop the folder onto the window). "
                + "Compressed, 32-bit and XISF data are converted to a working folder first.");
        importButton.addActionListener(e -> mainAppWindow.chooseAndImportFolder());
        setActionTooltip(convertMonoButton, "Extract luminance and convert all loaded FITS files to 16-bit monochrome. Applies stretch if enabled.");
        setActionTooltip(stretchButton, "Apply the current non-linear stretch settings to all imported FITS files and save as new files.");
        setActionTooltip(solveButton, "Calculate the celestial coordinates (WCS) for the selected frame.");
        setActionTooltip(skyCatalogueButton, "<html>Download the stars (Gaia), galaxies, nebulae and clusters (SIMBAD) and variable stars "
                + "(AAVSO VSX)<br>in the field of the plate-solved frames, for the Annotate switch (A) of the viewers. "
                + "Runs in the background;<br>the star depth is set in the Astrometry Config tab.</html>");
        setActionTooltip(blinkButton, "Animate the selected frames in a new window for manual visual inspection (Ctrl+B).");
        setActionTooltip(detectSingleButton, "Run the extraction engine on the selected frame to preview detected sources and streaks with the current settings (Ctrl+P).");
        setActionTooltip(manualTransientInspectionButton, "Extract purified transients from all frames against the master background and navigate through them using arrow keys.");
        setActionTooltip(detectBatchButton, "Run the full pipeline: detect, link and report moving objects across the whole sequence (Ctrl+D).");
        setActionTooltip(detectIterativelyButton, "For datasets too large for the standard run: multiple temporally spaced passes across the sequence.");
        setActionTooltip(autoTuneButton, AUTO_TUNE_TOOLTIP);
        for (JButton button : actionTooltips.keySet()) {
            button.setEnabled(false);
        }
        // Importing is the one action available before any frames are loaded.
        importButton.setEnabled(true);

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
        add(workflowStrip, BorderLayout.NORTH);
        refreshWorkflowStatus();

        // ==========================================
        // ACTION LISTENERS
        // ==========================================

        convertMonoButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            boolean stretchEnabled = mainAppWindow.getStretchPanel().isStretchEnabled();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            ImageProcessing session = mainAppWindow.getImageProcessing();
            startJob(JobKind.CONVERT_TO_MONO, session,
                    new BatchConvertMonoTask(session, stretchEnabled, stretchFactor, iterations, algo), this::showConversionOutcome);
        });

        stretchButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            ImageProcessing session = mainAppWindow.getImageProcessing();
            startJob(JobKind.BATCH_STRETCH, session, new BatchStretchTask(session, stretchFactor, iterations, algo),
                    this::showStretchOutcome);
        });

        blinkButton.addActionListener(e -> {
            if (isBlinking.get()) {
                // Stop: while loading the task sees the flag; once playing, closing the window ends the blink.
                isBlinking.set(false);
                mainAppWindow.getBlinkFrame().close();
                return;
            }
            FitsFileInformation[] selectedFitsFilesInfo = getSelectedFilesInformation();
            if (selectedFitsFilesInfo == null || selectedFitsFilesInfo.length == 0 || !confirmBlinkMemory(selectedFitsFilesInfo)) {
                return;
            }
            if (blinkLoader != null && blinkLoader.isAlive()) {
                // A stopped blink is still finishing the frame it was reading; its events would end the new one.
                statusLabel.setText("Still stopping the previous blink, try again in a moment.");
                return;
            }
            isBlinking.set(true);
            mainAppWindow.getBlinkFrame().showLoading(selectedFitsFilesInfo);
            blinkLoader = new Thread(new BlinkImagesTask(mainAppWindow.getEventBus(), selectedFitsFilesInfo, isBlinking), "blink-loader");
            blinkLoader.start();
        });

        detectSingleButton.addActionListener(e -> previewSelectedFrame());

        // Each run gets the detection settings as they are at the click, or does not start while a setting is invalid.
        manualTransientInspectionButton.addActionListener(e -> {
            DetectionConfig settings = mainAppWindow.getDetectionConfigurationPanel().checkedSettings("Manual Transient Inspection did not start.");
            if (settings == null) {
                return;
            }
            ImageProcessing session = mainAppWindow.getImageProcessing();
            startJob(JobKind.TRANSIENT_INSPECTION, session, new ManualTransientInspectionTask(session, settings), (handle, outcome) -> {
                if (outcome instanceof JobOutcome.Completed<ManualTransientInspectionTask.Result> completed) {
                    ManualTransientInspectionTask.Result result = completed.result();
                    TransientInspectionFrame frame = new TransientInspectionFrame(result.frames(), result.transients(), result.files(),
                            mainAppWindow.getStretchPanel());
                    if (!frame.open()) {
                        JOptionPane.showMessageDialog(null, "The detection kept no frames to inspect.",
                                "Manual Transient Inspection", JOptionPane.INFORMATION_MESSAGE);
                    }
                } else {
                    showDetectionProblem(outcome);
                }
            });
        });

        detectBatchButton.addActionListener(e -> {
            DetectionConfig settings = mainAppWindow.getDetectionConfigurationPanel().checkedSettings("Detect Moving Targets did not start.");
            if (settings == null) {
                return;
            }
            ImageProcessing session = mainAppWindow.getImageProcessing();
            startJob(JobKind.DETECTION, session, new DetectionTask(session, settings), this::showDetectionOutcome);
        });

        detectIterativelyButton.addActionListener(e -> {
            DetectionConfig settings = mainAppWindow.getDetectionConfigurationPanel().checkedSettings("Detect Iteratively did not start.");
            if (settings == null) {
                return;
            }
            Integer maxFrames = askIterativeFrameLimit();
            if (maxFrames == null) {
                return;
            }
            ImageProcessing session = mainAppWindow.getImageProcessing();
            startJob(JobKind.ITERATIVE_DETECTION, session, new IterativeDetectionTask(session, settings, maxFrames),
                    this::showDetectionOutcome);
        });

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

        table.getSelectionModel().addListSelectionListener(event -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && table.getValueAt(selectedRow, FitsFileTableModel.COL_FILENAME) != null) {

                FitsFileInformation fileInfo = getSelectedFileInformation();
                if (fileInfo != null) {
                    boolean isSolved = "Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED)));
                    solveButton.setEnabled(!isSolved);
                }

                mainAppWindow.getStretchPanel().onFrameSelectionChanged();
            } else {
                mainAppWindow.getStretchPanel().onFrameSelectionChanged();
            }

            FitsFileInformation[] selectedFitsFilesInfo = getSelectedFilesInformation();
            if (selectedFitsFilesInfo != null && selectedFitsFilesInfo.length >= 3) {
                blinkButton.setEnabled(true);
            } else {
                blinkButton.setEnabled(false);
            }
            refreshWorkflowStatus();
        });

        skyCatalogueButton.addActionListener(e -> onSkyCatalogueButton());
        // A viewer switches the correction on or off; the Astrometry status follows.
        SkyCatalogueModel.shared().addCorrectionListener(this::refreshWorkflowStatus);

        solveButton.addActionListener(e -> {
            ApplicationWindow.logger.info("Will try to solve image");
            FitsFileInformation selectedFile = getSelectedFileInformation();
            if (selectedFile == null) return;

            // The result belongs to this file, wherever its row is when the solve ends.
            ImageProcessing session = mainAppWindow.getImageProcessing();
            startJob(JobKind.PLATE_SOLVE, session, new PlateSolveTask(session, selectedFile.getFilePath(),
                            astapSolveRadio.isSelected(), astrometryNetSolveRadio.isSelected()),
                    (handle, outcome) -> showSolveOutcome(selectedFile, outcome));
        });
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

        if (containsColorImages) {
            ApplicationWindow.logger.info("Color images detected. Enabling 'Batch Convert to Mono'.");
            convertMonoButton.setEnabled(true);
            setDetectionButtonsDisabled();
        } else {
            ApplicationWindow.logger.info("All images are Monochrome. Enabling 'Detect Objects'.");
            convertMonoButton.setEnabled(false);
            setDetectionButtonsEnabled();
        }
        // Plate solving updates the table; keep the solved count current.
        model.addTableModelListener(e -> refreshWorkflowStatus());
        refreshWorkflowStatus();
    }

    private void clearLoadedControls() {
        containsColorImages = false;
        solveButton.setEnabled(false);
        blinkButton.setEnabled(false);
        convertMonoButton.setEnabled(false);
        stretchButton.setEnabled(false);
        setDetectionButtonsDisabled();
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

    /** Sets a button's tooltip; while the button is disabled the tooltip also says why. */
    private void setActionTooltip(JButton button, String tooltip) {
        actionTooltips.put(button, tooltip);
        button.addPropertyChangeListener("enabled", e -> updateActionTooltip(button));
        updateActionTooltip(button);
    }

    private void updateActionTooltip(JButton button) {
        String tooltip = actionTooltips.get(button);
        String reason = button.isEnabled() ? null : disabledReason(button);
        String body = tooltip.replaceFirst("^<html>", "").replaceFirst("</html>$", "");
        button.setToolTipText(reason == null ? tooltip
                : "<html>" + body + "<br><i>Not available: " + reason + "</i></html>");
    }

    private String disabledReason(JButton button) {
        if (uiLocked) {
            return "wait until the current task has finished.";
        }
        if (button == importButton) {
            return "an import or another task is running.";
        }
        int frames = table == null ? 0 : table.getRowCount();
        if (frames == 0) {
            return "import frames first (Import Aligned Frames…, or drop a folder on the window).";
        }
        if (button == convertMonoButton) {
            return "all frames are already monochrome.";
        }
        if (button == stretchButton) {
            return "tick \"Enable batch stretch\" in the Image Stretch tab first.";
        }
        if (button == solveButton) {
            return table.getSelectedRow() < 0 ? "select one frame in the table." : "the selected frame is already solved.";
        }
        if (button == blinkButton) {
            return "select at least 3 frames in the table.";
        }
        if (button == skyCatalogueButton) {
            return "plate solve at least one frame first, and write the solution to its FITS header.";
        }
        if (containsColorImages) {
            return "convert the frames to monochrome first (1 Prepare).";
        }
        if (button == autoTuneButton) {
            return frames < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES
                    ? "Auto-Tune needs at least " + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES + " frames."
                    : "Auto-Tune is already running.";
        }
        return null;
    }

    /** Updates the status line of every group and the disabled-button reasons. */
    void refreshWorkflowStatus() {
        int frames = table == null || table.getModel() == null ? 0 : table.getRowCount();
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
        boolean fetching = skyFetchThread != null;
        skyCatalogueButton.setText(fetching ? "Cancel Sky Catalogue" : FETCH_SKY_CATALOGUE);
        skyCatalogueButton.setEnabled(!uiLocked && (fetching || hasSolvedFrame));
        updateAutoTuneButton();
        updateAutoTuneTooltip();
        for (JButton button : actionTooltips.keySet()) {
            updateActionTooltip(button);
        }
    }

    /**
     * The eight values the calibrated auto-tuner sets: detection sigma, grow and minimum pixels; master sigma,
     * grow and minimum pixels; mask overlap; base star jitter, in pixels.
     */
    void showActiveDetectionSettings(double detectionSigma, double growSigma, int minPixels,
                                     double masterSigma, double masterGrowSigma, int masterMinPixels,
                                     double maskOverlap, double jitterPixels) {
        activeDetectionValue.setText(String.format(Locale.US, "%.2f / %.2f / %d", detectionSigma, growSigma, minPixels));
        activeMaskValue.setText(String.format(Locale.US, "%.2f / %.2f / %d", masterSigma, masterGrowSigma, masterMinPixels));
        activeOverlapValue.setText(String.format(Locale.US, "%.2f", maskOverlap));
        activeJitterValue.setText(String.format(Locale.US, "%.2f px", jitterPixels));
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
        JLabel editSettingsLink = new JLabel("<html><u>Edit settings…</u></html>");
        editSettingsLink.setFont(small);
        editSettingsLink.setForeground(LINK_COLOR);
        editSettingsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        editSettingsLink.setToolTipText("Open Detection Settings to change these values.");
        editSettingsLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!uiLocked) {
                    mainAppWindow.getTabbedPane().setSelectedIndex(3);
                }
            }
        });
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
        DetectionConfigurationPanel settings = mainAppWindow.getDetectionConfigurationPanel();
        return settings == null ? "Manual settings" : settings.getSettingsSummary();
    }

    /** The saved auto-tune line for the imported dataset, or null when there is none. */
    private String savedAutoTuneLine() {
        DetectionConfigurationPanel settings = mainAppWindow.getDetectionConfigurationPanel();
        return settings == null ? null : settings.getSavedAutoTuneLine();
    }

    /** Adds the saved-run date to the Auto-Tune button, and says that a click measures again. */
    private void updateAutoTuneTooltip() {
        int frames = table == null ? 0 : table.getRowCount();
        String saved = frames == 0 ? null : savedAutoTuneLine();
        String tooltip = saved == null ? AUTO_TUNE_TOOLTIP
                : AUTO_TUNE_TOOLTIP.replace("</html>", "<br>" + saved + ". A click measures the frames again.</html>");
        actionTooltips.put(autoTuneButton, tooltip);
        updateActionTooltip(autoTuneButton);
    }

    /** Right-click menu on the frame table for the actions on selected frames. */
    private void installTableContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem blinkItem = new JMenuItem("Blink Selected");
        JMenuItem previewItem = new JMenuItem("Preview Frame");
        JMenuItem solveItem = new JMenuItem("Plate Solve Selected");
        blinkItem.addActionListener(e -> blinkButton.doClick());
        previewItem.addActionListener(e -> detectSingleButton.doClick());
        solveItem.addActionListener(e -> solveButton.doClick());
        menu.add(blinkItem);
        menu.add(previewItem);
        menu.add(solveItem);
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
                blinkItem.setEnabled(blinkButton.isEnabled());
                previewItem.setEnabled(detectSingleButton.isEnabled());
                solveItem.setEnabled(solveButton.isEnabled());
                menu.show(table, e.getX(), e.getY());
            }
        });
    }

    /** Ctrl+D detect, Ctrl+B blink, Ctrl+P preview, Ctrl+T Auto-Tune. */
    private void installShortcuts() {
        bindShortcut("detect", KeyStroke.getKeyStroke("ctrl D"), detectBatchButton);
        bindShortcut("blink", KeyStroke.getKeyStroke("ctrl B"), blinkButton);
        bindShortcut("preview", KeyStroke.getKeyStroke("ctrl P"), detectSingleButton);
        bindShortcut("autoTune", KeyStroke.getKeyStroke("ctrl T"), autoTuneButton);
    }

    private void bindShortcut(String name, KeyStroke key, JButton button) {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(key, name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (button.isEnabled() && !uiLocked) {
                    button.doClick();
                }
            }
        });
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

    /**
     * Starts fetching the sky catalogue of the session in the background, or cancels the work running. The rest of
     * the application stays usable meanwhile. The catalogue is saved next to the frames and handed to the viewers at
     * once; then, in the same background job, the plate solution is checked against its stars (see
     * {@link #startSkyJob}).
     */
    private void onSkyCatalogueButton() {
        if (skyFetchThread != null) {
            skyFetchThread.interrupt();
            skyFetchLabel.setText("Cancelling…");
            return;
        }
        FitsFileInformation[] files = getImportedFiles();
        WcsSolutionResolver.ResolvedWcsSolution solution = files == null ? null : WcsSolutionResolver.resolveUncorrected(null, files);
        if (solution == null) {
            skyFetchProblem = "No frame has a plate solution in its FITS header.";
            refreshWorkflowStatus();
            return;
        }
        SkyField field = SkyField.of(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight());
        double depth = mainAppWindow.getImageProcessing().getAppConfig().skyCatalogueStarMagnitude();
        skyFetchProblem = null;
        correctionProblem = null;
        statusLabel.setText("Fetching the sky catalogue (" + field.sizeText() + " field)…");
        startSkyJob(files, sessionFolder(files), field, depth, null);
    }

    /**
     * The background job: fetches the catalogue (unless {@code existing} is given), hands it over, then fits the
     * plate-solution correction to its stars and hands that over too. Interrupting the thread cancels what is left.
     */
    private void startSkyJob(FitsFileInformation[] files, File folder, SkyField field, double depth, SkyCatalogue existing) {
        Thread[] thread = new Thread[1];
        thread[0] = new Thread(() -> {
            SkyCatalogueClient client = new SkyCatalogueClient();
            SkyCatalogue catalogue = existing;
            if (catalogue == null) {
                SkyCatalogue result = null;
                String failure = null;
                boolean cancelled = false;
                try {
                    result = client.fetch(field, depth, (number, total, text) ->
                            EventQueue.invokeLater(() -> showSkyFetchStage(thread[0], "Sky catalogue " + number + "/" + total + ": " + text)));
                } catch (InterruptedException e) {
                    cancelled = true;
                } catch (RuntimeException e) {
                    ApplicationWindow.logger.log(java.util.logging.Level.WARNING, "Sky catalogue fetch failed", e);
                    failure = e.getMessage();
                }
                SkyCatalogue fetched = result;
                String problem = failure;
                boolean wasCancelled = cancelled;
                boolean fitFollows = !cancelled && failure == null && result != null && !result.stars.isEmpty();
                EventQueue.invokeLater(() -> skyFetchFinished(thread[0], folder, fetched, problem, wasCancelled, fitFollows));
                if (!fitFollows) {
                    return;
                }
                catalogue = result;
            }
            PlateSolutionCorrection correction = null;
            String failure = null;
            boolean cancelled = false;
            try {
                correction = PlateCorrectionFitter.fit(files, catalogue, client, text ->
                        EventQueue.invokeLater(() -> showSkyFetchStage(thread[0], "Checking the plate solution: " + text)));
            } catch (InterruptedException e) {
                cancelled = true;
            } catch (IOException | RuntimeException e) {
                ApplicationWindow.logger.log(java.util.logging.Level.WARNING, "Plate solution check failed", e);
                failure = e.getMessage();
            }
            PlateSolutionCorrection fitted = correction;
            String problem = failure;
            boolean wasCancelled = cancelled;
            EventQueue.invokeLater(() -> correctionFinished(thread[0], folder, fitted, problem, wasCancelled));
        }, "sky-catalogue");
        thread[0].setDaemon(true);
        skyFetchThread = thread[0];
        showSkyFetchStage(thread[0], existing == null ? "Sky catalogue: starting" : "Checking the plate solution: starting");
        skyFetchIndicator.setVisible(true);
        skyFetchTimer.start();
        refreshWorkflowStatus();
        thread[0].start();
    }

    private void showSkyFetchStage(Thread job, String stage) {
        if (job != skyFetchThread) {
            return;
        }
        if (!stage.equals(skyFetchStage)) {
            skyFetchStage = stage;
            skyFetchStageStart = System.currentTimeMillis();
        }
        updateSkyFetchText();
    }

    private void updateSkyFetchText() {
        if (skyFetchThread == null) {
            return;
        }
        long seconds = (System.currentTimeMillis() - skyFetchStageStart) / 1000;
        skyFetchLabel.setText(skyFetchStage + "…" + (seconds >= 2 ? " " + seconds + " s" : ""));
    }

    private void skyFetchFinished(Thread job, File folder, SkyCatalogue result, String failure, boolean cancelled, boolean fitFollows) {
        if (job != skyFetchThread) {
            return; // cancelled by a new import: the result belongs to the previous session
        }
        if (!fitFollows) {
            stopSkyFetchIndicator();
        }
        if (cancelled) {
            statusLabel.setText("Sky catalogue: cancelled");
        } else if (failure != null || result == null) {
            skyFetchProblem = "The fetch failed: " + failure;
            statusLabel.setText("Sky catalogue: the fetch failed");
        } else if (result.isEmpty()) {
            skyFetchProblem = result.problems.isEmpty() ? "The services found nothing in this field." : String.join("\n", result.problems);
            statusLabel.setText("Sky catalogue: nothing could be fetched");
        } else {
            skyFetchProblem = result.problems.isEmpty() ? null : String.join("\n", result.problems);
            try {
                SkyCatalogueStore.save(folder, result);
            } catch (IOException e) {
                ApplicationWindow.logger.log(java.util.logging.Level.WARNING, "Cannot save the sky catalogue in " + folder, e);
                skyFetchProblem = (skyFetchProblem == null ? "" : skyFetchProblem + "\n")
                        + "It could not be saved in the session folder (" + e.getMessage() + "), so it lasts until SpacePixels closes.";
            }
            SkyCatalogueModel.shared().set(result);
            statusLabel.setText("Sky catalogue: " + result.summary() + (result.problems.isEmpty() ? "" : " (incomplete)"));
        }
        refreshWorkflowStatus();
    }

    /**
     * The fitted correction arrives. It replaces the one there was and keeps its on/off setting; a new session's
     * first correction starts switched off, so nothing changes until it is switched on in a viewer.
     */
    private void correctionFinished(Thread job, File folder, PlateSolutionCorrection correction, String failure, boolean cancelled) {
        if (job != skyFetchThread) {
            return;
        }
        stopSkyFetchIndicator();
        if (cancelled) {
            statusLabel.setText("Plate solution check: cancelled");
        } else if (correction == null) {
            correctionProblem = "The plate solution could not be checked: " + failure;
            statusLabel.setText("Plate solution check failed");
        } else {
            correctionProblem = null;
            PlateSolutionCorrection previous = SkyCatalogueModel.shared().getCorrection();
            correction.enabled = correction.isNeeded() && previous != null && previous.enabled
                    && correction.solutionKey.equals(previous.solutionKey);
            try {
                PlateSolutionCorrection.save(folder, correction);
            } catch (IOException e) {
                correctionProblem = "It could not be saved in the session folder (" + e.getMessage() + ").";
            }
            SkyCatalogueModel.shared().setCorrection(correction);
            statusLabel.setText(!correction.isNeeded()
                    ? String.format(Locale.US, "Plate solution checked: the FITS header already matches the stars (%.2f px)", correction.beforeMedianPx)
                    : "Plate solution checked: " + correction.improvementText()
                    + (correction.enabled ? ", correction in use" : "; switch the correction on in a viewer (C)"));
        }
        refreshWorkflowStatus();
    }

    private void stopSkyFetchIndicator() {
        skyFetchThread = null;
        skyFetchTimer.stop();
        skyFetchIndicator.setVisible(false);
    }

    /**
     * On import: stops the work for the previous session and offers the catalogue and correction saved with this
     * one, if any. When there is a catalogue but no correction for the frames' solution, the check is started.
     */
    private void restoreSkyCatalogue() {
        if (skyFetchThread != null) {
            skyFetchThread.interrupt();
            stopSkyFetchIndicator();
        }
        skyFetchProblem = null;
        correctionProblem = null;
        FitsFileInformation[] files = getImportedFiles();
        File folder = files == null ? null : sessionFolder(files);
        SkyCatalogueModel.shared().setSessionFolder(folder);
        SkyCatalogue saved = folder == null ? null : SkyCatalogueStore.load(folder);
        WcsSolutionResolver.ResolvedWcsSolution solution = files == null ? null : WcsSolutionResolver.resolveUncorrected(null, files);
        SkyField current = solution == null ? null
                : SkyField.of(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight());
        if (saved != null && current != null && !current.isSameFieldAs(saved.field)) {
            saved = null; // fetched for other frames
        }
        PlateSolutionCorrection correction = folder == null ? null : PlateSolutionCorrection.load(folder);
        if (correction != null && (solution == null || !solution.getTransformer().solutionKey().equals(correction.solutionKey))) {
            correction = null; // fitted to another solution
        }
        SkyCatalogueModel.shared().set(saved);
        SkyCatalogueModel.shared().setCorrection(correction);
        if (saved != null && correction == null && !saved.stars.isEmpty() && current != null) {
            startSkyJob(files, folder, current, saved.starMagnitudeLimit, saved);
        }
    }

    private static File sessionFolder(FitsFileInformation[] files) {
        File parent = new File(files[0].getFilePath()).getAbsoluteFile().getParentFile();
        return parent != null ? parent : new File(System.getProperty("user.dir"));
    }

    /** The second line of the Astrometry status, and a third for the plate-solution correction. */
    private String skyCatalogueStatusText() {
        String catalogueText = catalogueStatusText();
        PlateSolutionCorrection correction = SkyCatalogueModel.shared().getCorrection();
        if (correction != null) {
            return catalogueText + "<br>" + (!correction.isNeeded()
                    ? "✓ Plate solution checked · " + String.format(Locale.US, "%.2f px", correction.beforeMedianPx)
                    : correction.enabled
                    ? "✓ Plate solution corrected · " + String.format(Locale.US, "%.2f px", correction.afterMedianPx)
                    : "Correction available · " + correction.improvementText());
        }
        if (correctionProblem != null) {
            return catalogueText + "<br>⚠ Plate solution not checked";
        }
        return catalogueText;
    }

    private String catalogueStatusText() {
        if (skyFetchThread != null) {
            return skyFetchStage.startsWith("Checking") ? "Checking the plate solution…" : "Fetching sky catalogue…";
        }
        SkyCatalogue catalogue = SkyCatalogueModel.shared().get();
        if (catalogue == null) {
            return skyFetchProblem != null ? "⚠ Sky catalogue not fetched" : "No sky catalogue yet";
        }
        return (catalogue.problems.isEmpty() && skyFetchProblem == null ? "✓ Sky catalogue · " : "⚠ Sky catalogue · ")
                + String.format(Locale.US, "%,d stars", catalogue.stars.size());
    }

    private String skyCatalogueTooltip() {
        StringBuilder text = new StringBuilder("<html>The report has sky coordinates: moving objects are identified with JPL and SkyBoT,<br>"
                + "and variable-star candidates are matched against AAVSO VSX.");
        SkyCatalogue catalogue = SkyCatalogueModel.shared().get();
        if (catalogue != null) {
            text.append("<br><br>Sky catalogue for the Annotate switch: ").append(catalogue.summary())
                    .append(String.format(Locale.US, "<br>Stars to Gaia G %.1f", catalogue.starMagnitudeLimit))
                    .append(catalogue.starSource == null ? "" : " from " + catalogue.starSource)
                    .append(catalogue.fetchedAtUtc == null ? "" : ", fetched " + fetchedText(catalogue.fetchedAtUtc));
            double depth = mainAppWindow.getImageProcessing() == null ? catalogue.starMagnitudeLimit
                    : mainAppWindow.getImageProcessing().getAppConfig().skyCatalogueStarMagnitude();
            if (Math.abs(depth - catalogue.starMagnitudeLimit) > 0.01) {
                text.append(String.format(Locale.US, "<br><i>The star depth is now set to %.1f: fetch again to use it.</i>", depth));
            }
        } else {
            text.append("<br><br>").append(FETCH_SKY_CATALOGUE).append(" downloads the stars, deep-sky objects and variables of the field<br>"
                    + "for the Annotate switch (A) of the viewers.");
        }
        if (skyFetchProblem != null) {
            text.append("<br><br><b>").append(catalogue == null ? "Not fetched:" : "Incomplete:").append("</b> ")
                    .append(escapeHtml(skyFetchProblem).replace("\n", "<br>"));
        }
        PlateSolutionCorrection correction = SkyCatalogueModel.shared().getCorrection();
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
            text.append("<br>The FITS files are not changed.");
        } else if (correctionProblem != null) {
            text.append("<br><br><b>Plate solution not checked:</b> ").append(escapeHtml(correctionProblem));
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
        if (table == null || table.getModel() == null) {
            return null;
        }

        FitsFileTableModel model = (FitsFileTableModel) table.getModel();
        int rowCount = model.getRowCount();

        if (rowCount == 0) {
            return null;
        }

        FitsFileInformation[] allFiles = new FitsFileInformation[rowCount];
        for (int i = 0; i < rowCount; i++) {
            allFiles[i] = model.getFitsFileAt(i);
        }

        return allFiles;
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

    /** Enables or disables importing (the button and dropping folders), for example while an import runs. */
    void setImportEnabled(boolean enabled) {
        importButton.setEnabled(enabled);
    }

    boolean isImportEnabled() {
        return importButton.isEnabled() && !uiLocked;
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

    /** The Detect Moving Targets button, so other views can offer the same action with the same enabling. */
    JButton getDetectButton() {
        return detectBatchButton;
    }

    /**
     * Connects the Auto-Tune button to the Overview's Run Auto-Tune button: it is enabled with it (frames ready, no tune
     * running), and a click runs {@code openAndRun}, which shows the Overview and starts Auto-Tune there.
     */
    void bindAutoTuneButton(JButton runButton, Runnable openAndRun) {
        overviewRunAutoTuneButton = runButton;
        autoTuneButton.addActionListener(e -> openAndRun.run());
        runButton.addPropertyChangeListener("enabled", e -> updateAutoTuneButton());
        updateAutoTuneButton();
    }

    private void updateAutoTuneButton() {
        autoTuneButton.setEnabled(!uiLocked && overviewRunAutoTuneButton != null && overviewRunAutoTuneButton.isEnabled());
    }

    /** Runs Detect Moving Targets exactly as its button does (ignored while disabled or busy). */
    void runDetection() {
        if (detectBatchButton.isEnabled() && !uiLocked) {
            detectBatchButton.doClick();
        }
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

    public void setBatchStretchButtonEnabled(boolean state) {
        stretchButton.setEnabled(state);
    }

    private void unlockUI() {
        for (Map.Entry<Component, Boolean> entry : savedComponentStates.entrySet()) {
            entry.getKey().setEnabled(entry.getValue());
        }
        savedComponentStates.clear();
        uiLocked = false;
        refreshWorkflowStatus();

        mainAppWindow.setMenuState(true);
        mainAppWindow.getTabbedPane().setEnabledAt(1, true);
        mainAppWindow.getTabbedPane().setEnabledAt(2, true);
        mainAppWindow.getTabbedPane().setEnabledAt(3, true);
    }

    private void lockUI() {
        uiLocked = true;
        savedComponentStates.clear();
        saveAndDisableRecursive(this);

        mainAppWindow.setMenuState(false);
        mainAppWindow.getTabbedPane().setEnabledAt(1, false);
        mainAppWindow.getTabbedPane().setEnabledAt(2, false);
        mainAppWindow.getTabbedPane().setEnabledAt(3, false);
    }

    private void saveAndDisableRecursive(Container container) {
        for (Component c : container.getComponents()) {
            savedComponentStates.put(c, c.isEnabled());
            c.setEnabled(false);

            if (c instanceof Container) {
                saveAndDisableRecursive((Container) c);
            }
        }
    }

    /** Whether a task holds the window, so another must wait. */
    boolean isUiLocked() {
        return uiLocked;
    }

    /** Starts a job under the coordinator; when it cannot start now, the status bar says why. */
    <R> void startJob(JobKind kind, Object session, Job<R> job, JobCoordinator.OutcomeHandler<R> handler) {
        JobCoordinator.Admission admission = mainAppWindow.getJobCoordinator().start(kind, session, job, handler);
        if (!admission.admitted()) {
            statusLabel.setText(admission.refusal());
        }
    }

    /**
     * Asks for the most frames the iterative passes may use: 0 (or an empty field) for all the frames of the session,
     * or null when the user cancels.
     */
    private Integer askIterativeFrameLimit() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(new JLabel("<html>Enter the maximum number of frames to use for the iterative passes<br>(Leave empty or enter 0 to go up to the total number of frames):</html>"), BorderLayout.NORTH);

        JTextField inputField = new JTextField(10);
        JPanel fieldPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        fieldPanel.add(inputField);
        panel.add(fieldPanel, BorderLayout.CENTER);

        int result = JOptionPane.showConfirmDialog(
                null,
                panel,
                "Limit Iterative Passes",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (result != JOptionPane.OK_OPTION) {
            return null;
        }
        String input = inputField.getText();
        if (input != null && !input.trim().isEmpty()) {
            try {
                return Integer.parseInt(input.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /** The end of Detect Moving Targets and Detect Iteratively: offers the report, or says what went wrong. */
    private void showDetectionOutcome(JobHandle handle, JobOutcome<File> outcome) {
        if (outcome instanceof JobOutcome.Completed<File> completed) {
            promptUserToOpenReport(completed.result());
        } else {
            showDetectionProblem(outcome);
        }
    }

    /** Shows a line in the status bar, for an outcome handled outside this panel. */
    void showStatus(String text) {
        statusLabel.setText(text);
    }

    /** Tells the user that a detection job was declined or failed, as the detection jobs always have. */
    void showDetectionProblem(JobOutcome<?> outcome) {
        String reason = outcome instanceof JobOutcome.Declined<?> declined ? declined.reason()
                : outcome instanceof JobOutcome.Failed<?> failed ? failed.reason() : null;
        if (reason != null) {
            JOptionPane.showMessageDialog(this, "Detection failed: " + reason, "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void disableControlsProcessing() { lockUI(); }
    private void enableControlsProcessingFinished() { unlockUI(); }
    private void disableControlsBlinking() { lockUI(); }
    private void enableControlsProcessingBlinkingFinished() { unlockUI(); }

    /**
     * Opens Preview Frame on the selected frame, with the detection settings as they are now; later edits apply
     * when Preview Frame is opened again. The Detection Settings button uses this same window.
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

        DetectionConfig settings = mainAppWindow.getDetectionConfigurationPanel().checkedSettings("Preview Frame did not open.");
        if (settings == null) {
            return;
        }
        if (detectionSequenceFrame == null) {
            detectionSequenceFrame = new DetectionSequenceFrame(mainAppWindow);
        }

        detectionSequenceFrame.openSequence(
                allFiles,
                startIndex,
                settings,
                mainAppWindow.getStretchPanel()
        );
    }

    public void setDetectionButtonsEnabled() {
        this.detectSingleButton.setEnabled(true);
        this.manualTransientInspectionButton.setEnabled(true);
        this.detectBatchButton.setEnabled(true);
        this.detectIterativelyButton.setEnabled(true);
    }

    public void setDetectionButtonsDisabled() {
        this.detectSingleButton.setEnabled(false);
        this.manualTransientInspectionButton.setEnabled(false);
        this.detectBatchButton.setEnabled(false);
        this.detectIterativelyButton.setEnabled(false);
    }

    // ==========================================
    // EVENT SUBSCRIBERS
    // ==========================================

    // --- NEW: PROGRESS DIALOG SUBSCRIBER ---
    @Subscribe
    public void onEngineProgressUpdate(EngineProgressUpdateEvent event) {
        EventQueue.invokeLater(() -> {
            if (progressDialog != null && progressDialog.isVisible()) {
                progressDialog.updateProgress(event.getPercentage(), event.getMessage());
            }
        });
    }

    @Subscribe
    public void onImportStarted(FitsImportStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            statusLabel.setText("Importing FITS/XISF sequence...");
            progressDialog.showIndeterminateProgress("Loading FITS/XISF sequence in the background...");
            if (!progressDialog.isVisible()) {
                progressDialog.setVisible(true);
            }
        });
    }

    @Subscribe
    public void onImportFinished(FitsImportFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            progressDialog.setVisible(false);
            setProgressBarIdle();

            if (event.isSuccess()) {
                FitsFileInformation[] files = event.getFilesInformation();
                int importedCount = files == null ? 0 : files.length;
                statusLabel.setText(importedCount == 0 ? "No files loaded" : "Imported " + importedCount + " file(s)");
            } else {
                statusLabel.setText("Import failed");
            }
        });
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

    @Subscribe
    public void onBlinkStarted(BlinkStartedEvent event) {
        EventQueue.invokeLater(() -> {
            disableControlsBlinking();
            // The button stays available to stop the blink.
            blinkButton.setEnabled(true);
            blinkButton.setText("Stop Blinking");
            statusLabel.setText("Loading frames for blinking...");
        });
    }

    @Subscribe
    public void onBlinkLoadProgress(BlinkLoadProgressEvent event) {
        EventQueue.invokeLater(() -> {
            statusLabel.setText(String.format("Loading frame %d of %d for blinking...", event.getLoaded() + 1, event.getTotal()));
            mainAppWindow.getBlinkFrame().showLoadProgress(event.getLoaded(), event.getTotal());
        });
    }

    @Subscribe
    public void onBlinkSequenceLoaded(BlinkSequenceLoadedEvent event) {
        EventQueue.invokeLater(() -> {
            if (!isBlinking.get()) {
                // Stopped just as the last frame was read.
                endBlink("Blinking stopped.");
                return;
            }
            statusLabel.setText("Blinking " + event.getSequence().getFrames().size()
                    + " frames. Close the blink window or click Stop Blinking to end.");
            mainAppWindow.getBlinkFrame().open(event.getSequence(), event.getFiles(), getImportedFiles(), mainAppWindow.getStretchPanel(),
                    mainAppWindow.getImageProcessing() == null ? null : mainAppWindow.getImageProcessing().getLastTrackOverlay(),
                    mainAppWindow.getImageProcessing() == null ? null : mainAppWindow.getImageProcessing().getLastVariableStarOverlay());
        });
    }

    /** Loading ended without showing the frames: stopped while loading, or failed. */
    @Subscribe
    public void onBlinkFinished(BlinkFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            isBlinking.set(false);
            // Hides the window if it still shows the loading progress.
            mainAppWindow.getBlinkFrame().close();
            endBlink("Blinking stopped.");
            if (!event.isSuccess()) {
                JOptionPane.showMessageDialog(this,
                        "Blink process failed: " + event.getErrorMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    @Subscribe
    public void onBlinkFrameClosed(BlinkFrameClosedEvent event) {
        EventQueue.invokeLater(() -> {
            isBlinking.set(false);
            endBlink("Blinking stopped.");
        });
    }

    private void endBlink(String status) {
        if (!uiLocked) {
            return;
        }
        blinkButton.setText("Blink Selected");
        enableControlsProcessingBlinkingFinished();
        statusLabel.setText(status);
    }

    /**
     * The end of Plate Solve: offers to write the solution into the frame's FITS header, or says why the frame was
     * not solved. The solver's temporary files are removed either way.
     */
    private void showSolveOutcome(FitsFileInformation fileInfo, JobOutcome<PlateSolveResult> outcome) {
        FitsFileTableModel model = (FitsFileTableModel) table.getModel();
        PlateSolveResult result = outcome instanceof JobOutcome.Completed<PlateSolveResult> completed ? completed.result() : null;
        if (result != null && result.isSuccess()) {
            ApplicationWindow.logger.info(result.toString());
            statusLabel.setText("Image was successfully plate-solved");

            int choice = JOptionPane.showConfirmDialog(this,
                    "Image was successfully plate-solved.\n\nWould you like to write the WCS coordinate solution directly into the FITS header?\n(This permanently modifies the file)",
                    "Update FITS Header",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION && fileInfo != null) {
                try {
                    Map<String, String> updatedHeader = mainAppWindow.getImageProcessing()
                            .updateFitsHeaderWithWCS(fileInfo.getFilePath(), result.getSolveInformation());
                    fileInfo.getFitsHeader().clear();
                    fileInfo.getFitsHeader().putAll(updatedHeader);
                    model.refreshRow(model.rowOf(fileInfo));
                    statusLabel.setText("FITS header updated with WCS solution");
                    JOptionPane.showMessageDialog(this, "FITS header successfully updated.", "Success", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    statusLabel.setText("Failed to update FITS header");
                    JOptionPane.showMessageDialog(this, "Failed to update FITS header: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                } finally {
                    if (fileInfo != null) {
                        mainAppWindow.getImageProcessing().cleanupSolveArtifacts(fileInfo.getFilePath(), result);
                    }
                }
            } else {
                if (fileInfo != null) {
                    mainAppWindow.getImageProcessing().cleanupSolveArtifacts(fileInfo.getFilePath(), result);
                }
                statusLabel.setText("Plate solve completed; WCS was not written to the FITS header");
            }
        } else {
            String errorMsg = result != null ? result.getFailureReason() + " " + result.getWarning()
                    : outcome instanceof JobOutcome.Failed<PlateSolveResult> failed ? failed.reason() : "Internal Error";
            if (fileInfo != null) {
                mainAppWindow.getImageProcessing().cleanupSolveArtifacts(fileInfo.getFilePath(), result);
            }
            statusLabel.setText("Plate solve failed");
            JOptionPane.showMessageDialog(this, "Image was not plate-solved successfully: " + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
        }

        int selectedRow = table.getSelectedRow();
        if (selectedRow >= 0) {
            FitsFileInformation selectedFileInfo = getSelectedFileInformation();
            if (selectedFileInfo != null) {
                boolean isSolved = "Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED)));
                solveButton.setEnabled(!isSolved);
            }
        }
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
                    mainAppWindow.importFolder(generatedMonoDirectory);
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Batch conversion to mono completed successfully.",
                        "Conversion Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        } else if (outcome instanceof JobOutcome.Failed<File> failed) {
            JOptionPane.showMessageDialog(this,
                    "Cannot convert images: " + failed.reason(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
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
            JOptionPane.showMessageDialog(this,
                    "Cannot stretch images: " + failed.reason(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * What the status bar and the progress dialog say while a job of this kind runs, and after it; an indeterminate
     * job reports no percentage.
     */
    private record JobTexts(String started, String initializing, boolean indeterminate, String ended) {
        static JobTexts of(JobKind kind) {
            return switch (kind) {
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
