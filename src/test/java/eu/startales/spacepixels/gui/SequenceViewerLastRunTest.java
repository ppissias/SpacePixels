package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.detection.SavedReportOverlays;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.platesolve.PlateSolveService;
import eu.startales.spacepixels.session.ProcessingSession;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JCheckBox;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Every sequence viewer takes all the findings of a session's last run, without opening a window. */
public class SequenceViewerLastRunTest {

    private static final List<String> SWITCHES = List.of("Show moving object tracks", "Show streak tracks",
            "Show variable stars", "Show faint movers", "Show other findings");

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** A session with a finding of every kind offers them all, and no session offers none. */
    @Test public void aViewerOffersEveryKindOfFindingOfTheLastRun() throws Exception {
        Path directory = folder.newFolder("session").toPath();
        FitsFileInformation[] files = {frame(directory, "a.fit", "2026-03-10T02:00:00"), frame(directory, "b.fit", "2026-03-10T02:10:00")};
        long first = files[0].getObservationTimestamp(), second = files[1].getObservationTimestamp();
        Files.writeString(directory.resolve("report-data.json"), "{\"schemaVersion\":1,\"frameCount\":2,\"findings\":["
                + finding("T1", 10, true) + "," + finding("ST1", 20, true) + "," + finding("S1", 30, true) + ","
                + finding("V1", 40, false) + "," + finding("FM1", 50, false) + "],"
                + "\"faintMovers\":[{\"id\":\"FM1\",\"x\":50,\"y\":50,\"vxPixelsPerMinute\":0.5,\"vyPixelsPerMinute\":0,"
                + "\"referenceTimeMillis\":" + first + ",\"fittedPositions\":["
                + "{\"frameNumber\":1,\"midpointMillis\":" + first + "},{\"frameNumber\":2,\"midpointMillis\":" + second + "}]}]}");
        Files.writeString(directory.resolve("detection_report.html"), "report");
        var overlays = SavedReportOverlays.read(directory.resolve("detection_report.html").toFile(), files).overlays();
        ProcessingSession session = new ProcessingSession(new PlateSolveService(new AppConfig()), files, null);
        SwingUtilities.invokeAndWait(() -> session.setLastRun(overlays));

        SwingUtilities.invokeAndWait(() -> {
            ViewerLifecycleProbe viewer = new ViewerLifecycleProbe(true);
            try {
                viewer.setLastRun(session, files);
                for (String name : SWITCHES) {
                    assertTrue(name + " is offered", box(viewer.getContentPane(), name).isEnabled());
                }
                viewer.setLastRun(null, files);
                for (String name : SWITCHES) {
                    assertFalse(name + " has nothing to show", box(viewer.getContentPane(), name).isEnabled());
                }
            } finally {
                viewer.dispose();
            }
        });
    }

    private static FitsFileInformation frame(Path directory, String name, String date) {
        return new FitsFileInformation(directory.resolve(name).toString(), name, true, 100, 80, Map.of("DATE-OBS", date));
    }

    private static String finding(String id, int x, boolean measured) {
        return "{\"id\":\"" + id + "\",\"x\":" + x + ",\"y\":" + x + ",\"frames\":[1,2]"
                + (measured ? ",\"positions\":[{\"frameNumber\":1,\"x\":" + x + ",\"y\":" + x + "},{\"frameNumber\":2,\"x\":" + (x + 1) + ",\"y\":" + x + "}]" : "")
                + "}";
    }

    /** The switch with this name for screen readers. */
    private static JCheckBox box(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JCheckBox box && name.equals(box.getAccessibleContext().getAccessibleName())) return box;
            if (child instanceof Container inner) {
                JCheckBox found = inner.getComponentCount() == 0 ? null : boxOrNull(inner, name);
                if (found != null) return found;
            }
        }
        throw new AssertionError("no switch named " + name);
    }

    private static JCheckBox boxOrNull(Container root, String name) {
        try {
            return box(root, name);
        } catch (AssertionError notInThisBranch) {
            return null;
        }
    }
}
