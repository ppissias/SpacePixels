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

import nom.tam.fits.*;
import nom.tam.util.Cursor;
import nom.tam.image.compression.hdu.CompressedImageHDU;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.SpacePixelsAppConfigIO;
import eu.startales.spacepixels.gui.ApplicationWindow;

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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    private static final DateTimeFormatter TRACE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'").withZone(ZoneOffset.UTC);

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
        /** Leftover detections. Shown in the summary, and not added to {@link #totalDetections}. */
        public final int unclassifiedDetections;
        /** Anomalies that lie on a confirmed streak in the same frame. Already included in {@link #anomalies}. */
        public final int lowConfidenceAnomalies;
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
                         int lowConfidenceAnomalies) {
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
            this.lowConfidenceAnomalies = lowConfidenceAnomalies;
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
            String anomalyCount = lowConfidenceAnomalies > 0
                    ? anomalies + " (" + lowConfidenceAnomalies + " low confidence)"
                    : Integer.toString(anomalies);
            return "The engine found " + totalDetections + " detections, more than the usual " + limit + ".\n\n"
                    + "Moving-object tracks: " + movingTargets + "\n"
                    + "Streak tracks (multi-frame): " + streakTracks + "\n"
                    + "Single streaks: " + singleStreaks + "\n"
                    + "Single-frame anomalies: " + anomalyCount + "\n"
                    + "Slow-mover candidates (maximum stack): " + slowMoverCandidates + "\n"
                    + "Local rescue candidates: " + localRescueCandidates + "\n"
                    + "Local activity clusters: " + localActivityClusters + "\n"
                    + "Common-motion groups: " + commonMotionGroups + "\n"
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
            String anomalyValue = lowConfidenceAnomalies > 0
                    ? anomalies + "  (" + lowConfidenceAnomalies + " low confidence)"
                    : Integer.toString(anomalies);
            boolean anomaliesOn = config == null || config.enableAnomalyRescue;
            boolean slowMoversOn = config == null || config.enableSlowMoverDetection;
            boolean residualOn = config == null || config.enableResidualTransientAnalysis;
            boolean localRescueOn = residualOn && (config == null || config.enableLocalRescueCandidates);
            boolean localActivityOn = residualOn && (config == null || config.enableLocalActivityClusters);
            boolean commonMotionOn = config == null || config.enableCommonMotionRejection;
            boolean variableStarsOn = config != null && config.enableVariableStarDetection
                    && photometryVerdict != null && !"NOT_RUN".equals(photometryVerdict);
            OptionalRow[] rows = {
                    OptionalRow.always("Moving-object tracks", Integer.toString(movingTargets), movingTargets),
                    OptionalRow.always("Streak tracks", Integer.toString(streakTracks), streakTracks),
                    OptionalRow.always("Single streaks", Integer.toString(singleStreaks), singleStreaks),
                    OptionalRow.when(anomaliesOn, "Single-frame anomalies", anomalyValue, anomalies),
                    OptionalRow.when(slowMoversOn, "Slow-mover candidates", Integer.toString(slowMoverCandidates), slowMoverCandidates),
                    OptionalRow.when(localRescueOn, "Local rescue candidates", Integer.toString(localRescueCandidates), localRescueCandidates),
                    OptionalRow.when(localActivityOn, "Local activity clusters", Integer.toString(localActivityClusters), localActivityClusters),
                    OptionalRow.when(commonMotionOn, "Common-motion groups", Integer.toString(commonMotionGroups), commonMotionGroups)
            };
            JCheckBox anomaliesBox = null;
            JCheckBox slowMoversBox = null;
            JCheckBox localRescueBox = null;
            JCheckBox localActivityBox = null;
            JCheckBox commonMotionBox = null;
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
            JCheckBox[] watched = {anomaliesBox, slowMoversBox, localRescueBox, localActivityBox, commonMotionBox};
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
            return new ReportChoice(panel, anomaliesBox, slowMoversBox, localRescueBox, localActivityBox, commonMotionBox, variableStarsBox);
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
                    "residual analysis", "common-motion groups"};
            int[] counts = {movingTargets + streakTracks + singleStreaks, anomalies,
                    slowMoverCandidates, localRescueCandidates + localActivityClusters, commonMotionGroups};
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
        final JCheckBox variableStars;

        ReportChoice(JComponent panel, JCheckBox anomalies, JCheckBox slowMovers, JCheckBox localRescue,
                     JCheckBox localActivity, JCheckBox commonMotion, JCheckBox variableStars) {
            this.panel = panel;
            this.anomalies = anomalies;
            this.slowMovers = slowMovers;
            this.localRescue = localRescue;
            this.localActivity = localActivity;
            this.commonMotion = commonMotion;
            this.variableStars = variableStars;
        }

        ReportInclusion inclusion() {
            return new ReportInclusion(
                    selected(anomalies),
                    selected(slowMovers),
                    selected(localRescue),
                    selected(localActivity),
                    selected(commonMotion),
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

    private static void logFitsTimestampDiagnostics(String stageLabel, FitsFileInformation[] filesInfo) {
        if (!DebugOutput.isEnabled()) {
            return;
        }
        if (filesInfo == null) {
            System.out.println("\n--- " + stageLabel + " FITS Timestamp Diagnostics ---");
            System.out.println("No FITS metadata loaded.");
            return;
        }

        int validTimestamps = 0;
        int displayWithoutTimestamp = 0;

        System.out.println("\n--- " + stageLabel + " FITS Timestamp Diagnostics ---");
        for (int i = 0; i < filesInfo.length; i++) {
            FitsFileInformation info = filesInfo[i];
            long timestamp = info.getObservationTimestamp();
            if (timestamp > 0L) {
                validTimestamps++;
            }
            if (info.hasDisplayableObservationDateWithoutUsableTimestamp()) {
                displayWithoutTimestamp++;
            }

            System.out.println(String.format(
                    Locale.US,
                    "[%03d] file='%s' | parsedTimestamp=%d | parsedUtc='%s' | %s",
                    i,
                    info.getFileName(),
                    timestamp,
                    formatTraceTimestamp(timestamp),
                    info.getObservationTimestampDiagnostics()));
        }
        System.out.println(String.format(
                Locale.US,
                "Timestamp diagnostics summary: %d/%d files produced a usable timestamp; %d files displayed a date/time but would pass no usable timestamp to JTransient.",
                validTimestamps,
                filesInfo.length,
                displayWithoutTimestamp));
    }

    private static String formatTraceTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "N/A";
        }
        return TRACE_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis));
    }

    /**
     * Internal metadata bundle used during parallel FITS validation/loading in headless mode.
     */
    private static class FitsMetadataLoadResult {
        private final FitsFileInformation fileInfo;
        private final int width;
        private final int height;
        private final int bitpix;

        private FitsMetadataLoadResult(FitsFileInformation fileInfo, int width, int height, int bitpix) {
            this.fileInfo = fileInfo;
            this.width = width;
            this.height = height;
            this.bitpix = bitpix;
        }
    }

    // --- NEW: Custom Exception for Automatic Redirection ---
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

    private final File alignedFitsFolderFullPath;

    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));

    private final AppConfig appConfig;
    private final File appConfigFile;
    private final PlateSolveService plateSolveService;
    private final StandardDetectionPipelineService standardDetectionPipelineService;
    private final IterativeDetectionPipelineService iterativeDetectionPipelineService;
    private final FitsVisualizationRenderer fitsVisualizationRenderer;

    private FitsFileInformation[] cachedFileInfo;

    /**
     * Creates a processing facade bound to a specific aligned FITS directory.
     */
    public static synchronized ImageProcessing getInstance(File alignedFitsFolderFullPath) throws IOException, FitsException {
        return new ImageProcessing(alignedFitsFolderFullPath);
    }

    @Override
    public void close() {
        executor.shutdown();
    }

    /**
     * Initializes the processor and loads persisted app-level preferences if they exist.
     */
    private ImageProcessing(File alignedFitsFolderFullPath) throws IOException, FitsException {
        this.alignedFitsFolderFullPath = alignedFitsFolderFullPath;

        String userhome = System.getProperty("user.home");
        if (userhome == null) userhome = "";
        this.appConfigFile = new File(userhome, SpacePixelsAppConfigIO.DEFAULT_FILENAME);

        AppConfig loadedConfig = null;
        if (this.appConfigFile.exists()) {
            try {
                loadedConfig = SpacePixelsAppConfigIO.load(this.appConfigFile);
            } catch (Exception e) {
                ApplicationWindow.logger.warning("Failed to load JSON app config: " + e.getMessage());
            }
        }
        this.appConfig = (loadedConfig != null) ? loadedConfig : new AppConfig();
        this.plateSolveService = new PlateSolveService(this.appConfig);
        this.standardDetectionPipelineService = new StandardDetectionPipelineService(this.appConfig);
        this.iterativeDetectionPipelineService = new IterativeDetectionPipelineService(this.appConfig, this.standardDetectionPipelineService);
        this.fitsVisualizationRenderer = new FitsVisualizationRenderer();
    }

    // =========================================================================
    // HDU HELPER METHODS (Immune to empty primary HDUs and Compression)
    // =========================================================================

    /**
     * Iterates through the FITS structure to find the actual Image HDU.
     * Safely decompresses .fz files and skips empty primary headers.
     */
    public static BasicHDU<?> getImageHDU(Fits fits) throws FitsException, IOException {
        BasicHDU<?> hdu;
        BasicHDU<?> fallback = null;
        
        // 1. If the Fits object already read HDUs into memory, use them directly
        int numHdus = fits.getNumberOfHDUs();
        for (int i = 0; i < numHdus; i++) {
            hdu = fits.getHDU(i);
            if (fallback == null) fallback = hdu;
            
            if (hdu instanceof CompressedImageHDU) {
                return ((CompressedImageHDU) hdu).asImageHDU();
            }
            int[] axes = hdu.getAxes();
            if (axes != null && axes.length >= 2) {
                boolean hasData = true;
                for (int axis : axes) if (axis <= 0) hasData = false;
                if (hasData) return hdu;
            }
        }

        // 2. Otherwise, safely stream through the file to find the target HDU
        while ((hdu = fits.readHDU()) != null) {
            if (fallback == null) fallback = hdu; // Store the dummy Primary HDU just in case

            if (hdu instanceof CompressedImageHDU) {
                return ((CompressedImageHDU) hdu).asImageHDU();
            }
            
            int[] axes = hdu.getAxes();
            if (axes != null && axes.length >= 2) {
                boolean hasData = true;
                for (int axis : axes) {
                    if (axis <= 0) hasData = false;
                }
                if (hasData) return hdu;
            }
        }
        return fallback;
    }

    /**
     * Detects whether a FITS file is compressed, either via a compressed HDU or `ZIMAGE` header.
     */
    private boolean isCompressedFits(Fits fits) throws FitsException, IOException {
        int numHdus = fits.getNumberOfHDUs();
        for (int i = 0; i < numHdus; i++) {
            if (fits.getHDU(i) instanceof CompressedImageHDU || fits.getHDU(i).getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }
        
        BasicHDU<?> hdu;
        while ((hdu = fits.readHDU()) != null) {
            if (hdu instanceof CompressedImageHDU || hdu.getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }
        return false;
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
        File[] fitsFileInformation = getFitsFilesDetails();
        File generatedMonoDirectory = null;

        int total = fitsFileInformation.length;
        for (int i = 0; i < total; i++) {
            File fileInfo = fitsFileInformation[i];
            if (progressListener != null) {
                int percent = (int) (((float) i / total) * 100);
                progressListener.onProgressUpdate(percent, "Converting " + fileInfo.getName() + "...");
            }

            Fits originalFits = new Fits(fileInfo);
            BasicHDU<?> imageHDU = getImageHDU(originalFits);

            // Check if it's already mono to save time
            int naxis = imageHDU.getHeader().getIntValue("NAXIS");
            if (naxis == 3) { // It's a color image
                ApplicationWindow.logger.info("Converting color image to mono: " + fileInfo.getName());
                Fits monochromeFits = convertToMono(originalFits);

                // Save standard mono
                String monoFilename = addDirectory(fileInfo, "_mono");
                if (generatedMonoDirectory == null) {
                    generatedMonoDirectory = new File(monoFilename).getParentFile();
                }
                writeFitsWithSuffix(monochromeFits, monoFilename, "_mono");

                // If stretch is enabled, stretch the mono version and save it
                if (stretch) {
                    ApplicationWindow.logger.info("Stretching newly converted mono image: " + fileInfo.getName());
                    stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                    String monoStretchFilename = addDirectory(fileInfo, "_mono_stretched");
                    writeFitsWithSuffix(monochromeFits, monoStretchFilename, "_mono_stretch");
                }
            } else {
                ApplicationWindow.logger.info("Skipping mono conversion (already mono): " + fileInfo.getName());
            }
            originalFits.close();
        }
        return generatedMonoDirectory;
    }

    /**
     * Stretches all images in the folder, regardless of whether they are color or mono.
     */
    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();

        int total = fitsFileInformation.length;
        for (int i = 0; i < total; i++) {
            File fileInfo = fitsFileInformation[i];
            if (progressListener != null) {
                int percent = (int) (((float) i / total) * 100);
                progressListener.onProgressUpdate(percent, "Stretching " + fileInfo.getName() + "...");
            }

            Fits originalFits = new Fits(fileInfo);

            ApplicationWindow.logger.info("Stretching image: " + fileInfo.getName());

            // Stretch the file in memory
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);

            // Save the stretched version
            String stretchFilename = addDirectory(fileInfo, "_stretched");
            writeFitsWithSuffix(originalFits, stretchFilename, "_stretch");

            originalFits.close();
        }
    }


