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

import eu.startales.spacepixels.detection.FaintMoverOverlay;
import eu.startales.spacepixels.detection.FindingOverlay;
import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.detection.VariableStarOverlay;
import eu.startales.spacepixels.report.FindingCategory;

import javax.swing.*;
import java.awt.*;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Report findings section of a viewer's side panel: a switch in the header for all of them (key R), then one row
 * per layer of the last detection run (variable stars, faint movers, moving object tracks, streak tracks) and Other
 * findings, which unfolds into the report's remaining categories. Each row says how many findings the run has, or why
 * there are none to show.
 */
final class FindingsSection {

    private static final String NO_RUN = "Run Detect Moving Targets on this session to see its findings here.";
    private static final List<ViewerLayer> OTHER_LAYERS = List.of(ViewerLayer.SINGLE_FRAME, ViewerLayer.SLOW_MOVERS,
            ViewerLayer.LOCAL_RESIDUALS, ViewerLayer.GROUPS);

    private final ViewerLayerState state;
    private final JCheckBox shownSwitch = SidePanelIcons.onOff("Show report findings");
    private final Map<ViewerLayer, LayerRow> rows = new EnumMap<>(ViewerLayer.class);
    private final JButton expand = new JButton();
    private final JPanel children = new JPanel();
    private final SidePanelSection section;
    private boolean expanded;
    /** The last run's tracks and faint movers, and their reference markers from an older report. */
    private TrackOverlay tracks;
    private boolean tracksAvailable;
    private FaintMoverOverlay faintMovers;
    private boolean faintMoversAvailable;
    private final Map<ViewerLayer, Integer> references = new EnumMap<>(ViewerLayer.class);

    FindingsSection(ViewerLayerState state) {
        this.state = state;
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JLabel caption = new JLabel("labels · show");
        caption.setForeground(UiStyle.helpColor());
        caption.setFont(UiStyle.bodyFont().deriveFont(UiStyle.bodyFont().getSize2D() - 1f));
        JPanel captionRow = new JPanel(new BorderLayout());
        captionRow.setOpaque(false);
        captionRow.add(caption, BorderLayout.EAST);
        add(body, captionRow);
        for (ViewerLayer layer : List.of(ViewerLayer.VARIABLE_STARS, ViewerLayer.FAINT_MOVERS, ViewerLayer.MOVING_TRACKS,
                ViewerLayer.STREAK_TRACKS)) {
            add(body, row(layer, state, null, 0));
        }
        expand.putClientProperty("JButton.buttonType", "borderless");
        expand.setFocusable(false);
        expand.setMargin(new Insets(0, 0, 0, 0));
        expand.addActionListener(e -> setExpanded(!expanded));
        add(body, row(ViewerLayer.OTHER_FINDINGS, state, expand, 0));
        children.setLayout(new BoxLayout(children, BoxLayout.Y_AXIS));
        children.setOpaque(false);
        for (ViewerLayer layer : OTHER_LAYERS) {
            add(children, row(layer, state, null, 22));
        }
        add(body, children);
        shownSwitch.addActionListener(e -> state.setVisible(ViewerLayer.REPORT_FINDINGS, shownSwitch.isSelected()));
        section = new SidePanelSection("Report findings", body).addHeaderControl(shownSwitch);
        setExpanded(false);
        rows.get(ViewerLayer.VARIABLE_STARS).setKey("V");
        rows.get(ViewerLayer.MOVING_TRACKS).setKey("T");
        rows.get(ViewerLayer.STREAK_TRACKS).setKey("Shift+T");
        setTracks(null, false);
        setVariableStars(null, false);
        setFaintMovers(null, false);
        setFindings(null, false);
    }

    SidePanelSection section() {
        return section;
    }

    LayerRow row(ViewerLayer layer) {
        return rows.get(layer);
    }

    /** Whether a key may switch this layer: it has something to show, and every group above it is on. */
    boolean isAvailable(ViewerLayer layer) {
        LayerRow row = rows.get(layer);
        return row != null && row.eye().isEnabled();
    }

    /** Key R: switches all the findings, while there are any to show. */
    void toggleShown() {
        if (shownSwitch.isEnabled()) state.toggleVisible(ViewerLayer.REPORT_FINDINGS);
    }

    /** Shows the shared state again after a change, possibly made in another viewer. */
    void refresh() {
        rows.values().forEach(LayerRow::refresh);
        updateSwitch();
    }

    /** The header switch: available while any row has something to show. */
    private void updateSwitch() {
        boolean any = rows.values().stream().anyMatch(LayerRow::hasContent);
        shownSwitch.setEnabled(any);
        shownSwitch.setSelected(state.isVisible(ViewerLayer.REPORT_FINDINGS));
        String action = "Show all the findings of the last detection run (R). Off, their rows are disabled and keep their own choices.";
        String tip = any ? action : "<html>" + action + "<br><i>Not available: " + NO_RUN + "</i></html>";
        shownSwitch.setToolTipText(tip);
        section.title().setToolTipText(tip);
    }

    void setTracks(TrackOverlay overlay, boolean available) {
        tracks = overlay;
        tracksAvailable = available;
        updateTracks();
    }

    private void updateTracks() {
        updateTrackRow(ViewerLayer.MOVING_TRACKS, false, "Moving object tracks of the last detection run, numbered T1, T2… as in the report.");
        updateTrackRow(ViewerLayer.STREAK_TRACKS, true, "Streak tracks of the last detection run, numbered ST1, ST2… as in the report.");
        updateSwitch();
    }

