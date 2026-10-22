/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.jplatesolve.PlateSolveResult;
import io.github.ppissias.jplatesolve.PlateSolveOperation;

import nom.tam.fits.*;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.gui.GuiFitsImportInteraction;
import eu.startales.spacepixels.session.SessionFrames;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import io.github.ppissias.jtransient.core.SourceExtractor;

import javax.swing.*;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * High-level FITS processing facade for SpacePixels.
 *
 * <p>This class coordinates FITS import validation, image conversion, preview/stretch generation,
 * report handoff, and the JTransient detection pipeline. Specialized WCS/plate-solving behavior is
 * delegated to focused collaborators so the public processing API can remain stable while the
 * implementation is split into smaller responsibilities.</p>
 */
public class ImageProcessing implements AutoCloseable {
    public static final int MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS =
            DetectionPipelineSupport.MIN_USABLE_FRAMES_FOR_MULTI_FRAME_ANALYSIS;

    @FunctionalInterface
    public interface DetectionSafetyPrompt {
        boolean shouldProceed(DetectionSummary summary);
    }

    /**
     * Lightweight detection-count snapshot used by UI safety prompts before heavy report export.
     */
    public static final class DetectionSummary {
        public final int totalDetections;
        public final int singleStreaks;
        public final int streakTracks;
        public final int movingTargets;
        public final int anomalies;
        public final int slowMoverCandidates;
        public final int localRescueCandidates;
        public final int localActivityClusters;
        /** Compact sources that share one displacement. Included in {@link #totalDetections}. */
        public final int commonMotionGroups;
        /** Detections that stay at one camera position (hot pixels, dust). Included in {@link #totalDetections}. */
        public final int cameraFixedGroups;
        /** Leftover detections. Shown in the summary, and not added to {@link #totalDetections}. */
        public final int unclassifiedDetections;
        /** Photometry verdict ("READY", "LIMITED", "NOT_READY") or null when variable-star detection did not run. */
        public final String photometryVerdict;
        public final int variableStarCandidates;
        public final int possibleVariableStars;
        /** Groups to write. The warning dialog replaces this when the user leaves some out. */
        private ReportInclusion reportInclusion = ReportInclusion.all();

        DetectionSummary(int totalDetections,
                         int singleStreaks,
                         int streakTracks,
                         int movingTargets,
                         int anomalies,
                         int slowMoverCandidates,
                         int localRescueCandidates,
                         int localActivityClusters,
                         String photometryVerdict,
                         int variableStarCandidates,
                         int possibleVariableStars,
                         int commonMotionGroups,
                         int unclassifiedDetections,
                         int cameraFixedGroups) {
            this.totalDetections = totalDetections;
            this.singleStreaks = singleStreaks;
            this.streakTracks = streakTracks;
            this.movingTargets = movingTargets;
            this.anomalies = anomalies;
            this.slowMoverCandidates = slowMoverCandidates;
            this.localRescueCandidates = localRescueCandidates;
            this.localActivityClusters = localActivityClusters;
            this.photometryVerdict = photometryVerdict;
            this.variableStarCandidates = variableStarCandidates;
            this.possibleVariableStars = possibleVariableStars;
            this.commonMotionGroups = commonMotionGroups;
            this.unclassifiedDetections = unclassifiedDetections;
            this.cameraFixedGroups = cameraFixedGroups;
        }

        /** Which optional groups this report should contain. All of them, unless the warning dialog said otherwise. */
        public ReportInclusion reportInclusion() {
            return reportInclusion;
        }

        /**
         * Asks whether to build the report when the classified count is above {@code limit}.
         * A count at or under the limit proceeds without a dialog. Optional groups the detection had switched on
         * can be left out of the report; groups that were switched off are not offered.
         *
         * @param heading optional line above the summary, for an iterative pass
         * @param config the settings this detection used, or null when every measured group may be offered
         * @return true when the report should be generated
         */
        public boolean confirmReport(boolean plateSolved, int limit, String heading, DetectionConfig config) {
            if (totalDetections <= limit) {
                return true;
            }
            final boolean[] proceed = {false};
            try {
                SwingUtilities.invokeAndWait(() -> {
                    ReportChoice choice = warningPanel(plateSolved, heading, config);
                    int answer = JOptionPane.showConfirmDialog(
                            null,
                            choice.panel,
                            "Detection summary",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.WARNING_MESSAGE);
                    proceed[0] = answer == JOptionPane.YES_OPTION;
                    if (proceed[0]) {
                        reportInclusion = choice.inclusion();
                    }
                });
            } catch (Exception e) {
                return false;
            }
            return proceed[0];
        }

