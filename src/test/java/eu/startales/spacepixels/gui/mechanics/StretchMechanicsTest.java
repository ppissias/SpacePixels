package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.assertj.swing.fixture.JSliderFixture;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JSlider;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Image Stretch and Batch Stretch, driven by the controls the user sees.
 * Run with {@code ./gradlew guiMechanicsTest}.
 */
public class StretchMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 180_000)
    public void slidersChangeThePreviewAndBatchStretchCompletes() throws Exception {
        Path session = importSyntheticSequence();
        window().tabbedPane().selectTab("Image Stretch");
        window().checkBox(VisibleText.checkBox("Stretch display")).check();
        waitUntilPreviewSettled("the first frame is previewed");

        long[] before = previewSignatures();
        assertNotEquals("the stretched preview differs from the linear one", before[0], before[1]);

        List<JSlider> sliders = VisibleText.showingSliders(window().target());
        assertEquals("the two stretch sliders", 2, sliders.size());
        int[] movedTo = new int[sliders.size()];
        for (int i = 0; i < sliders.size(); i++) {
            JSlider slider = sliders.get(i);
            int value = GuiActionRunner.execute(slider::getValue);
            movedTo[i] = value < slider.getMaximum() ? slider.getMaximum() : slider.getMinimum();
            new JSliderFixture(robot(), slider).slideTo(movedTo[i]);
        }
        waitFor("the preview finishes after the sliders move", () -> !frameCaption().contains("rendering"));
        long[] afterSliders = previewSignatures();
        assertEquals("the linear preview stays put, stretched was " + before[1] + " and is " + afterSliders[1]
                        + ", sliders now " + GuiActionRunner.execute(sliders.get(0)::getValue) + " and "
                        + GuiActionRunner.execute(sliders.get(1)::getValue) + " (moved toward " + movedTo[0] + " and " + movedTo[1] + ")",
                before[0], afterSliders[0]);
        assertNotEquals("moving the sliders changes the stretched preview", before[1], afterSliders[1]);

        window().checkBox(VisibleText.checkBox("Stretch display")).uncheck();
        waitFor("turning stretch display off shows the linear frame on both sides", () -> {
            if (frameCaption().contains("rendering")) {
                return false;
            }
            long[] now = previewSignatures();
            return showingLabel("Linear (stretch off)").contains("Linear (stretch off)")
                    && now[0] != 0 && now[0] == now[1] && now[1] != afterSliders[1];
        });

        window().checkBox(VisibleText.checkBox("Enable batch stretch")).check();
        window().button(VisibleText.button("Next ▶")).click();
        waitFor("the preview steps to the next frame", () -> {
            String label = frameCaption();
            return label.contains("Frame 2 of") && label.contains("frame_02.fit") && !label.contains("rendering");
        });

        window().tabbedPane().selectTab("Main");
        assertTrue(window().button(VisibleText.button("Batch Stretch")).target().isEnabled());
        window().button(VisibleText.button("Batch Stretch")).click();

        JOptionPaneFixture done = JOptionPaneFinder.findOptionPane().withTimeout(60_000).using(robot());
        Object message = GuiActionRunner.execute(() -> done.target().getMessage());
        assertTrue(String.valueOf(message).contains("Batch stretch completed successfully"));
        done.okButton().click();

        File[] stretched = session.toFile().listFiles(file -> file.isDirectory() && file.getName().contains("stretched"));
        assertEquals(1, stretched.length);
        assertEquals(5, stretched[0].listFiles(File::isFile).length);
    }

    /** The preview's own caption, not the help text that also says "Preview Frame". */
    private String frameCaption() {
        return window().label(new GenericTypeMatcher<>(JLabel.class, true) {
            @Override
            protected boolean isMatching(JLabel label) {
                String text = label.getText();
                return text != null && text.startsWith("Frame ");
            }
        }).target().getText();
    }

    private void waitUntilPreviewSettled(String description) {
        waitFor(description, () -> {
            String label = showingLabel("Frame 1 of");
            return label.contains("Frame 1 of") && !label.contains("rendering") && previewSignatures()[0] != 0;
        });
    }

    /** Pixel signatures of the linear preview and the stretched preview, in that order. */
    private long[] previewSignatures() {
        return GuiActionRunner.execute(() -> {
            List<Component> views = new ArrayList<>();
            collectPreviews(window().target(), views);
            if (views.size() != 2) {
                throw new AssertionError("Expected the two stretch previews, found " + views.size());
            }
            return new long[]{signature(views.get(0)), signature(views.get(1))};
        });
    }

    private static void collectPreviews(Component component, List<Component> views) {
        if (component.isShowing() && component.getClass().getSimpleName().equals("ZoomableImageView")) {
            views.add(component);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectPreviews(child, views);
            }
        }
    }

    private static long signature(Component view) {
        BufferedImage image;
        try {
            Field field = view.getClass().getDeclaredField("image");
            field.setAccessible(true);
            image = (BufferedImage) field.get(view);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        if (image == null) {
            return 0L;
        }
        long hash = 1125899906842597L;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash = hash * 31 + image.getRGB(x, y);
            }
        }
        return hash == 0L ? 1L : hash;
    }
}
