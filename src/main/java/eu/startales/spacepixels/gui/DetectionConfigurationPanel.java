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
    private JSpinner spinMasterSigma, spinMasterMinPix, spinMasterSlowMoverMinElongation, spinMasterSlowMoverMinPixels, spinMasterSlowMoverSigma, spinMasterSlowMoverGrowSigma;
    private JSpinner spinStreakMinElong, spinStreakMinPix, spinSingleStreakMinPeakSigma, spinPointMinPix;
    private JSpinner spinBgClippingIters, spinBgClippingFactor;

    // --- TrackLinker Spinners ---
    private JSpinner spinStationaryDefect, spinReqDetToStar, spinStarJitterExp, spinStarJitter, spinMaxMaskOverlapFraction, spinPredTol, spinAngleTol;
    private JSpinner spinTrackMinFrameRatio, spinAbsMaxPoints, spinMaxJump, spinMaxSizeRatio;
    private JSpinner spinRhythmVar, spinRhythmMinRatio, spinRhythmStatThresh, spinTimeBasedVelocityTolerance;
    private JSpinner spinMaxFluxRatio;
    
    // --- Anomaly Rescue ---
    private JCheckBox chkEnableAnomalyRescue;
    private JSpinner spinAnomalyMinPeakSigma, spinAnomalyMinPixels;

    // --- Quality Control Spinners ---
    private JSpinner spinMinFramesAnalysis, spinStarCountSigma, spinFwhmSigma;
    private JSpinner spinEccentricitySigma, spinBackgroundSigma, spinZeroSigmaFallback;
    // NEW: Absolute minimum tolerance spinners
    private JSpinner spinMinBgDevAdu, spinMinEccEnvelope, spinMinFwhmEnvelope;

    private JSpinner spinQualitySigma, spinQualityMinPix, spinMaxElongFwhm, spinErrorFallback;

    // --- Auto-Tuner Spinners ---
    private JSpinner spinAutoTransientPenalty, spinAutoSigmaPenalty, spinAutoMinPixPenalty;

    // --- Visualization Spinners (SpacePixels Specific) ---
    private JSpinner spinStreakScale, spinStreakCentroidRad, spinPointBoxRad, spinBoxPad;
    private JSpinner spinAutoBlackSigma, spinAutoWhiteSigma, spinGifBlinkSpeed, spinCropPadding;

    private final JButton previewBtn = new JButton("Preview Detection Settings");

    // --- NEW: AUTO-TUNE BUTTON ---
    private final JButton autoTuneBtn = new JButton("Auto-Tune Settings");

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

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        // Add all buttons
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
        AutoTuneTask tuneTask = new AutoTuneTask(mainAppWindow.getEventBus(), poolToUse, jTransientConfig);
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
                "These are the most commonly tweaked settings. <b>Tip:</b> Start with a high Detection Sigma and Min Pixels, " +
                "then progressively lower them to detect fainter objects without introducing noise. " +
                "Please note that the Auto-Tuner is heuristic-based and may not always provide optimal settings for every dataset." +
                "</div></html>");
        basicIntroLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(basicIntroLabel);

        panel.add(createSectionHeader("Core Source Extraction"));
        spinDetectionSigma = addRow(panel, "Detection Sigma Multiplier", "Strict threshold to start detecting a new object. Lower catches fainter objects but increases noise.", new SpinnerNumberModel(jTransientConfig.detectionSigmaMultiplier, 1.0, 999.0, 0.5));
        spinGrowSigma = addRow(panel, "Grow Sigma (Hysteresis)", "Lower threshold used to trace the faint edges of a blob once a bright seed pixel is found.", new SpinnerNumberModel(jTransientConfig.growSigmaMultiplier, 0.5, 999.0, 0.1));
        spinMinPixels = addRow(panel, "Min Detection Pixels", "The absolute minimum number of pixels a blob must have to be evaluated.", new SpinnerNumberModel(jTransientConfig.minDetectionPixels, 1, 99999, 1));
        spinStreakMinElong = addRow(panel, "Streak Min Elongation", "Minimum length/width ratio required to classify a blob as a fast-moving streak.", new SpinnerNumberModel(jTransientConfig.streakMinElongation, 1.0, 999.0, 0.5));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Tracking & Anomalies"));
        spinStarJitter = addRow(panel, "Base Star Jitter Radius", "Maximum distance (px) stars can wobble between frames due to seeing.", new SpinnerNumberModel(jTransientConfig.maxStarJitter, 0.5, 999.0, 0.5));
        spinMaxMaskOverlapFraction = addRow(panel, "Max Mask Overlap Fraction", "Instead of a strict 1-pixel touch, allow a transient to overlap the master mask up to this fraction (e.g., 0.25 = 25%).", new SpinnerNumberModel(jTransientConfig.maxMaskOverlapFraction, 0.0, 1.0, 0.05));
        spinMaxJump = addRow(panel, "Max Jump Velocity", "The cosmic speed limit! Absolute maximum distance (px) an object can travel between frames.", new SpinnerNumberModel(jTransientConfig.maxJumpPixels, 10.0, 99999.0, 10.0));
        chkEnableAnomalyRescue = addCheckboxRow(panel, "Enable Anomaly Rescue", "Enable the rescue of single-frame, ultra-bright point sources that failed to form a multi-frame track.", jTransientConfig.enableAnomalyRescue);

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Export & Visualization Preferences"));
        spinAutoBlackSigma = addRow(panel, "Auto Stretch Black Sigma", "Subtractions from mean (in sigmas) to set the black point. Ensures dark sky.", new SpinnerNumberModel(ImageDisplayUtils.autoStretchBlackSigma, -99.0, 999.0, 0.1));
        spinAutoWhiteSigma = addRow(panel, "Auto Stretch White Sigma", "Additions to mean (in sigmas) to set white point. Faint targets hit this and turn white.", new SpinnerNumberModel(ImageDisplayUtils.autoStretchWhiteSigma, 0.1, 999.0, 0.5));
        spinGifBlinkSpeed = addRow(panel, "GIF Blink Speed (ms)", "Speed of the exported GIF animations in milliseconds per frame.", new SpinnerNumberModel(ImageDisplayUtils.gifBlinkSpeedMs, 10, 60000, 50));

        return panel;
    }

    private JPanel buildAdvancedExtractorPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Master Map Extraction"));
        chkEnableSlowMovers = addCheckboxRow(panel, "Enable Deep Stack Anomalies", "Scan the master median stack for ultra-slow moving targets (e.g., distant KBOs).", jTransientConfig.enableSlowMoverDetection);
        spinMasterSigma = addRow(panel, "Master Sigma Multiplier", "Baseline requirement for extracting stars to build the Master Star Map. Typically lower to ensure faint halos are masked.", new SpinnerNumberModel(jTransientConfig.masterSigmaMultiplier, 0.5, 999.0, 0.25));
        spinMasterMinPix = addRow(panel, "Master Min Pixels", "Minimum number of pixels a source must have to be considered a star in the Master Star Map.", new SpinnerNumberModel(jTransientConfig.masterMinDetectionPixels, 1, 99999, 1));
        spinMasterSlowMoverSigma = addRow(panel, "Master Slow-Mover Sigma", "Detection sigma used exclusively for finding ultra-slow movers in the master stack.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverSigmaMultiplier, 1.0, 999.0, 0.5));
        spinMasterSlowMoverGrowSigma = addRow(panel, "Master Slow-Mover Grow Sigma", "Grow sigma (hysteresis) used exclusively for finding ultra-slow movers in the master stack.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverGrowSigmaMultiplier, 0.5, 999.0, 0.1));
        spinMasterSlowMoverMinElongation = addRow(panel, "Master Slow-Mover Min Elongation", "Minimum elongation in the master stack to flag an object as an ultra-slow mover candidate. Use 0 to disable.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverMinElongation, 0.0, 99.0, 0.5));
        spinMasterSlowMoverMinPixels = addRow(panel, "Master Slow-Mover Min Pixels", "Minimum pixel area required to flag an elongated object in the master stack as a slow mover candidate.", new SpinnerNumberModel(jTransientConfig.masterSlowMoverMinPixels, 1, 99999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Shape & Edge Classification"));
        spinStreakMinPix = addRow(panel, "Streak Min Pixels", "Minimum number of pixels required to classify an elongated blob as a streak.", new SpinnerNumberModel(jTransientConfig.streakMinPixels, 1, 99999, 1));
        spinSingleStreakMinPeakSigma = addRow(panel, "Single Streak Min Peak Sigma", "Dedicated filter for single-frame streaks to prevent elongated noise/artifacts. Use 0 to disable.", new SpinnerNumberModel(jTransientConfig.singleStreakMinPeakSigma, 0.0, 9999.0, 1.0));
        spinEdgeMargin = addRow(panel, "Edge Margin (Dead Zone)", "Objects with their center in this zone (pixels from edge) are ignored to prevent edge artifacts.", new SpinnerNumberModel(jTransientConfig.edgeMarginPixels, 0, 9999, 1));
        spinVoidFraction = addRow(panel, "Void Threshold Fraction", "If a pixel is darker than this fraction of the background median, it's treated as artificial registration padding.", new SpinnerNumberModel(jTransientConfig.voidThresholdFraction, 0.1, 1.0, 0.1));
        spinVoidRadius = addRow(panel, "Void Proximity Radius", "Distance (px) to look ahead for a void edge to defeat interpolation gradients.", new SpinnerNumberModel(jTransientConfig.voidProximityRadius, 0, 999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Background Statistics"));
        spinBgClippingIters = addRow(panel, "Sigma Clipping Iterations", "Number of passes used to exclude bright stars when calculating the background sky noise.", new SpinnerNumberModel(jTransientConfig.bgClippingIterations, 1, 99, 1));
        spinBgClippingFactor = addRow(panel, "Sigma Clipping Factor", "The threshold (in sigmas) used to chop off stars during the background calculation.", new SpinnerNumberModel(jTransientConfig.bgClippingFactor, 1.0, 99.0, 0.5));

        return panel;
    }

    private JPanel buildAdvancedKinematicsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Phase 1 & 3: Defects & Stars"));
        spinStationaryDefect = addRow(panel, "Stationary Defect Threshold", "Max movement (px) allowed for a streak to be considered a stationary sensor defect (hot column).", new SpinnerNumberModel(jTransientConfig.stationaryDefectThreshold, 0.0, 999.0, 0.5));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Phase 4: Geometric Kinematics"));
        spinPredTol = addRow(panel, "Prediction Line Tolerance", "Allowable radius (px) deviation from the linearly predicted trajectory path.", new SpinnerNumberModel(jTransientConfig.predictionTolerance, 0.5, 999.0, 0.5));
        spinAngleTol = addRow(panel, "Trajectory Angle Tolerance", "Maximum angle difference (degrees) allowed between streak orientation and travel trajectory.", new SpinnerNumberModel(jTransientConfig.angleToleranceDegrees, 1.0, 180.0, 1.0));
        spinMaxSizeRatio = addRow(panel, "Max Morphological Size Ratio", "Maximum allowable ratio in pixel area between two linked objects (disable with 0).", new SpinnerNumberModel(jTransientConfig.maxSizeRatio, 0.0, 999.0, 0.5));
        spinMaxFluxRatio = addRow(panel, "Max Photometric Flux Ratio", "Maximum allowable ratio in total brightness (flux) between two linked objects (disable with 0).", new SpinnerNumberModel(jTransientConfig.maxFluxRatio, 0.0, 999.0, 0.5));
        spinTrackMinFrameRatio = addRow(panel, "Track Length Min Frame Ratio", "Denominator used to calculate minimum points required (e.g., 20 frames / 3.0 = ~7 points required).", new SpinnerNumberModel(jTransientConfig.trackMinFrameRatio, 1.0, 99.0, 0.5));
        spinAbsMaxPoints = addRow(panel, "Absolute Max Required Points", "Hard cap on min points required so huge batches don't demand mathematically impossible lengths.", new SpinnerNumberModel(jTransientConfig.absoluteMaxPointsRequired, 2, 999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Kinematic Rhythm (Speed Limits)"));
        spinRhythmVar = addRow(panel, "Allowed Rhythm Variance", "Max allowed pixel deviation from the expected speed to maintain a 'steady rhythm'.", new SpinnerNumberModel(jTransientConfig.rhythmAllowedVariance, 0.0, 999.0, 0.5));
        spinRhythmMinRatio = addRow(panel, "Min Rhythm Consistency Ratio", "Minimum percentage of jumps (e.g., 0.70 = 70%) that must strictly follow the expected speed.", new SpinnerNumberModel(jTransientConfig.rhythmMinConsistencyRatio, 0.0, 1.0, 0.05));
        spinRhythmStatThresh = addRow(panel, "Rhythm Stationary Threshold", "If the median jump is smaller than this, the object isn't actually moving (it's an artifact).", new SpinnerNumberModel(jTransientConfig.rhythmStationaryThreshold, 0.0, 999.0, 0.1));
        spinTimeBasedVelocityTolerance = addRow(panel, "Time-Based Velocity Tolerance", "Max allowed variance in velocity (speed) when valid timestamps are available (e.g., 0.10 = 10%). Time-based tracks bypass Max Jump.", new SpinnerNumberModel(jTransientConfig.timeBasedVelocityTolerance, 0.0, 1.0, 0.05));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Advanced Anomaly Rescue"));
        spinAnomalyMinPeakSigma = addRow(panel, "Anomaly Min Peak Sigma", "The minimum Peak Signal-to-Noise ratio (Sigma) a single-frame point must have to be rescued.", new SpinnerNumberModel(jTransientConfig.anomalyMinPeakSigma, 1.0, 9999.0, 1.0));
        spinAnomalyMinPixels = addRow(panel, "Anomaly Min Pixels", "The minimum physical size a single-frame point must have to be rescued. Prevents single hot-pixels or cosmic rays from being flagged.", new SpinnerNumberModel(jTransientConfig.anomalyMinPixels, 1, 99999, 1));

        return panel;
    }

    private JPanel buildQualityPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Session Outlier Rejection (MAD)"));
        spinMinFramesAnalysis = addRow(panel, "Min Frames for Analysis", "Minimum frames required in a session to perform meaningful statistical analysis.", new SpinnerNumberModel(jTransientConfig.minFramesForAnalysis, 2, 9999, 1));
        spinStarCountSigma = addRow(panel, "Star Count Drop Sigma", "Sigma drop allowed for star count. Drops usually indicate passing clouds or heavy haze.", new SpinnerNumberModel(jTransientConfig.starCountSigmaDeviation, 0.0, 999.0, 0.5));
        spinFwhmSigma = addRow(panel, "FWHM Spike Sigma", "Sigma spike allowed for FWHM. Spikes indicate bad focus, wind blur, or poor seeing.", new SpinnerNumberModel(jTransientConfig.fwhmSigmaDeviation, 0.0, 999.0, 0.5));
        spinEccentricitySigma = addRow(panel, "Eccentricity Spike Sigma", "Sigma spike allowed for eccentricity. Spikes mean stars are trailing (mount error, wind bump).", new SpinnerNumberModel(jTransientConfig.eccentricitySigmaDeviation, 0.0, 999.0, 0.5));
        spinBackgroundSigma = addRow(panel, "Background Deviation Sigma", "Sigma deviation for sky background. Spike = stray light, Drop = thick clouds.", new SpinnerNumberModel(jTransientConfig.backgroundSigmaDeviation, 0.0, 999.0, 0.5));
        spinZeroSigmaFallback = addRow(panel, "Zero-Sigma Math Fallback", "Fallback value if all frames are mathematically identical to prevent division by zero.", new SpinnerNumberModel(jTransientConfig.zeroSigmaFallback, 0.0001, 999.0, 0.001));

        // --- NEW: ABSOLUTE MINIMUMS SECTION ---
        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Absolute Minimum Tolerances"));
        spinMinBgDevAdu = addRow(panel, "Min Background Deviation (ADU)", "Hard minimum for background deviation to prevent rejection from tiny read noise fluctuations.", new SpinnerNumberModel(jTransientConfig.minBackgroundDeviationADU, 0.0, 999.0, 1.0));
        spinMinEccEnvelope = addRow(panel, "Min Eccentricity Envelope", "Hard minimum threshold envelope for eccentricity. Prevents rejection of slightly rounder/flatter stars.", new SpinnerNumberModel(jTransientConfig.minEccentricityEnvelope, 0.01, 5.0, 0.05));
        spinMinFwhmEnvelope = addRow(panel, "Min FWHM Envelope (Pixels)", "Hard minimum threshold envelope for FWHM to prevent hyper-sensitivity on very stable nights.", new SpinnerNumberModel(jTransientConfig.minFwhmEnvelope, 0.1, 10.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Single Frame Analytics"));
        spinQualitySigma = addRow(panel, "Quality Eval Sigma Multiplier", "Strict threshold for quality evaluation. Measures only strong, distinct stars, ignoring noise.", new SpinnerNumberModel(jTransientConfig.qualitySigmaMultiplier, 1.0, 999.0, 0.5));
        spinQualityMinPix = addRow(panel, "Quality Min Detection Pixels", "Min pixels a source must have to be considered a valid star during the quality phase.", new SpinnerNumberModel(jTransientConfig.qualityMinDetectionPixels, 1, 99999, 1));
        spinMaxElongFwhm = addRow(panel, "Max Elongation for FWHM", "Max elongation a star can have to be included in focus calculation. Excludes trailed stars.", new SpinnerNumberModel(jTransientConfig.maxElongationForFwhm, 1.0, 999.0, 0.1));
        spinErrorFallback = addRow(panel, "Error Fallback Value", "Value assigned when a frame is completely devoid of valid stars (e.g., thick clouds).", new SpinnerNumberModel(jTransientConfig.errorFallbackValue, 0.0, 999999.0, 10.0));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Auto-Tuner Heuristics"));

        JLabel autoTuneDescLabel = new JLabel("<html><div style='color: #999999; font-size: 12px; padding-bottom: 10px; width: 450px;'>" +
                "The Auto-Tuner ranks settings to maximize this equation:<br>" +
                "<span style='color: #4da6ff; font-family: monospace; font-size: 13px;'>Score = Stars - (Noise &times; W<sub>t</sub>) - (Sigma &times; W<sub>s</sub>) - (MinPix &times; W<sub>m</sub>)</span>" +
                "</div></html>");
        autoTuneDescLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(autoTuneDescLabel);

        spinAutoTransientPenalty = addRow(panel, "Transient Noise Penalty (Wt)", "Weighting penalty for transients used in the Auto-Tuner scoring heuristic.", new SpinnerNumberModel(jTransientConfig.scoreWeightTransientPenalty, 0.0, 999.0, 0.5));
        spinAutoSigmaPenalty = addRow(panel, "Sigma Penalty Weight (Ws)", "Weighting penalty for high sigma thresholds. Forces the tuner to prefer the lowest possible clean sigma.", new SpinnerNumberModel(jTransientConfig.scoreWeightSigmaPenalty, 0.0, 999.0, 1.0));
        spinAutoMinPixPenalty = addRow(panel, "Min Pixels Penalty Weight (Wm)", "Weighting penalty for high minimum pixel limits.", new SpinnerNumberModel(jTransientConfig.scoreWeightMinPixPenalty, 0.0, 999.0, 0.5));

        return panel;
    }

    private JPanel buildAdvancedVisualizationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Raw Image Annotations"));
        spinStreakScale = addRow(panel, "Streak Line Scale Factor", "Multiplier applied to calculated elongation ratio to stretch the drawn streak line.", new SpinnerNumberModel(RawImageAnnotator.streakLineScaleFactor, 0.1, 999.0, 0.5));
        spinStreakCentroidRad = addRow(panel, "Streak Centroid Box Radius", "Radius of the small, tight box drawn exactly at a fast-moving streak's calculated centroid.", new SpinnerNumberModel(RawImageAnnotator.streakCentroidBoxRadius, 1, 999, 1));
        spinPointBoxRad = addRow(panel, "Point Source Min Box Radius", "Absolute minimum radius (px) for a bounding box drawn around standard point sources.", new SpinnerNumberModel(RawImageAnnotator.pointSourceMinBoxRadius, 1, 999, 1));
        spinBoxPad = addRow(panel, "Dynamic Box Padding", "Extra padding added to dynamically calculated radius so the box rests on dark sky.", new SpinnerNumberModel(RawImageAnnotator.dynamicBoxPadding, 0, 999, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Image Cropping"));
        spinCropPadding = addRow(panel, "Track Crop Border Padding", "Base padding (in pixels) added to the width and height of multi-frame track crops.", new SpinnerNumberModel(ImageDisplayUtils.trackCropPadding, 0, 9999, 10));

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

        Dimension textDim = new Dimension(450, 55);
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

        Dimension textDim = new Dimension(450, 55);
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
            jTransientConfig.masterSlowMoverMinElongation = ((Number) spinMasterSlowMoverMinElongation.getValue()).doubleValue();
            jTransientConfig.masterSlowMoverMinPixels = ((Number) spinMasterSlowMoverMinPixels.getValue()).intValue();
            jTransientConfig.streakMinElongation = ((Number) spinStreakMinElong.getValue()).doubleValue();
            jTransientConfig.streakMinPixels = ((Number) spinStreakMinPix.getValue()).intValue();
            jTransientConfig.singleStreakMinPeakSigma = ((Number) spinSingleStreakMinPeakSigma.getValue()).doubleValue();
            jTransientConfig.bgClippingIterations = ((Number) spinBgClippingIters.getValue()).intValue();
            jTransientConfig.bgClippingFactor = ((Number) spinBgClippingFactor.getValue()).doubleValue();

            // --- Apply Tracker Settings to the POJO ---
            jTransientConfig.stationaryDefectThreshold = ((Number) spinStationaryDefect.getValue()).doubleValue();
            jTransientConfig.maxStarJitter = ((Number) spinStarJitter.getValue()).doubleValue();
            jTransientConfig.maxMaskOverlapFraction = ((Number) spinMaxMaskOverlapFraction.getValue()).doubleValue();
            jTransientConfig.predictionTolerance = ((Number) spinPredTol.getValue()).doubleValue();
            jTransientConfig.angleToleranceDegrees = ((Number) spinAngleTol.getValue()).doubleValue();
            jTransientConfig.maxJumpPixels = ((Number) spinMaxJump.getValue()).doubleValue();
            jTransientConfig.maxSizeRatio = ((Number) spinMaxSizeRatio.getValue()).doubleValue();
            jTransientConfig.maxFluxRatio = ((Number) spinMaxFluxRatio.getValue()).doubleValue();
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
            jTransientConfig.zeroSigmaFallback = ((Number) spinZeroSigmaFallback.getValue()).doubleValue();

            // --- NEW: ABSOLUTE MINIMUMS ---
            jTransientConfig.minBackgroundDeviationADU = ((Number) spinMinBgDevAdu.getValue()).doubleValue();
            jTransientConfig.minEccentricityEnvelope = ((Number) spinMinEccEnvelope.getValue()).doubleValue();
            jTransientConfig.minFwhmEnvelope = ((Number) spinMinFwhmEnvelope.getValue()).doubleValue();

            jTransientConfig.qualitySigmaMultiplier = ((Number) spinQualitySigma.getValue()).doubleValue();
            jTransientConfig.qualityMinDetectionPixels = ((Number) spinQualityMinPix.getValue()).intValue();
            jTransientConfig.maxElongationForFwhm = ((Number) spinMaxElongFwhm.getValue()).doubleValue();
            jTransientConfig.errorFallbackValue = ((Number) spinErrorFallback.getValue()).doubleValue();

            jTransientConfig.scoreWeightTransientPenalty = ((Number) spinAutoTransientPenalty.getValue()).doubleValue();
            jTransientConfig.scoreWeightSigmaPenalty = ((Number) spinAutoSigmaPenalty.getValue()).doubleValue();
            jTransientConfig.scoreWeightMinPixPenalty = ((Number) spinAutoMinPixPenalty.getValue()).doubleValue();

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
                    } else {
                        autoTuneBtn.setEnabled(false);
                    }
                } else {
                    previewBtn.setEnabled(false);
                    autoTuneBtn.setEnabled(false);
                }
            }
        });
    }

    @Subscribe
    public void onAutoTuneStarted(AutoTuneStartedEvent event) {
        EventQueue.invokeLater(() -> {
            autoTuneBtn.setEnabled(false);
            autoTuneBtn.setText("Tuning... Please Wait");
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        });
    }

    @Subscribe
    public void onAutoTuneFinished(eu.startales.spacepixels.events.AutoTuneFinishedEvent event) {
        EventQueue.invokeLater(() -> {
            // Unlock UI
            autoTuneBtn.setEnabled(true);
            autoTuneBtn.setText("Auto-Tune Settings");
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
                            String.format("• Grow Sigma: %.1f (capped to Detection Sigma)\n", result.optimizedConfig.growSigmaMultiplier) : "";

                    String summary = String.format(
                            "Auto-Tuning Complete!\n\n" +
                                    "Winning Settings Found:\n" +
                                    "• Detection Sigma: %.1f\n" +
                                    "• Min Pixels: %d\n" +
                                    "• Max Star Jitter: %.2f px\n" +
                                    "• Streak Min Elongation: %.2f\n" +
                                    "%s\n" +
                                    "Telemetry: Extracted %d stable stars with a %.1f%% noise ratio.",
                            result.optimizedConfig.detectionSigmaMultiplier,
                            result.optimizedConfig.minDetectionPixels,
                            result.optimizedConfig.maxStarJitter,
                            result.optimizedConfig.streakMinElongation,
                            adjustedMsg,
                            result.bestStarCount,
                            (result.bestTransientRatio * 100)
                    );

                    JOptionPane.showMessageDialog(DetectionConfigurationPanel.this, summary, "Auto-Tuner Success", JOptionPane.INFORMATION_MESSAGE);
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
        spinStreakMinElong.setValue(config.streakMinElongation);

        // Push the visual changes to the underlying memory state immediately
        applySettingsToMemory();
    }


}