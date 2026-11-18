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
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.ObservingSites;
import eu.startales.spacepixels.detection.ComputeOptionsMapper;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.platesolve.AstrometryNetAccount;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.ObservingSite;
import io.github.ppissias.jtransient.compute.GpuDiagnostics;
import io.github.ppissias.jtransient.gpu.OpenClDevice;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Astrometry Config tab: the ASTAP solver, the Astrometry.net account, and the observing site used to identify
 * objects in the report.
 * Edits apply to the session as soon as they are valid; Save keeps them for the next start.
 */
public class ConfigurationPanel extends JPanel {
    /** Wide enough for a normal Astrometry.net API key. A longer key scrolls inside the field. */
    private static final int API_KEY_COLUMNS = 18;

    /** Wide enough for an address such as http://localhost:5000/. */
    private static final int LOCAL_SOLVER_ADDRESS_COLUMNS = 18;
    /** Watney Astrometry, a free solver whose solver API offers the Astrometry.net web API. */
    static final String WATNEY_URL = "https://github.com/Jusas/WatneyAstrometry";
    /** ASTAP download page, offered when no star database is found. */
    static final String ASTAP_DOWNLOAD_URL = "https://www.hnsky.org/astap.htm";
    /** FITS keywords the report reads for the site, in the same order (one list, owned by ObservingSite). */
    static final List<String> LATITUDE_KEYS = ObservingSite.LATITUDE_KEYS;
    static final List<String> LONGITUDE_KEYS = ObservingSite.LONGITUDE_KEYS;
    static final List<String> ALTITUDE_KEYS = ObservingSite.ALTITUDE_KEYS;

    // link to main window
    private final ApplicationSettings applicationSettings;
    private final SessionAccess sessions;
    private final FrameList frames;
    private final ApplicationStorage storage;
    private final BooleanSupplier closing;
    private final AstapInstallationCheck astapChecks;
    private final JButton astapCheckButton = new JButton("Check");
    private final JButton saveButton = new JButton("Save");

    private final JLabel astapPathLabel = new JLabel("Not set");
    private final JLabel astapStatusLabel = statusLabel();
    private final JButton astapDownloadButton = AccessibleControls.link("Download ASTAP and star databases",
            "Download ASTAP and star databases", () -> openBrowser(ASTAP_DOWNLOAD_URL));
    private final JRadioButton astrometryAnonymousButton = new JRadioButton("Anonymous");
    private final JRadioButton astrometryOwnKeyButton = new JRadioButton("My API key");
    private final JPasswordField astrometryKeyField = new JPasswordField();
    private final JButton astrometryCheckButton = new JButton("Check");
    private final JLabel astrometryStatus = statusLabel();
    /** The running login check; null when none runs. */
    private SwingWorker<Void, Void> astrometryCheck;
    private final JTextField localSolverAddressField = new JTextField();
    private final JPasswordField localSolverKeyField = new JPasswordField();
    private final JButton localSolverCheckButton = new JButton("Check");
    private final JLabel localSolverStatus = statusLabel();
    private final JButton watneyLink = AccessibleControls.link("Watney Astrometry (free download)",
            "Download Watney Astrometry", () -> openBrowser(WATNEY_URL));
    /** The running local solver check; null when none runs. */
    private SwingWorker<Void, Void> localSolverCheck;
    private final DefaultComboBoxModel<String> siteNames = new DefaultComboBoxModel<>();
    private final JComboBox<String> siteCombo = new JComboBox<>(siteNames);
    private final JButton newSiteButton = new JButton("New");
    private final JButton deleteSiteButton = new JButton("Delete");
    private final JTextField siteNameField = new JTextField(14);
    private final JLabel siteNameStatus = statusLabel();
    private final JTextField observatoryCodeTextField = new JTextField();
    private final JTextField latTextField = new JTextField();
    private final JTextField longTextField = new JTextField();
    private final JTextField altitudeTextField = new JTextField();
    private final JLabel observatoryCodeStatus = statusLabel();
    private final JLabel latStatus = statusLabel();
    private final JLabel longStatus = statusLabel();
    private final JLabel altitudeStatus = statusLabel();
    private final JLabel footerStateLabel = new JLabel(" ");
    private final SettingsRowPanel.ControlColumn settingsControlColumn = new SettingsRowPanel.ControlColumn();
    private final JLabel footerMessageLabel = new JLabel(" ");
    private final JSpinner starDepthSpinner = UiStyle.spinner(new DecimalSpinnerModel(
            AppConfig.DEFAULT_SKY_CATALOGUE_STAR_MAGNITUDE, AppConfig.MIN_SKY_CATALOGUE_STAR_MAGNITUDE,
            AppConfig.MAX_SKY_CATALOGUE_STAR_MAGNITUDE, 0.5));
    private final JLabel starDepthStatus = statusLabel();
    private final JSpinner solarDepthSpinner = UiStyle.spinner(new DecimalSpinnerModel(
            AppConfig.DEFAULT_SOLAR_SYSTEM_MAGNITUDE, AppConfig.MIN_SOLAR_SYSTEM_MAGNITUDE,
            AppConfig.MAX_SOLAR_SYSTEM_MAGNITUDE, 0.5));
    private final JLabel solarDepthStatus = statusLabel();
    private final JComboBox<GpuModeChoice> gpuModeCombo = new JComboBox<>(new GpuModeChoice[] {
            new GpuModeChoice("AUTO", "Auto (recommended)"),
            new GpuModeChoice("GPU", "Always GPU"),
            new GpuModeChoice("CPU", "Always CPU")
    });
    private final DefaultComboBoxModel<GpuDeviceChoice> gpuDeviceModel = new DefaultComboBoxModel<>();
    private final JComboBox<GpuDeviceChoice> gpuDeviceCombo = new JComboBox<>(gpuDeviceModel);
    private final JButton gpuTestButton = new JButton("Test GPU");
    private final JLabel gpuStatusLabel = statusLabel();
    /** Background OpenCL probe and benchmark workers; null when idle. */
    private SwingWorker<GpuDiagnostics.ProbeSummary, Void> gpuProbeWorker;
    private SwingWorker<GpuDiagnostics.TestResult, Void> gpuTestWorker;
    private GpuDiagnostics.ProbeSummary latestGpuProbe;

    /** Site values as last saved, for the unsaved-changes indicator and Revert. */
    private String savedObservatoryCode = "";
    private String savedLat = "";
    private String savedLong = "";
    private String savedAltitude = "";
    private List<ObservingSites.Site> savedSites = List.of();
    private String savedSiteName = "";
    private String savedAstap = "";
    private boolean savedAstrometryUseOwnKey;
    private String savedAstrometryApiKey = "";
    private String savedLocalSolverAddress = "";
    private String savedLocalSolverApiKey = "";
    private double savedStarDepth;
    private double savedSolarDepth;
    private String savedGpuMode = AppConfig.DEFAULT_GPU_MODE;
    private String savedGpuDeviceId = "";
    private long saveVersion;
    /** True while the fields are filled from the configuration, so the fill is not treated as an edit. */
    private boolean loading;
    private Runnable fetchSettingsChanged = () -> { };