// =========================================================================
    // THE MULTI-THREADED DETECTION PIPELINE
    // =========================================================================

    /**
     * Runs the standard detection pipeline on the full cached frame set and exports the HTML
     * session report plus associated visualizations.
     */
    public File detectObjects(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener) throws Exception {
        if (this.cachedFileInfo == null) getFitsfileInformation();
        return standardDetectionPipelineService.detectObjects(config, this.cachedFileInfo, safetyPrompt, progressListener);
    }

    /** Tracks of the last detection run on these frames, or null when none has run. */
    public TrackOverlay getLastTrackOverlay() {
        return standardDetectionPipelineService.getLastTrackOverlay();
    }

    /** Variable-star candidates of the last standard detection run, numbered as in its report. */
    public VariableStarOverlay getLastVariableStarOverlay() {
        return standardDetectionPipelineService.getLastVariableStarOverlay();
    }

    /**
     * Runs the standard detection pipeline and returns the in-memory engine output without writing
     * any report artifacts to disk.
     */
    public PipelineExecutionData runDetectionPipeline(DetectionConfig config, TransientEngineProgressListener progressListener) throws Exception {
        if (this.cachedFileInfo == null) getFitsfileInformation();
        return standardDetectionPipelineService.runDetectionPipeline(config, this.cachedFileInfo, progressListener);
    }

    /**
     * Exports the HTML report and visualization bundle for a previously executed pipeline run.
     */
    public File exportDetectionReport(PipelineExecutionData executionData) throws IOException {
        return standardDetectionPipelineService.exportDetectionReport(executionData);
    }