    private void updateTrackRow(ViewerLayer layer, boolean streak, String description) {
        int count = tracks == null ? 0 : (int) tracks.getTracks().stream().filter(track -> track.streak == streak).count();
        int referenced = references.getOrDefault(layer, 0);
        boolean available = (tracksAvailable && count > 0) || referenced > 0;
        String tip = description + " A ring marks the position in the frame shown; the faint line shows the whole track.";
        if (referenced > 0) {
            tip = tracksAvailable && count > 0 ? tip + " Dashed squares marked ref are report reference positions."
                    : "From an older report, which saved one reference position per track (dashed squares marked ref). "
                    + "Run Detect Moving Targets again to see their positions frame by frame.";
        }
        rows.get(layer).setAvailable(available,
                available ? String.valueOf((tracksAvailable ? count : 0) + referenced) : reason(tracks != null, count),
                available ? tip : tracks == null ? NO_RUN : "The last run has no " + layer.title.toLowerCase(java.util.Locale.ROOT) + " on these frames.");
    }

    void setVariableStars(VariableStarOverlay overlay, boolean available) {
        int candidates = overlay == null ? 0 : overlay.getCandidates().size();
        rows.get(ViewerLayer.VARIABLE_STARS).setAvailable(available,
                available ? String.valueOf(candidates) : reason(overlay != null, candidates),
                available ? "Variable-star candidates of the last detection run, numbered as in the report. "
                        + "Solid rings: high confidence; dashed rings: possible. Hover for details."
                        : "Run Detect Moving Targets with variable-star photometry enabled on this session to see its candidates here.");
        updateSwitch();
    }

    void setFaintMovers(FaintMoverOverlay overlay, boolean available) {
        faintMovers = overlay;
        faintMoversAvailable = available;
        updateFaintMovers();
    }

    private void updateFaintMovers() {
        int candidates = faintMovers == null ? 0 : faintMovers.candidates().size();
        int referenced = references.getOrDefault(ViewerLayer.FAINT_MOVERS, 0);
        boolean available = faintMoversAvailable || referenced > 0;
        rows.get(ViewerLayer.FAINT_MOVERS).setAvailable(available,
                available ? String.valueOf((faintMoversAvailable ? candidates : 0) + referenced) : reason(faintMovers != null, candidates),
                available ? "Experimental FM candidates: faint dashed fitted paths and rings at each frame's exposure midpoint. These are unverified."
                        + (referenced > 0 ? " Dashed squares marked ref are report reference positions without a fitted path." : "")
                        : "Run Detect Moving Targets with the faint-mover search enabled on this session to see its candidates here.");
        updateSwitch();
    }

    void setFindings(FindingOverlay overlay, boolean available) {
        Map<ViewerLayer, Integer> counts = new EnumMap<>(ViewerLayer.class);
        if (overlay != null) {
            for (FindingOverlay.Marker marker : overlay.markers()) {
                counts.merge(ViewerLayer.of(FindingCategory.fromId(marker.id())), 1, Integer::sum);
            }
        }
        references.clear();
        for (ViewerLayer layer : List.of(ViewerLayer.MOVING_TRACKS, ViewerLayer.STREAK_TRACKS, ViewerLayer.FAINT_MOVERS)) {
            if (available && counts.containsKey(layer)) references.put(layer, counts.get(layer));
        }
        updateTracks();
        updateFaintMovers();
        int total = OTHER_LAYERS.stream().mapToInt(layer -> counts.getOrDefault(layer, 0)).sum();
        rows.get(ViewerLayer.OTHER_FINDINGS).setAvailable(available && total > 0,
                available ? (total > 0 ? String.valueOf(total) : "none in report") : reason(overlay != null, total),
                available ? "The report's other categories. Rings mark measured positions, vivid in the frame shown and faint in "
                        + "the other frames; hover over one for its frame. Dashed squares marked ref are report references, "
                        + "not per-frame detections." : NO_RUN);
        for (ViewerLayer layer : OTHER_LAYERS) {
            int count = counts.getOrDefault(layer, 0);
            boolean has = available && count > 0;
            rows.get(layer).setAvailable(has, has ? String.valueOf(count) : available ? "none in report" : "",
                    describe(layer));
        }
        expand.setEnabled(available && total > 0);
        updateSwitch();
    }

    private static String reason(boolean run, int count) {
        return !run ? "no run" : count == 0 ? "none in report" : "other frames";
    }

    private static String describe(ViewerLayer layer) {
        return switch (layer) {
            case SINGLE_FRAME -> "S: single-frame streaks; A: single-frame anomalies. Vivid in their own frame, faint in the others.";
            case SLOW_MOVERS -> "DS: slow-mover candidates from the maximum stack; drawn as references, not per-frame detections.";
            case LOCAL_RESIDUALS -> "LR: local rescue candidates; LC: local activity clusters.";
            case GROUPS -> "FC: camera-fixed groups; CM: common-motion groups.";
            default -> layer.title;
        };
    }

    private void setExpanded(boolean expanded) {
        this.expanded = expanded;
        children.setVisible(expanded);
        expand.setText(expanded ? "▾" : "▸");
        expand.getAccessibleContext().setAccessibleName((expanded ? "Fold " : "Unfold ") + "other findings");
        expand.setToolTipText((expanded ? "Hide" : "Show") + " the categories of the other findings.");
        children.revalidate();
    }

    private LayerRow row(ViewerLayer layer, ViewerLayerState state, JComponent leading, int indent) {
        LayerRow row = new LayerRow(layer, state, leading, indent);
        rows.put(layer, row);
        return row;
    }

    private static void add(JPanel panel, JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(component);
    }
}
