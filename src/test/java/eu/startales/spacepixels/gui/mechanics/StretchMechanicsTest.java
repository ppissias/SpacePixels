package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.JOptionPaneFinder;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import javax.swing.JFrame;
import org.assertj.swing.fixture.JOptionPaneFixture;
import org.assertj.swing.fixture.JSliderFixture;
import org.junit.Test;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;

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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Image Stretch and Batch Stretch, driven by the controls the user sees.
 * Run with {@code ./gradlew guiMechanicsTest}.
 */
public class StretchMechanicsTest extends MechanicsTestCase {
    private FrameFixture stretchEditor;

    @Test(timeout = 180_000)
    public void slidersChangeThePreviewAndBatchStretchCompletes() throws Exception {
        Path session = importSyntheticSequence();
        openEditor();
        editor().checkBox(VisibleText.checkBox("Stretch display")).check();
        waitUntilPreviewSettled("the first frame is previewed");

        long[] before = previewSignatures();
        assertNotEquals("the stretched preview differs from the linear one", before[0], before[1]);

        List<JSlider> sliders = VisibleText.showingSliders(editor().target());
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

        editor().checkBox(VisibleText.checkBox("Stretch display")).uncheck();
        waitFor("turning stretch display off shows the linear frame on both sides", () -> {
            if (frameCaption().contains("rendering")) {
                return false;
            }
            long[] now = previewSignatures();
            return editor().label(VisibleText.labelContaining("Linear (stretch off)")).target().getText().contains("Linear (stretch off)")
                    && now[0] != 0 && now[0] == now[1] && now[1] != afterSliders[1];
        });

        editor().button(VisibleText.button("Next ▶")).click();
        waitFor("the preview steps to the next frame", () -> {
            String label = frameCaption();
            return label.contains("Frame 2 of") && label.contains("frame_02.fit") && !label.contains("rendering");
        });

        editor().button(VisibleText.button("Export stretched copies…")).click();
        var export = JOptionPaneFinder.findOptionPane().withTimeout(15_000).using(robot());
        export.button(VisibleText.button("Export")).click();

        JOptionPaneFixture done = JOptionPaneFinder.findOptionPane().withTimeout(60_000).using(robot());
        Object message = GuiActionRunner.execute(() -> done.target().getMessage());
        assertTrue(String.valueOf(message).contains("Batch stretch completed successfully"));
        done.okButton().click();

        File[] stretched = session.toFile().listFiles(file -> file.isDirectory() && file.getName().contains("stretched"));
        assertEquals(1, stretched.length);
        assertEquals(5, stretched[0].listFiles(File::isFile).length);
    }

