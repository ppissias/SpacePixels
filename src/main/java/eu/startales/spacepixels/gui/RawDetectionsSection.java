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

import javax.swing.*;
import java.awt.*;

/**
 * The Raw detections section of a viewer that extracts its frames' sources: the switch that draws them (key D), what
 * was found on the frame shown, and the trial thresholds. Editing a threshold switches the detections on, and the
 * header says "Not saved" while a trial value differs from the saved settings. The section is folded while its
 * switch is off.
 */
final class RawDetectionsSection {

    private static final Color UNSAVED = new Color(0xE3C25A);

    private final ViewerLayerState state;
    private final TrialSettingsControls trials;
    private final JCheckBox shown = SidePanelIcons.onOff("Show raw detections");
    private final JLabel badge = new JLabel("Not saved");
    private final JLabel summary = new JLabel(" ");
    private final SidePanelSection section;
    private boolean initialised;

    RawDetectionsSection(ViewerLayerState state, TrialSettingsControls trials) {
        this.state = state;
        this.trials = trials;
        shown.setToolTipText("Show raw detections (D): green boxes for point sources, red lines for streaks, "
                + "extracted with the trial thresholds below. From 100 % zoom the detected pixels are tinted too.");
        shown.addActionListener(e -> state.setVisible(ViewerLayer.RAW_DETECTIONS, shown.isSelected()));
        badge.setForeground(UNSAVED);
        badge.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD, UiStyle.bodyFont().getSize2D() - 1f));
        badge.setToolTipText("A trial value differs from the saved Detection Settings.");
        summary.setForeground(UiStyle.helpColor());
        JLabel caption = new JLabel("TRIAL PARAMETERS");
        caption.setForeground(UiStyle.helpColor());
        caption.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD, UiStyle.bodyFont().getSize2D() - 2f));
        caption.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(8), 0, UiStyle.space(2), 0));

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JComponent form = trials.form();
        for (JComponent component : new JComponent[]{summary, caption, form}) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(component);
        }
        section = new SidePanelSection("Raw detections", body).addHeaderControl(badge).addHeaderControl(shown);
        section.title().setIcon(OverlayStyle.swatch(ViewerLayer.RAW_DETECTIONS));
        section.title().setIconTextGap(UiStyle.space(8));
        trials.setOnChange(() -> badge.setVisible(trials.hasUnsavedChanges()));
        trials.setOnEdit(() -> state.setVisible(ViewerLayer.RAW_DETECTIONS, true));
        badge.setVisible(trials.hasUnsavedChanges());
        refresh();
    }

    SidePanelSection section() {
        return section;
    }

    /** What was extracted on the frame shown, for example "812 point sources · 2 streaks"; null while it is prepared. */
    void setSummary(String text) {
        summary.setText(text == null ? " " : text + " on this frame");
    }

    /** Follows the shared layer state: the switch, and the section unfolded while the detections are shown. */
    void refresh() {
        boolean on = state.isVisible(ViewerLayer.RAW_DETECTIONS);
        // Folded or unfolded only when the switch changes, so a section unfolded by hand stays open.
        boolean changed = !initialised || shown.isSelected() != on;
        initialised = true;
        shown.setSelected(on);
        if (changed) {
            section.setExpanded(on || trials.hasUnsavedChanges());
        }
    }
}