        /** A checkbox is offered only for a group the detection ran and that found something. */
        static boolean offerInReport(boolean featureEnabled, int count) {
            return featureEnabled && count > 0;
        }

        /**
         * The warning shown when a run returns many detections, grouped by kind of result. Slow-mover candidates
         * (maximum stack) and local rescue candidates (residual analysis of leftover transients) are different
         * analyses and are listed separately.
         *
         * @param plateSolved whether a plate-solved frame gives the report sky coordinates
         * @param limit the count above which the warning is shown
         */
        public String warningMessage(boolean plateSolved, int limit) {
            String photometry;
            if (photometryVerdict == null || "NOT_RUN".equals(photometryVerdict)) {
                photometry = "off";
            } else if ("NOT_READY".equals(photometryVerdict)) {
                photometry = "session not ready for photometry (see the report)";
            } else {
                photometry = variableStarCandidates + " candidates, " + possibleVariableStars + " possible";
            }
            return "The engine found " + totalDetections + " detections, more than the usual " + limit + ".\n\n"
                    + "Moving-object tracks: " + movingTargets + "\n"
                    + "Streak tracks (multi-frame): " + streakTracks + "\n"
                    + "Single streaks: " + singleStreaks + "\n"
                    + "Single-frame anomalies: " + anomalies + "\n"
                    + "Slow-mover candidates (maximum stack): " + slowMoverCandidates + "\n"
                    + "Local rescue candidates: " + localRescueCandidates + "\n"
                    + "Local activity clusters: " + localActivityClusters + "\n"
                    + "Common-motion groups: " + commonMotionGroups + "\n"
                    + "Camera-fixed groups (hot pixels, dust): " + cameraFixedGroups + "\n"
                    + "Still unclassified, not counted above: " + unclassifiedDetections + "\n"
                    + "Variable stars: " + photometry + "\n"
                    + "Astrometry: " + (plateSolved
                    ? "a plate-solved frame is available, so the report has sky coordinates and online identification."
                    : "no plate-solved frame, so the report has no sky coordinates or identification.") + "\n\n"
                    + "Largest group: " + largestGroup() + ".\n"
                    + "Generating image crops, GIFs and the HTML report for this many objects takes a long time and uses a lot of disk space.\n"
                    + "Many tracks or anomalies usually mean the Detection Sigma is too low and noise was linked.\n\n"
                    + "Do you want to generate the report anyway?";
        }

        private ReportChoice warningPanel(boolean plateSolved, String heading, DetectionConfig config) {
            JPanel panel = new JPanel();
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(false);
            panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 4));

            String title = (heading == null || heading.isBlank() ? "" : heading + " — ")
                    + "This run found " + totalDetections + " detections.";
            panel.add(aligned(label(title, true)));
            panel.add(Box.createVerticalStrut(10));

