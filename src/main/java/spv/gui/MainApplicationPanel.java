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
import org.apache.commons.configuration2.ex.ConfigurationException;
import spv.util.*;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.List;

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

    /**
     * Create the panel.
     */
    public MainApplicationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;

        setLayout(new BorderLayout(0, 0));

        JPanel panel = new JPanel();
        add(panel, BorderLayout.NORTH);
        panel.setLayout(new FlowLayout(FlowLayout.CENTER, 5, 5));

        solveButton.setToolTipText("Solve current image");
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
            if (table.getValueAt(table.getSelectedRow(), 6) != null) {
                //check how the image was solved
                FitsFileInformation imageInfo = (FitsFileInformation) table.getValueAt(table.getSelectedRow(), 6);
                PlateSolveResult result = imageInfo.getSolveResult();
                if (result.isSuccess()) {
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
            setProgressBarWorking();
            disableControlsProcessing();
            new Thread(() -> {
                ApplicationWindow.logger.info("Will batch convert all images to mono");
                try {
                    int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
                    int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
                    boolean stretchEnabled = mainAppWindow.getStretchPanel().isStretchEnabled();
                    StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

                    // Passes the stretchEnabled flag so your PreProcessor knows whether to stretch during conversion!
                    mainAppWindow.getImagePreProcessing().batchConvertToMono(stretchEnabled, stretchFactor, iterations, algo);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot convert images :" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                }
                EventQueue.invokeLater(() -> {
                    setProgressBarIdle();
                    enableControlsProcessingFinished();
                });
            }).start();
        });
        panel.add(convertMonoButton);

        stretchButton.setToolTipText("Stretch all images (color or mono) as specified and save them");
        stretchButton.setEnabled(false);
        stretchButton.addActionListener(e -> {
            setProgressBarWorking();
            disableControlsProcessing();
            new Thread(() -> {
                ApplicationWindow.logger.info("Will stretch all images (color or mono)");
                try {
                    int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
                    int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
                    StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

                    mainAppWindow.getImagePreProcessing().batchStretch(stretchFactor, iterations, algo);
                } catch (Exception ex) {
                    ex.printStackTrace();
                    EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot stretch images :" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                }
                EventQueue.invokeLater(() -> {
                    setProgressBarIdle();
                    enableControlsProcessingFinished();
                });
            }).start();
        });
        panel.add(stretchButton);

        blinkButton.setToolTipText("Blink 3 or more images");
        blinkButton.setEnabled(false);
        blinkButton.addActionListener(e -> {
            disableControlsBlinking();
            mainAppWindow.getFullImagePreviewFrame().setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            new Thread(() -> {
                if (blinkButton.getText().equals("blink")) {
                    EventQueue.invokeLater(() -> {
                        blinkButton.setText("stop blinking");
                        mainAppWindow.getFullImagePreviewFrame().setVisible(true);
                    });

                    FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
                    BufferedImage[] images = new BufferedImage[selectedFitsFilesInfo.length];

                    if (selectedFitsFilesInfo != null) {
                        int i = 0;
                        for (FitsFileInformation selectedFitsFileInfo : selectedFitsFilesInfo) {
                            Fits selectedFitsImage;
                            try {
                                selectedFitsImage = new Fits(selectedFitsFileInfo.getFilePath());
                                Object kernelData = selectedFitsImage.getHDU(0).getKernel();
                                StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

                                images[i] = mainAppWindow.getImagePreProcessing().getStretchedImageFullSize(kernelData,
                                        selectedFitsFileInfo.getSizeWidth(),
                                        selectedFitsFileInfo.getSizeHeight(),
                                        mainAppWindow.getStretchPanel().getStretchSlider().getValue(),
                                        mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue(), algo);

                            } catch (FitsException | IOException ex) {
                                ex.printStackTrace();
                                EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(mainAppWindow.getFullImagePreviewFrame(),
                                        "Cannot show full image:" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
                                return;
                            }
                            i++;
                        }

                        int j = 0;
                        while (true) {
                            BufferedImage image = images[j];
                            try { Thread.sleep(500); } catch (InterruptedException ex) { ex.printStackTrace(); }

                            EventQueue.invokeLater(() -> mainAppWindow.getFullImagePreviewFrame().setImage(image));

                            j++;
                            if (j == images.length) { j = 0; }
                            if (blinkButton.getText().equals("blink")) {
                                break;
                            }
                        }
                    }
                } else {
                    EventQueue.invokeLater(() -> {
                        blinkButton.setText("blink");
                        mainAppWindow.getFullImagePreviewFrame().setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
                        mainAppWindow.getFullImagePreviewFrame().setVisible(false);
                        enableControlsProcessingBlinkingFinished();
                    });
                }
            }).start();
        });
        panel.add(blinkButton);

        detectButton.setToolTipText("Detect objects in images (must be monochrome images)");
        detectButton.setEnabled(false);
        detectButton.addActionListener(e -> {
            try {
                FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
                if (selectedFitsFilesInfo != null && selectedFitsFilesInfo.length > 0) {
                    FitsFileInformation aSelectedFile = selectedFitsFilesInfo[0];
                    nom.tam.fits.Fits selectedFitsImage = new nom.tam.fits.Fits(aSelectedFile.getFilePath());

                    Object kernelData = selectedFitsImage.getHDU(0).getKernel();
                    if (kernelData instanceof short[][]) {
                        short[][] imageData = (short[][])kernelData;

                        short[][] debugImage = new short[imageData.length][imageData[0].length];
                        for(int x = 0; x < imageData.length; x++) {
                            System.arraycopy(imageData[x], 0, debugImage[x], 0, imageData[x].length);
                        }

                        List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                                debugImage,
                                SourceExtractor.detectionSigmaMultiplier,
                                SourceExtractor.minDetectionPixels
                        );

                        int streakCount = 0;
                        int starCount = 0;

                        for (SourceExtractor.DetectedObject obj : objects) {
                            if (obj.isStreak) {
                                streakCount++;
                            } else {
                                starCount++;
                            }
                        }

                        RawImageAnnotator.drawDetections(debugImage, objects);
                        BufferedImage finalImageToDisplay = ImageDisplayUtils.createDisplayImage(debugImage);

                        mainAppWindow.getFullImagePreviewFrame().setTitle(
                                "Preview - " + aSelectedFile.getFileName() +
                                        " | Stars: " + starCount +
                                        " | Streaks: " + streakCount
                        );

                        mainAppWindow.getFullImagePreviewFrame().setImage(finalImageToDisplay);
                        mainAppWindow.getFullImagePreviewFrame().setVisible(true);

                        ImageDisplayUtils.analyzeFitsData(imageData);

                        JOptionPane.showMessageDialog(
                                mainAppWindow.getFullImagePreviewFrame(),
                                "Quick Detection Complete!\n\n" +
                                        "Stars/Point Sources: " + starCount + "\n" +
                                        "Streaks Detected: " + streakCount,
                                "Detection Summary",
                                JOptionPane.INFORMATION_MESSAGE
                        );

                    } else {
                        throw new IOException("Cannot understand FITS format: expected short[][], got " + kernelData.getClass().toString());
                    }

                } else {
                    mainAppWindow.getImagePreProcessing().detectObjects();
                }
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        panel.add(detectButton);

        panel.add(progressBar);
        progressBar.setEnabled(true);

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

        table = new JTable();
        scrollPane.setViewportView(table);

        table.getSelectionModel().addListSelectionListener(event -> {
            if (table.getValueAt(table.getSelectedRow(), 0) != null) {

                if (table.getValueAt(table.getSelectedRow(), 5).equals("yes")) {
                    showSolvedImageButton.setEnabled(true);
                    solveButton.setEnabled(false);
                } else {
                    showSolvedImageButton.setEnabled(false);
                    solveButton.setEnabled(true);
                }

                if (mainAppWindow.getStretchPanel().isStretchEnabled()) {
                    mainAppWindow.setStretchFrameVisible(true);
                    try {
                        updateImageStretchWindow();
                    } catch (FitsException | IOException e) {
                        e.printStackTrace();
                    }
                }
            } else {
                mainAppWindow.setStretchFrameVisible(false);
            }

            FitsFileInformation[] selectedFitsFilesInfo = mainAppWindow.getSelectedFiles();
            if (selectedFitsFilesInfo != null) {
                if (selectedFitsFilesInfo.length >= 3) {
                    blinkButton.setEnabled(true);
                } else {
                    blinkButton.setEnabled(false);
                }
            } else {
                blinkButton.setEnabled(false);
            }
        });

        solveButton.addActionListener(e -> {
            ApplicationWindow.logger.info("Will try to solve image");
            int row = table.getSelectedRow();
            if (row < 0) return;

            FitsFileInformation seletedFile = (FitsFileInformation) table.getValueAt(row, 6);
            setProgressBarWorking();
            disableControlsSolving();

            new Thread(() -> {
                try {
                    Thread.sleep(500);
                    Future<PlateSolveResult> solveResult = mainAppWindow.getImagePreProcessing().solve(seletedFile.getFilePath(), astapSolveCheckbox.isSelected(), astrometrynetSolveCheckbox.isSelected());
                    if (solveResult != null) {
                        final PlateSolveResult result = solveResult.get();

                        if (result.isSuccess()) {
                            EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(MainApplicationPanel.this, "Image was succesfully plate-solved"));
                            mainAppWindow.getImagePreProcessing().writeSolveResults(seletedFile.getFilePath(), result);
                        } else {
                            EventQueue.invokeLater(() -> JOptionPane.showMessageDialog(MainApplicationPanel.this, "Image was not plate-solved sccesfully:" + result.getFailureReason() + " " + result.getWarning()));
                        }
                        ApplicationWindow.logger.info(result.toString());
                        EventQueue.invokeLater(() -> {
                            table.setValueAt(result, row, 5);
                            ((FitsFileTableModel) table.getModel()).fireTableDataChanged();
                            setProgressBarIdle();
                            enableControlsSolvingFinished();
                        });
                    }
                } catch (InterruptedException | ExecutionException | FitsException | IOException |
                         ConfigurationException ex) {
                    EventQueue.invokeLater(() -> {
                        JOptionPane.showMessageDialog(MainApplicationPanel.this, "Cannot solve image:" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                        setProgressBarIdle();
                        enableControlsSolvingFinished();
                    });
                }
            }).start();
        });
    }

    public void setTableModel(AbstractTableModel tableModel) {
        table.setModel(tableModel);
        table.getColumnModel().getColumn(6).setMinWidth(0);
        table.getColumnModel().getColumn(6).setMaxWidth(0);
        table.getColumnModel().getColumn(6).setWidth(0);

        // --- NEW: AUTO-EVALUATE LOADED IMAGES FOR COLOR ---
        FitsFileTableModel model = (FitsFileTableModel) tableModel;
        boolean containsColorImages = false;

        // The FitsFileTableModel stores its data internally.
        // We can check row 0, column 6 to get the FitsFileInformation object for each row.
        for (int i = 0; i < model.getRowCount(); i++) {
            FitsFileInformation fileInfo = (FitsFileInformation) model.getValueAt(i, 6);
            if (fileInfo != null && !fileInfo.isMonochrome()) {
                containsColorImages = true;
                break; // Found one color image, stop checking
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
        return (FitsFileInformation) table.getValueAt(row, 6);
    }

    public FitsFileInformation[] getSelectedFilesInformation() {
        int[] selected_rows = table.getSelectedRows();
        if (selected_rows != null && selected_rows.length > 0) {
            FitsFileInformation[] ret = new FitsFileInformation[selected_rows.length];
            for (int i = 0; i < selected_rows.length; i++) {
                ret[i] = (FitsFileInformation) table.getValueAt(selected_rows[i], 6);
            }
            return ret;
        }
        return null;
    }

    public void updateImageStretchWindow() throws FitsException, IOException {
        int stretchFactor = mainAppWindow.getStretchPanel().getStretchSlider().getValue();
        int iterations = mainAppWindow.getStretchPanel().getStretchIterationsSlider().getValue();
        StretchAlgorithm algo = mainAppWindow.getStretchPanel().getStretchAlgorithm();

        FitsFileInformation selectedFitsFileInfo = getSelectedFileInformation();
        if (selectedFitsFileInfo != null) {
            Fits selectedFitsImage = new Fits(selectedFitsFileInfo.getFilePath());
            Object kernelData = selectedFitsImage.getHDU(0).getKernel();

            BufferedImage fitsImagePreview = mainAppWindow.getImagePreProcessing().getImagePreview(kernelData);
            BufferedImage fitsImagePreviewStretch = mainAppWindow.getImagePreProcessing().getStretchedImagePreview(kernelData, stretchFactor, iterations, algo);

            mainAppWindow.setOriginalImage(fitsImagePreview);
            mainAppWindow.setStretchedImage(fitsImagePreviewStretch);
            if (mainAppWindow.getFullImagePreviewFrame().isVisible()) {
                BufferedImage fitsImagePreviewFS = mainAppWindow.getImagePreProcessing().getStretchedImageFullSize(kernelData, selectedFitsFileInfo.getSizeWidth(),
                        selectedFitsFileInfo.getSizeHeight(), stretchFactor, iterations, algo);
                mainAppWindow.getFullImagePreviewFrame().setImage(fitsImagePreviewFS);
            }
            selectedFitsImage.close();
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
        convertMonoButton.setEnabled(state);
    }

    private void disableControlsSolving() {
        this.blinkButton.setEnabled(false);
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

    private void enableControlsSolvingFinished() {
        this.blinkButton.setEnabled(false);
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

    private void disableControlsProcessing() {
        this.blinkButton.setEnabled(false);
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

    private void enableControlsProcessingFinished() {
        this.blinkButton.setEnabled(false);
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
}