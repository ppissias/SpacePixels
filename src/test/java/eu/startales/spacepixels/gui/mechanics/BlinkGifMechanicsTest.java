package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.core.MouseButton;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.data.TableCell;
import org.junit.Test;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** Exercises real selection, context export, frame exclusion and return to normal pan/navigation. */
public class BlinkGifMechanicsTest extends MechanicsTestCase {
    /** Mouse gestures export the included loop at viewport dimensions and Escape leaves selection before closing. */
    @Test(timeout = 180_000)
    public void selectedRegionExportsIncludedAnnotatedFrames() throws Exception {
        importFolder(SolvedSession.create(folder.newFolder("solved").toPath()));
        window().table().selectRows(0, 1, 2);
        window().button(VisibleText.button("Blink Selected")).click();
        FrameFixture blink = WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            @Override protected boolean isMatching(JFrame candidate) {
                return candidate.isShowing() && candidate.getTitle().startsWith("Blink");
            }
        }).withTimeout(20_000).using(robot());
        JToggleButton select = robot().finder().find(blink.target(), new GenericTypeMatcher<>(JToggleButton.class) {
            @Override protected boolean isMatching(JToggleButton candidate) {
                return "selectGifRegion".equals(candidate.getName());
            }
        });
        waitFor("GIF selection is ready", () -> GuiActionRunner.execute(select::isEnabled));
        robot().click(select);
        waitFor("selection pauses playback", () -> blink.button(VisibleText.button("▶ Play")).target().isEnabled());
        blink.table().cell(TableCell.row(1).column(0)).click();
        assertEquals(Boolean.FALSE, GuiActionRunner.execute(() -> blink.table().target().getValueAt(1, 0)));
        blink.checkBox(VisibleText.checkBox("Annotate")).check();
        JComponent image = robot().finder().find(blink.target(), new GenericTypeMatcher<>(JComponent.class) {
            @Override protected boolean isMatching(JComponent candidate) {
                return candidate.isShowing() && candidate.getAccessibleContext() != null
                        && candidate.getAccessibleContext().getAccessibleName() != null
                        && candidate.getAccessibleContext().getAccessibleName().startsWith("Blink image");
            }
        });
        JComponent glass = GuiActionRunner.execute(() -> ((JLayer<?>) image.getParent()).getGlassPane());
        Point start = new Point(60, 60), end = new Point(240, 200);
        robot().pressMouse(glass, start, MouseButton.LEFT_BUTTON);
        robot().moveMouse(glass, end);
        robot().releaseMouse(MouseButton.LEFT_BUTTON);
        robot().click(glass, new Point(100, 100), MouseButton.RIGHT_BUTTON, 1);
        blink.menuItem("exportBlinkRegionGif").click();
        var chooser = JFileChooserFinder.findFileChooser().withTimeout(15_000).using(robot());
        Path gif = folder.getRoot().toPath().resolve("selected.gif");
        GuiActionRunner.execute(() -> chooser.target().setSelectedFile(gif.toFile()));
        chooser.approve();
        waitFor("the GIF is published", () -> Files.isRegularFile(gif)
                && blink.label("blinkGifStatus").target().getText().startsWith("Saved 2 frames"));
        double deviceScale = GuiActionRunner.execute(() -> image.getGraphicsConfiguration().getDefaultTransform().getScaleX());
        try (var input = ImageIO.createImageInputStream(gif.toFile())) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input);
                assertEquals(2, reader.getNumImages(true));
                assertEquals((int) Math.ceil(end.x * deviceScale) - (int) Math.floor(start.x * deviceScale), reader.getWidth(0));
                assertEquals((int) Math.ceil(end.y * deviceScale) - (int) Math.floor(start.y * deviceScale), reader.getHeight(0));
            } finally { reader.dispose(); }
        }
        GuiActionRunner.execute(() -> image.requestFocusInWindow());
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape leaves selection", () -> !GuiActionRunner.execute(select::isSelected));
        assertTrue(blink.target().isShowing());
        assertFalse(GuiActionRunner.execute(glass::isVisible));
        robot().pressMouse(image, new Point(80, 80), MouseButton.LEFT_BUTTON);
        robot().moveMouse(image, new Point(110, 100));
        robot().releaseMouse(MouseButton.LEFT_BUTTON);
        robot().pressAndReleaseKeys(KeyEvent.VK_RIGHT);
        assertTrue(blink.target().isShowing());
        GuiActionRunner.execute(() -> blink.target().dispatchEvent(new WindowEvent(blink.target(), WindowEvent.WINDOW_CLOSING)));
        waitFor("Blink closes", () -> !blink.target().isShowing());
    }
}
