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

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.FitsFileInformation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.IOException;

public class ConfigurationPanel extends JPanel {
    // link to main window
    private final ApplicationWindow mainAppWindow;

    private final JLabel astapPathLabel;
    private final JTextField focalLengthTextField;
    private final JTextField pixelSizeTextfield;
    private final JTextField observatoryCodeTextField;
    private final JTextField latTextField;
    private final JTextField longTextField;
    private final JTextField raTextfield;
    private final JTextField decTextField;

    /**
     * Create the panel.
     */
    public ConfigurationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 20, 20, 20));

        // ==========================================
        // MAIN CONTENT CONTAINER
        // ==========================================
        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));

        // --- SECTION 1: EXTERNAL TOOLS ---
        mainContent.add(createSectionHeader("External Tools"));

        astapPathLabel = new JLabel("Not set");
        astapPathLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton astapPathButton = new JButton("Browse...");

        // Changed to LEFT alignment
        JPanel astapControlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astapControlPanel.add(astapPathLabel);
        astapControlPanel.add(Box.createHorizontalStrut(10));
        astapControlPanel.add(astapPathButton);

        mainContent.add(createConfigRow(
                "ASTAP Executable Path",
                "The local path to the ASTAP solver executable used for plate solving.",
                astapControlPanel));

        // --- SECTION 2: DETECTION & ANNOTATION ---
        mainContent.add(createSectionHeader("Detection & Annotation Parameters (Used for object identification)"));

        observatoryCodeTextField = new JTextField();
        mainContent.add(createConfigRow(
                "IAU Observatory Code",
                "Optional 3-character observatory code for SkyBoT and other topocentric services. If not set, SpacePixels will fall back to geocenter-based lookups.",
                observatoryCodeTextField));

        latTextField = new JTextField();
        mainContent.add(createConfigRow(
                "Site Latitude (N)",
                "Observation site latitude for accurate celestial annotation.",
                latTextField));

        longTextField = new JTextField();
        mainContent.add(createConfigRow(
                "Site Longitude (E)",
                "Observation site longitude for accurate celestial annotation.",
                longTextField));

        // --- SECTION 3: SOLVING PARAMETERS ---
        mainContent.add(createSectionHeader("Solving Parameters (Near Solve) [not implemented yet]"));

        focalLengthTextField = new JTextField();
        mainContent.add(createConfigRow(
                "Telescope Focal Length",
                "Focal length of the telescope in millimeters.",
                focalLengthTextField));
        focalLengthTextField.setEnabled(false);

        pixelSizeTextfield = new JTextField();
        mainContent.add(createConfigRow(
                "Camera Pixel Size",
                "Pixel size of the sensor in microns (\u00B5m).",
                pixelSizeTextfield));
        pixelSizeTextfield.setEnabled(false);


        raTextfield = new JTextField();
        mainContent.add(createConfigRow(
                "Approximate Right Ascension (RA)",
                "Field coordinates at the center of the image (HH MM SS.xxx).",
                raTextfield));
        raTextfield.setEnabled(false);

        decTextField = new JTextField();
        mainContent.add(createConfigRow(
                "Approximate Declination (DEC)",
                "Field coordinates at the center of the image (+HH MM SS.xxx).",
                decTextField));
        decTextField.setEnabled(false);

        // Add the scrolling capability just in case the window is resized too small
        JScrollPane scrollPane = new JScrollPane(mainContent);
        scrollPane.setBorder(null); // Keep it clean
        scrollPane.getVerticalScrollBar().setUnitIncrement(16); // Smooth scrolling
        add(scrollPane, BorderLayout.CENTER);

        // ==========================================
        // BOTTOM ACTION BAR
        // ==========================================
        JPanel actionContainer = new JPanel(new BorderLayout());
        actionContainer.setBorder(new EmptyBorder(20, 0, 0, 0)); // Padding from the top

        JButton fitsDeduceButton = new JButton("Deduce from FITS header");
        fitsDeduceButton.setToolTipText("Try to deduce the solving parameters from the selected image FITS header");

        JButton saveConfigButton = new JButton("Save Configuration");
        saveConfigButton.setToolTipText("Saves current configuration to properties");

        actionContainer.add(fitsDeduceButton, BorderLayout.WEST);
        actionContainer.add(saveConfigButton, BorderLayout.EAST);

        add(actionContainer, BorderLayout.SOUTH);

        // ==========================================
        // LISTENERS
        // ==========================================
        astapPathButton.addActionListener(e -> {
            ApplicationWindow.logger.info("ASTAP settings");
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
            fc.setDialogTitle("ASTAP executable");

            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                File astapExecutableFilePath = fc.getSelectedFile();

                String[] cmdArray = { astapExecutableFilePath.getAbsolutePath(), "-h" };
                try {
                    Runtime.getRuntime().exec(cmdArray, null, astapExecutableFilePath.getParentFile());

                    mainAppWindow.getImageProcessing().getAppConfig().astapExecutablePath = astapExecutableFilePath.getAbsolutePath();
                    mainAppWindow.getImageProcessing().saveAppConfig();
                    astapPathLabel.setText(astapExecutableFilePath.getAbsolutePath());

                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this,
                            "Cannot execute ASTAP or save config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        fitsDeduceButton.addActionListener(e -> {
            FitsFileInformation selectedFile = mainAppWindow.getMainApplicationPanel().getSelectedFileInformation();

            if (selectedFile != null) {
                double pixelScaleX = 0;
                double pixelScaleY = 0;

                for (String key : selectedFile.getFitsHeader().keySet()) {
                    String value = selectedFile.getFitsHeader().get(key);
                    switch (key) {
                        case "XPIXSZ": pixelScaleX = Double.parseDouble(value); break;
                        case "YPIXSZ": pixelScaleY = Double.parseDouble(value); break;
                        case "FOCALLEN": focalLengthTextField.setText(value); break;
                        case "SITELAT": latTextField.setText(value); break;
                        case "SITELONG": longTextField.setText(value); break;
                        case "OBJCTRA": raTextfield.setText(value); break;
                        case "OBJCTDEC": decTextField.setText(value); break;
                    }
                }

                if (pixelScaleX != 0 && pixelScaleY != 0) {
                    pixelSizeTextfield.setText(String.valueOf((pixelScaleX + pixelScaleY) / 2));
                } else if (pixelScaleX != 0) {
                    pixelSizeTextfield.setText(String.valueOf(pixelScaleX));
                } else if (pixelScaleY != 0) {
                    pixelSizeTextfield.setText(String.valueOf(pixelScaleY));
                }
            } else {
                JOptionPane.showMessageDialog(this,
                        "Please select an imported FITS file in the Main tab to read its header.",
                        "No File Selected", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        saveConfigButton.addActionListener(e -> {
            try {
                AppConfig config = mainAppWindow.getImageProcessing().getAppConfig();
                config.observatoryCode = observatoryCodeTextField.getText();
                config.imageRA = raTextfield.getText();
                config.imageDEC = decTextField.getText();
                config.siteLat = latTextField.getText();
                config.siteLong = longTextField.getText();
                config.pixelSize = pixelSizeTextfield.getText();
                config.focalLength = focalLengthTextField.getText();
                mainAppWindow.getImageProcessing().saveAppConfig();

                JOptionPane.showMessageDialog(this,
                        "Configuration saved successfully.", "Success", JOptionPane.INFORMATION_MESSAGE);

            } catch (IOException ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(this,
                        "Cannot save configuration: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    public void refreshComponents() {
        if (mainAppWindow.getImageProcessing() != null) {
            AppConfig config = mainAppWindow.getImageProcessing().getAppConfig();
            String astap = config.astapExecutablePath;
            astapPathLabel.setText(astap == null || astap.isEmpty() ? "Not set" : astap);
            observatoryCodeTextField.setText(config.observatoryCode);
            raTextfield.setText(config.imageRA);
            decTextField.setText(config.imageDEC);
            latTextField.setText(config.siteLat);
            longTextField.setText(config.siteLong);
            pixelSizeTextfield.setText(config.pixelSize);
            focalLengthTextField.setText(config.focalLength);
        }
    }

    // ==========================================
    // UI HELPER METHODS
    // ==========================================

    /**
     * Creates a styled section header matching the FlatLaf Accent color.
     */
    private JLabel createSectionHeader(String title) {
        JLabel headerLabel = new JLabel(title);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 16f));

        Color accentColor = UIManager.getColor("Component.accentColor");
        if (accentColor == null) {
            accentColor = Color.decode("#4285f4");
        }
        headerLabel.setForeground(accentColor);
        headerLabel.setBorder(new EmptyBorder(20, 0, 10, 0));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return headerLabel;
    }

// =========================================================================
    // UI LAYOUT HELPERS (Standardized across the app)
    // =========================================================================

    private JPanel createConfigRow(String title, String description, JComponent inputControl) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(new EmptyBorder(5, 0, 15, 0));

        // Left side: Text (Title + Description)
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Wrapping the description in HTML enables automatic word-wrapping in Swing
        JLabel descLabel = new JLabel("<html>" + description + "</html>");
        descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 12f));
        descLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(3)); // Small gap between title and desc
        textPanel.add(descLabel);

        // Lock the width so inputs align vertically, but give it enough height (55) for 2 lines of text
        Dimension textDim = new Dimension(450, 55);
        textPanel.setPreferredSize(textDim);
        textPanel.setMinimumSize(textDim);
        textPanel.setMaximumSize(textDim);

        // Right side: Input Control
        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        if (inputControl instanceof JTextField) {
            inputControl.setPreferredSize(new Dimension(150, 26));
        } else if (inputControl instanceof JSpinner) {
            inputControl.setPreferredSize(new Dimension(80, 26));
        }
        inputWrapper.add(inputControl);

        row.add(textPanel);
        row.add(Box.createHorizontalStrut(20)); // Spacing between text and input
        row.add(inputWrapper);
        row.add(Box.createHorizontalGlue()); // Pushes everything left

        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        return row;
    }
}
