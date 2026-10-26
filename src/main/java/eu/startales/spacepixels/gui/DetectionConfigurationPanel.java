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

import eu.startales.spacepixels.config.DetectionSettingsPresentation;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import eu.startales.spacepixels.session.ApplicationStorage;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.VariableStarReportOptions;
import eu.startales.spacepixels.config.SpacePixelsSettingsFiles;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.config.DetectionSetting;
import eu.startales.spacepixels.config.ResidualAnalysisSettings;
import eu.startales.spacepixels.config.CoreDetectionSettings;
import eu.startales.spacepixels.config.QualitySettings;
import eu.startales.spacepixels.config.VariableStarsSettings;
import eu.startales.spacepixels.config.SlowMoversSettings;
import eu.startales.spacepixels.config.AnomalyDetectionSettings;
import eu.startales.spacepixels.config.MovingObjectsSettings;
import eu.startales.spacepixels.config.StreakDetectionSettings;
import eu.startales.spacepixels.detection.AutoTuneCandidatePoolBuilder;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.tasks.AutoTuneTask;
import eu.startales.spacepixels.tasks.StarMaskStackTask;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.util.DebugOutput;

/** Owns typed detection and application settings bindings, preserving hidden profile fields and shared viewer models. */
public class DetectionConfigurationPanel extends JPanel {

    private static final Logger LOGGER = Logger.getLogger(DetectionConfigurationPanel.class.getName());

    private final SessionAccess sessions;
    private final JobCoordinator jobs;
    private final ApplicationStorage storage;
    private final JFrame windowFrame;
    private final BooleanSupplier closing;
    private final Runnable showThisTab;
    private final ActiveSettingsReadout readout;
    private final FrameList frames;

