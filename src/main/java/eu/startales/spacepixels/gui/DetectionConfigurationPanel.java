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

import com.google.common.eventbus.Subscribe;
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;
import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.AutoTuneStartedEvent;
import eu.startales.spacepixels.events.FitsImportFinishedEvent;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.config.SpacePixelsDetectionProfileIO;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferences;
import eu.startales.spacepixels.config.SpacePixelsVisualizationPreferencesIO;
import eu.startales.spacepixels.tasks.AutoTuneTask;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import eu.startales.spacepixels.util.*;
import eu.startales.spacepixels.util.reporting.DetectionReportGenerator;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class DetectionConfigurationPanel extends JPanel {

    //link to main window
    private final ApplicationWindow mainAppWindow;

    private final TuningPreviewManager previewManager;

    private volatile DetectionConfig jTransientConfig;
    private volatile int autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
    private final File detectionProfileFile = new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.DEFAULT_FILENAME);
    private final File legacyDetectionProfileFile = new File(System.getProperty("user.home"), SpacePixelsDetectionProfileIO.LEGACY_FILENAME);
    private final File visualizationPreferencesFile = new File(System.getProperty("user.home"), SpacePixelsVisualizationPreferencesIO.DEFAULT_FILENAME);

    private JSpinner spinDetectionSigma, spinMinPixels, spinEdgeMargin, spinGrowSigma, spinVoidFraction, spinVoidRadius;
    private JCheckBox chkEnableSlowMovers, chkEnableBinaryStarLikeStreakShapeVeto;
    private JSpinner spinMasterSigma, spinMasterMinPix, spinMasterSlowMoverMinPixels, spinMasterSlowMoverSigma, spinMasterSlowMoverGrowSigma;
    private JSpinner spinSlowMoverMinAxisRatio, spinSlowMoverMaxAxisRatio, spinSlowMoverMinFillFactor;
    private JSpinner spinSlowMoverMedianSupportOverlapFraction, spinSlowMoverMedianSupportMaxOverlapFraction;
    private JSpinner spinSlowMoverMinFrameSupport, spinSlowMoverMaxStationaryLikelihood;
    private JSpinner spinStreakMinElong, spinStreakMinPix, spinSingleStreakMinPeakSigma, spinStreakTimeConsistencyTolerance;
    private JSpinner spinBgClippingIters, spinBgClippingFactor;

    // --- TrackLinker Spinners ---
    private JCheckBox chkStrictExposureKinematics, chkEnableGeometricTrackLinking;
    private JSpinner spinStarJitter, spinMaxMaskOverlapFraction, spinPredTol, spinAngleTol;
    private JSpinner spinTrackMinFrameRatio, spinAbsMaxPoints, spinMaxJump;
    private JSpinner spinRhythmVar, spinRhythmMinRatio, spinRhythmStatThresh, spinTimeBasedVelocityTolerance;
    private JSpinner spinMaxFwhmRatio, spinMaxSurfaceBrightnessRatio;

    // --- Anomaly Rescue ---
    private JCheckBox chkEnableAnomalyRescue;
    private JSpinner spinAnomalyMinPeakSigma, spinAnomalyMinPixels, spinAnomalyMinIntegratedSigma, spinAnomalyMinIntegratedPixels, spinAnomalyMinPeakSigmaFloor, spinSuspectedStreakLineTolerance, spinAnomalySuspectedStreakMinElongation;

    // --- Residual transient analysis ---
    private JCheckBox chkEnableResidualTransientAnalysis, chkEnableLocalRescueCandidates, chkEnableLocalActivityClusters;
    private JSpinner spinLocalActivityClusterRadiusPixels, spinLocalActivityClusterMinFrames;

    // --- Quality Control Spinners ---
    private JSpinner spinMinFramesAnalysis, spinStarCountSigma, spinFwhmSigma;
    private JSpinner spinEccentricitySigma, spinBackgroundSigma;
    // NEW: Absolute minimum tolerance spinners
    private JSpinner spinMinBgDevAdu, spinMinEccEnvelope, spinMinBrightStarEccEnvelope, spinMinFwhmEnvelope;

    private JSpinner spinQualitySigma, spinQualityGrowSigma, spinQualityMinPix, spinQualityBrightStarPeakSigmaOffset, spinQualityBrightStarMinStars, spinMaxElongFwhm, spinBrightStarEccentricitySigma;
    private JCheckBox chkEnableBrightStarEccentricityFilter;

    // --- Variable-star photometry ---
    private JCheckBox chkEnableVariableStarDetection, chkPhotometryFitPlane;
    private JSpinner spinPhotometryMaxStars, spinPhotometryMinSnr, spinPhotometryMaxElongation, spinPhotometryApertureFwhmFactor;
    private JSpinner spinPhotometryAnnulusInnerFwhmFactor, spinPhotometryAnnulusOuterFwhmFactor;
    private JSpinner spinPhotometrySaturationFraction, spinPhotometryMaxRegistrationSpreadPixels;
    private JSpinner spinLinearityMinDistinctLevels, spinLinearityMaxFloorClippedFraction;
    private JSpinner spinLinearityMaxConcentrationDrift, spinLinearityMinRangeMag, spinLinearityMinStars;
    private JSpinner spinLinearityMaxFrameSlope, spinLinearityMinZeroPointRangeMag;
    private JSpinner spinLinearityMaxSlopeTrackingCorrelation, spinLinearityMaxFailingFrameFraction;
    private JSpinner spinVariableMinFrames, spinVariableMinSpanMinutes, spinVariableNoiseModelNeighbors, spinVariableScoreSigma;
    private JSpinner spinVariableMinAmplitudeMag, spinVariableLimitedMinAmplitudeMag, spinVariableAmplitudeNoiseFactor;
    private JSpinner spinVariableMinPersistenceFrames, spinVariableMinSplitHalfCorrelation, spinVariableMaxApertureAmplitudeDifference;
    private JSpinner spinVariableMaxSystematicsCorrelation, spinVariableSystematicsResponseFactor, spinVariableLocalRadiusPixels, spinVariableMaxLocalCorrelation;

    private JSpinner spinAutoTuneMaxCandidateFrames;

    private JSpinner spinStreakScale, spinStreakCentroidRad, spinPointBoxRad, spinBoxPad;
    private JSpinner spinAutoBlackSigma, spinAutoWhiteSigma, spinGifBlinkSpeed, spinCropPadding;
    private JCheckBox chkIncludeAiCreativeReportSections;

    private final JButton previewBtn = new JButton("Preview Detection Settings");
    private final JButton autoTuneBtn = new JButton("Auto-Tune Settings");
    private final JComboBox<JTransientAutoTuner.AutoTuneProfile> autoTuneProfileCombo = new JComboBox<>(JTransientAutoTuner.AutoTuneProfile.values());

    public DetectionConfigurationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        loadPersistedSettings();

        this.previewManager = new TuningPreviewManager(mainAppWindow);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JTabbedPane tabbedPane = new JTabbedPane();

        // Build and add the tabs
        tabbedPane.addTab("Basic Tuning", buildScrollPane(buildBasicTuningPanel()));
        tabbedPane.addTab("Object Detection", buildScrollPane(buildSourceExtractionPanel()));
        tabbedPane.addTab("Streak Detection", buildScrollPane(buildStreakDetectionPanel()));
        tabbedPane.addTab("Moving Objects", buildScrollPane(buildMovingObjectsPanel()));
        tabbedPane.addTab("Anomaly Detection", buildScrollPane(buildAnomalyDetectionPanel()));
        tabbedPane.addTab("Slow Movers", buildScrollPane(buildSlowMoversPanel()));
        tabbedPane.addTab("Residual Analysis", buildScrollPane(buildResidualAnalysisPanel()));
        tabbedPane.addTab("Quality Control", buildScrollPane(buildQualityPanel()));
        tabbedPane.addTab("Variable Stars", buildScrollPane(buildVariableStarsPanel()));
        tabbedPane.addTab("Advanced Visualization", buildScrollPane(buildAdvancedVisualizationPanel()));

        add(tabbedPane, BorderLayout.CENTER);

        setupConstraints();

        JButton applyBtn = new JButton("Apply Settings");
        applyBtn.setToolTipText("Apply these parameters to the current detection engine session.");
        applyBtn.addActionListener(e -> {
            applySettingsToMemory();
            JOptionPane.showMessageDialog(this, "Settings Applied Successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        });

        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.setToolTipText("Save detection-profile and visualization preferences as the defaults for future startups.");
        saveBtn.addActionListener(e -> savePersistedSettings());

        JButton loadDefaultsBtn = new JButton("Load Defaults");
        loadDefaultsBtn.setToolTipText("Reset the detection settings in this panel to a fresh DetectionConfig instance. This does not overwrite the saved profile unless you save afterward.");
        loadDefaultsBtn.addActionListener(e -> {
            int choice = JOptionPane.showConfirmDialog(
                    this,
                    "Reset the detection settings in this panel to DetectionConfig defaults?\n\n" +
                            "This updates the current in-memory session only. Your saved profile remains unchanged until you click Save Configuration.",
                    "Load Detection Defaults",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                loadDetectionDefaults();
                JOptionPane.showMessageDialog(this, "DetectionConfig defaults loaded into the panel and current session.", "Defaults Loaded", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        previewBtn.setToolTipText("Run object detection on the selected frame with the current settings and show the exact detection mask.");
        previewBtn.addActionListener(e -> previewManager.showPreview(getJTransientConfig()));

        autoTuneBtn.setToolTipText("Mathematically sweeps settings to find the optimal signal-to-noise ratio for the current image sequence.");
        autoTuneBtn.addActionListener(e -> runAutoTuner());
        autoTuneBtn.setEnabled(false);

        autoTuneProfileCombo.setSelectedItem(JTransientAutoTuner.AutoTuneProfile.BALANCED);
        autoTuneProfileCombo.setToolTipText("Select the tuning strategy (Conservative = lower noise, Aggressive = faint targets).");
        autoTuneProfileCombo.setEnabled(false);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        bottomPanel.add(new JLabel("Tuning Profile: "));
        bottomPanel.add(autoTuneProfileCombo);
        bottomPanel.add(autoTuneBtn);
        bottomPanel.add(previewBtn);
        bottomPanel.add(loadDefaultsBtn);
        bottomPanel.add(saveBtn);
        bottomPanel.add(applyBtn);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setupConstraints() {
        // Ensure Detection Sigma >= Master Sigma
        spinDetectionSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
            double masterSigma = ((Number) spinMasterSigma.getValue()).doubleValue();
            if (detSigma < masterSigma) {
                spinDetectionSigma.setValue(masterSigma);
                detSigma = masterSigma;
            }

            // Ensure Master Sigma <= Grow Sigma <= Detection Sigma
            double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
            if (growSigma < masterSigma) {
                spinGrowSigma.setValue(masterSigma);
            } else if (detSigma < growSigma) {
                spinGrowSigma.setValue(detSigma);
            }
        });
        spinMasterSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
            double masterSigma = ((Number) spinMasterSigma.getValue()).doubleValue();
            if (masterSigma > detSigma) {
                spinMasterSigma.setValue(detSigma);
                masterSigma = detSigma;
            }

            double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
            if (growSigma < masterSigma) {
                spinGrowSigma.setValue(masterSigma);
            }
        });

        // Ensure Master Sigma <= Grow Sigma <= Detection Sigma
        spinGrowSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
            double masterSigma = ((Number) spinMasterSigma.getValue()).doubleValue();
            double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
            if (growSigma < masterSigma) {
                spinGrowSigma.setValue(masterSigma);
            } else if (growSigma > detSigma) {
                spinGrowSigma.setValue(detSigma);
            }
        });

        // Ensure Detection Min Pixels >= Master Min Pixels
        spinMinPixels.addChangeListener(e -> {
            int detPix = ((Number) spinMinPixels.getValue()).intValue();
            int masterPix = ((Number) spinMasterMinPix.getValue()).intValue();
            if (detPix < masterPix) spinMinPixels.setValue(masterPix);
        });
        spinMasterMinPix.addChangeListener(e -> {
            int detPix = ((Number) spinMinPixels.getValue()).intValue();
            int masterPix = ((Number) spinMasterMinPix.getValue()).intValue();
            if (masterPix > detPix) spinMasterMinPix.setValue(detPix);
        });

        // Ensure Master Slow Mover Grow Sigma <= Master Slow Mover Detection Sigma
        spinMasterSlowMoverSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinMasterSlowMoverSigma.getValue()).doubleValue();
            double growSigma = ((Number) spinMasterSlowMoverGrowSigma.getValue()).doubleValue();
            if (detSigma < growSigma) spinMasterSlowMoverGrowSigma.setValue(detSigma);
        });

        spinMasterSlowMoverGrowSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinMasterSlowMoverSigma.getValue()).doubleValue();
            double growSigma = ((Number) spinMasterSlowMoverGrowSigma.getValue()).doubleValue();
            if (growSigma > detSigma) spinMasterSlowMoverGrowSigma.setValue(detSigma);
        });

        // Basic tuning sigma changes are mirrored into the slow-mover sigma settings.
        spinDetectionSigma.addChangeListener(e -> syncSlowMoverSigmasFromBasicTuning());
        spinGrowSigma.addChangeListener(e -> syncSlowMoverSigmasFromBasicTuning());

        // A stationary threshold above the maximum jump makes geometric tracking self-contradictory.
        spinMaxJump.addChangeListener(e -> {
            double maxJump = ((Number) spinMaxJump.getValue()).doubleValue();
            double stationaryThreshold = ((Number) spinRhythmStatThresh.getValue()).doubleValue();
            if (stationaryThreshold > maxJump) spinRhythmStatThresh.setValue(maxJump);
        });
        spinRhythmStatThresh.addChangeListener(e -> {
            double maxJump = ((Number) spinMaxJump.getValue()).doubleValue();
            double stationaryThreshold = ((Number) spinRhythmStatThresh.getValue()).doubleValue();
            if (stationaryThreshold > maxJump) spinRhythmStatThresh.setValue(maxJump);
        });
    }

    /**
     * Copies the basic tuning Detection Sigma and Grow Sigma into the slow-mover Sigma and Grow Sigma.
     * Values are read from the spinners so constraint adjustments made by other listeners are respected.
     */
    private void syncSlowMoverSigmasFromBasicTuning() {
        double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
        double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
        // Set sigma first so the slow-mover grow sigma constraint does not cap the new grow value.
        setSpinnerValueClamped(spinMasterSlowMoverSigma, detSigma);
        setSpinnerValueClamped(spinMasterSlowMoverGrowSigma, growSigma);
    }

    private void runAutoTuner() {
        FitsFileInformation[] selectedFiles = mainAppWindow.getMainApplicationPanel().getSelectedFilesInformation();
        FitsFileInformation[] poolToUse;

        if (selectedFiles != null && selectedFiles.length >= SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            poolToUse = selectedFiles;
            System.out.println("Auto-Tuning using user's explicit selection of " + poolToUse.length + " frames.");
        } else {
            poolToUse = mainAppWindow.getMainApplicationPanel().getImportedFiles();
            System.out.println("Auto-Tuning using entire imported sequence.");
        }

        if (poolToUse == null || poolToUse.length < SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
            JOptionPane.showMessageDialog(this,
                    "You need at least " + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES + " monochrome frames available to run the Auto-Tuner.",
                    "Insufficient Data",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        applySettingsToMemory();

        mainAppWindow.getEventBus().post(new EngineProgressUpdateEvent(0, "Initializing Mathematical Auto-Tuner..."));

        JTransientAutoTuner.AutoTuneProfile selectedProfile = (JTransientAutoTuner.AutoTuneProfile) autoTuneProfileCombo.getSelectedItem();
        AutoTuneTask tuneTask = new AutoTuneTask(
                mainAppWindow.getEventBus(),
                poolToUse,
                jTransientConfig,
                autoTuneMaxCandidateFrames,
                selectedProfile);
        new Thread(tuneTask).start();
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
                try (FileReader migrationReader = new FileReader(profileToLoad)) {
                    if (SpacePixelsDetectionProfileIO.needsMigration(migrationReader)) {
                        SwingUtilities.invokeLater(() -> offerDetectionProfileMigration(profileToLoad, detectionProfile));
                    }
                } catch (Exception e) {
                    System.err.println("Failed to inspect detection profile for migration: " + e.getMessage());
                }
                return;
            } catch (Exception e) {
                System.err.println("Failed to load detection profile, falling back to defaults: " + e.getMessage());
            }
        }
        jTransientConfig = new DetectionConfig();
        autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
    }

    private void offerDetectionProfileMigration(File profileToLoad, SpacePixelsDetectionProfile detectionProfile) {
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

        try {
            File backup = SpacePixelsDetectionProfileIO.migrate(profileToLoad, detectionProfileFile, detectionProfile);
            JOptionPane.showMessageDialog(
                    this,
                    "Detection profile migrated to:\n" + detectionProfileFile.getAbsolutePath()
                            + "\n\nOriginal profile kept at:\n" + backup.getAbsolutePath(),
                    "Migration Complete",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to migrate detection profile: " + e.getMessage(), "Migration Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadVisualizationPreferences() {
        if (!visualizationPreferencesFile.exists()) {
            return;
        }

        try (FileReader reader = new FileReader(visualizationPreferencesFile)) {
            SpacePixelsVisualizationPreferences preferences = SpacePixelsVisualizationPreferencesIO.load(reader);
            preferences.applyToRuntime();
        } catch (Exception e) {
            System.err.println("Failed to load visualization preferences, falling back to defaults: " + e.getMessage());
        }
    }

    private void savePersistedSettings() {
        applySettingsToMemory();

        try (FileWriter detectionWriter = new FileWriter(detectionProfileFile);
             FileWriter visualizationWriter = new FileWriter(visualizationPreferencesFile)) {
            SpacePixelsDetectionProfileIO.write(
                    detectionWriter,
                    new SpacePixelsDetectionProfile(jTransientConfig, autoTuneMaxCandidateFrames));
            SpacePixelsVisualizationPreferencesIO.write(
                    visualizationWriter,
                    SpacePixelsVisualizationPreferences.captureCurrent());
            JOptionPane.showMessageDialog(
                    this,
                    "Configuration saved successfully to:\n" +
                            detectionProfileFile.getAbsolutePath() + "\n" +
                            visualizationPreferencesFile.getAbsolutePath(),
                    "Save Success",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to write configuration JSON: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadDetectionDefaults() {
        jTransientConfig = new DetectionConfig();
        updateSpinnersFromConfig(jTransientConfig);
    }

    public DetectionConfig getJTransientConfig() {
        applySettingsToMemory();
        return jTransientConfig;
    }

    private JPanel buildBasicTuningPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        JLabel basicIntroLabel = new JLabel("<html><div style='color: #999999; font-size: 12px; padding-bottom: 10px; width: 450px;'>" +
                "Make sure to run the Auto-Tuner by selecting a Tuning profile and clicking the Auto-Tune Settings button. Start here with the three core object-detection controls. Then move into the category tabs for streaks, moving objects, anomalies, slow movers, and detection safeguards. " +
                "If you detect too many transients and false positives, increase Detection Sigma, Grow Sigma, and Min Detection Pixels. " +
                "</div></html>");
        basicIntroLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(basicIntroLabel);

        panel.add(createSectionHeader("Core Object Detection"));
        spinDetectionSigma = addRow(panel, "Detection Sigma Multiplier", "Minimum brightness threshold for starting a new detection. Higher values reduce noise; lower values detect fainter objects.", doubleSpinnerModel(jTransientConfig.detectionSigmaMultiplier, 1.0, 20.0, 0.1));
        spinGrowSigma = addRow(panel, "Grow Sigma (Hysteresis)", "Secondary threshold used to expand a detection after it starts during per-frame object detection. Lower values capture fainter edges; higher values keep detections tighter. For stable veto-mask behavior this should not go below Master Sigma, and the UI enforces that. The master veto map itself internally uses the master sigma for both seed and grow thresholds.", doubleSpinnerModel(jTransientConfig.growSigmaMultiplier, 0.1, 20.0, 0.1));
        spinMinPixels = addRow(panel, "Min Detection Pixels", "Minimum blob size required for a detection to be kept. Higher values reject hot pixels and noise; lower values allow smaller sources.", intSpinnerModel(jTransientConfig.minDetectionPixels, 1, 2000, 1));

        return panel;
    }

    private JPanel buildSourceExtractionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Controls for the master veto map and low-level detection safeguards. If you get false detections near the edge of the frame increase Void Proximity Radius."));

        panel.add(createSectionHeader("Common Settings"));
        spinMasterSigma = addRow(panel, "Master Sigma Multiplier", "Detection threshold used when building the master star map. Lower values mask more faint stars and halos; higher values create a smaller, cleaner mask. For the master veto map, this value is used as both the seed and grow threshold to keep the mask tight, so per-frame Grow Sigma should not be set below it.", doubleSpinnerModel(jTransientConfig.masterSigmaMultiplier, 0.5, 15.0, 0.1));
        spinMasterMinPix = addRow(panel, "Master Min Pixels", "Minimum size required for a source to be included in the master star map. Lower values include fainter stars.", intSpinnerModel(jTransientConfig.masterMinDetectionPixels, 1, 2000, 1));
        spinMaxMaskOverlapFraction = addRow(panel, "Max Mask Overlap Fraction", "Maximum fraction of a point footprint that may overlap the master veto mask before it is rejected as likely stellar residual contamination.", doubleSpinnerModel(jTransientConfig.maxMaskOverlapFraction, 0.0, 1.0, 0.01));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinEdgeMargin = addRow(panel, "Edge Margin (Dead Zone)", "Rejects detections too close to the image edge, where alignment and stacking artifacts are common.", intSpinnerModel(jTransientConfig.edgeMarginPixels, 0, 2000, 1));
        spinVoidFraction = addRow(panel, "Void Threshold Fraction", "Pixels darker than this fraction of the local background are treated as registration void or padding, not real data.", doubleSpinnerModel(jTransientConfig.voidThresholdFraction, 0.0, 1.0, 0.01));
        spinVoidRadius = addRow(panel, "Void Proximity Radius", "How far to look for nearby void padding when rejecting edge or interpolation artifacts. Larger values are more aggressive.", intSpinnerModel(jTransientConfig.voidProximityRadius, 0, 200, 1));
        spinBgClippingIters = addRow(panel, "Sigma Clipping Iterations", "Number of clipping passes used when estimating background statistics. More passes remove bright-star contamination more thoroughly.", intSpinnerModel(jTransientConfig.bgClippingIterations, 1, 10, 1));
        spinBgClippingFactor = addRow(panel, "Sigma Clipping Factor", "Clipping threshold used during background estimation. Lower values clip bright stars more aggressively.", doubleSpinnerModel(jTransientConfig.bgClippingFactor, 1.0, 10.0, 0.1));

        return panel;
    }

    private JPanel buildStreakDetectionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Settings for streak detection."));

        panel.add(createSectionHeader("Common Settings"));
        spinStreakMinElong = addRow(panel, "Streak Min Elongation", "Minimum elongation required for a detected object to be treated as a streak candidate rather than a point source.", doubleSpinnerModel(jTransientConfig.streakMinElongation, 1.0, 20.0, 0.1));
        spinStreakMinPix = addRow(panel, "Streak Min Pixels", "Minimum size required for an elongated detection to be accepted as a streak. Higher values reject thin artifacts.", intSpinnerModel(jTransientConfig.streakMinPixels, 1, 2000, 1));
        spinSingleStreakMinPeakSigma = addRow(panel, "Single Streak Min Peak Sigma", "Minimum peak signal-to-noise required for a streak seen in only one frame. Helps reject elongated noise and interpolation artifacts.", doubleSpinnerModel(jTransientConfig.singleStreakMinPeakSigma, 0.0, 50.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinAngleTol = addRow(panel, "Trajectory Angle Tolerance", "For streak-based tracks, maximum allowed difference between the streak angle and the inferred track direction.", doubleSpinnerModel(jTransientConfig.angleToleranceDegrees, 0.5, 180.0, 0.5));
        spinStreakTimeConsistencyTolerance = addRow(
                panel,
                "Streak Time Consistency Tolerance",
                "When timestamps are available, this controls how much projected-speed variation the multi-frame streak linker tolerates between sampled frames. Higher values are more permissive for fragmented or faint streak trails.",
                doubleSpinnerModel(getOptionalDoubleField(jTransientConfig, "streakTimeConsistencyTolerance", 0.50), 0.0, 1.0, 0.01));
        chkEnableBinaryStarLikeStreakShapeVeto = addCheckboxRow(
                panel,
                "Enable Binary-Star-Like Streak Shape Veto",
                "Keeps the single-streak shape veto active for detections whose footprint looks more like a binary star than a real moving streak. Enable if you are getting false positives of stars close to each other as streaks.",
                getOptionalBooleanField(jTransientConfig, "enableBinaryStarLikeStreakShapeVeto", true));

        return panel;
    }

    private JPanel buildMovingObjectsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Configuration for moving-object track construction. Common settings cover the main linker behavior; advanced settings collect the stricter geometric-only limits and point-to-point similarity checks."));

        panel.add(createSectionHeader("Common Settings"));
        chkStrictExposureKinematics = addCheckboxRow(panel, "Strict Exposure Kinematics", "Rejects candidate links when the implied motion is inconsistent with exposure timing assumptions. Usually it is a good way to filter out false positives", jTransientConfig.strictExposureKinematics);
        chkEnableGeometricTrackLinking = addCheckboxRow(panel, "Enable Geometric Track Linking", "Enable the geometric (time-agnostic) track linker. May produce false positives", getOptionalBooleanField(jTransientConfig, "enableGeometricTrackLinking", true));
        spinStarJitter = addRow(panel, "Base Star Jitter Radius", "Baseline radius under which detections are treated as stationary star jitter instead of true moving points. Higher values are more conservative around registration residuals.", doubleSpinnerModel(jTransientConfig.maxStarJitter, 0.0, 20.0, 0.1));
        spinPredTol = addRow(panel, "Prediction Line Tolerance", "Maximum distance a candidate point may sit from the projected track line and still be accepted. Higher values allow noisier tracks but increase false links.", doubleSpinnerModel(jTransientConfig.predictionTolerance, 0.1, 50.0, 0.1));
        spinTrackMinFrameRatio = addRow(panel, "Track Length Min Frame Ratio", "Controls minimum track length as required points = total frames / this value. Lower values demand longer tracks; higher values allow shorter ones.", doubleSpinnerModel(jTransientConfig.trackMinFrameRatio, 1.0, 20.0, 0.25));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinAbsMaxPoints = addRow(panel, "Absolute Max Required Points", "Upper limit on the required number of points for a valid track in very long sequences.", intSpinnerModel(jTransientConfig.absoluteMaxPointsRequired, 2, 100, 1));
        spinMaxFwhmRatio = addRow(panel, "Max FWHM Ratio", "Maximum allowed FWHM difference between linked points. Helps ensure all points in a track have similar optical blur; 0 disables this check.", doubleSpinnerModel(jTransientConfig.maxFwhmRatio, 0.0, 10.0, 0.1));
        spinMaxSurfaceBrightnessRatio = addRow(panel, "Max Surface Brightness Ratio", "Maximum allowed surface-brightness difference between linked points. Helps prevent linking compact artifacts to diffuse blobs; 0 disables this check.", doubleSpinnerModel(jTransientConfig.maxSurfaceBrightnessRatio, 0.0, 10.0, 0.1));
        spinMaxJump = addRow(panel, "Max Jump Velocity", "Maximum geometric jump allowed between linked detections. This is only relevant to the geometric moving-object linker.", doubleSpinnerModel(jTransientConfig.maxJumpPixels, 0.0, 10000.0, 0.1));
        spinRhythmVar = addRow(panel, "Allowed Rhythm Variance", "Maximum deviation from the median step size allowed when checking whether a track moves at a steady rate.", doubleSpinnerModel(jTransientConfig.rhythmAllowedVariance, 0.0, 20.0, 0.1));
        spinRhythmMinRatio = addRow(panel, "Min Rhythm Consistency Ratio", "Minimum fraction of jumps that must match the median speed within the allowed variance for the geometric linker to keep the track.", doubleSpinnerModel(jTransientConfig.rhythmMinConsistencyRatio, 0.0, 1.0, 0.01));
        spinRhythmStatThresh = addRow(panel, "Rhythm Stationary Threshold", "Tracks with median jump below this value are treated as stationary noise or residual stars rather than moving objects by the geometric linker.", doubleSpinnerModel(jTransientConfig.rhythmStationaryThreshold, 0.0, 20.0, 0.1));
        spinTimeBasedVelocityTolerance = addRow(panel, "Time-Based Velocity Tolerance", "When timestamps are available, this controls how much speed variation the moving-object linker tolerates between points before rejecting the track.", doubleSpinnerModel(jTransientConfig.timeBasedVelocityTolerance, 0.0, 1.0, 0.01));

        return panel;
    }

    private JPanel buildAnomalyDetectionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Anomalies are either transient high energy events or transient larger but fainter events. Here are the controls for single-frame anomaly rescue and suspected same-frame anomaly streak identification. Sometimes faint streaks produce false positive anomalies."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableAnomalyRescue = addCheckboxRow(panel, "Enable Anomaly Rescue", "Keeps single-frame anomaly rescue active for flashes and fragments that do not become full multi-frame tracks.", jTransientConfig.enableAnomalyRescue);
        spinAnomalyMinPeakSigma = addRow(panel, "Anomaly Min Peak Sigma", "Minimum peak signal-to-noise required for a single-frame point source to be rescued as an anomaly. Higher values are stricter.", doubleSpinnerModel(jTransientConfig.anomalyMinPeakSigma, 1.0, 50.0, 0.1));
        spinAnomalyMinPixels = addRow(panel, "Anomaly Min Pixels", "Minimum size required for a single-frame point source to be rescued. Higher values reject more hot pixels and cosmic rays.", intSpinnerModel(jTransientConfig.anomalyMinPixels, 1, 2000, 1));
        spinAnomalyMinIntegratedSigma = addRow(panel, "Anomaly Min Integrated Sigma", "Minimum integrated signal-to-noise required for the broader single-frame anomaly rescue path. Higher values demand stronger total support from faint but larger flashes.", doubleSpinnerModel(jTransientConfig.anomalyMinIntegratedSigma, 1.0, 200.0, 0.5));
        spinAnomalyMinIntegratedPixels = addRow(panel, "Anomaly Min Integrated Pixels", "Minimum footprint size required for the integrated-sigma anomaly path. Higher values reject small high-energy fragments from the broader rescue branch.", intSpinnerModel(jTransientConfig.anomalyMinIntegratedPixels, 1, 2000, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinAnomalyMinPeakSigmaFloor = addRow(panel, "Anomaly Min Peak Sigma Floor", "Safety floor for diffuse anomaly rescue. Even broader anomalies must retain at least some local prominence to avoid low-contrast mush.", doubleSpinnerModel(jTransientConfig.anomalyMinPeakSigmaFloor, 0.0, 20.0, 0.1));
        spinSuspectedStreakLineTolerance = addRow(panel, "Suspected Streak Line Tolerance", "Maximum perpendicular centroid distance allowed when grouping rescued same-frame anomalies into a suspected streak line. Higher values group faint streak fragments more permissively; lower values keep the grouping tighter.", doubleSpinnerModel(getOptionalDoubleField(jTransientConfig, "suspectedStreakLineTolerance", 6.0), 0.0, 50.0, 0.1));
        spinAnomalySuspectedStreakMinElongation = addRow(panel, "Anomaly Suspected Streak Min Elongation", "Rescued anomalies above this elongation are checked for same-frame collinear grouping and may be exported as suspected streak tracks. Higher values restrict grouping to more elongated anomaly fragments.", doubleSpinnerModel(getOptionalDoubleField(jTransientConfig, "anomalySuspectedStreakMinElongation", 3.5), 1.0, 20.0, 0.1));

        return panel;
    }

    private JPanel buildSlowMoversPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Find elongated shapes in the maximum stack and compare them with sources in the median stack. These are candidates to review, not confirmed moving objects."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableSlowMovers = addCheckboxRow(panel, "Enable Slow-Mover Detection", "Look for elongated shapes in the maximum stack after poor-quality frames are removed.<br>Results are candidates for review, not confirmed tracks.", jTransientConfig.enableSlowMoverDetection);
        spinMasterSlowMoverSigma = addRow(panel, "Slow-Mover Sigma", "A pixel must rise this far above the estimated stack background to start a source.<br>Used on both stacks. Lower values find fainter sources but may add noise.", doubleSpinnerModel(jTransientConfig.masterSlowMoverSigmaMultiplier, 0.5, 15.0, 0.1));
        spinMasterSlowMoverGrowSigma = addRow(panel, "Slow-Mover Grow Sigma", "Neighboring pixels above this lower brightness threshold join a source.<br>Used on both stacks. Lower values enlarge shapes and may merge sources.<br>Cannot exceed Slow-Mover Sigma.", doubleSpinnerModel(jTransientConfig.masterSlowMoverGrowSigmaMultiplier, 0.1, 15.0, 0.1));
        spinMasterSlowMoverMinPixels = addRow(panel, "Slow-Mover Min Pixels", "Fewest connected pixels required to keep a source in either stack.<br>Higher values reject small noise spots but may miss small sources.", intSpinnerModel(jTransientConfig.masterSlowMoverMinPixels, 1, 2000, 1));
        spinSlowMoverMinAxisRatio = addRow(panel, "Min Axis Ratio", "Length divided by width of a candidate's detected shape; 1 is roughly round.<br>Raise this to reject rounder sources.", doubleSpinnerModel(jTransientConfig.slowMoverMinAxisRatio, 1.0, 20.0, 0.05));
        spinSlowMoverMaxAxisRatio = addRow(panel, "Max Axis Ratio", "Largest allowed length-to-width ratio for a candidate.<br>Lower this to reject longer, thinner streaks. Cannot be below Min Axis Ratio.", doubleSpinnerModel(jTransientConfig.slowMoverMaxAxisRatio, 1.0, 20.0, 0.05));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinSlowMoverMinFillFactor = addRow(panel, "Min Fill Factor", "How solid the shape must be inside a rectangle aligned with it.<br>Fill factor is the fraction of that rectangle covered by detected pixels.<br>Higher values reject sparse, bent, or branched shapes. Zero disables it.", doubleSpinnerModel(jTransientConfig.slowMoverMinFillFactor, 0.0, 1.0, 0.01));
        spinSlowMoverMedianSupportOverlapFraction = addRow(panel, "Median Mask Min Overlap", "Minimum share of candidate pixels also in the median-stack source mask.<br>0.20 requires 20% overlap; 0 does not require overlap.<br>Higher values require more persistence.", doubleSpinnerModel(jTransientConfig.slowMoverMedianSupportOverlapFraction, 0.0, 1.0, 0.01));
        spinSlowMoverMedianSupportMaxOverlapFraction = addRow(panel, "Median Mask Max Overlap", "Largest share of candidate pixels allowed in the median-stack source mask.<br>0.80 rejects over 80% overlap. Lower values reject more stationary sources.<br>1 disables this limit.", doubleSpinnerModel(jTransientConfig.slowMoverMedianSupportMaxOverlapFraction, 0.0, 1.0, 0.01));
        spinSlowMoverMinFrameSupport = addRow(panel, "Min Frame Support (%)", "Minimum share of usable frames with significant signal inside the candidate shape.<br>Raise to require evidence in more frames; zero disables this rejection.<br>Skipped if fewer than two usable frames. This does not confirm motion.", doubleSpinnerModel(jTransientConfig.slowMoverMinFrameSupport, 0.0, 100.0, 1.0));
        spinSlowMoverMaxStationaryLikelihood = addRow(panel, "Max Stationary Likelihood (%)", "Largest allowed share of supported frame positions clustered near one spot.<br>Lower to reject more stationary-looking candidates; 100 disables it.<br>Skipped if evidence is insufficient. This heuristic is not a probability.", doubleSpinnerModel(jTransientConfig.slowMoverMaxStationaryLikelihood, 0.0, 100.0, 1.0));

        return panel;
    }

    private JPanel buildResidualAnalysisPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Final-pass analysis of the leftover per-frame point transients after JTransient finishes normal classification. This stage runs only on detections that were not consumed by confirmed moving-object tracks, accepted streak tracks, suspected same-frame streak tracks, or standalone anomalies. It first looks for weak object-like local rescue candidates, then groups the still-leftover points into broader local activity clusters for manual review."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableResidualTransientAnalysis = addCheckboxRow(panel, "Enable Residual Transient Analysis", "Master switch for the final-pass leftover-transient analysis stage. Disable this to skip both local rescue candidates and local activity clusters.", getOptionalBooleanField(jTransientConfig, "enableResidualTransientAnalysis", true));
        chkEnableLocalRescueCandidates = addCheckboxRow(panel, "Enable Local Rescue Candidates", "Runs the object-like rescue pass on leftover point detections to surface weak local motion or repeaters that did not become normal tracks.", getOptionalBooleanField(jTransientConfig, "enableLocalRescueCandidates", true));
        chkEnableLocalActivityClusters = addCheckboxRow(panel, "Enable Local Activity Clusters", "After local rescue candidates are removed, groups the remaining leftover detections into broad same-area activity clusters for review. These are not confirmed objects.", getOptionalBooleanField(jTransientConfig, "enableLocalActivityClusters", true));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Activity Cluster Settings"));
        spinLocalActivityClusterRadiusPixels = addRow(panel, "Local Activity Cluster Radius (Pixels)", "Spatial linkage radius used when grouping leftover detections into broad local activity clusters after rescue candidates are consumed.", doubleSpinnerModel(getOptionalDoubleField(jTransientConfig, "localActivityClusterRadiusPixels", 10.0), 0.0, 100.0, 0.5));
        spinLocalActivityClusterMinFrames = addRow(panel, "Local Activity Cluster Min Frames", "Minimum number of unique frames required before a broad local activity cluster is exported for review.", intSpinnerModel(getOptionalIntField(jTransientConfig, "localActivityClusterMinFrames", 3), 1, 100, 1));

        return panel;
    }

    private JPanel buildVariableStarsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Measure the brightness of stationary stars in every frame that passed quality control, check whether the frames respond linearly to light, and report stars whose brightness changes more than noise and shared systematics explain. The analysis is tuned to avoid false variables: a missed variable is preferred over a false one. Results and per-frame diagnostics appear in the pipeline telemetry."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableVariableStarDetection = addCheckboxRow(panel, "Enable Variable-Star Detection", "Run stationary-star photometry and variable-star detection after moving-object tracking.<br>Adds processing time proportional to stars x frames.", jTransientConfig.enableVariableStarDetection);
        spinPhotometryMaxStars = addRow(panel, "Max Stars Measured", "Most stars measured; 0 measures every usable star (recommended).<br>A cap picks stars evenly across the field and the brightness range. Saturated stars are always skipped.", intSpinnerModel(jTransientConfig.photometryMaxStars, 0, 200000, 500));
        spinPhotometryMinSnr = addRow(panel, "Min Star SNR", "Stars fainter than this median signal-to-noise ratio are measured and exported, but not used or scored.<br>0 disables the cut.", doubleSpinnerModel(jTransientConfig.photometryMinSnr, 0.0, 1000.0, 1.0));
        spinVariableScoreSigma = addRow(panel, "Variability Score Sigma", "How far above same-brightness stars both the scatter score and the Stetson J score must be for a star to become a candidate.<br>Higher values give fewer, safer candidates.", doubleSpinnerModel(jTransientConfig.variableScoreSigma, 1.0, 50.0, 0.5));
        spinVariableMinAmplitudeMag = addRow(panel, "Min Amplitude (mag)", "Smallest brightness change (95th minus 5th percentile) reported as high confidence.", doubleSpinnerModel(jTransientConfig.variableMinAmplitudeMag, 0.0, 5.0, 0.01));
        spinVariableMinFrames = addRow(panel, "Min Frames", "Fewest usable measurements a star needs to be scored, and fewest frames the session must keep after the photometry checks.", intSpinnerModel(jTransientConfig.variableMinFrames, 3, 10000, 1));
        spinVariableMinSpanMinutes = addRow(panel, "Min Time Span (minutes)", "Shortest time span of a candidate's measurements for a high-confidence result.<br>Ignored when the frames have no timestamps.", doubleSpinnerModel(jTransientConfig.variableMinSpanMinutes, 0.0, 1440.0, 5.0));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Apertures And Star Selection"));
        spinPhotometryApertureFwhmFactor = addRow(panel, "Aperture Radius (x FWHM)", "Photometry aperture radius in units of each frame's measured FWHM.", doubleSpinnerModel(jTransientConfig.photometryApertureFwhmFactor, 0.5, 5.0, 0.1));
        spinPhotometryAnnulusInnerFwhmFactor = addRow(panel, "Sky Annulus Inner Radius (x FWHM)", "Inner radius of the sky ring. Stars with a bright neighbour inside this radius are not measured.", doubleSpinnerModel(jTransientConfig.photometryAnnulusInnerFwhmFactor, 1.0, 20.0, 0.1));
        spinPhotometryAnnulusOuterFwhmFactor = addRow(panel, "Sky Annulus Outer Radius (x FWHM)", "Outer radius of the sky ring. Must be larger than the inner radius.", doubleSpinnerModel(jTransientConfig.photometryAnnulusOuterFwhmFactor, 1.5, 30.0, 0.1));
        spinPhotometryMaxElongation = addRow(panel, "Max Star Elongation", "Master stars more elongated than this (blends, galaxies, trails) are not measured.", doubleSpinnerModel(jTransientConfig.photometryMaxElongation, 1.0, 5.0, 0.05));
        spinPhotometrySaturationFraction = addRow(panel, "Saturation Fraction", "A measurement is flagged saturated when its peak pixel exceeds this fraction of the session saturation level.", doubleSpinnerModel(jTransientConfig.photometrySaturationFraction, 0.1, 1.0, 0.01));
        chkPhotometryFitPlane = addCheckboxRow(panel, "Fit Per-Frame Gradient", "Fit a linear brightness gradient across the field in every frame, absorbing sky gradients and differential extinction.", jTransientConfig.photometryFitPlane);
        spinPhotometryMaxRegistrationSpreadPixels = addRow(panel, "Max Registration Spread (px)", "Frames whose star positions scatter more than this around the median offset are excluded (rotation or poor alignment).", doubleSpinnerModel(jTransientConfig.photometryMaxRegistrationSpreadPixels, 0.0, 10.0, 0.05));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Linearity Checks"));
        spinLinearityMinDistinctLevels = addRow(panel, "A: Min Distinct Pixel Levels", "Fewer distinct pixel values suggest 8-bit or heavily quantised data; the session is refused.", intSpinnerModel(jTransientConfig.linearityMinDistinctLevels, 2, 65536, 64));
        spinLinearityMaxFloorClippedFraction = addRow(panel, "A: Max Clipped Sky Fraction", "Largest share of sky pixels allowed at zero. More means negative sky noise was clipped and faint stars are biased; the session is limited.", doubleSpinnerModel(jTransientConfig.linearityMaxFloorClippedFraction, 0.0, 1.0, 0.005));
        spinLinearityMaxConcentrationDrift = addRow(panel, "B: Max Concentration Drift", "Largest change of star concentration (flux in 0.7 x FWHM / flux in 2.5 x FWHM) from faint to bright stars inside the linear range.<br>Stretched data makes bright stars flatter.", doubleSpinnerModel(jTransientConfig.linearityMaxConcentrationDrift, 0.0, 0.5, 0.005));
        spinLinearityMinRangeMag = addRow(panel, "B: Min Linear Range (mag)", "Frames whose linear magnitude range is narrower than this are excluded.", doubleSpinnerModel(jTransientConfig.linearityMinRangeMag, 0.0, 10.0, 0.1));
        spinLinearityMinStars = addRow(panel, "B: Min Stars", "Fewest bright-enough stars a frame needs for the linearity check, and fewest measurable stars for photometry to run.", intSpinnerModel(jTransientConfig.linearityMinStars, 5, 5000, 5));
        spinLinearityMaxFrameSlope = addRow(panel, "D: Max Response Slope (mag/mag)", "Largest allowed difference in how bright and faint stars respond within one frame. Failing frames are excluded.", doubleSpinnerModel(jTransientConfig.linearityMaxFrameSlope, 0.0, 0.5, 0.001));
        spinLinearityMinZeroPointRangeMag = addRow(panel, "D: Min Transparency Range (mag)", "Below this change in transparency over the session, check D cannot confirm linearity and the session is limited.", doubleSpinnerModel(jTransientConfig.linearityMinZeroPointRangeMag, 0.0, 2.0, 0.01));
        spinLinearityMaxSlopeTrackingCorrelation = addRow(panel, "D: Max Slope Tracking Correlation", "The session is refused when the response slope follows transparency or sky level at least this strongly.", doubleSpinnerModel(jTransientConfig.linearityMaxSlopeTrackingCorrelation, 0.0, 1.0, 0.05));
        spinLinearityMaxFailingFrameFraction = addRow(panel, "B/D: Max Failing Frame Fraction", "Share of frames that may fail check B or D before the whole session is refused.", doubleSpinnerModel(jTransientConfig.linearityMaxFailingFrameFraction, 0.0, 1.0, 0.05));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Candidate Gates"));
        spinVariableNoiseModelNeighbors = addRow(panel, "Noise Model Neighbours", "Stars nearest in brightness used to estimate the expected scatter of each star.", intSpinnerModel(jTransientConfig.variableNoiseModelNeighbors, 5, 1000, 5));
        spinVariableLimitedMinAmplitudeMag = addRow(panel, "Min Amplitude When Limited (mag)", "Smallest amplitude reported as high confidence when the linearity verdict is Limited.", doubleSpinnerModel(jTransientConfig.variableLimitedMinAmplitudeMag, 0.0, 5.0, 0.01));
        spinVariableAmplitudeNoiseFactor = addRow(panel, "Amplitude Noise Factor", "The amplitude must also exceed this many times the expected scatter at the star's brightness.", doubleSpinnerModel(jTransientConfig.variableAmplitudeNoiseFactor, 0.0, 50.0, 0.5));
        spinVariableMinPersistenceFrames = addRow(panel, "Min Persistence (frames)", "Fewest consecutive frames that must deviate in the same direction. Rejects single-frame events.", intSpinnerModel(jTransientConfig.variableMinPersistenceFrames, 1, 1000, 1));
        spinVariableMinSplitHalfCorrelation = addRow(panel, "Min Split-Half Correlation", "Consecutive measurement pairs must agree at least this well. Rejects noise that only looks coherent.", doubleSpinnerModel(jTransientConfig.variableMinSplitHalfCorrelation, -1.0, 1.0, 0.05));
        spinVariableMaxApertureAmplitudeDifference = addRow(panel, "Max Aperture Amplitude Difference", "Amplitudes measured with small and large apertures may differ by at most this fraction. Rejects neighbour leakage and seeing effects.", doubleSpinnerModel(jTransientConfig.variableMaxApertureAmplitudeDifference, 0.0, 5.0, 0.05));
        spinVariableMaxSystematicsCorrelation = addRow(panel, "Max Systematics Correlation", "Light curves correlating more than this with transparency, FWHM, local sky or registration offsets are checked against the response factor below.<br>Correlation alone does not reject a star: real variables that brighten or fade steadily correlate with any steady drift.", doubleSpinnerModel(jTransientConfig.variableMaxSystematicsCorrelation, 0.0, 1.0, 0.05));
        spinVariableSystematicsResponseFactor = addRow(panel, "Systematics Response Factor", "A correlated systematic rejects a candidate only if its amplitude is at most this many times the largest response that constant stars of similar brightness show to the same systematic.<br>Lower values reject fewer candidates.", doubleSpinnerModel(jTransientConfig.variableSystematicsResponseFactor, 0.0, 100.0, 0.5));
        spinVariableLocalRadiusPixels = addRow(panel, "Local Comparison Radius (px)", "Radius within which nearby constant stars are checked for the same pattern.", doubleSpinnerModel(jTransientConfig.variableLocalRadiusPixels, 0.0, 5000.0, 10.0));
        spinVariableMaxLocalCorrelation = addRow(panel, "Max Local Correlation", "Largest allowed median correlation with nearby constant stars. Rejects dust, dew and local gradients.", doubleSpinnerModel(jTransientConfig.variableMaxLocalCorrelation, 0.0, 1.0, 0.05));

        return panel;
    }

    private JPanel buildQualityPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Auto-Tuner Candidate Pool"));
        spinAutoTuneMaxCandidateFrames = addRow(
                panel,
                "Max Frames For Auto-Tuner",
                "Maximum number of frames SpacePixels will hand to the JTransient Auto-Tuner. When the sequence is longer than this, SpacePixels builds a deterministic pool using best-quality, median-quality, and evenly spaced frames.",
                intSpinnerModel(autoTuneMaxCandidateFrames, SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES, 5000, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Session Outlier Rejection (MAD)"));
        spinMinFramesAnalysis = addRow(panel, "Min Frames for Analysis", "Minimum number of frames required before session-level outlier rejection is applied.", intSpinnerModel(jTransientConfig.minFramesForAnalysis, 2, 500, 1));
        spinStarCountSigma = addRow(panel, "Star Count Drop Sigma", "Rejects frames whose star count falls too far below the session median. Higher values are more tolerant.", doubleSpinnerModel(jTransientConfig.starCountSigmaDeviation, 0.0, 10.0, 0.1));
        spinFwhmSigma = addRow(panel, "FWHM Spike Sigma", "Rejects frames whose median FWHM rises too far above the session median. Higher values are more tolerant.", doubleSpinnerModel(jTransientConfig.fwhmSigmaDeviation, 0.0, 10.0, 0.1));
        spinEccentricitySigma = addRow(panel, "Eccentricity Spike Sigma", "Rejects frames whose star shapes become too elongated compared with the session median. Higher values are more tolerant.", doubleSpinnerModel(jTransientConfig.eccentricitySigmaDeviation, 0.0, 10.0, 0.1));
        chkEnableBrightStarEccentricityFilter = addCheckboxRow(panel, "Enable Bright-Star Eccentricity Filter", "Runs an extra rejection gate using only the brightest quality stars, which is useful for catching tracking error or wind that shows up most clearly on high-SNR stars.", jTransientConfig.enableBrightStarEccentricityFilter);
        spinBrightStarEccentricitySigma = addRow(panel, "Bright-Star Eccentricity Sigma", "Rejects frames whose bright-star median eccentricity rises too far above the session median. Higher values are more tolerant.", doubleSpinnerModel(jTransientConfig.brightStarEccentricitySigmaDeviation, 0.0, 10.0, 0.1));
        spinBackgroundSigma = addRow(panel, "Background Deviation Sigma", "Rejects frames whose background level deviates too much from the session median. Higher values are more tolerant.", doubleSpinnerModel(jTransientConfig.backgroundSigmaDeviation, 0.0, 10.0, 0.1));

        // --- NEW: ABSOLUTE MINIMUMS SECTION ---
        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Absolute Minimum Tolerances"));
        spinMinBgDevAdu = addRow(panel, "Min Background Deviation (ADU)", "Minimum absolute background tolerance used even when the measured session variation is tiny.", doubleSpinnerModel(jTransientConfig.minBackgroundDeviationADU, 0.0, 10000.0, 5.0));
        spinMinEccEnvelope = addRow(panel, "Min Eccentricity Envelope", "Minimum absolute tolerance around the session eccentricity median, so tiny shape changes do not trigger rejection.", doubleSpinnerModel(jTransientConfig.minEccentricityEnvelope, 0.0, 1.0, 0.01));
        spinMinBrightStarEccEnvelope = addRow(panel, "Min Bright-Star Eccentricity Envelope", "Minimum absolute tolerance around the bright-star eccentricity median, so tiny high-SNR shape changes do not trigger rejection.", doubleSpinnerModel(jTransientConfig.minBrightStarEccentricityEnvelope, 0.0, 1.0, 0.01));
        spinMinFwhmEnvelope = addRow(panel, "Min FWHM Envelope (Pixels)", "Minimum absolute tolerance around the session FWHM median, so tiny focus changes do not trigger rejection.", doubleSpinnerModel(jTransientConfig.minFwhmEnvelope, 0.0, 5.0, 0.05));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Single Frame Analytics"));
        spinQualitySigma = addRow(panel, "Quality Eval Sigma Multiplier", "Detection threshold used only for extracting stars for frame quality analysis, not for transient detection.", doubleSpinnerModel(jTransientConfig.qualitySigmaMultiplier, 1.0, 15.0, 0.1));
        spinQualityGrowSigma = addRow(panel, "Quality Grow Sigma", "Secondary hysteresis threshold used only while growing stars for frame quality analysis and auto-tune frame sampling.", doubleSpinnerModel(jTransientConfig.qualityGrowSigmaMultiplier, 0.1, 20.0, 0.1));
        spinQualityMinPix = addRow(panel, "Quality Min Detection Pixels", "Minimum source size required for a star to be used in frame quality analysis.", intSpinnerModel(jTransientConfig.qualityMinDetectionPixels, 1, 500, 1));
        spinQualityBrightStarPeakSigmaOffset = addRow(panel, "Bright-Star Peak Sigma Offset", "Additional peak-sigma above the quality seed threshold required before a quality star contributes to the bright-star eccentricity metric.", doubleSpinnerModel(jTransientConfig.qualityBrightStarPeakSigmaOffset, 0.0, 50.0, 0.1));
        spinQualityBrightStarMinStars = addRow(panel, "Bright-Star Min Stars", "Minimum number of qualifying bright stars required before the bright-star eccentricity metric is considered valid for a frame.", intSpinnerModel(jTransientConfig.qualityBrightStarMinStars, 1, 500, 1));
        spinMaxElongFwhm = addRow(panel, "Quality Max Elongation for FWHM", "Only stars with elongation below this value are used when measuring median FWHM for frame quality.", doubleSpinnerModel(jTransientConfig.qualityMaxElongationForFwhm, 1.0, 10.0, 0.05));

        return panel;
    }

    private JPanel buildAdvancedVisualizationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Visualization-only controls for exported reports and review imagery. These settings do not change detection results; they only affect presentation, contrast stretch, and animation cadence."));

        panel.add(createSectionHeader("Display Stretch & Animation"));
        spinAutoBlackSigma = addRow(panel, "Auto Stretch Black Sigma", "Controls how far below the image mean the automatic black point is placed when stretching rendered imagery. Higher values darken the background more aggressively.", doubleSpinnerModel(DisplayImageRenderer.autoStretchBlackSigma, 0.0, 10.0, 0.1));
        spinAutoWhiteSigma = addRow(panel, "Auto Stretch White Sigma", "Controls how far above the image mean the automatic white point is placed when stretching rendered imagery. Higher values preserve more bright-core detail but can reduce contrast on faint structure.", doubleSpinnerModel(DisplayImageRenderer.autoStretchWhiteSigma, 0.1, 20.0, 0.1));
        spinGifBlinkSpeed = addRow(panel, "GIF Blink Speed (ms)", "Frame delay used for exported animated GIFs. Lower values blink faster; higher values slow the inspection cadence.", intSpinnerModel(DetectionReportGenerator.gifBlinkSpeedMs, 50, 5000, 10));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Raw Image Annotations"));
        spinStreakScale = addRow(panel, "Streak Line Scale Factor", "Visualization-only setting that scales the length of the drawn streak annotation line.", doubleSpinnerModel(RawImageAnnotator.streakLineScaleFactor, 0.1, 20.0, 0.1));
        spinStreakCentroidRad = addRow(panel, "Streak Centroid Box Radius", "Visualization-only setting that controls the size of the box drawn around a streak centroid.", intSpinnerModel(RawImageAnnotator.streakCentroidBoxRadius, 1, 100, 1));
        spinPointBoxRad = addRow(panel, "Point Source Min Box Radius", "Visualization-only setting that sets the minimum radius of boxes drawn around point sources.", intSpinnerModel(RawImageAnnotator.pointSourceMinBoxRadius, 1, 50, 1));
        spinBoxPad = addRow(panel, "Dynamic Box Padding", "Visualization-only setting that adds extra padding around automatically sized annotation boxes.", intSpinnerModel(RawImageAnnotator.dynamicBoxPadding, 0, 50, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Image Cropping"));
        spinCropPadding = addRow(panel, "Track Crop Border Padding", "Export-only setting that adds extra border around cropped track images.", intSpinnerModel(DetectionReportGenerator.trackCropPadding, 0, 2000, 10));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Optional Report Sections"));
        chkIncludeAiCreativeReportSections = addCheckboxRow(
                panel,
                "Include AI Creative Report Sections",
                "These optional reporting sections gave AI agents freedom to visualize the detection data in their own way. You can see the results by enabling them.",
                DetectionReportGenerator.includeAiCreativeReportSections);

        return panel;
    }

    private JLabel createTabIntro(String text) {
        JLabel introLabel = new JLabel("<html><div style='color: #999999; font-size: 12px; padding-bottom: 10px; width: 450px;'>" + text + "</div></html>");
        introLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return introLabel;
    }

    private JScrollPane buildScrollPane(JPanel content) {
        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
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
        spinner.setEditor(new JSpinner.NumberEditor(spinner, pattern));
        JFormattedTextField textField = ((JSpinner.NumberEditor) spinner.getEditor()).getTextField();
        textField.setColumns(Math.max(5, decimals + 5));
    }

    private String formatSpinnerValue(JSpinner spinner) {
        Number value = (Number) spinner.getValue();
        int decimals = spinnerDecimalPlaces(spinner);
        if (decimals == 0) {
            return Long.toString(value.longValue());
        }
        return String.format("%." + decimals + "f", value.doubleValue());
    }

    private JLabel createSectionHeader(String title) {
        JLabel headerLabel = new JLabel(title);
        headerLabel.setFont(headerLabel.getFont().deriveFont(Font.BOLD, 16f));

        Color accentColor = UIManager.getColor("Component.accentColor");
        if (accentColor == null) {
            accentColor = Color.decode("#4285f4");
        }
        headerLabel.setForeground(accentColor);
        headerLabel.setBorder(new EmptyBorder(10, 0, 10, 0));
        headerLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return headerLabel;
    }

    private JSpinner addRow(JPanel parent, String title, String description, SpinnerModel model) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(new EmptyBorder(5, 0, 15, 0));

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel descLabel = new JLabel("<html>" + description + "</html>");
        descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 12f));
        descLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(3));
        textPanel.add(descLabel);

        // Increased height to 85px to safely accommodate multiple lines of description text
        Dimension textDim = new Dimension(480, 85);
        textPanel.setPreferredSize(textDim);
        textPanel.setMinimumSize(textDim);
        textPanel.setMaximumSize(textDim);

        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JSpinner spinner = new JSpinner(model);
        if (model instanceof SpinnerNumberModel) {
            configureSpinnerEditor(spinner);
            spinner.addChangeListener(e -> configureSpinnerEditor(spinner));
        }
        spinner.setPreferredSize(new Dimension(80, 26));
        inputWrapper.add(spinner);

        row.add(textPanel);
        row.add(Box.createHorizontalStrut(20));
        row.add(inputWrapper);
        row.add(Box.createHorizontalGlue());

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(row);

        return spinner;
    }

    private JCheckBox addCheckboxRow(JPanel parent, String title, String description, boolean defaultValue) {
        JPanel row = new JPanel();
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setBorder(new EmptyBorder(5, 0, 15, 0));

        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel descLabel = new JLabel("<html>" + description + "</html>");
        descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 12f));
        descLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(3));
        textPanel.add(descLabel);

        // Increased height to 85px to safely accommodate multiple lines of description text
        Dimension textDim = new Dimension(480, 85);
        textPanel.setPreferredSize(textDim);
        textPanel.setMinimumSize(textDim);
        textPanel.setMaximumSize(textDim);

        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JCheckBox checkBox = new JCheckBox();
        checkBox.setSelected(defaultValue);
        checkBox.setPreferredSize(new Dimension(80, 26));
        inputWrapper.add(checkBox);

        row.add(textPanel);
        row.add(Box.createHorizontalStrut(20));
        row.add(inputWrapper);
        row.add(Box.createHorizontalGlue());

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(row);

        return checkBox;
    }

    private void applySettingsToMemory() {
        try {
            commitAllSpinners();
            normalizeDependentSpinners();

            jTransientConfig.detectionSigmaMultiplier = ((Number) spinDetectionSigma.getValue()).doubleValue();
            jTransientConfig.growSigmaMultiplier = ((Number) spinGrowSigma.getValue()).doubleValue();
            jTransientConfig.minDetectionPixels = ((Number) spinMinPixels.getValue()).intValue();
            jTransientConfig.edgeMarginPixels = ((Number) spinEdgeMargin.getValue()).intValue();
            jTransientConfig.voidThresholdFraction = ((Number) spinVoidFraction.getValue()).doubleValue();
            jTransientConfig.voidProximityRadius = ((Number) spinVoidRadius.getValue()).intValue();
            jTransientConfig.enableSlowMoverDetection = chkEnableSlowMovers.isSelected();
            jTransientConfig.masterSigmaMultiplier = ((Number) spinMasterSigma.getValue()).doubleValue();
            jTransientConfig.masterMinDetectionPixels = ((Number) spinMasterMinPix.getValue()).intValue();
            jTransientConfig.masterSlowMoverSigmaMultiplier = ((Number) spinMasterSlowMoverSigma.getValue()).doubleValue();
            jTransientConfig.masterSlowMoverGrowSigmaMultiplier = ((Number) spinMasterSlowMoverGrowSigma.getValue()).doubleValue();
            jTransientConfig.slowMoverMinAxisRatio = ((Number) spinSlowMoverMinAxisRatio.getValue()).doubleValue();
            jTransientConfig.slowMoverMaxAxisRatio = ((Number) spinSlowMoverMaxAxisRatio.getValue()).doubleValue();
            jTransientConfig.slowMoverMinFillFactor = ((Number) spinSlowMoverMinFillFactor.getValue()).doubleValue();
            jTransientConfig.masterSlowMoverMinPixels = ((Number) spinMasterSlowMoverMinPixels.getValue()).intValue();
            jTransientConfig.slowMoverMedianSupportOverlapFraction = ((Number) spinSlowMoverMedianSupportOverlapFraction.getValue()).doubleValue();
            jTransientConfig.slowMoverMedianSupportMaxOverlapFraction = ((Number) spinSlowMoverMedianSupportMaxOverlapFraction.getValue()).doubleValue();
            jTransientConfig.slowMoverMinFrameSupport = ((Number) spinSlowMoverMinFrameSupport.getValue()).doubleValue();
            jTransientConfig.slowMoverMaxStationaryLikelihood = ((Number) spinSlowMoverMaxStationaryLikelihood.getValue()).doubleValue();
            jTransientConfig.streakMinElongation = ((Number) spinStreakMinElong.getValue()).doubleValue();
            jTransientConfig.streakMinPixels = ((Number) spinStreakMinPix.getValue()).intValue();
            jTransientConfig.singleStreakMinPeakSigma = ((Number) spinSingleStreakMinPeakSigma.getValue()).doubleValue();
            setOptionalDoubleField(jTransientConfig, "streakTimeConsistencyTolerance", ((Number) spinStreakTimeConsistencyTolerance.getValue()).doubleValue());
            jTransientConfig.bgClippingIterations = ((Number) spinBgClippingIters.getValue()).intValue();
            jTransientConfig.bgClippingFactor = ((Number) spinBgClippingFactor.getValue()).doubleValue();

            // --- Apply Tracker Settings to the POJO ---
            jTransientConfig.maxStarJitter = ((Number) spinStarJitter.getValue()).doubleValue();
            jTransientConfig.maxMaskOverlapFraction = ((Number) spinMaxMaskOverlapFraction.getValue()).doubleValue();
            jTransientConfig.predictionTolerance = ((Number) spinPredTol.getValue()).doubleValue();
            jTransientConfig.angleToleranceDegrees = ((Number) spinAngleTol.getValue()).doubleValue();
            jTransientConfig.maxJumpPixels = ((Number) spinMaxJump.getValue()).doubleValue();
            jTransientConfig.strictExposureKinematics = chkStrictExposureKinematics.isSelected();
            setOptionalBooleanField(jTransientConfig, "enableGeometricTrackLinking", chkEnableGeometricTrackLinking.isSelected());
            jTransientConfig.maxFwhmRatio = ((Number) spinMaxFwhmRatio.getValue()).doubleValue();
            jTransientConfig.maxSurfaceBrightnessRatio = ((Number) spinMaxSurfaceBrightnessRatio.getValue()).doubleValue();
            jTransientConfig.trackMinFrameRatio = ((Number) spinTrackMinFrameRatio.getValue()).doubleValue();
            jTransientConfig.absoluteMaxPointsRequired = ((Number) spinAbsMaxPoints.getValue()).intValue();
            jTransientConfig.rhythmAllowedVariance = ((Number) spinRhythmVar.getValue()).doubleValue();
            jTransientConfig.rhythmMinConsistencyRatio = ((Number) spinRhythmMinRatio.getValue()).doubleValue();
            jTransientConfig.rhythmStationaryThreshold = ((Number) spinRhythmStatThresh.getValue()).doubleValue();
            jTransientConfig.timeBasedVelocityTolerance = ((Number) spinTimeBasedVelocityTolerance.getValue()).doubleValue();

            jTransientConfig.enableAnomalyRescue = chkEnableAnomalyRescue.isSelected();
            jTransientConfig.anomalyMinPeakSigma = ((Number) spinAnomalyMinPeakSigma.getValue()).doubleValue();
            jTransientConfig.anomalyMinPixels = ((Number) spinAnomalyMinPixels.getValue()).intValue();
            jTransientConfig.anomalyMinIntegratedSigma = ((Number) spinAnomalyMinIntegratedSigma.getValue()).doubleValue();
            jTransientConfig.anomalyMinIntegratedPixels = ((Number) spinAnomalyMinIntegratedPixels.getValue()).intValue();
            jTransientConfig.anomalyMinPeakSigmaFloor = ((Number) spinAnomalyMinPeakSigmaFloor.getValue()).doubleValue();
            setOptionalDoubleField(jTransientConfig, "suspectedStreakLineTolerance", ((Number) spinSuspectedStreakLineTolerance.getValue()).doubleValue());
            setOptionalDoubleField(jTransientConfig, "anomalySuspectedStreakMinElongation", ((Number) spinAnomalySuspectedStreakMinElongation.getValue()).doubleValue());
            setOptionalBooleanField(jTransientConfig, "enableBinaryStarLikeStreakShapeVeto", chkEnableBinaryStarLikeStreakShapeVeto.isSelected());
            setOptionalBooleanField(jTransientConfig, "enableResidualTransientAnalysis", chkEnableResidualTransientAnalysis.isSelected());
            setOptionalBooleanField(jTransientConfig, "enableLocalRescueCandidates", chkEnableLocalRescueCandidates.isSelected());
            setOptionalBooleanField(jTransientConfig, "enableLocalActivityClusters", chkEnableLocalActivityClusters.isSelected());
            setOptionalDoubleField(jTransientConfig, "localActivityClusterRadiusPixels", ((Number) spinLocalActivityClusterRadiusPixels.getValue()).doubleValue());
            setOptionalIntField(jTransientConfig, "localActivityClusterMinFrames", ((Number) spinLocalActivityClusterMinFrames.getValue()).intValue());

            autoTuneMaxCandidateFrames = ((Number) spinAutoTuneMaxCandidateFrames.getValue()).intValue();
            SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);

            jTransientConfig.minFramesForAnalysis = ((Number) spinMinFramesAnalysis.getValue()).intValue();
            jTransientConfig.starCountSigmaDeviation = ((Number) spinStarCountSigma.getValue()).doubleValue();
            jTransientConfig.fwhmSigmaDeviation = ((Number) spinFwhmSigma.getValue()).doubleValue();
            jTransientConfig.eccentricitySigmaDeviation = ((Number) spinEccentricitySigma.getValue()).doubleValue();
            jTransientConfig.enableBrightStarEccentricityFilter = chkEnableBrightStarEccentricityFilter.isSelected();
            jTransientConfig.brightStarEccentricitySigmaDeviation = ((Number) spinBrightStarEccentricitySigma.getValue()).doubleValue();
            jTransientConfig.backgroundSigmaDeviation = ((Number) spinBackgroundSigma.getValue()).doubleValue();

            jTransientConfig.minBackgroundDeviationADU = ((Number) spinMinBgDevAdu.getValue()).doubleValue();
            jTransientConfig.minEccentricityEnvelope = ((Number) spinMinEccEnvelope.getValue()).doubleValue();
            jTransientConfig.minBrightStarEccentricityEnvelope = ((Number) spinMinBrightStarEccEnvelope.getValue()).doubleValue();
            jTransientConfig.minFwhmEnvelope = ((Number) spinMinFwhmEnvelope.getValue()).doubleValue();

            jTransientConfig.qualitySigmaMultiplier = ((Number) spinQualitySigma.getValue()).doubleValue();
            jTransientConfig.qualityGrowSigmaMultiplier = ((Number) spinQualityGrowSigma.getValue()).doubleValue();
            jTransientConfig.qualityMinDetectionPixels = ((Number) spinQualityMinPix.getValue()).intValue();
            jTransientConfig.qualityBrightStarPeakSigmaOffset = ((Number) spinQualityBrightStarPeakSigmaOffset.getValue()).doubleValue();
            jTransientConfig.qualityBrightStarMinStars = ((Number) spinQualityBrightStarMinStars.getValue()).intValue();
            jTransientConfig.qualityMaxElongationForFwhm = ((Number) spinMaxElongFwhm.getValue()).doubleValue();

            jTransientConfig.enableVariableStarDetection = chkEnableVariableStarDetection.isSelected();
            jTransientConfig.photometryMaxStars = ((Number) spinPhotometryMaxStars.getValue()).intValue();
            jTransientConfig.photometryMinSnr = ((Number) spinPhotometryMinSnr.getValue()).doubleValue();
            jTransientConfig.photometryMaxElongation = ((Number) spinPhotometryMaxElongation.getValue()).doubleValue();
            jTransientConfig.photometryApertureFwhmFactor = ((Number) spinPhotometryApertureFwhmFactor.getValue()).doubleValue();
            jTransientConfig.photometryAnnulusInnerFwhmFactor = ((Number) spinPhotometryAnnulusInnerFwhmFactor.getValue()).doubleValue();
            jTransientConfig.photometryAnnulusOuterFwhmFactor = ((Number) spinPhotometryAnnulusOuterFwhmFactor.getValue()).doubleValue();
            jTransientConfig.photometrySaturationFraction = ((Number) spinPhotometrySaturationFraction.getValue()).doubleValue();
            jTransientConfig.photometryFitPlane = chkPhotometryFitPlane.isSelected();
            jTransientConfig.photometryMaxRegistrationSpreadPixels = ((Number) spinPhotometryMaxRegistrationSpreadPixels.getValue()).doubleValue();
            jTransientConfig.linearityMinDistinctLevels = ((Number) spinLinearityMinDistinctLevels.getValue()).intValue();
            jTransientConfig.linearityMaxFloorClippedFraction = ((Number) spinLinearityMaxFloorClippedFraction.getValue()).doubleValue();
            jTransientConfig.linearityMaxConcentrationDrift = ((Number) spinLinearityMaxConcentrationDrift.getValue()).doubleValue();
            jTransientConfig.linearityMinRangeMag = ((Number) spinLinearityMinRangeMag.getValue()).doubleValue();
            jTransientConfig.linearityMinStars = ((Number) spinLinearityMinStars.getValue()).intValue();
            jTransientConfig.linearityMaxFrameSlope = ((Number) spinLinearityMaxFrameSlope.getValue()).doubleValue();
            jTransientConfig.linearityMinZeroPointRangeMag = ((Number) spinLinearityMinZeroPointRangeMag.getValue()).doubleValue();
            jTransientConfig.linearityMaxSlopeTrackingCorrelation = ((Number) spinLinearityMaxSlopeTrackingCorrelation.getValue()).doubleValue();
            jTransientConfig.linearityMaxFailingFrameFraction = ((Number) spinLinearityMaxFailingFrameFraction.getValue()).doubleValue();
            jTransientConfig.variableMinFrames = ((Number) spinVariableMinFrames.getValue()).intValue();
            jTransientConfig.variableMinSpanMinutes = ((Number) spinVariableMinSpanMinutes.getValue()).doubleValue();
            jTransientConfig.variableNoiseModelNeighbors = ((Number) spinVariableNoiseModelNeighbors.getValue()).intValue();
            jTransientConfig.variableScoreSigma = ((Number) spinVariableScoreSigma.getValue()).doubleValue();
            jTransientConfig.variableMinAmplitudeMag = ((Number) spinVariableMinAmplitudeMag.getValue()).doubleValue();
            jTransientConfig.variableLimitedMinAmplitudeMag = ((Number) spinVariableLimitedMinAmplitudeMag.getValue()).doubleValue();
            jTransientConfig.variableAmplitudeNoiseFactor = ((Number) spinVariableAmplitudeNoiseFactor.getValue()).doubleValue();
            jTransientConfig.variableMinPersistenceFrames = ((Number) spinVariableMinPersistenceFrames.getValue()).intValue();
            jTransientConfig.variableMinSplitHalfCorrelation = ((Number) spinVariableMinSplitHalfCorrelation.getValue()).doubleValue();
            jTransientConfig.variableMaxApertureAmplitudeDifference = ((Number) spinVariableMaxApertureAmplitudeDifference.getValue()).doubleValue();
            jTransientConfig.variableMaxSystematicsCorrelation = ((Number) spinVariableMaxSystematicsCorrelation.getValue()).doubleValue();
            jTransientConfig.variableSystematicsResponseFactor = ((Number) spinVariableSystematicsResponseFactor.getValue()).doubleValue();
            jTransientConfig.variableLocalRadiusPixels = ((Number) spinVariableLocalRadiusPixels.getValue()).doubleValue();
            jTransientConfig.variableMaxLocalCorrelation = ((Number) spinVariableMaxLocalCorrelation.getValue()).doubleValue();

            RawImageAnnotator.streakLineScaleFactor = ((Number) spinStreakScale.getValue()).doubleValue();
            RawImageAnnotator.streakCentroidBoxRadius = ((Number) spinStreakCentroidRad.getValue()).intValue();
            RawImageAnnotator.pointSourceMinBoxRadius = ((Number) spinPointBoxRad.getValue()).intValue();
            RawImageAnnotator.dynamicBoxPadding = ((Number) spinBoxPad.getValue()).intValue();
            DisplayImageRenderer.autoStretchBlackSigma = ((Number) spinAutoBlackSigma.getValue()).doubleValue();
            DisplayImageRenderer.autoStretchWhiteSigma = ((Number) spinAutoWhiteSigma.getValue()).doubleValue();
            DetectionReportGenerator.gifBlinkSpeedMs = ((Number) spinGifBlinkSpeed.getValue()).intValue();
            DetectionReportGenerator.trackCropPadding = ((Number) spinCropPadding.getValue()).intValue();
            DetectionReportGenerator.includeAiCreativeReportSections = chkIncludeAiCreativeReportSections != null && chkIncludeAiCreativeReportSections.isSelected();

        } catch (Exception ex) {
            System.err.println("Error applying settings to memory: " + ex.getMessage());
        }
    }

    private void normalizeDependentSpinners() {
        double detectionSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
        double masterSigma = ((Number) spinMasterSigma.getValue()).doubleValue();
        if (detectionSigma < masterSigma) {
            spinDetectionSigma.setValue(masterSigma);
            detectionSigma = masterSigma;
        }

        double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
        if (growSigma < masterSigma) {
            spinGrowSigma.setValue(masterSigma);
        } else if (growSigma > detectionSigma) {
            spinGrowSigma.setValue(detectionSigma);
        }

        int minPixels = ((Number) spinMinPixels.getValue()).intValue();
        int masterMinPixels = ((Number) spinMasterMinPix.getValue()).intValue();
        if (minPixels < masterMinPixels) {
            spinMinPixels.setValue(masterMinPixels);
        }

        double slowMoverSigma = ((Number) spinMasterSlowMoverSigma.getValue()).doubleValue();
        double slowMoverGrowSigma = ((Number) spinMasterSlowMoverGrowSigma.getValue()).doubleValue();
        if (slowMoverGrowSigma > slowMoverSigma) {
            spinMasterSlowMoverGrowSigma.setValue(slowMoverSigma);
        }
        double minAxisRatio = ((Number) spinSlowMoverMinAxisRatio.getValue()).doubleValue();
        if (((Number) spinSlowMoverMaxAxisRatio.getValue()).doubleValue() < minAxisRatio) {
            spinSlowMoverMaxAxisRatio.setValue(minAxisRatio);
        }

        double minSupportOverlap = ((Number) spinSlowMoverMedianSupportOverlapFraction.getValue()).doubleValue();
        double maxSupportOverlap = ((Number) spinSlowMoverMedianSupportMaxOverlapFraction.getValue()).doubleValue();
        if (minSupportOverlap > maxSupportOverlap) {
            spinSlowMoverMedianSupportMaxOverlapFraction.setValue(minSupportOverlap);
        }

        double maxJump = ((Number) spinMaxJump.getValue()).doubleValue();
        double stationaryThreshold = ((Number) spinRhythmStatThresh.getValue()).doubleValue();
        if (stationaryThreshold > maxJump) {
            spinRhythmStatThresh.setValue(maxJump);
        }

        double annulusInner = ((Number) spinPhotometryAnnulusInnerFwhmFactor.getValue()).doubleValue();
        double annulusOuter = ((Number) spinPhotometryAnnulusOuterFwhmFactor.getValue()).doubleValue();
        if (annulusOuter <= annulusInner) {
            setSpinnerValueClamped(spinPhotometryAnnulusOuterFwhmFactor, annulusInner + 0.5);
        }
    }

    private void commitAllSpinners() {
        Component[] tabs = ((JTabbedPane) getComponent(0)).getComponents();
        for (Component tab : tabs) {
            if (tab instanceof JScrollPane) {
                JPanel viewport = (JPanel) ((JScrollPane) tab).getViewport().getView();
                for (Component c : viewport.getComponents()) {
                    if (c instanceof JPanel) {
                        JPanel rowPanel = (JPanel) c;
                        for (Component wrapper : rowPanel.getComponents()) {
                            if (wrapper instanceof JPanel) {
                                for (Component spinner : ((JPanel) wrapper).getComponents()) {
                                    if (spinner instanceof JSpinner) {
                                        try {
                                            ((JSpinner) spinner).commitEdit();
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    @Subscribe
    public void onImportFinished(FitsImportFinishedEvent event) {
        EventQueue.invokeLater(() -> {

            if (event.isSuccess()) {
                FitsFileInformation[] filesInfo = event.getFilesInformation();
                if (filesInfo == null || filesInfo.length == 0) {
                    previewBtn.setEnabled(false);
                    autoTuneBtn.setEnabled(false);
                    autoTuneProfileCombo.setEnabled(false);
                    return;
                }

                boolean existsColor = false;
                for (FitsFileInformation fitsFile : filesInfo) {
                    if (!fitsFile.isMonochrome()) {
                        existsColor = true;
                        break;
                    }
                }

                if (!existsColor) {
                    previewBtn.setEnabled(true);

                    if (filesInfo.length >= SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES) {
                        autoTuneBtn.setEnabled(true);
                        autoTuneProfileCombo.setEnabled(true);
                    } else {
                        autoTuneBtn.setEnabled(false);
                        autoTuneProfileCombo.setEnabled(false);
                    }
                } else {
                    previewBtn.setEnabled(false);
                    autoTuneBtn.setEnabled(false);
                    autoTuneProfileCombo.setEnabled(false);
                }
            }
        });
    }

    @Subscribe
    public void onAutoTuneStarted(AutoTuneStartedEvent event) {
        EventQueue.invokeLater(() -> {
            autoTuneBtn.setEnabled(false);
            autoTuneBtn.setText("Tuning... Please Wait");
            autoTuneProfileCombo.setEnabled(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        });
    }

    @Subscribe
    public void onAutoTuneFinished(eu.startales.spacepixels.events.AutoTuneFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            autoTuneBtn.setEnabled(true);
            autoTuneBtn.setText("Auto-Tune Settings");
            autoTuneProfileCombo.setEnabled(true);
            setCursor(Cursor.getDefaultCursor());

            if (event.getResult() != null && event.getResult().telemetryReport != null) {
                System.out.println(event.getResult().telemetryReport);
            }
            if (event.isSuccess() && event.getResult() != null) {
                JTransientAutoTuner.AutoTunerResult result = event.getResult();

                if (result.success) {
                    updateSpinnersFromConfig(result.optimizedConfig);
                    // The tuner leaves slow-mover sigmas untouched; mirror the tuned basic sigmas into them.
                    syncSlowMoverSigmasFromBasicTuning();

                    DetectionConfig appliedConfig = getJTransientConfig();

                    boolean detectionSigmaAdjusted = Math.abs(appliedConfig.detectionSigmaMultiplier - result.optimizedConfig.detectionSigmaMultiplier) > 1e-9;
                    boolean growSigmaAdjusted = Math.abs(appliedConfig.growSigmaMultiplier - result.optimizedConfig.growSigmaMultiplier) > 1e-9;

                    String detectionMsg = detectionSigmaAdjusted
                            ? String.format("• Detection Sigma: %s (raised to respect Master Sigma)", formatSpinnerValue(spinDetectionSigma))
                            : String.format("• Detection Sigma: %s", formatSpinnerValue(spinDetectionSigma));

                    String growMsg;
                    if (growSigmaAdjusted) {
                        String growAdjustmentReason = appliedConfig.growSigmaMultiplier > result.optimizedConfig.growSigmaMultiplier
                                ? "raised to Master Sigma"
                                : "capped to Detection Sigma";
                        growMsg = String.format("• Grow Sigma: %s (%s)", formatSpinnerValue(spinGrowSigma), growAdjustmentReason);
                    } else {
                        growMsg = String.format("• Grow Sigma: %s", formatSpinnerValue(spinGrowSigma));
                    }

                    String summary = String.format(
                            "Auto-Tuning Complete!\n\n" +
                                    "Winning Settings Found:\n" +
                                    "%s\n" +
                                    "%s\n" +
                                    "• Min Pixels: %d\n" +
                                    "• Max Star Jitter: %s px\n" +
                                    "• Max Mask Overlap Fraction: %s\n" +
                                    "• Slow-Mover Sigma / Grow Sigma: %s / %s (copied from Detection / Grow Sigma)\n\n" +
                                    "Telemetry: Detected %d stable stars with a %.1f%% noise ratio.\n\n" +
                                    "Would you like to view the detailed mathematical evaluation report?",
                            detectionMsg,
                            growMsg,
                            appliedConfig.minDetectionPixels,
                            formatSpinnerValue(spinStarJitter),
                            formatSpinnerValue(spinMaxMaskOverlapFraction),
                            formatSpinnerValue(spinMasterSlowMoverSigma),
                            formatSpinnerValue(spinMasterSlowMoverGrowSigma),
                            result.bestStarCount,
                            (result.bestTransientRatio * 100)
                    );

                    int choice = JOptionPane.showConfirmDialog(DetectionConfigurationPanel.this, summary, "Auto-Tuner Success", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        showTelemetryReportWindow(result.telemetryReport);
                    }
                } else {
                    JOptionPane.showMessageDialog(DetectionConfigurationPanel.this,
                            "The Auto-Tuner could not find a stable star field that meets the strict noise limits.\n" +
                                    "This usually happens if the images are too noisy, heavily clouded, or not aligned.\n\n" +
                                    "Falling back to your current manual settings.",
                            "Auto-Tuner Failed", JOptionPane.WARNING_MESSAGE);
                }
            } else {
                JOptionPane.showMessageDialog(DetectionConfigurationPanel.this, "Auto-Tuning encountered a fatal error: " + event.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Helper method to physically move the UI sliders to match a provided config.
     */
    private void updateSpinnersFromConfig(DetectionConfig config) {
        setSpinnerValueClamped(spinMasterSigma, config.masterSigmaMultiplier);
        setSpinnerValueClamped(spinDetectionSigma, config.detectionSigmaMultiplier);
        setSpinnerValueClamped(spinGrowSigma, config.growSigmaMultiplier);
        setSpinnerValueClamped(spinMasterMinPix, config.masterMinDetectionPixels);
        setSpinnerValueClamped(spinMinPixels, config.minDetectionPixels);
        setSpinnerValueClamped(spinEdgeMargin, config.edgeMarginPixels);
        setSpinnerValueClamped(spinVoidFraction, config.voidThresholdFraction);
        setSpinnerValueClamped(spinVoidRadius, config.voidProximityRadius);
        chkEnableSlowMovers.setSelected(config.enableSlowMoverDetection);

        setSpinnerValueClamped(spinMasterSlowMoverSigma, config.masterSlowMoverSigmaMultiplier);
        setSpinnerValueClamped(spinMasterSlowMoverGrowSigma, config.masterSlowMoverGrowSigmaMultiplier);
        setSpinnerValueClamped(spinSlowMoverMinAxisRatio, config.slowMoverMinAxisRatio);
        setSpinnerValueClamped(spinSlowMoverMaxAxisRatio, config.slowMoverMaxAxisRatio);
        setSpinnerValueClamped(spinSlowMoverMinFillFactor, config.slowMoverMinFillFactor);
        setSpinnerValueClamped(spinMasterSlowMoverMinPixels, config.masterSlowMoverMinPixels);
        setSpinnerValueClamped(spinSlowMoverMedianSupportOverlapFraction, config.slowMoverMedianSupportOverlapFraction);
        setSpinnerValueClamped(spinSlowMoverMedianSupportMaxOverlapFraction, config.slowMoverMedianSupportMaxOverlapFraction);
        setSpinnerValueClamped(spinSlowMoverMinFrameSupport, config.slowMoverMinFrameSupport);
        setSpinnerValueClamped(spinSlowMoverMaxStationaryLikelihood, config.slowMoverMaxStationaryLikelihood);

        chkStrictExposureKinematics.setSelected(config.strictExposureKinematics);
        chkEnableGeometricTrackLinking.setSelected(getOptionalBooleanField(config, "enableGeometricTrackLinking", true));
        setSpinnerValueClamped(spinStarJitter, config.maxStarJitter);
        setSpinnerValueClamped(spinMaxMaskOverlapFraction, config.maxMaskOverlapFraction);
        setSpinnerValueClamped(spinPredTol, config.predictionTolerance);
        setSpinnerValueClamped(spinAngleTol, config.angleToleranceDegrees);
        setSpinnerValueClamped(spinMaxJump, config.maxJumpPixels);
        setSpinnerValueClamped(spinMaxFwhmRatio, config.maxFwhmRatio);
        setSpinnerValueClamped(spinMaxSurfaceBrightnessRatio, config.maxSurfaceBrightnessRatio);
        setSpinnerValueClamped(spinTrackMinFrameRatio, config.trackMinFrameRatio);
        setSpinnerValueClamped(spinAbsMaxPoints, config.absoluteMaxPointsRequired);
        setSpinnerValueClamped(spinRhythmVar, config.rhythmAllowedVariance);
        setSpinnerValueClamped(spinRhythmMinRatio, config.rhythmMinConsistencyRatio);
        setSpinnerValueClamped(spinRhythmStatThresh, config.rhythmStationaryThreshold);
        setSpinnerValueClamped(spinTimeBasedVelocityTolerance, config.timeBasedVelocityTolerance);

        setSpinnerValueClamped(spinStreakMinElong, config.streakMinElongation);
        setSpinnerValueClamped(spinStreakMinPix, config.streakMinPixels);
        setSpinnerValueClamped(spinSingleStreakMinPeakSigma, config.singleStreakMinPeakSigma);
        setSpinnerValueClamped(spinStreakTimeConsistencyTolerance, getOptionalDoubleField(config, "streakTimeConsistencyTolerance", 0.50));
        chkEnableBinaryStarLikeStreakShapeVeto.setSelected(getOptionalBooleanField(config, "enableBinaryStarLikeStreakShapeVeto", true));
        setSpinnerValueClamped(spinBgClippingIters, config.bgClippingIterations);
        setSpinnerValueClamped(spinBgClippingFactor, config.bgClippingFactor);

        chkEnableAnomalyRescue.setSelected(config.enableAnomalyRescue);
        setSpinnerValueClamped(spinAnomalyMinPeakSigma, config.anomalyMinPeakSigma);
        setSpinnerValueClamped(spinAnomalyMinPixels, config.anomalyMinPixels);
        setSpinnerValueClamped(spinAnomalyMinIntegratedSigma, config.anomalyMinIntegratedSigma);
        setSpinnerValueClamped(spinAnomalyMinIntegratedPixels, config.anomalyMinIntegratedPixels);
        setSpinnerValueClamped(spinAnomalyMinPeakSigmaFloor, config.anomalyMinPeakSigmaFloor);
        setSpinnerValueClamped(spinSuspectedStreakLineTolerance, getOptionalDoubleField(config, "suspectedStreakLineTolerance", 6.0));
        setSpinnerValueClamped(spinAnomalySuspectedStreakMinElongation, getOptionalDoubleField(config, "anomalySuspectedStreakMinElongation", 3.5));
        chkEnableResidualTransientAnalysis.setSelected(getOptionalBooleanField(config, "enableResidualTransientAnalysis", true));
        chkEnableLocalRescueCandidates.setSelected(getOptionalBooleanField(config, "enableLocalRescueCandidates", true));
        chkEnableLocalActivityClusters.setSelected(getOptionalBooleanField(config, "enableLocalActivityClusters", true));
        setSpinnerValueClamped(spinLocalActivityClusterRadiusPixels, getOptionalDoubleField(config, "localActivityClusterRadiusPixels", 10.0));
        setSpinnerValueClamped(spinLocalActivityClusterMinFrames, getOptionalIntField(config, "localActivityClusterMinFrames", 3));

        setSpinnerValueClamped(spinMinFramesAnalysis, config.minFramesForAnalysis);
        setSpinnerValueClamped(spinStarCountSigma, config.starCountSigmaDeviation);
        setSpinnerValueClamped(spinFwhmSigma, config.fwhmSigmaDeviation);
        setSpinnerValueClamped(spinEccentricitySigma, config.eccentricitySigmaDeviation);
        chkEnableBrightStarEccentricityFilter.setSelected(config.enableBrightStarEccentricityFilter);
        setSpinnerValueClamped(spinBrightStarEccentricitySigma, config.brightStarEccentricitySigmaDeviation);
        setSpinnerValueClamped(spinBackgroundSigma, config.backgroundSigmaDeviation);
        setSpinnerValueClamped(spinMinBgDevAdu, config.minBackgroundDeviationADU);
        setSpinnerValueClamped(spinMinEccEnvelope, config.minEccentricityEnvelope);
        setSpinnerValueClamped(spinMinBrightStarEccEnvelope, config.minBrightStarEccentricityEnvelope);
        setSpinnerValueClamped(spinMinFwhmEnvelope, config.minFwhmEnvelope);
        setSpinnerValueClamped(spinQualitySigma, config.qualitySigmaMultiplier);
        setSpinnerValueClamped(spinQualityGrowSigma, config.qualityGrowSigmaMultiplier);
        setSpinnerValueClamped(spinQualityMinPix, config.qualityMinDetectionPixels);
        setSpinnerValueClamped(spinQualityBrightStarPeakSigmaOffset, config.qualityBrightStarPeakSigmaOffset);
        setSpinnerValueClamped(spinQualityBrightStarMinStars, config.qualityBrightStarMinStars);
        setSpinnerValueClamped(spinMaxElongFwhm, config.qualityMaxElongationForFwhm);

        chkEnableVariableStarDetection.setSelected(config.enableVariableStarDetection);
        setSpinnerValueClamped(spinPhotometryMaxStars, config.photometryMaxStars);
        setSpinnerValueClamped(spinPhotometryMinSnr, config.photometryMinSnr);
        setSpinnerValueClamped(spinPhotometryMaxElongation, config.photometryMaxElongation);
        setSpinnerValueClamped(spinPhotometryApertureFwhmFactor, config.photometryApertureFwhmFactor);
        setSpinnerValueClamped(spinPhotometryAnnulusInnerFwhmFactor, config.photometryAnnulusInnerFwhmFactor);
        setSpinnerValueClamped(spinPhotometryAnnulusOuterFwhmFactor, config.photometryAnnulusOuterFwhmFactor);
        setSpinnerValueClamped(spinPhotometrySaturationFraction, config.photometrySaturationFraction);
        chkPhotometryFitPlane.setSelected(config.photometryFitPlane);
        setSpinnerValueClamped(spinPhotometryMaxRegistrationSpreadPixels, config.photometryMaxRegistrationSpreadPixels);
        setSpinnerValueClamped(spinLinearityMinDistinctLevels, config.linearityMinDistinctLevels);
        setSpinnerValueClamped(spinLinearityMaxFloorClippedFraction, config.linearityMaxFloorClippedFraction);
        setSpinnerValueClamped(spinLinearityMaxConcentrationDrift, config.linearityMaxConcentrationDrift);
        setSpinnerValueClamped(spinLinearityMinRangeMag, config.linearityMinRangeMag);
        setSpinnerValueClamped(spinLinearityMinStars, config.linearityMinStars);
        setSpinnerValueClamped(spinLinearityMaxFrameSlope, config.linearityMaxFrameSlope);
        setSpinnerValueClamped(spinLinearityMinZeroPointRangeMag, config.linearityMinZeroPointRangeMag);
        setSpinnerValueClamped(spinLinearityMaxSlopeTrackingCorrelation, config.linearityMaxSlopeTrackingCorrelation);
        setSpinnerValueClamped(spinLinearityMaxFailingFrameFraction, config.linearityMaxFailingFrameFraction);
        setSpinnerValueClamped(spinVariableMinFrames, config.variableMinFrames);
        setSpinnerValueClamped(spinVariableMinSpanMinutes, config.variableMinSpanMinutes);
        setSpinnerValueClamped(spinVariableNoiseModelNeighbors, config.variableNoiseModelNeighbors);
        setSpinnerValueClamped(spinVariableScoreSigma, config.variableScoreSigma);
        setSpinnerValueClamped(spinVariableMinAmplitudeMag, config.variableMinAmplitudeMag);
        setSpinnerValueClamped(spinVariableLimitedMinAmplitudeMag, config.variableLimitedMinAmplitudeMag);
        setSpinnerValueClamped(spinVariableAmplitudeNoiseFactor, config.variableAmplitudeNoiseFactor);
        setSpinnerValueClamped(spinVariableMinPersistenceFrames, config.variableMinPersistenceFrames);
        setSpinnerValueClamped(spinVariableMinSplitHalfCorrelation, config.variableMinSplitHalfCorrelation);
        setSpinnerValueClamped(spinVariableMaxApertureAmplitudeDifference, config.variableMaxApertureAmplitudeDifference);
        setSpinnerValueClamped(spinVariableMaxSystematicsCorrelation, config.variableMaxSystematicsCorrelation);
        setSpinnerValueClamped(spinVariableSystematicsResponseFactor, config.variableSystematicsResponseFactor);
        setSpinnerValueClamped(spinVariableLocalRadiusPixels, config.variableLocalRadiusPixels);
        setSpinnerValueClamped(spinVariableMaxLocalCorrelation, config.variableMaxLocalCorrelation);

        // Push the visual changes to the underlying memory state immediately
        applySettingsToMemory();
    }

    private boolean getOptionalBooleanField(DetectionConfig config, String fieldName, boolean defaultValue) {
        try {
            Field field = DetectionConfig.class.getField(fieldName);
            return field.getBoolean(config);
        } catch (ReflectiveOperationException ignored) {
            return defaultValue;
        }
    }

    private double getOptionalDoubleField(DetectionConfig config, String fieldName, double defaultValue) {
        try {
            Field field = DetectionConfig.class.getField(fieldName);
            return field.getDouble(config);
        } catch (ReflectiveOperationException ignored) {
            return defaultValue;
        }
    }

    private int getOptionalIntField(DetectionConfig config, String fieldName, int defaultValue) {
        try {
            Field field = DetectionConfig.class.getField(fieldName);
            return field.getInt(config);
        } catch (ReflectiveOperationException ignored) {
            return defaultValue;
        }
    }

    private void setOptionalBooleanField(DetectionConfig config, String fieldName, boolean value) {
        try {
            Field field = DetectionConfig.class.getField(fieldName);
            field.setBoolean(config, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void setOptionalDoubleField(DetectionConfig config, String fieldName, double value) {
        try {
            Field field = DetectionConfig.class.getField(fieldName);
            field.setDouble(config, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void setOptionalIntField(DetectionConfig config, String fieldName, int value) {
        try {
            Field field = DetectionConfig.class.getField(fieldName);
            field.setInt(config, value);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private void showTelemetryReportWindow(String reportText) {
        JTextArea textArea = new JTextArea(reportText);
        textArea.setEditable(false);
        textArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        textArea.setMargin(new java.awt.Insets(10, 10, 10, 10));

        JScrollPane scrollPane = new JScrollPane(textArea);
        scrollPane.setPreferredSize(new Dimension(800, 600));

        JDialog dialog = new JDialog(mainAppWindow.getFrame(), "Auto-Tuner Telemetry Report", true);
        dialog.getContentPane().add(scrollPane);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }
}
