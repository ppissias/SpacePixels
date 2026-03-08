package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.*;
import spv.util.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

public class DetectionConfigurationPanel extends JPanel {

    // --- SourceExtractor Spinners ---
    private JSpinner spinDetectionSigma, spinMinPixels, spinEdgeMargin, spinGrowSigma, spinVoidFraction;
    private JSpinner spinStreakMinElong, spinStreakMinPix, spinPointMinPix;
    private JSpinner spinBgClippingIters, spinBgClippingFactor;

    // --- TrackLinker Spinners ---
    private JSpinner spinStationaryDefect, spinReqDetToStar, spinStarJitterExp;
    private JSpinner spinTrackMinFrameRatio, spinAbsMaxPoints, spinMaxJump, spinMaxSizeRatio;
    private JSpinner spinRhythmVar, spinRhythmMinRatio, spinRhythmStatThresh;
    private JSpinner spinMaxFluxRatio;

    // --- Quality Control Spinners ---
    private JSpinner spinMinFramesAnalysis, spinStarCountSigma, spinFwhmSigma;
    private JSpinner spinEccentricitySigma, spinBackgroundSigma, spinZeroSigmaFallback;
    private JSpinner spinQualitySigma, spinQualityMinPix, spinMaxElongFwhm, spinErrorFallback;

    // --- Visualization Spinners ---
    private JSpinner spinStreakScale, spinStreakCentroidRad, spinPointBoxRad, spinBoxPad;
    private JSpinner spinAutoBlackSigma, spinAutoWhiteSigma, spinGifBlinkSpeed, spinCropPadding;