            JPanel grid = new JPanel(new GridBagLayout());
            grid.setOpaque(false);
            grid.setAlignmentX(Component.LEFT_ALIGNMENT);
            boolean anomaliesOn = config == null || config.enableAnomalyRescue;
            boolean slowMoversOn = config == null || config.enableSlowMoverDetection;
            boolean residualOn = config == null || config.enableResidualTransientAnalysis;
            boolean localRescueOn = residualOn && (config == null || config.enableLocalRescueCandidates);
            boolean localActivityOn = residualOn && (config == null || config.enableLocalActivityClusters);
            boolean commonMotionOn = config == null || config.enableCommonMotionRejection;
            boolean cameraFixedOn = config == null || config.enableCameraFixedRejection;
            boolean variableStarsOn = config != null && config.enableVariableStarDetection
                    && photometryVerdict != null && !"NOT_RUN".equals(photometryVerdict);
            OptionalRow[] rows = {
                    OptionalRow.always("Moving-object tracks", Integer.toString(movingTargets), movingTargets),
                    OptionalRow.always("Streak tracks", Integer.toString(streakTracks), streakTracks),
                    OptionalRow.always("Single streaks", Integer.toString(singleStreaks), singleStreaks),
                    OptionalRow.when(anomaliesOn, "Single-frame anomalies", Integer.toString(anomalies), anomalies),
                    OptionalRow.when(slowMoversOn, "Slow-mover candidates", Integer.toString(slowMoverCandidates), slowMoverCandidates),
                    OptionalRow.when(localRescueOn, "Local rescue candidates", Integer.toString(localRescueCandidates), localRescueCandidates),
                    OptionalRow.when(localActivityOn, "Local activity clusters", Integer.toString(localActivityClusters), localActivityClusters),
                    OptionalRow.when(commonMotionOn, "Common-motion groups", Integer.toString(commonMotionGroups), commonMotionGroups),
                    OptionalRow.when(cameraFixedOn, "Camera-fixed groups", Integer.toString(cameraFixedGroups), cameraFixedGroups)
            };
            JCheckBox anomaliesBox = null;
            JCheckBox slowMoversBox = null;
            JCheckBox localRescueBox = null;
            JCheckBox localActivityBox = null;
            JCheckBox commonMotionBox = null;
            JCheckBox cameraFixedBox = null;
            JCheckBox[] boxes = new JCheckBox[rows.length];
            if (offerInReport(anomaliesOn, anomalies)) {
                anomaliesBox = includeBox();
                boxes[3] = anomaliesBox;
            }
            if (offerInReport(slowMoversOn, slowMoverCandidates)) {
                slowMoversBox = includeBox();
                boxes[4] = slowMoversBox;
            }
            if (offerInReport(localRescueOn, localRescueCandidates)) {
                localRescueBox = includeBox();
                boxes[5] = localRescueBox;
            }
            if (offerInReport(localActivityOn, localActivityClusters)) {
                localActivityBox = includeBox();
                boxes[6] = localActivityBox;
            }
            if (offerInReport(commonMotionOn, commonMotionGroups)) {
                commonMotionBox = includeBox();
                boxes[7] = commonMotionBox;
            }
            if (offerInReport(cameraFixedOn, cameraFixedGroups)) {
                cameraFixedBox = includeBox();
                boxes[8] = cameraFixedBox;
            }
            JCheckBox variableStarsBox = offerInReport(variableStarsOn, 1) ? includeBox() : null;

