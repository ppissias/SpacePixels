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
import eu.startales.spacepixels.util.AstrometryNetAccount;
import eu.startales.spacepixels.util.FitsFileInformation;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Astrometry Config tab: the ASTAP solver, the Astrometry.net account, and the observing site used to identify
 * objects in the report.
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

    // link to main window
    private final ApplicationWindow mainAppWindow;
    private final AstapInstallationCheck astapChecks;
    private final JButton astapCheckButton = new JButton("Check");
    private final JButton saveButton = new JButton("Save");

    private final JLabel astapPathLabel = new JLabel("Not set");
    private final JLabel astapStatusLabel = new JLabel(" ");
    private final JRadioButton astrometryAnonymousButton = new JRadioButton("Anonymous");
    private final JRadioButton astrometryOwnKeyButton = new JRadioButton("My API key");
    private final JPasswordField astrometryKeyField = new JPasswordField();
    private final JButton astrometryCheckButton = new JButton("Check");
    private final JLabel astrometryStatus = statusLabel();
    /** The running login check; null when none runs. */
    private SwingWorker<Void, Void> astrometryCheck;
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
    private String savedAstap = "";
    private boolean savedAstrometryUseOwnKey;
    private String savedAstrometryApiKey = "";
    private double savedStarDepth;
    private long saveVersion;
    /** True while the fields are filled from the configuration, so the fill is not treated as an edit. */
    private boolean loading;

    public ConfigurationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        astapChecks = new AstapInstallationCheck(this::astapChecked, this::astapCheckFailed);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 20, 20, 20));

        JPanel mainContent = new JPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));

        // --- EXTERNAL TOOLS ---
        mainContent.add(createSectionHeader("Plate Solving"));

        astapPathLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton astapPathButton = new JButton("Browse...");
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

        ButtonGroup astrometryAccount = new ButtonGroup();
        astrometryAccount.add(astrometryAnonymousButton);
        astrometryAccount.add(astrometryOwnKeyButton);
        astrometryAnonymousButton.setSelected(true);
        astrometryKeyField.setEnabled(false);
        astrometryKeyField.setPreferredSize(new Dimension(CONFIG_TEXT_FIELD_WIDTH, CONFIG_TEXT_FIELD_HEIGHT));
        astrometryKeyField.setMaximumSize(new Dimension(CONFIG_TEXT_FIELD_WIDTH, CONFIG_TEXT_FIELD_HEIGHT));
        astrometryCheckButton.setToolTipText("Log in to Astrometry.net with this account. Nothing is uploaded.");
        JPanel astrometryChoiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astrometryChoiceRow.add(astrometryAnonymousButton);
        astrometryChoiceRow.add(Box.createHorizontalStrut(14));
        astrometryChoiceRow.add(astrometryOwnKeyButton);
        JPanel astrometryKeyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astrometryKeyRow.add(astrometryKeyField);
        astrometryKeyRow.add(Box.createHorizontalStrut(6));
        astrometryKeyRow.add(astrometryCheckButton);

        mainContent.add(createConfigRow(
                "Astrometry.net Account",
                "Used by <b>Plate Solve Selected</b> with the <b>Astrometry.net (online)</b> option. <b>Anonymous</b> needs "
                        + "no account. With <b>My API key</b>, your uploads also show up on your Astrometry.net profile. "
                        + "To get a key, sign in at nova.astrometry.net: the key is in the <b>My Profile</b> section of the "
                        + "dashboard. SpacePixels keeps it in plain text in <code>spacepixels_app.json</code> in your home "
                        + "folder. <b>Check</b> logs in without uploading anything.",
                verticalStack(astrometryChoiceRow, verticalStack(astrometryKeyRow, astrometryStatus))));

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
        astrometryAnonymousButton.addActionListener(e -> astrometryAccountChanged());
        astrometryOwnKeyButton.addActionListener(e -> astrometryAccountChanged());
        astrometryCheckButton.addActionListener(e -> checkAstrometryNet());
        astrometryKeyField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                astrometryAccountChanged();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                astrometryAccountChanged();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                astrometryAccountChanged();
            }
        });
        onEdit(observatoryCodeTextField, text -> validateObservatoryCode(text), value -> config().observatoryCode = value);
        onEdit(latTextField, text -> validateCoordinate(text, 90, "latitude"), value -> config().siteLat = value);
        onEdit(longTextField, text -> validateCoordinate(text, 180, "longitude"), value -> config().siteLong = value);
    }

    public void refreshComponents() {
        AppConfig config = config();
        String astap = config.astapExecutablePath;
        astapPathLabel.setText(astap == null || astap.isEmpty() ? "Not set" : astap);
        loading = true;
        try {
            starDepthSpinner.setValue(config.skyCatalogueStarMagnitude());
            starDepthStatus.setText(" ");
            astrometryOwnKeyButton.setSelected(config.astrometryNetUseOwnKey);
            astrometryAnonymousButton.setSelected(!config.astrometryNetUseOwnKey);
            astrometryKeyField.setText(nullToEmpty(config.astrometryNetApiKey));
            astrometryKeyField.setEnabled(config.astrometryNetUseOwnKey);
            observatoryCodeTextField.setText(nullToEmpty(config.observatoryCode));
            latTextField.setText(nullToEmpty(config.siteLat));
            longTextField.setText(nullToEmpty(config.siteLong));
        } finally {
            loading = false;
        }
        savedObservatoryCode = nullToEmpty(config.observatoryCode);
        savedLat = nullToEmpty(config.siteLat);
        savedLong = nullToEmpty(config.siteLong);
        savedAstap = nullToEmpty(config.astapExecutablePath);
        savedAstrometryUseOwnKey = config.astrometryNetUseOwnKey;
        savedAstrometryApiKey = nullToEmpty(config.astrometryNetApiKey);
        savedStarDepth = config.skyCatalogueStarMagnitude();
        validateAll();
        checkAstap();
        showAstrometryAccountNote();
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
                if (!loading && problem == null && mainAppWindow.getApplicationSettings() != null) {
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
        saveDefaults(true);
    }

    private void saveDefaults(boolean showError) {
        if (mainAppWindow.isClosing()) return;
        AppConfig snapshot = config().copy();
        long expectedVersion = ++saveVersion;
        saveButton.setEnabled(false);
        footerMessageLabel.setText("Saving application defaults...");
        starDepthStatus.setText("Saving...");
        mainAppWindow.getSettingsStorage().submit("Application defaults", () -> {
            mainAppWindow.getApplicationSettings().save(snapshot);
            return null;
        }).whenComplete((ignored, failure) -> EventQueue.invokeLater(() -> {
            if (mainAppWindow.isClosing()) return;
            if (failure == null) {
                savedObservatoryCode = nullToEmpty(snapshot.observatoryCode);
                savedLat = nullToEmpty(snapshot.siteLat);
                savedLong = nullToEmpty(snapshot.siteLong);
                savedAstap = nullToEmpty(snapshot.astapExecutablePath);
                savedAstrometryUseOwnKey = snapshot.astrometryNetUseOwnKey;
                savedAstrometryApiKey = nullToEmpty(snapshot.astrometryNetApiKey);
                savedStarDepth = snapshot.skyCatalogueStarMagnitude();
            }
            updateSavedState();
            if (saveVersion != expectedVersion) return;
            saveButton.setEnabled(true);
            starDepthStatus.setForeground(failure == null ? UIManager.getColor("Label.disabledForeground") : warningColor());
            starDepthStatus.setText(failure == null ? "✓ Saved · used by the next Fetch Sky Catalogue"
                    : "Cannot save: " + failure.getMessage());
            footerMessageLabel.setText(failure == null ? "Saved application defaults."
                    + (hasUnsavedDefaults() ? " Newer edits remain unsaved." : "")
                    : "Not saved: " + failure.getMessage());
            if (failure != null && showError) {
                JOptionPane.showMessageDialog(this, "Cannot save configuration: " + failure.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }));
    }

    private void revertSite() {
        observatoryCodeTextField.setText(savedObservatoryCode);
        latTextField.setText(savedLat);
        longTextField.setText(savedLong);
        footerMessageLabel.setText("Reverted to the saved values.");
    }

    private void updateSavedState() {
        boolean fieldsMatchConfig = observatoryCodeTextField.getText().trim().equals(nullToEmpty(config().observatoryCode))
                && latTextField.getText().trim().equals(nullToEmpty(config().siteLat))
                && longTextField.getText().trim().equals(nullToEmpty(config().siteLong));
        if (!fieldsMatchConfig) {
            footerStateLabel.setForeground(warningColor());
            footerStateLabel.setText("● Fix the marked field; the last valid value stays in use");
        } else if (hasUnsavedDefaults()) {
            footerStateLabel.setForeground(warningColor());
            footerStateLabel.setText("● Unsaved application defaults (in use)");
        } else {
            footerStateLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
            footerStateLabel.setText("✓ Saved settings in use");
        }
    }

    private boolean hasUnsavedDefaults() {
        return !nullToEmpty(config().observatoryCode).equals(savedObservatoryCode)
                || !nullToEmpty(config().siteLat).equals(savedLat)
                || !nullToEmpty(config().siteLong).equals(savedLong)
                || !nullToEmpty(config().astapExecutablePath).equals(savedAstap)
                || config().astrometryNetUseOwnKey != savedAstrometryUseOwnKey
                || !nullToEmpty(config().astrometryNetApiKey).equals(savedAstrometryApiKey)
                || config().skyCatalogueStarMagnitude() != savedStarDepth;
    }

    /** Keeps the new star depth at once, like the ASTAP path. */
    private void starDepthChanged() {
        if (loading) {
            return;
        }
        config().skyCatalogueStarMagnitude = ((Number) starDepthSpinner.getValue()).doubleValue();
        updateSavedState();
        saveDefaults(false);
    }

    // ==========================================
    // ASTROMETRY.NET ACCOUNT
    // ==========================================

    /** Applies the account choice and the key at once and keeps them for the next start, like the star depth. */
    private void astrometryAccountChanged() {
        astrometryKeyField.setEnabled(astrometryOwnKeyButton.isSelected());
        if (loading) {
            return;
        }
        AppConfig config = config();
        config.astrometryNetUseOwnKey = astrometryOwnKeyButton.isSelected();
        config.astrometryNetApiKey = new String(astrometryKeyField.getPassword()).trim();
        showAstrometryAccountNote();
        updateSavedState();
        saveDefaults(false);
    }

    private void showAstrometryAccountNote() {
        AppConfig config = config();
        if (!config.astrometryNetUseOwnKey) {
            showAstrometryStatus(null, "Online solves log in anonymously.");
        } else if (config.astrometryNetApiKeyInUse().isEmpty()) {
            showAstrometryStatus("Enter your key. Until then, online solves log in anonymously.", null);
        } else {
            showAstrometryStatus(null, "Online solves use your key.");
        }
    }

    /** Logs in to Astrometry.net with the chosen account on a worker thread. Nothing is uploaded. */
    private void checkAstrometryNet() {
        if (mainAppWindow.isClosing() || astrometryCheck != null) {
            return;
        }
        AppConfig account = config().copy();
        String apiKey = account.astrometryNetApiKeyInUse();
        astrometryCheckButton.setEnabled(false);
        showAstrometryStatus(null, "Checking with Astrometry.net…");
        astrometryCheck = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                AstrometryNetAccount.checkLogin(account);
                return null;
            }

            @Override
            protected void done() {
                if (astrometryCheck != this || mainAppWindow.isClosing()) {
                    return;
                }
                astrometryCheck = null;
                astrometryCheckButton.setEnabled(true);
                if (!apiKey.equals(config().astrometryNetApiKeyInUse())) {
                    // The account changed during the check, so this answer is about the previous one.
                    showAstrometryAccountNote();
                    return;
                }
                try {
                    get();
                    showAstrometryStatus(null, apiKey.isEmpty()
                            ? "✓ Astrometry.net accepted the anonymous login"
                            : "✓ Astrometry.net accepted your key");
                } catch (CancellationException ignored) {
                    showAstrometryAccountNote();
                } catch (ExecutionException e) {
                    Throwable failure = e.getCause() != null ? e.getCause() : e;
                    showAstrometryStatus(failure.getMessage() != null ? failure.getMessage() : failure.toString(), null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        astrometryCheck.execute();
    }

    /** A login failure can be a long sentence, so the status wraps at the width of the account controls. */
    private void showAstrometryStatus(String problem, String note) {
        showStatus(astrometryStatus, problem == null ? null : wrapStatus(problem), note == null ? null : wrapStatus(note));
    }

    private static String wrapStatus(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width: 330px;'>" + escaped + "</div></html>";
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
        startAstapCheck(astapExecutable.getAbsolutePath(), true);
    }

    /** Shows whether ASTAP is present and which star databases are installed next to it. */
    private void checkAstap() {
        startAstapCheck(config().astapExecutablePath, false);
    }

    private void startAstapCheck(String path, boolean select) {
        if (mainAppWindow.isClosing()) return;
        astapCheckButton.setEnabled(false);
        showAstapStatus(true, "Checking ASTAP installation...");
        astapChecks.check(path, select);
    }

    private void astapChecked(AstapInstallationCheck.Request request, AstapInstallationCheck.Result result) {
        if (mainAppWindow.isClosing()) return;
        astapCheckButton.setEnabled(true);
        if (request.select()) {
            config().astapExecutablePath = request.path();
            astapPathLabel.setText(request.path());
            updateSavedState();
            saveDefaults(true);
        }
        showAstapStatus(result.available(), result.message());
    }

    private void astapCheckFailed(AstapInstallationCheck.Request request, Throwable failure) {
        if (mainAppWindow.isClosing()) return;
        astapCheckButton.setEnabled(true);
        showAstapStatus(false, "Cannot check ASTAP: " + failure.getMessage());
        if (request.select()) {
            JOptionPane.showMessageDialog(this, "Cannot execute ASTAP: " + failure.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    void close() {
        astapChecks.close();
        SwingWorker<Void, Void> check = astrometryCheck;
        astrometryCheck = null;
        if (check != null) {
            check.cancel(true);
        }
    }

    /** Star databases found in the folder, by name (e.g. D50), with their file counts. */
    static Map<String, Integer> starDatabases(File folder) {
        return AstapInstallationCheck.starDatabases(folder);
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
        return mainAppWindow.getApplicationSettings().getConfig();
    }

    void sessionChanged() {
        validateAll();
        updateSavedState();
    }

    private FitsFileInformation[] importedFiles() {
        try {
            return mainAppWindow.getImageProcessing() == null ? null : mainAppWindow.getImageProcessing().getImportedFiles();
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