// =========================================================================
// ITERATIVE SLOW-MOVER PIPELINE
// =========================================================================

    /**
     * Re-runs detection on progressively larger time-spaced subsets so slow movers can be explored
     * across several pass sizes while sharing a single global master stack.
     */
    public File detectSlowObjectsIterative(DetectionConfig config, DetectionSafetyPrompt safetyPrompt, TransientEngineProgressListener progressListener, int maxFramesLimit) throws Exception {
        if (this.cachedFileInfo == null) getFitsfileInformation();
        return iterativeDetectionPipelineService.detectSlowObjectsIterative(
                config,
                this.cachedFileInfo,
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
        return cachedFileInfo != null ? cachedFileInfo : getFitsfileInformation();
    }

    /**
     * Loads FITS metadata for interactive use after validating compression state, format
     * consistency, dimensions, and optional auto-conversion paths. Replaces the imported frames with new objects.
     */
    public FitsFileInformation[] getFitsfileInformation() throws Exception {
        File[] fitsFileInformation = getFitsFilesDetails();
        int numFiles = fitsFileInformation.length;

        if (numFiles == 0) return new FitsFileInformation[0];
        FitsSequenceValidator.validate(fitsFileInformation);

        // --- NEW: COMPRESSION GATEKEEPER ---
        try (Fits firstFits = new Fits(fitsFileInformation[0])) {
            if (isCompressedFits(firstFits)) {
                int choice = JOptionPane.showConfirmDialog(null,
                        "These images appear to be compressed (.fz format).\nSpacePixels requires uncompressed FITS files for optimal tracking and plate-solving.\n\n" +
                                "Would you like to automatically decompress this sequence into a new directory?",
                        "Decompression Required",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    ApplicationWindow.logger.info("Starting decompression...");
                    File newDir = batchDecompress(fitsFileInformation);
                    
                    int loadChoice = JOptionPane.showConfirmDialog(null, 
                            "Decompression Complete!\n\nWould you like to automatically load the new uncompressed directory?", 
                            "Success", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    
                    if (loadChoice == JOptionPane.YES_OPTION && newDir != null) {
                        throw new RedirectImportException(newDir);
                    }
                }
                return new FitsFileInformation[0]; // Stop the current import
            }
        } catch (RedirectImportException re) {
            throw re;
        } catch (Exception e) {
            ApplicationWindow.logger.warning("Failed to read first FITS for compression check: " + e.getMessage());
        }


        // --- PRE-FLIGHT CONSISTENCY CHECK ---
        ApplicationWindow.logger.info("Performing pre-flight consistency check on " + numFiles + " files...");
        FitsFormatChecker.FitsFormat refFormat = FitsFormatChecker.FitsFormat.UNSUPPORTED;
        try {
            refFormat = FitsFormatChecker.checkFormat(fitsFileInformation[0]);
        } catch (Exception ignore) {}

        int tempRefWidth = -1;
        int tempRefHeight = -1;
        boolean tempRefMono = true;
        Header refHeader;

        try {
            Fits firstFits = new Fits(fitsFileInformation[0]);
            BasicHDU<?> hdu = getImageHDU(firstFits);
            refHeader = hdu.getHeader();
            int[] axes = hdu.getAxes();
            if (axes == null) throw new FitsException("No image axes found");

            if (axes.length == 2) {
                tempRefHeight = axes[0];
                tempRefWidth = axes[1];
                tempRefMono = true;
            } else if (axes.length == 3) {
                tempRefHeight = axes[1];
                tempRefWidth = axes[2];
                tempRefMono = false;
            } else {
                firstFits.close();
                JOptionPane.showMessageDialog(null, "Unsupported FITS axes length: " + axes.length, "Import Error", JOptionPane.ERROR_MESSAGE);
                return new FitsFileInformation[0];
            }
            firstFits.close();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null, "Failed to read the first FITS file: " + e.getMessage(), "Import Error", JOptionPane.ERROR_MESSAGE);
            return new FitsFileInformation[0];
        }

        final int refWidth = tempRefWidth;
        final int refHeight = tempRefHeight;
        final boolean refMono = tempRefMono;
        final FitsFormatChecker.FitsFormat finalRefFormat = refFormat;

        List<Callable<String>> validationTasks = new ArrayList<>();
        for (int i = 1; i < numFiles; i++) {
            final File f = fitsFileInformation[i];
            validationTasks.add(() -> {
                FitsFormatChecker.FitsFormat format = FitsFormatChecker.FitsFormat.UNSUPPORTED;
                try { format = FitsFormatChecker.checkFormat(f); } catch (Exception ignore) {}

                if (format != finalRefFormat) {
                    return "Inconsistent FITS formats detected!\nFile: " + f.getName() + " has format " + format + " but expected " + finalRefFormat + ".\nAll FITS files in the sequence must be of the exact same type.";
                }
                Fits fits = null;
                try {
                    fits = new Fits(f);
                    BasicHDU<?> hdu = getImageHDU(fits);
                    int[] axes = hdu.getAxes();
                    if (axes == null) return "No valid image HDU found in " + f.getName();

                    boolean mono = (axes.length == 2);
                    int w = -1, h = -1;
                    if (mono) {
                        h = axes[0];
                        w = axes[1];
                    } else if (axes.length == 3) {
                        h = axes[1];
                        w = axes[2];
                    }

                    if (mono != refMono || w != refWidth || h != refHeight) {
                        return "Inconsistent FITS dimensions or color-space detected!\nFile: " + f.getName() + " is " + w + "x" + h + " (Mono: " + mono + ")\nExpected: " + refWidth + "x" + refHeight + " (Mono: " + refMono + ").\nAll files must have the exact same resolution and color space.";
                    }
                } catch (Exception e) {
                    return "Failed to read FITS file " + f.getName() + ": " + e.getMessage();
                } finally {
                    if (fits != null) {
                        try { fits.close(); } catch (Exception ignored) {}
                    }
                }
                return null; // OK
            });
        }

        try {
            List<Future<String>> results = executor.invokeAll(validationTasks);
            for (Future<String> res : results) {
                String errorMsg = res.get();
                if (errorMsg != null) {
                    JOptionPane.showMessageDialog(null, errorMsg, "Import Error", JOptionPane.ERROR_MESSAGE);
                    return new FitsFileInformation[0];
                }
            }
        } catch (Exception e) {
            throw new IOException("Multi-threaded validation failed: " + e.getMessage(), e);
        }
        // --- END CONSISTENCY CHECK ---

        // --- THE GATEKEEPER: Check the format of the first file ---
        FitsFormatChecker.FitsFormat format = refFormat;

        if ((format == FitsFormatChecker.FitsFormat.MONO_16BIT || format == FitsFormatChecker.FitsFormat.COLOR_16BIT)
                && !FitsPixelConverter.isUnsigned16Bit(refHeader)) {
            boolean color = format == FitsFormatChecker.FitsFormat.COLOR_16BIT;
            int choice = JOptionPane.showConfirmDialog(null,
                    dialogParagraphs("These 16-bit images store their pixels with BZERO " + headerNumber(refHeader.getDoubleValue("BZERO", 0.0))
                                    + " and BSCALE " + headerNumber(refHeader.getDoubleValue("BSCALE", 1.0)) + ". "
                                    + "SpacePixels reads 16-bit images as unsigned values (BZERO 32768, BSCALE 1), so it would read these "
                                    + "at the wrong brightness level, which breaks the saturation, padding and void checks.",
                            color ? "Would you like to extract the Luminance (16-bit Mono) and save a standard 16-bit Color version?"
                                  : "Would you like to convert this sequence to standard 16-bit images in a new folder?"),
                    "Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting signed/scaled 16-bit to standard 16-bit conversion...");
                return standardizeAndOfferToLoad(fitsFileInformation, color);
            }

            ApplicationWindow.logger.info("User declined 16-bit standardization. Clearing the active import.");
            return new FitsFileInformation[0];

        } else if (format == FitsFormatChecker.FitsFormat.MONO_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.MONO_32BIT_INT) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "These images appear to be 32-bit.\nSpacePixels' transient detection engine is highly optimized for 16-bit monochrome files.\n\n" +
                            "Would you like to automatically standardize this sequence to 16-bit Mono?",
                    "Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting 32-bit Mono to 16-bit Mono conversion...");
                return standardizeAndOfferToLoad(fitsFileInformation, false);
            }

            ApplicationWindow.logger.info("User declined 32-bit mono standardization. Clearing the active import.");
            return new FitsFileInformation[0];

        } else if (format == FitsFormatChecker.FitsFormat.COLOR_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.COLOR_32BIT_INT) {
            int choice = JOptionPane.showConfirmDialog(null,
                    "These images are 32-bit Color.\nSpacePixels' transient detection engine requires 16-bit monochrome files.\n\n" +
                            "Would you like to extract the Luminance (16-bit Mono) and save a 16-bit Color version?",
                    "Color Format Conversion Required",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE);

            if (choice == JOptionPane.YES_OPTION) {
                ApplicationWindow.logger.info("Starting 32-bit Color to 16-bit Mono/Color conversion...");
                return standardizeAndOfferToLoad(fitsFileInformation, true);
            }

            ApplicationWindow.logger.info("User declined 32-bit color standardization. Clearing the active import.");
            return new FitsFileInformation[0];
        } else if (format == FitsFormatChecker.FitsFormat.UNSUPPORTED) {
            JOptionPane.showMessageDialog(null, "Unsupported FITS format detected. SpacePixels supports 16-bit and 32-bit FITS files.", "Import Error", JOptionPane.ERROR_MESSAGE);
            return new FitsFileInformation[0];
        }

        DebugOutput.println("\n--- Fast-Loading Information for " + numFiles + " FITS files ---");

        List<Callable<FitsFileInformation>> tasks = new ArrayList<>();

        for (int i = 0; i < numFiles; i++) {
            final int index = i;
            final File currentFile = fitsFileInformation[i];

            tasks.add(() -> {
                Fits fitsFile = null;
                try {
                    fitsFile = new Fits(currentFile);
                    BasicHDU<?> hdu = getImageHDU(fitsFile);
                    Header fitsHeader = hdu.getHeader();

                    String fpath = currentFile.getAbsolutePath();
                    boolean monochromeImage;
                    int width;
                    int height;

                    int[] axes = hdu.getAxes();

                    if (axes.length == 2) {
                        monochromeImage = true;
                        height = axes[0];
                        width = axes[1];
                    } else if (axes.length == 3) {
                        monochromeImage = false;
                        height = axes[1];
                        width = axes[2];
                    } else {
                        throw new FitsException("Cannot understand file, it has axes length=" + axes.length);
                    }

                    FitsFileInformation fileInfo = new FitsFileInformation(fpath, currentFile.getName(), monochromeImage, width, height);

                    Cursor<String, HeaderCard> iter = fitsHeader.iterator();
                    while (iter.hasNext()) {
                        HeaderCard fitsHeaderCard = iter.next();
                        fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
                    }

                    return fileInfo;

                } finally {
                    if (fitsFile != null) {
                        fitsFile.close();
                    }
                }
            });
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];

        try {
            List<Future<FitsFileInformation>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                ret[i] = futures.get(i).get();
            }
        } catch (Exception e) {
            throw new IOException("Multi-threaded file loading failed: " + e.getMessage(), e);
        }

        FitsSequenceValidator.validateAndSort(ret);

        this.cachedFileInfo = ret;
        DebugOutput.println("Finished loading metadata for " + numFiles + " files instantly.");
        logFitsTimestampDiagnostics("Interactive import", ret);
        return ret;
    }

    /**
     * Headless variant of FITS metadata loading used by CLI/batch flows that cannot prompt the
     * user for decompression or format-conversion decisions.
     */
    public FitsFileInformation[] getFitsfileInformationHeadless() throws Exception {
        File[] fitsFiles = getFitsFilesDetails();
        int numFiles = fitsFiles.length;

        if (numFiles == 0) {
            this.cachedFileInfo = new FitsFileInformation[0];
            return this.cachedFileInfo;
        }

        DebugOutput.println("\n--- Validating " + numFiles + " FITS files for headless batch detection ---");

        List<Callable<FitsMetadataLoadResult>> tasks = new ArrayList<>();
        for (File currentFile : fitsFiles) {
            tasks.add(() -> loadFitsMetadataHeadless(currentFile));
        }

        FitsMetadataLoadResult[] loadedResults = new FitsMetadataLoadResult[numFiles];
        try {
            List<Future<FitsMetadataLoadResult>> futures = executor.invokeAll(tasks);
            for (int i = 0; i < futures.size(); i++) {
                loadedResults[i] = futures.get(i).get();
            }
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException("Headless FITS validation failed.", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Headless FITS validation was interrupted.", e);
        }

        FitsMetadataLoadResult reference = loadedResults[0];
        for (int i = 1; i < loadedResults.length; i++) {
            validateHeadlessFitsConsistency(reference, loadedResults[i]);
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];
        for (int i = 0; i < loadedResults.length; i++) {
            ret[i] = loadedResults[i].fileInfo;
        }

        FitsSequenceValidator.validateAndSort(ret);

        this.cachedFileInfo = ret;
        DebugOutput.println("Validated and loaded metadata for " + numFiles + " FITS files.");
        logFitsTimestampDiagnostics("Headless import", ret);
        return ret;
    }

    // =========================================================================
    // EXISTING HELPER METHODS (Unchanged)
    // =========================================================================

    /**
     * Loads and validates a single FITS file for headless mode, enforcing the strict mono 16-bit
     * requirements expected by the batch pipeline.
     */
    private FitsMetadataLoadResult loadFitsMetadataHeadless(File currentFile) throws Exception {
        Fits fitsFile = null;
        try {
            fitsFile = new Fits(currentFile);
            if (isCompressedFits(fitsFile)) {
                throw new IOException("Compressed FITS files are not supported in batch mode: " + currentFile.getName());
            }

            BasicHDU<?> hdu = getImageHDU(fitsFile);
            if (hdu == null) {
                throw new FitsException("No valid image HDU found in " + currentFile.getName());
            }

            int[] axes = hdu.getAxes();
            if (axes == null || axes.length != 2) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + currentFile.getName() + " has axes length " + (axes == null ? 0 : axes.length) + ".");
            }

            Header fitsHeader = hdu.getHeader();
            int bitpix = fitsHeader.getIntValue("BITPIX", 0);
            if (bitpix != 16) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + currentFile.getName() + " has BITPIX=" + bitpix + ".");
            }
            if (!FitsPixelConverter.isUnsigned16Bit(fitsHeader)) {
                throw new IOException("Expected unsigned 16-bit FITS files (BZERO=32768, BSCALE=1). File " + currentFile.getName()
                        + " has BZERO=" + headerNumber(fitsHeader.getDoubleValue("BZERO", 0.0))
                        + " and BSCALE=" + headerNumber(fitsHeader.getDoubleValue("BSCALE", 1.0))
                        + ", so read as they are its pixels would be at the wrong brightness level. Convert the folder first:"
                        + " import it in the SpacePixels window, which offers the conversion, or use the API with"
                        + " InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO.");
            }

            int height = axes[0];
            int width = axes[1];
            if (width <= 0 || height <= 0) {
                throw new FitsException("Invalid image dimensions in " + currentFile.getName() + ": " + width + "x" + height);
            }

            FitsFileInformation fileInfo = new FitsFileInformation(currentFile.getAbsolutePath(), currentFile.getName(), true, width, height);

            Cursor<String, HeaderCard> iter = fitsHeader.iterator();
            while (iter.hasNext()) {
                HeaderCard fitsHeaderCard = iter.next();
                fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
            }

            return new FitsMetadataLoadResult(fileInfo, width, height, bitpix);
        } finally {
            if (fitsFile != null) {
                fitsFile.close();
            }
        }
    }

    /** A header number without a trailing ".0" when it is whole. */
    private static String headerNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    /**
     * Ensures every headless input frame matches the reference frame in geometry and bit depth.
     */
    private void validateHeadlessFitsConsistency(FitsMetadataLoadResult reference, FitsMetadataLoadResult candidate) throws IOException {
        if (candidate.bitpix != reference.bitpix) {
            throw new IOException("Inconsistent FITS bit depth detected. File " + candidate.fileInfo.getFileName() +
                    " has BITPIX=" + candidate.bitpix + " but " + reference.fileInfo.getFileName() + " has BITPIX=" + reference.bitpix + ".");
        }

        if (candidate.width != reference.width || candidate.height != reference.height) {
            throw new IOException("Inconsistent FITS dimensions detected. File " + candidate.fileInfo.getFileName() +
                    " is " + candidate.width + "x" + candidate.height + " but " + reference.fileInfo.getFileName() +
                    " is " + reference.width + "x" + reference.height + ".");
        }
    }



    /**
     * Enumerates FITS-like files in the configured directory and returns them in stable path order.
     */
    private File[] getFitsFilesDetails() throws IOException, FitsException {
        File directory = alignedFitsFolderFullPath;
        if (!directory.isDirectory()) {
            throw new IOException("file:" + directory.getAbsolutePath() + " is not a directory");
        }

        FitsSequenceValidator.validateDirectoryExtensions(directory);
        File[] ret = directory.listFiles(file -> file.isFile()
                && FitsSequenceValidator.imageExtension(file.getName()) != null
                && !".xisf".equals(FitsSequenceValidator.imageExtension(file.getName())));
        if (ret == null) {
            throw new IOException("Could not list input directory: " + directory);
        }
        if (ret.length == 0) {
            throw new IOException("No fits files in directory:" + directory.getAbsolutePath());
        }

        Arrays.sort(ret, Comparator.comparing(File::getAbsolutePath));
        return ret;
    }

    /**
     * Closes a group of FITS handles that were opened as a batch.
     */
    private static void closeFitsFiles(Fits[] fitsFiles) throws IOException {
        for (Fits fitsFile : fitsFiles) {
            fitsFile.close();
        }
    }

    /**
     * Starts plate solving through either ASTAP or astrometry.net using the currently configured
     * external tooling.
     */
    public Future<PlateSolveResult> solve(String fitsFileFullPath, boolean astap, boolean astrometry) throws FitsException, IOException {
        return plateSolveService.solve(fitsFileFullPath, astap, astrometry);
    }

    /**
     * Copies WCS keywords from a reference FITS file into every file in the active directory and
     * optionally emits stretched derivatives alongside the solved outputs.
     */
    public void applyWCSHeader(String wcsHeaderFile, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();
        plateSolveService.applyWCSHeader(
                wcsHeaderFile,
                fitsFileInformation,
                (fitsFile, fits) -> writeUpdatedFITSFile(fitsFile, fits, stretchFactor, iterations, stretch, algo));
    }

    /**
     * Writes stretched derivatives for every FITS file in the active directory without modifying
     * headers or running any detection logic.
     */
    public void onlyStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = getFitsFilesDetails();
        for (int i = 0; i < fitsFileInformation.length; i++) {
            Fits fitsFile = new Fits(fitsFileInformation[i]);
            writeOnlyStretchedFitsFile(fitsFileInformation[i], fitsFile, stretchFactor, iterations, algo);
        }
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
        SpacePixelsAppConfigIO.write(appConfigFile, appConfig);
    }

    /**
     * Emits solved FITS derivatives for a single source file, including mono conversions and
     * optional stretched variants when requested.
     */
    private void writeUpdatedFITSFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = getImageHDU(originalFits).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        String newFNameSolved = addDirectory(fileInformation, "_solved");
        writeFitsWithSuffix(originalFits, newFNameSolved, "_wcs");

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
            String newFNameSolvedMono = addDirectory(fileInformation, "_solved_mono");
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMono, "_mono_wcs");
        }

        if (stretch) {
            String newFNameSolvedStretched = addDirectory(fileInformation, "_solved_stretched");
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_wcs_stretch");

            if (isColor) {
                String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_solved_mono_stretched");
                stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_wcs_stretch");
            }
        }
    }

    /**
     * Emits only stretched derivatives for a single FITS file, preserving both color and derived
     * mono outputs when applicable.
     */
    private void writeOnlyStretchedFitsFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = getImageHDU(originalFits).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
        }

        String newFNameSolvedStretched = addDirectory(fileInformation, "_stretched");
        stretchFITSImage(originalFits, stretchFactor, iterations, algo);
        writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_stretch");

        if (isColor) {
            String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_mono_stretched");
            stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_stretch");
        }
    }

    /**
     * Converts a color FITS container into a new mono FITS container while preserving the original
     * non-structural header metadata.
     */
    private Fits convertToMono(Fits colorFITSImage) throws FitsException, IOException {
        BasicHDU<?> originalHDU = getImageHDU(colorFITSImage);
        Object monoKernelData = FitsPixelConverter.extractLuminance(
                FitsPixelConverter.standardizeTo16BitColor(originalHDU.getKernel(), originalHDU.getHeader()));
        return FitsPixelConverter.createFitsFromData(monoKernelData, originalHDU.getHeader());
    }

    /**
     * Writes a FITS object to disk using SpacePixels' suffix-based naming convention.
     */
    private void writeFitsWithSuffix(Fits fitsImage, String fitsFilename, String suffix) throws IOException, FitsException {
        int lastSepPosition = fitsFilename.lastIndexOf(".");
        fitsFilename = fitsFilename.substring(0, lastSepPosition) + suffix + ".fit";
        File toDeleteFile = new File(fitsFilename);
        if (toDeleteFile.exists()) {
            toDeleteFile.delete();
        }
        fitsImage.write(new File(fitsFilename));
    }

    /**
     * Ensures a sibling output directory exists and returns the matching output path for a file.
     */
    private String addDirectory(File currentFile, String directory) throws IOException {
        String newDirectory = currentFile.getParent() + File.separator + directory;
        File newDirFile = new File(newDirectory);
        if (!newDirFile.exists()) {
            newDirFile.mkdirs();
        }
        return newDirFile.getAbsolutePath() + File.separator + currentFile.getName();
    }