            GridBagConstraints name = new GridBagConstraints();
            name.gridx = 0;
            name.anchor = GridBagConstraints.WEST;
            name.insets = new Insets(2, 0, 2, 18);
            GridBagConstraints value = new GridBagConstraints();
            value.gridx = 1;
            value.anchor = GridBagConstraints.EAST;
            value.insets = new Insets(2, 0, 2, 8);
            GridBagConstraints choice = new GridBagConstraints();
            choice.gridx = 2;
            choice.anchor = GridBagConstraints.WEST;
            choice.insets = new Insets(2, 0, 2, 0);
            Color quiet = UIManager.getColor("Label.disabledForeground");
            JLabel included = label("", false);
            int row = 0;
            boolean anyChoice = false;
            for (int i = 0; i < rows.length; i++) {
                if (rows[i] == null) {
                    continue;
                }
                name.gridy = row;
                value.gridy = row;
                choice.gridy = row;
                boolean zero = rows[i].count == 0;
                JLabel nameLabel = new JLabel(rows[i].label);
                JLabel valueLabel = new JLabel(rows[i].value);
                valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD));
                if (zero && quiet != null) {
                    nameLabel.setForeground(quiet);
                    valueLabel.setForeground(quiet);
                }
                grid.add(nameLabel, name);
                grid.add(valueLabel, value);
                if (boxes[i] != null) {
                    grid.add(boxes[i], choice);
                    anyChoice = true;
                }
                row++;
            }
            if (variableStarsOn) {
                name.gridy = row;
                value.gridy = row;
                choice.gridy = row;
                grid.add(new JLabel("Variable stars"), name);
                grid.add(new JLabel(photometryLine()), value);
                if (variableStarsBox != null) {
                    grid.add(variableStarsBox, choice);
                    anyChoice = true;
                }
            }
            panel.add(grid);
            JCheckBox[] watched = {anomaliesBox, slowMoversBox, localRescueBox, localActivityBox, commonMotionBox, cameraFixedBox};
            Runnable refreshIncluded = () -> included.setText(includedLine(watched));
            for (JCheckBox box : watched) {
                if (box != null) {
                    box.addItemListener(e -> refreshIncluded.run());
                }
            }
            refreshIncluded.run();
            panel.add(Box.createVerticalStrut(8));
            panel.add(aligned(wrapped("Still unclassified, not counted above: " + unclassifiedDetections
                    + ". The inspector lists them.")));
            panel.add(Box.createVerticalStrut(6));
            panel.add(aligned(wrapped("Astrometry: " + (plateSolved
                    ? "a plate-solved frame is available, so the report has sky coordinates and online identification."
                    : "no plate-solved frame, so the report has no sky coordinates or identification."))));
            panel.add(Box.createVerticalStrut(8));
            panel.add(aligned(wrapped("Largest group: " + largestGroup() + ". "
                    + "Image crops, GIFs and the HTML report for this many objects take a long time and a lot of disk. "
                    + "Many tracks or anomalies usually mean the Detection Sigma is too low and noise was linked.")));
            if (anyChoice) {
                panel.add(Box.createVerticalStrut(8));
                panel.add(aligned(wrapped("Uncheck a group to leave it out of this report. The detection settings stay as they are.")));
                panel.add(Box.createVerticalStrut(4));
                panel.add(aligned(included));
            }
            panel.add(Box.createVerticalStrut(10));
            panel.add(aligned(label("Generate the report?", true)));
            return new ReportChoice(panel, anomaliesBox, slowMoversBox, localRescueBox, localActivityBox, commonMotionBox,
                    cameraFixedBox, variableStarsBox);
        }

        private String includedLine(JCheckBox[] boxes) {
            int included = movingTargets + streakTracks + singleStreaks;
            if (boxes[0] == null || boxes[0].isSelected()) {
                included += anomalies;
            }
            if (boxes[1] == null || boxes[1].isSelected()) {
                included += slowMoverCandidates;
            }
            if (boxes[2] == null || boxes[2].isSelected()) {
                included += localRescueCandidates;
            }
            if (boxes[3] == null || boxes[3].isSelected()) {
                included += localActivityClusters;
            }
            if (boxes[4] == null || boxes[4].isSelected()) {
                included += commonMotionGroups;
            }
            if (boxes[5] == null || boxes[5].isSelected()) {
                included += cameraFixedGroups;
            }
            return "The report will include " + included + " of these detections.";
        }

        private String photometryLine() {
            if ("NOT_READY".equals(photometryVerdict)) {
                return "session not ready for photometry";
            }
            return variableStarCandidates + " candidates, " + possibleVariableStars + " possible";
        }

        private static JCheckBox includeBox() {
            JCheckBox box = new JCheckBox("In report");
            box.setSelected(true);
            box.setOpaque(false);
            return box;
        }

        private static JLabel label(String text, boolean bold) {
            JLabel result = new JLabel(text);
            if (bold) {
                result.setFont(result.getFont().deriveFont(Font.BOLD));
            }
            return result;
        }

        /** Wrapped plain text whose height is known before the dialog is shown. HTML labels report one line until they are on screen, and the option pane then clips the question and the buttons. */
        private static JComponent wrapped(String text) {
            JTextArea area = new JTextArea(text);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setEditable(false);
            area.setOpaque(false);
            area.setFocusable(false);
            area.setBorder(null);
            Font font = UIManager.getFont("Label.font");
            if (font != null) {
                area.setFont(font);
            }
            Color foreground = UIManager.getColor("Label.foreground");
            if (foreground != null) {
                area.setForeground(foreground);
            }
            int width = 640;
            int height = wrappedHeight(area.getFontMetrics(area.getFont()), text, width);
            area.setPreferredSize(new Dimension(width, height));
            area.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
            return area;
        }

        /** Line count for a word-wrapped label before the component is on screen. */
        private static int wrappedHeight(java.awt.FontMetrics fm, String text, int width) {
            int height = 0;
            int lineHeight = fm.getHeight();
            for (String paragraph : text.split("\n", -1)) {
                if (paragraph.isEmpty()) {
                    height += lineHeight;
                    continue;
                }
                int lineWidth = 0;
                int lines = 1;
                for (String word : paragraph.split(" ")) {
                    int wordWidth = fm.stringWidth(word + " ");
                    if (lineWidth > 0 && lineWidth + wordWidth > width) {
                        lines++;
                        lineWidth = wordWidth;
                    } else {
                        lineWidth += wordWidth;
                    }
                }
                height += lines * lineHeight;
            }
            return height + fm.getDescent();
        }

        private static JComponent aligned(JComponent component) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            return component;
        }

        private String largestGroup() {
            String[] names = {"moving objects", "single-frame anomalies", "slow-mover candidates",
                    "residual analysis", "common-motion groups", "camera-fixed groups"};
            int[] counts = {movingTargets + streakTracks + singleStreaks, anomalies,
                    slowMoverCandidates, localRescueCandidates + localActivityClusters, commonMotionGroups,
                    cameraFixedGroups};
            int best = 0;
            for (int i = 1; i < counts.length; i++) {
                if (counts[i] > counts[best]) {
                    best = i;
                }
            }
            return names[best] + " (" + counts[best] + ")";
        }
    }

    /** One line of the warning. Null when that part of the detection was switched off. */
    private static final class OptionalRow {
        final String label;
        final String value;
        final int count;

        private OptionalRow(String label, String value, int count) {
            this.label = label;
            this.value = value;
            this.count = count;
        }

        static OptionalRow always(String label, String value, int count) {
            return new OptionalRow(label, value, count);
        }

        static OptionalRow when(boolean featureEnabled, String label, String value, int count) {
            return featureEnabled ? new OptionalRow(label, value, count) : null;
        }
    }

    /** The warning panel and the include boxes, read after the dialog closes. */
    private static final class ReportChoice {
        final JComponent panel;
        final JCheckBox anomalies;
        final JCheckBox slowMovers;
        final JCheckBox localRescue;
        final JCheckBox localActivity;
        final JCheckBox commonMotion;
        final JCheckBox cameraFixed;
        final JCheckBox variableStars;

        ReportChoice(JComponent panel, JCheckBox anomalies, JCheckBox slowMovers, JCheckBox localRescue,
                     JCheckBox localActivity, JCheckBox commonMotion, JCheckBox cameraFixed, JCheckBox variableStars) {
            this.panel = panel;
            this.anomalies = anomalies;
            this.slowMovers = slowMovers;
            this.localRescue = localRescue;
            this.localActivity = localActivity;
            this.commonMotion = commonMotion;
            this.cameraFixed = cameraFixed;
            this.variableStars = variableStars;
        }

        ReportInclusion inclusion() {
            return new ReportInclusion(
                    selected(anomalies),
                    selected(slowMovers),
                    selected(localRescue),
                    selected(localActivity),
                    selected(commonMotion),
                    selected(cameraFixed),
                    selected(variableStars));
        }

        private static boolean selected(JCheckBox box) {
            return box == null || box.isSelected();
        }
    }

    /**
     * Immutable payload returned after the JTransient engine completes but before any optional
     * report export starts.
     */
    public static final class PipelineExecutionData {
        private final PipelineResult pipelineResult;
        private final DetectionConfig effectiveConfig;
        private final FitsFileInformation[] filesInformation;
        private final List<short[][]> rawFramesForExport;
        private final long pipelineDurationMillis;

        PipelineExecutionData(PipelineResult pipelineResult,
                              DetectionConfig effectiveConfig,
                              FitsFileInformation[] filesInformation,
                              List<short[][]> rawFramesForExport,
                              long pipelineDurationMillis) {
            this.pipelineResult = pipelineResult;
            this.effectiveConfig = effectiveConfig == null ? null : effectiveConfig.clone();
            this.filesInformation = filesInformation == null ? new FitsFileInformation[0] : filesInformation.clone();
            this.rawFramesForExport = Collections.unmodifiableList(new ArrayList<>(rawFramesForExport));
            this.pipelineDurationMillis = pipelineDurationMillis;
        }

        public PipelineResult getPipelineResult() {
            return pipelineResult;
        }

        public DetectionConfig getEffectiveConfig() {
            return effectiveConfig == null ? null : effectiveConfig.clone();
        }

        public FitsFileInformation[] getFilesInformation() {
            return filesInformation.clone();
        }

        public List<short[][]> getRawFramesForExport() {
            return rawFramesForExport;
        }

        public long getPipelineDurationMillis() {
            return pipelineDurationMillis;
        }
    }

    public static class RedirectImportException extends Exception {
        private final File newDirectory;

        /**
         * Carries the replacement directory that the caller should load instead of the current one.
         */
        public RedirectImportException(File newDirectory) {
            super("Redirecting import to new directory");
            this.newDirectory = newDirectory;
        }

        /**
         * Returns the directory that should replace the original import target.
         */
        public File getNewDirectory() {
            return newDirectory;
        }
    }

    private final SessionFrames sessionFrames = new SessionFrames();
    private final FitsImportService importService;
    private final FitsBatchService batchService;

    private final AppConfig appConfig;
    private final ApplicationSettings applicationSettings;
    private final PlateSolveService plateSolveService;
    private final StandardDetectionPipelineService standardDetectionPipelineService;
    private final IterativeDetectionPipelineService iterativeDetectionPipelineService;
    private final FitsVisualizationRenderer fitsVisualizationRenderer;

    public SessionFrames getSessionFrames() { return sessionFrames; }

    /**
     * Creates a processing facade bound to a specific aligned FITS directory.
     */
    public static synchronized ImageProcessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException {
        return getInstance(alignedFitsFolderFullPath, ApplicationSettings.loadDefault());
    }

    public static ImageProcessing getInstance(File folder, ApplicationSettings settings) throws IOException, FitsException {
        return new ImageProcessing(folder, settings);
    }

    @Override
    public void close() {
        importService.close();
        plateSolveService.close();
        sessionFrames.clear();
    }

    /**
     * Initializes the processor and loads persisted app-level preferences if they exist.
     */
    private ImageProcessing(File alignedFitsFolderFullPath, ApplicationSettings settings) throws IOException, FitsException {
        this.applicationSettings = Objects.requireNonNull(settings);
        this.appConfig = settings.getConfig();
        this.plateSolveService = new PlateSolveService(this.appConfig);
        this.standardDetectionPipelineService = new StandardDetectionPipelineService(this.appConfig, sessionFrames);
        this.iterativeDetectionPipelineService = new IterativeDetectionPipelineService(this.appConfig, this.standardDetectionPipelineService);
        this.fitsVisualizationRenderer = new FitsVisualizationRenderer();
        this.batchService = new FitsBatchService(alignedFitsFolderFullPath, fitsVisualizationRenderer);
        this.importService = new FitsImportService(alignedFitsFolderFullPath, sessionFrames, batchService, new GuiFitsImportInteraction());
    }

    // =========================================================================
    // HDU HELPER METHODS (Immune to empty primary HDUs and Compression)
    // =========================================================================

    /**
     * Iterates through the FITS structure to find the actual Image HDU.
     * Safely decompresses .fz files and skips empty primary headers.
     */
    public static BasicHDU<?> getImageHDU(Fits fits) throws FitsException, IOException {
        return FitsImages.getImageHDU(fits);
    }

