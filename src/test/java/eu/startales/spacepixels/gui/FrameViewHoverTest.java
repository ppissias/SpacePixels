package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
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
            view.setSkyLayers(true, true, false);
            assertNull(view.getToolTipText(mouse(view, 202, 162)));
            paint(view);
            assertNull(view.getToolTipText(mouse(view, 202, 162)));
            view.clear();
            assertNull(view.getToolTipText(mouse(view, 202, 162)));
        });
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