// =========================================================================
    // 32-BIT TO 16-BIT STANDARDIZATION LOGIC
    // =========================================================================

    /**
     * Decompresses a sequence of compressed FITS files into a sibling directory and returns that
     * new directory for optional reload.
     */
    private File batchDecompress(File[] files) throws Exception {
        eu.startales.spacepixels.gui.ProcessingProgressDialog progressDialog =
                new eu.startales.spacepixels.gui.ProcessingProgressDialog(null);
        progressDialog.setTitle("Decompressing FITS files...");

        SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));

        File targetDir = null;
        try {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                final int percent = (int) (((float) i / files.length) * 100);
                SwingUtilities.invokeLater(() -> progressDialog.updateProgress(percent, "Decompressing " + file.getName() + "..."));

                Fits originalFits = new Fits(file);
                BasicHDU<?> imageHDU = getImageHDU(originalFits);

                if (imageHDU != null) {
                    Fits decompressedFits = new Fits();
                    decompressedFits.addHDU(imageHDU);

                    String outName = addDirectory(file, "_uncompressed");
                    if (outName.toLowerCase().endsWith(".fz")) {
                        outName = outName.substring(0, outName.length() - 3);
                    }

                    if (targetDir == null) targetDir = new File(outName).getParentFile();
                    File outFile = new File(outName);
                    if (outFile.exists()) outFile.delete();
                    decompressedFits.write(outFile);
                }
                originalFits.close();
            }
        } finally {
            SwingUtilities.invokeLater(() -> progressDialog.dispose());
        }
        return targetDir;
    }

    /**
     * Converts to standard 16-bit after asking how to store pixel values below zero, then offers to load the new
     * folder (the mono one for colour input). Always ends the current import.
     */
    private FitsFileInformation[] standardizeAndOfferToLoad(File[] files, boolean isColor) throws Exception {
        int addedOffset = chooseNegativeValueOffset(files);
        if (addedOffset < 0) {
            ApplicationWindow.logger.info("Conversion cancelled at the negative-value choice. Clearing the active import.");
            return new FitsFileInformation[0];
        }
        File newDir = batchStandardizeTo16Bit(files, isColor, addedOffset);

        int loadChoice = JOptionPane.showConfirmDialog(null,
                isColor ? "Conversion Complete!\n\nWould you like to automatically load the newly created 16-bit Mono directory to run detections?"
                        : "Conversion Complete!\n\nWould you like to automatically load the newly created 16-bit directory?",
                "Success", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (loadChoice == JOptionPane.YES_OPTION && newDir != null) {
            throw new RedirectImportException(newDir);
        }
        return new FitsFileInformation[0]; // Stop the current import
    }

    /**
     * Looks for pixels the conversion would put below zero and asks how to store them. Returns the ADU to add to every
     * pixel (0 when there are none or they are to be set to 0), or -1 when the user cancels.
     */
    private int chooseNegativeValueOffset(File[] files) throws Exception {
        eu.startales.spacepixels.gui.ProcessingProgressDialog progressDialog =
                new eu.startales.spacepixels.gui.ProcessingProgressDialog(null);
        progressDialog.setTitle("Checking pixel values...");
        SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));
        FitsPixelConverter.NegativeValueScan scan;
        try {
            scan = FitsPixelConverter.scanNegativeValues(files, percent -> SwingUtilities.invokeLater(
                    () -> progressDialog.updateProgress(percent, "Checking for pixel values below zero...")));
        } finally {
            SwingUtilities.invokeLater(progressDialog::dispose);
        }
        if (!scan.hasNegativeValues()) {
            return 0;
        }

        int offset = scan.offsetToKeepNegativeValues();
        String[] options = {"Add an Offset (Recommended)", "Set to 0", "Cancel"};
        int choice = JOptionPane.showOptionDialog(null, negativeValueQuestion(scan, offset), "Pixel Values Below Zero",
                JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        ApplicationWindow.logger.info(scan.negativePixels() + " of " + scan.totalPixels() + " pixels below zero, lowest "
                + scan.lowest() + "; choice: " + (choice == 0 ? "add " + offset + " ADU" : choice == 1 ? "set to 0" : "cancel"));
        return choice == 0 ? offset : choice == 1 ? 0 : -1;
    }

    /** The question {@link #chooseNegativeValueOffset} asks. */
    static String negativeValueQuestion(FitsPixelConverter.NegativeValueScan scan, int offset) {
        double percent = 100.0 * scan.negativePixels() / Math.max(1, scan.totalPixels());
        String share = percent >= 10 ? String.format(Locale.ROOT, "%.0f %%", percent)
                : percent >= 0.1 ? String.format(Locale.ROOT, "%.1f %%", percent) : "Less than 0.1 %";
        return dialogParagraphs(
                String.format(Locale.ROOT, "%s of the pixels are below zero (the lowest is %,.0f ADU). This usually means the "
                        + "frames were bias- or dark-subtracted without a pedestal. Standard 16-bit images cannot store values "
                        + "below zero.", share, scan.lowest()),
                String.format(Locale.ROOT, "<b>Add an Offset (recommended):</b> adds %,d ADU to every pixel of every frame. All "
                        + "values are kept, so the sky background and its noise stay intact. Padding left by registration stays "
                        + "at 0, and the report takes the offset off again for the sky brightness.", offset),
                "<b>Set to 0:</b> keeps the original levels, but every pixel below zero becomes 0. Where the sky is close to "
                        + "zero this cuts off the low side of the noise, so the background is measured too high and the noise "
                        + "too low. With the sky at zero, detection can fail.");
    }

    /**
     * Warns that the images have different exposure times and offers to import only the largest group, copied into a
     * subfolder because SpacePixels works on whole folders. Returns that subfolder, or null when the user cancels.
     */
    public static File offerToImportLargestExposureGroup(MixedExposureException mixed) throws IOException {
        ExposureGroup largest = mixed.getLargestGroup();
        String[] options = {"Import the " + largest.describe(), "Cancel"};
        int choice = JOptionPane.showOptionDialog(null, mixedExposureQuestion(mixed), "Different Exposure Times",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        ApplicationWindow.logger.info(mixed.getMessage() + " Choice: " + (choice == 0 ? "import the largest group" : "cancel"));
        if (choice != 0) {
            return null;
        }

        eu.startales.spacepixels.gui.ProcessingProgressDialog progressDialog =
                new eu.startales.spacepixels.gui.ProcessingProgressDialog(null);
        progressDialog.setTitle("Copying images...");
        SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));
        try {
            return largest.copyIntoSubfolder(percent -> SwingUtilities.invokeLater(
                    () -> progressDialog.updateProgress(percent, "Copying the " + largest.describe() + "...")));
        } finally {
            SwingUtilities.invokeLater(progressDialog::dispose);
        }
    }

    /** The warning {@link #offerToImportLargestExposureGroup} shows. */
    static String mixedExposureQuestion(MixedExposureException mixed) {
        ExposureGroup largest = mixed.getLargestGroup();
        StringBuilder groups = new StringBuilder();
        for (String group : MixedExposureException.describeGroups(mixed.getGroups())) {
            groups.append("<br>&bull; ").append(htmlText(group));
        }
        return dialogParagraphs(
                "These " + mixed.getImageCount() + " images do not all have the same exposure time:" + groups,
                "SpacePixels needs one exposure time for the whole sequence: the brightness of the stars and the sky, and "
                        + "the length of trails, change with the exposure, and the detection compares the frames with each other.",
                "Would you like to import only the " + largest.describe() + "? They are copied into the subfolder <b>"
                        + htmlText(largest.getFolderName()) + "</b>, and the original folder stays as it is.");
    }

    private static String htmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A dialog message of wrapped paragraphs (Swing's own wrapping of plain text drops characters). */
    private static String dialogParagraphs(String... paragraphs) {
        StringBuilder html = new StringBuilder("<html><body style='width: 430px'>");
        for (int i = 0; i < paragraphs.length; i++) {
            html.append(i == 0 ? "<p>" : "<p style='margin-top: 8px'>").append(paragraphs[i]).append("</p>");
        }
        return html.append("</body></html>").toString();
    }

    /**
     * Standardizes 32-bit, signed or scaled 16-bit FITS inputs into unsigned 16-bit outputs, optionally preserving
     * color frames and extracting luminance for detection workflows. {@code addedOffset} ADU are added to every pixel
     * except registration padding, so values below zero are kept.
     */
    private File batchStandardizeTo16Bit(File[] files, boolean isColor, int addedOffset) throws Exception {
        eu.startales.spacepixels.gui.ProcessingProgressDialog progressDialog =
                new eu.startales.spacepixels.gui.ProcessingProgressDialog(null);
        progressDialog.setTitle("Converting to standard 16-bit...");

        SwingUtilities.invokeLater(() -> progressDialog.setVisible(true));

        File targetDir = null;
        try {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                final int percent = (int) (((float) i / files.length) * 100);
                SwingUtilities.invokeLater(() -> progressDialog.updateProgress(percent, "Converting " + file.getName() + "..."));

                ApplicationWindow.logger.info("Converting file to standard 16-bit: " + file.getName());

                // --- DEBUG: Print Original File ---
                printFitsDebugInfo("ORIGINAL", file);

                Fits originalFits = new Fits(file);
                BasicHDU<?> imageHDU = getImageHDU(originalFits);
                Header origHeader = imageHDU.getHeader();
                Object kernel = imageHDU.getKernel();

                if (isColor) {
                    // 1. Convert to 16-bit Color
                    short[][][] color16 = FitsPixelConverter.standardizeTo16BitColor(kernel, origHeader, addedOffset);
                    Fits colorFits = FitsPixelConverter.createFitsFromData(color16, origHeader, addedOffset);
                    String colorName = addDirectory(file, "_16bit_color");
                    writeFitsWithSuffix(colorFits, colorName, "_16bit_color");

                    // --- DEBUG: Print Converted Color File ---
                    String finalColorPath = colorName.substring(0, colorName.lastIndexOf(".")) + "_16bit_color.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT COLOR", new File(finalColorPath));

                    // 2. Extract Luminance to 16-bit Mono
                    short[][] mono16 = FitsPixelConverter.extractLuminance(color16);
                    Fits monoFits = FitsPixelConverter.createFitsFromData(mono16, origHeader, addedOffset);

                    // Note: No manual header hacking needed here anymore!
                    // createFitsFromData safely handles NAXIS and NAXIS3 automatically.
                    String monoName = addDirectory(file, "_16bit_mono");
                    if (targetDir == null) targetDir = new File(monoName).getParentFile();
                    writeFitsWithSuffix(monoFits, monoName, "_16bit_mono");

                    // --- DEBUG: Print Converted Luminance File ---
                    String finalLuminancePath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit_mono.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT LUMINANCE", new File(finalLuminancePath));

                } else {
                    // Convert to 16-bit Mono
                    short[][] mono16 = FitsPixelConverter.standardizeTo16BitMono(kernel, origHeader, addedOffset);
                    Fits monoFits = FitsPixelConverter.createFitsFromData(mono16, origHeader, addedOffset);
                    String monoName = addDirectory(file, "_16bit_converted");
                    if (targetDir == null) targetDir = new File(monoName).getParentFile();
                    writeFitsWithSuffix(monoFits, monoName, "_16bit");

                    // --- DEBUG: Print Converted Mono File ---
                    String finalMonoPath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT MONO", new File(finalMonoPath));
                }
                originalFits.close();
            }
        } finally {
            SwingUtilities.invokeLater(() -> progressDialog.dispose());
        }
        return targetDir;
    }

