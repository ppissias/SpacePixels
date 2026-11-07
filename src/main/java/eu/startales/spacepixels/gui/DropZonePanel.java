package eu.startales.spacepixels.gui;

import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.dnd.DropTargetEvent;
import java.util.TooManyListenersException;

/**
 * What the frame list shows before any frames are imported: where to drop a folder, what it may hold, and the ways on
 * from here. Its view scrolls, so a small window can still reach all of it.
 */
final class DropZonePanel extends JPanel {

    /** The manual's section that walks through a session; its anchor is checked against the manual by a test. */
    static final String WORKFLOW_SECTION = "3-workflow-find-moving-objects-in-a-session";

    private final Zone zone = new Zone();
    private final JPanel view = new CenteringView();
    private final JScrollPane scroll = new JScrollPane(view);

    /**
     * @param chooseFolder the import command, the highlighted action of an empty window
     * @param prepareRaw   the preparation command
     * @param openWorkflow opens the manual's workflow
     */
    DropZonePanel(Action chooseFolder, Action prepareRaw, Runnable openWorkflow) {
        super(new BorderLayout());
        zone.setLayout(new BoxLayout(zone, BoxLayout.Y_AXIS));
        zone.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(20), UiStyle.space(36), UiStyle.space(20), UiStyle.space(36)));

        JLabel icon = new JLabel(new FolderIcon(UiStyle.space(44)));
        JLabel heading = new JLabel("Drop a folder of aligned frames here", SwingConstants.CENTER);
        heading.putClientProperty("FlatLaf.styleClass", "h2");
        WrappingHelpLabel inputs = new WrappingHelpLabel("<div align='center'>FITS or XISF frames, already calibrated and aligned. "
                + "XISF is converted to a working folder, and SpacePixels asks before converting compressed or 32-bit data. "
                + "Colour frames can be converted to mono after importing.</div>", 480);
        inputs.wrapAt(UiStyle.space(480));

        JButton choose = new JButton(chooseFolder);
        choose.putClientProperty("FlatLaf.style", MainApplicationPanel.PRIMARY_BUTTON_STYLE);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, UiStyle.space(8), 0));
        actions.setOpaque(false);
        actions.add(choose);
        actions.add(new JButton(prepareRaw));

        JButton workflow = AccessibleControls.link("New here? Read the workflow in the manual",
                "Open the workflow section of the user manual", openWorkflow);
        workflow.setToolTipText("Opens the manual in your browser: import, Auto-Tune, detect and read the report.");

        addCentred(icon, UiStyle.space(8));
        addCentred(heading, UiStyle.space(6));
        addCentred(inputs, UiStyle.space(12));
        addCentred(actions, UiStyle.space(6));
        addCentred(workflow, 0);

        view.add(zone);
        scroll.setBorder(null);
        add(scroll, BorderLayout.CENTER);
    }

    private void addCentred(JComponent component, int gapBelow) {
        component.setAlignmentX(Component.CENTER_ALIGNMENT);
        zone.add(component);
        if (gapBelow > 0) {
            zone.add(Box.createVerticalStrut(gapBelow));
        }
    }

    /** Accepts a dropped folder anywhere in the panel, and lights up the zone while one is dragged over it. */
    void installFolderDrop(TransferHandler handler) {
        setTransferHandler(handler);
        scroll.setTransferHandler(handler);
        scroll.getViewport().setTransferHandler(handler);
        view.setTransferHandler(handler);
        if (view.getDropTarget() == null) {
            return;
        }
        try {
            view.getDropTarget().addDropTargetListener(new DropTargetAdapter() {
                @Override
                public void dragEnter(DropTargetDragEvent event) {
                    zone.setDragOver(event.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
                }

                @Override
                public void dragExit(DropTargetEvent event) {
                    zone.setDragOver(false);
                }

                @Override
                public void drop(DropTargetDropEvent event) {
                    zone.setDragOver(false);
                }
            });
        } catch (TooManyListenersException cannotShowIt) {
            // Dropping still works; only the highlight is missing.
        }
    }

    /** Centres the zone when the window has room, and scrolls when it does not. */
    private static final class CenteringView extends JPanel implements Scrollable {
        CenteringView() {
            super(new GridBagLayout());
        }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return UiStyle.space(16); }

        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return UiStyle.space(120); }

        @Override public boolean getScrollableTracksViewportWidth() { return getParent().getWidth() > getPreferredSize().width; }

        @Override public boolean getScrollableTracksViewportHeight() { return getParent().getHeight() > getPreferredSize().height; }
    }

    /** The dashed area itself, drawn in the theme's colours. */
    private static final class Zone extends JPanel {
        private boolean dragOver;

        Zone() {
            setOpaque(false);
        }

        void setDragOver(boolean over) {
            dragOver = over;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D dashed = (Graphics2D) g.create();
            dashed.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color accent = UIManager.getColor("Component.focusColor");
            Color line = dragOver && accent != null ? accent : UiStyle.helpColor();
            float width = UiStyle.space(dragOver ? 3 : 2) / 1.5f;
            float dash = UiStyle.space(7);
            dashed.setColor(line);
            dashed.setStroke(new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{dash, dash}, 0f));
            int inset = Math.round(width);
            int arc = UiStyle.space(12);
            dashed.drawRoundRect(inset, inset, getWidth() - 2 * inset - 1, getHeight() - 2 * inset - 1, arc, arc);
            dashed.dispose();
        }
    }

    /** A folder with an arrow into it, in the text colour of the theme. */
    private static final class FolderIcon implements Icon {
        private final int size;

        FolderIcon(int size) {
            this.size = size;
        }

        @Override public int getIconWidth() { return size; }

        @Override public int getIconHeight() { return size; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D pen = (Graphics2D) g.create();
            pen.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            pen.translate(x, y);
            pen.setColor(UiStyle.helpColor());
            pen.setStroke(new BasicStroke(Math.max(1.5f, size / 22f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float s = size / 44f;
            int[] outlineX = {(int) (4 * s), (int) (4 * s), (int) (16 * s), (int) (21 * s), (int) (40 * s), (int) (40 * s)};
            int[] outlineY = {(int) (36 * s), (int) (8 * s), (int) (8 * s), (int) (14 * s), (int) (14 * s), (int) (36 * s)};
            pen.drawPolygon(outlineX, outlineY, outlineX.length);
            int middle = (int) (22 * s);
            pen.drawLine(middle, (int) (19 * s), middle, (int) (31 * s));
            pen.drawLine((int) (17 * s), (int) (26 * s), middle, (int) (31 * s));
            pen.drawLine((int) (27 * s), (int) (26 * s), middle, (int) (31 * s));
            pen.dispose();
        }
    }
}