    /** Editor navigation preserves Main's selection, while viewer links reuse it and all controls remain live. */
    @Test(timeout = 180_000)
    public void editorSharesControlsAndViewerFrameWithoutChangingMainSelection() throws Exception {
        Path sequence = importSyntheticSequence();
        GuiActionRunner.execute(() -> window().table().target().setRowSelectionInterval(0, 2));
        openEditor();
        java.awt.Frame firstEditor = editor().target();
        waitUntilPreviewSettled("initial comparison renders");
        editor().button(VisibleText.button("Next ▶")).click();
        waitFor("editor steps independently", () -> frameCaption().contains("Frame 2 of") && !frameCaption().contains("rendering"));
        assertEquals(3, GuiActionRunner.execute(() -> window().table().target().getSelectedRowCount()).intValue());
        closeEditor();

        window().button(VisibleText.button("Preview Frame")).click();
        FrameFixture preview = viewer("Preview Frame");
        preview.button(VisibleText.button("▶")).click();
        waitFor("Preview shows frame two", () -> preview.label(VisibleText.labelContaining("Frame 2 of")).target().getText().contains("frame_02.fit"));
        GuiActionRunner.execute(() -> preview.button(VisibleText.button("Open stretch editor…")).target().doClick());
        stretchEditor = findEditor();
        assertTrue("the same editor window is reused", firstEditor == editor().target());
        waitFor("editor opens on Preview's frame", () -> frameCaption().contains("frame_02.fit") && !frameCaption().contains("rendering"));

        var editorNumber = GuiActionRunner.execute(() -> VisibleText.spinnerByLabel(editor().target(), "Black Point"));
        var viewerNumber = preview.spinner(new GenericTypeMatcher<javax.swing.JSpinner>(javax.swing.JSpinner.class, true) {
            @Override protected boolean isMatching(javax.swing.JSpinner spinner) {
                return spinner.getAccessibleContext().getAccessibleName().contains("Black Point");
            }
        }).target();
        GuiActionRunner.execute(() -> editorNumber.setValue(35));
        waitFor("editor numeric input reaches Preview", () -> GuiActionRunner.execute(() -> ((Number) viewerNumber.getValue()).intValue() == 35));
        GuiActionRunner.execute(() -> viewerNumber.setValue(40));
        waitFor("Preview numeric input reaches editor", () -> GuiActionRunner.execute(() -> ((Number) editorNumber.getValue()).intValue() == 40));
        assertEquals(3, GuiActionRunner.execute(() -> window().table().target().getSelectedRowCount()).intValue());

        editor().button(VisibleText.button("Export stretched copies…")).click();
        var cancelled = JOptionPaneFinder.findOptionPane().withTimeout(15_000).using(robot());
        cancelled.button(VisibleText.button("Cancel")).click();
        assertFalse("cancelling export writes no copies", sequence.resolve("_stretched").toFile().exists());
        GuiActionRunner.execute(() -> window().target().toFront());
        importSyntheticSequence();
        waitFor("a new session disposes the editor", () -> !GuiActionRunner.execute(firstEditor::isDisplayable));
        openEditor();
        assertEquals("shared stretch survives closing and importing", 40,
                GuiActionRunner.execute(() -> application().getStretchPanel().getStretchSlider().getValue()).intValue());
    }

    /** Show in Preview opens the one Preview Frame on the editor's frame; the editor stays open and has no Close button. */
    @Test(timeout = 180_000)
    public void showInPreviewFollowsTheEditorsFrame() throws Exception {
        importSyntheticSequence();
        openEditor();
        waitUntilPreviewSettled("initial comparison renders");
        assertTrue("the window's own close is enough", robot().finder().findAll(editor().target(), new GenericTypeMatcher<>(javax.swing.JButton.class, true) {
            @Override protected boolean isMatching(javax.swing.JButton button) {
                return "Close".equals(button.getText()) || "Show full size".equals(button.getText());
            }
        }).isEmpty());
        editor().button(VisibleText.button("Next ▶")).click();
        waitFor("editor steps to frame two", () -> frameCaption().contains("Frame 2 of") && !frameCaption().contains("rendering"));
        GuiActionRunner.execute(() -> editor().button(VisibleText.button("Show in Preview")).target().doClick());
        FrameFixture preview = viewer("Preview Frame");
        waitFor("Preview shows the editor's frame", () -> preview.label(VisibleText.labelContaining("Frame 2 of")).target().getText().contains("frame_02.fit"));
        assertTrue("the editor stays open", GuiActionRunner.execute(editor().target()::isShowing));

        // Preview is in front of the editor now, so a real click would land on it.
        GuiActionRunner.execute(() -> editor().button(VisibleText.button("Next ▶")).target().doClick());
        waitFor("editor steps to frame three", () -> frameCaption().contains("Frame 3 of") && !frameCaption().contains("rendering"));
        GuiActionRunner.execute(() -> editor().button(VisibleText.button("Show in Preview")).target().doClick());
        waitFor("the open Preview follows", () -> preview.label(VisibleText.labelContaining("Frame 3 of")).target().getText().contains("frame_03.fit"));
    }