// =========================================================================
    // DEBUG: FITS VERIFICATION
    // =========================================================================

    /**
     * Dumps a compact debug summary of a FITS file's stored and display-scaled numeric range.
     */
    private void printFitsDebugInfo(String label, File fitsFile) {
        if (!DebugOutput.isEnabled()) {
            return;
        }
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = getImageHDU(fits);
            Header header = hdu.getHeader();
            Object kernel = hdu.getKernel();

            int bitpix = header.getIntValue("BITPIX", 0);
            double bzero = header.getDoubleValue("BZERO", 0.0);
            double bscale = header.getDoubleValue("BSCALE", 1.0);

            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;

            // Scan 2D arrays (Mono)
            if (kernel instanceof float[][]) {
                float[][] data = (float[][]) kernel;
                for (float[] row : data) {
                    for (float val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof short[][]) {
                short[][] data = (short[][]) kernel;
                for (short[] row : data) {
                    for (short val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof int[][]) {
                int[][] data = (int[][]) kernel;
                for (int[] row : data) {
                    for (int val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            }

            System.out.println("\n--- DEBUG: " + label + " ---");
            System.out.println("File:   " + fitsFile.getName());
            System.out.println("BITPIX: " + bitpix);
            System.out.println("BZERO:  " + bzero);
            System.out.println("BSCALE: " + bscale);
            if (min != Double.MAX_VALUE) {
                System.out.println("Raw Min Value: " + min);
                System.out.println("Raw Max Value: " + max);
                // Calculate what the true value is after BZERO/BSCALE are applied by a viewer
                System.out.println("True Visual Min: " + ((min * bscale) + bzero));
                System.out.println("True Visual Max: " + ((max * bscale) + bzero));
            } else {
                System.out.println("(3D Color array, skipping min/max scan for brevity)");
            }
            System.out.println("-----------------------------------");

        } catch (Exception e) {
            System.err.println("Debug read failed for " + fitsFile.getName() + ": " + e.getMessage());
        }
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
     * Creates a timestamped sibling directory for detection exports and returns its location.
     */
    public static File createDetectionsDirectory(File anyFitsFile) {
        File parentDir = anyFitsFile.getParentFile();
        if (parentDir == null) {
            parentDir = new File(System.getProperty("user.dir"));
        }

        // Generate a sortable timestamp
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
        String timestamp = java.time.LocalDateTime.now().format(formatter);

        // Create the uniquely named detections directory
        File detectionsDir = new File(parentDir, "detections_" + timestamp);

        if (!detectionsDir.exists()) {
            boolean created = detectionsDir.mkdirs();
            if (created) {
                DebugOutput.println("Created new export directory at: " + detectionsDir.getAbsolutePath());
            } else {
                System.err.println("Failed to create export directory. Check folder permissions.");
            }
        }

        return detectionsDir;
    }
}
