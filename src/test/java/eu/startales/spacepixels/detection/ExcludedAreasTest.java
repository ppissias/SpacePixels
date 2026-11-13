package eu.startales.spacepixels.detection;

import io.github.ppissias.jtransient.engine.ExcludedArea;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Excluded areas are clipped to their frames, count overlaps once, and survive a save and a load. */
public class ExcludedAreasTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    /** Rectangles reaching past the frame are cut at its edge; ones entirely outside are dropped. */
    @Test
    public void areasAreClippedToTheFrame() {
        ExcludedAreas areas = new ExcludedAreas(100, 50, List.of(new ExcludedArea(-10, 40, 30, 20),
                new ExcludedArea(200, 0, 5, 5), new ExcludedArea(90, 0, 20, 10)));
        assertEquals(List.of(new ExcludedArea(0, 40, 20, 10), new ExcludedArea(90, 0, 10, 10)), areas.areas());
        assertTrue(areas.fits(100, 50));
        assertFalse(areas.fits(100, 51));
        assertTrue(ExcludedAreas.none(100, 50).isEmpty());
    }

    /** The excluded share counts each pixel once, however many areas cover it. */
    @Test
    public void theExcludedShareCountsOverlapsOnce() {
        ExcludedAreas areas = new ExcludedAreas(100, 100, List.of(new ExcludedArea(0, 0, 50, 100),
                new ExcludedArea(25, 0, 50, 100)));
        assertEquals(0.75, areas.fraction(), 1e-12);
        assertEquals(0.5, areas.without(1).fraction(), 1e-12);
        assertEquals(0.5, areas.replacing(0, new ExcludedArea(30, 0, 10, 100)).fraction(), 1e-12);
        assertEquals(3, areas.with(new ExcludedArea(0, 0, 1, 1)).areas().size());
    }

    /**
     * A run uses the session's areas when its frames are in the session's folder, otherwise the folder's saved file,
     * and only areas that fit its frames.
     */
    @Test
    public void aRunUsesTheSessionsAreasInItsFolderOtherwiseTheFile() throws Exception {
        File dir = folder.newFolder("frames");
        var frame = new eu.startales.spacepixels.fits.FitsFileInformation(new File(dir, "a.fit").getPath(), "a.fit", true, 100, 50);
        var files = new eu.startales.spacepixels.fits.FitsFileInformation[]{frame};
        assertTrue(ExcludedAreas.forRun(files, null).isEmpty());
        ExcludedAreas saved = new ExcludedAreas(100, 50, List.of(new ExcludedArea(0, 0, 10, 50)));
        ExcludedAreas.save(dir, saved);
        assertEquals(saved.areas(), ExcludedAreas.forRun(files, null));
        ExcludedAreas session = new ExcludedAreas(100, 50, List.of(new ExcludedArea(5, 5, 5, 5)));
        var sky = new eu.startales.spacepixels.skycatalog.SkySnapshot(dir, null, null, null, session);
        assertEquals(session.areas(), ExcludedAreas.forRun(files, sky));
        var elsewhere = new eu.startales.spacepixels.skycatalog.SkySnapshot(folder.newFolder("other"), null, null, null, session);
        assertEquals(saved.areas(), ExcludedAreas.forRun(files, elsewhere));
        var wider = new eu.startales.spacepixels.fits.FitsFileInformation(frame.getFilePath(), "a.fit", true, 200, 50);
        assertTrue(ExcludedAreas.forRun(new eu.startales.spacepixels.fits.FitsFileInformation[]{wider}, null).isEmpty());
        assertEquals(List.of(new ExcludedArea(1, 2, 3, 4)),
                ExcludedAreas.fromRectangles(List.of(new int[]{1, 2, 3, 4}, new int[]{0, 0, 0, 5})));
        assertTrue(ExcludedAreas.fromRectangles(null).isEmpty());
    }

    /** Saving and loading keeps the frame size and the rectangles; no areas removes the file. */
    @Test
    public void areasSurviveSaveAndLoad() throws Exception {
        File session = folder.newFolder("session");
        assertNull(ExcludedAreas.load(session));
        ExcludedAreas areas = new ExcludedAreas(2046, 4094, List.of(new ExcludedArea(1530, 0, 50, 4094),
                new ExcludedArea(0, 0, 20, 4094)));
        ExcludedAreas.save(session, areas);
        ExcludedAreas loaded = ExcludedAreas.load(session);
        assertEquals(areas.areas(), loaded.areas());
        assertTrue(loaded.fits(2046, 4094));
        ExcludedAreas.save(session, ExcludedAreas.none(2046, 4094));
        assertFalse(ExcludedAreas.fileIn(session).exists());
        Files.writeString(ExcludedAreas.fileIn(session).toPath(), "{not json");
        assertNull(ExcludedAreas.load(session));
    }
}
