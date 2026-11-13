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

import eu.startales.spacepixels.detection.ExcludedAreas;
import eu.startales.spacepixels.session.ProcessingSession;
import io.github.ppissias.jtransient.engine.ExcludedArea;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The Excluded areas section of Preview Frame: rectangles of the frames that detection leaves out in every frame, such
 * as blurred edges or a bad column. A switch shows them, Draw turns drawing on the image on, and the list removes one
 * or all. The session owns them, saves them in its folder and gives them to the next detection run. EDT only.
 */
final class ExcludedAreasSection {
    private static final Color PROBLEM = new Color(0xE57373);

    private final ViewerLayerState state;
    private final ProcessingSession session;
    private final Consumer<FrameView.ExcludedAreaEditing> drawing;
    private final Consumer<List<ExcludedArea>> display;
    private final JCheckBox shown = SidePanelIcons.onOff("Show excluded areas");
    private final JToggleButton draw = new JToggleButton("Draw areas");
    private final JButton removeAll = new JButton("Remove all");
    private final JLabel summary = new JLabel(" ");
    private final JLabel problem = new JLabel(" ");
    private final JPanel list = new JPanel();
    private final SidePanelSection section;
    /** Kept here, as the layer state holds its listeners weakly. */
    private final Runnable layerListener = this::refresh;
    private boolean editable = true;

    /**
     * @param state   the viewers' shared layer choices
     * @param session the session whose areas are shown and edited
     * @param drawing turns drawing on the image on (a receiver) or off (null)
     * @param display shows the areas on the image
     */
    ExcludedAreasSection(ViewerLayerState state, ProcessingSession session,
                         Consumer<FrameView.ExcludedAreaEditing> drawing, Consumer<List<ExcludedArea>> display) {
        this.state = state;
        this.session = session;
        this.drawing = drawing;
        this.display = display;
        shown.setToolTipText("Show the areas detection leaves out, in light red.");
        shown.addActionListener(e -> state.setVisible(ViewerLayer.EXCLUDED_AREAS, shown.isSelected()));
        draw.setToolTipText("Drag on the image to mark an area; right-drag moves the image; right-click an area to remove it.");
        draw.addActionListener(e -> setDrawing(draw.isSelected()));
        removeAll.setToolTipText("Remove every excluded area of this session.");
        removeAll.addActionListener(e -> change(ExcludedAreas.none(areas().width(), areas().height())));
        summary.setForeground(UiStyle.helpColor());
        problem.setForeground(PROBLEM);
        problem.setVisible(false);
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);

        WrappingHelpLabel help = new WrappingHelpLabel("Mark areas that detection should leave out in every frame, "
                + "such as blurred edges or a bad column. Blink the frames first to see how far a defect moves, and cover "
                + "all of it. The areas are saved with the session and used by the next detection.", 260);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, UiStyle.space(6), 0));
        buttons.setOpaque(false);
        buttons.add(draw);
        buttons.add(removeAll);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        for (JComponent component : new JComponent[]{help, buttons, summary, problem, list}) {
            component.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(component);
            body.add(Box.createVerticalStrut(UiStyle.space(4)));
        }
        section = new SidePanelSection("Excluded areas", body).addHeaderControl(shown);
        section.title().setIcon(OverlayStyle.swatch(ViewerLayer.EXCLUDED_AREAS));
        section.title().setIconTextGap(UiStyle.space(8));
        if (session != null) session.addExcludedAreasListener(this::refresh);
        state.addListener(layerListener);
        refresh();
    }

    SidePanelSection section() {
        return section;
    }

    /** Turns drawing on, unfolding the section, for example from the main window's command. */
    void startDrawing() {
        section.setExpanded(true);
        if (editable && !draw.isSelected()) {
            draw.setSelected(true);
            setDrawing(true);
        }
    }

    /** Allows editing only while no job runs; drawing stops when editing is not allowed. */
    void setEditable(boolean editable) {
        this.editable = editable;
        if (!editable && draw.isSelected()) {
            draw.setSelected(false);
            setDrawing(false);
        }
        refresh();
    }

    /** Follows the session's areas and the shared layer switch. */
    void refresh() {
        shown.setSelected(state.isVisible(ViewerLayer.EXCLUDED_AREAS));
        ExcludedAreas areas = areas();
        display.accept(areas.areas());
        summary.setText(areas.isEmpty() ? "No excluded areas."
                : String.format(Locale.ROOT, "%d %s · %.1f %% of the frame", areas.areas().size(),
                areas.areas().size() == 1 ? "area" : "areas", 100 * areas.fraction()));
        list.removeAll();
        for (int i = 0; i < areas.areas().size(); i++) {
            ExcludedArea area = areas.areas().get(i);
            JPanel row = new JPanel(new BorderLayout(UiStyle.space(6), 0));
            row.setOpaque(false);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel text = new JLabel(String.format(Locale.ROOT, "x %d–%d, y %d–%d (%d × %d px)", area.x(),
                    area.x() + area.width() - 1, area.y(), area.y() + area.height() - 1, area.width(), area.height()));
            JButton remove = new JButton("✕");
            remove.setToolTipText("Remove this area.");
            remove.getAccessibleContext().setAccessibleName("Remove area " + (i + 1));
            remove.setMargin(new java.awt.Insets(0, UiStyle.space(4), 0, UiStyle.space(4)));
            remove.setEnabled(editable);
            int index = i;
            remove.addActionListener(e -> change(areas().without(index)));
            row.add(text, BorderLayout.CENTER);
            row.add(remove, BorderLayout.EAST);
            row.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(1), 0, UiStyle.space(1), 0));
            list.add(row);
        }
        draw.setEnabled(editable && session != null);
        removeAll.setEnabled(editable && !areas.isEmpty());
        list.revalidate();
        list.repaint();
    }

    /** The session's areas; none without a session. */
    private ExcludedAreas areas() {
        return session == null ? ExcludedAreas.none(0, 0) : session.getExcludedAreas();
    }

    private void setDrawing(boolean on) {
        drawing.accept(on ? new FrameView.ExcludedAreaEditing() {
            @Override
            public void drawn(ExcludedArea area) {
                change(areas().with(area));
            }

            @Override
            public void removeAt(int index) {
                if (index >= 0 && index < areas().areas().size()) change(areas().without(index));
            }
        } : null);
    }

    /** Gives the session the changed areas; it saves them and tells this section and the other viewers. */
    private void change(ExcludedAreas changed) {
        if (session == null || !editable) return;
        session.setExcludedAreas(changed, failure -> {
            problem.setVisible(failure != null);
            problem.setText(failure == null ? " " : "Not saved: " + failure.getMessage());
        });
    }
}
