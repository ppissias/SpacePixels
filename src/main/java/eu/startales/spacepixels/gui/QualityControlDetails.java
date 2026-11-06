package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.QualityControlSnapshot;
import io.github.ppissias.jtransient.engine.FrameQualityResult;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.Locale;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Presents measured evidence and the engine's exact session limits without adding quality rules in the GUI. */
final class QualityControlDetails {
    /** Opens a scrollable, selectable readout on the EDT for the original selected file. */
    static void show(Component owner, QualityControlSnapshot snapshot, FrameQualityResult.Frame frame, boolean current) {
        JTextArea text = new JTextArea(describe(snapshot, frame, current), 27, 68);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setCaretPosition(0);
        text.getAccessibleContext().setAccessibleName("Frame quality measurements and session limits");
        JScrollPane scroll = new JScrollPane(text);
        var screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        var preferred = scroll.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.min(preferred.width, screen.width * 4 / 5),
                Math.min(preferred.height, screen.height * 3 / 4)));
        JOptionPane.showMessageDialog(owner, scroll, "Quality details — " + frame.filename(),
                JOptionPane.INFORMATION_MESSAGE);
    }

    /** Formats missing evidence explicitly; all displayed thresholds come from the engine's completed result. */
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
        return available && Double.isFinite(value) ? String.format(Locale.US, "%.3f", value) : "Unavailable";
    }
}
