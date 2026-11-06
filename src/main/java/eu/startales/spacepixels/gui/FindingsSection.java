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
 * The Report findings section of a viewer's side panel: one row per layer of the last detection run (variable stars,
 * faint movers, tracks) and Other findings, which unfolds into the report's remaining categories. Each row says how
 * many findings the run has, or why there are none to show.
 */
final class FindingsSection {

    private static final String NO_RUN = "Run Detect Moving Targets on this session to see its findings here.";
    private static final List<ViewerLayer> OTHER_LAYERS = List.of(ViewerLayer.SINGLE_FRAME, ViewerLayer.SLOW_MOVERS,
            ViewerLayer.LOCAL_RESIDUALS, ViewerLayer.GROUPS);

    private final Map<ViewerLayer, LayerRow> rows = new EnumMap<>(ViewerLayer.class);
    private final JButton expand = new JButton();
    private final JPanel children = new JPanel();
    private final SidePanelSection section;
    private boolean expanded;

    FindingsSection(ViewerLayerState state) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        JLabel caption = new JLabel("labels · show");
        caption.setForeground(UiStyle.helpColor());
        caption.setFont(UiStyle.bodyFont().deriveFont(UiStyle.bodyFont().getSize2D() - 1f));
        JPanel captionRow = new JPanel(new BorderLayout());
        captionRow.setOpaque(false);
        captionRow.add(caption, BorderLayout.EAST);
        add(body, captionRow);
        for (ViewerLayer layer : List.of(ViewerLayer.VARIABLE_STARS, ViewerLayer.FAINT_MOVERS, ViewerLayer.TRACKS)) {
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
        section = new SidePanelSection("Report findings", body);
        setExpanded(false);
        rows.get(ViewerLayer.VARIABLE_STARS).eye().setToolTipText("Show or hide the variable-star candidates (V).");
        rows.get(ViewerLayer.TRACKS).eye().setToolTipText("Show or hide the tracks (T).");
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

    /** Whether a key may switch this layer: it has something to show. */
    boolean isAvailable(ViewerLayer layer) {
        LayerRow row = rows.get(layer);
        return row != null && row.eye().isEnabled();
    }

    /** Shows the shared state again after a change, possibly made in another viewer. */
    void refresh() {
        rows.values().forEach(LayerRow::refresh);
    }

    void setTracks(TrackOverlay overlay, boolean available) {
        int tracks = overlay == null ? 0 : overlay.getTracks().size();
        rows.get(ViewerLayer.TRACKS).setAvailable(available, available ? String.valueOf(tracks) : reason(overlay != null, tracks),
                available ? "Tracks of the last detection run, numbered as in the report: T for moving objects, ST for streak tracks. "
                        + "Rings mark the positions in the frame shown."
                        : overlay == null ? NO_RUN : "The last run has no tracks on these frames.");
    }

    void setVariableStars(VariableStarOverlay overlay, boolean available) {
        int candidates = overlay == null ? 0 : overlay.getCandidates().size();
        rows.get(ViewerLayer.VARIABLE_STARS).setAvailable(available,
                available ? String.valueOf(candidates) : reason(overlay != null, candidates),
                available ? "Variable-star candidates of the last detection run, numbered as in the report. "
                        + "Solid rings: high confidence; dashed rings: possible. Hover for details."
                        : "Run Detect Moving Targets with variable-star photometry enabled on this session to see its candidates here.");
    }

    void setFaintMovers(FaintMoverOverlay overlay, boolean available) {
        int candidates = overlay == null ? 0 : overlay.candidates().size();
        rows.get(ViewerLayer.FAINT_MOVERS).setAvailable(available,
                available ? String.valueOf(candidates) : reason(overlay != null, candidates),
                available ? "Experimental FM candidates: dashed fitted paths and rings at each frame's exposure midpoint. These are unverified."
                        : "Run Detect Moving Targets with the faint-mover search enabled on this session to see its candidates here.");
    }

    void setFindings(FindingOverlay overlay, boolean available) {
        Map<ViewerLayer, Integer> counts = new EnumMap<>(ViewerLayer.class);
        if (overlay != null) {
            for (FindingOverlay.Marker marker : overlay.markers()) {
                counts.merge(ViewerLayer.of(FindingCategory.fromId(marker.id())), 1, Integer::sum);
            }
        }
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        rows.get(ViewerLayer.OTHER_FINDINGS).setAvailable(available, available ? String.valueOf(total) : reason(overlay != null, total),
                available ? "The report's other categories. Rings mark measured positions in the frame shown; dashed squares "
                        + "marked ref are report references, not per-frame detections." : NO_RUN);
        for (ViewerLayer layer : OTHER_LAYERS) {
            int count = counts.getOrDefault(layer, 0);
            boolean has = available && count > 0;
            rows.get(layer).setAvailable(has, has ? String.valueOf(count) : available ? "none in report" : "",
                    describe(layer));
        }
        expand.setEnabled(available);
    }

    private static String reason(boolean run, int count) {
        return !run ? "no run" : count == 0 ? "none in report" : "other frames";
    }

    private static String describe(ViewerLayer layer) {
        return switch (layer) {
            case SINGLE_FRAME -> "S: single-frame streaks; A: single-frame anomalies.";
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
