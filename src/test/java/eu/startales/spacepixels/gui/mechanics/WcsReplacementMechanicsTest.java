package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.MainApplicationPanel;
import eu.startales.spacepixels.gui.MetadataReplacementProbe;
import eu.startales.spacepixels.gui.ViewerLifecycleProbe;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.util.FitsFileInformation;
import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import javax.swing.JButton;
import java.awt.EventQueue;
import java.awt.Window;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * Writing a WCS solution replaces the frame's metadata in the open viewers: also for a frame being prepared while
 * the write ends, in every inspection window of the session, and never in another folder's or session's windows.
 */
public class WcsReplacementMechanicsTest extends MechanicsTestCase {

    @Test(timeout = 120_000)
    public void aFramePreparedDuringTheReplacementIsShownWithTheNewMetadata() throws Exception {
        importSyntheticSequence();
        FitsFileInformation[] files = GuiActionRunner.execute(() -> application().getSession().getFiles());
        ViewerLifecycleProbe viewer = GuiActionRunner.execute(() -> {
            ViewerLifecycleProbe created = new ViewerLifecycleProbe(true);
            created.open(files, application().getStretchPanel());
            return created;
        });
        try {
            assertTrue("the old metadata is taken for preparation", viewer.awaitStarted());
            FitsFileInformation solved = files[0].withFitsHeader(solution(210.0));
            GuiActionRunner.execute(() -> viewer.replaceFrameMetadata(files[0], solved));
            viewer.finishPreparation();
            assertTrue(viewer.awaitFinished());
            waitFor("the prepared frame is shown", () -> GuiActionRunner.execute(() -> viewer.shownInfo() != null));
            FitsFileInformation shown = GuiActionRunner.execute(viewer::shownInfo);
            assertSame("the frame prepared before the write shows the new solution", solved, shown);
            assertEquals("210.0", shown.getFitsHeader().get("CRVAL1"));
        } finally {
            viewer.finishPreparation();
            GuiActionRunner.execute(() -> {
                viewer.closeViewer();
                viewer.dispose();
            });
        }
    }

    @Test(timeout = 240_000)
    public void everyInspectionOfTheSessionTakesTheWriteAndOthersAreLeftAlone() throws Exception {
        Path sequence = importSyntheticSequence();
        MainApplicationPanel panel = application().getMainApplicationPanel();
        ProcessingSession first = GuiActionRunner.execute(() -> application().getSession());
        FitsFileInformation[] files = GuiActionRunner.execute(first::getFiles);
        String firstPath = files[0].getFilePath();
        String secondPath = files[1].getFilePath();

        openInspection(1);
        openInspection(2);
        List<Window> inspections = GuiActionRunner.execute(MetadataReplacementProbe::showingInspections);
        assertEquals(2, inspections.size());
        for (Window inspection : inspections) {
            waitFor("the inspection shows its first frame",
                    () -> GuiActionRunner.execute(() -> MetadataReplacementProbe.shownInfo(inspection) != null));
        }

        // A namesake in another folder is not this window's file.
        File elsewhere = folder.newFolder("elsewhere");
        FitsFileInformation namesake = new FitsFileInformation(new File(elsewhere, files[0].getFileName()).getAbsolutePath(),
                files[0].getFileName(), true, files[0].getSizeWidth(), files[0].getSizeHeight());
        FitsFileInformation namesakeSolved = namesake.withFitsHeader(solution(99.0));
        GuiActionRunner.execute(() -> {
            for (Window inspection : inspections) {
                MetadataReplacementProbe.replace(inspection, namesake, namesakeSolved);
                assertFalse("another folder's write leaves the frame unsolved",
                        MetadataReplacementProbe.inspectedInfo(inspection, firstPath).isWcsSolved());
            }
        });

        // The session's write reaches both inspections.
        FitsFileInformation solved = GuiActionRunner.execute(() ->
                MetadataReplacementProbe.applyWcsHeader(panel, files[0], solution(210.0)));
        GuiActionRunner.execute(() -> {
            for (Window inspection : inspections) {
                FitsFileInformation info = MetadataReplacementProbe.inspectedInfo(inspection, firstPath);
                assertNotNull(info);
                assertSame("each inspection of the session takes the new solution", solved, info);
            }
        });

        // Importing the same folder again makes a new session; its writes leave the old session's windows alone.
        GuiActionRunner.execute(() -> application().importFolder(sequence.toFile()));
        waitFor("the folder is imported again", () -> GuiActionRunner.execute(() ->
                application().getSession() != null && application().getSession() != first));
        FitsFileInformation reimported = GuiActionRunner.execute(() -> application().getSession().getFiles()[1]);
        assertEquals(secondPath, reimported.getFilePath());
        GuiActionRunner.execute(() -> MetadataReplacementProbe.applyWcsHeader(panel, reimported, solution(120.0)));
        GuiActionRunner.execute(() -> {
            assertEquals("the inspections stay open after the import", 2, MetadataReplacementProbe.registeredInspections(panel));
            for (Window inspection : inspections) {
                assertTrue(inspection.isShowing());
                assertFalse("a write in the new session does not reach the old session's inspection",
                        MetadataReplacementProbe.inspectedInfo(inspection, secondPath).isWcsSolved());
            }
            for (Window inspection : inspections) {
                inspection.dispatchEvent(new java.awt.event.WindowEvent(inspection, java.awt.event.WindowEvent.WINDOW_CLOSING));
            }
        });
        waitFor("closed inspections are forgotten",
                () -> GuiActionRunner.execute(() -> MetadataReplacementProbe.registeredInspections(panel) == 0));
    }

    private void openInspection(int expected) {
        JButton button = window().button(VisibleText.button("Manual Transient Inspection")).target();
        waitFor("Manual Transient Inspection can start", () -> GuiActionRunner.execute(button::isEnabled));
        EventQueue.invokeLater(button::doClick);
        waitFor("inspection " + expected + " opens", () -> GuiActionRunner.execute(() ->
                MetadataReplacementProbe.showingInspections().size() == expected
                        && MetadataReplacementProbe.registeredInspections(application().getMainApplicationPanel()) == expected));
    }

    /** A tangent-plane solution centred on {@code ra}, 20° north, at 1.5″ per pixel. */
    private static Map<String, String> solution(double ra) {
        double scale = 1.5 / 3600;
        Map<String, String> header = new HashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "50.5");
        header.put("CRPIX2", "50.5");
        header.put("CRVAL1", Double.toString(ra));
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-scale));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(scale));
        return header;
    }
}
