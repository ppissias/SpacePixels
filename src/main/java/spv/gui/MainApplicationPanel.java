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

import io.github.ppissias.astrolib.PlateSolveResult;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import spv.events.*;
import spv.tasks.BatchConvertMonoTask;
import spv.tasks.BatchStretchTask;
import spv.tasks.DetectionTask;
import spv.tasks.PlateSolveTask;
import spv.util.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.logging.Level;

import com.google.common.eventbus.Subscribe;

public class MainApplicationPanel extends JPanel {

    //link to main window
    private ApplicationWindow mainAppWindow;

    //the table
    private volatile JTable table;

    private final JProgressBar progressBar = new JProgressBar();

    private final JButton convertMonoButton = new JButton("batch convert to mono");

    private final JButton stretchButton = new JButton("batch stretch");

    private final JButton blinkButton = new JButton("blink");

    private final JButton showSolvedImageButton = new JButton("show solved image");

    private final JButton solveButton = new JButton("Solve");

    private final JButton detectButton = new JButton("Detect Objects");

    private final JLabel statusLabel = new JLabel(" Ready");

    private volatile boolean containsColorImages = true;

    // 1. Add this variable to the top of MainApplicationPanel
    private volatile boolean isBlinking = false;

    /**
     * Create the panel.
     */
    public MainApplicationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;

        // Register this panel to listen for events
        this.mainAppWindow.getEventBus().register(this);

        setLayout(new BorderLayout(0, 0));

