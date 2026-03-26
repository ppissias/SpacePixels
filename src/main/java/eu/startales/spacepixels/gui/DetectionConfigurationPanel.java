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
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

// --- NEW IMPORTS FOR PROGRESS DIALOG ---
import eu.startales.spacepixels.events.DetectionStartedEvent;
import eu.startales.spacepixels.events.EngineProgressUpdateEvent;

import eu.startales.spacepixels.events.AutoTuneFinishedEvent;
import eu.startales.spacepixels.events.AutoTuneStartedEvent;
import eu.startales.spacepixels.events.FitsImportFinishedEvent;
import eu.startales.spacepixels.tasks.AutoTuneTask;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import eu.startales.spacepixels.util.*;
import nom.tam.fits.Fits;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DetectionConfigurationPanel extends JPanel {

    //link to main window
    private final ApplicationWindow mainAppWindow;

    private final TuningPreviewManager previewManager;

    // --- NEW: JTransient Config State ---
    private volatile DetectionConfig jTransientConfig;
    private final File jTransientConfigFile = new File(System.getProperty("user.home"), "spacepixels_jtransient.json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // --- SourceExtractor Spinners ---
    private JSpinner spinDetectionSigma, spinMinPixels, spinEdgeMargin, spinGrowSigma, spinVoidFraction, spinVoidRadius;
    private JCheckBox chkEnableSlowMovers;
    private JSpinner spinMasterSigma, spinMasterMinPix, spinMasterSlowMoverMinElongation, spinMasterSlowMoverMinPixels, spinMasterSlowMoverSigma, spinMasterSlowMoverGrowSigma, spinSlowMoverBaselineMadMultiplier, spinSlowMoverStackMiddleFraction;
    private JSpinner spinStreakMinElong, spinStreakMinPix, spinSingleStreakMinPeakSigma, spinPointMinPix;
    private JSpinner spinBgClippingIters, spinBgClippingFactor;

    // --- TrackLinker Spinners ---
    private JCheckBox chkStrictExposureKinematics;
    private JSpinner spinReqDetToStar, spinStarJitterExp, spinStarJitter, spinMaxMaskOverlapFraction, spinPredTol, spinAngleTol;
    private JSpinner spinTrackMinFrameRatio, spinAbsMaxPoints, spinMaxJump;
    private JSpinner spinRhythmVar, spinRhythmMinRatio, spinRhythmStatThresh, spinTimeBasedVelocityTolerance;
    private JSpinner spinMaxFwhmRatio, spinMaxSurfaceBrightnessRatio;

    // --- Anomaly Rescue ---
    private JCheckBox chkEnableAnomalyRescue;
    private JSpinner spinAnomalyMinPeakSigma, spinAnomalyMinPixels;

    // --- Quality Control Spinners ---
    private JSpinner spinMinFramesAnalysis, spinStarCountSigma, spinFwhmSigma;
    private JSpinner spinEccentricitySigma, spinBackgroundSigma;
    // NEW: Absolute minimum tolerance spinners
    private JSpinner spinMinBgDevAdu, spinMinEccEnvelope, spinMinFwhmEnvelope;

    private JSpinner spinQualitySigma, spinQualityMinPix, spinMaxElongFwhm;

    // --- Auto-Tuner Spinners ---
    private JSpinner spinAutoTransientPenalty, spinAutoSigmaPenalty, spinAutoMinPixPenalty;

    // --- Visualization Spinners (SpacePixels Specific) ---
    private JSpinner spinStreakScale, spinStreakCentroidRad, spinPointBoxRad, spinBoxPad;
    private JSpinner spinAutoBlackSigma, spinAutoWhiteSigma, spinGifBlinkSpeed, spinCropPadding;

    private final JButton previewBtn = new JButton("Preview Detection Settings");

    // --- NEW: AUTO-TUNE BUTTON ---
    private final JButton autoTuneBtn = new JButton("Auto-Tune Settings");
    private final JComboBox<JTransientAutoTuner.AutoTuneProfile> autoTuneProfileCombo = new JComboBox<>(JTransientAutoTuner.AutoTuneProfile.values());

    public DetectionConfigurationPanel(ApplicationWindow mainAppWindow) {
        this.mainAppWindow = mainAppWindow;
        // 1. Load the JTransient config before building the UI
        loadJTransientConfig();

        this.previewManager = new TuningPreviewManager(mainAppWindow);

        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JTabbedPane tabbedPane = new JTabbedPane();

        // Build and add the tabs
        tabbedPane.addTab("Basic Tuning", buildScrollPane(buildBasicTuningPanel()));
        tabbedPane.addTab("Advanced Extractor", buildScrollPane(buildAdvancedExtractorPanel()));
        tabbedPane.addTab("Advanced Kinematics", buildScrollPane(buildAdvancedKinematicsPanel()));
        tabbedPane.addTab("Quality Control", buildScrollPane(buildQualityPanel()));
        tabbedPane.addTab("Advanced Visualization", buildScrollPane(buildAdvancedVisualizationPanel()));

        add(tabbedPane, BorderLayout.CENTER);

        // --- ENFORCE UI CONSTRAINTS ---
        setupConstraints();

        // Apply Button (Updates memory only)
        JButton applyBtn = new JButton("Apply Settings");
        applyBtn.setToolTipText("Apply these parameters to the current detection engine session.");
        applyBtn.addActionListener(e -> {
            applySettingsToMemory();
            JOptionPane.showMessageDialog(this, "Settings Applied Successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);
        });

        // Save Button (Updates memory AND saves to JSON)
        JButton saveBtn = new JButton("Save Configuration");
        saveBtn.setToolTipText("Save these parameters as the default for future startups.");
        saveBtn.addActionListener(e -> saveJTransientConfig());

        previewBtn.setToolTipText("Run extraction on the selected frame with current settings and show the exact pixel mask.");
        previewBtn.addActionListener(e -> previewManager.showPreview(getJTransientConfig()));

        // --- AUTO-TUNE ACTION LISTENER ---
        autoTuneBtn.setToolTipText("Mathematically sweeps settings to find the optimal signal-to-noise ratio for the current image sequence.");
        autoTuneBtn.addActionListener(e -> runAutoTuner());
        // Disabled until files are actually loaded and are monochrome
        autoTuneBtn.setEnabled(false);

        autoTuneProfileCombo.setSelectedItem(JTransientAutoTuner.AutoTuneProfile.BALANCED);
        autoTuneProfileCombo.setToolTipText("Select the tuning strategy (Conservative = lower noise, Aggressive = faint targets).");
        autoTuneProfileCombo.setEnabled(false);

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        // Add all buttons
        bottomPanel.add(new JLabel("Tuning Profile: "));
        bottomPanel.add(autoTuneProfileCombo);
        bottomPanel.add(autoTuneBtn);
        bottomPanel.add(previewBtn);
        bottomPanel.add(saveBtn);
        bottomPanel.add(applyBtn);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void setupConstraints() {
        // Ensure Detection Sigma >= Master Sigma
        spinDetectionSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
            double masterSigma = ((Number) spinMasterSigma.getValue()).doubleValue();
            if (detSigma < masterSigma) spinDetectionSigma.setValue(masterSigma);

            // Ensure Grow Sigma <= Detection Sigma
            double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
            if (detSigma < growSigma) spinGrowSigma.setValue(detSigma);
        });
        spinMasterSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
            double masterSigma = ((Number) spinMasterSigma.getValue()).doubleValue();
            if (masterSigma > detSigma) spinMasterSigma.setValue(detSigma);
        });

        // Ensure Grow Sigma <= Detection Sigma
        spinGrowSigma.addChangeListener(e -> {
            double detSigma = ((Number) spinDetectionSigma.getValue()).doubleValue();
            double growSigma = ((Number) spinGrowSigma.getValue()).doubleValue();
            if (growSigma > detSigma) spinGrowSigma.setValue(detSigma);
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
    }

// =========================================================================
    // JTRANSIENT AUTO-TUNER LOGIC (EventBus Driven)
    // =========================================================================

    private void runAutoTuner() {
        // --- THE SMART FALLBACK LOGIC ---
        // 1. First, see if the user specifically highlighted a range of files in the UI
        FitsFileInformation[] selectedFiles = mainAppWindow.getMainApplicationPanel().getSelectedFilesInformation();
        FitsFileInformation[] poolToUse;

        if (selectedFiles != null && selectedFiles.length >= 5) {
            poolToUse = selectedFiles;
            System.out.println("Auto-Tuning using user's explicit selection of " + poolToUse.length + " frames.");
        } else {
            // 2. If they didn't, fall back to all imported files
            poolToUse = mainAppWindow.getMainApplicationPanel().getImportedFiles();
            System.out.println("Auto-Tuning using entire imported sequence.");
        }

        if (poolToUse == null || poolToUse.length < 5) {
            JOptionPane.showMessageDialog(this, "You need at least 5 monochrome frames available to run the Auto-Tuner.", "Insufficient Data", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Apply current UI settings to memory to act as the baseline
        applySettingsToMemory();

        mainAppWindow.getEventBus().post(new EngineProgressUpdateEvent(0, "Initializing Mathematical Auto-Tuner..."));

        // Dispatch the task to the background thread via the EventBus pattern
        JTransientAutoTuner.AutoTuneProfile selectedProfile = (JTransientAutoTuner.AutoTuneProfile) autoTuneProfileCombo.getSelectedItem();
        AutoTuneTask tuneTask = new AutoTuneTask(mainAppWindow.getEventBus(), poolToUse, jTransientConfig, selectedProfile);
        new Thread(tuneTask).start();
    }

    // =========================================================================
    // JTRANSIENT JSON LOAD/SAVE LOGIC
    // =========================================================================

    private void loadJTransientConfig() {
        if (jTransientConfigFile.exists()) {
            try (FileReader reader = new FileReader(jTransientConfigFile)) {
                DetectionConfig loadedConfig = gson.fromJson(reader, DetectionConfig.class);
                if (loadedConfig != null) {
                    jTransientConfig = loadedConfig;
                    return; // Success
                }
            } catch (Exception e) {
                System.err.println("Failed to load JTransient config, falling back to defaults: " + e.getMessage());
            }
        }
        // Fallback: Instantiate a fresh one with default values if file doesn't exist
        jTransientConfig = new DetectionConfig();
    }

    private void saveJTransientConfig() {
        applySettingsToMemory(); // Ensure memory is synced with UI first

        try (FileWriter writer = new FileWriter(jTransientConfigFile)) {
            gson.toJson(jTransientConfig, writer);
            JOptionPane.showMessageDialog(this, "Configuration saved successfully to:\n" + jTransientConfigFile.getAbsolutePath(), "Save Success", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to write JTransient JSON: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public DetectionConfig getJTransientConfig() {
        applySettingsToMemory();
        return jTransientConfig;
    }

    // =========================================================================
    // TAB BUILDERS
    // =========================================================================

    private JPanel buildBasicTuningPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        JLabel basicIntroLabel = new JLabel("<html><div style='color: #999999; font-size: 12px; padding-bottom: 10px; width: 450px;'>" +
                "These are the most commonly tweaked settings. <b>Tip:</b> Click the Auto-Tune Settings button and see the detection results. " +
                "Then progressively lower the Detection Sigma and Grow Sigma, or run the Aggressive tuning profile to detect fainter objects. " +
                "If you detect too many transients and false positives, increase the the Detection Sigma, Grow Sigma and Min Detection Pixels. " +
                "</div></html>");
        basicIntroLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(basicIntroLabel);

        panel.add(createSectionHeader("Core Source Extraction"));
        spinDetectionSigma = addRow(panel, "Detection Sigma Multiplier", "Minimum brightness threshold for starting a new detection. Higher values reduce noise; lower values detect fainter objects.", new SpinnerNumberModel(jTransientConfig.detectionSigmaMultiplier, 1.0, 999.0, 0.5));
        spinGrowSigma = addRow(panel, "Grow Sigma (Hysteresis)", "Secondary threshold used to expand a detection after it starts. Lower values capture fainter edges; higher values keep detections tighter.", new SpinnerNumberModel(jTransientConfig.growSigmaMultiplier, 0.5, 999.0, 0.1));
        spinMinPixels = addRow(panel, "Min Detection Pixels", "Minimum blob size required for a detection to be kept. Higher values reject hot pixels and noise; lower values allow smaller sources.", new SpinnerNumberModel(jTransientConfig.minDetectionPixels, 1, 99999, 1));
        spinStreakMinElong = addRow(panel, "Streak Min Elongation", "Minimum elongation ratio required to classify a detection as a streak instead of a point source. Higher values classify fewer objects as streaks.", new SpinnerNumberModel(jTransientConfig.streakMinElongation, 1.0, 999.0, 0.5));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Tracking & Anomalies"));
        spinStarJitter = addRow(panel, "Base Star Jitter Radius", "Expected motion of fixed stars from seeing, guiding, or alignment. Used to grow the master star mask and ignore motion smaller than normal star jitter.", new SpinnerNumberModel(jTransientConfig.maxStarJitter, 0.5, 999.0, 0.5));
        spinMaxMaskOverlapFraction = addRow(panel, "Max Mask Overlap Fraction", "Maximum fraction of a detection allowed to overlap the master star mask. Higher values rescue objects near star halos but may admit more false positives.", new SpinnerNumberModel(jTransientConfig.maxMaskOverlapFraction, 0.0, 1.0, 0.05));
        spinMaxJump = addRow(panel, "Max Jump Velocity", "Maximum allowed jump between linked points in geometric tracking mode. Higher values allow faster movers but increase false links; ignored by time-based tracking.", new SpinnerNumberModel(jTransientConfig.maxJumpPixels, 10.0, 99999.0, 10.0));
        chkStrictExposureKinematics = addCheckboxRow(panel, "Strict Exposure Kinematics", "Uses exposure time and object footprint to cap how far a real object can move between frames. Enable this if you see too many unrealistic track links.", jTransientConfig.strictExposureKinematics);
        chkEnableAnomalyRescue = addCheckboxRow(panel, "Enable Anomaly Rescue", "Allows very bright single-frame point sources to be kept even if they do not form a multi-frame track.", jTransientConfig.enableAnomalyRescue);
        chkEnableSlowMovers = addCheckboxRow(panel, "Enable Deep Stack Anomalies", "Builds and analyzes the slow-mover stack to search for ultra-slow moving objects that may be missed by normal tracking.", jTransientConfig.enableSlowMoverDetection);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Export & Visualization Preferences"));
        spinAutoBlackSigma = addRow(panel, "Auto Stretch Black Sigma", "Rendering-only setting for previews and exports. Lower values darken the background more aggressively.", new SpinnerNumberModel(ImageDisplayUtils.autoStretchBlackSigma, -99.0, 999.0, 0.1));
        spinAutoWhiteSigma = addRow(panel, "Auto Stretch White Sigma", "Rendering-only setting for previews and exports. Lower values make faint features reach white sooner.", new SpinnerNumberModel(ImageDisplayUtils.autoStretchWhiteSigma, 0.1, 999.0, 0.5));
        spinGifBlinkSpeed = addRow(panel, "GIF Blink Speed (ms)", "Milliseconds shown per frame in exported GIF animations. Lower values blink faster.", new SpinnerNumberModel(ImageDisplayUtils.gifBlinkSpeedMs, 10, 60000, 50));

        return panel;
    }

    private JPanel buildAdvancedExtractorPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Advanced Anomaly Rescue"));
        spinAnomalyMinPeakSigma = addRow(panel, "Anomaly Min Peak Sigma", "Minimum peak signal-to-noise required for a single-frame point source to be rescued as an anomaly. Higher values are stricter.", new SpinnerNumberModel(jTransientConfig.anomalyMinPeakSigma, 1.0, 9999.0, 1.0));
        spinAnomalyMinPixels = addRow(panel, "Anomaly Min Pixels", "Minimum size required for a single-frame point source to be rescued. Higher values reject more hot pixels and cosmic rays.", new SpinnerNumberModel(jTransientConfig.anomalyMinPixels, 1, 99999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Streak Detection"));
        spinStreakMinPix = addRow(panel, "Streak Min Pixels", "Minimum size required for an elongated detection to be accepted as a streak. Higher values reject thin artifacts.", new SpinnerNumberModel(jTransientConfig.streakMinPixels, 1, 99999, 1));
        spinSingleStreakMinPeakSigma = addRow(panel, "Single Streak Min Peak Sigma", "Minimum peak signal-to-noise required for a streak seen in only one frame. Helps reject elongated noise and interpolation artifacts.", new SpinnerNumberModel(jTransientConfig.singleStreakMinPeakSigma, 0.0, 9999.0, 1.0));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Master Map Extraction"));
        spinMasterSigma = addRow(panel, "Master Sigma Multiplier", "Detection threshold used when building the master star map. Lower values mask more faint stars and halos; higher values create a smaller, cleaner mask.", new SpinnerNumberModel(jTransientConfig.masterSigmaMultiplier, 0.5, 999.0, 0.25));
        spinMasterMinPix = addRow(panel, "Master Min Pixels", "Minimum size required for a source to be included in the master star map. Lower values include fainter stars.", new SpinnerNumberModel(jTransientConfig.masterMinDetectionPixels, 1, 99999, 1));
        spinMasterSlowMoverSigma = addRow(panel, "Master Slow-Mover Sigma", "Detection threshold used only when searching the deep stack for ultra-slow movers.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverSigmaMultiplier, 1.0, 999.0, 0.5));
        spinMasterSlowMoverGrowSigma = addRow(panel, "Master Slow-Mover Grow Sigma", "Secondary grow threshold used only for deep-stack slow-mover extraction. Lower values capture more faint edges.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverGrowSigmaMultiplier, 0.5, 999.0, 0.1));
        spinSlowMoverBaselineMadMultiplier = addRow(panel, "Slow Mover Baseline MAD Multiplier", "How far above the field's median elongation a source must be to count as a slow-mover candidate. Higher values are stricter.", new SpinnerNumberModel(jTransientConfig.slowMoverBaselineMadMultiplier, 0.0, 999.0, 0.5));
        spinSlowMoverStackMiddleFraction = addRow(panel, "Slow Mover Stack Middle Fraction", "Fraction of sorted per-pixel samples around the median used to build the slow-mover stack. Larger values blend more frames; smaller values stay closer to the median.", new SpinnerNumberModel(jTransientConfig.slowMoverStackMiddleFraction, 0.0, 1.0, 0.05));
        spinMasterSlowMoverMinPixels = addRow(panel, "Master Slow-Mover Min Pixels", "Minimum size required for an elongated source in the master stack to be considered a slow-mover candidate.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverMinPixels, 1, 99999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Shape & Edge Classification"));
        spinEdgeMargin = addRow(panel, "Edge Margin (Dead Zone)", "Rejects detections too close to the image edge, where alignment and stacking artifacts are common.", new SpinnerNumberModel(jTransientConfig.edgeMarginPixels, 0, 9999, 1));
        spinVoidFraction = addRow(panel, "Void Threshold Fraction", "Pixels darker than this fraction of the local background are treated as registration void or padding, not real data.", new SpinnerNumberModel(jTransientConfig.voidThresholdFraction, 0.1, 1.0, 0.1));
        spinVoidRadius = addRow(panel, "Void Proximity Radius", "How far to look for nearby void padding when rejecting edge or interpolation artifacts. Larger values are more aggressive.", new SpinnerNumberModel(jTransientConfig.voidProximityRadius, 0, 999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Background Statistics"));
        spinBgClippingIters = addRow(panel, "Sigma Clipping Iterations", "Number of clipping passes used when estimating background statistics. More passes remove bright-star contamination more thoroughly.", new SpinnerNumberModel(jTransientConfig.bgClippingIterations, 1, 99, 1));
        spinBgClippingFactor = addRow(panel, "Sigma Clipping Factor", "Clipping threshold used during background estimation. Lower values clip bright stars more aggressively.", new SpinnerNumberModel(jTransientConfig.bgClippingFactor, 1.0, 99.0, 0.5));

        return panel;
    }

    private JPanel buildAdvancedKinematicsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Track Point Similarities"));
        spinMaxFwhmRatio = addRow(panel, "Max FWHM Ratio", "Maximum allowed FWHM difference between linked points. Helps ensure all points in a track have similar optical blur; 0 disables this check.", new SpinnerNumberModel(jTransientConfig.maxFwhmRatio, 0.0, 999.0, 0.5));
        spinMaxSurfaceBrightnessRatio = addRow(panel, "Max Surface Brightness Ratio", "Maximum allowed surface-brightness difference between linked points. Helps prevent linking compact artifacts to diffuse blobs; 0 disables this check.", new SpinnerNumberModel(jTransientConfig.maxSurfaceBrightnessRatio, 0.0, 999.0, 0.5));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Phase 4: Geometric Kinematics"));
        spinPredTol = addRow(panel, "Prediction Line Tolerance", "Maximum distance a candidate point may sit from the projected track line and still be accepted. Higher values allow noisier tracks but increase false links.", new SpinnerNumberModel(jTransientConfig.predictionTolerance, 0.5, 999.0, 0.5));
        spinAngleTol = addRow(panel, "Trajectory Angle Tolerance", "For streaks, maximum allowed difference between the streak angle and the track direction.", new SpinnerNumberModel(jTransientConfig.angleToleranceDegrees, 1.0, 180.0, 1.0));
        spinTrackMinFrameRatio = addRow(panel, "Track Length Min Frame Ratio", "Controls minimum track length as required points = total frames / this value. Lower values demand longer tracks; higher values allow shorter ones.", new SpinnerNumberModel(jTransientConfig.trackMinFrameRatio, 1.0, 99.0, 0.5));
        spinAbsMaxPoints = addRow(panel, "Absolute Max Required Points", "Upper limit on the required number of points for a valid track in very long sequences.", new SpinnerNumberModel(jTransientConfig.absoluteMaxPointsRequired, 2, 999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Kinematic Rhythm (Speed Limits)"));
        spinRhythmVar = addRow(panel, "Allowed Rhythm Variance", "Maximum deviation from the median step size allowed when checking whether a track moves at a steady rate.", new SpinnerNumberModel(jTransientConfig.rhythmAllowedVariance, 0.0, 999.0, 0.5));
        spinRhythmMinRatio = addRow(panel, "Min Rhythm Consistency Ratio", "Minimum fraction of jumps that must match the median speed within the allowed variance.", new SpinnerNumberModel(jTransientConfig.rhythmMinConsistencyRatio, 0.0, 1.0, 0.05));
        spinRhythmStatThresh = addRow(panel, "Rhythm Stationary Threshold", "Tracks with median jump below this value are treated as stationary noise or residual stars, not moving objects.", new SpinnerNumberModel(jTransientConfig.rhythmStationaryThreshold, 0.0, 999.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Time-Based Kinematics"));
        spinTimeBasedVelocityTolerance = addRow(panel, "Time-Based Velocity Tolerance", "When timestamps are available, maximum allowed variation in speed between points. Lower values require steadier motion.", new SpinnerNumberModel(jTransientConfig.timeBasedVelocityTolerance, 0.0, 1.0, 0.05));

        return panel;
    }

    private JPanel buildQualityPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Session Outlier Rejection (MAD)"));
        spinMinFramesAnalysis = addRow(panel, "Min Frames for Analysis", "Minimum number of frames required before session-level outlier rejection is applied.", new SpinnerNumberModel(jTransientConfig.minFramesForAnalysis, 2, 9999, 1));
        spinStarCountSigma = addRow(panel, "Star Count Drop Sigma", "Rejects frames whose star count falls too far below the session median. Higher values are more tolerant.", new SpinnerNumberModel(jTransientConfig.starCountSigmaDeviation, 0.0, 999.0, 0.5));
        spinFwhmSigma = addRow(panel, "FWHM Spike Sigma", "Rejects frames whose median FWHM rises too far above the session median. Higher values are more tolerant.", new SpinnerNumberModel(jTransientConfig.fwhmSigmaDeviation, 0.0, 999.0, 0.5));
        spinEccentricitySigma = addRow(panel, "Eccentricity Spike Sigma", "Rejects frames whose star shapes become too elongated compared with the session median. Higher values are more tolerant.", new SpinnerNumberModel(jTransientConfig.eccentricitySigmaDeviation, 0.0, 999.0, 0.5));
        spinBackgroundSigma = addRow(panel, "Background Deviation Sigma", "Rejects frames whose background level deviates too much from the session median. Higher values are more tolerant.", new SpinnerNumberModel(jTransientConfig.backgroundSigmaDeviation, 0.0, 999.0, 0.5));

        // --- NEW: ABSOLUTE MINIMUMS SECTION ---
        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Absolute Minimum Tolerances"));
        spinMinBgDevAdu = addRow(panel, "Min Background Deviation (ADU)", "Minimum absolute background tolerance used even when the measured session variation is tiny.", new SpinnerNumberModel(jTransientConfig.minBackgroundDeviationADU, 0.0, 999.0, 1.0));
        spinMinEccEnvelope = addRow(panel, "Min Eccentricity Envelope", "Minimum absolute tolerance around the session eccentricity median, so tiny shape changes do not trigger rejection.", new SpinnerNumberModel(jTransientConfig.minEccentricityEnvelope, 0.01, 5.0, 0.05));
        spinMinFwhmEnvelope = addRow(panel, "Min FWHM Envelope (Pixels)", "Minimum absolute tolerance around the session FWHM median, so tiny focus changes do not trigger rejection.", new SpinnerNumberModel(jTransientConfig.minFwhmEnvelope, 0.1, 10.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Single Frame Analytics"));
        spinQualitySigma = addRow(panel, "Quality Eval Sigma Multiplier", "Detection threshold used only for extracting stars for frame quality analysis, not for transient detection.", new SpinnerNumberModel(jTransientConfig.qualitySigmaMultiplier, 1.0, 999.0, 0.5));
        spinQualityMinPix = addRow(panel, "Quality Min Detection Pixels", "Minimum source size required for a star to be used in frame quality analysis.", new SpinnerNumberModel(jTransientConfig.qualityMinDetectionPixels, 1, 99999, 1));
        spinMaxElongFwhm = addRow(panel, "Max Elongation for FWHM", "Only stars with elongation below this value are used when measuring median FWHM for frame quality.", new SpinnerNumberModel(jTransientConfig.maxElongationForFwhm, 1.0, 999.0, 0.1));

        return panel;
    }

    private JPanel buildAdvancedVisualizationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Raw Image Annotations"));
        spinStreakScale = addRow(panel, "Streak Line Scale Factor", "Visualization-only setting that scales the length of the drawn streak annotation line.", new SpinnerNumberModel(RawImageAnnotator.streakLineScaleFactor, 0.1, 999.0, 0.5));
        spinStreakCentroidRad = addRow(panel, "Streak Centroid Box Radius", "Visualization-only setting that controls the size of the box drawn around a streak centroid.", new SpinnerNumberModel(RawImageAnnotator.streakCentroidBoxRadius, 1, 999, 1));
        spinPointBoxRad = addRow(panel, "Point Source Min Box Radius", "Visualization-only setting that sets the minimum radius of boxes drawn around point sources.", new SpinnerNumberModel(RawImageAnnotator.pointSourceMinBoxRadius, 1, 999, 1));
        spinBoxPad = addRow(panel, "Dynamic Box Padding", "Visualization-only setting that adds extra padding around automatically sized annotation boxes.", new SpinnerNumberModel(RawImageAnnotator.dynamicBoxPadding, 0, 999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Image Cropping"));
        spinCropPadding = addRow(panel, "Track Crop Border Padding", "Export-only setting that adds extra border around cropped track images.", new SpinnerNumberModel(ImageDisplayUtils.trackCropPadding, 0, 9999, 10));

        return panel;
    }

    // =========================================================================
    // UI LAYOUT HELPERS (Standardized across the app)
    // =========================================================================

    private JScrollPane buildScrollPane(JPanel content) {
        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        return scrollPane;
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

    // =========================================================================
    // APPLY LOGIC
    // =========================================================================

    private void applySettingsToMemory() {
        try {
            commitAllSpinners();

            // --- Apply Extractor Settings to the POJO ---
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

            // --- Apply Quality Settings to the POJO ---
            jTransientConfig.minFramesForAnalysis = ((Number) spinMinFramesAnalysis.getValue()).intValue();
            jTransientConfig.starCountSigmaDeviation = ((Number) spinStarCountSigma.getValue()).doubleValue();
            jTransientConfig.fwhmSigmaDeviation = ((Number) spinFwhmSigma.getValue()).doubleValue();
            jTransientConfig.eccentricitySigmaDeviation = ((Number) spinEccentricitySigma.getValue()).doubleValue();
            jTransientConfig.backgroundSigmaDeviation = ((Number) spinBackgroundSigma.getValue()).doubleValue();

            // --- NEW: ABSOLUTE MINIMUMS ---
            jTransientConfig.minBackgroundDeviationADU = ((Number) spinMinBgDevAdu.getValue()).doubleValue();
            jTransientConfig.minEccentricityEnvelope = ((Number) spinMinEccEnvelope.getValue()).doubleValue();
            jTransientConfig.minFwhmEnvelope = ((Number) spinMinFwhmEnvelope.getValue()).doubleValue();

            jTransientConfig.qualitySigmaMultiplier = ((Number) spinQualitySigma.getValue()).doubleValue();
            jTransientConfig.qualityMinDetectionPixels = ((Number) spinQualityMinPix.getValue()).intValue();
            jTransientConfig.maxElongationForFwhm = ((Number) spinMaxElongFwhm.getValue()).doubleValue();

            // --- Apply Visualization Settings (SpacePixels Static Variables) ---
            RawImageAnnotator.streakLineScaleFactor = ((Number) spinStreakScale.getValue()).doubleValue();
            RawImageAnnotator.streakCentroidBoxRadius = ((Number) spinStreakCentroidRad.getValue()).intValue();
            RawImageAnnotator.pointSourceMinBoxRadius = ((Number) spinPointBoxRad.getValue()).intValue();
            RawImageAnnotator.dynamicBoxPadding = ((Number) spinBoxPad.getValue()).intValue();
            ImageDisplayUtils.autoStretchBlackSigma = ((Number) spinAutoBlackSigma.getValue()).doubleValue();
            ImageDisplayUtils.autoStretchWhiteSigma = ((Number) spinAutoWhiteSigma.getValue()).doubleValue();
            ImageDisplayUtils.gifBlinkSpeedMs = ((Number) spinGifBlinkSpeed.getValue()).intValue();
            ImageDisplayUtils.trackCropPadding = ((Number) spinCropPadding.getValue()).intValue();

        } catch (Exception ex) {
            System.err.println("Error applying settings to memory: " + ex.getMessage());
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

        //if images are color disable the preview settings button
        EventQueue.invokeLater(() -> {

            if (event.isSuccess()) {
                // Update internal state
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

                    // --- NEW: Enable the Auto-Tune button if we have enough frames! ---
                    if (filesInfo.length >= 5) {
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
            // Unlock UI
            autoTuneBtn.setEnabled(true);
            autoTuneBtn.setText("Auto-Tune Settings");
            autoTuneProfileCombo.setEnabled(true);
            setCursor(Cursor.getDefaultCursor());
            
            System.out.println(event.getResult().telemetryReport);
            if (event.isSuccess() && event.getResult() != null) {
                JTransientAutoTuner.AutoTunerResult result = event.getResult();

                if (result.success) { // <-- Only proceed if the strict math succeeded!

                    boolean growSigmaAdjusted = false;
                    if (result.optimizedConfig.growSigmaMultiplier > result.optimizedConfig.detectionSigmaMultiplier) {
                        result.optimizedConfig.growSigmaMultiplier = result.optimizedConfig.detectionSigmaMultiplier;
                        growSigmaAdjusted = true;
                    }

                    // Physically move the sliders
                    updateSpinnersFromConfig(result.optimizedConfig);

                    String adjustedMsg = growSigmaAdjusted ?
                            String.format("• Grow Sigma: %.2f (capped to Detection Sigma)", result.optimizedConfig.growSigmaMultiplier) : 
                            String.format("• Grow Sigma: %.2f", result.optimizedConfig.growSigmaMultiplier);

                    String summary = String.format(
                            "Auto-Tuning Complete!\n\n" +
                                    "Winning Settings Found:\n" +
                                    "• Detection Sigma: %.2f\n" +
                                    "%s\n" +
                                    "• Min Pixels: %d\n" +
                                    "• Max Star Jitter: %.2f px\n" +
                                    "• Max Mask Overlap Fraction: %.2f\n" +
                                    "• Streak Min Elongation: %.2f\n\n" +
                                    "Telemetry: Extracted %d stable stars with a %.1f%% noise ratio.\n\n" +
                                    "Would you like to view the detailed mathematical evaluation report?",
                            result.optimizedConfig.detectionSigmaMultiplier,
                            adjustedMsg,
                            result.optimizedConfig.minDetectionPixels,
                            result.optimizedConfig.maxStarJitter,
                            result.optimizedConfig.maxMaskOverlapFraction,
                            result.optimizedConfig.streakMinElongation,
                            result.bestStarCount,
                            (result.bestTransientRatio * 100)
                    );

                    int choice = JOptionPane.showConfirmDialog(DetectionConfigurationPanel.this, summary, "Auto-Tuner Success", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);
                    if (choice == JOptionPane.YES_OPTION) {
                        showTelemetryReportWindow(result.telemetryReport);
                    }
                } else {
                    // Show a helpful warning explaining WHY it didn't change the settings
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

        spinDetectionSigma.setValue(config.detectionSigmaMultiplier);
        spinGrowSigma.setValue(config.growSigmaMultiplier);
        spinMinPixels.setValue(config.minDetectionPixels);
        spinStarJitter.setValue(config.maxStarJitter);
        spinMaxMaskOverlapFraction.setValue(config.maxMaskOverlapFraction);
        chkStrictExposureKinematics.setSelected(config.strictExposureKinematics);
        spinStreakMinElong.setValue(config.streakMinElongation);

        // Push the visual changes to the underlying memory state immediately
        applySettingsToMemory();
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
