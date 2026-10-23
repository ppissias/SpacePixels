package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.RunPreview;
import eu.startales.spacepixels.util.RunPreviewSink;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The progress dialog's live view: pictures of the current job only, throttled, and hidden on request. */
public class ProcessingProgressDialogTest {

    private ProcessingProgressDialog dialog;
    private final List<Boolean> savedPreferences = new ArrayList<>();

    @Before
    public void createDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            dialog = new ProcessingProgressDialog(null);
            dialog.setPreviewPreference(true, savedPreferences::add);
        });
    }

    @After
    public void disposeDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> dialog.dispose());
    }

    private static RunPreview picture(String caption) {
        return RunPreview.of(new BufferedImage(120, 80, BufferedImage.TYPE_BYTE_GRAY), caption);
    }

    /** Lets pictures handed over on this thread reach the dialog. */
    private static void settle() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> { });
    }

    private RunPreviewSink beginJob(boolean previews) throws Exception {
        AtomicReference<RunPreviewSink> sink = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            sink.set(dialog.previewSink());
            dialog.beginPreview(previews);
        });
        return sink.get();
    }

    private String caption() throws Exception {
        AtomicReference<String> text = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> text.set(dialog.previewCaptionText()));
        return text.get();
    }

    private boolean hasPicture() throws Exception {
        AtomicReference<Boolean> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> shown.set(dialog.previewHasPicture()));
        return shown.get();
    }

    @Test
    public void aPictureAndItsCaptionReachTheLiveView() throws Exception {
        RunPreviewSink sink = beginJob(true);
        assertTrue(sink.wantsPreview(true));
        sink.preview(picture("Frame 1 of 33"));
        settle();
        assertTrue(hasPicture());
        assertTrue(caption(), caption().contains("Frame 1 of 33"));

        sink.preview(RunPreview.captionOnly("Picture from loading · the detection is running"));
        settle();
        assertTrue("a caption-only update keeps the picture", hasPicture());
        assertTrue(caption().contains("the detection is running"));

        sink.preview(RunPreview.cleared("Pass 2 of 3 · 7 frames"));
        settle();
        assertFalse("a new pass removes the previous pass's picture", hasPicture());
        assertTrue(caption().contains("Pass 2 of 3"));
    }

    @Test
    public void severalFindingsAreShownInTurnUntilTheNextPreview() throws Exception {
        RunPreviewSink sink = beginJob(true);
        sink.preview(RunPreview.slides(List.of(picture("Moving-object candidate · provisional"), picture("Streak · provisional"))));
        settle();
        assertTrue(caption(), caption().contains("Moving-object candidate"));
        assertTrue(turning());
        SwingUtilities.invokeAndWait(() -> dialog.showNextSlide());
        assertTrue(caption(), caption().contains("Streak"));
        SwingUtilities.invokeAndWait(() -> dialog.showNextSlide());
        assertTrue("the slides start again", caption().contains("Moving-object candidate"));

        SwingUtilities.invokeAndWait(this::clickToggle);
        assertFalse("a hidden picture does not turn", turning());
        SwingUtilities.invokeAndWait(this::clickToggle);
        assertTrue("showing it again turns the slides again", turning());

        sink.preview(picture("Report picture: track map"));
        settle();
        assertFalse("a single picture replaces the slides", turning());
        assertTrue(caption().contains("Report picture: track map"));
        SwingUtilities.invokeAndWait(() -> dialog.endPreview());
        assertFalse(turning());
    }

    private boolean turning() throws Exception {
        AtomicReference<Boolean> running = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> running.set(dialog.slidesTurning()));
        return running.get();
    }

    @Test
    public void picturesAreThrottledButAStageChangeIsShownAtOnce() throws Exception {
        RunPreviewSink sink = beginJob(true);
        assertTrue(sink.wantsPreview(false));
        assertFalse("a second picture within the interval is not made", sink.wantsPreview(false));
        assertTrue("a stage change is shown at once", sink.wantsPreview(true));
    }

    @Test
    public void aFinishedJobsLatePicturesAreDropped() throws Exception {
        RunPreviewSink first = beginJob(true);
        SwingUtilities.invokeAndWait(() -> dialog.endPreview());
        assertFalse("nothing is wanted between jobs", first.wantsPreview(true));
        RunPreviewSink second = beginJob(true);
        first.preview(picture("from the first job"));
        settle();
        assertFalse(hasPicture());
        second.preview(picture("from the second job"));
        settle();
        assertTrue(caption().contains("from the second job"));
    }

    @Test
    public void aJobWithoutPicturesShowsNoLiveViewAndMakesNone() throws Exception {
        RunPreviewSink sink = beginJob(false);
        AtomicReference<Boolean> shown = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> shown.set(dialog.isPreviewAreaShown()));
        assertFalse(shown.get());
        assertFalse(sink.wantsPreview(true));
    }

    @Test
    public void hidingThePictureStopsItsWorkAndIsRemembered() throws Exception {
        RunPreviewSink sink = beginJob(true);
        AtomicReference<Integer> heights = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            heights.set(dialog.getHeight());
            dialog.setPreviewPreference(true, savedPreferences::add);
        });
        SwingUtilities.invokeAndWait(() -> clickToggle());
        assertFalse("a hidden picture is not made", sink.wantsPreview(true));
        assertEquals(List.of(false), savedPreferences);
        AtomicReference<Integer> hiddenHeight = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> hiddenHeight.set(dialog.getHeight()));
        assertTrue("the dialog shrinks without the picture", hiddenHeight.get() < heights.get());

        SwingUtilities.invokeAndWait(() -> clickToggle());
        assertTrue(sink.wantsPreview(true));
        assertEquals(List.of(false, true), savedPreferences);
    }

    @Test
    public void aSavedHiddenChoiceAppliesToTheNextJob() throws Exception {
        SwingUtilities.invokeAndWait(() -> dialog.setPreviewPreference(false, savedPreferences::add));
        RunPreviewSink sink = beginJob(true);
        assertFalse(sink.wantsPreview(true));
        assertTrue("restoring the choice does not save it again", savedPreferences.isEmpty());
    }

    @Test
    public void aLongMessageWrapsWithoutWideningTheDialog() throws Exception {
        beginJob(true);
        AtomicReference<Integer> width = new AtomicReference<>();
        AtomicReference<Integer> widened = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            width.set(dialog.getWidth());
            dialog.updateProgress(40, "Pass 7 frames: " + "measuring the brightness of every comparison star ".repeat(4));
            widened.set(dialog.getWidth());
        });
        assertEquals(width.get(), widened.get());
    }

    /** As the settings layout test does: larger base fonts wrap into more lines, all shown, at the same width. */
    @Test
    public void longTextFitsAtLargerFontSizes() throws Exception {
        String message = "Writing the report: measuring the brightness of every comparison star in pass 7 ".repeat(3);
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Font original = javax.swing.UIManager.getFont("Label.font");
            try {
                for (int size : new int[]{13, 20, 26}) {
                    javax.swing.UIManager.put("Label.font", new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.PLAIN, UiStyle.space(size)));
                    ProcessingProgressDialog large = new ProcessingProgressDialog(null);
                    try {
                        large.beginPreview(true);
                        int width = large.getWidth();
                        large.updateProgress(40, message);
                        large.validate();
                        java.awt.Container content = large.getContentPane();
                        assertTrue("every wrapped line is shown at font " + size,
                                content.getPreferredSize().height <= content.getHeight());
                        assertEquals("the dialog keeps its width at font " + size, width, large.getWidth());
                    } finally {
                        large.dispose();
                    }
                }
            } finally {
                javax.swing.UIManager.put("Label.font", original);
            }
        });
    }

    @Test
    public void theHideShowControlIsAKeyboardLinkWithAName() throws Exception {
        AtomicReference<javax.swing.JButton> toggle = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> toggle.set(findToggle()));
        assertTrue(toggle.get().isFocusable());
        assertEquals("Hide the live-view picture", toggle.get().getAccessibleContext().getAccessibleName());
        SwingUtilities.invokeAndWait(() -> toggle.get().doClick());
        assertEquals("Show the live-view picture", toggle.get().getAccessibleContext().getAccessibleName());
        assertEquals("Show picture", toggle.get().getText());
    }

    private javax.swing.JButton findToggle() {
        for (java.awt.Component component : allComponents(dialog.getContentPane())) {
            if (component instanceof javax.swing.JButton button
                    && (button.getText().equals("Hide picture") || button.getText().equals("Show picture"))) {
                return button;
            }
        }
        throw new AssertionError("No Hide/Show picture button");
    }

    private void clickToggle() {
        for (java.awt.Component component : allComponents(dialog.getContentPane())) {
            if (component instanceof javax.swing.JButton button
                    && (button.getText().equals("Hide picture") || button.getText().equals("Show picture"))) {
                button.doClick();
                return;
            }
        }
        throw new AssertionError("No Hide/Show picture button");
    }

    private static List<java.awt.Component> allComponents(java.awt.Container container) {
        List<java.awt.Component> components = new ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            components.add(child);
            if (child instanceof java.awt.Container nested) {
                components.addAll(allComponents(nested));
            }
        }
        return components;
    }
}