    private volatile DetectionConfig jTransientConfig;
    private volatile int autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
    // The visualization settings in use: the values of the visualization page and the variable-star report
    // presentation, which is saved with the detection profile. Runs capture it when they start; viewers draw with it.
    private VisualizationSettings visualization = VisualizationSettings.defaults();
    private final File detectionProfileFile = new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
    private final File legacyDetectionProfileFile = new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.LEGACY_FILENAME);
    private final File visualizationPreferencesFile = new File(System.getProperty("user.home"), SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);

    private JSpinner spinDetectionSigma, spinMinPixels, spinGrowSigma;
    private JCheckBox chkEnableSlowMovers;
    private JSpinner spinMasterSigma, spinMasterGrowSigma, spinMasterMinPix, spinMasterSlowMoverMinPixels, spinMasterSlowMoverSigma, spinMasterSlowMoverGrowSigma;
    private JSpinner spinSlowMoverMinAxisRatio, spinSlowMoverMaxAxisRatio, spinSlowMoverMinFillFactor;
    private JSpinner spinSlowMoverMedianSupportOverlapFraction, spinSlowMoverMedianSupportMaxOverlapFraction;
    private JSpinner spinSlowMoverMinFrameSupport, spinSlowMoverMaxStationaryLikelihood;
    private JSpinner spinStreakMinElong, spinStreakMinPix, spinSingleStreakMinPeakSigma, spinStreakTimeConsistencyTolerance;
    private JCheckBox chkEnableFaintStreakFragments;
    private JSpinner spinFaintStreakLineTolerance;

    // --- TrackLinker Spinners ---
    private JCheckBox chkStrictExposureKinematics, chkEnableGeometricTrackLinking;
    private JSpinner spinStarJitter, spinMaxMaskOverlapFraction, spinPredTol, spinAngleTol;
    private JSpinner spinTrackMinFrameRatio, spinAbsMaxPoints, spinMaxJump;
    private JSpinner spinRhythmVar, spinRhythmMinRatio, spinRhythmStatThresh, spinTimeBasedVelocityTolerance;
    private JSpinner spinMaxFwhmRatio, spinMaxSurfaceBrightnessRatio;

    // --- Anomaly Rescue ---
    private JCheckBox chkEnableAnomalyRescue;
    private JSpinner spinAnomalyMinPeakSigma, spinAnomalyMinPixels, spinAnomalyMinIntegratedSigma, spinAnomalyMinIntegratedPixels, spinAnomalyMinPeakSigmaFloor;

    // --- Residual transient analysis ---
    private JCheckBox chkEnableResidualTransientAnalysis, chkEnableLocalRescueCandidates, chkEnableLocalActivityClusters;
    private JCheckBox chkEnableCommonMotionRejection;
    private JCheckBox chkEnableCameraFixedRejection;
    private JSpinner spinLocalActivityClusterRadiusPixels, spinLocalActivityClusterMinFrames, spinCommonMotionShiftTolerance;

    // --- Quality Control Spinners ---
    private JSpinner spinStarCountSigma, spinFwhmSigma;
    private JSpinner spinEccentricitySigma, spinBackgroundSigma;
    private JSpinner spinMinBgDevAdu;

    private JSpinner spinBrightStarEccentricitySigma;
    private JCheckBox chkEnableBrightStarEccentricityFilter;
    private JComboBox<String> cmbFrameRejection;
    private boolean syncingFrameRejection;

    // --- Variable-star photometry ---
    private JCheckBox chkEnableVariableStarDetection;
    private JSpinner spinVariableMaxCandidateCards;
    private JComboBox<VariableStarReportOptions.SortOrder> cmbVariableStarSortOrder;
    private JSpinner spinPhotometryMaxStars, spinPhotometryMinSnr;
    private JSpinner spinLinearityMaxConcentrationDrift, spinLinearityMinRangeMag;
    private JSpinner spinLinearityMaxFrameSlope;
    private JSpinner spinLinearityMaxFailingFrameFraction;
    private JSpinner spinVariableMinFrames, spinVariableMinSpanMinutes, spinVariableScoreSigma;
    private JSpinner spinVariableMinAmplitudeMag, spinVariableLimitedMinAmplitudeMag, spinVariableAmplitudeNoiseFactor;
    private JSpinner spinVariableMinPersistenceFrames, spinVariableMinSplitHalfCorrelation, spinVariableMaxApertureAmplitudeDifference;
    private JSpinner spinVariableMaxSystematicsCorrelation, spinVariableLocalRadiusPixels, spinVariableMaxLocalCorrelation;

    private JSpinner spinAutoTuneMaxCandidateFrames;

    private JSpinner spinStreakScale, spinStreakCentroidRad, spinPointBoxRad, spinBoxPad;
    private JSpinner spinAutoBlackSigma, spinAutoWhiteSigma, spinGifBlinkSpeed, spinCropPadding;

    /** Client property marking a section header label on a settings page. */
    static final String SECTION_HEADER = "spacepixels.sectionHeader";
    /** Client property marking a section header whose settings are shown only with "Show expert settings". */
    static final String EXPERT_SECTION = "spacepixels.expertSection";
    /** Frame rejection presets; each sets the five session outlier limits together. */
    private static final String[] FRAME_REJECTION_PRESETS = {"Lenient", "Normal", "Strict"};
    private static final String FRAME_REJECTION_CUSTOM = "Custom";

    private AutoTuneOverviewPanel overviewPanel;
    private SettingsNavigator navigator;

    private final List<SettingRow> settingRows = new ArrayList<>();
    private final List<DetectionSettingBinding<?>> typedBindings = new ArrayList<>();
    private final List<ApplicationSettingBinding<?>> reportBindings = new ArrayList<>();
    private final List<ApplicationSettingBinding<?>> tuningBindings = new ArrayList<>();
    private final List<ApplicationSettingBinding<?>> visualizationBindings = new ArrayList<>();
    // The title of each number setting, in page order, for the messages about its field.
    private final Map<JSpinner, String> inputTitles = new LinkedHashMap<>();
    private final Map<JSpinner, DetectionSetting<? extends Number>> coreDefinitions = new LinkedHashMap<>();
    private final Map<String, JSpinner> detectionInputs = new LinkedHashMap<>();
    private final JLabel footerStateLabel = new JLabel(" ");
    private final JLabel footerMessageLabel = new JLabel(" ");
    private final JButton saveButton = new JButton("Save");
    // The footer message: a note (saved, reverted, a setting moved to keep its pair in order), replaced by what to
    // enter while a field holds invalid text, after the name of a save or run that it stopped.
    private String footerNote = " ";
    private boolean footerNoteIsProblem;
    private String refusedAction;

    // Last saved state, for the unsaved-changes indicator and Revert.
    private DetectionConfig savedConfig;
    private int savedAutoTuneMaxCandidateFrames;
    private VisualizationSettings savedVisualization;
    private String savedSnapshot;
    private boolean suppressAutoApply;
    private final ViewerRegistry viewers;

    /**
     * Builds the EDT-owned settings editor. {@code readout} and {@code frames} are the main panel. Admitted jobs copy
     * the session and settings at start; {@code sessions} is only for the EDT commands.
     */
    public DetectionConfigurationPanel(SessionAccess sessions, JobCoordinator jobs, ApplicationStorage storage,
            JFrame windowFrame, BooleanSupplier closing, Runnable showThisTab, ActiveSettingsReadout readout,
            FrameList frames) {
        this(sessions, jobs, storage, windowFrame, closing, showThisTab, readout, frames, new ViewerRegistry());
    }

    public DetectionConfigurationPanel(SessionAccess sessions, JobCoordinator jobs, ApplicationStorage storage,
            JFrame windowFrame, BooleanSupplier closing, Runnable showThisTab, ActiveSettingsReadout readout,
            FrameList frames, ViewerRegistry viewers) {
        this.sessions = sessions;
        this.jobs = jobs;
        this.storage = storage;
        this.windowFrame = windowFrame;
        this.closing = closing;
        this.showThisTab = showThisTab;
        this.readout = readout;
        this.frames = frames;
        this.viewers = viewers != null ? viewers : new ViewerRegistry();
        loadPersistedSettings();

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        // Pages in the navigation list, grouped; the rows register themselves with the navigator as they are built.
        navigator = new SettingsNavigator();
        overviewPanel = new AutoTuneOverviewPanel(new OverviewHost());
        buildCoreSettings(overviewPanel);
        navigator.addPage(null, "Overview", overviewPanel, coreSearchTerms());
        navigator.addPage("DETECTION", "Streak Detection", buildStreakDetectionPanel(), List.of());
        navigator.addPage("DETECTION", "Quality Control", buildQualityPanel(), List.of());
        navigator.addPage("MOVING OBJECTS", "Track Linking", buildMovingObjectsPanel(), List.of());
        navigator.addPage("MOVING OBJECTS", "Anomaly Detection", buildAnomalyDetectionPanel(), List.of());
        navigator.addPage("MOVING OBJECTS", "Slow Movers", buildSlowMoversPanel(), List.of());
        navigator.addPage("MOVING OBJECTS", "Residual Analysis", buildResidualAnalysisPanel(), List.of());
        navigator.addPage("VARIABLE STARS", "Variable Stars", buildVariableStarsPanel(), List.of());
        navigator.addPage("REPORT", "Report Visualization", buildAdvancedVisualizationPanel(), List.of());

        add(navigator, BorderLayout.CENTER);
        buildAnalyses(overviewPanel);
        overviewPanel.bindCommands(frames.detectAction(), frames.previewAction());
        frames.setAutoTuneCommand(this::openOverviewAndRunAutoTune);

        setupFrameRejectionPreset();
        // Settings that only matter while their feature is on appear with it.
        showOnlyWhen(chkEnableGeometricTrackLinking, spinMaxJump, spinRhythmVar, spinRhythmMinRatio, spinRhythmStatThresh);
        showOnlyWhen(chkEnableFaintStreakFragments, spinFaintStreakLineTolerance);
        showOnlyWhen(chkEnableLocalActivityClusters, spinLocalActivityClusterRadiusPixels, spinLocalActivityClusterMinFrames);
        showOnlyWhen(chkEnableCommonMotionRejection, spinCommonMotionShiftTolerance);
        showOnlyWhen(chkEnableBrightStarEccentricityFilter, spinBrightStarEccentricitySigma);
        navigator.setExtraChangedCount("Overview", () -> overviewPanel.unsavedCoreCount());
        navigator.finishBuilding();
        // Ctrl+D runs Detect Moving Targets here too: the main window's command, with its enabled state.
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ctrl D"), "detectMovingTargets");
        getActionMap().put("detectMovingTargets", frames.detectAction());
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ctrl F"), "searchSettings");
        getActionMap().put("searchSettings", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                navigator.searchField().requestFocusInWindow();
                navigator.searchField().selectAll();
            }
        });

        // Changes apply to the session as soon as they are made; only saving for the next start is explicit.
        saveButton.setToolTipText("Save the detection profile and visualization preferences as the defaults for future startups.");
        saveButton.addActionListener(e -> savePersistedSettings());

        JButton revertBtn = new JButton("Revert");
        revertBtn.setToolTipText("Return every setting to the last saved state.");
        revertBtn.addActionListener(e -> revertToSaved());

        JButton loadDefaultsBtn = new JButton("Load Defaults");
        loadDefaultsBtn.setToolTipText("Reset the detection settings to the DetectionConfig defaults for this session. The saved profile is unchanged until you click Save; Revert undoes it.");
        loadDefaultsBtn.addActionListener(e -> {
            loadDetectionDefaults();
            showFooterNote("Defaults loaded. Revert restores the saved settings.");
        });

        footerStateLabel.setBorder(new EmptyBorder(0, 4, 0, 12));
        footerMessageLabel.setForeground(UiStyle.helpColor());
        JPanel footerText = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        footerText.add(footerStateLabel);
        footerText.add(footerMessageLabel);

        JPanel footerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton reviewRules = new JButton("Review rules…");
        reviewRules.setToolTipText("Review configuration and explicitly align supported consistency policies.");
        reviewRules.addActionListener(event -> reviewRules());
        footerButtons.add(reviewRules);
        footerButtons.add(revertBtn);
        footerButtons.add(loadDefaultsBtn);
        footerButtons.add(saveButton);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(10, 0, 0, 0));
        footer.add(footerText, BorderLayout.CENTER);
        footer.add(footerButtons, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);

        installAutoApply(navigator);
        applySettingsToMemory();
        rememberSavedState();
        updateSavedState();
    }

    /** The settings the Auto-Tuner chooses, shown on the Overview page. */
    private void buildCoreSettings(AutoTuneOverviewPanel overview) {
        spinDetectionSigma = addCoreDouble(overview, "Detection in each frame", CoreDetectionSettings.DETECTION_SIGMA_MULTIPLIER, 1.0, 20.0, 0.1);
        spinGrowSigma = addCoreDouble(overview, null, CoreDetectionSettings.GROW_SIGMA_MULTIPLIER, 0.1, 20.0, 0.1);
        spinMinPixels = addCoreInteger(overview, null, CoreDetectionSettings.MIN_DETECTION_PIXELS, 1, 2000, 1);
        overview.addPreviewAction();

        spinMasterSigma = addCoreDouble(overview, "Star mask (master map)", CoreDetectionSettings.MASTER_SIGMA_MULTIPLIER, 0.5, 15.0, 0.1);
        spinMasterGrowSigma = addCoreDouble(overview, null, CoreDetectionSettings.MASTER_GROW_SIGMA_MULTIPLIER, 0.0, 15.0, 0.1);
        spinMasterMinPix = addCoreInteger(overview, null, CoreDetectionSettings.MASTER_MIN_DETECTION_PIXELS, 1, 2000, 1);
        spinMaxMaskOverlapFraction = addCoreDouble(overview, null, CoreDetectionSettings.MAX_MASK_OVERLAP_FRACTION, 0.0, 1.0, 0.01);
        spinStarJitter = addCoreDouble(overview, null, CoreDetectionSettings.MAX_STAR_JITTER, 0.0, 20.0, 0.1);

        JButton testStarMaskButton = new JButton("Test Star Mask…");
        testStarMaskButton.setToolTipText("<html>Build the master stack as a detection run would, then try star mask settings "
                + "and see the resulting veto mask on the stack, with statistics.</html>");
        testStarMaskButton.addActionListener(e -> openStarMaskExplorer());
        overview.addCoreAction(testStarMaskButton);
    }

    /** Registers an Overview double with its original spinner model and typed field ownership. */
    private JSpinner addCoreDouble(AutoTuneOverviewPanel overview, String group, DetectionSetting<Double> setting,
            double min, double max, double step) {
        JSpinner input = createSpinner(doubleSpinnerModel(setting.read(jTransientConfig), min, max, step));
        registerTypedBinding(setting, input, () -> ((Number) input.getValue()).doubleValue(),
                value -> setSpinnerValueClamped(input, value.doubleValue()));
        addCoreSetting(overview, group, setting, input);
        return input;
    }

    /** Registers an Overview integer without changing the numeric type shared with Preview Frame. */
    private JSpinner addCoreInteger(AutoTuneOverviewPanel overview, String group, DetectionSetting<Integer> setting,
            int min, int max, int step) {
        JSpinner input = createSpinner(intSpinnerModel(setting.read(jTransientConfig), min, max, step));
        registerTypedBinding(setting, input, () -> ((Number) input.getValue()).intValue(),
                value -> setSpinnerValueClamped(input, value.intValue()));
        addCoreSetting(overview, group, setting, input);
        return input;
    }

    /** Uses the same definition for Overview labels, saved markers and shared preview guidance. */
    private void addCoreSetting(AutoTuneOverviewPanel overview, String group,
            DetectionSetting<? extends Number> setting, JSpinner input) {
        overview.addCoreSetting(group, setting.title(), setting.help(), input);
        inputTitles.put(input, setting.title());
        coreDefinitions.put(input, setting);
    }

    /** Derives Overview search terms from registered definitions instead of a parallel title list. */
    private List<String> coreSearchTerms() {
        List<String> terms = new ArrayList<>(List.of("Auto-Tune"));
        for (DetectionSetting<?> setting : coreDefinitions.values()) {
            terms.add(setting.id());
            terms.add(setting.title());
            terms.add(setting.help());
        }
        return terms;
    }

    /** Detached input specifications for Preview Frame's three per-frame trial thresholds. */
    List<TrialSettingsControls.Input> perFrameDetectionSettings() {
        List<TrialSettingsControls.Input> settings = new ArrayList<>();
        for (JSpinner spinner : new JSpinner[]{spinDetectionSigma, spinGrowSigma, spinMinPixels}) {
            settings.add(TrialSettingsControls.Input.copyOf(coreDefinitions.get(spinner), (SpinnerNumberModel) spinner.getModel()));
        }
        return settings;
    }

    /** Merges only edited preview fields on the EDT; normal pair rules apply without advancing the saved baseline. */
    DetectionConfig applyTrialSettings(Map<String, Number> changes) {
        if (closing.getAsBoolean() || jobs.current() != null) return null;
        Map<JSpinner, Number> values = new LinkedHashMap<>();
        for (Map.Entry<String, Number> change : changes.entrySet()) {
            JSpinner input = detectionInputs.get(change.getKey());
            DetectionSetting<? extends Number> setting = coreDefinitions.get(input);
            if (setting == null || change.getValue() == null || !Double.isFinite(change.getValue().doubleValue())) {
                throw new IllegalArgumentException("Unknown or invalid preview setting: " + change.getKey());
            }
            values.put(input, setting.type().cast(change.getValue()));
        }
        boolean wasSuppressed = suppressAutoApply;
        suppressAutoApply = true;
        try {
            values.forEach(JSpinner::setValue);
        } finally {
            suppressAutoApply = wasSuppressed;
        }
        onSettingEdited();
        return settingsInUse();
    }

    /** The analyses of a run, each switched on the Overview and in step with the checkbox on its detailed tab. */
    private void buildAnalyses(AutoTuneOverviewPanel overview) {
        overview.addAnalysis("Moving objects & streaks", "asteroids, satellites and other objects that move between frames; always on",
                null, "Track Linking");
        overview.addAnalysis("Slow movers", "objects that barely move during the session (comets, distant asteroids), found in the stacked frames",
                chkEnableSlowMovers.getModel(), "Slow Movers");
        overview.addAnalysis("Anomaly rescue", "bright or large events seen in a single frame (flashes, glints)",
                chkEnableAnomalyRescue.getModel(), "Anomaly Detection");
        overview.addAnalysis("Residual analysis", "a last look at leftover detections for weak candidates and local activity",
                chkEnableResidualTransientAnalysis.getModel(), "Residual Analysis");
        overview.addAnalysis("Variable-star photometry", "light curves of the field stars and variable-star candidates, matched against AAVSO VSX "
                + "when a frame is plate-solved; each run uses its normal readiness checks", chkEnableVariableStarDetection.getModel(), "Variable Stars");
    }

    // ==========================================
    // STAR MASK EXPLORER
    // ==========================================

    /**
     * Opens the Star Mask Explorer. The master stack is built once per session (as a detection run builds it) and
     * kept while the frames and the settings that shape the stack stay the same.
     */
    private void openStarMaskExplorer() {
        ProcessingSession owner = sessions.current();
        FitsFileInformation[] files = owner == null ? null : owner.getFiles();
        if (files == null || files.length == 0) {
            return;
        }
        DetectionConfig config = checkedSettings("Test Star Mask did not start.");
        if (config == null) {
            return;
        }
        String stackKey = masterStackKey(config);
        if (owner != null && owner.getMasterStack(stackKey) != null) {
            showStarMaskExplorer(files, config);
            return;
        }
        JobCoordinator.Admission admission = jobs.start(JobKind.STAR_MASK_STACK, owner,
                new StarMaskStackTask(files, config.clone()), sessions.forCurrent((handle, outcome) -> {
                    if (!(outcome instanceof JobOutcome.Completed<JTransientEngine.MasterStackResult> completed)) {
                        readout.showDetectionProblem(outcome);
                    } else if (sessions.isCurrent(handle.session())) {
                        // Kept only for the session it was built for.
                        owner.setMasterStack(stackKey, completed.result());
                        showStarMaskExplorer(files, config);
                    }
                }));
        if (!admission.admitted()) {
            showFooterNote(admission.refusal(), true);
        }
    }

    /** Opens the explorer on the settings taken when Test Star Mask was clicked. */
    private void showStarMaskExplorer(FitsFileInformation[] files, DetectionConfig config) {
        File first = new File(files[0].getFilePath());
        String sessionName = ViewerSupport.sessionName(first);
        StarMaskExplorerFrame.Settings current = new StarMaskExplorerFrame.Settings(
                config.masterSigmaMultiplier, config.masterGrowSigmaMultiplier, config.masterMinDetectionPixels, config.maxStarJitter);
        ProcessingSession owner = sessions.current();
        StarMaskExplorerFrame frame = viewers.register(new StarMaskExplorerFrame(windowFrame, sessionName, owner.getMasterStack(masterStackKey(config)), config,
                visualization, current,
                changes -> sessions.isCurrent(owner) ? applyTrialSettings(changes) : null));
        frame.setVisible(true);
    }

    /** The settings that shape the master stack: everything except the star mask values the explorer varies. */
    private static String masterStackKey(DetectionConfig config) {
        DetectionConfig key = config.clone();
        key.masterSigmaMultiplier = 0;
        key.masterGrowSigmaMultiplier = 0;
        key.masterMinDetectionPixels = 0;
        key.maxStarJitter = 0;
        key.maxMaskOverlapFraction = 0;
        StringWriter writer = new StringWriter();
        SpacePixelsDetectionProfileIO.write(writer, key, 0);
        return writer.toString();
    }

    /** Callbacks of the Overview page. */
    private final class OverviewHost implements AutoTuneOverviewPanel.Host {
        @Override
        public void startAutoTune(AutoTuneProfile profile) {
            runAutoTuner(profile);
        }

        @Override
        public void applyTunedConfig(DetectionConfig tuned) {
            // The slow-mover thresholds are not copied: they apply to the maximum and median stacks, whose noise
            // differs from the single frames the tuner measures.
            updateSpinnersFromConfig(tuned);
            applySettingsToMemory();
            updateSavedState();
        }

        @Override
        public DetectionConfig currentConfig() {
            applySettingsToMemory();
            return jTransientConfig.clone();
        }

        @Override
        public void showReport(String reportText) {
            showTelemetryReportWindow(reportText);
        }

        @Override
        public void showSettingsTab(String tabTitle) {
            navigator.showPage(tabTitle);
        }

        @Override
        public void tuningStateChanged() {
            readout.refreshWorkflowStatus();
        }
    }

    /** Shows the Overview page of this tab and runs Auto-Tune as its Run Auto-Tune button does. */
    private void openOverviewAndRunAutoTune() {
        showThisTab.run();
        navigator.showPage("Overview");
        overviewPanel.runAutoTune();
    }

    /** One-line description of the detection settings in use, for the main window. */
    public String getSettingsSummary() {
        return overviewPanel == null ? "Manual settings" : overviewPanel.settingsSummary();
    }

    /** When this dataset has a saved auto-tune, the line that names each tuner and when it ran. */
    public String getSavedAutoTuneLine() {
        return overviewPanel == null ? null : overviewPanel.savedAutoTuneLine();
    }

    // ==========================================
    // AUTO-APPLY AND SAVED STATE
    // ==========================================

    /** Applies every edit to the session right away and refreshes the unsaved-changes indicator. */
    private void installAutoApply(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JSpinner) {
                ((JSpinner) c).addChangeListener(e -> onSettingEdited());
            } else if (c instanceof JCheckBox) {
                ((JCheckBox) c).addItemListener(e -> onSettingEdited());
            } else if (c instanceof JComboBox && c != cmbFrameRejection) {
                ((JComboBox<?>) c).addActionListener(e -> onSettingEdited());
            } else if (c instanceof Container) {
                installAutoApply((Container) c);
            }
        }
    }

    private void onSettingEdited() {
        if (suppressAutoApply) {
            return;
        }
        suppressAutoApply = true;
        List<String> adjustments;
        try {
            adjustments = applySettingsToMemory();
        } finally {
            suppressAutoApply = false;
        }
        showFooterNote(String.join(" · ", adjustments));
        updateSavedState();
    }

    /** The saved value of a core setting on the Overview, or null before anything is saved. */
    private Number savedCoreValue(JSpinner spinner) {
        if (savedConfig == null) {
            return null;
        }
        DetectionSetting<? extends Number> setting = coreDefinitions.get(spinner);
        return setting == null ? null : setting.read(savedConfig);
    }

    private void rememberSavedState() {
        rememberSavedState(new SpacePixelsDetectionProfile(jTransientConfig.clone(), autoTuneMaxCandidateFrames, reportOptions()),
                SpacePixelsVisualizationPreferences.of(visualization), navigator.captureSavedState());
    }

    private void rememberSavedState(SpacePixelsDetectionProfile profile, SpacePixelsVisualizationPreferences preferences,
                                    Runnable savedRows) {
        savedConfig = profile.getDetectionConfig().clone();
        savedAutoTuneMaxCandidateFrames = profile.getAutoTuneMaxCandidateFrames();
        savedVisualization = preferences.applyTo(VisualizationSettings.defaults())
                .withVariableStarReportOptions(profile.getVariableStarReportOptions());
        savedSnapshot = snapshot(profile, preferences);
        savedRows.run();
        overviewPanel.setSavedValues(this::savedCoreValue);
    }

    private String currentSnapshot() {
        return snapshot(new SpacePixelsDetectionProfile(jTransientConfig, autoTuneMaxCandidateFrames, reportOptions()),
                SpacePixelsVisualizationPreferences.of(visualization));
    }

    private static String snapshot(SpacePixelsDetectionProfile profile, SpacePixelsVisualizationPreferences preferences) {
        StringWriter writer = new StringWriter();
        SpacePixelsDetectionProfileIO.write(writer, profile);
        SpacePixelsVisualizationPreferencesIO.write(writer, preferences);
        return writer.toString();
    }

    private void updateSavedState() {
        if (savedSnapshot == null) {
            return;
        }
        boolean unsaved = !savedSnapshot.equals(currentSnapshot());
        if (unsaved) {
            footerStateLabel.setForeground(UIManager.getColor("Actions.Yellow") != null ? UIManager.getColor("Actions.Yellow") : new Color(0xE0A030));
            footerStateLabel.setText("● Unsaved changes");
            footerStateLabel.setToolTipText("The changes are already in use for this session. Save keeps them for the next start; Revert discards them.");
        } else {
            footerStateLabel.setForeground(UiStyle.helpColor());
            footerStateLabel.setText("✓ Saved settings in use");
            footerStateLabel.setToolTipText(null);
        }
        // The page dots and the unsaved count: Auto-Tune, Revert and the profile buttons change the Overview's core
        // settings, which are not rows and so do not refresh them on their own.
        navigator.refreshFilters();
    }

    private void revertToSaved() {
        if (savedConfig == null) {
            return;
        }
        jTransientConfig = savedConfig.clone();
        visualization = savedVisualization;
        // The visualization controls first: applying the detection settings applies every control.
        updateVisualizationSpinners();
        updateSpinnersFromConfig(jTransientConfig);
        setSpinnerValueClamped(spinAutoTuneMaxCandidateFrames, savedAutoTuneMaxCandidateFrames);
        applySettingsToMemory();
        overviewPanel.forgetTunedValues();
        showFooterNote("Reverted to the saved settings.");
        updateSavedState();
    }

    /** Reloads rendering controls as one EDT update so a partial reload cannot overwrite restored preferences. */
    private void updateVisualizationSpinners() {
        boolean wasSuppressed = suppressAutoApply;
        suppressAutoApply = true;
        try {
            visualizationBindings.forEach(ApplicationSettingBinding::load);
        } finally {
            suppressAutoApply = wasSuppressed;
        }
    }

    private void runAutoTuner(AutoTuneProfile selectedProfile) {
        FitsFileInformation[] selectedFiles = frames.getSelectedFilesInformation();
        FitsFileInformation[] poolToUse = AutoTuneCandidatePoolBuilder.inputFrames(
                frames.getImportedFiles(), selectedFiles);

        if (poolToUse == null || poolToUse.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            JOptionPane.showMessageDialog(this,
                    "You need at least " + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES + " monochrome frames available to run the Auto-Tuner.",
                    "Insufficient Data",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        DetectionConfig settings = checkedSettings("Auto-Tune did not start.");
        if (settings == null) {
            return;
        }

        AutoTuneTask tuneTask = new AutoTuneTask(
                poolToUse,
                settings,
                autoTuneMaxCandidateFrames,
                selectedProfile);
        JobCoordinator.Admission admission = jobs.start(JobKind.AUTO_TUNE,
                sessions.current(), tuneTask,
                sessions.forCurrent((handle, outcome) -> autoTuneFinished(outcome)));
        if (!admission.admitted()) {
            showFooterNote(admission.refusal(), true);
            return;
        }
        overviewPanel.tuneStarted();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
    }

    private void loadPersistedSettings() {
        loadDetectionProfile();
        loadVisualizationPreferences();
    }

    private void loadDetectionProfile() {
        File profileToLoad = detectionProfileFile.exists() ? detectionProfileFile : (legacyDetectionProfileFile.exists() ? legacyDetectionProfileFile : null);
        if (profileToLoad != null) {
            try (FileReader reader = new FileReader(profileToLoad)) {
                SpacePixelsDetectionProfile detectionProfile = SpacePixelsDetectionProfileIO.load(reader);
                jTransientConfig = detectionProfile.getDetectionConfig();
                autoTuneMaxCandidateFrames = detectionProfile.getAutoTuneMaxCandidateFrames();
                visualization = visualization.withVariableStarReportOptions(detectionProfile.getVariableStarReportOptions());
                try (FileReader migrationReader = new FileReader(profileToLoad)) {
                    if (SpacePixelsDetectionProfileIO.needsMigration(migrationReader)) {
                        SpacePixelsDetectionProfile migrationProfile = new SpacePixelsDetectionProfile(jTransientConfig.clone(),
                                autoTuneMaxCandidateFrames, reportOptions());
                        SwingUtilities.invokeLater(() -> offerDetectionProfileMigration(profileToLoad, migrationProfile));
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to inspect detection profile for migration", e);
                }
                return;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to load detection profile, falling back to defaults", e);
            }
        }
        jTransientConfig = new DetectionConfig();
        autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        visualization = visualization.withVariableStarReportOptions(VariableStarReportOptions.DEFAULT);
    }

    private void offerDetectionProfileMigration(File profileToLoad, SpacePixelsDetectionProfile detectionProfile) {
        if (closing.getAsBoolean()) return;
        int choice = JOptionPane.showConfirmDialog(
                this,
                "Your saved detection profile has missing or unrecognized settings.\n\n"
                        + "Migrate it now? Supported and renamed settings will be kept,\n"
                        + "unrecognized fields removed, and remaining missing fields set to current defaults.\n"
                        + "The original file will be kept as a backup.\n"
                        + "If this profile came from a newer SpacePixels version, choose No.",
                "Migrate Detection Profile",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice != JOptionPane.YES_OPTION) {
            return;
        }

        saveButton.setEnabled(false);
        showFooterNote("Migrating detection profile...");
        storage.submit("Detection settings", () ->
                SpacePixelsDetectionProfileIO.migrate(profileToLoad, detectionProfileFile, detectionProfile))
                .whenComplete((backup, failure) -> EventQueue.invokeLater(() -> {
                    if (closing.getAsBoolean()) return;
                    saveButton.setEnabled(true);
                    if (failure == null) {
                        showFooterNote("Detection profile migrated.");
                        JOptionPane.showMessageDialog(this,
                                "Detection profile migrated to:\n" + detectionProfileFile.getAbsolutePath()
                                        + "\n\nOriginal profile kept at:\n" + backup.getAbsolutePath(),
                                "Migration Complete", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        showFooterNote("Migration failed; saved settings are unchanged.", true);
                        JOptionPane.showMessageDialog(this, "Failed to migrate detection profile: " + failure.getMessage(),
                                "Migration Failed", JOptionPane.ERROR_MESSAGE);
                    }
                }));
    }

    private void loadVisualizationPreferences() {
        if (!visualizationPreferencesFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(visualizationPreferencesFile)) {
            visualization = SpacePixelsVisualizationPreferencesIO.load(reader).applyTo(visualization);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load visualization preferences, falling back to defaults", e);
        }
    }

    private void savePersistedSettings() {
        if (closing.getAsBoolean() || !saveButton.isEnabled()) return;
        DetectionConfig settings = checkedSettings("Not saved.");
        if (settings == null) {
            return;
        }

        SpacePixelsDetectionProfile profile = new SpacePixelsDetectionProfile(settings, autoTuneMaxCandidateFrames, reportOptions());
        SpacePixelsVisualizationPreferences preferences = SpacePixelsVisualizationPreferences.of(visualization);
        Runnable savedRows = navigator.captureSavedState();
        ProcessingSession expectedSession = sessions.current();
        saveButton.setEnabled(false);
        showFooterNote("Saving settings...");
        storage.submit("Detection settings", () -> {
            SpacePixelsSettingsFiles.save(
                    detectionProfileFile, profile, visualizationPreferencesFile, preferences);
            return null;
        }).whenComplete((ignored, failure) -> EventQueue.invokeLater(() -> {
            if (closing.getAsBoolean()) return;
            saveButton.setEnabled(true);
            if (failure != null) {
                showFooterNote("Not saved. " + failure.getMessage(), true);
                JOptionPane.showMessageDialog(this, "Could not save the settings.\n" + failure.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            rememberSavedState(profile, preferences, savedRows);
            boolean unchanged = savedSnapshot.equals(currentSnapshot());
            if (unchanged && sessions.current() == expectedSession) overviewPanel.clearTunedMarkers();
            updateSavedState();
            showFooterNote("Saved to " + detectionProfileFile.getParent()
                    + (unchanged ? "" : "; newer edits remain unsaved."));
            footerMessageLabel.setToolTipText("<html>" + detectionProfileFile.getAbsolutePath() + "<br>"
                    + visualizationPreferencesFile.getAbsolutePath() + "</html>");
        }));
    }

    private void loadDetectionDefaults() {
        jTransientConfig = new DetectionConfig();
        visualization = visualization.withVariableStarReportOptions(VariableStarReportOptions.DEFAULT);
        updateSpinnersFromConfig(jTransientConfig);
        overviewPanel.forgetTunedValues();
        updateSavedState();
    }

    /**
     * The detection settings for a save or a run starting now: a copy of the session settings, hidden settings
     * included, so later edits cannot change that run. When a field holds invalid text or the settings break a rule
     * of the shared engine rules, nothing is copied: this tab shows why, starting with {@code refusedAction}
     * (for example "Detect Moving Targets did not start."), the cursor goes into the field, and the result is null.
     */
    DetectionConfig checkedSettings(String refusedAction) {
        JSpinner invalid = invalidInputToShow();
        if (invalid != null) {
            showThisTab.run();
            // First, since revealing the setting can clear a filter, an edit that replaces the footer note.
            focusInput(invalid);
            this.refusedAction = refusedAction;
            renderFooter();
            return null;
        }
        List<String> adjustments = applySettingsToMemory();
        List<DetectionConfigRules.Issue> issues = DetectionConfigRules.validate(jTransientConfig, DetectionConfigRules.Operation.CONFIGURATION);
        List<DetectionConfigRules.Issue> problems = issues.stream().filter(issue -> issue.severity() == DetectionConfigRules.Severity.ERROR).toList();
        if (!problems.isEmpty()) {
            showThisTab.run();
            showFooterNote(refusedAction + " " + DetectionSettingsPresentation.issue(problems.getFirst()), true);
            return null;
        }
        if (!adjustments.isEmpty()) {
            showFooterNote(String.join(" · ", adjustments));
        } else if (issues.stream().anyMatch(issue -> issue.severity() == DetectionConfigRules.Severity.WARNING)) {
            showFooterNote("Settings have advisory warnings. Use Review rules… for details.");
        }
        return jTransientConfig.clone();
    }

    /**
     * A copy of the settings in use, for a window that follows the edits (Preview Frame). Unlike
     * {@link #checkedSettings} it changes nothing on this tab: every edit is applied as it is made, keeping the pairs
     * in order, and a field with invalid text keeps its last value.
     */
    DetectionConfig settingsInUse() {
        return jTransientConfig.clone();
    }

    /**
     * The visualization settings as last applied, for a job to capture when it is created (read it after
     * {@link #checkedSettings}) and for the viewers to draw detections with.
     */
    VisualizationSettings visualizationSettings() { return visualization; }

    /** The variable-star report presentation in use, part of the visualization settings. */
    private VariableStarReportOptions reportOptions() { return visualization.getVariableStarReportOptions(); }

    /** Replaces the variable-star report presentation in the visualization settings in use. */
    private void setReportOptions(VariableStarReportOptions options) {
        visualization = visualization.withVariableStarReportOptions(options);
    }

    /**
     * The Auto-Tune frame limit as last applied, for a job to capture when it is created; read it after
     * {@link #checkedSettings} so the field's current text is included.
     */
    int autoTuneMaxCandidateFrames() { return autoTuneMaxCandidateFrames; }

    // ==========================================
    // INVALID TEXT AND THE FOOTER MESSAGE
    // ==========================================

    /** What to enter in the setting's field, or null when its text is a valid value. */
    private String inputProblem(JSpinner spinner) {
        if (!(spinner.getEditor() instanceof CheckedNumberEditor)) {
            return null;
        }
        CheckedNumberEditor editor = (CheckedNumberEditor) spinner.getEditor();
        return editor.isTextValid() ? null : inputTitles.get(spinner) + ": " + editor.expected();
    }

    /** The field with invalid text to talk about: the one being edited, otherwise the first in page order. */
    private JSpinner invalidInputToShow() {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        JSpinner edited = focused == null ? null : (JSpinner) SwingUtilities.getAncestorOfClass(JSpinner.class, focused);
        if (edited != null && inputTitles.containsKey(edited) && inputProblem(edited) != null) {
            return edited;
        }
        for (JSpinner spinner : inputTitles.keySet()) {
            if (inputProblem(spinner) == null) {
                continue;
            }
            // A setting whose feature is switched off is hidden and not used, so its text does not count.
            SettingRow row = rowOf(spinner);
            if (row == null || row.applies.getAsBoolean()) {
                return spinner;
            }
        }
        return null;
    }

    /** The row of a setting on a detailed page, or null for a core setting on the Overview. */
    private SettingRow rowOf(JSpinner spinner) {
        for (SettingRow row : settingRows) {
            if (row.input == spinner) {
                return row;
            }
        }
        return null;
    }

    /** Shows the setting's page with the setting on it, scrolled into view, and puts the cursor into its field. */
    private void focusInput(JSpinner spinner) {
        SettingRow settingRow = rowOf(spinner);
        if (settingRow == null) {
            navigator.showPage("Overview");
        } else {
            navigator.reveal(settingRow);
        }
        JComponent shown = settingRow == null ? spinner : settingRow.row;
        // After the page and filters have been laid out.
        SwingUtilities.invokeLater(() -> {
            shown.scrollRectToVisible(new Rectangle(shown.getSize()));
            ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().requestFocusInWindow();
        });
    }

    /** Replaces the footer note, for example after a save or an edit; a blank note clears it. */
    private void showFooterNote(String note) {
        showFooterNote(note, false);
    }

    private void showFooterNote(String note, boolean problem) {
        footerNote = note == null || note.isBlank() ? " " : note;
        footerNoteIsProblem = problem;
        refusedAction = null;
        footerMessageLabel.setToolTipText(null);
        renderFooter();
    }

    /**
     * Shows the note, or, while a field holds invalid text, what to enter there (after the save or run it stopped).
     * Runs after every change of a field's text, so the message follows the fields.
     */
    private void renderFooter() {
        JSpinner invalid = invalidInputToShow();
        if (invalid == null) {
            refusedAction = null;
        }
        boolean problem = invalid != null || footerNoteIsProblem;
        Color error = UIManager.getColor("Actions.Red");
        footerMessageLabel.setForeground(problem ? (error != null ? error : new Color(0xDB5860))
                : UiStyle.helpColor());
        footerMessageLabel.setText(invalid == null ? footerNote
                : (refusedAction == null ? "" : refusedAction + " ") + inputProblem(invalid));
    }

    private JPanel buildStreakDetectionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Settings for streak detection."));

        panel.add(createSectionHeader("Common Settings"));
        spinSingleStreakMinPeakSigma = addTypedDouble(panel, StreakDetectionSettings.SINGLE_STREAK_MIN_PEAK_SIGMA, 0.0, 50.0, 0.1);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Streak Shape And Linking"));
        spinStreakMinElong = addTypedDouble(panel, StreakDetectionSettings.STREAK_MIN_ELONGATION, 1.0, 20.0, 0.1);
        spinStreakMinPix = addTypedInteger(panel, StreakDetectionSettings.STREAK_MIN_PIXELS, 1, 2000, 1);
        spinAngleTol = addTypedDouble(panel, StreakDetectionSettings.ANGLE_TOLERANCE_DEGREES, 0.5, 180.0, 0.5);
        spinStreakTimeConsistencyTolerance = addTypedDouble(panel, StreakDetectionSettings.STREAK_TIME_CONSISTENCY_TOLERANCE, 0.0, 1.0, 0.01);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Faint Streak Fragments"));
        chkEnableFaintStreakFragments = addTypedCheckbox(panel, StreakDetectionSettings.ENABLE_FAINT_STREAK_FRAGMENTS);
        spinFaintStreakLineTolerance = addTypedDouble(panel, StreakDetectionSettings.FAINT_STREAK_LINE_TOLERANCE, 0.0, 20.0, 0.1);

        return panel;
    }

    private JPanel buildMovingObjectsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Configuration for moving-object track construction. Base Star Jitter Radius is on the Overview with the other values the Auto-Tuner sets. Hot pixels and dust are set aside before tracking. The time-based linker is used when the frames have timestamps; the geometric linker is an optional extra."));

        panel.add(createSectionHeader("Hot Pixels And Dust"));
        chkEnableCameraFixedRejection = addTypedCheckbox(panel, MovingObjectsSettings.ENABLE_CAMERA_FIXED_REJECTION);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Track Linking"));
        chkStrictExposureKinematics = addTypedCheckbox(panel, MovingObjectsSettings.STRICT_EXPOSURE_KINEMATICS);
        spinPredTol = addTypedDouble(panel, MovingObjectsSettings.PREDICTION_TOLERANCE, 0.1, 50.0, 0.1);
        spinTrackMinFrameRatio = addTypedDouble(panel, MovingObjectsSettings.TRACK_MIN_FRAME_RATIO, 1.0, 20.0, 0.25);
        spinAbsMaxPoints = addTypedInteger(panel, MovingObjectsSettings.ABSOLUTE_MAX_POINTS_REQUIRED, 2, 100, 1);
        spinMaxFwhmRatio = addTypedDouble(panel, MovingObjectsSettings.MAX_FWHM_RATIO, 0.0, 10.0, 0.1);
        spinMaxSurfaceBrightnessRatio = addTypedDouble(panel, MovingObjectsSettings.MAX_SURFACE_BRIGHTNESS_RATIO, 0.0, 10.0, 0.1);
        spinTimeBasedVelocityTolerance = addTypedDouble(panel, MovingObjectsSettings.TIME_BASED_VELOCITY_TOLERANCE, 0.0, 1.0, 0.01);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Geometric Linker"));
        chkEnableGeometricTrackLinking = addTypedCheckbox(panel, MovingObjectsSettings.ENABLE_GEOMETRIC_TRACK_LINKING);
        spinMaxJump = addTypedDouble(panel, MovingObjectsSettings.MAX_JUMP_PIXELS, 0.0, 10000.0, 0.1);
        spinRhythmVar = addTypedDouble(panel, MovingObjectsSettings.RHYTHM_ALLOWED_VARIANCE, 0.0, 20.0, 0.1);
        spinRhythmMinRatio = addTypedDouble(panel, MovingObjectsSettings.RHYTHM_MIN_CONSISTENCY_RATIO, 0.0, 1.0, 0.01);
        spinRhythmStatThresh = addTypedDouble(panel, MovingObjectsSettings.RHYTHM_STATIONARY_THRESHOLD, 0.0, 20.0, 0.1);

        return panel;
    }

    private JPanel buildAnomalyDetectionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Anomalies are either transient high energy events or transient larger but fainter events. Here are the controls for single-frame anomaly rescue. A faint streak through a star can change the star's shape and produce an anomaly; when that anomaly lies on a known streak's path, it becomes part of that streak."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableAnomalyRescue = addTypedCheckbox(panel, AnomalyDetectionSettings.ENABLE_ANOMALY_RESCUE);
        spinAnomalyMinPeakSigma = addTypedDouble(panel, AnomalyDetectionSettings.ANOMALY_MIN_PEAK_SIGMA, 1.0, 50.0, 0.1);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Anomaly Thresholds And Grouping"));
        spinAnomalyMinPixels = addTypedInteger(panel, AnomalyDetectionSettings.ANOMALY_MIN_PIXELS, 1, 2000, 1);
        spinAnomalyMinIntegratedSigma = addTypedDouble(panel, AnomalyDetectionSettings.ANOMALY_MIN_INTEGRATED_SIGMA, 1.0, 200.0, 0.5);
        spinAnomalyMinIntegratedPixels = addTypedInteger(panel, AnomalyDetectionSettings.ANOMALY_MIN_INTEGRATED_PIXELS, 1, 2000, 1);
        spinAnomalyMinPeakSigmaFloor = addTypedDouble(panel, AnomalyDetectionSettings.ANOMALY_MIN_PEAK_SIGMA_FLOOR, 0.0, 20.0, 0.1);

        return panel;
    }

    private JPanel buildSlowMoversPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Find elongated shapes in the maximum stack and compare them with sources in the median stack. These are candidates to review, not confirmed moving objects. The sigma, grow and minimum pixels below apply to the stacks, whose noise differs from single frames, so they are set separately from the per-frame detection settings (Auto-Tune does not change them)."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableSlowMovers = addTypedCheckbox(panel, SlowMoversSettings.ENABLE_SLOW_MOVER_DETECTION);
        spinMasterSlowMoverSigma = addTypedDouble(panel, SlowMoversSettings.MASTER_SLOW_MOVER_SIGMA_MULTIPLIER, 0.5, 15.0, 0.1);
        spinMasterSlowMoverGrowSigma = addTypedDouble(panel, SlowMoversSettings.MASTER_SLOW_MOVER_GROW_SIGMA_MULTIPLIER, 0.1, 15.0, 0.1);
        spinMasterSlowMoverMinPixels = addTypedInteger(panel, SlowMoversSettings.MASTER_SLOW_MOVER_MIN_PIXELS, 1, 2000, 1);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Shape And Evidence"));
        spinSlowMoverMinAxisRatio = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MIN_AXIS_RATIO, 1.0, 20.0, 0.05);
        spinSlowMoverMaxAxisRatio = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MAX_AXIS_RATIO, 1.0, 20.0, 0.05);
        spinSlowMoverMinFillFactor = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MIN_FILL_FACTOR, 0.0, 1.0, 0.01);
        spinSlowMoverMedianSupportOverlapFraction = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MEDIAN_SUPPORT_OVERLAP_FRACTION, 0.0, 1.0, 0.01);
        spinSlowMoverMedianSupportMaxOverlapFraction = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MEDIAN_SUPPORT_MAX_OVERLAP_FRACTION, 0.0, 1.0, 0.01);
        spinSlowMoverMinFrameSupport = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MIN_FRAME_SUPPORT, 0.0, 100.0, 1.0);
        spinSlowMoverMaxStationaryLikelihood = addTypedDouble(panel, SlowMoversSettings.SLOW_MOVER_MAX_STATIONARY_LIKELIHOOD, 0.0, 100.0, 1.0);

        return panel;
    }

    /** Builds the existing residual page from typed definitions without exposing hidden engine controls. */
    private JPanel buildResidualAnalysisPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Analysis of point transients left after tracks, streaks and standalone anomalies. Common-motion filtering first sets aside compact sources that share a displacement, independently of the residual-analysis switch. Optional residual analysis then looks for weak local rescue candidates and builds activity clusters from the remaining points."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableResidualTransientAnalysis = addTypedCheckbox(panel, ResidualAnalysisSettings.ENABLE_RESIDUAL);
        chkEnableLocalRescueCandidates = addTypedCheckbox(panel, ResidualAnalysisSettings.ENABLE_RESCUE);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Activity Clusters"));
        chkEnableLocalActivityClusters = addTypedCheckbox(panel, ResidualAnalysisSettings.ENABLE_CLUSTERS);
        spinLocalActivityClusterRadiusPixels = addTypedDouble(panel, ResidualAnalysisSettings.CLUSTER_RADIUS, 0.0, 100.0, 0.5);
        spinLocalActivityClusterMinFrames = addTypedInteger(panel, ResidualAnalysisSettings.CLUSTER_FRAMES, 1, 100, 1);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Common Motion"));
        chkEnableCommonMotionRejection = addTypedCheckbox(panel, ResidualAnalysisSettings.REJECT_COMMON_MOTION);
        spinCommonMotionShiftTolerance = addTypedDouble(panel, ResidualAnalysisSettings.MOTION_TOLERANCE, 0.0, 20.0, 0.1);

        return panel;
    }

    private JPanel buildVariableStarsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Measure the brightness of stationary stars in every frame that passed quality control, check whether the frames respond linearly to light, and report stars whose brightness changes more than noise and shared systematics explain. The analysis is tuned to avoid false variables: a missed variable is preferred over a false one. Apertures, sky rings and star selection adapt to each session automatically. Results and per-frame diagnostics appear in the pipeline telemetry."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableVariableStarDetection = addTypedCheckbox(panel, VariableStarsSettings.ENABLE_VARIABLE_STAR_DETECTION);

        panel.add(createSectionHeader("Report Presentation"));
        spinVariableMaxCandidateCards = addApplicationInteger(panel, reportBindings,
                "variableStarMaxCandidateCards", "Maximum Detailed Candidate Cards", "cards",
                "Maximum variable-star cards with full light curves and cutouts in the report (default 40).<br>Set 60 for a longer report. The table, field maps and CSVs still include every candidate.",
                false, () -> reportOptions().maxCandidateCards(),
                value -> setReportOptions(new VariableStarReportOptions(value, reportOptions().sortOrder())),
                1, VariableStarReportOptions.MAX_CANDIDATE_CARDS, 1);
        cmbVariableStarSortOrder = addApplicationChoice(panel, reportBindings,
                "variableStarSortOrder", "Candidate Presentation Order", "",
                "High confidence first; V numbers stay fixed.<br>Observed changes: up-and-down, dips and peaks, then larger Δmag.<br>Known periods: saved VSX matches; unknown periods last in each tier.",
                false, VariableStarReportOptions.SortOrder.class, () -> reportOptions().sortOrder(),
                value -> setReportOptions(new VariableStarReportOptions(reportOptions().maxCandidateCards(), value)),
                VariableStarReportOptions.SortOrder.values());

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Candidate Thresholds"));
        spinVariableScoreSigma = addTypedDouble(panel, VariableStarsSettings.VARIABLE_SCORE_SIGMA, 1.0, 50.0, 0.5);
        spinVariableMinAmplitudeMag = addTypedDouble(panel, VariableStarsSettings.VARIABLE_MIN_AMPLITUDE_MAG, 0.0, 5.0, 0.01);
        spinVariableMinFrames = addTypedInteger(panel, VariableStarsSettings.VARIABLE_MIN_FRAMES, 3, 10000, 1);
        spinVariableMinSpanMinutes = addTypedDouble(panel, VariableStarsSettings.VARIABLE_MIN_SPAN_MINUTES, 0.0, 1440.0, 5.0);
        spinPhotometryMaxStars = addTypedInteger(panel, VariableStarsSettings.PHOTOMETRY_MAX_STARS, 0, 200000, 500);
        spinPhotometryMinSnr = addTypedDouble(panel, VariableStarsSettings.PHOTOMETRY_MIN_SNR, 0.0, 1000.0, 1.0);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Linearity Checks"));
        spinLinearityMaxConcentrationDrift = addTypedDouble(panel, VariableStarsSettings.LINEARITY_MAX_CONCENTRATION_DRIFT, 0.0, 0.5, 0.005);
        spinLinearityMinRangeMag = addTypedDouble(panel, VariableStarsSettings.LINEARITY_MIN_RANGE_MAG, 0.0, 10.0, 0.1);
        spinLinearityMaxFrameSlope = addTypedDouble(panel, VariableStarsSettings.LINEARITY_MAX_FRAME_SLOPE, 0.0, 0.5, 0.001);
        spinLinearityMaxFailingFrameFraction = addTypedDouble(panel, VariableStarsSettings.LINEARITY_MAX_FAILING_FRAME_FRACTION, 0.0, 1.0, 0.05);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Candidate Gates"));
        spinVariableLimitedMinAmplitudeMag = addTypedDouble(panel, VariableStarsSettings.VARIABLE_LIMITED_MIN_AMPLITUDE_MAG, 0.0, 5.0, 0.01);
        spinVariableAmplitudeNoiseFactor = addTypedDouble(panel, VariableStarsSettings.VARIABLE_AMPLITUDE_NOISE_FACTOR, 0.0, 50.0, 0.5);
        spinVariableMinPersistenceFrames = addTypedInteger(panel, VariableStarsSettings.VARIABLE_MIN_PERSISTENCE_FRAMES, 1, 1000, 1);
        spinVariableMinSplitHalfCorrelation = addTypedDouble(panel, VariableStarsSettings.VARIABLE_MIN_SPLIT_HALF_CORRELATION, -1.0, 1.0, 0.05);
        spinVariableMaxApertureAmplitudeDifference = addTypedDouble(panel, VariableStarsSettings.VARIABLE_MAX_APERTURE_AMPLITUDE_DIFFERENCE, 0.0, 5.0, 0.05);
        spinVariableMaxSystematicsCorrelation = addTypedDouble(panel, VariableStarsSettings.VARIABLE_MAX_SYSTEMATICS_CORRELATION, 0.0, 1.0, 0.05);
        spinVariableLocalRadiusPixels = addTypedDouble(panel, VariableStarsSettings.VARIABLE_LOCAL_RADIUS_PIXELS, 0.0, 5000.0, 10.0);
        spinVariableMaxLocalCorrelation = addTypedDouble(panel, VariableStarsSettings.VARIABLE_MAX_LOCAL_CORRELATION, 0.0, 1.0, 0.05);

        return panel;
    }

    private JPanel buildQualityPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Frames that differ too much from the rest of the session are left out before detection: fewer stars (clouds), softer focus, elongated stars (wind or tracking) or a shifted background (clouds, light leaks)."));

        panel.add(createSectionHeader("Frame Rejection"));
        cmbFrameRejection = new JComboBox<>(new DefaultComboBoxModel<>(FRAME_REJECTION_PRESETS));
        cmbFrameRejection.putClientProperty(SettingRow.PRESET_CHOOSER, true);
        UiStyle.inputWidth(cmbFrameRejection, 110);
        addSettingRow(panel, "frameRejectionPreset", "Strictness", "How readily frames that differ from the rest of the session are left out. Normal uses the default limits; Lenient keeps more frames, Strict leaves out more.<br>The individual limits are expert settings; changing one shows Custom.", false, cmbFrameRejection);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Session Outlier Rejection (MAD)"));
        spinStarCountSigma = addTypedDouble(panel, QualitySettings.STAR_COUNT_SIGMA_DEVIATION, 0.0, 10.0, 0.1);
        spinFwhmSigma = addTypedDouble(panel, QualitySettings.FWHM_SIGMA_DEVIATION, 0.0, 10.0, 0.1);
        spinEccentricitySigma = addTypedDouble(panel, QualitySettings.ECCENTRICITY_SIGMA_DEVIATION, 0.0, 10.0, 0.1);
        chkEnableBrightStarEccentricityFilter = addTypedCheckbox(panel, QualitySettings.ENABLE_BRIGHT_STAR_ECCENTRICITY_FILTER);
        spinBrightStarEccentricitySigma = addTypedDouble(panel, QualitySettings.BRIGHT_STAR_ECCENTRICITY_SIGMA_DEVIATION, 0.0, 10.0, 0.1);
        spinBackgroundSigma = addTypedDouble(panel, QualitySettings.BACKGROUND_SIGMA_DEVIATION, 0.0, 10.0, 0.1);
        spinMinBgDevAdu = addTypedDouble(panel, QualitySettings.MIN_BACKGROUND_DEVIATION_ADU, 0.0, 10000.0, 5.0);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Auto-Tune"));
        spinAutoTuneMaxCandidateFrames = addApplicationInteger(panel, tuningBindings,
                "autoTuneMaxCandidateFrames", "Frames Used by Auto-Tune (both tuners)", "frames",
                "Most frames given to Auto-Tune. When the session has more, SpacePixels picks the best-quality, median-quality and evenly spaced frames. More frames give Auto-Tune more measurements on small sensors and a master stack closer to the real run; fewer frames use less memory on large sensors (about 120 MB per frame at 61 megapixels).",
                true, () -> autoTuneMaxCandidateFrames, value -> autoTuneMaxCandidateFrames = value,
                SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES, 5000, 1);

        return panel;
    }

    private JPanel buildAdvancedVisualizationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Visualization-only controls for exported reports and review imagery. These settings do not change detection results; they only affect presentation, contrast stretch, and animation cadence."));

        panel.add(createSectionHeader("Display Stretch & Animation"));
        spinAutoBlackSigma = addApplicationDouble(panel, visualizationBindings,
                "autoStretchBlackSigma", "Auto Stretch Black Sigma", "sigma",
                "Controls how far below the image mean the automatic black point is placed when stretching rendered imagery. Higher values darken the background more aggressively.",
                false, () -> visualization.getAutoStretchBlackSigma(), value -> visualization = visualization.withAutoStretchBlackSigma(value),
                0.0, 10.0, 0.1);
        spinAutoWhiteSigma = addApplicationDouble(panel, visualizationBindings,
                "autoStretchWhiteSigma", "Auto Stretch White Sigma", "sigma",
                "Controls how far above the image mean the automatic white point is placed when stretching rendered imagery. Higher values preserve more bright-core detail but can reduce contrast on faint structure.",
                false, () -> visualization.getAutoStretchWhiteSigma(), value -> visualization = visualization.withAutoStretchWhiteSigma(value),
                0.1, 20.0, 0.1);
        spinGifBlinkSpeed = addApplicationInteger(panel, visualizationBindings,
                "gifBlinkSpeedMs", "GIF Blink Speed (ms)", "ms",
                "Frame delay used for exported animated GIFs. Lower values blink faster; higher values slow the inspection cadence.",
                false, () -> visualization.getGifBlinkSpeedMs(), value -> visualization = visualization.withGifBlinkSpeedMs(value),
                50, 5000, 10);


        panel.add(Box.createVerticalStrut(10));
        panel.add(createExpertSectionHeader("Annotations And Cropping"));
        spinStreakScale = addApplicationDouble(panel, visualizationBindings,
                "streakLineScaleFactor", "Streak Line Scale Factor", "",
                "Visualization-only setting that scales the length of the drawn streak annotation line.",
                true, () -> visualization.getStreakLineScaleFactor(), value -> visualization = visualization.withStreakLineScaleFactor(value),
                0.1, 20.0, 0.1);
        spinStreakCentroidRad = addApplicationInteger(panel, visualizationBindings,
                "streakCentroidBoxRadius", "Streak Centroid Box Radius", "pixels",
                "Visualization-only setting that controls the size of the box drawn around a streak centroid.",
                true, () -> visualization.getStreakCentroidBoxRadius(), value -> visualization = visualization.withStreakCentroidBoxRadius(value),
                1, 100, 1);
        spinPointBoxRad = addApplicationInteger(panel, visualizationBindings,
                "pointSourceMinBoxRadius", "Point Source Min Box Radius", "pixels",
                "Visualization-only setting that sets the minimum radius of boxes drawn around point sources.",
                true, () -> visualization.getPointSourceMinBoxRadius(), value -> visualization = visualization.withPointSourceMinBoxRadius(value),
                1, 50, 1);
        spinBoxPad = addApplicationInteger(panel, visualizationBindings,
                "dynamicBoxPadding", "Dynamic Box Padding", "pixels",
                "Visualization-only setting that adds extra padding around automatically sized annotation boxes.",
                true, () -> visualization.getDynamicBoxPadding(), value -> visualization = visualization.withDynamicBoxPadding(value),
                0, 50, 1);
        spinCropPadding = addApplicationInteger(panel, visualizationBindings,
                "trackCropPadding", "Track Crop Border Padding", "pixels",
                "Export-only setting that adds extra border around cropped track images.",
                true, () -> visualization.getTrackCropPadding(), value -> visualization = visualization.withTrackCropPadding(value),
                0, 2000, 10);

        return panel;
    }

    private JLabel createTabIntro(String text) {
        JLabel introLabel = new WrappingHelpLabel(text, 600);
        introLabel.setBorder(new EmptyBorder(0, 0, UiStyle.space(10), 0));
        introLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return introLabel;
    }

    private SpinnerNumberModel doubleSpinnerModel(double value, double min, double max, double step) {
        return new SpinnerNumberModel(clampDouble(value, min, max), min, max, step);
    }

    private SpinnerNumberModel intSpinnerModel(int value, int min, int max, int step) {
        return new SpinnerNumberModel(clampInt(value, min, max), min, max, step);
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void setSpinnerValueClamped(JSpinner spinner, double value) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        double min = ((Number) model.getMinimum()).doubleValue();
        double max = ((Number) model.getMaximum()).doubleValue();
        spinner.setValue(clampDouble(value, min, max));
        configureSpinnerEditor(spinner);
    }

    private void setSpinnerValueClamped(JSpinner spinner, int value) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        int min = ((Number) model.getMinimum()).intValue();
        int max = ((Number) model.getMaximum()).intValue();
        spinner.setValue(clampInt(value, min, max));
        configureSpinnerEditor(spinner);
    }

    private int numberScale(Number number) {
        if (number == null) {
            return 0;
        }
        if (number instanceof Integer || number instanceof Long || number instanceof Short || number instanceof Byte) {
            return 0;
        }
        return Math.max(0, BigDecimal.valueOf(number.doubleValue()).stripTrailingZeros().scale());
    }

    private int spinnerDecimalPlaces(JSpinner spinner) {
        SpinnerNumberModel model = (SpinnerNumberModel) spinner.getModel();
        Number step = (Number) model.getStepSize();
        Number value = (Number) model.getValue();
        int decimals = Math.max(numberScale(step), numberScale(value));
        if ((value instanceof Double || value instanceof Float || step instanceof Double || step instanceof Float) && decimals == 0) {
            decimals = 1;
        }
        return decimals;
    }

    private void configureSpinnerEditor(JSpinner spinner) {
        int decimals = spinnerDecimalPlaces(spinner);
        String pattern = decimals == 0 ? "#0" : "#0." + "0".repeat(decimals);
        CheckedNumberEditor editor = new CheckedNumberEditor(spinner, pattern, this::renderFooter);
        spinner.setEditor(editor);
        editor.getTextField().setColumns(Math.max(5, decimals + 5));
        renderFooter();
    }

    private String formatSpinnerValue(JSpinner spinner) {
        Number value = (Number) spinner.getValue();
        int decimals = spinnerDecimalPlaces(spinner);
        if (decimals == 0) {
            return Long.toString(value.longValue());
        }
        return String.format("%." + decimals + "f", value.doubleValue());
    }

    static Color accentColor() {
        Color accentColor = UIManager.getColor("Component.accentColor");
        return accentColor != null ? accentColor : Color.decode("#4285f4");
    }

    static JLabel createSectionHeader(String title) {
        JLabel headerLabel = new JLabel(title);
        headerLabel.setFont(UiStyle.sectionFont());
        headerLabel.setForeground(accentColor());
        headerLabel.setBorder(new EmptyBorder(10, 0, 10, 0));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerLabel.putClientProperty(SECTION_HEADER, true);
        return headerLabel;
    }

    /** A section header whose settings appear only while "Show expert settings" is on. */
    private static JLabel createExpertSectionHeader(String title) {
        JLabel headerLabel = createSectionHeader(title);
        headerLabel.putClientProperty(EXPERT_SECTION, true);
        return headerLabel;
    }

    /** Shows the rows of {@code inputs} only while {@code toggle} is on. */
    private void showOnlyWhen(AbstractButton toggle, JComponent... inputs) {
        for (SettingRow row : settingRows) {
            for (JComponent input : inputs) {
                if (row.input == input) {
                    row.applies = toggle::isSelected;
                }
            }
        }
    }

    /** The five session outlier limits a frame rejection preset sets, in a fixed order. */
    private JSpinner[] frameRejectionSpinners() {
        return new JSpinner[]{spinStarCountSigma, spinFwhmSigma, spinEccentricitySigma, spinBrightStarEccentricitySigma, spinBackgroundSigma};
    }

    private static double[] frameRejectionPresetValues(String preset) {
        if ("Lenient".equals(preset)) {
            return new double[]{3.0, 3.5, 4.5, 4.5, 4.5};
        }
        if ("Strict".equals(preset)) {
            return new double[]{1.5, 2.0, 2.5, 2.5, 2.5};
        }
        DetectionConfig defaults = new DetectionConfig();
        return new double[]{defaults.starCountSigmaDeviation, defaults.fwhmSigmaDeviation, defaults.eccentricitySigmaDeviation,
                defaults.brightStarEccentricitySigmaDeviation, defaults.backgroundSigmaDeviation};
    }

    /** Choosing a preset sets the five limits; editing a limit shows the matching preset, or Custom. */
    private void setupFrameRejectionPreset() {
        cmbFrameRejection.setToolTipText("<html>Lenient: star count 3.0, FWHM 3.5, eccentricity and background 4.5 sigma.<br>"
                + "Normal: the defaults (2.0, 2.5, 3.0).<br>Strict: star count 1.5, FWHM 2.0, eccentricity and background 2.5 sigma.</html>");
        cmbFrameRejection.addActionListener(e -> {
            Object selected = cmbFrameRejection.getSelectedItem();
            if (syncingFrameRejection || selected == null || FRAME_REJECTION_CUSTOM.equals(selected)) {
                return;
            }
            double[] values = frameRejectionPresetValues((String) selected);
            JSpinner[] spinners = frameRejectionSpinners();
            syncingFrameRejection = true;
            try {
                for (int i = 0; i < spinners.length; i++) {
                    setSpinnerValueClamped(spinners[i], values[i]);
                }
            } finally {
                syncingFrameRejection = false;
            }
            syncFrameRejectionPreset();
        });
        for (JSpinner spinner : frameRejectionSpinners()) {
            spinner.addChangeListener(e -> {
                if (!syncingFrameRejection) {
                    syncFrameRejectionPreset();
                }
            });
        }
        syncFrameRejectionPreset();
    }

    private void syncFrameRejectionPreset() {
        JSpinner[] spinners = frameRejectionSpinners();
        String match = FRAME_REJECTION_CUSTOM;
        for (String preset : FRAME_REJECTION_PRESETS) {
            double[] values = frameRejectionPresetValues(preset);
            boolean same = true;
            for (int i = 0; i < spinners.length && same; i++) {
                same = Math.abs(((Number) spinners[i].getValue()).doubleValue() - values[i]) < 1e-9;
            }
            if (same) {
                match = preset;
                break;
            }
        }
        DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) cmbFrameRejection.getModel();
        syncingFrameRejection = true;
        try {
            boolean hasCustom = model.getIndexOf(FRAME_REJECTION_CUSTOM) >= 0;
            if (FRAME_REJECTION_CUSTOM.equals(match) && !hasCustom) {
                model.addElement(FRAME_REJECTION_CUSTOM);
            }
            model.setSelectedItem(match);
            if (!FRAME_REJECTION_CUSTOM.equals(match) && hasCustom) {
                model.removeElement(FRAME_REJECTION_CUSTOM);
            }
        } finally {
            syncingFrameRejection = false;
        }
    }

    private JSpinner createSpinner(SpinnerModel model) {
        JSpinner spinner = UiStyle.spinner(model);
        if (model instanceof SpinnerNumberModel) {
            configureSpinnerEditor(spinner);
            spinner.addChangeListener(e -> configureSpinnerEditor(spinner));
        }
        UiStyle.inputWidth(spinner, 80);
        return spinner;
    }

    /** Binds an application double with its existing range and explicit runtime or profile owner. */
    private JSpinner addApplicationDouble(JPanel parent, List<ApplicationSettingBinding<?>> bindings,
            String id, String title, String unit, String description, boolean expert,
            java.util.function.Supplier<Double> getter, java.util.function.Consumer<Double> setter,
            double min, double max, double step) {
        JSpinner input = createSpinner(doubleSpinnerModel(getter.get(), min, max, step));
        addApplicationBinding(parent, bindings, new ApplicationSettingBinding<>(
                id, title, unit, description, expert, Double.class, getter, setter,
                () -> ((Number) input.getValue()).doubleValue(),
                value -> setSpinnerValueClamped(input, value.doubleValue())), input);
        return input;
    }

    /** Binds an application integer without adding an engine setting or changing its persistence format. */
    private JSpinner addApplicationInteger(JPanel parent, List<ApplicationSettingBinding<?>> bindings,
            String id, String title, String unit, String description, boolean expert,
            java.util.function.Supplier<Integer> getter, java.util.function.Consumer<Integer> setter,
            int min, int max, int step) {
        JSpinner input = createSpinner(intSpinnerModel(getter.get(), min, max, step));
        addApplicationBinding(parent, bindings, new ApplicationSettingBinding<>(
                id, title, unit, description, expert, Integer.class, getter, setter,
                () -> ((Number) input.getValue()).intValue(),
                value -> setSpinnerValueClamped(input, value.intValue())), input);
        return input;
    }

    /** Binds a finite set of typed presentation choices without casting the selected item during apply. */
    private <Value> JComboBox<Value> addApplicationChoice(JPanel parent, List<ApplicationSettingBinding<?>> bindings,
            String id, String title, String unit, String description, boolean expert,
            Class<Value> type, java.util.function.Supplier<Value> getter, java.util.function.Consumer<Value> setter,
            Value[] choices) {
        JComboBox<Value> input = new JComboBox<>(choices);
        input.setSelectedItem(getter.get());
        addApplicationBinding(parent, bindings, new ApplicationSettingBinding<>(
                id, title, unit, description, expert, type, getter, setter,
                () -> type.cast(input.getSelectedItem()), input::setSelectedItem), input);
        return input;
    }

    /** Registers application identity, disclosure and load/apply at the row that owns the control. */
    private <Value> void addApplicationBinding(JPanel parent, List<ApplicationSettingBinding<?>> bindings,
            ApplicationSettingBinding<Value> binding, JComponent input) {
        addSettingRow(parent, binding.id(), binding.title(), binding.help(), binding.expert(), input);
        bindings.add(binding);
    }

    /** Adds a typed boolean row using the same model later shared with the Overview analysis switch. */
    private JCheckBox addTypedCheckbox(JPanel parent, DetectionSetting<Boolean> setting) {
        JCheckBox input = new JCheckBox();
        input.setSelected(setting.read(jTransientConfig));
        UiStyle.inputWidth(input, 80);
        addTypedBinding(parent, setting, input, input::isSelected, input::setSelected);
        return input;
    }

    /** Binds a double-valued engine field while retaining the existing GUI range and editor behavior. */
    private JSpinner addTypedDouble(JPanel parent, DetectionSetting<Double> setting, double min, double max, double step) {
        JSpinner input = createSpinner(doubleSpinnerModel(setting.read(jTransientConfig), min, max, step));
        addTypedBinding(parent, setting, input, () -> ((Number) input.getValue()).doubleValue(),
                value -> setSpinnerValueClamped(input, value.doubleValue()));
        return input;
    }

    /** Binds an integer-valued engine field without changing the persisted numeric type. */
    private JSpinner addTypedInteger(JPanel parent, DetectionSetting<Integer> setting, int min, int max, int step) {
        JSpinner input = createSpinner(intSpinnerModel(setting.read(jTransientConfig), min, max, step));
        addTypedBinding(parent, setting, input, () -> ((Number) input.getValue()).intValue(),
                value -> setSpinnerValueClamped(input, value.intValue()));
        return input;
    }

    /** Registers identity, disclosure and typed load/apply together so page moves need no separate copy lists. */
    private <Value> void addTypedBinding(JPanel parent, DetectionSetting<Value> setting, JComponent input,
            java.util.function.Supplier<Value> current, java.util.function.Consumer<Value> display) {
        registerTypedBinding(setting, input, current, display);
        addSettingRow(parent, setting.id(), setting.title(), setting.help(), setting.expert(), input);
    }

    /** Registers one typed engine binding and its numeric editor for rule-driven alignment. */
    private <Value> void registerTypedBinding(DetectionSetting<Value> setting, JComponent input,
            java.util.function.Supplier<Value> current, java.util.function.Consumer<Value> display) {
        typedBindings.add(new DetectionSettingBinding<>(setting, current, display));
        if (input instanceof JSpinner spinner) {
            detectionInputs.put(setting.id(), spinner);
        }
    }

    /** Adds a setting row (title, description, input and reset button) and registers it with the navigator. */
    private void addSettingRow(JPanel parent, String id, String title, String description, boolean expert, JComponent input) {
        JLabel titleLabel = new JLabel(title);
        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(4), 0));
        inputWrapper.add(input);
        JPanel row = new SettingsRowPanel(titleLabel, description, inputWrapper);
        SettingRow setting = new SettingRow(parent, row, titleLabel, id, title, description, expert, input);
        inputWrapper.add(setting.resetButton());
        parent.add(row);
        settingRows.add(setting);
        if (input instanceof JSpinner) {
            inputTitles.put((JSpinner) input, title);
        }
        navigator.registerRow(setting);
    }

    /**
     * Commits the fields with valid text and copies every setting into the session configuration, in place, so the
     * hidden settings keep their loaded values. Returns what was moved to keep a pair of settings in order.
     */
    private List<String> applySettingsToMemory() {
        boolean wasSuppressed = suppressAutoApply;
        suppressAutoApply = true;
        List<String> adjustments;
        try {
            commitAllSpinners();
            adjustments = normalizeDependentSpinners();

            typedBindings.forEach(binding -> binding.applyTo(jTransientConfig));

            tuningBindings.forEach(ApplicationSettingBinding::apply);
            reportBindings.forEach(ApplicationSettingBinding::apply);
            visualizationBindings.forEach(ApplicationSettingBinding::apply);
        } finally {
            suppressAutoApply = wasSuppressed;
        }
        publishActiveSettings();
        return adjustments;
    }

    /** Pushes the auto-tuned detection values to the readout on the main window. */
    private void publishActiveSettings() {
        if (spinDetectionSigma == null || spinMasterSigma == null || spinMaxMaskOverlapFraction == null || spinStarJitter == null) {
            return;
        }
        readout.showEnabledAnalyses(AnalysisScopeText.analyses(jTransientConfig));
        readout.showActiveDetectionSettings(jTransientConfig);
    }

    /** Puts each pair of settings back in order by moving the one its rule names, and says what was moved. */
    private List<String> normalizeDependentSpinners() {
        DetectionConfig draft = jTransientConfig.clone();
        typedBindings.forEach(binding -> binding.applyTo(draft));
        List<String> adjustments = new ArrayList<>();
        for (DetectionConfigRules.Adjustment change : DetectionConfigRules.align(draft, DetectionConfigRules.Operation.CONFIGURATION).adjustments()) {
            JSpinner moved = detectionInputs.get(change.field());
            if (moved == null) throw new IllegalStateException("No GUI binding for aligned field " + change.field());
            moved.setValue(change.newValue().doubleValue());
            adjustments.add(DetectionSettingsPresentation.adjustment(change, formatSpinnerValue(moved)));
        }
        return adjustments;
    }

    /** Shows configuration-only advice and applies reviewed-policy changes only after explicit confirmation. */
    private void reviewRules() {
        DetectionConfig config = checkedSettings("Review did not start.");
        if (config == null) return;
        List<DetectionConfigRules.Issue> issues = DetectionConfigRules.validate(config, DetectionConfigRules.Operation.CONFIGURATION);
        DetectionConfigRules.Alignment aligned = DetectionConfigRules.align(config, DetectionConfigRules.Operation.CONFIGURATION,
                DetectionConfigRules.AlignmentPolicy.REVIEWED_CONSISTENCY);
        String diagnostics = issues.isEmpty() ? "No configuration-only issues. Dataset readiness is checked during analysis."
                : String.join("\n\n", issues.stream().map(issue -> issue.severity() + " / " + issue.stage() + " / " + issue.ruleId() + ": "
                        + DetectionSettingsPresentation.issue(issue) + "\nFields: " + String.join(", ", issue.fields())).toList());
        String proposed = aligned.adjustments().isEmpty() ? ""
                : "\n\nOptional changes (not yet applied):\n" + String.join("\n", aligned.adjustments().stream()
                        .map(change -> DetectionSettingsPresentation.title(change.field()) + ": " + change.oldValue() + " → " + change.newValue()).toList())
                + "\n\nOnly these changes will be applied. Star caps, jitter and persisted annulus factors are not changed.";
        JTextArea text = new JTextArea(diagnostics + proposed, 17, 68);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        if (aligned.adjustments().isEmpty()) {
            JOptionPane.showMessageDialog(this, new JScrollPane(text), "Shared settings rules", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int choice = JOptionPane.showOptionDialog(this, new JScrollPane(text), "Shared settings rules",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"Align listed changes", "Keep settings"}, "Keep settings");
        if (choice != 0) return;
        jTransientConfig = aligned.config();
        updateSpinnersFromConfig(jTransientConfig);
        applySettingsToMemory();
        updateSavedState();
        showFooterNote("Reviewed consistency changes applied in memory; Save keeps them for the next start.");
    }

    private void commitAllSpinners() {
        commitSpinners(this);
    }

    private static void commitSpinners(Container container) {
        for (Component c : container.getComponents()) {
            if (c instanceof JSpinner) {
                try {
                    ((JSpinner) c).commitEdit();
                } catch (Exception ignored) {
                    // Invalid text stays in its field and the setting keeps its value; saves and runs
                    // (checkedSettings) refuse to start until the text is corrected.
                }
            } else if (c instanceof Container) {
                commitSpinners((Container) c);
            }
        }
    }

    /** A new session, or none: the Overview shows it, and the master stack of the last one is dropped. */
    void sessionChanged(FitsFileInformation[] filesInfo) {
        if (filesInfo.length == 0) {
            overviewPanel.setSession(sessions.current(), null, 0, 0, 0, false, null, new ArrayList<>());
            return;
        }
        boolean existsColor = false;
        for (FitsFileInformation fitsFile : filesInfo) {
            if (!fitsFile.isMonochrome()) {
                existsColor = true;
                break;
            }
        }
        File firstFile = new File(filesInfo[0].getFilePath());
        String sessionName = ViewerSupport.sessionName(firstFile);
        List<String> fileNames = new ArrayList<>();
        for (FitsFileInformation file : filesInfo) {
            fileNames.add(new File(file.getFilePath()).getName());
        }
        boolean readyForTuning = !existsColor && filesInfo.length >= SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        overviewPanel.setSession(sessions.current(), sessionName, filesInfo.length, filesInfo[0].getSizeWidth(), filesInfo[0].getSizeHeight(),
                readyForTuning, firstFile.getParentFile(), fileNames);
    }

    /** The end of Auto-Tune: the Overview shows the result or the problem, and the status bar says how it ended. */
    private void autoTuneFinished(JobOutcome<AutoTunerResult> outcome) {
        setCursor(Cursor.getDefaultCursor());
        if (outcome instanceof JobOutcome.Cancelled<?>) {
            // The status bar already says that Auto-Tune was cancelled.
            overviewPanel.tuneCancelled();
            return;
        }
        boolean completed = outcome instanceof JobOutcome.Completed<?>;
        AutoTunerResult result =
                outcome instanceof JobOutcome.Completed<AutoTunerResult> done ? done.result() : null;
        if (result != null && result.telemetryReport != null) {
            DebugOutput.println(result.telemetryReport);
        }
        String message = completed ? "Auto-Tuning completed successfully."
                : outcome instanceof JobOutcome.Failed<AutoTunerResult> failed ? failed.reason() : null;
        overviewPanel.tuneFinished(completed, message, result);
        readout.showStatus(completed ? "Auto-Tuning completed successfully." : "Auto-Tuning failed or aborted.");
        if (outcome instanceof JobOutcome.Failed<AutoTunerResult> failed) {
            FailureDialog.show(this, "Auto-Tune Failed", "Auto-Tune failed: " + failed.reason(), failed.cause());
        }
    }

    /**
     * Helper method to physically move the UI sliders to match a provided config.
     */
    private void updateSpinnersFromConfig(DetectionConfig config) {
        // One apply at the end instead of one per spinner, so no half-updated configuration is normalized.
        boolean wasSuppressed = suppressAutoApply;
        suppressAutoApply = true;
        try {
            setSpinnersFromConfig(config);
        } finally {
            suppressAutoApply = wasSuppressed;
        }
        // Push the visual changes to the underlying memory state immediately
        applySettingsToMemory();
    }

    private void setSpinnersFromConfig(DetectionConfig config) {
        reportBindings.forEach(ApplicationSettingBinding::load);

        typedBindings.forEach(binding -> binding.loadFrom(config));

    }

    private void showTelemetryReportWindow(String reportText) {
        JTextArea textArea = new JTextArea(reportText);
        textArea.setEditable(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UiStyle.bodyFont().getSize()));
        textArea.setMargin(new java.awt.Insets(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(800, 600));

        JDialog dialog = new JDialog(windowFrame, "Auto-Tuner Telemetry Report", true);
        dialog.getContentPane().add(scrollPane);
        dialog.pack();
        WindowSizing.install(dialog, 600, 400);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
}
