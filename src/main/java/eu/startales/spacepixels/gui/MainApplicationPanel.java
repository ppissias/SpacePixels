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
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.StretchAlgorithm;
import eu.startales.spacepixels.util.WcsSolutionResolver;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueClient;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import eu.startales.spacepixels.events.*;
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
    private final JPanel updateNoticeHolder = new JPanel(new BorderLayout());
    private final JButton convertMonoButton = new JButton("Convert to Mono");
    private final JButton stretchButton = new JButton("Batch Stretch");
    private final JButton blinkButton = new JButton("Blink Selected");
    private final JButton solveButton = new JButton("Plate Solve Selected");
    private final JButton skyCatalogueButton = new JButton(FETCH_SKY_CATALOGUE);
    private final JButton detectSingleButton = new JButton("Preview Frame");
    private final JButton manualTransientInspectionButton = new JButton("Manual Transient Inspection");
    private final JButton detectBatchButton = new JButton("Detect Moving Targets");
    private final JButton detectIterativelyButton = new JButton("Detect Iteratively (large datasets)");
    private final JRadioButton astapSolveRadio = new JRadioButton("ASTAP");
    private final JRadioButton astrometryNetSolveRadio = new JRadioButton("Astrometry.net (online)");

    // Workflow-strip status lines, built only from what is already known (headers, selection, solved flags)
    private final JLabel prepareStatus = new JLabel();
    private final JLabel astrometryStatus = new JLabel();
    private final JLabel inspectStatus = new JLabel();
    private final JLabel detectStatus = new JLabel();
    // Shares its state with the Variable-Star Detection setting once the settings panel binds it.
    private final JCheckBox variableStarCheck = new JCheckBox("Variable-star photometry");
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

    private DetectionSequenceFrame detectionSequenceFrame;

    public MainApplicationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        this.mainAppWindow.getEventBus().register(this);

        // --- NEW: PROGRESS DIALOG INITIALIZATION ---
        // Pass the main app frame so the dialog knows who to lock and where to center itself
        this.progressDialog = new ProcessingProgressDialog((JFrame) mainAppWindow.getFrame());

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
        JLabel editSettingsLink = new JLabel("<html><u>Edit settings…</u></html>");
        editSettingsLink.setForeground(LINK_COLOR);
        editSettingsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        editSettingsLink.setToolTipText("Open the Detection Settings tab (Auto-Tune, thresholds, variable stars).");
        editSettingsLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!uiLocked) {
                    mainAppWindow.getTabbedPane().setSelectedIndex(3);
                }
            }
        });
        JPanel detectGroup = workflowGroup("4  Detect", detectStatus,
                buttonRow(detectBatchButton),
                buttonRow(detectIterativelyButton),
                buttonRow(variableStarCheck, editSettingsLink));

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

            BatchConvertMonoTask task = new BatchConvertMonoTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    stretchEnabled,
                    stretchFactor,
                    iterations,
                    algo);
            new Thread(task).start();
        });

        stretchButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            new Thread(() -> {
                mainAppWindow.getEventBus().post(new BatchStretchStartedEvent());
                try {
                    io.github.ppissias.jtransient.engine.TransientEngineProgressListener progressListener = (percentage, message) -> {
                        mainAppWindow.getEventBus().post(new EngineProgressUpdateEvent(percentage, message));
                    };
                    mainAppWindow.getImageProcessing().batchStretch(stretchFactor, iterations, algo, progressListener);
                    mainAppWindow.getEventBus().post(new BatchStretchFinishedEvent(true, null));
                } catch (Exception ex) {
                    ApplicationWindow.logger.log(java.util.logging.Level.SEVERE, "Batch stretch failed", ex);
                    mainAppWindow.getEventBus().post(new BatchStretchFinishedEvent(false, ex.getMessage()));
                }
            }).start();
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

        detectSingleButton.addActionListener(e -> {
            FitsFileTableModel model = (FitsFileTableModel) table.getModel();
            if (model == null || model.getRowCount() == 0) return;

            FitsFileInformation[] allFiles = new FitsFileInformation[model.getRowCount()];
            for (int i = 0; i < model.getRowCount(); i++) {
                allFiles[i] = model.getFitsFileAt(i);
            }

            int startIndex = table.getSelectedRow();
            if (startIndex < 0) startIndex = 0;

            if (detectionSequenceFrame == null) {
                detectionSequenceFrame = new DetectionSequenceFrame(mainAppWindow);
            }

            detectionSequenceFrame.openSequence(
                    allFiles,
                    startIndex,
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig(),
                    mainAppWindow.getStretchPanel()
            );
        });

        manualTransientInspectionButton.addActionListener(e -> {
            new Thread(new ManualTransientInspectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig(),
                    mainAppWindow.getStretchPanel()
            )).start();
        });

        detectBatchButton.addActionListener(e -> {
            new Thread(new DetectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig()
            )).start();
        });

        // Wire up the new button to the new task
        detectIterativelyButton.addActionListener(e -> {
            new Thread(new IterativeDetectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig()
            )).start();
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
        // Right end: the "new version" notice (when a newer release exists) and the progress bar.
        JPanel statusRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        statusRight.setOpaque(false);
        statusRight.add(updateNoticeHolder);
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

        solveButton.addActionListener(e -> {
            ApplicationWindow.logger.info("Will try to solve image");
            int row = table.getSelectedRow();
            if (row < 0) return;

            FitsFileInformation selectedFile = getSelectedFileInformation();
            if (selectedFile == null) return;

            new Thread(new PlateSolveTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    selectedFile.getFilePath(),
                    row,
                    astapSolveRadio.isSelected(),
                    astrometryNetSolveRadio.isSelected()
            )).start();
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
        status.setAlignmentX(Component.LEFT_ALIGNMENT);
        status.setForeground(UIManager.getColor("Label.disabledForeground"));
        status.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 0));
        group.add(status);
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
            return "tick \"Write stretched copies\" in the Image Stretch tab first.";
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
            detectStatus.setText("Import aligned frames to start");
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
            detectStatus.setText(containsColorImages ? "Convert to monochrome first" : "Settings: " + detectionSettingsSummary());
        }
        boolean fetching = skyFetchThread != null;
        skyCatalogueButton.setText(fetching ? "Cancel Sky Catalogue" : FETCH_SKY_CATALOGUE);
        skyCatalogueButton.setEnabled(!uiLocked && (fetching || hasSolvedFrame));
        updateVariableStarTooltip();
        for (JButton button : actionTooltips.keySet()) {
            updateActionTooltip(button);
        }
    }

    /**
     * Shares the photometry switch with the Variable-Star Detection setting, so either place turns it on or off.
     */
    void bindVariableStarToggle(ButtonModel settingModel) {
        variableStarCheck.setModel(settingModel);
        variableStarCheck.addItemListener(e -> updateVariableStarTooltip());
        updateVariableStarTooltip();
    }

    private void updateVariableStarTooltip() {
        String text = "<html>Also measure the brightness of the field stars in every frame and look for variable stars<br>"
                + "(light curves and candidates in the report). Runs with Detect Moving Targets; the iterative mode skips it.";
        if (variableStarCheck.isSelected() && !hasSolvedFrame) {
            text += "<br><i>Plate-solve one frame so the candidates can be matched against AAVSO VSX.</i>";
        }
        variableStarCheck.setToolTipText(text + "</html>");
    }

    private String detectionSettingsSummary() {
        DetectionConfigurationPanel settings = mainAppWindow.getDetectionConfigurationPanel();
        return settings == null ? "Manual settings" : settings.getSettingsSummary();
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

    /** Ctrl+D detect, Ctrl+B blink, Ctrl+P preview. */
    private void installShortcuts() {
        bindShortcut("detect", KeyStroke.getKeyStroke("ctrl D"), detectBatchButton);
        bindShortcut("blink", KeyStroke.getKeyStroke("ctrl B"), blinkButton);
        bindShortcut("preview", KeyStroke.getKeyStroke("ctrl P"), detectSingleButton);
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
     * Starts fetching the sky catalogue of the session in the background, or cancels the fetch running. The rest
     * of the application stays usable meanwhile; the result is saved next to the frames and handed to the viewers.
     */
    private void onSkyCatalogueButton() {
        if (skyFetchThread != null) {
            skyFetchThread.interrupt();
            skyFetchLabel.setText("Cancelling the sky catalogue…");
            return;
        }
        FitsFileInformation[] files = getImportedFiles();
        WcsSolutionResolver.ResolvedWcsSolution solution = files == null ? null : WcsSolutionResolver.resolve(null, files);
        if (solution == null) {
            skyFetchProblem = "No frame has a plate solution in its FITS header.";
            refreshWorkflowStatus();
            return;
        }
        SkyField field = SkyField.of(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight());
        double depth = mainAppWindow.getImageProcessing().getAppConfig().skyCatalogueStarMagnitude();
        File folder = sessionFolder(files);
        Thread[] thread = new Thread[1];
        thread[0] = new Thread(() -> {
            SkyCatalogue result = null;
            String failure = null;
            boolean cancelled = false;
            try {
                result = new SkyCatalogueClient().fetch(field, depth, (number, total, text) ->
                        EventQueue.invokeLater(() -> showSkyFetchStage(thread[0], number, total, text)));
            } catch (InterruptedException e) {
                cancelled = true;
            } catch (RuntimeException e) {
                ApplicationWindow.logger.log(java.util.logging.Level.WARNING, "Sky catalogue fetch failed", e);
                failure = e.getMessage();
            }
            SkyCatalogue fetched = result;
            String problem = failure;
            boolean wasCancelled = cancelled;
            EventQueue.invokeLater(() -> skyFetchFinished(thread[0], folder, fetched, problem, wasCancelled));
        }, "sky-catalogue");
        thread[0].setDaemon(true);
        skyFetchThread = thread[0];
        skyFetchProblem = null;
        showSkyFetchStage(thread[0], 0, 3, "starting");
        skyFetchIndicator.setVisible(true);
        skyFetchTimer.start();
        statusLabel.setText("Fetching the sky catalogue (" + field.sizeText() + " field)…");
        refreshWorkflowStatus();
        thread[0].start();
    }

    private void showSkyFetchStage(Thread fetch, int number, int total, String text) {
        if (fetch != skyFetchThread) {
            return;
        }
        skyFetchStage = number == 0 ? "Sky catalogue: " + text : "Sky catalogue " + number + "/" + total + ": " + text;
        skyFetchStageStart = System.currentTimeMillis();
        updateSkyFetchText();
    }

    private void updateSkyFetchText() {
        if (skyFetchThread == null) {
            return;
        }
        long seconds = (System.currentTimeMillis() - skyFetchStageStart) / 1000;
        skyFetchLabel.setText(skyFetchStage + "…" + (seconds >= 2 ? " " + seconds + " s" : ""));
    }

    private void skyFetchFinished(Thread fetch, File folder, SkyCatalogue result, String failure, boolean cancelled) {
        if (fetch != skyFetchThread) {
            return; // cancelled by a new import: the result belongs to the previous session
        }
        stopSkyFetchIndicator();
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

    private void stopSkyFetchIndicator() {
        skyFetchThread = null;
        skyFetchTimer.stop();
        skyFetchIndicator.setVisible(false);
    }

    /** On import: stops a fetch for the previous session and offers the catalogue saved with this one, if any. */
    private void restoreSkyCatalogue() {
        if (skyFetchThread != null) {
            skyFetchThread.interrupt();
            stopSkyFetchIndicator();
        }
        skyFetchProblem = null;
        FitsFileInformation[] files = getImportedFiles();
        SkyCatalogue saved = files == null ? null : SkyCatalogueStore.load(sessionFolder(files));
        if (saved != null) {
            WcsSolutionResolver.ResolvedWcsSolution solution = WcsSolutionResolver.resolve(null, files);
            SkyField current = solution == null ? null
                    : SkyField.of(solution.getTransformer(), files[0].getSizeWidth(), files[0].getSizeHeight());
            if (current != null && !current.isSameFieldAs(saved.field)) {
                saved = null; // fetched for other frames
            }
        }
        SkyCatalogueModel.shared().set(saved);
    }

    private static File sessionFolder(FitsFileInformation[] files) {
        File parent = new File(files[0].getFilePath()).getAbsoluteFile().getParentFile();
        return parent != null ? parent : new File(System.getProperty("user.dir"));
    }

    /** The second line of the Astrometry status. */
    private String skyCatalogueStatusText() {
        if (skyFetchThread != null) {
            return "Fetching sky catalogue…";
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

    /** Shows the "new version" notice at the right end of the status bar. */
    void setUpdateNotice(JComponent notice) {
        updateNoticeHolder.removeAll();
        updateNoticeHolder.setOpaque(false);
        updateNoticeHolder.add(notice, BorderLayout.CENTER);
        updateNoticeHolder.revalidate();
        updateNoticeHolder.repaint();
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

    private void disableControlsSolving() { lockUI(); }
    private void enableControlsSolvingFinished() { unlockUI(); }
    private void disableControlsProcessing() { lockUI(); }
    private void enableControlsProcessingFinished() { unlockUI(); }
    private void disableControlsBlinking() { lockUI(); }
    private void enableControlsProcessingBlinkingFinished() { unlockUI(); }

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

    @Subscribe
    public void onDetectionStarted(DetectionStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();

            statusLabel.setText("Object detection started...");

            // --- NEW: SHOW PROGRESS DIALOG ---
            // Because JDialog.setVisible(true) is blocking when modal, it acts as its own event loop.
            // Tasks wrapped in invokeLater() will still execute behind the scenes!
            progressDialog.updateProgress(0, "Initializing object detection...");
            progressDialog.setVisible(true);
        });
    }

    @Subscribe
    public void onDetectionFinished(DetectionFinishedEvent event) {
        EventQueue.invokeLater(() -> {

            // --- NEW: HIDE PROGRESS DIALOG ---
            progressDialog.setVisible(false);

            setProgressBarIdle();
            enableControlsProcessingFinished();
            statusLabel.setText("Object detection completed");

            if (event.isSuccess()) {
                if (!event.isQuickDetection()) {
                    promptUserToOpenReport(event.getReportFilename());
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Detection failed: " + event.getErrorMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    @Subscribe
    public void onAutoTuneStarted(AutoTuneStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();

            statusLabel.setText("Auto-Tuning mathematical sequence...");

            // --- SHOW PROGRESS DIALOG ---
            progressDialog.updateProgress(0, "Initializing Auto-Tuner...");
            progressDialog.setVisible(true);
        });
    }

    @Subscribe
    public void onAutoTuneFinished(AutoTuneFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            // --- HIDE PROGRESS DIALOG ---
            progressDialog.setVisible(false);

            setProgressBarIdle();
            enableControlsProcessingFinished();

            if (event.isSuccess()) {
                statusLabel.setText("Auto-Tuning completed successfully.");
            } else {
                statusLabel.setText("Auto-Tuning failed or aborted.");
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
            mainAppWindow.getBlinkFrame().open(event.getSequence(), event.getFiles(), mainAppWindow.getStretchPanel(),
                    mainAppWindow.getImageProcessing() == null ? null : mainAppWindow.getImageProcessing().getLastTrackOverlay());
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

    @Subscribe
    public void onSolveStarted(SolveStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsSolving();
            statusLabel.setText("Plate solving image...");
            progressDialog.showIndeterminateProgress("Plate solving selected image...");
            progressDialog.setVisible(true);
        });
    }

    @Subscribe
    public void onSolveFinished(SolveFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            progressDialog.setVisible(false);
            PlateSolveResult result = event.getResult();
            FitsFileTableModel model = (FitsFileTableModel) table.getModel();
            FitsFileInformation fileInfo = model.getFitsFileAt(event.getRowIndex());

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
                        model.refreshRow(event.getRowIndex());
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
                String errorMsg = (result != null) ? (result.getFailureReason() + " " + result.getWarning()) : "Internal Error";
                if (fileInfo != null) {
                    mainAppWindow.getImageProcessing().cleanupSolveArtifacts(fileInfo.getFilePath(), result);
                }
                JOptionPane.showMessageDialog(this, "Image was not plate-solved successfully: " + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
            }

            setProgressBarIdle();
            enableControlsSolvingFinished();

            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                FitsFileInformation selectedFileInfo = getSelectedFileInformation();
                if (selectedFileInfo != null) {
                    boolean isSolved = "Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED)));
                    solveButton.setEnabled(!isSolved);
                }
            }
        });
    }

    @Subscribe
    public void onBatchConvertStarted(BatchConvertStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();
            statusLabel.setText("Batch conversion started");
            
            progressDialog.updateProgress(0, "Initializing batch conversion...");
            progressDialog.setVisible(true);
        });
    }

    @Subscribe
    public void onBatchConvertFinished(BatchConvertFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            progressDialog.setVisible(false);
            setProgressBarIdle();
            enableControlsProcessingFinished();

            if (event.isSuccess()) {
                File generatedMonoDirectory = event.getGeneratedMonoDirectory();
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
                        new Thread(new FitsImportTask(mainAppWindow.getEventBus(), generatedMonoDirectory)).start();
                        return;
                    }
                } else {
                    JOptionPane.showMessageDialog(this,
                            "Batch conversion to mono completed successfully.",
                            "Conversion Complete",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Cannot convert images: " + event.getErrorMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
            statusLabel.setText("Batch conversion finished");
        });
    }

    @Subscribe
    public void onBatchStretchStarted(BatchStretchStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();
            statusLabel.setText("Batch stretch started");
            
            progressDialog.updateProgress(0, "Initializing batch stretch...");
            progressDialog.setVisible(true);
        });
    }

    @Subscribe
    public void onBatchStretchFinished(BatchStretchFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            progressDialog.setVisible(false);
            setProgressBarIdle();
            enableControlsProcessingFinished();

            if (event.isSuccess()) {
                JOptionPane.showMessageDialog(this,
                        "Batch stretch completed successfully.\n" +
                                "If you wish to work with these new images, please import the newly created directory.",
                        "Stretch Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                        "Cannot stretch images: " + event.getErrorMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }
            statusLabel.setText("Batch stretch finished");
        });
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
