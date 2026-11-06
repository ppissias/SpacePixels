package eu.startales.spacepixels.gui.mechanics;

import com.google.gson.Gson;
import eu.startales.spacepixels.detection.SavedReportOverlays;
import eu.startales.spacepixels.fits.FitsImportService;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.FrameFixture;
import org.junit.Test;
import javax.swing.JFrame;
import java.awt.event.WindowEvent;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Opens existing report findings in real Preview and Blink windows without running detection. */
public class SavedReportMechanicsTest extends MechanicsTestCase {
    /** Compatible saved tracks, variables, fitted movers and reference markers are available and checked at import. */
    @Test(timeout = 180_000) public void importedReportFindingsAreVisibleByDefault() throws Exception {
        Path directory = SolvedSession.create(folder.newFolder("saved").toPath());
        writeReport(directory);
        importFolder(directory);
        var session = GuiActionRunner.execute(() -> application().getSession());
        assertEquals(1,session.getTrackOverlay().getTracks().size());
        assertEquals("Known fixture variable",session.getVariableStarOverlay().getCandidates().getFirst().name);
        assertEquals(1,session.getFaintMoverOverlay().candidates().size());
        assertEquals(2,session.getFindingOverlay().markers().size());
        window().table().selectRows(0);
        window().button(VisibleText.button("Preview Frame")).click();
        var preview = viewer("Preview Frame");
        requireAllLayers(preview);
        close(preview);
        window().table().selectRows(0,1,2);
        window().button(VisibleText.button("Blink Selected")).click();
        var blink = viewer("Blink");
        waitFor("blink finishes loading", () -> blink.checkBox(VisibleText.checkBox("Show faint movers")).target().isEnabled());
        requireAllLayers(blink);
        close(blink);
        assertSame(session,GuiActionRunner.execute(() -> application().getSession()));
        assertNull("Opening saved findings starts no detection",GuiActionRunner.execute(() -> application().getJobCoordinator().current()));
    }

    /** Builds portable report metadata and known display geometry beside the isolated images. */
    private void writeReport(Path directory) throws Exception {
        eu.startales.spacepixels.fits.FitsFileInformation[] files;
        try (var importer = new FitsImportService(directory.toFile(),null)) { files = importer.readFiles(); }
        var data = new LinkedHashMap<String,Object>();
        data.put("schemaVersion",1);data.put("frameCount",files.length);data.put("inputFrames",SavedReportOverlays.identities(files));
        data.put("findings",List.of(
                Map.of("id","T1","x",20,"y",30,"frames",List.of(1,5),"positions",List.of(
                        Map.of("frameNumber",1,"x",20,"y",30),Map.of("frameNumber",5,"x",30,"y",35))),
                Map.of("id","V1","x",60,"y",40,"frames",List.of(1,2,3,4,5),"tier","HIGH_CONFIDENCE"),
                Map.of("id","DS1","x",48,"y",48,"frames",List.of()),
                Map.of("id","A1","x",25,"y",60,"frames",List.of(2),"positions",List.of(Map.of("frameNumber",2,"x",25,"y",60))),
                Map.of("id","FM1","x",40,"y",30,"frames",List.of())));
        data.put("variableMarkers",List.of(Map.of("label","V1","x",60,"y",40,"name","Known fixture variable",
                "description","V1 · Known fixture variable · High confidence · variation 0.200 mag")));
        var positions = new ArrayList<Object>();
        for (int i = 0; i < files.length; i++) positions.add(Map.of("frameNumber",i+1,"midpointMillis",
                files[i].getObservationTimestamp()+files[i].getExposureDurationMillis()/2));
        long epoch = files[0].getObservationTimestamp()+files[0].getExposureDurationMillis()/2;
        data.put("faintMovers",List.of(Map.of("id","FM1","x",40,"y",30,"vxPixelsPerMinute",.1,"vyPixelsPerMinute",0,
                "referenceTimeMillis",epoch,"fittedPositions",positions)));
        var report = Files.createDirectory(directory.resolve("detections_saved"));
        Files.writeString(report.resolve("report-data.json"),new Gson().toJson(data));
        Files.writeString(report.resolve("detection_report.html"),"Completed fixture report");
    }

    /** Finds a real showing viewer by its user-visible title. */
    private FrameFixture viewer(String title) {
        return WindowFinder.findFrame(new GenericTypeMatcher<>(JFrame.class) {
            /** Refuses hidden or unrelated windows. */
            @Override protected boolean isMatching(JFrame frame) { return frame.isShowing() && frame.getTitle().startsWith(title); }
        }).withTimeout(20_000).using(robot());
    }

    /** Every available report category group starts visible and remains independently switchable. */
    private void requireAllLayers(FrameFixture viewer) {
        for (String text : List.of("Show tracks","Show variable stars","Show faint movers","Show other findings"))
            viewer.checkBox(VisibleText.checkBox(text)).requireEnabled().requireSelected();
        viewer.checkBox(VisibleText.checkBox("Show other findings")).uncheck().requireNotSelected();
        viewer.checkBox(VisibleText.checkBox("Show other findings")).check().requireSelected();
    }

    /** Uses normal close delivery so background workers and the main-window lock are released. */
    private void close(FrameFixture viewer) {
        GuiActionRunner.execute(() -> viewer.target().dispatchEvent(new WindowEvent(viewer.target(),WindowEvent.WINDOW_CLOSING)));
        waitFor("viewer closes", () -> !viewer.target().isShowing());
    }
}