    /** Colour frames, which the detection cannot read, show in Preview in colour without raw detections. */
    @Test(timeout = 180_000)
    public void showInPreviewShowsColourFramesWithoutRawDetections() throws Exception {
        Path sequence = SyntheticDatasetFactory.createColor16FitsSequence(folder.newFolder("colour-preview").toPath(), "session", 5);
        GuiActionRunner.execute(() -> application().importFolder(sequence.toFile()));
        var offered = JOptionPaneFinder.findOptionPane().withTimeout(30_000).using(robot());
        offered.noButton().click();
        openEditor();
        waitFor("the colour frame renders", () -> frameCaption().contains("Frame 1 of") && !frameCaption().contains("rendering"));
        GuiActionRunner.execute(() -> editor().button(VisibleText.button("Show in Preview")).target().doClick());
        FrameFixture preview = viewer("Preview Frame");
        waitFor("Preview says what it shows", () -> preview.label(VisibleText.labelContaining("colour · no raw detections on this frame")).target().isShowing());
        preview.button(VisibleText.button("▶")).click();
        waitFor("Preview steps through the colour frames", () -> preview.label(VisibleText.labelContaining("Frame 2 of")).target().getText().contains("no raw detections"));
    }

    /** Blink can open the editor on its displayed frame while its existing export/job lock remains in force. */
    @Test(timeout = 180_000)
    public void blinkOpensStretchEditorWithExportDisabled() throws Exception {
        importSyntheticSequence();
        GuiActionRunner.execute(() -> window().table().target().setRowSelectionInterval(0, 2));
        window().button(VisibleText.button("Blink Selected")).click();
        FrameFixture blink = viewer("Blink");
        blink.button(VisibleText.button("❚❚ Pause")).click();
        String shown = blink.label(VisibleText.labelContaining("Frame ")).target().getText();
        String frameName = java.util.regex.Pattern.compile("frame_\\d+\\.fit").matcher(shown).results()
                .map(java.util.regex.MatchResult::group).findFirst().orElseThrow();
        GuiActionRunner.execute(() -> blink.button(VisibleText.button("Open stretch editor…")).target().doClick());
        stretchEditor = findEditor();
        waitFor("editor uses the frame Blink displays", () -> frameCaption().contains(frameName) && !frameCaption().contains("rendering"));
        assertFalse("Blink continues to guard disk export", editor().button(VisibleText.button("Export stretched copies…")).target().isEnabled());
        assertTrue("opening the editor retains paused playback", blink.button(VisibleText.button("▶ Play")).target().isEnabled());
        assertTrue(GuiActionRunner.execute(blink.target()::isShowing));
        GuiActionRunner.execute(() -> blink.target().dispatchEvent(new java.awt.event.WindowEvent(blink.target(), java.awt.event.WindowEvent.WINDOW_CLOSING)));
        waitFor("closing Blink releases export", () -> editor().button(VisibleText.button("Export stretched copies…")).target().isEnabled());
    }