    public DetectionConfigurationPanel() {
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));

        JTabbedPane tabbedPane = new JTabbedPane();

        // Build and add the tabs
        tabbedPane.addTab("Source Extraction", buildScrollPane(buildExtractorPanel()));
        tabbedPane.addTab("Track Linking", buildScrollPane(buildTrackerPanel()));
        tabbedPane.addTab("Quality Control", buildScrollPane(buildQualityPanel()));
        tabbedPane.addTab("Visualization", buildScrollPane(buildVisualizationPanel()));

        add(tabbedPane, BorderLayout.CENTER);

        // Apply Button
        JButton applyBtn = new JButton("Apply Settings");
        applyBtn.setToolTipText("Save and apply these parameters to the detection engine.");
        applyBtn.addActionListener(e -> applySettings());

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBorder(new EmptyBorder(10, 0, 0, 0));
        bottomPanel.add(applyBtn);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    // =========================================================================
    // TAB BUILDERS (Using unified layout strategy)
    // =========================================================================

    private JPanel buildExtractorPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Detection Thresholds"));
        spinDetectionSigma = addRow(panel, "Detection Sigma Multiplier", "Strict threshold to start detecting a new object. Lower catches fainter objects but increases noise.", new SpinnerNumberModel(SourceExtractor.detectionSigmaMultiplier, 1.0, 20.0, 0.5));
        spinGrowSigma = addRow(panel, "Grow Sigma (Hysteresis)", "Lower threshold used to trace the faint edges of a blob once a bright seed pixel is found.", new SpinnerNumberModel(SourceExtractor.growSigmaMultiplier, 0.5, 10.0, 0.1));
        spinMinPixels = addRow(panel, "Min Detection Pixels", "The absolute minimum number of pixels a blob must have to be evaluated.", new SpinnerNumberModel(SourceExtractor.minDetectionPixels, 1, 100, 1));
        spinEdgeMargin = addRow(panel, "Edge Margin (Dead Zone)", "Objects with their center in this zone (pixels from edge) are ignored to prevent edge artifacts.", new SpinnerNumberModel(SourceExtractor.edgeMarginPixels, 0, 100, 1));
        spinVoidFraction = addRow(panel, "Void Threshold Fraction", "If a pixel is darker than this fraction of the background median, it's treated as artificial registration padding.", new SpinnerNumberModel(SourceExtractor.voidThresholdFraction, 0.1, 1.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Shape Classification"));
        spinStreakMinElong = addRow(panel, "Streak Min Elongation", "Minimum length/width ratio required to classify a blob as a fast-moving streak.", new SpinnerNumberModel(SourceExtractor.streakMinElongation, 1.0, 20.0, 0.5));
        spinStreakMinPix = addRow(panel, "Streak Min Pixels", "Minimum number of pixels required to classify an elongated blob as a streak.", new SpinnerNumberModel(SourceExtractor.streakMinPixels, 1, 500, 1));
        spinPointMinPix = addRow(panel, "Point Source Min Pixels", "Minimum number of pixels to classify a blob as a standard point source (star/asteroid).", new SpinnerNumberModel(SourceExtractor.pointSourceMinPixels, 1, 100, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Background Statistics"));
        spinBgClippingIters = addRow(panel, "Sigma Clipping Iterations", "Number of passes used to exclude bright stars when calculating the background sky noise.", new SpinnerNumberModel(SourceExtractor.bgClippingIterations, 1, 10, 1));
        spinBgClippingFactor = addRow(panel, "Sigma Clipping Factor", "The threshold (in sigmas) used to chop off stars during the background calculation.", new SpinnerNumberModel(SourceExtractor.bgClippingFactor, 1.0, 5.0, 0.5));

        return panel;
    }

    private JPanel buildTrackerPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Phase 1 & 3: Defects & Stars"));
        spinStationaryDefect = addRow(panel, "Stationary Defect Threshold", "Max movement (px) allowed for a streak to be considered a stationary sensor defect (hot column).", new SpinnerNumberModel(TrackLinker.stationaryDefectThreshold, 0.0, 20.0, 0.5));
        spinReqDetToStar = addRow(panel, "Required Detections for Star", "How many frames an object must appear in the exact spot to be classified as a stationary star.", new SpinnerNumberModel(TrackLinker.requiredDetectionsToBeStar, 2, 50, 1));
        spinStarJitterExp = addRow(panel, "Star Jitter Expansion Factor", "Multiplier for star jitter to account for long-term atmospheric wobble over the entire session.", new SpinnerNumberModel(TrackLinker.starJitterExpansionFactor, 1.0, 5.0, 0.1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Phase 4: Geometric Kinematics"));
        spinMaxJump = addRow(panel, "Max Jump Velocity", "The cosmic speed limit! Absolute maximum distance (px) an object can travel between frames.", new SpinnerNumberModel(TrackLinker.maxJumpPixels, 10.0, 2000.0, 10.0));
        spinMaxSizeRatio = addRow(panel, "Max Morphological Size Ratio", "Maximum allowable ratio in pixel area between two linked objects.", new SpinnerNumberModel(TrackLinker.maxSizeRatio, 1.0, 10.0, 0.5));
        spinMaxFluxRatio = addRow(panel, "Max Photometric Flux Ratio", "Maximum allowable ratio in total brightness (flux) between two linked objects.", new SpinnerNumberModel(TrackLinker.maxFluxRatio, 1.0, 10.0, 0.5));
        spinTrackMinFrameRatio = addRow(panel, "Track Length Min Frame Ratio", "Denominator used to calculate minimum points required (e.g., 20 frames / 3.0 = ~7 points required).", new SpinnerNumberModel(TrackLinker.trackMinFrameRatio, 1.0, 10.0, 0.5));
        spinAbsMaxPoints = addRow(panel, "Absolute Max Required Points", "Hard cap on min points required so huge batches don't demand mathematically impossible lengths.", new SpinnerNumberModel(TrackLinker.absoluteMaxPointsRequired, 3, 20, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Kinematic Rhythm (Speed Limits)"));
        spinRhythmVar = addRow(panel, "Allowed Rhythm Variance", "Max allowed pixel deviation from the expected speed to maintain a 'steady rhythm'.", new SpinnerNumberModel(TrackLinker.rhythmAllowedVariance, 0.0, 20.0, 0.5));
        spinRhythmMinRatio = addRow(panel, "Min Rhythm Consistency Ratio", "Minimum percentage of jumps (e.g., 0.70 = 70%) that must strictly follow the expected speed.", new SpinnerNumberModel(TrackLinker.rhythmMinConsistencyRatio, 0.1, 1.0, 0.05));
        spinRhythmStatThresh = addRow(panel, "Rhythm Stationary Threshold", "If the median jump is smaller than this, the object isn't actually moving (it's an artifact).", new SpinnerNumberModel(TrackLinker.rhythmStationaryThreshold, 0.0, 5.0, 0.1));

        return panel;
    }

    private JPanel buildQualityPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Session Outlier Rejection (MAD)"));
        spinMinFramesAnalysis = addRow(panel, "Min Frames for Analysis", "Minimum frames required in a session to perform meaningful statistical analysis.", new SpinnerNumberModel(SessionEvaluator.minFramesForAnalysis, 3, 20, 1));
        spinStarCountSigma = addRow(panel, "Star Count Drop Sigma", "Sigma drop allowed for star count. Drops usually indicate passing clouds or heavy haze.", new SpinnerNumberModel(SessionEvaluator.starCountSigmaDeviation, 0.5, 10.0, 0.5));
        spinFwhmSigma = addRow(panel, "FWHM Spike Sigma", "Sigma spike allowed for FWHM. Spikes indicate bad focus, wind blur, or poor seeing.", new SpinnerNumberModel(SessionEvaluator.fwhmSigmaDeviation, 0.5, 10.0, 0.5));
        spinEccentricitySigma = addRow(panel, "Eccentricity Spike Sigma", "Sigma spike allowed for eccentricity. Spikes mean stars are trailing (mount error, wind bump).", new SpinnerNumberModel(SessionEvaluator.eccentricitySigmaDeviation, 0.5, 10.0, 0.5));
        spinBackgroundSigma = addRow(panel, "Background Deviation Sigma", "Sigma deviation for sky background. Spike = stray light, Drop = thick clouds.", new SpinnerNumberModel(SessionEvaluator.backgroundSigmaDeviation, 0.5, 10.0, 0.5));
        spinZeroSigmaFallback = addRow(panel, "Zero-Sigma Math Fallback", "Fallback value if all frames are mathematically identical to prevent division by zero.", new SpinnerNumberModel(SessionEvaluator.zeroSigmaFallback, 0.0001, 1.0, 0.001));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("Single Frame Analytics"));
        spinQualitySigma = addRow(panel, "Quality Eval Sigma Multiplier", "Strict threshold for quality evaluation. Measures only strong, distinct stars, ignoring noise.", new SpinnerNumberModel(FrameQualityAnalyzer.qualitySigmaMultiplier, 1.0, 20.0, 0.5));
        spinQualityMinPix = addRow(panel, "Quality Min Detection Pixels", "Min pixels a source must have to be considered a valid star during the quality phase.", new SpinnerNumberModel(FrameQualityAnalyzer.qualityMinDetectionPixels, 1, 50, 1));
        spinMaxElongFwhm = addRow(panel, "Max Elongation for FWHM", "Max elongation a star can have to be included in focus calculation. Excludes trailed stars.", new SpinnerNumberModel(FrameQualityAnalyzer.maxElongationForFwhm, 1.0, 5.0, 0.1));
        spinErrorFallback = addRow(panel, "Error Fallback Value", "Value assigned when a frame is completely devoid of valid stars (e.g., thick clouds).", new SpinnerNumberModel(FrameQualityAnalyzer.errorFallbackValue, 100.0, 9999.0, 10.0));

        return panel;
    }

    private JPanel buildVisualizationPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(10, 20, 20, 20));

        panel.add(createSectionHeader("Raw Image Annotations"));
        spinStreakScale = addRow(panel, "Streak Line Scale Factor", "Multiplier applied to calculated elongation ratio to stretch the drawn streak line.", new SpinnerNumberModel(RawImageAnnotator.streakLineScaleFactor, 1.0, 20.0, 0.5));
        spinStreakCentroidRad = addRow(panel, "Streak Centroid Box Radius", "Radius of the small, tight box drawn exactly at a fast-moving streak's calculated centroid.", new SpinnerNumberModel(RawImageAnnotator.streakCentroidBoxRadius, 1, 20, 1));
        spinPointBoxRad = addRow(panel, "Point Source Min Box Radius", "Absolute minimum radius (px) for a bounding box drawn around standard point sources.", new SpinnerNumberModel(RawImageAnnotator.pointSourceMinBoxRadius, 1, 50, 1));
        spinBoxPad = addRow(panel, "Dynamic Box Padding", "Extra padding added to dynamically calculated radius so the box rests on dark sky.", new SpinnerNumberModel(RawImageAnnotator.dynamicBoxPadding, 0, 50, 1));

        panel.add(Box.createVerticalStrut(10));
        panel.add(createSectionHeader("GIF Export & Contrast"));
        spinAutoBlackSigma = addRow(panel, "Auto Stretch Black Sigma", "Subtractions from mean (in sigmas) to set the black point. Ensures dark sky.", new SpinnerNumberModel(ImageDisplayUtils.autoStretchBlackSigma, 0.0, 5.0, 0.1));
        spinAutoWhiteSigma = addRow(panel, "Auto Stretch White Sigma", "Additions to mean (in sigmas) to set white point. Faint targets hit this and turn white.", new SpinnerNumberModel(ImageDisplayUtils.autoStretchWhiteSigma, 1.0, 20.0, 0.5));
        spinGifBlinkSpeed = addRow(panel, "GIF Blink Speed (ms)", "Speed of the exported GIF animations in milliseconds per frame.", new SpinnerNumberModel(ImageDisplayUtils.gifBlinkSpeedMs, 50, 2000, 50));
        spinCropPadding = addRow(panel, "Track Crop Border Padding", "Base padding (in pixels) added to the width and height of multi-frame track crops.", new SpinnerNumberModel(ImageDisplayUtils.trackCropPadding, 10, 500, 10));

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

        // Left side: Text (Title + Description)
        JPanel textPanel = new JPanel();
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 13f));
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Use HTML for automatic word-wrapping
        JLabel descLabel = new JLabel("<html>" + description + "</html>");
        descLabel.setFont(descLabel.getFont().deriveFont(Font.PLAIN, 12f));
        descLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        descLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        textPanel.add(titleLabel);
        textPanel.add(Box.createVerticalStrut(3));
        textPanel.add(descLabel);

        // Lock the width of the text panel so all inputs align perfectly
        Dimension textDim = new Dimension(450, 55);
        textPanel.setPreferredSize(textDim);
        textPanel.setMinimumSize(textDim);
        textPanel.setMaximumSize(textDim);

        // Right side: Spinner Control
        JPanel inputWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        JSpinner spinner = new JSpinner(model);
        spinner.setPreferredSize(new Dimension(80, 26));
        inputWrapper.add(spinner);

        row.add(textPanel);
        row.add(Box.createHorizontalStrut(20));
        row.add(inputWrapper);
        row.add(Box.createHorizontalGlue()); // Pushes everything left

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        parent.add(row);

        return spinner;
    }
    // =========================================================================
    // APPLY LOGIC
    // =========================================================================

    private void applySettings() {
        try {
            commitAllSpinners();

            // --- Apply Extractor Settings ---
            SourceExtractor.detectionSigmaMultiplier = (double) spinDetectionSigma.getValue();
            SourceExtractor.growSigmaMultiplier = (double) spinGrowSigma.getValue();
            SourceExtractor.minDetectionPixels = (int) spinMinPixels.getValue();
            SourceExtractor.edgeMarginPixels = (int) spinEdgeMargin.getValue();
            SourceExtractor.voidThresholdFraction = (double) spinVoidFraction.getValue();
            SourceExtractor.streakMinElongation = (double) spinStreakMinElong.getValue();
            SourceExtractor.streakMinPixels = (int) spinStreakMinPix.getValue();
            SourceExtractor.pointSourceMinPixels = (int) spinPointMinPix.getValue();
            SourceExtractor.bgClippingIterations = (int) spinBgClippingIters.getValue();
            SourceExtractor.bgClippingFactor = (double) spinBgClippingFactor.getValue();

            // --- Apply Tracker Settings ---
            TrackLinker.stationaryDefectThreshold = (double) spinStationaryDefect.getValue();
            TrackLinker.requiredDetectionsToBeStar = (int) spinReqDetToStar.getValue();
            TrackLinker.starJitterExpansionFactor = (double) spinStarJitterExp.getValue();
            TrackLinker.maxJumpPixels = (double) spinMaxJump.getValue();
            TrackLinker.maxSizeRatio = (double) spinMaxSizeRatio.getValue();
            TrackLinker.maxFluxRatio = (double) spinMaxFluxRatio.getValue();
            TrackLinker.trackMinFrameRatio = (double) spinTrackMinFrameRatio.getValue();
            TrackLinker.absoluteMaxPointsRequired = (int) spinAbsMaxPoints.getValue();
            TrackLinker.rhythmAllowedVariance = (double) spinRhythmVar.getValue();
            TrackLinker.rhythmMinConsistencyRatio = (double) spinRhythmMinRatio.getValue();
            TrackLinker.rhythmStationaryThreshold = (double) spinRhythmStatThresh.getValue();

            // --- Apply Quality Settings ---
            SessionEvaluator.minFramesForAnalysis = (int) spinMinFramesAnalysis.getValue();
            SessionEvaluator.starCountSigmaDeviation = (double) spinStarCountSigma.getValue();
            SessionEvaluator.fwhmSigmaDeviation = (double) spinFwhmSigma.getValue();
            SessionEvaluator.eccentricitySigmaDeviation = (double) spinEccentricitySigma.getValue();
            SessionEvaluator.backgroundSigmaDeviation = (double) spinBackgroundSigma.getValue();
            SessionEvaluator.zeroSigmaFallback = (double) spinZeroSigmaFallback.getValue();
            FrameQualityAnalyzer.qualitySigmaMultiplier = (double) spinQualitySigma.getValue();
            FrameQualityAnalyzer.qualityMinDetectionPixels = (int) spinQualityMinPix.getValue();
            FrameQualityAnalyzer.maxElongationForFwhm = (double) spinMaxElongFwhm.getValue();
            FrameQualityAnalyzer.errorFallbackValue = (double) spinErrorFallback.getValue();

            // --- Apply Visualization Settings ---
            RawImageAnnotator.streakLineScaleFactor = (double) spinStreakScale.getValue();
            RawImageAnnotator.streakCentroidBoxRadius = (int) spinStreakCentroidRad.getValue();
            RawImageAnnotator.pointSourceMinBoxRadius = (int) spinPointBoxRad.getValue();
            RawImageAnnotator.dynamicBoxPadding = (int) spinBoxPad.getValue();
            ImageDisplayUtils.autoStretchBlackSigma = (double) spinAutoBlackSigma.getValue();
            ImageDisplayUtils.autoStretchWhiteSigma = (double) spinAutoWhiteSigma.getValue();
            ImageDisplayUtils.gifBlinkSpeedMs = (int) spinGifBlinkSpeed.getValue();
            ImageDisplayUtils.trackCropPadding = (int) spinCropPadding.getValue();

            JOptionPane.showMessageDialog(this, "Settings Applied Successfully!", "Success", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error applying settings: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void commitAllSpinners() {
        Component[] tabs = ((JTabbedPane) getComponent(0)).getComponents();
        for (Component tab : tabs) {
            if (tab instanceof JScrollPane) {
                JPanel viewport = (JPanel) ((JScrollPane) tab).getViewport().getView();
                for (Component c : viewport.getComponents()) {
                    // Check if the component is a JPanel (our config row)
                    if (c instanceof JPanel) {
                        JPanel rowPanel = (JPanel) c;
                        // Iterate through the row to find the inputWrapper, then the spinner
                        for(Component wrapper : rowPanel.getComponents()) {
                            if (wrapper instanceof JPanel) {
                                for(Component spinner : ((JPanel)wrapper).getComponents()) {
                                    if (spinner instanceof JSpinner) {
                                        try {
                                            ((JSpinner) spinner).commitEdit();
                                        } catch (Exception ignored) {}
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