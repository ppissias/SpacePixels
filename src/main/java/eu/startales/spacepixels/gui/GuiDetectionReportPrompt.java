package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.ImageProcessing;
import eu.startales.spacepixels.util.ReportInclusion;
import eu.startales.spacepixels.util.reporting.FindingCategory;
import io.github.ppissias.jtransient.config.DetectionConfig;

import javax.swing.*;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;

import static eu.startales.spacepixels.util.ImageProcessing.DetectionSummary.offerInReport;

/** Desktop-only report confirmation; workers receive callbacks rather than depending on Swing. */
public final class GuiDetectionReportPrompt {
    private static final Logger LOGGER = Logger.getLogger(GuiDetectionReportPrompt.class.getName());
    private final ToIntFunction<JComponent> confirmation;

    /** Attaches modal report questions to the initiating window. */
    public GuiDetectionReportPrompt(Component parent) {
        this(panel -> JOptionPane.showConfirmDialog(parent, panel, "Detection summary",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE));
    }

    /** Supplies the modal answer separately so EDT dispatch and choices can be tested without a window. */
    GuiDetectionReportPrompt(ToIntFunction<JComponent> confirmation) {
        this.confirmation = java.util.Objects.requireNonNull(confirmation);
    }

    /**
     * Confirms counts above the limit on the EDT and applies accepted report-only choices.
     * Declines on dismissal, interruption or a dialog failure; counts at the limit proceed without UI.
     */
    public boolean confirm(ImageProcessing.DetectionSummary summary, boolean plateSolved,
                           int limit, String heading, DetectionConfig config) {
        if (Thread.currentThread().isInterrupted()) return false;
        if (summary.totalDetections <= limit) return true;
        FutureTask<ReportInclusion> question = new FutureTask<>(() -> {
            ReportChoice choice = warningPanel(summary, plateSolved, heading, config);
            return confirmation.applyAsInt(choice.panel) == JOptionPane.YES_OPTION ? choice.inclusion() : null;
        });
        if (SwingUtilities.isEventDispatchThread()) question.run(); else SwingUtilities.invokeLater(question);
        try {
            ReportInclusion inclusion = question.get();
            if (inclusion == null) return false;
            summary.includeInReport(inclusion);
            return true;
        } catch (InterruptedException interrupted) {
            question.cancel(false);
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException failure) {
            LOGGER.log(Level.WARNING, "Could not ask whether to generate the report.", failure.getCause());
            return false;
        }
    }