        JPanel panel = new JPanel();
        add(panel, BorderLayout.NORTH);
        panel.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));

        solveButton.setToolTipText("Solve current image");
        solveButton.setEnabled(false);
        panel.add(solveButton);

        JCheckBox astapSolveCheckbox = new JCheckBox("ASTAP");
        astapSolveCheckbox.setToolTipText("Solve the image using ASTAP");
        astapSolveCheckbox.setSelected(true);
        panel.add(astapSolveCheckbox);

        JCheckBox astrometrynetSolveCheckbox = new JCheckBox("Astrometry.net (online)");
        astrometrynetSolveCheckbox.setToolTipText("solve the image using the online nova.astrometry.net web services");
        panel.add(astrometrynetSolveCheckbox);

        showSolvedImageButton.setToolTipText("Show solved image");
        showSolvedImageButton.setEnabled(false);
        showSolvedImageButton.addActionListener(e -> {
            FitsFileInformation imageInfo = getSelectedFileInformation();
            if (imageInfo != null) {
                //check how the image was solved
                PlateSolveResult result = imageInfo.getSolveResult();
                if (result != null && result.isSuccess()) {
                    //sanity check
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

                    //load image
                    ApplicationWindow.logger.info("loading image: " + annotatedImageURL.toString());
                    try {
                        BufferedImage image = ImageIO.read(annotatedImageURL);
                        if (image == null) {
                            throw new IOException("cannot show image: " + annotatedImageURL.toString());
                        }
                        JLabel label = new JLabel(new ImageIcon(image));
                        JFrame f = new JFrame();
                        f.getContentPane().add(label);
                        f.pack();
                        f.setLocation(200, 200);
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
        panel.add(showSolvedImageButton);

        convertMonoButton.setToolTipText("Convert all images to monochrome and save them. If stretch is checked, also stretch them.");
        convertMonoButton.setEnabled(false);
        convertMonoButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            boolean stretchEnabled = mainAppWindow.getStretchPanel().isStretchEnabled();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            // Fire and forget
            new Thread(new BatchConvertMonoTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImagePreProcessing(),
                    stretchEnabled,
                    stretchFactor,
                    iterations,
                    algo
            )).start();
        });

        stretchButton.setToolTipText("Stretch all images (color or mono) as specified and save them");
        stretchButton.setEnabled(false);
        stretchButton.addActionListener(e -> {
            int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
            int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

            // Fire and forget
            new Thread(new BatchStretchTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImagePreProcessing(),
                    stretchFactor,
                    iterations,
                    algo
            )).start();
        });
        panel.add(stretchButton);

        blinkButton.setToolTipText("Blink 3 or more images");
        blinkButton.setEnabled(false);
        blinkButton.addActionListener(e -> {
            if (!isBlinking) {
                // START BLINKING
                isBlinking = true;
                disableControlsBlinking();
                blinkButton.setText("stop blinking");

                // We don't change default close operations anymore, the frame handles itself
                mainAppWindow.getFullImagePreviewFrame().setVisible(true);

                new Thread(() -> {
                    try {
                        FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
                        if (selectedFitsFilesInfo == null || selectedFitsFilesInfo.length == 0) {
                            stopBlinkingProcess(); // abort if nothing selected
                            return;
                        }

                        BufferedImage[] images = new BufferedImage[selectedFitsFilesInfo.length];

                        // Load and stretch the images
                        for (int i = 0; i < selectedFitsFilesInfo.length; i++) {
                            Fits selectedFitsImage = new Fits(selectedFitsFilesInfo[i].getFilePath());
                            Object kernelData = selectedFitsImage.getHDU(0).getKernel();
                            StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

                            images[i] = mainAppWindow.getImagePreProcessing().getStretchedImageFullSize(
                                    kernelData,
                                    selectedFitsFilesInfo[i].getSizeWidth(),
                                    selectedFitsFilesInfo[i].getSizeHeight(),
                                    mainAppWindow.getStretchPanel().getStretchSlider().getValue(),
                                    mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue(), algo);
                        }

                        // The Animation Loop
                        int j = 0;
                        while (isBlinking) { // Controlled by the boolean flag now
                            BufferedImage image = images[j];
                            EventQueue.invokeLater(() -> mainAppWindow.getFullImagePreviewFrame().setImage(image));

                            Thread.sleep(500);

                            j++;
                            if (j == images.length) { j = 0; }
                        }

                    } catch (FitsException | IOException | InterruptedException ex) {
                        ApplicationWindow.logger.log(Level.SEVERE, "Error during blinking", ex);
                        EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(mainAppWindow.getFullImagePreviewFrame(),
                                "Cannot show full image:" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                        stopBlinkingProcess();
                    }
                }).start();

            } else {
                // USER CLICKED "STOP BLINKING"
                stopBlinkingProcess();
                // We programmatically close the frame if they clicked the button
                mainAppWindow.getFullImagePreviewFrame().dispose();
            }
        });
        panel.add(blinkButton);

        detectButton.setToolTipText("Detect objects in images (must be monochrome images)");
        detectButton.setEnabled(false);
        detectButton.addActionListener(e -> {
            FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();

            // --- NEW LOGIC: Check for multiple selections ---
            if (selectedFitsFilesInfo != null && selectedFitsFilesInfo.length > 1) {
                int userChoice = JOptionPane.showConfirmDialog(this,
                        "Multiple files are selected. Quick Detection will only be shown for the first file:\n" +
                                selectedFitsFilesInfo[0].getFileName() + "\n\nDo you want to proceed?",
                        "Multiple Files Selected",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.WARNING_MESSAGE);

                // If the user clicks Cancel or closes the dialog, abort the action completely
                if (userChoice != JOptionPane.OK_OPTION) {
                    return;
                }
            }
            // Launch the background task
            new Thread(new DetectionTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImagePreProcessing(),
                    selectedFitsFilesInfo
            )).start();
        });

        panel.add(detectButton);

        panel.add(progressBar);
        progressBar.setEnabled(true);
        //progressBar.setString("Ready");     // Default idle text


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

        JScrollPane scrollPane = new JScrollPane();
        add(scrollPane, BorderLayout.CENTER);

        // 2. Add this right before or after you setup the JScrollPane for the table
        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusBar.setBorder(BorderFactory.createEtchedBorder()); // Gives it a nice recessed look
        statusBar.add(statusLabel);

        // Add it to the bottom of the MainApplicationPanel
        add(statusBar, BorderLayout.SOUTH);

        table = new JTable();
        scrollPane.setViewportView(table);

        table.getSelectionModel().addListSelectionListener(event -> {
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0 && table.getValueAt(selectedRow, FitsFileTableModel.COL_FILENAME) != null) {

                if ("yes".equals(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED))) {
                    showSolvedImageButton.setEnabled(true);
                    solveButton.setEnabled(false);
                } else {
                    showSolvedImageButton.setEnabled(false);
                    solveButton.setEnabled(true);
                }

                // If the stretch panel is active, tell it to update its preview images
                if (mainAppWindow.getStretchPanel().isStretchEnabled()) {
                    mainAppWindow.getStretchPanel().triggerPreviewUpdate();
                }
            } else {
                // If the stretch panel is active but no row is selected, clear the preview
                if (mainAppWindow.getStretchPanel().isStretchEnabled()) {
                    mainAppWindow.getStretchPanel().triggerPreviewUpdate();
                }
            }

            FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
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

            // Fire and forget! The task will handle everything else.
            new Thread(new PlateSolveTask(
                    mainAppWindow.getEventBus(),
                    mainAppWindow.getImagePreProcessing(),
                    selectedFile.getFilePath(),
                    row,
                    astapSolveCheckbox.isSelected(),
                    astrometrynetSolveCheckbox.isSelected()
            )).start();
        });
    }

    /**
     * Automatically resizes the columns of a JTable to fit their content and headers.
     */
    private void resizeTableColumns(JTable table) {
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // Prevents columns from snapping back to equal sizes

        for (int column = 0; column < table.getColumnCount(); column++) {
            int width = 50; // Minimum width

            // 1. Check the width of the column header
            Component headerComp = table.getTableHeader().getDefaultRenderer().getTableCellRendererComponent(
                    table, table.getColumnModel().getColumn(column).getHeaderValue(), false, false, 0, column);
            width = Math.max(headerComp.getPreferredSize().width, width);

            // 2. Check the width of every row in this column
            for (int row = 0; row < table.getRowCount(); row++) {
                Component cellComp = table.prepareRenderer(table.getCellRenderer(row, column), row, column);
                width = Math.max(cellComp.getPreferredSize().width, width);
            }

            // 3. Set the width with a 15-pixel padding
            table.getColumnModel().getColumn(column).setPreferredWidth(width + 15);
        }
    }

    public void setTableModel(AbstractTableModel tableModel) {
        table.setModel(tableModel);

        // --- NEW: Resize the columns based on the newly loaded data ---
        resizeTableColumns(table);
        // Assume false initially, and only set to true if we find one
        containsColorImages = false;
        FitsFileTableModel model = (FitsFileTableModel) tableModel;

        for (int i = 0; i < model.getRowCount(); i++) {
            FitsFileInformation fileInfo = model.getFitsFileAt(i);

            if (fileInfo != null && !fileInfo.isMonochrome()) {
                containsColorImages = true;
                break;
            }
        }

        // Apply the UI Logic
        if (containsColorImages) {
            ApplicationWindow.logger.info("Color images detected. Enabling 'Batch Convert to Mono'.");
            convertMonoButton.setEnabled(true);
            detectButton.setEnabled(false); // Must convert before detecting
        } else {
            ApplicationWindow.logger.info("All images are Monochrome. Enabling 'Detect Objects'.");
            convertMonoButton.setEnabled(false); // No need to convert
            detectButton.setEnabled(true);
        }

        // The stretch button is always available once files are loaded
        //stretchButton.setEnabled(true);
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

    public void setProgressBarWorking() {
        progressBar.setIndeterminate(true);
    }

    public void setProgressBarIdle() {
        progressBar.setIndeterminate(false);
    }

    public void setBatchStretchButtonEnabled(boolean state) {
        stretchButton.setEnabled(state);
        convertMonoButton.setEnabled(state);
    }

    private void disableControlsSolving() {
        this.blinkButton.setEnabled(false);
        this.showSolvedImageButton.setEnabled(false);
        this.stretchButton.setEnabled(false);
        this.convertMonoButton.setEnabled(false);
        this.table.setEnabled(false);
        this.solveButton.setEnabled(false);
        this.detectButton.setEnabled(false);
        mainAppWindow.setMenuState(false);
        mainAppWindow.getTabbedPane().setEnabledAt(1, false);
        mainAppWindow.getTabbedPane().setEnabledAt(2, false);
        mainAppWindow.getTabbedPane().setEnabledAt(3, false);
    }

    private void enableControlsSolvingFinished() {
        this.blinkButton.setEnabled(false);
        this.showSolvedImageButton.setEnabled(false);
        this.stretchButton.setEnabled(true);
        this.convertMonoButton.setEnabled(true);
        this.table.setEnabled(true);
        this.solveButton.setEnabled(false);
        if (!containsColorImages) {
            this.detectButton.setEnabled(true);
        }
        mainAppWindow.setMenuState(true);
        mainAppWindow.getTabbedPane().setEnabledAt(1, true);
        mainAppWindow.getTabbedPane().setEnabledAt(2, true);
        mainAppWindow.getTabbedPane().setEnabledAt(3, true);
    }

    private void disableControlsProcessing() {
        this.blinkButton.setEnabled(false);
        this.showSolvedImageButton.setEnabled(false);
        this.stretchButton.setEnabled(false);
        this.convertMonoButton.setEnabled(false);
        this.table.setEnabled(false);
        this.solveButton.setEnabled(false);
        this.detectButton.setEnabled(false);
        mainAppWindow.setMenuState(false);
        mainAppWindow.getTabbedPane().setEnabledAt(1, false);
        mainAppWindow.getTabbedPane().setEnabledAt(2, false);
        mainAppWindow.getTabbedPane().setEnabledAt(3, false);
    }

    private void enableControlsProcessingFinished() {
        this.blinkButton.setEnabled(false);
        this.showSolvedImageButton.setEnabled(false);
        this.stretchButton.setEnabled(true);
        this.convertMonoButton.setEnabled(true);
        this.table.setEnabled(true);
        this.solveButton.setEnabled(false);
        if (!containsColorImages) {
            this.detectButton.setEnabled(true);
        }
        mainAppWindow.setMenuState(true);
        mainAppWindow.getTabbedPane().setEnabledAt(1, true);
        mainAppWindow.getTabbedPane().setEnabledAt(2, true);
        mainAppWindow.getTabbedPane().setEnabledAt(3, true);
    }

    private void disableControlsBlinking() {
        this.showSolvedImageButton.setEnabled(false);
        this.stretchButton.setEnabled(false);
        this.convertMonoButton.setEnabled(false);
        this.table.setEnabled(false);
        this.solveButton.setEnabled(false);
        mainAppWindow.setMenuState(false);
        mainAppWindow.getTabbedPane().setEnabledAt(1, false);
        mainAppWindow.getTabbedPane().setEnabledAt(2, false);
        mainAppWindow.getTabbedPane().setEnabledAt(3, false);
    }

    private void enableControlsProcessingBlinkingFinished() {
        this.showSolvedImageButton.setEnabled(false);
        this.stretchButton.setEnabled(true);
        this.convertMonoButton.setEnabled(true);
        this.table.setEnabled(true);
        this.solveButton.setEnabled(false);
        mainAppWindow.setMenuState(true);
        mainAppWindow.getTabbedPane().setEnabledAt(1, true);
        mainAppWindow.getTabbedPane().setEnabledAt(2, true);
        mainAppWindow.getTabbedPane().setEnabledAt(3, true);
    }

    public void setDetectionEnabled() {
        detectButton.setEnabled(true);
    }

    // 2. Add a helper method to handle the UI reset safely
    private void stopBlinkingProcess() {
        isBlinking = false; // This safely breaks the while loop in the thread
        EventQueue.invokeLater(() -> {
            blinkButton.setText("blink");
            enableControlsProcessingBlinkingFinished();
        });
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
                JOptionPane.showMessageDialog(this, "Image was successfully plate-solved");
                ApplicationWindow.logger.info(result.toString());
                statusLabel.setText("Image was successfully plate-solved");
                // Update the table using the constant
                table.setValueAt(result, event.getRowIndex(), FitsFileTableModel.COL_SOLVED);
                ((FitsFileTableModel) table.getModel()).fireTableDataChanged();
            } else {
                String errorMsg = (result != null) ? (result.getFailureReason() + " " + result.getWarning()) : "Internal Error";
                JOptionPane.showMessageDialog(this, "Image was not plate-solved successfully: " + errorMsg, "Error", JOptionPane.ERROR_MESSAGE);
            }

            // Restore UI state
            setProgressBarIdle();
            enableControlsSolvingFinished();

            // Re-evaluate button states in case the selection changed while processing
            int selectedRow = table.getSelectedRow();
            if (selectedRow >= 0) {
                if ("yes".equals(table.getValueAt(selectedRow, FitsFileTableModel.COL_SOLVED))) {
                    showSolvedImageButton.setEnabled(true);
                    solveButton.setEnabled(false);
                } else {
                    showSolvedImageButton.setEnabled(false);
                    solveButton.setEnabled(true);
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
                JLabel label = new JLabel(new ImageIcon(event.getImage()));
                JFrame f = new JFrame("Solved Image Preview");

                // CRITICAL: Ensure the frame is destroyed when closed, not just hidden
                f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

                // Add a scroll pane just in case the image is larger than the screen
                JScrollPane scrollPane = new JScrollPane(label);
                f.getContentPane().add(scrollPane);

                f.pack();

                // Center the new window relative to the main app instead of hardcoding 200,200
                f.setLocationRelativeTo(this);
                f.setVisible(true);
                statusLabel.setText("Showing solved image preview");
            } else {
                JOptionPane.showMessageDialog(this, "Cannot show image: " + event.getErrorMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }


    // 3. Add the Subscriber to catch the frame closing!
    @Subscribe
    public void onFullImageFrameClosed(FullImageViewFrameClosedEvent event) {
        // If the user clicked the "X" on the frame while blinking was active...
        if (isBlinking) {
            ApplicationWindow.logger.info("Full image frame closed by user, stopping blink animation.");
            stopBlinkingProcess();
        }
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

    @Subscribe
    public void onDetectionStarted(DetectionStartedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarWorking();
            disableControlsProcessing();
            statusLabel.setText("Object detection started");
        });
    }

    @Subscribe
    public void onDetectionFinished(DetectionFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            setProgressBarIdle();
            enableControlsProcessingFinished();

            if (event.isSuccess()) {
                if (event.isQuickDetection()) {
                    // Handle UI updates for Quick Detection
                    mainAppWindow.getFullImagePreviewFrame().setTitle(
                            "Preview - " + event.getFileName() +
                                    " | Stars: " + event.getStarCount() +
                                    " | Streaks: " + event.getStreakCount()
                    );
                    mainAppWindow.getFullImagePreviewFrame().setImage(event.getAnnotatedImage());
                    mainAppWindow.getFullImagePreviewFrame().setVisible(true);

                    JOptionPane.showMessageDialog(
                            mainAppWindow.getFullImagePreviewFrame(),
                            "Quick Detection Complete!\n\n" +
                                    "Stars/Point Sources: " + event.getStarCount() + "\n" +
                                    "Streaks Detected: " + event.getStreakCount(),
                            "Detection Summary",
                            JOptionPane.INFORMATION_MESSAGE
                    );
                } else {
                    // Handle UI updates for Batch Detection
                    JOptionPane.showMessageDialog(this,
                            "Batch object detection completed successfully.",
                            "Detection Complete",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Detection failed: " + event.getErrorMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
            }

            statusLabel.setText("Object detection completed");
        });
    }
}