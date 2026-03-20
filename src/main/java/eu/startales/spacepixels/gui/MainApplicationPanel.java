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

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.common.eventbus.Subscribe;

public class MainApplicationPanel extends JPanel {

    //link to main window
    private final ApplicationWindow mainAppWindow;

    // --- NEW: PROGRESS DIALOG ---
    private final ProcessingProgressDialog progressDialog;

    //the table
    private volatile JTable table;

    private final JProgressBar progressBar = new JProgressBar();
    private final JButton convertMonoButton = new JButton("batch convert to mono");
    private final JButton stretchButton = new JButton("batch stretch");
    private final JButton blinkButton = new JButton("blink");
    private final JButton showSolvedImageButton = new JButton("show solved image");
    private final JButton solveButton = new JButton("Solve");
    private final JButton detectSingleButton = new JButton("Detect Objects (Single Image)");
    private final JButton detectBatchButton = new JButton("Detect Moving Objects (Entire Set)");
    private final JButton detectSlowBatchButton = new JButton("Detect Slow Movers (Iterative)");

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
        JPanel topControlContainer = new JPanel();
        topControlContainer.setLayout(new BoxLayout(topControlContainer, BoxLayout.Y_AXIS));

        // --- ROW 1 ---
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        solveButton.setToolTipText("Solve current image");
        solveButton.setEnabled(false);
        row1.add(solveButton);

        JCheckBox astapSolveCheckbox = new JCheckBox("ASTAP");
        astapSolveCheckbox.setToolTipText("Solve the image using ASTAP");
        astapSolveCheckbox.setSelected(true);
        row1.add(astapSolveCheckbox);

        JCheckBox astrometrynetSolveCheckbox = new JCheckBox("Astrometry.net (online)");
        astrometrynetSolveCheckbox.setToolTipText("solve the image using the online nova.astrometry.net web services");
        row1.add(astrometrynetSolveCheckbox);

        showSolvedImageButton.setToolTipText("Show solved image");
        showSolvedImageButton.setEnabled(false);
        row1.add(showSolvedImageButton);

        blinkButton.setToolTipText("Blink 3 or more images");
        blinkButton.setEnabled(false);
        row1.add(blinkButton);

