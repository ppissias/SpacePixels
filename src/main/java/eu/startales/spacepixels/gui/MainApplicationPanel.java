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

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.StretchAlgorithm;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.eventbus.Subscribe;

public class MainApplicationPanel extends JPanel {
    private static final Color LINK_COLOR = new Color(120, 180, 255);

    //link to main window
    private final ApplicationWindow mainAppWindow;

    // --- NEW: PROGRESS DIALOG ---
    private final ProcessingProgressDialog progressDialog;

    //the table
    private volatile JTable table;

    private final JProgressBar progressBar = new JProgressBar();
    private final JButton convertMonoButton = new JButton("Convert to Mono");
    private final JButton stretchButton = new JButton("Batch Stretch");
    private final JButton blinkButton = new JButton("Blink Selected");
    private final JButton solveButton = new JButton("Plate Solve Selected");
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
        setActionTooltip(convertMonoButton, "Extract luminance and convert all loaded FITS files to 16-bit monochrome. Applies stretch if enabled.");
        setActionTooltip(stretchButton, "Apply the current non-linear stretch settings to all imported FITS files and save as new files.");
        setActionTooltip(solveButton, "Calculate the celestial coordinates (WCS) for the selected frame.");
        setActionTooltip(blinkButton, "Animate the selected frames in a new window for manual visual inspection (Ctrl+B).");
        setActionTooltip(detectSingleButton, "Run the extraction engine on the selected frame to preview detected sources and streaks with the current settings (Ctrl+P).");
        setActionTooltip(manualTransientInspectionButton, "Extract purified transients from all frames against the master background and navigate through them using arrow keys.");
        setActionTooltip(detectBatchButton, "Run the full pipeline: detect, link and report moving objects across the whole sequence (Ctrl+D).");
        setActionTooltip(detectIterativelyButton, "For datasets too large for the standard run: multiple temporally spaced passes across the sequence.");
        for (JButton button : actionTooltips.keySet()) {
            button.setEnabled(false);
        }

        astapSolveRadio.setToolTipText("Solve with ASTAP (configured in the Astrometry Config tab).");
        astrometryNetSolveRadio.setToolTipText("Solve with the online nova.astrometry.net web service.");
        ButtonGroup solverGroup = new ButtonGroup();
        solverGroup.add(astapSolveRadio);
        solverGroup.add(astrometryNetSolveRadio);
        astapSolveRadio.setSelected(true);

        // The primary action: accent colours of the look and feel, larger and bold.
        detectBatchButton.putClientProperty("FlatLaf.style",
                "font: +2 bold; background: $Button.default.background; foreground: $Button.default.foreground;"
                        + " borderColor: $Button.default.borderColor; margin: 6,16,6,16");

        JPanel prepareGroup = workflowGroup("1  Prepare", prepareStatus,
                buttonRow(convertMonoButton),
                buttonRow(stretchButton));
        JPanel astrometryGroup = workflowGroup("2  Astrometry", astrometryStatus,
                buttonRow(solveButton),
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

        // --- REFACTORED BLINK ACTION LISTENER ---
        blinkButton.addActionListener(e -> {
            if (!isBlinking.get()) {
                // START BLINKING
                FitsFileInformation[] selectedFitsFilesInfo = getSelectedFilesInformation();
                if (selectedFitsFilesInfo == null || selectedFitsFilesInfo.length == 0) return;

                isBlinking.set(true); // Lock the atomic boolean

                int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
                int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
                StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

                new Thread(new BlinkImagesTask(
                        mainAppWindow.getEventBus(),
                        mainAppWindow.getImageProcessing(),
                        selectedFitsFilesInfo,
                        stretchFactor,
                        iterations,
                        algo,
                        isBlinking
                )).start();

            } else {
                // STOP BLINKING
                isBlinking.set(false); // Task will see this and exit its loop
            }
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
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig()
            );
        });

