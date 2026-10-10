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
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Astrometry Config tab: the ASTAP solver and the observing site used to identify objects in the report.
 * Edits apply to the session as soon as they are valid; Save keeps them for the next start.
 */
public class ConfigurationPanel extends JPanel {
    private static final int CONFIG_TEXT_COLUMN_WIDTH = 680;
    private static final int CONFIG_TEXT_FIELD_WIDTH = 190;
    private static final int CONFIG_TEXT_FIELD_HEIGHT = 26;

    /** ASTAP download page, offered when no star database is found. */
    static final String ASTAP_DOWNLOAD_URL = "https://www.hnsky.org/astap.htm";
    /** FITS keywords the report reads for the site, in the same order. */
    static final String[] LATITUDE_KEYS = {"SITELAT", "OBSGEO-B", "LAT-OBS"};
    static final String[] LONGITUDE_KEYS = {"SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS"};
    /** ASTAP star database files, e.g. d50_0101.1476, named after the database. */
    private static final Pattern STAR_DATABASE_FILE = Pattern.compile("^([a-z]\\d\\d)_\\d{4}\\.\\w+$", Pattern.CASE_INSENSITIVE);

    // link to main window
    private final ApplicationWindow mainAppWindow;

    private final JLabel astapPathLabel = new JLabel("Not set");
    private final JLabel astapStatusLabel = new JLabel(" ");
    private final JTextField observatoryCodeTextField = new JTextField();
    private final JTextField latTextField = new JTextField();
    private final JTextField longTextField = new JTextField();
    private final JLabel observatoryCodeStatus = statusLabel();
    private final JLabel latStatus = statusLabel();
    private final JLabel longStatus = statusLabel();
    private final JLabel footerStateLabel = new JLabel(" ");
    private final JLabel footerMessageLabel = new JLabel(" ");
    private final JSpinner starDepthSpinner = new JSpinner(new SpinnerNumberModel(
            AppConfig.DEFAULT_SKY_CATALOGUE_STAR_MAGNITUDE, AppConfig.MIN_SKY_CATALOGUE_STAR_MAGNITUDE,
            AppConfig.MAX_SKY_CATALOGUE_STAR_MAGNITUDE, 0.5));
    private final JLabel starDepthStatus = statusLabel();

    /** Site values as last saved, for the unsaved-changes indicator and Revert. */
    private String savedObservatoryCode = "";
    private String savedLat = "";
    private String savedLong = "";
    /** True while the fields are filled from the configuration, so the fill is not treated as an edit. */
    private boolean loading;