        // --- ROW 2 ---
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));

        convertMonoButton.setToolTipText("Convert all images to monochrome and save them. If stretch is checked, also stretch them.");
        convertMonoButton.setEnabled(false);
        row2.add(convertMonoButton);

        stretchButton.setToolTipText("Stretch all images (color or mono) as specified and save them");
        stretchButton.setEnabled(false);
        row2.add(stretchButton);

        detectSingleButton.setToolTipText("Detect objects only in the currently selected monochrome image");
        detectSingleButton.setEnabled(false);
        row2.add(detectSingleButton);

        detectBatchButton.setToolTipText("Detect objects in all imported monochrome images");
        detectBatchButton.setEnabled(false);
        row2.add(detectBatchButton);

        // Add the new button to the UI
        detectSlowBatchButton.setToolTipText("Runs multiple passes with maximally spaced frames to find extremely slow-moving objects.");
        detectSlowBatchButton.setEnabled(false);
        row2.add(detectSlowBatchButton);

        progressBar.setEnabled(true);
        progressBar.setPreferredSize(new Dimension(150, 20));
        row2.add(Box.createHorizontalStrut(10));
        row2.add(progressBar);

        topControlContainer.add(row1);
        topControlContainer.add(row2);
        add(topControlContainer, BorderLayout.NORTH);

        // ==========================================
        // ACTION LISTENERS
        // ==========================================

        showSolvedImageButton.addActionListener(e -> {
            FitsFileInformation imageInfo = getSelectedFileInformation();
            if (imageInfo != null) {
                PlateSolveResult result = imageInfo.getSolveResult();
                if (result != null && result.isSuccess()) {
                    String annotatedImageLink = result.getSolveInformation().get("annotated_image_link");
                    URL annotatedImageURL = null;

                    switch (result.getSolveInformation().get("source")) {
                        case "astrometry.net": {
                            try {
                                annotatedImageURL = new URL(annotatedImageLink);
                            } catch (MalformedURLException ex) {
                                JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot understand URL :" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                return;
                            }
                            break;
                        }
                        case "astap": {
                            try {
                                annotatedImageURL = new File(annotatedImageLink).toURI().toURL();
                            } catch (MalformedURLException ex) {
                                JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot understand URL :" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                                return;
                            }
                            break;
                        }
                        default: {
                            JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot understand solve source :" + imageInfo, "Error", JOptionPane.ERROR_MESSAGE);
                            return;
                        }
                    }

                    ApplicationWindow.logger.info("loading image: " + annotatedImageURL.toString());
                    try {
                        BufferedImage image = ImageIO.read(annotatedImageURL);
                        if (image == null) {
                            throw new IOException("cannot show image: " + annotatedImageURL.toString());
                        }
                        JLabel label = new JLabel(new ImageIcon(image));
                        JFrame f = new JFrame("Solved Image Preview");
                        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                        JScrollPane scrollPane = new JScrollPane(label);
                        
                        // Constrain the window size so massive images don't take over the screen
                        int width = Math.min(image.getWidth(), 1024);
                        int height = Math.min(image.getHeight(), 768);
                        scrollPane.setPreferredSize(new Dimension(width, height));
                        
                        f.getContentPane().add(scrollPane);
                        f.pack();
                        f.setLocationRelativeTo(MainApplicationPanel.this);
                        f.setVisible(true);
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot show image :" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                    }

                } else {
                    JOptionPane.showMessageDialog(MainApplicationPanel.this, "Image not solved :" + imageInfo, "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
        });

        convertMonoButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            boolean stretchEnabled = mainAppWindow.getStretchPanel().isStretchEnabled();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            new Thread(new BatchConvertMonoTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    stretchEnabled,
                    stretchFactor,
                    iterations,
                    algo
            )).start();
        });

        stretchButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            new Thread(new BatchStretchTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    stretchFactor,
                    iterations,
                    algo
            )).start();
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

        detectBatchButton.addActionListener(e -> {
            new Thread(new DetectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    null,
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig()
            )).start();
        });

        // Wire up the new button to the new task
        detectSlowBatchButton.addActionListener(e -> {
            new Thread(new IterativeDetectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImageProcessing(),
                    mainAppWindow.getDetectionConfigurationPanel().getJTransientConfig()
            )).start();
        });

        astapSolveCheckbox.addActionListener(e -> {
            if (astapSolveCheckbox.isSelected()) {
                astrometrynetSolveCheckbox.setSelected(false);
            }
        });

        astrometrynetSolveCheckbox.addActionListener(e -> {
            if (astrometrynetSolveCheckbox.isSelected()) {
                astapSolveCheckbox.setSelected(false);
            }
        });

        // ==========================================
        // MAIN TABLE AREA
        // ==========================================
        JScrollPane scrollPane = new JScrollPane();
        add(scrollPane, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusBar.setBorder(BorderFactory.createEtchedBorder());
        statusBar.add(statusLabel);
        add(statusBar, BorderLayout.SOUTH);

        table = new JTable();
        scrollPane.setViewportView(table);

        table.getSelectionModel().addListSelectionListener(event -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && table.getValueAt(selectedRow, FitsFileTableModel.COL_FILENAME) != null) {

                FitsFileInformation fileInfo = getSelectedFileInformation();
                if (fileInfo != null) {
                    boolean isSolved = "Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED)));
                    solveButton.setEnabled(!isSolved);

                    PlateSolveResult result = fileInfo.getSolveResult();
                    showSolvedImageButton.setEnabled(result != null && result.isSuccess());
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
                    astapSolveCheckbox.isSelected(),
                    astrometrynetSolveCheckbox.isSelected()
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
    }

    public void setTableModel(AbstractTableModel tableModel) {
        table.setModel(tableModel);
        resizeTableColumns(table);
        containsColorImages = false;
        FitsFileTableModel model = (FitsFileTableModel) tableModel;

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

        mainAppWindow.setMenuState(true);
        mainAppWindow.getTabbedPane().setEnabledAt(1, true);
        mainAppWindow.getTabbedPane().setEnabledAt(2, true);
        mainAppWindow.getTabbedPane().setEnabledAt(3, true);
    }

    private void lockUI() {
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
        this.detectBatchButton.setEnabled(true);
        this.detectSlowBatchButton.setEnabled(true);
    }

    public void setDetectionButtonsDisabled() {
        this.detectSingleButton.setEnabled(false);
        this.detectBatchButton.setEnabled(false);
        this.detectSlowBatchButton.setEnabled(false);
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
        });
    }

    @Subscribe
    public void onSolveFinished(SolveFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            PlateSolveResult result = event.getResult();

            if (result != null && result.isSuccess()) {
                ApplicationWindow.logger.info(result.toString());
                statusLabel.setText("Image was successfully plate-solved");

                int choice = JOptionPane.showConfirmDialog(this,
                        "Image was successfully plate-solved.\n\nWould you like to write the WCS coordinate solution directly into the FITS header?\n(This permanently modifies the file)",
                        "Update FITS Header",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    FitsFileInformation fileInfo = ((FitsFileTableModel) table.getModel()).getFitsFileAt(event.getRowIndex());
                    if (fileInfo != null) {
                        try {
                            mainAppWindow.getImageProcessing().updateFitsHeaderWithWCS(fileInfo.getFilePath(), result.getSolveInformation());
                            // Apply the WCS data to the memory cache so internal table logic registers it immediately
                            fileInfo.getFitsHeader().putAll(result.getSolveInformation());
                            JOptionPane.showMessageDialog(this, "FITS header successfully updated.", "Success", JOptionPane.INFORMATION_MESSAGE);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(this, "Failed to update FITS header: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }

                // Bypass JTable's edit-check by updating the Model directly
                table.getModel().setValueAt(result, event.getRowIndex(), FitsFileTableModel.COL_SOLVED);
            } else {
                String errorMsg = (result != null) ? (result.getFailureReason() + " " + result.getWarning()) : "Internal Error";
                JOptionPane.showMessageDialog(this, "Image was not plate-solved successfully: " + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
            }

            setProgressBarIdle();
            enableControlsSolvingFinished();

            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                FitsFileInformation fileInfo = getSelectedFileInformation();
                if (fileInfo != null) {
                    boolean isSolved = "Yes".equalsIgnoreCase(String.valueOf(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED)));
                    solveButton.setEnabled(!isSolved);

                    PlateSolveResult res = fileInfo.getSolveResult();
                    showSolvedImageButton.setEnabled(res != null && res.isSuccess());
                }
            }
        });
    }

    @Subscribe
    public void onLoadSolvedImageStarted(LoadSolvedImageStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();
        });
    }

    @Subscribe
    public void onLoadSolvedImageFinished(LoadSolvedImageFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarIdle();
            enableControlsProcessingFinished();

            if (event.isSuccess()) {
                BufferedImage image = event.getImage();
                JLabel label = new JLabel(new ImageIcon(image));
                JFrame f = new JFrame("Solved Image Preview");
                f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                JScrollPane scrollPane = new JScrollPane(label);
                
                // Constrain the window size so massive images don't take over the screen
                int width = Math.min(image.getWidth(), 1024);
                int height = Math.min(image.getHeight(), 768);
                scrollPane.setPreferredSize(new Dimension(width, height));
                
                f.getContentPane().add(scrollPane);
                f.pack();
                f.setLocationRelativeTo(this);
                f.setVisible(true);
                statusLabel.setText("Showing solved image preview");
            } else {
                JOptionPane.showMessageDialog(this, "Cannot show image: " + event.getErrorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    @Subscribe
    public void onBatchConvertStarted(BatchConvertStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();
            statusLabel.setText("Batch conversion started");
        });
    }

    @Subscribe
    public void onBatchConvertFinished(BatchConvertFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarIdle();
            enableControlsProcessingFinished();

            if (event.isSuccess()) {
                JOptionPane.showMessageDialog(this,
                        "Batch conversion to mono completed successfully.\n" +
                                "Please import the newly created directory to use them.",
                        "Conversion Complete",
                        JOptionPane.INFORMATION_MESSAGE);
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
        });
    }

    @Subscribe
    public void onBatchStretchFinished(BatchStretchFinishedEvent event) {
        EventQueue.invokeLater(() -> {
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