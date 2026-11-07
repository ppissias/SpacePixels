package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.QualityControlSnapshot;
import io.github.ppissias.jtransient.engine.FrameQualityResult;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;

/**
 * Presents measured evidence and the engine's exact session limits without adding quality rules in the GUI: a value
 * and its limit stand side by side, and only the engine's verdict and reason say whether a frame passes.
 */
final class QualityControlDetails {
    /** Logical width the text wraps at, and so the width the dialog opens with. */
    private static final int CONTENT_WIDTH = 600;
    private static final String UNAVAILABLE = "Unavailable";
    private static final String NO_LIMIT = "—";

    /** Holds static helpers only. */
    private QualityControlDetails() { }

    /** Opens the readout of the original selected file, modal on the EDT; Copy details gives the same facts as text. */
    static void show(Component owner, QualityControlSnapshot snapshot, FrameQualityResult.Frame frame, boolean current) {
        Window window = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Quality details — " + frame.filename(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        AppIcon.apply(dialog);

        JButton copy = new JButton("Copy details");
        copy.setToolTipText("Copies the measurements and session limits as text.");
        copy.addActionListener(event -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(describe(snapshot, frame, current)), null);
            copy.setText("Copied");
        });
        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(8), UiStyle.space(16), UiStyle.space(12), UiStyle.space(16)));
        buttons.add(copy, BorderLayout.WEST);
        buttons.add(close, BorderLayout.EAST);

        WidthTrackingPanel form = new WidthTrackingPanel();
        form.add(content(snapshot, frame, current), BorderLayout.CENTER);
        JScrollPane scroll = WindowSizing.form(form);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        dialog.add(scroll, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        WindowSizing.install(dialog, 480, 320);
        dialog.pack();
        Rectangle usable = WindowSizing.usableBounds(dialog);
        dialog.setSize(Math.min(dialog.getWidth(), usable.width), Math.min(dialog.getHeight(), usable.height));
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** Builds the readout on the EDT: the verdict, the session's counts, then the measurements beside their limits. */
    static JPanel content(QualityControlSnapshot snapshot, FrameQualityResult.Frame frame, boolean current) {
        var result = snapshot.result();
        var limits = result.thresholds();
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(14), UiStyle.space(16), UiStyle.space(4), UiStyle.space(16)));
        body.getAccessibleContext().setAccessibleName("Frame quality measurements and session limits");
        if (!current) {
            add(body, band(warningColour(), "<b>Stale result.</b> Inputs or detection settings changed. Rerun quality control."), 10);
        }
        add(body, verdict(frame, result.frames().size(), current), 10);
        add(body, tiles(tile("Frames assessed", String.valueOf(result.frames().size())),
                tile("Would keep", String.valueOf(result.keptFrames())),
                tile("Would reject", String.valueOf(result.rejectedFrames()))), 6);
        if (result.tooFewFrames()) {
            add(body, band(warningColour(), "<b>Detection would stop:</b> fewer than 3 frames remain after quality control."), 6);
        }
        add(body, note("No files or selections have been removed."), 0);

        add(body, section("Measurements"), 0);
        add(body, measurements(frame, limits), 4);
        if (!limits.available()) {
            add(body, note("Statistical session thresholds unavailable: too few frames for the configured baseline. "
                    + "Registration and coverage exclusions still apply."), 0);
        }

        add(body, section("Common image area"), 0);
        add(body, area(result), 0);

        add(body, section("Registration padding fit"), 0);
        var fit = frame.registration();
        if (fit == null) {
            add(body, note("Unavailable (registration failed)."), 4);
        } else {
            add(body, tiles(tile("Shift X / Y", number(fit.shiftX(), 2, "") + " / " + number(fit.shiftY(), 2, " px")),
                    tile("Rotation", number(Math.toDegrees(fit.rotationRadians()), 3, "°")),
                    tile("Edge-fit RMS", number(fit.edgeFitRmsPixels(), 2, " px")),
                    tile("Camera mapping", fit.usable() ? "Usable" : "Not usable")), 6);
        }
        add(body, note("This fit describes registration-padding edges, not residual stellar alignment. "
                + "A zero without padding does not prove perfect alignment."), 8);
        return body;
    }

    /** Adds one left-aligned block with a scaled gap below it. */
    private static void add(JPanel body, JComponent block, int gapBelow) {
        block.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(block);
        if (gapBelow > 0) body.add(Box.createVerticalStrut(UiStyle.space(gapBelow)));
    }

    /** The engine's verdict and reason, tinted like the frame's table row; a stale result keeps the neutral colour. */
    private static JComponent verdict(FrameQualityResult.Frame frame, int frames, boolean current) {
        Color colour = frame.rejected() ? QualityControlTable.REJECT_COLOUR : QualityControlTable.KEEP_COLOUR;
        JPanel card = card(current ? tint(colour, 22) : tint(colour, 0));
        card.setLayout(new BorderLayout(UiStyle.space(10), 0));
        JLabel dot = new JLabel("●");
        dot.setForeground(colour);
        dot.setFont(UiStyle.sectionFont());
        dot.setVerticalAlignment(SwingConstants.TOP);
        card.add(dot, BorderLayout.WEST);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(frame.rejected() ? "Would reject" : "Would keep");
        title.setFont(UiStyle.sectionFont());
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(title);
        WrappingHelpLabel reason = new WrappingHelpLabel(escape(frame.reason()), CONTENT_WIDTH - 80);
        reason.setForeground(foreground());
        text.add(reason);
        WrappingHelpLabel name = new WrappingHelpLabel("Frame " + (frame.sequenceIndex() + 1) + " of " + frames + " · "
                + escape(frame.filename()), CONTENT_WIDTH - 80);
        text.add(name);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    /** One grid of measure, this frame's value and the session limit, with the evidence each is based on underneath. */
    private static JComponent measurements(FrameQualityResult.Frame frame, FrameQualityResult.Thresholds limits) {
        JPanel grid = new JPanel(new GridBagLayout());
        heading(grid, 1, "This frame");
        heading(grid, 2, "Session limit");
        int row = 1;
        row = measurement(grid, row, "Stars", null, number(frame.starCount(), 0, ""), null,
                limits.available() ? "at least " + number(limits.minimumStarCount(), 1, "") : NO_LIMIT, null);
        row = measurement(grid, row, "FWHM", frame.fwhmStarCount() + " reference stars",
                available(frame.medianFwhm(), frame.fwhmStarCount() > 0, 2, " px"), null,
                limits.available() ? "at most " + number(limits.maximumFwhm(), 2, " px") : NO_LIMIT, null);
        row = measurement(grid, row, "Elongation", "Major/minor axis · " + frame.usableShapeStarCount() + " usable stars",
                available(frame.medianElongation(), frame.usableShapeStarCount() > 0, 3, ""), null,
                limits.available() ? "at most " + number(limits.maximumElongation(), 3, "") : NO_LIMIT, null);
        row = measurement(grid, row, "Bright-star elongation", frame.brightStarShapeStarCount() + " bright reference stars",
                available(frame.brightStarMedianElongation(), frame.brightStarShapeStarCount() > 0, 3, ""), null,
                limits.available() ? "at most " + number(limits.maximumBrightStarElongation(), 3, "") : NO_LIMIT, null);
        row = measurement(grid, row, "Sky median", null, number(frame.backgroundMedian(), 1, " ADU"), null,
                limits.available() ? number(limits.minimumBackground(), 1, "") + " to " + number(limits.maximumBackground(), 1, " ADU") : NO_LIMIT,
                limits.available() ? "Baseline " + number(limits.backgroundBaseline(), 1, "") + " ± " + number(limits.maximumBackgroundDeviation(), 1, "") : null);
        measurement(grid, row, "Background noise", null, number(frame.backgroundNoise(), 1, " ADU"), null, NO_LIMIT, null);
        return grid;
    }

    /** A column heading of the measurements grid. */
    private static void heading(JPanel grid, int column, String text) {
        JLabel label = new JLabel(text, SwingConstants.RIGHT);
        label.setFont(smallFont().deriveFont(Font.BOLD));
        label.setForeground(UiStyle.helpColor());
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = column;
        c.gridy = 0;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(0, UiStyle.space(16), UiStyle.space(4), 0);
        grid.add(label, c);
    }

    /** Adds a separator and one measurement to the grid, and returns the next free grid row. */
    private static int measurement(JPanel grid, int row, String name, String nameDetail, String value, String valueDetail,
                                   String limit, String limitDetail) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.gridwidth = 3;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        grid.add(new JSeparator(), c);
        c = new GridBagConstraints();
        c.gridy = row + 1;
        c.gridx = 0;
        c.weightx = 1;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(UiStyle.space(5), 0, UiStyle.space(5), 0);
        grid.add(cell(name, false, foreground(), nameDetail, SwingConstants.LEFT), c);
        c.weightx = 0;
        c.gridx = 1;
        c.anchor = GridBagConstraints.NORTHEAST;
        c.insets = new Insets(UiStyle.space(5), UiStyle.space(16), UiStyle.space(5), 0);
        boolean missing = UNAVAILABLE.equals(value);
        grid.add(cell(value, !missing, missing ? UiStyle.helpColor() : foreground(), valueDetail, SwingConstants.RIGHT), c);
        c.gridx = 2;
        grid.add(cell(limit, false, UiStyle.helpColor(), limitDetail, SwingConstants.RIGHT), c);
        return row + 2;
    }

    /** A grid cell: its text, with an optional smaller line of evidence underneath. */
    private static JComponent cell(String text, boolean bold, Color colour, String detail, int alignment) {
        JLabel label = new JLabel(text, alignment);
        label.setForeground(colour);
        if (bold) label.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        if (detail == null) return label;
        JLabel below = new JLabel(detail, alignment);
        below.setFont(smallFont());
        below.setForeground(UiStyle.helpColor());
        JPanel panel = new JPanel(new GridLayout(2, 1, 0, 0));
        panel.setOpaque(false);
        panel.add(label);
        panel.add(below);
        return panel;
    }

    /** The two common-area shares, each with a thin bar; an unusable share shows no bar. */
    private static JComponent area(FrameQualityResult result) {
        JPanel grid = new JPanel(new GridBagLayout());
        areaRow(grid, 0, "Used to measure quality", result.qualityAreaFraction());
        areaRow(grid, 1, "After rejection", result.retainedAreaFraction());
        return grid;
    }

    /** One line of the common-area grid. */
    private static void areaRow(JPanel grid, int row, String name, double fraction) {
        boolean usable = Double.isFinite(fraction);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(UiStyle.space(3), 0, UiStyle.space(3), UiStyle.space(12));
        grid.add(new JLabel(name), c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setValue(usable ? (int) Math.round(fraction * 1000) : 0);
        bar.setVisible(usable);
        bar.setPreferredSize(new Dimension(UiStyle.space(160), UiStyle.space(6)));
        bar.getAccessibleContext().setAccessibleName(name);
        grid.add(bar, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.EAST;
        c.insets = new Insets(UiStyle.space(3), 0, UiStyle.space(3), 0);
        JLabel value = new JLabel(usable ? number(fraction * 100, 1, " %") : UNAVAILABLE);
        if (usable) value.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        grid.add(value, c);
    }

    /** Equal tiles in a row. */
    private static JComponent tiles(JComponent... tiles) {
        JPanel row = new JPanel(new GridLayout(1, tiles.length, UiStyle.space(8), 0));
        for (JComponent tile : tiles) row.add(tile);
        return row;
    }

    /** A value in bold under its small caption, in a slightly raised card. */
    private static JComponent tile(String caption, String value) {
        JPanel card = card(tint(foreground(), 6));
        card.setLayout(new GridLayout(2, 1, 0, UiStyle.space(2)));
        JLabel name = new JLabel(caption);
        name.setFont(smallFont());
        name.setForeground(UiStyle.helpColor());
        JLabel shown = new JLabel(value);
        shown.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        card.add(name);
        card.add(shown);
        return card;
    }

    /** A notice in a card tinted with this colour. */
    private static JComponent band(Color colour, String html) {
        JPanel card = card(tint(colour, 24));
        card.setLayout(new BorderLayout());
        WrappingHelpLabel text = new WrappingHelpLabel(html, CONTENT_WIDTH - 60);
        text.setForeground(foreground());
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    /** A rounded panel with this background and comfortable padding. */
    private static JPanel card(Color background) {
        JPanel card = new JPanel();
        card.setBackground(background);
        card.putClientProperty("FlatLaf.style", "arc: 10");
        card.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(7), UiStyle.space(10), UiStyle.space(7), UiStyle.space(10)));
        return card;
    }

    /** A section title, in the accent colour of the other settings headers. */
    private static JComponent section(String title) {
        JLabel label = new JLabel(title);
        label.setFont(UiStyle.sectionFont());
        label.setForeground(DetectionConfigurationPanel.accentColor());
        label.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(12), 0, UiStyle.space(6), 0));
        return label;
    }

    /** Wrapped explanatory text in the help colour. */
    private static JComponent note(String text) {
        return new WrappingHelpLabel(escape(text), CONTENT_WIDTH);
    }

    /** The panel background of the theme with this percentage of a colour. */
    private static Color tint(Color colour, int percent) {
        Color base = UIManager.getColor("Panel.background");
        return QualityControlTable.blend(base == null ? Color.DARK_GRAY : base, colour, percent);
    }

    /** The amber of the application's other warnings. */
    private static Color warningColour() {
        Color colour = UIManager.getColor("Actions.Yellow");
        return colour == null ? new Color(0xE0A030) : colour;
    }

    /** The theme's normal text colour. */
    private static Color foreground() {
        Color colour = UIManager.getColor("Label.foreground");
        return colour == null ? Color.LIGHT_GRAY : colour;
    }

    /** The body font one step smaller, for captions and evidence. */
    private static Font smallFont() {
        return UiStyle.bodyFont().deriveFont(Math.max(9f, UiStyle.bodyFont().getSize2D() - 1));
    }

    /** Keeps text from the engine or a file name literal inside an HTML label. */
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** A rounded number in fixed units, with no numeric substitute for an absent measurement. */
    private static String number(double value, int decimals, String unit) {
        return Double.isFinite(value) ? String.format(Locale.US, "%,." + decimals + "f", value) + unit : UNAVAILABLE;
    }

    /** As {@link #number}, for a measurement that has no evidence behind it when {@code measured} is false. */
    private static String available(double value, boolean measured, int decimals, String unit) {
        return measured ? number(value, decimals, unit) : UNAVAILABLE;
    }

    /** The readout as plain text, for Copy details. Missing evidence is explicit; all thresholds come from the engine. */
    static String describe(QualityControlSnapshot snapshot, FrameQualityResult.Frame frame, boolean current) {
        var result = snapshot.result();
        var limits = result.thresholds();
        StringBuilder text = new StringBuilder();
        if (!current) text.append("STALE RESULT — inputs or detection settings changed. Rerun quality control.\n\n");
        text.append(frame.rejected() ? "Would reject: " : "Would keep: ").append(frame.reason()).append("\n")
                .append("All ").append(result.frames().size()).append(" imported frames assessed: ")
                .append(result.keptFrames()).append(" would keep; ").append(result.rejectedFrames()).append(" would reject.\n");
        if (result.tooFewFrames()) text.append("Detection would stop: fewer than 3 frames remain after quality control.\n");
        text.append("No files or selections have been removed.\n\nMEASUREMENTS\n")
                .append("FWHM: ").append(value(frame.medianFwhm(), frame.fwhmStarCount() > 0)).append(" px (")
                .append(frame.fwhmStarCount()).append(" reference stars)\n")
                .append("Stars: ").append(frame.starCount()).append("\n")
                .append("Elongation (major/minor axis): ").append(value(frame.medianElongation(), frame.usableShapeStarCount() > 0))
                .append(" (").append(frame.usableShapeStarCount()).append(" usable stars)\n")
                .append("Bright-star elongation: ").append(value(frame.brightStarMedianElongation(), frame.brightStarShapeStarCount() > 0))
                .append(" (").append(frame.brightStarShapeStarCount()).append(" bright reference stars)\n")
                .append("Sky median: ").append(value(frame.backgroundMedian(), true)).append(" ADU\n")
                .append("Background noise: ").append(value(frame.backgroundNoise(), true)).append(" ADU\n\nSESSION LIMITS\n");
        if (!limits.available()) text.append("Statistical session thresholds unavailable: too few frames for the configured baseline.\n")
                .append("Registration and coverage exclusions still apply.\n");
        else text.append("Minimum star count: ").append(value(limits.minimumStarCount(), true)).append("\n")
                .append("Maximum FWHM: ").append(value(limits.maximumFwhm(), true)).append(" px\n")
                .append("Maximum elongation: ").append(value(limits.maximumElongation(), true)).append("\n")
                .append("Maximum bright-star elongation: ").append(value(limits.maximumBrightStarElongation(), true)).append("\n")
                .append("Sky baseline: ").append(value(limits.backgroundBaseline(), true)).append(" ADU\n")
                .append("Maximum sky deviation: ").append(value(limits.maximumBackgroundDeviation(), true)).append(" ADU\n")
                .append("Allowed sky median: ").append(value(limits.minimumBackground(), true)).append(" to ")
                .append(value(limits.maximumBackground(), true)).append(" ADU\n");
        text.append("Common area used to measure quality: ").append(value(result.qualityAreaFraction() * 100, true)).append("%\n")
                .append("Common area after rejection: ").append(value(result.retainedAreaFraction() * 100, true)).append("%\n\nREGISTRATION PADDING FIT\n");
        var fit = frame.registration();
        if (fit == null) text.append("Unavailable (registration failed).\n");
        else text.append("Shift X / Y: ").append(value(fit.shiftX(), true)).append(" / ").append(value(fit.shiftY(), true)).append(" px\n")
                .append("Rotation: ").append(value(Math.toDegrees(fit.rotationRadians()), true)).append(" degrees\n")
                .append("Edge-fit RMS: ").append(value(fit.edgeFitRmsPixels(), true)).append(" px\n")
                .append("Usable for camera mapping: ").append(fit.usable() ? "Yes" : "No").append("\n");
        return text.append("This fit describes registration-padding edges, not residual stellar alignment. A zero without padding does not prove perfect alignment.").toString();
    }

    /** Uses fixed display units and no numeric substitute for absent measurements. */
    private static String value(double value, boolean available) {
        return available && Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : UNAVAILABLE;
    }
}