    /** Notifies the main readout after valid in-memory edits; listener and all notifications run on the EDT. */
    void onFetchSettingsChanged(Runnable listener) {
        fetchSettingsChanged = java.util.Objects.requireNonNull(listener);
    }

    /** Astrometry settings. {@code frames} supplies the selected frame for "fill site from the header". */
    public ConfigurationPanel(ApplicationSettings applicationSettings, SessionAccess sessions, FrameList frames,
            ApplicationStorage storage, BooleanSupplier closing) {
        this.applicationSettings = applicationSettings;
        this.sessions = sessions;
        this.frames = frames;
        this.storage = storage;
        this.closing = closing;
        astapChecks = new AstapInstallationCheck(this::astapChecked, this::astapCheckFailed);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 20, 20, 20));

        JPanel mainContent = new WidthTrackingPanel();
        mainContent.setLayout(new BoxLayout(mainContent, BoxLayout.Y_AXIS));
        UiStyle.numberInput(observatoryCodeTextField);
        UiStyle.numberInput(latTextField);
        UiStyle.numberInput(longTextField);
        UiStyle.numberInput(altitudeTextField);
        astrometryKeyField.setColumns(API_KEY_COLUMNS);

        JPanel plateSolving = section("Plate Solving");

        astapPathLabel.setForeground(UiStyle.helpColor());
        JButton astapPathButton = new JButton("Browse...");
        astapCheckButton.setToolTipText("Check that ASTAP is there and that a star database is installed next to it.");
        astapCheckButton.addActionListener(e -> checkAstap());
        astapStatusLabel.setFont(UiStyle.bodyFont());
        astapDownloadButton.setVisible(false);

        JPanel astapPathRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astapPathRow.add(astapPathLabel);
        astapPathRow.add(Box.createHorizontalStrut(10));
        astapPathRow.add(astapPathButton);
        astapPathRow.add(Box.createHorizontalStrut(6));
        astapPathRow.add(astapCheckButton);
        JPanel astapControl = rightColumn(astapPathRow, verticalStack(astapStatusLabel, astapDownloadButton));

        plateSolving.add(createConfigRow(
                "ASTAP Executable Path",
                "The local ASTAP solver used by <b>Plate Solve Selected</b> with the ASTAP option. ASTAP also needs a star "
                        + "database (D50 is a good choice for most setups), installed next to the program. "
                        + "The <b>Astrometry.net (online)</b> option needs no installation; images are submitted as private, "
                        + "as a copy without your site location or observer name.",
                astapControl));

        ButtonGroup astrometryAccount = new ButtonGroup();
        astrometryAccount.add(astrometryAnonymousButton);
        astrometryAccount.add(astrometryOwnKeyButton);
        astrometryAnonymousButton.setSelected(true);
        astrometryKeyField.setEnabled(false);
        astrometryCheckButton.setToolTipText("Log in to Astrometry.net with this account. Nothing is uploaded.");
        JPanel astrometryChoiceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astrometryChoiceRow.add(astrometryAnonymousButton);
        astrometryChoiceRow.add(Box.createHorizontalStrut(14));
        astrometryChoiceRow.add(astrometryOwnKeyButton);
        JPanel astrometryKeyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        astrometryKeyRow.add(astrometryKeyField);
        astrometryKeyRow.add(Box.createHorizontalStrut(6));
        astrometryKeyRow.add(astrometryCheckButton);

        plateSolving.add(createConfigRow(
                "Astrometry.net Account",
                "Used by <b>Plate Solve Selected</b> with the <b>Astrometry.net (online)</b> option. <b>Anonymous</b> needs "
                        + "no account; all anonymous uploads belong to one shared account. With <b>My API key</b>, your uploads also show up on your Astrometry.net profile. "
                        + "To get a key, sign in at nova.astrometry.net: the key is in the <b>My Profile</b> section of the "
                        + "dashboard. SpacePixels keeps it in plain text in <code>spacepixels_app.json</code> in your home "
                        + "folder. <b>Check</b> logs in without uploading anything.",
                rightColumn(astrometryChoiceRow, rightColumn(astrometryKeyRow, astrometryStatus))));

        localSolverAddressField.setColumns(LOCAL_SOLVER_ADDRESS_COLUMNS);
        localSolverKeyField.setColumns(API_KEY_COLUMNS);
        localSolverCheckButton.setToolTipText("Log in to the local solver. No frame is sent.");
        watneyLink.setToolTipText(WATNEY_URL);
        JPanel localAddressRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        localAddressRow.add(new JLabel("Address"));
        localAddressRow.add(Box.createHorizontalStrut(6));
        localAddressRow.add(localSolverAddressField);
        JPanel localKeyRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        localKeyRow.add(new JLabel("API key"));
        localKeyRow.add(Box.createHorizontalStrut(6));
        localKeyRow.add(localSolverKeyField);
        localKeyRow.add(Box.createHorizontalStrut(6));
        localKeyRow.add(localSolverCheckButton);
        plateSolving.add(createConfigRow(
                "Local Solver (Astrometry.net API)",
                "A plate solver on this computer or your network that answers like Astrometry.net, used by <b>Plate Solve "
                        + "Selected</b> with the <b>Astrometry.net (local)</b> option, which is offered once an address is set. "
                        + "Enter its address, such as <code>http://localhost:5000/</code>, and its API key if it asks for one. "
                        + "<b>Watney Astrometry</b> is one such solver: run its solver API (<code>watney-api</code>), which "
                        + "has the key in <code>apikeys.yml</code>.",
                rightColumn(localAddressRow, rightColumn(localKeyRow, verticalStack(localSolverStatus, watneyLink)))));
        localSolverAddressField.getAccessibleContext().setAccessibleName("Local solver address");
        localSolverKeyField.getAccessibleContext().setAccessibleName("Local solver API key");
        mainContent.add(plateSolving);

        JPanel skyCatalogue = section("Sky Catalogue (Annotate)");
        starDepthSpinner.setEditor(new JSpinner.NumberEditor(starDepthSpinner, "0.0"));
        UiStyle.numberInput(starDepthSpinner);
        starDepthSpinner.addChangeListener(e -> starDepthChanged());
        skyCatalogue.add(createConfigRow(
                "Star Depth (Gaia G)",
                "The faintest stars that <b>Fetch Sky Catalogue</b> downloads from Gaia, as a G magnitude. "
                        + "Magnitude 15 takes about half a minute for a field of 4° × 3°; each magnitude deeper roughly "
                        + "doubles the stars and the wait. Variable stars are fetched to one magnitude fainter. "
                        + "A change applies to the next fetch.",
                rightColumn(starDepthSpinner, starDepthStatus)));
        solarDepthSpinner.setEditor(new JSpinner.NumberEditor(solarDepthSpinner, "0.0"));
        UiStyle.numberInput(solarDepthSpinner);
        solarDepthSpinner.addChangeListener(e -> solarDepthChanged());
        skyCatalogue.add(createConfigRow(
                "Asteroid and Comet Depth (V)",
                "The faintest asteroids and comets that <b>Fetch Asteroids and Comets</b> asks JPL and IMCCE SkyBoT for, as a predicted V "
                        + "magnitude. For comets JPL predicts the total magnitude, which can be off by a magnitude or more. "
                        + "A deeper limit finds more objects and takes longer: a search of one field took from half a minute "
                        + "to four minutes in tests, and for frames more than about ten years old JPL may not answer at all "
                        + "without a limit. A change applies to the next fetch.",
                rightColumn(solarDepthSpinner, solarDepthStatus)));
        mainContent.add(skyCatalogue);

        JPanel observingSite = section("Observing Site (for object identification)");

        siteCombo.setToolTipText("The observing site in use. Choosing another puts it in use at once; Save keeps the choice for the next start.");
        newSiteButton.setToolTipText("Add a site with empty values and put it in use.");
        deleteSiteButton.setToolTipText("Remove the selected site. Save keeps the change.");
        siteCombo.setPrototypeDisplayValue("Observatory name long");
        JPanel siteChoice = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        siteChoice.setOpaque(false);
        siteChoice.add(siteCombo);
        siteChoice.add(newSiteButton);
        siteChoice.add(deleteSiteButton);
        observingSite.add(createConfigRow(
                "Site",
                "The observing site in use, chosen from your saved sites: each has a name, an IAU observatory code, a latitude, "
                        + "a longitude and an altitude. Choosing a site puts it in use at once, as typing in the fields does. "
                        + "<b>New</b> adds an empty site to fill in; <b>Delete</b> removes the selected one. "
                        + "<b>Save</b> keeps the sites and the choice for the next start.",
                siteChoice));
        observingSite.add(createConfigRow(
                "Site Name",
                "The name of the selected site, shown in the list above. Each site needs its own name.",
                fieldWithStatus(siteNameField, siteNameStatus)));

        observingSite.add(createConfigRow(
                "IAU Observatory Code",
                "Optional 3-character observatory code for SkyBoT and other topocentric services. If not set, SpacePixels will fall back to geocenter-based lookups where supported.<br>When the FITS headers hold a site, that site describes the frames and is used instead wherever a lookup accepts coordinates (JPL and the asteroid and comet fetch). SkyBoT accepts only codes and always uses this one.<br><b>Format:</b> MPC/IAU code such as <code>J95</code>.<br><b>Note:</b> <code>500</code> is only a SkyBoT geocenter fallback and is not valid for JPL Small-Body Identification.",
                fieldWithStatus(observatoryCodeTextField, observatoryCodeStatus)));

        observingSite.add(createConfigRow(
                "Site Latitude (N)",
                "Observation site latitude for accurate celestial annotation. If the FITS headers hold a latitude, SpacePixels uses that value; this field is the fallback.<br><b>Format:</b> signed decimal degrees, positive for north and negative for south, e.g. <code>37.9838</code> or <code>-24.6272</code>. A hemisphere letter also works, e.g. <code>24.6272S</code>.",
                fieldWithStatus(latTextField, latStatus)));

        observingSite.add(createConfigRow(
                "Site Longitude (E)",
                "Observation site longitude for accurate celestial annotation. If the FITS headers hold a longitude, SpacePixels uses that value; this field is the fallback.<br><b>Format:</b> signed decimal degrees, positive for east and negative for west, e.g. <code>23.7275</code> or <code>-70.4030</code>. A hemisphere letter also works, e.g. <code>70.4030W</code>.",
                fieldWithStatus(longTextField, longStatus)));
        observingSite.add(createConfigRow(
                "Site Altitude (m)",
                "Observer height above the WGS84/GPS reference ellipsoid, in metres. A valid FITS elevation takes priority; "
                        + "this value is the fallback when the header has none. Leave blank to assume 0 m. Negative elevations are allowed. "
                        + "When JPL uses an IAU observatory code, its registered location is used instead.",
                fieldWithStatus(altitudeTextField, altitudeStatus)));
        mainContent.add(observingSite);

        JPanel gpuAcceleration = section("GPU acceleration (faint movers)");
        gpuModeCombo.setToolTipText("Choose whether the faint mover first-pass search may run on OpenCL graphics hardware.");
        gpuAcceleration.add(createConfigRow(
                "First pass compute",
                "Auto (recommended) checks parity and CPU-vs-GPU speed once on your machine and uses the GPU when it is clearly faster. "
                        + "Always GPU forces OpenCL when a compatible graphics card is present; Always CPU disables OpenCL.",
                gpuModeCombo));

        gpuDeviceModel.addElement(new GpuDeviceChoice("", "Auto (fastest verified)"));
        gpuDeviceCombo.getAccessibleContext().setAccessibleName("Graphics device");
        gpuDeviceCombo.setPrototypeDisplayValue(new GpuDeviceChoice("", "Auto (fastest verified device)   "));
        gpuDeviceCombo.setToolTipText("Select a specific OpenCL graphics device or leave Auto to pick the fastest verified card.");
        gpuTestButton.setToolTipText("Run the OpenCL parity self-test and multi-core CPU benchmark in the background.");
        JPanel gpuDeviceControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        gpuDeviceControls.setOpaque(false);
        gpuDeviceControls.add(gpuDeviceCombo);
        gpuDeviceControls.add(gpuTestButton);
        gpuAcceleration.add(createConfigRow(
                "Graphics device",
                "OpenCL device used for faint mover acceleration. Use Test GPU to run the parity check and benchmark on demand.",
                fieldWithStatus(gpuDeviceControls, gpuStatusLabel)));
        mainContent.add(gpuAcceleration);

        JScrollPane scrollPane = new JScrollPane(mainContent);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, BorderLayout.CENTER);

        // --- FOOTER ---
        JButton fillSiteButton = new JButton("Fill Site from FITS Header");
        fillSiteButton.setToolTipText("Copy the site latitude, longitude and altitude from the selected frame's FITS header (or the first frame) into the fallback fields.");
        fillSiteButton.addActionListener(e -> fillSiteFromHeader());

        JButton revertButton = new JButton("Revert");
        revertButton.setToolTipText("Return the sites and the chosen site to the last saved values.");
        revertButton.addActionListener(e -> revertSite());
        saveButton.setToolTipText("Keep the sites, the chosen site and the other settings for the next start.");
        saveButton.addActionListener(e -> saveSite());

        footerMessageLabel.setForeground(UiStyle.helpColor());
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
        localSolverCheckButton.addActionListener(e -> checkLocalSolver());
        for (javax.swing.text.JTextComponent field : List.of(localSolverAddressField, localSolverKeyField)) {
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent e) { localSolverChanged(); }
                @Override public void removeUpdate(DocumentEvent e) { localSolverChanged(); }
                @Override public void changedUpdate(DocumentEvent e) { localSolverChanged(); }
            });
        }
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
        onEdit(observatoryCodeTextField, text -> validateObservatoryCode(text), value -> {
            config().observatoryCode = value;
            ObservingSites.selected(config()).observatoryCode = value;
        });
        onEdit(latTextField, text -> validateCoordinate(text, 90, "latitude"), value -> {
            config().siteLat = value;
            ObservingSites.selected(config()).siteLat = value;
        });
        onEdit(longTextField, text -> validateCoordinate(text, 180, "longitude"), value -> {
            config().siteLong = value;
            ObservingSites.selected(config()).siteLong = value;
        });
        onEdit(altitudeTextField, ConfigurationPanel::validateAltitude, value -> {
            config().siteAltitude = value;
            ObservingSites.selected(config()).siteAltitude = value;
        });
        siteCombo.addActionListener(e -> {
            if (!loading && applicationSettings != null && siteCombo.getSelectedItem() != null) chooseSite((String) siteCombo.getSelectedItem());
        });
        newSiteButton.addActionListener(e -> addSite());
        deleteSiteButton.addActionListener(e -> deleteSite());
        siteNameField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { siteNameEdited(); }
            @Override public void removeUpdate(DocumentEvent e) { siteNameEdited(); }
            @Override public void changedUpdate(DocumentEvent e) { siteNameEdited(); }
        });
        gpuModeCombo.addActionListener(e -> gpuModeChanged());
        gpuDeviceCombo.addActionListener(e -> gpuDeviceChanged());
        gpuTestButton.addActionListener(e -> runGpuTest());
    }

    public void refreshComponents() {
        AppConfig config = config();
        String astap = config.astapExecutablePath;
        astapPathLabel.setText(astap == null || astap.isEmpty() ? "Not set" : astap);
        loading = true;
        try {
            starDepthSpinner.setValue(config.skyCatalogueStarMagnitude());
            starDepthStatus.setText(" ");
            solarDepthSpinner.setValue(config.solarSystemMagnitude());
            solarDepthStatus.setText(" ");
            astrometryOwnKeyButton.setSelected(config.astrometryNetUseOwnKey);
            astrometryAnonymousButton.setSelected(!config.astrometryNetUseOwnKey);
            astrometryKeyField.setText(nullToEmpty(config.astrometryNetApiKey));
            astrometryKeyField.setEnabled(config.astrometryNetUseOwnKey);
            localSolverAddressField.setText(nullToEmpty(config.localSolverAddress));
            localSolverKeyField.setText(nullToEmpty(config.localSolverApiKey));
            selectGpuMode(config.gpuMode());
            rebuildGpuDeviceChoices(config.gpuDeviceId());
            updateGpuControlsEnabled(config);
            ObservingSites.ensure(config);
            fillSite();
        } finally {
            loading = false;
        }
        savedSites = ObservingSites.copyOf(config.observingSites);
        savedSiteName = nullToEmpty(config.observingSite);
        savedObservatoryCode = nullToEmpty(config.observatoryCode);
        savedLat = nullToEmpty(config.siteLat);
        savedLong = nullToEmpty(config.siteLong);
        savedAltitude = nullToEmpty(config.siteAltitude);
        savedAstap = nullToEmpty(config.astapExecutablePath);
        savedAstrometryUseOwnKey = config.astrometryNetUseOwnKey;
        savedAstrometryApiKey = nullToEmpty(config.astrometryNetApiKey);
        savedLocalSolverAddress = nullToEmpty(config.localSolverAddress);
        savedLocalSolverApiKey = nullToEmpty(config.localSolverApiKey);
        savedStarDepth = config.skyCatalogueStarMagnitude();
        savedSolarDepth = config.solarSystemMagnitude();
        savedGpuMode = config.gpuMode();
        savedGpuDeviceId = config.gpuDeviceId();
        validateAll();
        checkAstap();
        showAstrometryAccountNote();
        showLocalSolverNote();
        probeGpuDevices();
        refreshGpuStatusLine();
        updateSavedState();
        fetchSettingsChanged.run();
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
                if (!loading && problem == null && applicationSettings != null) {
                    apply.accept(field.getText().trim());
                    fetchSettingsChanged.run();
                    footerMessageLabel.setText(" ");
                }
                validateAll();
                updateSavedState();
            }
        });
    }

    private void validateAll() {
        showStatus(siteNameStatus, applicationSettings == null ? null
                : ObservingSites.nameProblem(config(), siteNameField.getText()), " ");
        showStatus(observatoryCodeStatus, validateObservatoryCode(observatoryCodeTextField.getText().trim()),
                observatoryCodeNote(observatoryCodeTextField.getText().trim()));
        showStatus(latStatus, validateCoordinate(latTextField.getText().trim(), 90, "latitude"), headerNote(LATITUDE_KEYS, latTextField.getText().trim()));
        showStatus(longStatus, validateCoordinate(longTextField.getText().trim(), 180, "longitude"), headerNote(LONGITUDE_KEYS, longTextField.getText().trim()));
        showStatus(altitudeStatus, validateAltitude(altitudeTextField.getText().trim()),
                headerNote(ALTITUDE_KEYS, altitudeTextField.getText().trim()));
    }

    /** Accepts an optional finite height in metres, including values below the reference ellipsoid. */
    static String validateAltitude(String text) {
        if (text.isEmpty()) return null;
        return ObservingSite.parseAltitude(text) == null ? "Enter a finite altitude in metres." : null;
    }

    /** Returns null when the code is empty or valid, otherwise the reason. */
    static String validateObservatoryCode(String text) {
        if (text.isEmpty() || text.matches("(?i)[A-Z0-9]{3}")) {
            return null;
        }
        return "Use 3 letters or digits, e.g. J95.";
    }

    /** Status text for the code; notes when this session's FITS headers hold a site, which JPL lookups use instead. */
    private String observatoryCodeNote(String text) {
        if ("500".equals(text)) {
            return "500 is the geocentre: SkyBoT only, not JPL.";
        }
        if (text.isEmpty()) {
            return "Not set: geocentre-based lookups.";
        }
        return headerValue(LATITUDE_KEYS) != null && headerValue(LONGITUDE_KEYS) != null
                ? "✓ Valid. This session's FITS header has a site, which is used except for SkyBoT." : "✓ Valid";
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
    private String headerNote(List<String> keys, String fieldText) {
        String[] header = headerValue(keys);
        if (header == null) {
            if (sessions.current() == null) {
                return " ";
            }
            if (keys.equals(ALTITUDE_KEYS)) {
                return fieldText.isEmpty() ? "Not set: assuming 0 m (no FITS elevation)."
                        : "✓ Used for this session (no altitude in the FITS headers).";
            }
            return fieldText.isEmpty() ? "Not set, and no site in the FITS headers." : "✓ Used for this session (no site in the FITS headers).";
        }
        return "This session's FITS header has " + header[1] + " (" + header[0] + "); that value is used.";
    }

    /** First value of the given keywords in the imported frames, as {key, value}; null when none has one. */
    private String[] headerValue(List<String> keys) {
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
                if (keys.equals(ALTITUDE_KEYS) && ObservingSite.parseAltitude(value) == null) continue;
                if (value != null && !value.trim().replace("'", "").isEmpty()) {
                    return new String[]{key, value.trim().replace("'", "").trim()};
                }
            }
        }
        return null;
    }

    private void fillSiteFromHeader() {
        FitsFileInformation selected = frames.getSelectedFileInformation();
        FitsFileInformation[] files = selected != null ? new FitsFileInformation[]{selected} : importedFiles();
        String lat = null;
        String lon = null;
        String altitude = null;
        if (files != null) {
            for (FitsFileInformation file : files) {
                Map<String, String> header = file.getFitsHeader();
                if (header == null) {
                    continue;
                }
                lat = lat != null ? lat : firstValue(header, LATITUDE_KEYS);
                lon = lon != null ? lon : firstValue(header, LONGITUDE_KEYS);
                altitude = altitude != null ? altitude : firstValue(header, ALTITUDE_KEYS);
            }
        }
        if (lat == null && lon == null && altitude == null) {
            footerMessageLabel.setText("The FITS header has no site latitude, longitude or altitude.");
            return;
        }
        if (lat != null) {
            latTextField.setText(lat);
        }
        if (lon != null) {
            longTextField.setText(lon);
        }
        if (altitude != null) altitudeTextField.setText(altitude);
        footerMessageLabel.setText("Site filled from the FITS header.");
    }

    private static String firstValue(Map<String, String> header, List<String> keys) {
        for (String key : keys) {
            String value = header.get(key);
            if (keys.equals(ALTITUDE_KEYS) && ObservingSite.parseAltitude(value) == null) continue;
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
        if (closing.getAsBoolean()) return;
        AppConfig snapshot = config().copy();
        long expectedVersion = ++saveVersion;
        saveButton.setEnabled(false);
        footerMessageLabel.setText("Saving application defaults...");
        starDepthStatus.setText("Saving...");
        solarDepthStatus.setText("Saving...");
        storage.submit("Application defaults", () -> {
            applicationSettings.save(snapshot);
            return null;
        }).whenComplete((ignored, failure) -> EventQueue.invokeLater(() -> {
            if (closing.getAsBoolean()) return;
            if (failure == null) {
                savedObservatoryCode = nullToEmpty(snapshot.observatoryCode);
                savedLat = nullToEmpty(snapshot.siteLat);
                savedLong = nullToEmpty(snapshot.siteLong);
                savedAltitude = nullToEmpty(snapshot.siteAltitude);
                savedSites = ObservingSites.copyOf(snapshot.observingSites);
                savedSiteName = nullToEmpty(snapshot.observingSite);
                savedAstap = nullToEmpty(snapshot.astapExecutablePath);
                savedAstrometryUseOwnKey = snapshot.astrometryNetUseOwnKey;
                savedAstrometryApiKey = nullToEmpty(snapshot.astrometryNetApiKey);
                savedLocalSolverAddress = nullToEmpty(snapshot.localSolverAddress);
                savedLocalSolverApiKey = nullToEmpty(snapshot.localSolverApiKey);
                savedStarDepth = snapshot.skyCatalogueStarMagnitude();
                savedSolarDepth = snapshot.solarSystemMagnitude();
                savedGpuMode = snapshot.gpuMode();
                savedGpuDeviceId = snapshot.gpuDeviceId();
            }
            updateSavedState();
            if (saveVersion != expectedVersion) return;
            saveButton.setEnabled(true);
            starDepthStatus.setForeground(failure == null ? UiStyle.helpColor() : warningColor());
            starDepthStatus.setText(failure == null ? "✓ Saved · used by the next Fetch Sky Catalogue"
                    : "Cannot save: " + failure.getMessage());
            solarDepthStatus.setForeground(failure == null ? UiStyle.helpColor() : warningColor());
            solarDepthStatus.setText(failure == null ? "✓ Saved · used by the next Fetch Asteroids and Comets"
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
        AppConfig config = config();
        config.observingSites = ObservingSites.copyOf(savedSites);
        config.observatoryCode = savedObservatoryCode;
        config.siteLat = savedLat;
        config.siteLong = savedLong;
        config.siteAltitude = savedAltitude;
        config.observingSite = savedSiteName;
        ObservingSites.ensure(config);
        showSite();
        footerMessageLabel.setText("Reverted to the saved values.");
    }

    // ==========================================
    // NAMED SITES
    // ==========================================

    /** Fills the site list and the fields from the configuration; the caller sets {@link #loading}. */
    private void fillSite() {
        AppConfig config = config();
        siteNames.removeAllElements();
        for (ObservingSites.Site site : config.observingSites) siteNames.addElement(site.name);
        siteNames.setSelectedItem(config.observingSite);
        siteNameField.setText(nullToEmpty(config.observingSite));
        observatoryCodeTextField.setText(nullToEmpty(config.observatoryCode));
        latTextField.setText(nullToEmpty(config.siteLat));
        longTextField.setText(nullToEmpty(config.siteLong));
        altitudeTextField.setText(nullToEmpty(config.siteAltitude));
        deleteSiteButton.setEnabled(config.observingSites.size() > 1);
    }

    /** Shows the configuration's selected site and tells the readout, after a choice, an addition, a removal or Revert. */
    private void showSite() {
        loading = true;
        try {
            fillSite();
        } finally {
            loading = false;
        }
        validateAll();
        updateSavedState();
        fetchSettingsChanged.run();
    }

    private void chooseSite(String name) {
        if (name.equals(config().observingSite)) return;
        if (!ObservingSites.select(config(), name)) return;
        showSite();
        footerMessageLabel.setText("Using the site " + name + ".");
    }

    private void addSite() {
        if (applicationSettings == null) return;
        ObservingSites.Site site = ObservingSites.add(config());
        showSite();
        footerMessageLabel.setText("Added " + site.name + ": name it and enter its details, then Save.");
        siteNameField.requestFocusInWindow();
        siteNameField.selectAll();
    }

    private void deleteSite() {
        if (applicationSettings == null || config().observingSites.size() < 2) return;
        String name = config().observingSite;
        int answer = JOptionPane.showConfirmDialog(this, "Delete the site " + name + "?", "Delete site",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer != JOptionPane.OK_OPTION || !ObservingSites.remove(config(), name)) return;
        showSite();
        footerMessageLabel.setText("Deleted " + name + ". Save keeps the change; Revert brings it back.");
    }

    /** A valid name renames the selected site at once and in the list; an invalid one keeps the last valid name. */
    private void siteNameEdited() {
        if (loading || applicationSettings == null) return;
        String name = siteNameField.getText().trim();
        if (ObservingSites.nameProblem(config(), name) == null && !name.equals(config().observingSite)) {
            ObservingSites.rename(config(), name);
            loading = true;
            try {
                int index = siteCombo.getSelectedIndex();
                if (index >= 0) {
                    siteNames.removeElementAt(index);
                    siteNames.insertElementAt(name, index);
                    siteNames.setSelectedItem(name);
                }
            } finally {
                loading = false;
            }
            fetchSettingsChanged.run();
            footerMessageLabel.setText(" ");
        }
        validateAll();
        updateSavedState();
    }

    private void updateSavedState() {
        boolean fieldsMatchConfig = observatoryCodeTextField.getText().trim().equals(nullToEmpty(config().observatoryCode))
                && latTextField.getText().trim().equals(nullToEmpty(config().siteLat))
                && longTextField.getText().trim().equals(nullToEmpty(config().siteLong))
                && altitudeTextField.getText().trim().equals(nullToEmpty(config().siteAltitude))
                && siteNameField.getText().trim().equals(nullToEmpty(config().observingSite));
        if (!fieldsMatchConfig) {
            footerStateLabel.setForeground(warningColor());
            footerStateLabel.setText("● Fix the marked field; the last valid value stays in use");
        } else if (hasUnsavedDefaults()) {
            footerStateLabel.setForeground(warningColor());
            footerStateLabel.setText("● Unsaved application defaults (in use)");
        } else {
            footerStateLabel.setForeground(UiStyle.helpColor());
            footerStateLabel.setText("✓ Saved settings in use");
        }
    }

    private boolean hasUnsavedDefaults() {
        return !nullToEmpty(config().observatoryCode).equals(savedObservatoryCode)
                || !nullToEmpty(config().siteLat).equals(savedLat)
                || !nullToEmpty(config().siteLong).equals(savedLong)
                || !nullToEmpty(config().siteAltitude).equals(savedAltitude)
                || !ObservingSites.copyOf(config().observingSites).equals(savedSites)
                || !nullToEmpty(config().observingSite).equals(savedSiteName)
                || !nullToEmpty(config().astapExecutablePath).equals(savedAstap)
                || config().astrometryNetUseOwnKey != savedAstrometryUseOwnKey
                || !nullToEmpty(config().astrometryNetApiKey).equals(savedAstrometryApiKey)
                || !nullToEmpty(config().localSolverAddress).equals(savedLocalSolverAddress)
                || !nullToEmpty(config().localSolverApiKey).equals(savedLocalSolverApiKey)
                || config().skyCatalogueStarMagnitude() != savedStarDepth
                || config().solarSystemMagnitude() != savedSolarDepth
                || !config().gpuMode().equals(savedGpuMode)
                || !config().gpuDeviceId().equals(savedGpuDeviceId);
    }

    /** Keeps the new star depth at once, like the ASTAP path. */
    private void starDepthChanged() {
        if (loading) {
            return;
        }
        config().skyCatalogueStarMagnitude = ((Number) starDepthSpinner.getValue()).doubleValue();
        fetchSettingsChanged.run();
        updateSavedState();
        saveDefaults(false);
    }

    /** Keeps the new asteroid and comet depth at once, like the star depth. */
    private void solarDepthChanged() {
        if (loading) {
            return;
        }
        config().solarSystemMagnitude = ((Number) solarDepthSpinner.getValue()).doubleValue();
        fetchSettingsChanged.run();
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
        if (closing.getAsBoolean() || astrometryCheck != null) {
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
                if (astrometryCheck != this || closing.getAsBoolean()) {
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

    // ==========================================
    // LOCAL SOLVER (ASTROMETRY.NET API)
    // ==========================================

    /** Applies the address and the key at once and keeps them for the next start, like the account. */
    private void localSolverChanged() {
        if (loading) {
            return;
        }
        AppConfig config = config();
        config.localSolverAddress = localSolverAddressField.getText().trim();
        config.localSolverApiKey = new String(localSolverKeyField.getPassword()).trim();
        showLocalSolverNote();
        updateSavedState();
        fetchSettingsChanged.run();
        saveDefaults(false);
    }

    private void showLocalSolverNote() {
        String typed = localSolverAddressField.getText();
        var solver = AstrometryNetAccount.localSolverAddress(typed);
        localSolverCheckButton.setEnabled(solver != null && localSolverCheck == null);
        if (typed == null || typed.isBlank()) {
            showStatus(localSolverStatus, null, wrapStatus("Not set. The Astrometry.net (local) option is offered once an address is entered."));
        } else if (solver == null) {
            showStatus(localSolverStatus, wrapStatus(AstrometryNetAccount.localSolverAddressProblem(typed)), null);
        } else {
            showStatus(localSolverStatus, null, wrapStatus("Local solves go to " + solver));
        }
    }

    /** Logs in to the local solver on a worker thread. No frame is sent. */
    private void checkLocalSolver() {
        if (closing.getAsBoolean() || localSolverCheck != null) {
            return;
        }
        AppConfig solver = config().copy();
        var checked = AstrometryNetAccount.localSolver(solver);
        if (checked == null) {
            showLocalSolverNote();
            return;
        }
        localSolverCheckButton.setEnabled(false);
        showStatus(localSolverStatus, null, wrapStatus("Checking with " + checked.address() + "…"));
        localSolverCheck = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                AstrometryNetAccount.checkLocalLogin(solver);
                return null;
            }

            @Override
            protected void done() {
                if (localSolverCheck != this || closing.getAsBoolean()) {
                    return;
                }
                localSolverCheck = null;
                if (!checked.equals(AstrometryNetAccount.localSolver(config()))) {
                    // The address or the key changed during the check, so this answer is about the previous one.
                    showLocalSolverNote();
                    return;
                }
                localSolverCheckButton.setEnabled(true);
                try {
                    get();
                    showStatus(localSolverStatus, null, wrapStatus("✓ The local solver at " + checked.address() + " accepted the login"));
                } catch (CancellationException ignored) {
                    showLocalSolverNote();
                } catch (ExecutionException e) {
                    Throwable failure = e.getCause() != null ? e.getCause() : e;
                    showStatus(localSolverStatus, wrapStatus(failure.getMessage() != null ? failure.getMessage() : failure.toString()), null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        localSolverCheck.execute();
    }

    /** A login failure can be a long sentence, so the status wraps at the width of the account controls. */
    private void showAstrometryStatus(String problem, String note) {
        showStatus(astrometryStatus, problem == null ? null : wrapStatus(problem), note == null ? null : wrapStatus(note));
    }

    /** A Plate Solving status, wrapped and aligned to the right edge of the controls above it. */
    private static String wrapStatus(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div align='right'>" + escaped + "</div></html>";
    }

    // ==========================================
    // ASTAP
    // ==========================================

    private void chooseAstap() {
        JFileChooser fc = WindowSizing.fileChooser(null);
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
        if (closing.getAsBoolean()) return;
        astapCheckButton.setEnabled(false);
        showAstapStatus(true, "Checking ASTAP installation...");
        astapChecks.check(path, select);
    }

    private void astapChecked(AstapInstallationCheck.Request request, AstapInstallationCheck.Result result) {
        if (closing.getAsBoolean()) return;
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
        if (closing.getAsBoolean()) return;
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
        SwingWorker<Void, Void> local = localSolverCheck;
        localSolverCheck = null;
        if (local != null) {
            local.cancel(true);
        }
        SwingWorker<GpuDiagnostics.ProbeSummary, Void> probe = gpuProbeWorker;
        gpuProbeWorker = null;
        if (probe != null) {
            probe.cancel(true);
        }
        SwingWorker<GpuDiagnostics.TestResult, Void> gpuTest = gpuTestWorker;
        gpuTestWorker = null;
        if (gpuTest != null) {
            gpuTest.cancel(true);
        }
    }

    /** Star databases found in the folder, by name (e.g. D50), with their file counts. */
    static Map<String, Integer> starDatabases(File folder) {
        return AstapInstallationCheck.starDatabases(folder);
    }

    private void showAstapStatus(boolean ok, String text) {
        astapStatusLabel.setForeground(ok ? UiStyle.helpColor() : warningColor());
        boolean download = text.contains("Download");
        astapStatusLabel.setText(wrapStatus(text));
        astapStatusLabel.setToolTipText(download ? ASTAP_DOWNLOAD_URL : null);
        astapDownloadButton.setVisible(download);
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
        return applicationSettings.getConfig();
    }

    void sessionChanged() {
        validateAll();
        updateSavedState();
    }

    private FitsFileInformation[] importedFiles() {
        var session = sessions.current();
        return session == null ? null : session.getFiles();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Color warningColor() {
        Color color = UIManager.getColor("Actions.Yellow");
        return color != null ? color : new Color(0xE0A030);
    }

    private static JLabel statusLabel() {
        WrappingHelpLabel label = new WrappingHelpLabel(" ", 330);
        label.setFont(UiStyle.bodyFont());
        return label;
    }

    private static void showStatus(JLabel label, String problem, String note) {
        if (problem != null) {
            label.setForeground(warningColor());
            label.setText(problem);
        } else {
            label.setForeground(UiStyle.helpColor());
            label.setText(note);
        }
    }

    private static JPanel fieldWithStatus(JComponent editor, JLabel status) {
        return rightColumn(editor, status);
    }

    /**
     * Pins an editor to the right edge of the settings row and puts its note underneath. The editor keeps the width of
     * its text, so every field and spinner meets the same right edge.
     */
    private static JPanel rightColumn(JComponent editor, JComponent below) {
        JPanel column = new JPanel(new BorderLayout(0, 4));
        JPanel editorRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        editorRow.setOpaque(false);
        editorRow.add(editor);
        column.add(editorRow, BorderLayout.NORTH);
        column.add(below, BorderLayout.CENTER);
        return column;
    }

    /**
     * Groups one category in a titled rectangle. The height follows the controls, as on Prepare an observing session.
     */
    private static JPanel section(String title) {
        JPanel panel = new JPanel() {
            /** Spare viewport height stays below the form. The box does not become a tall empty area. */
            @Override public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(UiStyle.space(7), UiStyle.space(9), UiStyle.space(7), UiStyle.space(9))));
        return panel;
    }

    /** A status above a link, both against the right edge, under the controls they belong to. */
    private static JPanel verticalStack(JComponent top, JComponent bottom) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        top.setAlignmentX(Component.RIGHT_ALIGNMENT);
        bottom.setAlignmentX(Component.RIGHT_ALIGNMENT);
        // A link's text meets the same right edge as the status text, without the button's side padding.
        if (bottom instanceof AbstractButton link) link.setMargin(new Insets(2, 0, 2, 0));
        panel.add(top);
        panel.add(Box.createVerticalStrut(4));
        panel.add(bottom);
        return panel;
    }

    // ==========================================
    // GPU ACCELERATION (FAINT MOVERS)
    // ==========================================

    private void gpuModeChanged() {
        if (loading || applicationSettings == null) return;
        GpuModeChoice selected = (GpuModeChoice) gpuModeCombo.getSelectedItem();
        if (selected == null) return;
        config().gpuMode = selected.code();
        updateGpuControlsEnabled(config());
        refreshGpuStatusLine();
        fetchSettingsChanged.run();
        updateSavedState();
        saveDefaults(false);
    }

    private void gpuDeviceChanged() {
        if (loading || applicationSettings == null) return;
        GpuDeviceChoice selected = (GpuDeviceChoice) gpuDeviceCombo.getSelectedItem();
        if (selected == null) return;
        config().gpuDeviceId = selected.id();
        refreshGpuStatusLine();
        fetchSettingsChanged.run();
        updateSavedState();
        saveDefaults(false);
    }

    private void selectGpuMode(String mode) {
        String normalized = AppConfig.normalizeGpuMode(mode);
        for (int i = 0; i < gpuModeCombo.getItemCount(); i++) {
            GpuModeChoice item = gpuModeCombo.getItemAt(i);
            if (item.code().equals(normalized)) {
                gpuModeCombo.setSelectedIndex(i);
                return;
            }
        }
        gpuModeCombo.setSelectedIndex(0);
    }

    private void rebuildGpuDeviceChoices(String selectedDeviceId) {
        loading = true;
        try {
            gpuDeviceModel.removeAllElements();
            GpuDeviceChoice auto = new GpuDeviceChoice("", "Auto (fastest verified)");
            gpuDeviceModel.addElement(auto);
            GpuDeviceChoice selectedChoice = auto;
            String targetId = selectedDeviceId == null ? "" : selectedDeviceId.trim();
            boolean found = targetId.isEmpty();
            if (latestGpuProbe != null && latestGpuProbe.devices() != null) {
                for (OpenClDevice device : latestGpuProbe.devices()) {
                    String label = device.name() + " · " + device.formattedMemory() + " · " + device.openclVersion()
                            + (device.hostUnifiedMemory() ? " · shared RAM" : "");
                    GpuDeviceChoice choice = new GpuDeviceChoice(device.stableId(), label);
                    gpuDeviceModel.addElement(choice);
                    if (!found && device.stableId().equals(targetId)) {
                        selectedChoice = choice;
                        found = true;
                    }
                }
            }
            if (!found) {
                GpuDeviceChoice missing = new GpuDeviceChoice(targetId,
                        latestGpuProbe == null ? "Saved device (" + targetId + ")" : "Saved device (unavailable: " + targetId + ")");
                gpuDeviceModel.addElement(missing);
                selectedChoice = missing;
            }
            gpuDeviceCombo.setSelectedItem(selectedChoice);
            gpuDeviceCombo.setToolTipText(selectedChoice.label());
        } finally {
            loading = false;
        }
    }

    private void updateGpuControlsEnabled(AppConfig config) {
        boolean cpuOnly = "CPU".equals(config.gpuMode());
        boolean hasSavedId = !config.gpuDeviceId().isEmpty();
        boolean hasDevices = latestGpuProbe == null || (latestGpuProbe.devices() != null && !latestGpuProbe.devices().isEmpty());
        gpuDeviceCombo.setEnabled(!cpuOnly && (hasDevices || hasSavedId));
        gpuTestButton.setEnabled(gpuTestWorker == null && !closing.getAsBoolean());
    }

    private void probeGpuDevices() {
        if (applicationSettings == null || closing.getAsBoolean()) return;
        if (gpuProbeWorker != null) gpuProbeWorker.cancel(true);
        if (gpuTestWorker == null && !"CPU".equals(config().gpuMode())) {
            gpuStatusLabel.setForeground(UiStyle.helpColor());
            gpuStatusLabel.setText("Checking OpenCL devices...");
        }
        SwingWorker<GpuDiagnostics.ProbeSummary, Void> worker = new SwingWorker<>() {
            @Override protected GpuDiagnostics.ProbeSummary doInBackground() {
                return GpuDiagnostics.probeDevices();
            }

            @Override protected void done() {
                if (gpuProbeWorker == this) gpuProbeWorker = null;
                if (isCancelled() || closing.getAsBoolean() || applicationSettings == null) return;
                try {
                    latestGpuProbe = get();
                } catch (CancellationException | InterruptedException e) {
                    return;
                } catch (ExecutionException e) {
                    latestGpuProbe = new GpuDiagnostics.ProbeSummary(List.of(), "OpenCL probe failed: " + e.getCause().getMessage());
                }
                rebuildGpuDeviceChoices(config().gpuDeviceId());
                updateGpuControlsEnabled(config());
                if (gpuTestWorker == null) refreshGpuStatusLine();
                fetchSettingsChanged.run();
            }
        };
        gpuProbeWorker = worker;
        worker.execute();
    }

    private void runGpuTest() {
        if (applicationSettings == null || closing.getAsBoolean()) return;
        if (gpuTestWorker != null) gpuTestWorker.cancel(true);
        String preferredId = config().gpuDeviceId();
        gpuTestButton.setEnabled(false);
        gpuStatusLabel.setForeground(UiStyle.helpColor());
        gpuStatusLabel.setText("Testing OpenCL kernel and CPU-vs-GPU benchmark...");
        SwingWorker<GpuDiagnostics.TestResult, Void> worker = new SwingWorker<>() {
            @Override protected GpuDiagnostics.TestResult doInBackground() {
                return GpuDiagnostics.testDevice(preferredId, ComputeOptionsMapper.defaultCacheDirectory(), cpuWorkersForDiagnostics());
            }

            @Override protected void done() {
                if (gpuTestWorker == this) gpuTestWorker = null;
                if (isCancelled() || closing.getAsBoolean() || applicationSettings == null) return;
                gpuTestButton.setEnabled(true);
                try {
                    GpuDiagnostics.TestResult result = get();
                    if (result.device() != null && result.passed()) {
                        String tested = result.testedIso() == null || result.testedIso().isEmpty() ? "" : " · tested " + result.testedIso();
                        gpuStatusLabel.setForeground(UiStyle.helpColor());
                        gpuStatusLabel.setText(wrapStatus("✓ " + result.message() + " · " + result.device().openclVersion()
                                + " · " + result.device().name() + " · driver " + result.device().driverVersion() + tested));
                    } else {
                        gpuStatusLabel.setForeground(warningColor());
                        gpuStatusLabel.setText(wrapStatus(result.message()));
                    }
                    if (latestGpuProbe == null) probeGpuDevices();
                    fetchSettingsChanged.run();
                } catch (CancellationException | InterruptedException ignored) {
                } catch (ExecutionException e) {
                    gpuStatusLabel.setForeground(warningColor());
                    gpuStatusLabel.setText(wrapStatus("GPU test failed: " + e.getCause().getMessage()));
                }
            }
        };
        gpuTestWorker = worker;
        worker.execute();
    }

    private void refreshGpuStatusLine() {
        if (applicationSettings == null) return;
        AppConfig cfg = config();
        if ("CPU".equals(cfg.gpuMode())) {
            gpuStatusLabel.setForeground(UiStyle.helpColor());
            gpuStatusLabel.setText(wrapStatus("CPU (Always CPU setting)"));
            return;
        }
        if (latestGpuProbe == null) {
            gpuStatusLabel.setForeground(UiStyle.helpColor());
            gpuStatusLabel.setText("Checking OpenCL devices...");
            return;
        }
        GpuDiagnostics.ResolvedStatus resolved = GpuDiagnostics.resolveFromProbe(
                ComputeOptionsMapper.from(cfg), latestGpuProbe, cpuWorkersForDiagnostics());
        OpenClDevice dev = resolved.activeDevice();
        if (dev == null) {
            gpuStatusLabel.setForeground(warningColor());
            gpuStatusLabel.setText(wrapStatus(resolved.statusLine()));
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(dev.openclVersion()).append(" · ").append(dev.name()).append(" · driver ").append(dev.driverVersion());
        if (!resolved.testedIso().isEmpty()) sb.append(" · tested ").append(resolved.testedIso());
        if (Double.isFinite(resolved.cachedSpeedup())) {
            sb.append(String.format(Locale.ROOT, " (%.1f× vs CPU)", resolved.cachedSpeedup()));
        }
        if (!resolved.usingGpu()) {
            gpuStatusLabel.setForeground(warningColor());
            gpuStatusLabel.setText(wrapStatus(resolved.statusLine() + " · " + sb));
        } else {
            gpuStatusLabel.setForeground(UiStyle.helpColor());
            gpuStatusLabel.setText(wrapStatus((resolved.testedIso().isEmpty() ? resolved.statusLine() + " · " : "✓ ") + sb));
        }
    }

    private static int cpuWorkersForDiagnostics() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private record GpuModeChoice(String code, String label) {
        @Override public String toString() { return label; }
    }

    private record GpuDeviceChoice(String id, String label) {
        @Override public String toString() { return label; }
    }

    private JPanel createConfigRow(String title, String description, JComponent inputControl) {
        JLabel titleLabel = new JLabel(title);
        AccessibleControls.label(titleLabel, inputControl, description);
        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        inputWrapper.add(inputControl);
        return new SettingsRowPanel(titleLabel, description, inputWrapper, settingsControlColumn);
    }
}