    public ConfigurationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 20, 20, 20));

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));

        // --- EXTERNAL TOOLS ---
        mainContent.add(createSectionHeader("Plate Solving"));

        astapPathLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton astapPathButton = new JButton("Browse...");
        JButton astapCheckButton = new JButton("Check");
        astapCheckButton.setToolTipText("Check that ASTAP is there and that a star database is installed next to it.");
        astapCheckButton.addActionListener(e -> checkAstap());
        astapStatusLabel.setFont(astapStatusLabel.getFont().deriveFont(12f));
        astapStatusLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        astapStatusLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (astapStatusLabel.getText().contains("Download")) {
                    openBrowser(ASTAP_DOWNLOAD_URL);
                }
            }
        });

        JPanel astapPathRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astapPathRow.add(astapPathLabel);
        astapPathRow.add(Box.createHorizontalStrut(10));
        astapPathRow.add(astapPathButton);
        astapPathRow.add(Box.createHorizontalStrut(6));
        astapPathRow.add(astapCheckButton);
        JPanel astapControl = verticalStack(astapPathRow, astapStatusLabel);

        mainContent.add(createConfigRow(
                "ASTAP Executable Path",
                "The local ASTAP solver used by <b>Plate Solve Selected</b> with the ASTAP option. ASTAP also needs a star "
                        + "database (D50 is a good choice for most setups), installed next to the program. "
                        + "The <b>Astrometry.net (online)</b> option needs no installation; images are submitted as private.",
                astapControl));

        // --- SKY CATALOGUE ---
        mainContent.add(createSectionHeader("Sky Catalogue (Annotate)"));
        starDepthSpinner.setEditor(new JSpinner.NumberEditor(starDepthSpinner, "0.0"));
        starDepthSpinner.setPreferredSize(new Dimension(80, CONFIG_TEXT_FIELD_HEIGHT));
        starDepthSpinner.setMaximumSize(new Dimension(80, CONFIG_TEXT_FIELD_HEIGHT));
        starDepthSpinner.addChangeListener(e -> starDepthChanged());
        mainContent.add(createConfigRow(
                "Star Depth (Gaia G)",
                "The faintest stars that <b>Fetch Sky Catalogue</b> downloads from Gaia, as a G magnitude. "
                        + "Magnitude 15 takes about half a minute for a field of 4° × 3°; each magnitude deeper roughly "
                        + "doubles the stars and the wait. Variable stars are fetched to one magnitude fainter. "
                        + "A change applies to the next fetch.",
                verticalStack(starDepthSpinner, starDepthStatus)));

        // --- OBSERVING SITE ---
        mainContent.add(createSectionHeader("Observing Site (for object identification)"));

        mainContent.add(createConfigRow(
                "IAU Observatory Code",
                "Optional 3-character observatory code for SkyBoT and other topocentric services. If not set, SpacePixels will fall back to geocenter-based lookups where supported.<br><b>Format:</b> MPC/IAU code such as <code>J95</code>.<br><b>Note:</b> <code>500</code> is only a SkyBoT geocenter fallback and is not valid for JPL Small-Body Identification.",
                fieldWithStatus(observatoryCodeTextField, observatoryCodeStatus)));

        mainContent.add(createConfigRow(
                "Site Latitude (N)",
                "Observation site latitude for accurate celestial annotation. If the FITS headers hold a latitude, SpacePixels uses that value; this field is the fallback.<br><b>Format:</b> signed decimal degrees, positive for north and negative for south, e.g. <code>37.9838</code> or <code>-24.6272</code>. A hemisphere letter also works, e.g. <code>24.6272S</code>.",
                fieldWithStatus(latTextField, latStatus)));

        mainContent.add(createConfigRow(
                "Site Longitude (E)",
                "Observation site longitude for accurate celestial annotation. If the FITS headers hold a longitude, SpacePixels uses that value; this field is the fallback.<br><b>Format:</b> signed decimal degrees, positive for east and negative for west, e.g. <code>23.7275</code> or <code>-70.4030</code>. A hemisphere letter also works, e.g. <code>70.4030W</code>.",
                fieldWithStatus(longTextField, longStatus)));

        JScrollPane scrollPane = new JScrollPane(mainContent);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // --- FOOTER ---
        JButton fillSiteButton = new JButton("Fill Site from FITS Header");
        fillSiteButton.setToolTipText("Copy the site latitude and longitude from the selected frame's FITS header (or the first frame) into the fallback fields.");
        fillSiteButton.addActionListener(e -> fillSiteFromHeader());

        JButton revertButton = new JButton("Revert");
        revertButton.setToolTipText("Return the site fields to the last saved values.");
        revertButton.addActionListener(e -> revertSite());
        JButton saveButton = new JButton("Save");
        saveButton.setToolTipText("Keep the site values for the next start.");
        saveButton.addActionListener(e -> saveSite());

        footerMessageLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JPanel footerLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footerLeft.add(fillSiteButton);
        footerLeft.add(Box.createHorizontalStrut(14));
        footerLeft.add(footerStateLabel);
        footerLeft.add(Box.createHorizontalStrut(10));
        footerLeft.add(footerMessageLabel);
        JPanel footerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        footerRight.add(revertButton);
        footerRight.add(saveButton);
        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(20, 0, 0, 0));
        footer.add(footerLeft, BorderLayout.CENTER);
        footer.add(footerRight, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        // --- LISTENERS ---
        astapPathButton.addActionListener(e -> chooseAstap());
        onEdit(observatoryCodeTextField, text -> validateObservatoryCode(text), value -> config().observatoryCode = value);
        onEdit(latTextField, text -> validateCoordinate(text, 90, "latitude"), value -> config().siteLat = value);
        onEdit(longTextField, text -> validateCoordinate(text, 180, "longitude"), value -> config().siteLong = value);
    }

    public void refreshComponents() {
        if (mainAppWindow.getImageProcessing() == null) {
            return;
        }
        AppConfig config = config();
        String astap = config.astapExecutablePath;
        astapPathLabel.setText(astap == null || astap.isEmpty() ? "Not set" : astap);
        loading = true;
        try {
            starDepthSpinner.setValue(config.skyCatalogueStarMagnitude());
            starDepthStatus.setText(" ");
            observatoryCodeTextField.setText(nullToEmpty(config.observatoryCode));
            latTextField.setText(nullToEmpty(config.siteLat));
            longTextField.setText(nullToEmpty(config.siteLong));
        } finally {
            loading = false;
        }
        savedObservatoryCode = nullToEmpty(config.observatoryCode);
        savedLat = nullToEmpty(config.siteLat);
        savedLong = nullToEmpty(config.siteLong);
        validateAll();
        checkAstap();
        updateSavedState();
    }

    // ==========================================
    // SITE: VALIDATION, APPLY, SAVE
    // ==========================================

    /** Validates a field on every edit; a valid (or empty) value is applied to the session at once. */
    private void onEdit(JTextField field, Function<String, String> validator, Consumer<String> apply) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }

            private void changed() {
                String problem = validator.apply(field.getText().trim());
                if (!loading && problem == null && mainAppWindow.getImageProcessing() != null) {
                    apply.accept(field.getText().trim());
                    footerMessageLabel.setText(" ");
                }
                validateAll();
                updateSavedState();
            }
        });
    }

    private void validateAll() {
        showStatus(observatoryCodeStatus, validateObservatoryCode(observatoryCodeTextField.getText().trim()),
                observatoryCodeNote(observatoryCodeTextField.getText().trim()));
        showStatus(latStatus, validateCoordinate(latTextField.getText().trim(), 90, "latitude"), headerNote(LATITUDE_KEYS, latTextField.getText().trim()));
        showStatus(longStatus, validateCoordinate(longTextField.getText().trim(), 180, "longitude"), headerNote(LONGITUDE_KEYS, longTextField.getText().trim()));
    }

    /** Returns null when the code is empty or valid, otherwise the reason. */
    static String validateObservatoryCode(String text) {
        if (text.isEmpty() || text.matches("(?i)[A-Z0-9]{3}")) {
            return null;
        }
        return "Use 3 letters or digits, e.g. J95.";
    }

    private static String observatoryCodeNote(String text) {
        if ("500".equals(text)) {
            return "500 is the geocentre: SkyBoT only, not JPL.";
        }
        return text.isEmpty() ? "Not set: geocentre-based lookups." : "✓ Valid";
    }

    /** Returns null when the value is empty or a valid coordinate within ±limit degrees, otherwise the reason. */
    static String validateCoordinate(String text, double limit, String name) {
        if (text.isEmpty()) {
            return null;
        }
        Double degrees = parseDegrees(text);
        if (degrees == null) {
            return "Not a number of degrees.";
        }
        if (Math.abs(degrees) > limit) {
            return "A " + name + " must be between -" + (int) limit + " and " + (int) limit + ".";
        }
        return null;
    }

    /**
     * Parses decimal degrees the way the report does: an optional N/S/E/W suffix sets the sign and a decimal comma
     * is accepted. Returns null for anything else.
     */
    static Double parseDegrees(String text) {
        String value = text.replace("'", "").trim().toUpperCase(Locale.ROOT);
        boolean negative = value.endsWith("S") || value.endsWith("W");
        boolean positive = value.endsWith("N") || value.endsWith("E");
        if (negative || positive) {
            value = value.substring(0, value.length() - 1).trim();
        }
        try {
            double degrees = Double.parseDouble(value.replace(",", "."));
            if (!Double.isFinite(degrees)) {
                return null;
            }
            return negative ? -Math.abs(degrees) : positive ? Math.abs(degrees) : degrees;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Which value the report uses for this session: the FITS header's, if any frame has one. */
    private String headerNote(String[] keys, String fieldText) {
        String[] header = headerValue(keys);
        if (header == null) {
            if (mainAppWindow.getImageProcessing() == null) {
                return " ";
            }
            return fieldText.isEmpty() ? "Not set, and no site in the FITS headers." : "✓ Used for this session (no site in the FITS headers).";
        }
        return "This session's FITS header has " + header[1] + " (" + header[0] + "); that value is used.";
    }

    /** First value of the given keywords in the imported frames, as {key, value}; null when none has one. */
    private String[] headerValue(String[] keys) {
        FitsFileInformation[] files = importedFiles();
        if (files == null) {
            return null;
        }
        for (FitsFileInformation file : files) {
            Map<String, String> header = file.getFitsHeader();
            if (header == null) {
                continue;
            }
            for (String key : keys) {
                String value = header.get(key);
                if (value != null && !value.trim().replace("'", "").isEmpty()) {
                    return new String[]{key, value.trim().replace("'", "").trim()};
                }
            }
        }
        return null;
    }

    private void fillSiteFromHeader() {
        FitsFileInformation selected = mainAppWindow.getMainApplicationPanel().getSelectedFileInformation();
        FitsFileInformation[] files = selected != null ? new FitsFileInformation[]{selected} : importedFiles();
        String lat = null;
        String lon = null;
        if (files != null) {
            for (FitsFileInformation file : files) {
                Map<String, String> header = file.getFitsHeader();
                if (header == null) {
                    continue;
                }
                lat = lat != null ? lat : firstValue(header, LATITUDE_KEYS);
                lon = lon != null ? lon : firstValue(header, LONGITUDE_KEYS);
            }
        }
        if (lat == null && lon == null) {
            footerMessageLabel.setText("The FITS header has no site latitude or longitude.");
            return;
        }
        if (lat != null) {
            latTextField.setText(lat);
        }
        if (lon != null) {
            longTextField.setText(lon);
        }
        footerMessageLabel.setText("Site filled from the FITS header.");
    }

    private static String firstValue(Map<String, String> header, String[] keys) {
        for (String key : keys) {
            String value = header.get(key);
            if (value != null && !value.trim().replace("'", "").isEmpty()) {
                return value.trim().replace("'", "").trim();
            }
        }
        return null;
    }

    private void saveSite() {
        if (mainAppWindow.getImageProcessing() == null) {
            return;
        }
        try {
            mainAppWindow.getImageProcessing().saveAppConfig();
            savedObservatoryCode = nullToEmpty(config().observatoryCode);
            savedLat = nullToEmpty(config().siteLat);
            savedLong = nullToEmpty(config().siteLong);
            footerMessageLabel.setText("Saved.");
            updateSavedState();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot save configuration: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void revertSite() {
        observatoryCodeTextField.setText(savedObservatoryCode);
        latTextField.setText(savedLat);
        longTextField.setText(savedLong);
        footerMessageLabel.setText("Reverted to the saved values.");
    }

    private void updateSavedState() {
        if (mainAppWindow.getImageProcessing() == null) {
            footerStateLabel.setText(" ");
            return;
        }
        boolean fieldsMatchConfig = observatoryCodeTextField.getText().trim().equals(nullToEmpty(config().observatoryCode))
                && latTextField.getText().trim().equals(nullToEmpty(config().siteLat))
                && longTextField.getText().trim().equals(nullToEmpty(config().siteLong));
        boolean unsaved = !nullToEmpty(config().observatoryCode).equals(savedObservatoryCode)
                || !nullToEmpty(config().siteLat).equals(savedLat)
                || !nullToEmpty(config().siteLong).equals(savedLong);
        if (!fieldsMatchConfig) {
            footerStateLabel.setForeground(warningColor());
            footerStateLabel.setText("● Fix the marked field; the last valid value stays in use");
        } else if (unsaved) {
            footerStateLabel.setForeground(warningColor());
            footerStateLabel.setText("● Unsaved changes (in use for this session)");
        } else {
            footerStateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            footerStateLabel.setText("✓ Saved settings in use");
        }
    }

    /** Keeps the new star depth at once, like the ASTAP path. */
    private void starDepthChanged() {
        if (loading || mainAppWindow.getImageProcessing() == null) {
            return;
        }
        config().skyCatalogueStarMagnitude = ((Number) starDepthSpinner.getValue()).doubleValue();
        try {
            mainAppWindow.getImageProcessing().saveAppConfig();
            starDepthStatus.setForeground(UIManager.getColor("Label.disabledForeground"));
            starDepthStatus.setText("✓ Saved · used by the next Fetch Sky Catalogue");
        } catch (IOException ex) {
            starDepthStatus.setForeground(warningColor());
            starDepthStatus.setText("Cannot save: " + ex.getMessage());
        }
    }

    // ==========================================
    // ASTAP
    // ==========================================

    private void chooseAstap() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        fc.setDialogTitle("ASTAP executable");
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File astapExecutable = fc.getSelectedFile();
        try {
            Runtime.getRuntime().exec(new String[]{astapExecutable.getAbsolutePath(), "-h"}, null, astapExecutable.getParentFile());
            config().astapExecutablePath = astapExecutable.getAbsolutePath();
            mainAppWindow.getImageProcessing().saveAppConfig();
            astapPathLabel.setText(astapExecutable.getAbsolutePath());
            checkAstap();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Cannot execute ASTAP or save config: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Shows whether ASTAP is present and which star databases are installed next to it. */
    private void checkAstap() {
        String path = mainAppWindow.getImageProcessing() == null ? null : config().astapExecutablePath;
        if (path == null || path.isEmpty()) {
            showAstapStatus(false, "ASTAP is not set. Download ASTAP and a star database (D50), then choose astap.exe.");
            return;
        }
        File executable = new File(path);
        if (!executable.isFile()) {
            showAstapStatus(false, "ASTAP was not found at this path. Choose it again, or Download it.");
            return;
        }
        Map<String, Integer> databases = starDatabases(executable.getParentFile());
        if (databases.isEmpty()) {
            showAstapStatus(false, "ASTAP found, but no star database next to it, so solving will fail. Download one (D50 is a good choice).");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Map.Entry<String, Integer> entry : databases.entrySet()) {
            names.append(names.length() == 0 ? "" : ", ").append(entry.getKey());
        }
        showAstapStatus(true, "✓ ASTAP found · star database " + names);
    }

    /** Star databases found in the folder, by name (e.g. D50), with their file counts. */
    static Map<String, Integer> starDatabases(File folder) {
        Map<String, Integer> databases = new TreeMap<>();
        File[] files = folder == null ? null : folder.listFiles();
        if (files == null) {
            return databases;
        }
        for (File file : files) {
            Matcher matcher = STAR_DATABASE_FILE.matcher(file.getName());
            if (file.isFile() && matcher.matches()) {
                databases.merge(matcher.group(1).toUpperCase(Locale.ROOT), 1, Integer::sum);
            }
        }
        return databases;
    }

    private void showAstapStatus(boolean ok, String text) {
        astapStatusLabel.setForeground(ok ? UIManager.getColor("Label.disabledForeground") : warningColor());
        boolean download = text.contains("Download");
        astapStatusLabel.setText(download ? "<html>" + text.replace("Download", "<a href='#'>Download</a>") + "</html>" : text);
        astapStatusLabel.setToolTipText(download ? ASTAP_DOWNLOAD_URL : null);
    }

    private void openBrowser(String url) {
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception e) {
            footerMessageLabel.setText("Open " + url + " in a browser.");
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private AppConfig config() {
        return mainAppWindow.getImageProcessing().getAppConfig();
    }

    private FitsFileInformation[] importedFiles() {
        try {
            return mainAppWindow.getImageProcessing() == null ? null : mainAppWindow.getImageProcessing().getFitsfileInformation();
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Color warningColor() {
        Color color = UIManager.getColor("Actions.Yellow");
        return color != null ? color : new Color(0xE0A030);
    }

    private static JLabel statusLabel() {
        JLabel label = new JLabel(" ");
        label.setFont(label.getFont().deriveFont(11.5f));
        return label;
    }

    private static void showStatus(JLabel label, String problem, String note) {
        if (problem != null) {
            label.setForeground(warningColor());
            label.setText(problem);
        } else {
            label.setForeground(UIManager.getColor("Label.disabledForeground"));
            label.setText(note);
        }
    }

    private static JPanel fieldWithStatus(JTextField field, JLabel status) {
        field.setPreferredSize(new Dimension(CONFIG_TEXT_FIELD_WIDTH, CONFIG_TEXT_FIELD_HEIGHT));
        field.setMaximumSize(new Dimension(CONFIG_TEXT_FIELD_WIDTH, CONFIG_TEXT_FIELD_HEIGHT));
        return verticalStack(field, status);
    }

    private static JPanel verticalStack(JComponent top, JComponent bottom) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        top.setAlignmentX(Component.LEFT_ALIGNMENT);
        bottom.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(top);
        panel.add(Box.createVerticalStrut(4));
        panel.add(bottom);
        return panel;
    }

    /**
     * Creates a styled section header matching the FlatLaf Accent color.
     */
    private JLabel createSectionHeader(String title) {
        JLabel headerLabel = new JLabel(title);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 16f));
        headerLabel.setForeground(DetectionConfigurationPanel.accentColor());
        headerLabel.setBorder(new EmptyBorder(20, 0, 10, 0));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return headerLabel;
    }

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

        JLabel descLabel = createDescriptionPane(description);
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(3));
        textPanel.add(descLabel);

        // Keep a stable text column width but allow the row height to grow with wrapped content.
        Dimension preferredTextSize = textPanel.getPreferredSize();
        Dimension textDim = new Dimension(CONFIG_TEXT_COLUMN_WIDTH, preferredTextSize.height);
        textPanel.setPreferredSize(textDim);
        textPanel.setMinimumSize(textDim);
        textPanel.setMaximumSize(new Dimension(CONFIG_TEXT_COLUMN_WIDTH, Integer.MAX_VALUE));

        // Right side: Input Control
        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        inputWrapper.add(inputControl);

        row.add(textPanel);
        row.add(Box.createHorizontalStrut(20));
        row.add(inputWrapper);
        row.add(Box.createHorizontalGlue());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // Rows keep their own height instead of stretching to fill the tab.
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * The wrapped description under a setting title. A label with a fixed HTML width measures its height
     * correctly, also with display scaling (an editor pane over-estimated it and left large gaps).
     */
    private JLabel createDescriptionPane(String description) {
        JLabel descriptionLabel = new JLabel(wrapDescriptionHtml(description));
        descriptionLabel.setFont(descriptionLabel.getFont().deriveFont(Font.PLAIN, 12f));
        return descriptionLabel;
    }

    private String wrapDescriptionHtml(String description) {
        Color textColor = UIManager.getColor("Label.disabledForeground");
        if (textColor == null) {
            textColor = Color.GRAY;
        }
        return String.format(
                "<html><div style='width: 520px; color: rgb(%d,%d,%d);'>%s</div></html>",
                textColor.getRed(),
                textColor.getGreen(),
                textColor.getBlue(),
                description);
    }
}