// =========================================================================
    // NEW BATCH PROCESSING METHODS (Called by UI)
    // =========================================================================

    /**
     * Converts all color images in the folder to monochrome and returns the generated mono
     * directory so the UI can optionally import it immediately.
     *
     * <p>If stretch is enabled, stretched mono derivatives are also written alongside the mono
     * outputs.</p>
     */
    public File batchConvertToMono(boolean stretch, int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        return batchService.batchConvertToMono(stretch, stretchFactor, iterations, algo, progressListener);
    }

    /**
     * Stretches all images in the folder, regardless of whether they are color or mono.
     */
    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        batchService.batchStretch(stretchFactor, iterations, algo, progressListener);
    }

// =========================================================================
    // THE MULTI-THREADED DETECTION PIPELINE
    // =========================================================================

    /**
     * Runs the standard detection pipeline on the full cached frame set and exports the HTML
     * session report plus associated visualizations.
     */
    public File detectObjects(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return standardDetectionPipelineService.detectObjects(config, sessionFrames.getFiles(), safetyPrompt, progressListener);
    }

    /** Tracks of the last detection run on these frames, or null when none has run. */
    public TrackOverlay getLastTrackOverlay() {
        return sessionFrames.getTracks();
    }

    /** Variable-star candidates of the last standard detection run, numbered as in its report. */
    public VariableStarOverlay getLastVariableStarOverlay() {
        return sessionFrames.getVariables();
    }

    /**
     * Runs the standard detection pipeline and returns the in-memory engine output without writing
     * any report artifacts to disk.
     */
    public PipelineExecutionData runDetectionPipeline(DetectionConfig config, TransientEngineProgressListener progressListener) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return standardDetectionPipelineService.runDetectionPipeline(config, sessionFrames.getFiles(), progressListener);
    }

    /**
     * Exports the HTML report and visualization bundle for a previously executed pipeline run.
     */
    public File exportDetectionReport(PipelineExecutionData executionData) throws IOException {
        return exportDetectionReport(executionData, null);
    }

    /** As {@link #exportDetectionReport(PipelineExecutionData)}, telling {@code progressListener} 0-99% as it writes. */
    public File exportDetectionReport(PipelineExecutionData executionData, TransientEngineProgressListener progressListener) throws IOException {
        return standardDetectionPipelineService.exportDetectionReport(executionData, progressListener);
    }