    /** Builds the existing report choices on the EDT without changing detection settings. */
    ReportChoice warningPanel(ImageProcessing.DetectionSummary summary, boolean plateSolved, String heading, DetectionConfig config) {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 4));

        String title = (heading == null || heading.isBlank() ? "" : heading + " — ")
                + "This run found " + summary.totalDetections + " detections.";
        panel.add(aligned(label(title, true)));
        panel.add(Box.createVerticalStrut(10));

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        grid.setAlignmentX(Component.LEFT_ALIGNMENT);
        boolean anomaliesOn = FindingCategory.ANOMALY.computed(config, summary);
        boolean slowMoversOn = FindingCategory.SLOW_MOVER.computed(config, summary);
        boolean localRescueOn = FindingCategory.LOCAL_RESCUE.computed(config, summary);
        boolean localActivityOn = FindingCategory.LOCAL_ACTIVITY.computed(config, summary);
        boolean commonMotionOn = FindingCategory.COMMON_MOTION.computed(config, summary);
        boolean cameraFixedOn = FindingCategory.CAMERA_FIXED.computed(config, summary);
        boolean variableStarsOn = FindingCategory.VARIABLE_STAR.computed(config, summary);
        OptionalRow[] rows = {
                OptionalRow.category(FindingCategory.MOVING_TARGET, config, summary),
                OptionalRow.category(FindingCategory.STREAK_TRACK, config, summary),
                OptionalRow.category(FindingCategory.SINGLE_STREAK, config, summary),
                OptionalRow.category(FindingCategory.ANOMALY, config, summary),
                OptionalRow.category(FindingCategory.SLOW_MOVER, config, summary),
                OptionalRow.category(FindingCategory.LOCAL_RESCUE, config, summary),
                OptionalRow.category(FindingCategory.LOCAL_ACTIVITY, config, summary),
                OptionalRow.category(FindingCategory.COMMON_MOTION, config, summary),
                OptionalRow.category(FindingCategory.CAMERA_FIXED, config, summary)
        };
        JCheckBox anomaliesBox = null;
        JCheckBox slowMoversBox = null;
        JCheckBox localRescueBox = null;
        JCheckBox localActivityBox = null;
        JCheckBox commonMotionBox = null;
        JCheckBox cameraFixedBox = null;
        JCheckBox[] boxes = new JCheckBox[rows.length];
        if (offerInReport(anomaliesOn, summary.anomalies)) {
            anomaliesBox = includeBox();
            boxes[3] = anomaliesBox;
        }
        if (offerInReport(slowMoversOn, summary.slowMoverCandidates)) {
            slowMoversBox = includeBox();
            boxes[4] = slowMoversBox;
        }
        if (offerInReport(localRescueOn, summary.localRescueCandidates)) {
            localRescueBox = includeBox();
            boxes[5] = localRescueBox;
        }
        if (offerInReport(localActivityOn, summary.localActivityClusters)) {
            localActivityBox = includeBox();
            boxes[6] = localActivityBox;
        }
        if (offerInReport(commonMotionOn, summary.commonMotionGroups)) {
            commonMotionBox = includeBox();
            boxes[7] = commonMotionBox;
        }
        if (offerInReport(cameraFixedOn, summary.cameraFixedGroups)) {
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
        for (int index = 0; index < rows.length; index++) {
            if (rows[index] == null) {
                continue;
            }
            name.gridy = row;
            value.gridy = row;
            choice.gridy = row;
            boolean zero = rows[index].count == 0;
            JLabel nameLabel = new JLabel(rows[index].label);
            JLabel valueLabel = new JLabel(rows[index].value);
            valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD));
            if (zero && quiet != null) {
                nameLabel.setForeground(quiet);
                valueLabel.setForeground(quiet);
            }
            grid.add(nameLabel, name);
            grid.add(valueLabel, value);
            if (boxes[index] != null) {
                grid.add(boxes[index], choice);
                anyChoice = true;
            }
            row++;
        }
        if (variableStarsOn) {
            name.gridy = row;
            value.gridy = row;
            choice.gridy = row;
            grid.add(new JLabel(FindingCategory.VARIABLE_STAR.summaryLabel()), name);
            grid.add(new JLabel(photometryLine(summary)), value);
            if (variableStarsBox != null) {
                grid.add(variableStarsBox, choice);
                anyChoice = true;
            }
        }
        panel.add(grid);
        JCheckBox[] watched = {anomaliesBox, slowMoversBox, localRescueBox, localActivityBox, commonMotionBox, cameraFixedBox};
        Runnable refreshIncluded = () -> included.setText(includedLine(summary, watched));
        for (JCheckBox box : watched) {
            if (box != null) {
                box.addItemListener(event -> refreshIncluded.run());
            }
        }
        refreshIncluded.run();
        panel.add(Box.createVerticalStrut(8));
        panel.add(aligned(wrapped("Still unclassified, not counted above: " + summary.unclassifiedDetections
                + ". The inspector lists them.")));
        panel.add(Box.createVerticalStrut(6));
        panel.add(aligned(wrapped("Astrometry: " + (plateSolved
                ? "a plate-solved frame is available, so the report has sky coordinates and online identification."
                : "no plate-solved frame, so the report has no sky coordinates or identification."))));
        panel.add(Box.createVerticalStrut(8));
        panel.add(aligned(wrapped("Largest group: " + summary.largestGroup() + ". "
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

    /** Counts classified results still selected for export; photometry is reported separately. */
    private static String includedLine(ImageProcessing.DetectionSummary summary, JCheckBox[] boxes) {
        int included = FindingCategory.MOVING_TARGET.count(summary) + FindingCategory.STREAK_TRACK.count(summary)
                + FindingCategory.SINGLE_STREAK.count(summary);
        FindingCategory[] optional = {FindingCategory.ANOMALY, FindingCategory.SLOW_MOVER, FindingCategory.LOCAL_RESCUE,
                FindingCategory.LOCAL_ACTIVITY, FindingCategory.COMMON_MOTION, FindingCategory.CAMERA_FIXED};
        for (int index = 0; index < optional.length; index++) {
            if (boxes[index] == null || boxes[index].isSelected()) included += optional[index].count(summary);
        }
        return "The report will include " + included + " of these detections.";
    }

    /** Summarizes the run's photometry readiness and variable-star counts. */
    private static String photometryLine(ImageProcessing.DetectionSummary summary) {
        if ("NOT_READY".equals(summary.photometryVerdict)) {
            return "session not ready for photometry";
        }
        return summary.variableStarCandidates + " candidates, " + summary.possibleVariableStars + " possible";
    }

    /** Creates an initially included optional report group. */
    private static JCheckBox includeBox() {
        JCheckBox box = new JCheckBox("In report");
        box.setSelected(true);
        box.setOpaque(false);
        return box;
    }

    /** Creates a dialog label with optional emphasis. */
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
    private static int wrappedHeight(java.awt.FontMetrics metrics, String text, int width) {
        int height = 0;
        int lineHeight = metrics.getHeight();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                height += lineHeight;
                continue;
            }
            int lineWidth = 0;
            int lines = 1;
            for (String word : paragraph.split(" ")) {
                int wordWidth = metrics.stringWidth(word + " ");
                if (lineWidth > 0 && lineWidth + wordWidth > width) {
                    lines++;
                    lineWidth = wordWidth;
                } else {
                    lineWidth += wordWidth;
                }
            }
            height += lines * lineHeight;
        }
        return height + metrics.getDescent();
    }

    /** Left-aligns one component in the summary's vertical layout. */
    private static JComponent aligned(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }

    /** One line of the warning. Null when that part of the detection was switched off. */
    private static final class OptionalRow {
        final String label;
        final String value;
        final int count;

        /** Stores one enabled result category and its measured count. */
        private OptionalRow(String label, String value, int count) {
            this.label = label;
            this.value = value;
            this.count = count;
        }

        /** Builds an enabled category row from the same count/label policy as the exported report. */
        static OptionalRow category(FindingCategory category, DetectionConfig config, ImageProcessing.DetectionSummary summary) {
            int count = category.count(summary);
            return category.computed(config, summary) ? new OptionalRow(category.summaryLabel(), Integer.toString(count), count) : null;
        }
    }

    /** The warning panel and the include boxes, read after the dialog closes. */
    static final class ReportChoice {
        final JComponent panel;
        final JCheckBox anomalies;
        final JCheckBox slowMovers;
        final JCheckBox localRescue;
        final JCheckBox localActivity;
        final JCheckBox commonMotion;
        final JCheckBox cameraFixed;
        final JCheckBox variableStars;

        /** Keeps the displayed controls until the user answers the dialog. */
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

        /** Reads report-only choices; absent controls retain their default inclusion. */
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

        /** Treats an unavailable choice as included for compatibility. */
        private static boolean selected(JCheckBox box) {
            return box == null || box.isSelected();
        }
    }

}
