package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.TrackOverlay;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.assertj.swing.fixture.JCheckBoxFixture;
import org.junit.Test;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/**
 * Preview Frame and Manual Transient Inspection show the tracks of the session's last detection run, as Blink does:
 * Show tracks (T) is off until a run has tracks, and a run that ends while Preview Frame is open makes them available.
 */
public class LastRunTracksMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 180_000)
    public void previewAndInspectionShowTheLastRunsTracks() throws Exception {
        importFrames();
        window().table().selectRows(0);
        window().button(VisibleText.button("Preview Frame")).click();
        FrameFixture preview = viewer("Preview Frame");
        waitFor("first preview frame", () -> caption(preview).contains("Frame 1 of") && !caption(preview).contains("detecting"));
        JCheckBoxFixture previewTracks = preview.checkBox(VisibleText.checkBox("Show tracks"));
        assertFalse("Show tracks needs a detection run", previewTracks.target().isEnabled());

        endARunWithOneTrack();
        waitFor("the run's tracks become available", () -> previewTracks.target().isEnabled());
        assertFalse(previewTracks.target().isSelected());
        JComponent image = image(preview, "Preview Frame image");
        focus(image);
        robot().pressAndReleaseKeys(KeyEvent.VK_T);
        waitFor("T shows the tracks", () -> previewTracks.target().isSelected());
        assertTrue(GuiActionRunner.execute(() -> image.getAccessibleContext().getAccessibleDescription()).contains("Tracks on."));
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape on the image closes Preview Frame", () -> !preview.target().isShowing());

        window().button(VisibleText.button("Manual Transient Inspection")).click();
        FrameFixture inspection = viewer("Manual Transient Inspection");
        waitFor("first inspected frame", () -> caption(inspection).contains("Frame 1 of") && !caption(inspection).contains("preparing"));
        JCheckBoxFixture inspectionTracks = inspection.checkBox(VisibleText.checkBox("Show tracks"));
        assertTrue("The last run's tracks are offered here too", inspectionTracks.target().isEnabled());
        inspectionTracks.check();
        JComponent inspected = image(inspection, "Manual Transient Inspection image");
        assertTrue(GuiActionRunner.execute(() -> inspected.getAccessibleContext().getAccessibleDescription()).contains("Tracks on."));
        focus(inspected);
        robot().pressAndReleaseKeys(KeyEvent.VK_ESCAPE);
        waitFor("Escape on the image closes the inspection", () -> !inspection.target().isShowing());
    }

    /**
     * Stands in for a detection run: a job that leaves a track through the first three frames of the session where a
     * run leaves its tracks. Its end unlocks the window, as a run's does.
     */
    private void endARunWithOneTrack() throws Exception {
        FitsFileInformation[] files = GuiActionRunner.execute(() -> application().getSession().getFiles());
        Track track = new Track();
        for (int frame = 0; frame < 3; frame++) {
            DetectedObject point = new DetectedObject(20 + 10 * frame, 30 + 5 * frame, 1000, 9);
            point.sourceFrameIndex = frame;
            point.sourceFilename = new File(files[frame].getFilePath()).getName();
            track.addPoint(point);
        }
        TrackOverlay overlay = TrackOverlay.from(PipelineResult.builder().tracks(List.of(track)).build());
        CountDownLatch ended = new CountDownLatch(1);
        var admission = GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.DETECTION,
                application().getSession(), context -> {
                    application().getImageProcessing().getSessionFrames().setOverlays(overlay, null);
                    return JobOutcome.completed(Boolean.TRUE);
                }, (handle, outcome) -> ended.countDown()));
        assertTrue(admission.admitted());
        assertTrue(ended.await(30, TimeUnit.SECONDS));
    }

    private void importFrames() throws Exception {
        File directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(),
                "session", SyntheticDatasetFactory.DEFAULT_FRAME_COUNT).toFile();
        GuiActionRunner.execute(() -> application().importFolder(directory));
        waitFor("synthetic session installed", () -> GuiActionRunner.execute(() -> application().getSession() != null));
    }

    private FrameFixture viewer(String titleStart) {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            @Override
            protected boolean isMatching(JFrame frame) {
                return frame.getTitle() != null && frame.getTitle().startsWith(titleStart) && frame.isShowing();
            }
        }).withTimeout(30_000).using(robot());
    }

    /** The frame view, found by its accessible name. */
    private JComponent image(FrameFixture viewer, String name) {
        return robot().finder().find(viewer.target(), new GenericTypeMatcher<>(JComponent.class) {
            @Override
            protected boolean isMatching(JComponent candidate) {
                return candidate.isShowing() && candidate.getAccessibleContext() != null
                        && candidate.getAccessibleContext().getAccessibleName() != null
                        && candidate.getAccessibleContext().getAccessibleName().startsWith(name);
            }
        });
    }

    private void focus(JComponent control) {
        GuiActionRunner.execute(() -> control.requestFocusInWindow());
        waitFor("focus reaches the image", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == control);
    }

    private String caption(FrameFixture viewer) {
        try {
            return viewer.label(new GenericTypeMatcher<>(JLabel.class, true) {
                @Override
                protected boolean isMatching(JLabel label) {
                    return label.getText() != null && label.getText().startsWith("Frame ");
                }
            }).target().getText();
        } catch (RuntimeException notYet) {
            return "";
        }
    }
}
