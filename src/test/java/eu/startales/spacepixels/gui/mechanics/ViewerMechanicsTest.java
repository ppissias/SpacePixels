package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JCheckBoxFixture;
import org.junit.Test;

import javax.swing.JFrame;
import javax.swing.JLabel;
import java.awt.event.WindowEvent;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Preview Frame and Blink on a solved session with a sky catalogue. The track rows stay off: nothing has been detected.
 * Run with {@code ./gradlew guiMechanicsTest}.
 */
public class ViewerMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 180_000)
    public void previewAndBlinkStepFramesAndAnnotate() throws Exception {
        Path session = SolvedSession.create(folder.newFolder("solved").toPath());
        importFolder(session);
        waitFor("the session is solved and keeps its catalogue", () -> {
            String status = showingLabel("solved");
            return status.contains("5 / 5 solved")
                    && status.contains("stars")
                    && status.contains("Correction available")
                    && !status.contains("Fetching")
                    && !status.contains("Checking");
        });
        assertTrue(window().button(VisibleText.button("Fetch Sky Catalogue")).target().isEnabled());
        assertTrue("a solved session can ask JPL", window().button(VisibleText.button("Fetch Asteroids and Comets")).target().isEnabled());

        window().table().selectRows(0);
        window().button(VisibleText.button("Preview Frame")).click();
        FrameFixture preview = viewer("Preview Frame");
        waitForFrame(preview, "Frame 1 of 5", "detecting");
        preview.button(VisibleText.button("▶")).click();
        waitForFrame(preview, "Frame 2 of 5", "detecting");
        preview.button(VisibleText.button("▶")).click();
        waitForFrame(preview, "Frame 3 of 5", "detecting");
        closeViewer(preview);

        window().table().selectRows(0, 1, 2);
        window().button(VisibleText.button("Blink Selected")).click();
        FrameFixture blink = viewer("Blink");
        waitFor("the blink is playing", () -> blink.checkBox(VisibleText.checkBox("Show catalog annotations")).isEnabled());

        JCheckBoxFixture tracks = blink.checkBox(VisibleText.checkBox("Show moving object tracks"));
        assertFalse("The track rows need a detection", tracks.target().isEnabled());

        JCheckBoxFixture annotate = blink.checkBox(VisibleText.checkBox("Show catalog annotations"));
        JCheckBoxFixture correct = blink.checkBox(VisibleText.checkBox("Align markers to corrected plate solution"));
        annotate.uncheck();
        assertFalse("the section's switch disables its controls", correct.target().isEnabled());
        annotate.check();
        assertTrue(annotate.target().isSelected());
        assertTrue(correct.target().isEnabled());
        correct.check();
        assertTrue(correct.target().isSelected());

        blink.button(VisibleText.button("❚❚ Pause")).click();
        waitFor("pausing the blink offers Play", () -> blink.button(VisibleText.button("▶ Play")).target().isEnabled());
        String pausedOn = frameCaption(blink);
        blink.button(VisibleText.button("▶")).click();
        waitForFrameChange(blink, pausedOn);

        closeViewer(blink);
        waitFor("closing the blink stops it", () -> showingLabel("Blinking stopped").contains("Blinking stopped"));
    }

    /**
     * Each viewer's ↗ button brings the other forward on the frame shown. Both stay open: the blink pauses and resumes
     * as it was, Preview's trial values stay usable beside it, and only closing the blink frees the main window.
     */
    @Test(timeout = 180_000)
    public void blinkAndPreviewStayOpenForEachOther() throws Exception {
        importFolder(SolvedSession.create(folder.newFolder("solved").toPath()));
        window().table().selectRows(0, 1, 2);
        window().button(VisibleText.button("Blink Selected")).click();
        FrameFixture blink = viewer("Blink");
        waitFor("the blink is playing", () -> blink.checkBox(VisibleText.checkBox("Show catalog annotations")).isEnabled());

        // Paused by hand: it stays paused when it comes back, on the frame Preview shows.
        blink.button(VisibleText.button("❚❚ Pause")).click();
        blink.button(VisibleText.button("Open in Preview ↗")).click();
        FrameFixture preview = viewer("Preview Frame");
        waitForFrame(preview, "Frame ", "detecting");
        assertTrue("the blink stays open", GuiActionRunner.execute(() -> blink.target().isShowing()));
        assertFalse("the blink still holds the main window",
                GuiActionRunner.execute(() -> window().button(VisibleText.button("Detect Moving Targets")).target().isEnabled()));
        assertTrue("trial values stay usable beside a blink",
                GuiActionRunner.execute(() -> preview.spinner(new GenericTypeMatcher<>(javax.swing.JSpinner.class) {
                    @Override
                    protected boolean isMatching(javax.swing.JSpinner spinner) {
                        return "Detection Sigma".equals(spinner.getAccessibleContext().getAccessibleName());
                    }
                }).target().isEnabled()));

        // Among the three frames blinked, whichever frame the blink had reached.
        boolean last = frameCaption(preview).contains("Frame 3 of 5");
        preview.button(VisibleText.button(last ? "◀" : "▶")).click();
        waitForFrame(preview, "Frame ", "detecting");
        String number = frameCaption(preview).replaceAll("Frame (\\d+) of.*", "$1");
        preview.button(VisibleText.button("Blink ↗")).requireEnabled().click();
        waitFor("the blink comes back on Preview's frame", () -> frameCaption(blink).contains("Frame " + number + " of 3"));
        blink.button(VisibleText.button("▶ Play")).requireEnabled();

        // Paused by Open in Preview: it plays again when it comes back.
        blink.button(VisibleText.button("▶ Play")).click();
        blink.button(VisibleText.button("Open in Preview ↗")).click();
        waitFor("leaving pauses the blink", () -> blink.button(VisibleText.button("▶ Play")).target().isEnabled());
        preview.button(VisibleText.button("Blink ↗")).click();
        waitFor("the blink plays again", () -> blink.button(VisibleText.button("❚❚ Pause")).target().isEnabled());

        closeViewer(blink);
        waitFor("closing the blink frees the main window",
                () -> window().button(VisibleText.button("Detect Moving Targets")).target().isEnabled());
        assertTrue("Preview stays open", GuiActionRunner.execute(() -> preview.target().isShowing()));
        closeViewer(preview);
    }

    /** A double click on a frame in the list shows that frame in Preview Frame. */
    @Test(timeout = 180_000)
    public void doubleClickShowsTheFrameInPreview() throws Exception {
        importFolder(SolvedSession.create(folder.newFolder("solved").toPath()));
        window().table().cell(org.assertj.swing.data.TableCell.row(1).column(0)).doubleClick();
        FrameFixture preview = viewer("Preview Frame");
        waitForFrame(preview, "Frame 2 of 5", "detecting");
        closeViewer(preview);
    }

    private FrameFixture viewer(String titleStart) {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            @Override
            protected boolean isMatching(JFrame frame) {
                return frame.getTitle() != null && frame.getTitle().startsWith(titleStart) && frame.isShowing();
            }
        }).withTimeout(20_000).using(robot());
    }

    /** The blink may already have played past the first frame, so a step only has to leave the paused frame. */
    private void waitForFrameChange(FrameFixture viewer, String previous) {
        String last = previous;
        long deadline = System.nanoTime() + 20L * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            robot().waitForIdle();
            try {
                last = frameCaption(viewer);
                if (!last.equals(previous) && last.contains("of 3") && !last.contains("reading")) {
                    return;
                }
            } catch (RuntimeException lookup) {
                last = lookup.getMessage();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Stepping did not leave \"" + previous + "\". Last: " + last);
    }

    private void waitForFrame(FrameFixture viewer, String caption, String busyWord) {
        String last = "";
        long deadline = System.nanoTime() + 20L * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            robot().waitForIdle();
            try {
                last = frameCaption(viewer);
                if (last.contains(caption) && !last.contains(busyWord)) {
                    return;
                }
            } catch (RuntimeException lookup) {
                last = lookup.getMessage();
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Wanted \"" + caption + "\" without \"" + busyWord + "\". Last: " + last);
    }

    private String frameCaption(FrameFixture viewer) {
        return viewer.label(new GenericTypeMatcher<>(JLabel.class, true) {
            @Override
            protected boolean isMatching(JLabel label) {
                String text = label.getText();
                return text != null && text.startsWith("Frame ");
            }
        }).target().getText();
    }

    /** The viewer's own close, which hides it. Disposing the frame would skip that. */
    private void closeViewer(FrameFixture viewer) {
        GuiActionRunner.execute(() -> viewer.target().dispatchEvent(
                new WindowEvent(viewer.target(), WindowEvent.WINDOW_CLOSING)));
        waitFor("the viewer closes", () -> !viewer.target().isShowing());
    }
}
