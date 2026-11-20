package eu.startales.spacepixels.gui;

import io.github.ppissias.jtransient.engine.ExcludedArea;
import io.github.ppissias.jtransient.engine.FrameQualityResult;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The quality-control registration check for the whole session: how far the stars move between the registered frames,
 * the zone map, and, when zones are flagged, the offer to exclude them. Excluding happens only when the user presses
 * the button; the caller saves the areas with the session.
 */
final class RegistrationCheckDialog {
    private static final int CONTENT_WIDTH = 560;
    /** Flagged zones in the quality table's reject colour, readable on light and dark themes. */
    private static final String FLAGGED = String.format("#%06x", QualityControlTable.REJECT_COLOUR.getRGB() & 0xffffff);

    private RegistrationCheckDialog() {
    }

    /**
     * Opens the dialog, modal, on the EDT.
     *
     * @param alreadyExcluded the session's current excluded areas; flagged areas among them are not offered again
     * @param exclude receives the flagged areas to add when the user presses the button
     */
    static void show(Component owner, FrameQualityResult.RegistrationCheck check, List<ExcludedArea> alreadyExcluded,
                     Consumer<List<ExcludedArea>> exclude) {
        Window window = owner == null ? null : SwingUtilities.getWindowAncestor(owner);
        JDialog dialog = new JDialog(window, "Registration check", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        AppIcon.apply(dialog);

        List<ExcludedArea> offered = check.flaggedAreas().stream().filter(a -> !alreadyExcluded.contains(a)).toList();
        JLabel text = new JLabel(UiStyle.htmlWrappedTo(CONTENT_WIDTH, describe(check, offered)));
        text.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(14), UiStyle.space(16), UiStyle.space(8), UiStyle.space(16)));
        text.getAccessibleContext().setAccessibleName("Registration check result");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(8), 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(4), UiStyle.space(16), UiStyle.space(12), UiStyle.space(16)));
        JButton close = new JButton("Close");
        close.addActionListener(event -> dialog.dispose());
        if (!offered.isEmpty()) {
            JButton excludeButton = new JButton(offered.size() == 1 ? "Exclude this area" : "Exclude these " + offered.size() + " areas");
            excludeButton.setToolTipText("Adds the areas to the session's excluded areas: detection leaves them out in every frame. "
                    + "You can change or remove them in Preview Frame. Run quality control again afterwards.");
            excludeButton.addActionListener(event -> {
                dialog.dispose();
                exclude.accept(offered);
            });
            buttons.add(excludeButton);
        }
        buttons.add(close);

        dialog.add(text, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(close);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    /** The dialog's text: the summary, the zone map and what to do; HTML. */
    static String describe(FrameQualityResult.RegistrationCheck check, List<ExcludedArea> offered) {
        if (!check.available()) {
            return "<b>Not measured:</b> " + escape(check.reason()) + ".";
        }
        StringBuilder html = new StringBuilder();
        html.append(String.format(Locale.US, "How far the stars move between the aligned frames, in star widths (FWHM, "
                        + "here %.2f px), over %d frames and %d reference stars. Stars that move leave light past the star "
                        + "masks, which detection can take for moving objects.<br><br>",
                check.fwhmPixels(), check.framesMeasured(), check.referenceStars()));
        html.append("<table cellspacing='0' cellpadding='4' border='1'>");
        for (int row = 0; row < check.zones(); row++) {
            html.append("<tr>");
            for (int column = 0; column < check.zones(); column++) {
                double creep = check.zoneCreep(row, column);
                html.append(Double.isNaN(creep) ? "<td align='center'>&ndash;</td>"
                        : check.zoneFlagged(row, column)
                        ? String.format(Locale.US, "<td align='center'><font color='%s'><b>%.2f</b></font></td>", FLAGGED, creep)
                        : String.format(Locale.US, "<td align='center'>%.2f</td>", creep));
            }
            html.append("</tr>");
        }
        html.append("</table>");
        html.append(String.format(Locale.US, "Change over the session by zone of the frame; <font color='%s'><b>red</b></font> "
                + "above %.2f FWHM, &ndash; too few stars.<br><br>", FLAGGED, check.creepLimitFwhm()));
        if (check.overallFlagged()) {
            html.append(String.format(Locale.US, "<b>The whole star field moves by %.2f FWHM (%.1f px) during the "
                            + "session.</b> The frames are not aligned on the stars, for example aligned on a moving target or "
                            + "with a drifting alignment. Detections that move with the field may be false. Align the frames "
                            + "on the stars.<br><br>",
                    check.overallCreepFwhm(), check.overallCreepFwhm() * check.fwhmPixels()));
        }
        List<ExcludedArea> flagged = check.flaggedAreas();
        if (!flagged.isEmpty()) {
            html.append("<b>The alignment does not follow the field in part of the frame</b>, for example lens distortion "
                    + "it does not model. Detections there may be false. Align the frames with distortion correction, or "
                    + "exclude ").append(flagged.size() == 1 ? "this area" : "these areas").append(" from detection:<br>");
            for (ExcludedArea a : flagged) {
                html.append(String.format(Locale.US, "&nbsp;&nbsp;x %d&ndash;%d, y %d&ndash;%d%s<br>", a.x(),
                        a.x() + a.width() - 1, a.y(), a.y() + a.height() - 1, offered.contains(a) ? "" : " (already excluded)"));
            }
        }
        if (!check.anyFlagged()) {
            html.append("Nothing flagged: the stars stay in place.");
        }
        return html.toString();
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