    /** Finds a showing sequence viewer using its public window title. */
    private FrameFixture viewer(String title) {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            @Override protected boolean isMatching(JFrame frame) {
                return frame.isShowing() && frame.getTitle().startsWith(title);
            }
        }).withTimeout(20_000).using(robot());
    }

    /** Conversion remains linear even after selecting an aggressive display stretch in the same application. */
    @Test(timeout = 180_000)
    public void convertToMonoDoesNotCreateStretchedCopies() throws Exception {
        Path sequence = SyntheticDatasetFactory.createColor16FitsSequence(folder.newFolder("colour").toPath(), "session", 5);
        GuiActionRunner.execute(() -> application().importFolder(sequence.toFile()));
        waitFor("colour dataset imported", () -> GuiActionRunner.execute(() -> application().getSession() != null
                && application().getSession().getFiles().length == 5));
        var offered = JOptionPaneFinder.findOptionPane().withTimeout(15_000).using(robot());
        assertTrue(String.valueOf(GuiActionRunner.execute(() -> offered.target().getMessage()))
                .contains("Convert the dataset to linear monochrome copies now?"));
        offered.noButton().click();
        openEditor();
        editor().checkBox(VisibleText.checkBox("Stretch display")).check();
        for (JSlider slider : VisibleText.showingSliders(editor().target())) {
            new JSliderFixture(robot(), slider).slideTo(slider.getMaximum());
        }
        closeEditor();
        window().tabbedPane().selectTab("Main");
        window().button(VisibleText.button("Convert to Mono")).click();
        var done = JOptionPaneFinder.findOptionPane().withTimeout(60_000).using(robot());
        assertTrue(String.valueOf(GuiActionRunner.execute(() -> done.target().getMessage()))
                .contains("Batch conversion to mono completed successfully"));
        done.noButton().click();
        assertEquals(5, sequence.resolve("_mono").toFile().listFiles(File::isFile).length);
        assertFalse(sequence.resolve("_mono_stretched").toFile().exists());
        assertFalse(sequence.resolve("_stretched").toFile().exists());
        assertFalse(sequence.resolve("_mono/_stretched").toFile().exists());
    }

    /** Accepting the colour-import offer chains conversion and the existing optional mono-import question. */
    @Test(timeout = 180_000)
    public void colourImportOffersConversionAndThenImportsTheMonoDatasetWhenAccepted() throws Exception {
        Path sequence = SyntheticDatasetFactory.createColor16FitsSequence(folder.newFolder("auto-colour").toPath(), "session", 5);
        GuiActionRunner.execute(() -> application().importFolder(sequence.toFile()));
        var offered = JOptionPaneFinder.findOptionPane().withTimeout(30_000).using(robot());
        assertTrue(String.valueOf(GuiActionRunner.execute(() -> offered.target().getMessage()))
                .contains("Convert the dataset to linear monochrome copies now?"));
        offered.yesButton().click();
        var converted = JOptionPaneFinder.findOptionPane().withTimeout(60_000).using(robot());
        assertTrue(String.valueOf(GuiActionRunner.execute(() -> converted.target().getMessage()))
                .contains("Import the newly created mono directory now?"));
        converted.yesButton().click();
        waitFor("the converted mono dataset is imported", () -> GuiActionRunner.execute(() -> application().getSession() != null
                && java.util.Arrays.stream(application().getSession().getFiles()).allMatch(file -> file.isMonochrome()
                && Path.of(file.getFilePath()).getParent().equals(sequence.resolve("_mono")))));
        assertFalse(sequence.resolve("_mono_stretched").toFile().exists());
        assertFalse(sequence.resolve("_stretched").toFile().exists());
    }

    /** Opens the workspace using Main's visible entry point. */
    private void openEditor() {
        window().button(VisibleText.button("Image Stretch…")).click();
        stretchEditor = findEditor();
    }

    /** Closes the workspace as the window's close button does. */
    private void closeEditor() {
        GuiActionRunner.execute(() -> editor().target().dispatchEvent(
                new java.awt.event.WindowEvent(editor().target(), java.awt.event.WindowEvent.WINDOW_CLOSING)));
        waitFor("the editor closes", () -> !GuiActionRunner.execute(editor().target()::isShowing));
    }

    /** The mounted editor, also available to assertions already running on the EDT. */
    private FrameFixture editor() { return stretchEditor; }

    /** Finds the one shared, modeless stretch workspace after an entry point opens it. */
    private FrameFixture findEditor() {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            @Override protected boolean isMatching(JFrame frame) {
                return frame.isShowing() && "Image Stretch".equals(frame.getTitle());
            }
        }).withTimeout(15_000).using(robot());
    }

    /** The preview's own caption, not the help text that also says "Preview Frame". */
    private String frameCaption() {
        return editor().label(new GenericTypeMatcher<>(JLabel.class, true) {
            @Override
            protected boolean isMatching(JLabel label) {
                String text = label.getText();
                return text != null && text.startsWith("Frame ");
            }
        }).target().getText();
    }

    private void waitUntilPreviewSettled(String description) {
        waitFor(description, () -> {
            String label = frameCaption();
            return label.contains("Frame 1 of") && !label.contains("rendering") && previewSignatures()[0] != 0;
        });
    }

    /** Pixel signatures of the linear preview and the stretched preview, in that order. */
    private long[] previewSignatures() {
        return GuiActionRunner.execute(() -> {
            List<Component> views = new ArrayList<>();
            collectPreviews(editor().target(), views);
            if (views.size() != 2) {
                throw new AssertionError("Expected the two stretch previews, found " + views.size());
            }
            return new long[]{signature(views.get(0)), signature(views.get(1))};
        });
    }

    private static void collectPreviews(Component component, List<Component> views) {
        if (component.isShowing() && component.getClass().getSimpleName().equals("FrameView")) {
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
            Field field = view.getClass().getDeclaredField("picture");
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
