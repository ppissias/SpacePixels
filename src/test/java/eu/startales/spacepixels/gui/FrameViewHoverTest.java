package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class FrameViewHoverTest {
    @Test public void hoverNamesWorkWhenDensityHidesLabelsAndRespectZoomAndLayers() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView();
            view.setSize(400, 320);
            FitsFileInformation info = new FitsFileInformation("hover.fit", "hover.fit", true, 200, 160);
            view.showFrame(BlinkSequence.fromMono(info, new short[160][200]), new byte[1][65536], false);
            List<SkyMark> marks = new ArrayList<>();
            marks.add(new SkyMark(SkyMark.Kind.VARIABLE, 100, 80, 8, 0, 0, 0, "DK Per", "DK Per · EA/SD"));
            for (int i = 0; i < 50; i++) {
                marks.add(new SkyMark(SkyMark.Kind.VARIABLE, 10 + i, 20, 10 + i, 0, 0, 0, "Survey " + i, "Other star"));
            }
            view.setSkyMarks(marks, 15);
            paint(view);
            assertEquals("DK Per · EA/SD", view.getToolTipText(mouse(view, 201, 161)));
            assertNull(view.getToolTipText(mouse(view, 350, 250)));
            view.zoomToScale(4);
            paint(view);
            assertEquals("DK Per · EA/SD", view.getToolTipText(mouse(view, 202, 162)));
            view.setLayers(without(ViewerLayer.KNOWN_VARIABLES));
            assertNull(view.getToolTipText(mouse(view, 202, 162)));
            paint(view);
            assertNull(view.getToolTipText(mouse(view, 202, 162)));
            view.clear();
            assertNull(view.getToolTipText(mouse(view, 202, 162)));
        });
    }

    @Test public void asteroidsAreNamedWhereTheyArePredictedAndTheirPathsAreDrawnWithoutAPosition() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView();
            view.setSize(400, 320);
            FitsFileInformation info = new FitsFileInformation("hover.fit", "hover.fit", true, 200, 160);
            view.showFrame(BlinkSequence.fromMono(info, new short[160][200]), new byte[1][65536], false);
            List<SkyMark> marks = new ArrayList<>();
            marks.add(new SkyMark(SkyMark.Kind.SOLAR_SYSTEM, 100, 80, 14.1, 30, 5, 0, "194 Prokne 14.1",
                    "194 Prokne (A879 FA) · asteroid", new double[]{0, 80, 200, 80}, new double[]{98, 80, 102, 80}));
            marks.add(new SkyMark(SkyMark.Kind.STAR, 100, 80, 9, 0, 0, 0, "9.0", "Gaia G 9.0"));
            marks.add(new SkyMark(SkyMark.Kind.SOLAR_SYSTEM, Double.NaN, Double.NaN, 18, 0, 0, 0, "(2014 QV125) 18.0",
                    "(2014 QV125) · asteroid · elsewhere", new double[]{0, 10, 200, 10}, null));
            view.setSkyMarks(marks, 15);
            paint(view);
            assertEquals("the asteroid comes before the star under it", "194 Prokne (A879 FA) · asteroid",
                    view.getToolTipText(mouse(view, 201, 161)));
            assertNull("an object elsewhere at this time has no position to point at", view.getToolTipText(mouse(view, 200, 21)));
            view.setLayers(without(ViewerLayer.SOLAR_SYSTEM));
            paint(view);
            assertEquals("Gaia G 9.0", view.getToolTipText(mouse(view, 201, 161)));
        });
    }

    /** The default layers with these ones switched off. */
    private static ViewerLayerState.Snapshot without(ViewerLayer... hidden) {
        ViewerLayerState.Snapshot defaults = ViewerLayerState.Snapshot.defaults();
        java.util.Set<ViewerLayer> visible = java.util.EnumSet.copyOf(defaults.visible());
        for (ViewerLayer layer : hidden) visible.remove(layer);
        return new ViewerLayerState.Snapshot(visible, defaults.labelled());
    }

    private static MouseEvent mouse(FrameView view, int x, int y) {
        return new MouseEvent(view, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false);
    }

    private static void paint(FrameView view) {
        BufferedImage image = new BufferedImage(400, 320, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        view.paint(graphics);
        graphics.dispose();
    }
}