// =========================================================================
// ITERATIVE SLOW-MOVER PIPELINE
// =========================================================================

    /**
     * Re-runs detection on progressively larger time-spaced subsets so slow movers can be explored
     * across several pass sizes while sharing a single global master stack.
     */
    public File detectSlowObjectsIterative(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener, int maxFramesLimit) throws Exception {
        if (sessionFrames.getFiles() == null) getFitsfileInformation();
        return iterativeDetectionPipelineService.detectSlowObjectsIterative(
                config,
                sessionFrames.getFiles(),
                safetyPrompt,
                progressListener,
                maxFramesLimit);
    }

// =========================================================================
    // UPDATED FITS FILE INFORMATION (The Import Gatekeeper)
    // =========================================================================

    /** Whether any imported frame is plate-solved, which gives the report sky coordinates. */
    public boolean hasPlateSolvedFrame() {
        try {
            for (FitsFileInformation file : getImportedFiles()) {
                if (file.isWcsSolved()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // No frames: nothing is solved.
        }
        return false;
    }

    /**
     * The imported frames: the same objects the frame table shows and the detection run uses, so a header updated in
     * place (Plate Solve writes the WCS) reaches all of them. Reads the folder only when nothing is loaded yet; use
     * {@link #getFitsfileInformation()} only to import.
     */
    public FitsFileInformation[] getImportedFiles() throws Exception {
        return sessionFrames.getFiles() != null ? sessionFrames.getFiles() : getFitsfileInformation();
    }

    /**
     * Loads FITS metadata for interactive use after validating compression state, format
     * consistency, dimensions, and optional auto-conversion paths. Replaces the imported frames with new objects.
     */
    public FitsFileInformation[] getFitsfileInformation() throws Exception {
        return importService.getFitsfileInformation();
    }

    /**
     * Headless variant of FITS metadata loading used by CLI/batch flows that cannot prompt the
     * user for decompression or format-conversion decisions.
     */
    public FitsFileInformation[] getFitsfileInformationHeadless() throws Exception {
        return importService.getFitsfileInformationHeadless();
    }

    /**
     * Starts plate solving through either ASTAP or astrometry.net using the currently configured
     * external tooling.
     */
    public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) throws FitsException, IOException {
        return plateSolveService.solve(fitsFileFullPath, astap, astrometry);
    }

    public PlateSolveOperation startSolve(String path, boolean astap, boolean astrometry,
            Consumer<PlateSolveOperation.Progress> progress) throws IOException {
        return plateSolveService.startSolve(path, astap, astrometry, progress);
    }

    /**
     * Copies WCS keywords from a reference FITS file into every file in the active directory and
     * optionally emits stretched derivatives alongside the solved outputs.
     */
    @Deprecated
    public void applyWCSHeader(String wcsHeaderFile, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws IOException, FitsException {
        batchService.applyWCSHeader(plateSolveService, wcsHeaderFile, stretchFactor, iterations, stretch, algo);
    }

    /**
     * Writes stretched derivatives for every FITS file in the active directory without modifying
     * headers or running any detection logic.
     */
    @Deprecated
    public void onlyStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        batchService.onlyStretch(stretchFactor, iterations, algo);
    }

    /**
     * Safely injects Plate Solve WCS coordinates directly into the original FITS header.
     * Uses a temporary file swap to prevent data corruption.
     */
    public Map<String, String> updateFitsHeaderWithWCS(String fitsFileFullPath, Map<String, String> wcsData) throws Exception {
        return plateSolveService.updateFitsHeaderWithWCS(fitsFileFullPath, wcsData);
    }

    public void cleanupSolveArtifacts(String fitsFileFullPath, PlateSolveResult result) {
        plateSolveService.cleanupSolveArtifacts(fitsFileFullPath, result);
    }

    /**
     * Exposes the mutable application preferences currently associated with this processor.
     */
    public AppConfig getAppConfig() {
        return appConfig;
    }

    /**
     * Writes the current application preferences back to the user's config file.
     */
    public void saveAppConfig() throws IOException {
        applicationSettings.save();
    }

    /** The interactive conversion's negative-value question. */
    static String negativeValueQuestion(FitsPixelConverter.NegativeValueScan scan, int offset) {
        return FitsImportService.negativeValueQuestion(scan, offset);
    }

    /**
     * Warns that the images have different exposure times and offers to import only the largest group, copied into a
     * subfolder because SpacePixels works on whole folders. Returns that subfolder, or null when the user cancels.
     */
    public static File offerToImportLargestExposureGroup(MixedExposureException mixed) throws IOException {
        return FitsImportService.offerToImportLargestExposureGroup(mixed, new GuiFitsImportInteraction());
    }

    /** The warning {@link #offerToImportLargestExposureGroup} shows. */
    static String mixedExposureQuestion(MixedExposureException mixed) {
        return FitsImportService.mixedExposureQuestion(mixed);
    }

    /**
     * Applies the selected stretch algorithm directly to a FITS image in memory.
     */
    public void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        fitsVisualizationRenderer.stretchFitsImage(fitsImage, stretchFactor, iterations, algo);
    }

    /**
     * Builds a quick-look preview image from raw FITS kernel data without applying any stretch.
     */
    public BufferedImage getImagePreview(Object kernelData) throws FitsException {
        return fitsVisualizationRenderer.getImagePreview(kernelData);
    }

    /**
     * Convenience wrapper that renders a 350x350 stretched preview.
     */
    public BufferedImage getStretchedImagePreview(Object kernelData, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return fitsVisualizationRenderer.getStretchedImagePreview(kernelData, stretchFactor, iterations, algo);
    }

    /**
     * Convenience wrapper that renders a stretched preview at the caller's requested size.
     */
    public BufferedImage getStretchedImageFullSize(Object kernelData, int width, int height, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException {
        return fitsVisualizationRenderer.getStretchedImageFullSize(kernelData, width, height, stretchFactor, iterations, algo);
    }

    /**
     * Creates a new timestamped sibling directory for detection exports and returns its location. It is never the
     * folder of another run, even when two runs start in the same second.
     */
    public static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }
        File detectionsDir = OutputFolders.createNew(parentDir, "detections_");
        DebugOutput.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
        return detectionsDir;
    }
}
