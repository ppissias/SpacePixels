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
    private JCheckBox chkEnableSlowMovers, chkEnableSlowMoverShapeFiltering, chkEnableSlowMoverSpecificShapeFiltering, chkEnableSlowMoverResidualCoreFiltering, chkEnableBinaryStarLikeStreakShapeVeto;
    private JSpinner spinMasterSigma, spinMasterMinPix, spinMasterSlowMoverMinPixels, spinMasterSlowMoverSigma, spinMasterSlowMoverGrowSigma, spinSlowMoverBaselineMadMultiplier, spinSlowMoverStackMiddleFraction;
    private JSpinner spinSlowMoverMedianSupportOverlapFraction, spinSlowMoverMedianSupportMaxOverlapFraction, spinSlowMoverResidualCoreRadiusPixels, spinSlowMoverResidualCoreMinPositiveFraction;
    private JSpinner spinStreakMinElong, spinStreakMinPix, spinSingleStreakMinPeakSigma;
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
    private JSpinner spinMinBgDevAdu, spinMinEccEnvelope, spinMinFwhmEnvelope;

    private JSpinner spinQualitySigma, spinQualityGrowSigma, spinQualityMinPix, spinMaxElongFwhm;

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
        tabbedPane.addTab("Source Extraction", buildScrollPane(buildSourceExtractionPanel()));
        tabbedPane.addTab("Streak Detection", buildScrollPane(buildStreakDetectionPanel()));
        tabbedPane.addTab("Moving Objects", buildScrollPane(buildMovingObjectsPanel()));
        tabbedPane.addTab("Anomaly Detection", buildScrollPane(buildAnomalyDetectionPanel()));
        tabbedPane.addTab("Slow Movers", buildScrollPane(buildSlowMoversPanel()));
        tabbedPane.addTab("Residual Analysis", buildScrollPane(buildResidualAnalysisPanel()));
        tabbedPane.addTab("Quality Control", buildScrollPane(buildQualityPanel()));
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

        previewBtn.setToolTipText("Run extraction on the selected frame with current settings and show the exact pixel mask.");
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
                return;
            } catch (Exception e) {
                System.err.println("Failed to load detection profile, falling back to defaults: " + e.getMessage());
            }
        }
        jTransientConfig = new DetectionConfig();
        autoTuneMaxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;
        SpacePixelsDetectionProfileIO.setActiveAutoTuneMaxCandidateFrames(autoTuneMaxCandidateFrames);
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
                "Make sure to run the Auto-Tuner by selecting a Tuning profile and clicking the Auto-Tune Settings button. Start here with the three core extractor controls. Then move into the category tabs for streaks, moving objects, anomalies, slow movers, and source-extraction safeguards. " +
                "If you detect too many transients and false positives, increase Detection Sigma, Grow Sigma, and Min Detection Pixels. " +
                "</div></html>");
        basicIntroLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(basicIntroLabel);

        panel.add(createSectionHeader("Core Source Extraction"));
        spinDetectionSigma = addRow(panel, "Detection Sigma Multiplier", "Minimum brightness threshold for starting a new detection. Higher values reduce noise; lower values detect fainter objects.", doubleSpinnerModel(jTransientConfig.detectionSigmaMultiplier, 1.0, 20.0, 0.1));
        spinGrowSigma = addRow(panel, "Grow Sigma (Hysteresis)", "Secondary threshold used to expand a detection after it starts during per-frame extraction. Lower values capture fainter edges; higher values keep detections tighter. For stable veto-mask behavior this should not go below Master Sigma, and the UI enforces that. The master veto map itself internally uses the master sigma for both seed and grow thresholds.", doubleSpinnerModel(jTransientConfig.growSigmaMultiplier, 0.1, 20.0, 0.1));
        spinMinPixels = addRow(panel, "Min Detection Pixels", "Minimum blob size required for a detection to be kept. Higher values reject hot pixels and noise; lower values allow smaller sources.", intSpinnerModel(jTransientConfig.minDetectionPixels, 1, 2000, 1));

        return panel;
    }

    private JPanel buildSourceExtractionPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Controls for the master veto map and low-level extractor safeguards. If you get false detections near the edge of the frame increase Void Proximity Radius."));

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
        spinStreakMinElong = addRow(panel, "Streak Min Elongation", "Minimum elongation required for an extracted source to be treated as a streak candidate rather than a point source.", doubleSpinnerModel(jTransientConfig.streakMinElongation, 1.0, 20.0, 0.1));
        spinStreakMinPix = addRow(panel, "Streak Min Pixels", "Minimum size required for an elongated detection to be accepted as a streak. Higher values reject thin artifacts.", intSpinnerModel(jTransientConfig.streakMinPixels, 1, 2000, 1));
        spinSingleStreakMinPeakSigma = addRow(panel, "Single Streak Min Peak Sigma", "Minimum peak signal-to-noise required for a streak seen in only one frame. Helps reject elongated noise and interpolation artifacts.", doubleSpinnerModel(jTransientConfig.singleStreakMinPeakSigma, 0.0, 50.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinAngleTol = addRow(panel, "Trajectory Angle Tolerance", "For streak-based tracks, maximum allowed difference between the streak angle and the inferred track direction.", doubleSpinnerModel(jTransientConfig.angleToleranceDegrees, 0.5, 180.0, 0.5));
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

        panel.add(createTabIntro("Settings for deep-stack slow-mover detection. Enables detecting very slow moving objects across a sequence. The common settings control whether the branch runs and how strong a candidate must be; the advanced settings refine stack construction and slow-mover-specific filtering."));

        panel.add(createSectionHeader("Common Settings"));
        chkEnableSlowMovers = addCheckboxRow(panel, "Enable Slow-Mover Detection", "Keeps the deep-stack slow-mover branch active for ultra-slow elongated detections that are not well represented by normal frame-to-frame linking.", jTransientConfig.enableSlowMoverDetection);
        spinMasterSlowMoverSigma = addRow(panel, "Master Slow-Mover Sigma", "Detection threshold used only when searching the deep stack for ultra-slow movers.", doubleSpinnerModel(jTransientConfig.masterSlowMoverSigmaMultiplier, 0.5, 15.0, 0.1));
        spinMasterSlowMoverGrowSigma = addRow(panel, "Master Slow-Mover Grow Sigma", "Secondary grow threshold used only for deep-stack slow-mover extraction. Lower values capture more faint edges.", doubleSpinnerModel(jTransientConfig.masterSlowMoverGrowSigmaMultiplier, 0.1, 15.0, 0.1));
        spinMasterSlowMoverMinPixels = addRow(panel, "Master Slow-Mover Min Pixels", "Minimum size required for an elongated source in the master stack to be considered a slow-mover candidate.", intSpinnerModel(jTransientConfig.masterSlowMoverMinPixels, 1, 2000, 1));
        spinSlowMoverBaselineMadMultiplier = addRow(panel, "Slow Mover Baseline MAD Multiplier", "How far above the field's median elongation a source must be to count as a slow-mover candidate. Higher values are stricter.", doubleSpinnerModel(jTransientConfig.slowMoverBaselineMadMultiplier, 0.0, 10.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Settings"));
        spinSlowMoverStackMiddleFraction = addRow(panel, "Slow Mover Stack Middle Fraction", "Fraction of sorted per-pixel samples around the median used to build the slow-mover stack. Larger values blend more frames; smaller values stay closer to the median. For small frame counts, SpacePixels automatically caps this so the selected sample stays at least one frame below the pure maximum stack.", doubleSpinnerModel(jTransientConfig.slowMoverStackMiddleFraction, 0.0, 1.0, 0.01));
        chkEnableSlowMoverShapeFiltering = addCheckboxRow(panel, "Enable Slow-Mover Shape Filtering", "Keeps the slow-mover shape veto stage active. Disable this only if you want to skip irregular, binary, and slow-mover-specific shape checks entirely.", getOptionalBooleanField(jTransientConfig, "enableSlowMoverShapeFiltering", true));
        chkEnableSlowMoverSpecificShapeFiltering = addCheckboxRow(panel, "Enable Slow-Mover Specific Shape Filtering", "Keeps the extra slow-mover-only compact-shape veto active after the shared irregular and binary checks. Disable this to keep the shared shape filters while bypassing the targeted slow-mover-specific veto.", getOptionalBooleanField(jTransientConfig, "enableSlowMoverSpecificShapeFiltering", true));
        spinSlowMoverMedianSupportOverlapFraction = addRow(panel, "Median Support Min Overlap", "Minimum fraction of a slow-mover footprint that must overlap the median-stack artifact mask before the candidate is trusted. Higher values demand stronger support from the median stack.", doubleSpinnerModel(jTransientConfig.slowMoverMedianSupportOverlapFraction, 0.0, 1.0, 0.01));
        spinSlowMoverMedianSupportMaxOverlapFraction = addRow(panel, "Median Support Max Overlap", "Maximum fraction of a slow-mover footprint that may overlap the median-stack artifact mask. Lower values reject candidates that look too similar to stationary median-stack artifacts.", doubleSpinnerModel(jTransientConfig.slowMoverMedianSupportMaxOverlapFraction, 0.0, 1.0, 0.01));
        chkEnableSlowMoverResidualCoreFiltering = addCheckboxRow(panel, "Enable Slow-Mover Residual Core Filtering", "Checks the centroid-centered core of each deep-stack slow-mover candidate against Slow Mover Stack - Median Stack. Enable this to require a compact positive residual near the candidate center.", getOptionalBooleanField(jTransientConfig, "enableSlowMoverResidualCoreFiltering", true));
        spinSlowMoverResidualCoreRadiusPixels = addRow(panel, "Residual Core Radius (Pixels)", "Radius of the centroid-centered core tested against the positive slow-mover residual image. Larger values are more tolerant of broader compact slow movers.", doubleSpinnerModel(getOptionalDoubleField(jTransientConfig, "slowMoverResidualCoreRadiusPixels", 2.0), 0.0, 20.0, 0.1));
        spinSlowMoverResidualCoreMinPositiveFraction = addRow(panel, "Residual Core Min Positive Fraction", "Minimum fraction of core-footprint pixels that must stay positive in Slow Mover Stack - Median Stack. Higher values demand stronger centered excess signal.", doubleSpinnerModel(getOptionalDoubleField(jTransientConfig, "slowMoverResidualCoreMinPositiveFraction", 0.50), 0.0, 1.0, 0.01));

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
        spinBackgroundSigma = addRow(panel, "Background Deviation Sigma", "Rejects frames whose background level deviates too much from the session median. Higher values are more tolerant.", doubleSpinnerModel(jTransientConfig.backgroundSigmaDeviation, 0.0, 10.0, 0.1));

        // --- NEW: ABSOLUTE MINIMUMS SECTION ---
        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Absolute Minimum Tolerances"));
        spinMinBgDevAdu = addRow(panel, "Min Background Deviation (ADU)", "Minimum absolute background tolerance used even when the measured session variation is tiny.", doubleSpinnerModel(jTransientConfig.minBackgroundDeviationADU, 0.0, 10000.0, 5.0));
        spinMinEccEnvelope = addRow(panel, "Min Eccentricity Envelope", "Minimum absolute tolerance around the session eccentricity median, so tiny shape changes do not trigger rejection.", doubleSpinnerModel(jTransientConfig.minEccentricityEnvelope, 0.0, 1.0, 0.01));
        spinMinFwhmEnvelope = addRow(panel, "Min FWHM Envelope (Pixels)", "Minimum absolute tolerance around the session FWHM median, so tiny focus changes do not trigger rejection.", doubleSpinnerModel(jTransientConfig.minFwhmEnvelope, 0.0, 5.0, 0.05));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Single Frame Analytics"));
        spinQualitySigma = addRow(panel, "Quality Eval Sigma Multiplier", "Detection threshold used only for extracting stars for frame quality analysis, not for transient detection.", doubleSpinnerModel(jTransientConfig.qualitySigmaMultiplier, 1.0, 15.0, 0.1));
        spinQualityGrowSigma = addRow(panel, "Quality Grow Sigma", "Secondary hysteresis threshold used only while growing stars for frame quality analysis and auto-tune frame sampling.", doubleSpinnerModel(jTransientConfig.qualityGrowSigmaMultiplier, 0.1, 20.0, 0.1));
        spinQualityMinPix = addRow(panel, "Quality Min Detection Pixels", "Minimum source size required for a star to be used in frame quality analysis.", intSpinnerModel(jTransientConfig.qualityMinDetectionPixels, 1, 500, 1));
        spinMaxElongFwhm = addRow(panel, "Quality Max Elongation for FWHM", "Only stars with elongation below this value are used when measuring median FWHM for frame quality.", doubleSpinnerModel(jTransientConfig.qualityMaxElongationForFwhm, 1.0, 10.0, 0.05));

        return panel;
    }

    private JPanel buildAdvancedVisualizationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createTabIntro("Visualization-only controls for exported reports and review imagery. These settings do not change detection results; they only affect presentation, contrast stretch, and animation cadence."));

        panel.add(createSectionHeader("Display Stretch & Animation"));
        spinAutoBlackSigma = addRow(panel, "Auto Stretch Black Sigma", "Controls how far below the image mean the automatic black point is placed when stretching exported imagery. Higher values darken the background more aggressively.", doubleSpinnerModel(ImageDisplayUtils.autoStretchBlackSigma, 0.0, 10.0, 0.1));
        spinAutoWhiteSigma = addRow(panel, "Auto Stretch White Sigma", "Controls how far above the image mean the automatic white point is placed when stretching exported imagery. Higher values preserve more bright-core detail but can reduce contrast on faint structure.", doubleSpinnerModel(ImageDisplayUtils.autoStretchWhiteSigma, 0.1, 20.0, 0.1));
        spinGifBlinkSpeed = addRow(panel, "GIF Blink Speed (ms)", "Frame delay used for exported animated GIFs. Lower values blink faster; higher values slow the inspection cadence.", intSpinnerModel(ImageDisplayUtils.gifBlinkSpeedMs, 50, 5000, 10));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Raw Image Annotations"));
        spinStreakScale = addRow(panel, "Streak Line Scale Factor", "Visualization-only setting that scales the length of the drawn streak annotation line.", doubleSpinnerModel(RawImageAnnotator.streakLineScaleFactor, 0.1, 20.0, 0.1));
        spinStreakCentroidRad = addRow(panel, "Streak Centroid Box Radius", "Visualization-only setting that controls the size of the box drawn around a streak centroid.", intSpinnerModel(RawImageAnnotator.streakCentroidBoxRadius, 1, 100, 1));
        spinPointBoxRad = addRow(panel, "Point Source Min Box Radius", "Visualization-only setting that sets the minimum radius of boxes drawn around point sources.", intSpinnerModel(RawImageAnnotator.pointSourceMinBoxRadius, 1, 50, 1));
        spinBoxPad = addRow(panel, "Dynamic Box Padding", "Visualization-only setting that adds extra padding around automatically sized annotation boxes.", intSpinnerModel(RawImageAnnotator.dynamicBoxPadding, 0, 50, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Image Cropping"));
        spinCropPadding = addRow(panel, "Track Crop Border Padding", "Export-only setting that adds extra border around cropped track images.", intSpinnerModel(ImageDisplayUtils.trackCropPadding, 0, 2000, 10));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Optional Report Sections"));
        chkIncludeAiCreativeReportSections = addCheckboxRow(
                panel,
                "Include AI Creative Report Sections",
                "Adds the Skyprint and Kinematic Compass tribute panels to exported reports. This toggle is saved with visualization preferences, not with detection profiles.",
                ImageDisplayUtils.includeAiCreativeReportSections);

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
            jTransientConfig.slowMoverBaselineMadMultiplier = ((Number) spinSlowMoverBaselineMadMultiplier.getValue()).doubleValue();
            jTransientConfig.slowMoverStackMiddleFraction = ((Number) spinSlowMoverStackMiddleFraction.getValue()).doubleValue();
            jTransientConfig.masterSlowMoverMinPixels = ((Number) spinMasterSlowMoverMinPixels.getValue()).intValue();
            jTransientConfig.slowMoverMedianSupportOverlapFraction = ((Number) spinSlowMoverMedianSupportOverlapFraction.getValue()).doubleValue();
            jTransientConfig.slowMoverMedianSupportMaxOverlapFraction = ((Number) spinSlowMoverMedianSupportMaxOverlapFraction.getValue()).doubleValue();
            setOptionalBooleanField(jTransientConfig, "enableSlowMoverResidualCoreFiltering", chkEnableSlowMoverResidualCoreFiltering.isSelected());
            setOptionalDoubleField(jTransientConfig, "slowMoverResidualCoreRadiusPixels", ((Number) spinSlowMoverResidualCoreRadiusPixels.getValue()).doubleValue());
            setOptionalDoubleField(jTransientConfig, "slowMoverResidualCoreMinPositiveFraction", ((Number) spinSlowMoverResidualCoreMinPositiveFraction.getValue()).doubleValue());
            jTransientConfig.streakMinElongation = ((Number) spinStreakMinElong.getValue()).doubleValue();
            jTransientConfig.streakMinPixels = ((Number) spinStreakMinPix.getValue()).intValue();
            jTransientConfig.singleStreakMinPeakSigma = ((Number) spinSingleStreakMinPeakSigma.getValue()).doubleValue();
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
            setOptionalBooleanField(jTransientConfig, "enableSlowMoverShapeFiltering", chkEnableSlowMoverShapeFiltering.isSelected());
            setOptionalBooleanField(jTransientConfig, "enableSlowMoverSpecificShapeFiltering", chkEnableSlowMoverSpecificShapeFiltering.isSelected());
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
            jTransientConfig.backgroundSigmaDeviation = ((Number) spinBackgroundSigma.getValue()).doubleValue();

            jTransientConfig.minBackgroundDeviationADU = ((Number) spinMinBgDevAdu.getValue()).doubleValue();
            jTransientConfig.minEccentricityEnvelope = ((Number) spinMinEccEnvelope.getValue()).doubleValue();
            jTransientConfig.minFwhmEnvelope = ((Number) spinMinFwhmEnvelope.getValue()).doubleValue();

            jTransientConfig.qualitySigmaMultiplier = ((Number) spinQualitySigma.getValue()).doubleValue();
            jTransientConfig.qualityGrowSigmaMultiplier = ((Number) spinQualityGrowSigma.getValue()).doubleValue();
            jTransientConfig.qualityMinDetectionPixels = ((Number) spinQualityMinPix.getValue()).intValue();
            jTransientConfig.qualityMaxElongationForFwhm = ((Number) spinMaxElongFwhm.getValue()).doubleValue();

            RawImageAnnotator.streakLineScaleFactor = ((Number) spinStreakScale.getValue()).doubleValue();
            RawImageAnnotator.streakCentroidBoxRadius = ((Number) spinStreakCentroidRad.getValue()).intValue();
            RawImageAnnotator.pointSourceMinBoxRadius = ((Number) spinPointBoxRad.getValue()).intValue();
            RawImageAnnotator.dynamicBoxPadding = ((Number) spinBoxPad.getValue()).intValue();
            ImageDisplayUtils.autoStretchBlackSigma = ((Number) spinAutoBlackSigma.getValue()).doubleValue();
            ImageDisplayUtils.autoStretchWhiteSigma = ((Number) spinAutoWhiteSigma.getValue()).doubleValue();
            ImageDisplayUtils.gifBlinkSpeedMs = ((Number) spinGifBlinkSpeed.getValue()).intValue();
            ImageDisplayUtils.trackCropPadding = ((Number) spinCropPadding.getValue()).intValue();
            ImageDisplayUtils.includeAiCreativeReportSections = chkIncludeAiCreativeReportSections != null && chkIncludeAiCreativeReportSections.isSelected();

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
                                    "• Max Mask Overlap Fraction: %s\n\n" +
                                    "Telemetry: Extracted %d stable stars with a %.1f%% noise ratio.\n\n" +
                                    "Would you like to view the detailed mathematical evaluation report?",
                            detectionMsg,
                            growMsg,
                            appliedConfig.minDetectionPixels,
                            formatSpinnerValue(spinStarJitter),
                            formatSpinnerValue(spinMaxMaskOverlapFraction),
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
        setSpinnerValueClamped(spinSlowMoverBaselineMadMultiplier, config.slowMoverBaselineMadMultiplier);
        setSpinnerValueClamped(spinSlowMoverStackMiddleFraction, config.slowMoverStackMiddleFraction);
        setSpinnerValueClamped(spinMasterSlowMoverMinPixels, config.masterSlowMoverMinPixels);
        setSpinnerValueClamped(spinSlowMoverMedianSupportOverlapFraction, config.slowMoverMedianSupportOverlapFraction);
        setSpinnerValueClamped(spinSlowMoverMedianSupportMaxOverlapFraction, config.slowMoverMedianSupportMaxOverlapFraction);
        chkEnableSlowMoverResidualCoreFiltering.setSelected(getOptionalBooleanField(config, "enableSlowMoverResidualCoreFiltering", true));
        setSpinnerValueClamped(spinSlowMoverResidualCoreRadiusPixels, getOptionalDoubleField(config, "slowMoverResidualCoreRadiusPixels", 2.0));
        setSpinnerValueClamped(spinSlowMoverResidualCoreMinPositiveFraction, getOptionalDoubleField(config, "slowMoverResidualCoreMinPositiveFraction", 0.50));

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
        chkEnableSlowMoverShapeFiltering.setSelected(getOptionalBooleanField(config, "enableSlowMoverShapeFiltering", true));
        chkEnableSlowMoverSpecificShapeFiltering.setSelected(getOptionalBooleanField(config, "enableSlowMoverSpecificShapeFiltering", true));
        chkEnableResidualTransientAnalysis.setSelected(getOptionalBooleanField(config, "enableResidualTransientAnalysis", true));
        chkEnableLocalRescueCandidates.setSelected(getOptionalBooleanField(config, "enableLocalRescueCandidates", true));
        chkEnableLocalActivityClusters.setSelected(getOptionalBooleanField(config, "enableLocalActivityClusters", true));
        setSpinnerValueClamped(spinLocalActivityClusterRadiusPixels, getOptionalDoubleField(config, "localActivityClusterRadiusPixels", 10.0));
        setSpinnerValueClamped(spinLocalActivityClusterMinFrames, getOptionalIntField(config, "localActivityClusterMinFrames", 3));

        setSpinnerValueClamped(spinMinFramesAnalysis, config.minFramesForAnalysis);
        setSpinnerValueClamped(spinStarCountSigma, config.starCountSigmaDeviation);
        setSpinnerValueClamped(spinFwhmSigma, config.fwhmSigmaDeviation);
        setSpinnerValueClamped(spinEccentricitySigma, config.eccentricitySigmaDeviation);
        setSpinnerValueClamped(spinBackgroundSigma, config.backgroundSigmaDeviation);
        setSpinnerValueClamped(spinMinBgDevAdu, config.minBackgroundDeviationADU);
        setSpinnerValueClamped(spinMinEccEnvelope, config.minEccentricityEnvelope);
        setSpinnerValueClamped(spinMinFwhmEnvelope, config.minFwhmEnvelope);
        setSpinnerValueClamped(spinQualitySigma, config.qualitySigmaMultiplier);
        setSpinnerValueClamped(spinQualityGrowSigma, config.qualityGrowSigmaMultiplier);
        setSpinnerValueClamped(spinQualityMinPix, config.qualityMinDetectionPixels);
        setSpinnerValueClamped(spinMaxElongFwhm, config.qualityMaxElongationForFwhm);

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