        manualTransientInspectionButton.addActionListener(e -> {
            new Thread(new ManualTransientInspectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig()
            )).start();
        });

        detectBatchButton.addActionListener(e -> {
            new Thread(new DetectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    null,
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
        statusBar.add(progressBar, BorderLayout.EAST);
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

                if (mainAppWindow.getStretchPanel().isStretchEnabled()) {
                    mainAppWindow.getStretchPanel().triggerPreviewUpdate();
                }
            } else {
                if (mainAppWindow.getStretchPanel().isStretchEnabled()) {
                    mainAppWindow.getStretchPanel().triggerPreviewUpdate();
                }
            }

            FitsFileInformation[] selectedFitsFilesInfo = getSelectedFilesInformation();
            if (selectedFitsFilesInfo != null && selectedFitsFilesInfo.length >= 3) {
                blinkButton.setEnabled(true);
            } else {
                blinkButton.setEnabled(false);
            }
            refreshWorkflowStatus();
        });

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
        button.setToolTipText(reason == null ? tooltip
                : "<html>" + tooltip + "<br><i>Not available: " + reason + "</i></html>");
    }

    private String disabledReason(JButton button) {
        if (uiLocked) {
            return "wait until the current task has finished.";
        }
        int frames = table == null ? 0 : table.getRowCount();
        if (frames == 0) {
            return "import frames first (File → Import aligned FITS/XISF files).";
        }
        if (button == convertMonoButton) {
            return "all frames are already monochrome.";
        }
        if (button == stretchButton) {
            return "enable stretching in the Image Stretch tab first.";
        }
        if (button == solveButton) {
            return table.getSelectedRow() < 0 ? "select one frame in the table." : "the selected frame is already solved.";
        }
        if (button == blinkButton) {
            return "select at least 3 frames in the table.";
        }
        if (containsColorImages) {
            return "convert the frames to monochrome first (1 Prepare).";
        }
        return null;
    }

    /** Updates the status line of every group and the disabled-button reasons. */
    void refreshWorkflowStatus() {
        int frames = table == null || table.getModel() == null ? 0 : table.getRowCount();
        if (frames == 0) {
            prepareStatus.setText("No frames imported");
            astrometryStatus.setText(" ");
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
            astrometryStatus.setText(solved == 0 ? "Solve one frame to identify objects" : "✓ " + solved + " / " + frames + " solved");
            astrometryStatus.setToolTipText(solved == 0
                    ? "<html>No frame is plate-solved yet. One solved frame gives the report sky coordinates:<br>"
                    + "moving objects are identified with JPL and SkyBoT, and variable-star candidates are matched against AAVSO VSX.</html>"
                    : "<html>The report has sky coordinates: moving objects are identified with JPL and SkyBoT,<br>"
                    + "and variable-star candidates are matched against AAVSO VSX.</html>");
            hasSolvedFrame = solved > 0;
            int selected = table.getSelectedRowCount();
            inspectStatus.setText(selected == 0 ? "No frames selected" : selected + (selected == 1 ? " frame selected" : " frames selected"));
            detectStatus.setText(containsColorImages ? "Convert to monochrome first" : "Settings: " + detectionSettingsSummary());
        }
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

    // ... (The rest of your existing event subscribers like onBlinkStarted, onBatchConvertStarted, etc. remain unchanged)

    @Subscribe
    public void onBlinkStarted(BlinkStartedEvent event) {
        EventQueue.invokeLater(() -> {
            disableControlsBlinking();
            blinkButton.setText("stop blinking");
            statusLabel.setText("Preparing blink sequence...");
        });
    }

    @Subscribe
    public void onBlinkFrameUpdate(BlinkFrameUpdateEvent event) {
        EventQueue.invokeLater(() -> {
            statusLabel.setText("Blinking...");
            if (!mainAppWindow.getBlinkFrame().isVisible()) {
                mainAppWindow.getBlinkFrame().setVisible(true);
            }
            mainAppWindow.getBlinkFrame().setImage(event.getImage());
        });
    }

    @Subscribe
    public void onBlinkFinished(BlinkFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            blinkButton.setText("blink");
            enableControlsProcessingBlinkingFinished();
            statusLabel.setText("Blinking stopped.");
            mainAppWindow.getBlinkFrame().dispose();

            if (!event.isSuccess()) {
                JOptionPane.showMessageDialog(this,
                        "Blink process failed: " + event.getErrorMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    @Subscribe
    public void onBlinkFrameClosed(BlinkFrameClosedEvent event) {
        if (isBlinking.get()) {
            ApplicationWindow.logger.info("Blink frame closed by user, signalling blink thread to stop.");
            isBlinking.set(false);
        }
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
