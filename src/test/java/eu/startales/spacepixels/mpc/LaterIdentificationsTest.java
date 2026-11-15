package eu.startales.spacepixels.mpc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Identifications made after a report: saved beside it, read back, applied to its objects; a broken file is ignored. */
public class LaterIdentificationsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesLoadsAndApplies() throws Exception {
        var entry = new LaterIdentifications.Entry();
        entry.id = "T2";
        entry.status = ReportedObjects.POSSIBLE;
        entry.names = List.of("800463 (2014 FM90)");
        entry.note = "Identify all: 0″ away";
        entry.source = "Identify all";
        entry.madeAtUtc = "2026-11-15T10:00:00Z";
        var entries = new LinkedHashMap<String, LaterIdentifications.Entry>();
        entries.put("T2", entry);
        LaterIdentifications.save(folder.getRoot(), entries);
        var loaded = LaterIdentifications.load(folder.getRoot());
        assertEquals("800463 (2014 FM90)", loaded.get("T2").names.getFirst());
        var position = List.of(new ReportedObjects.Position(1, 10, 20, 3.0, 40.0));
        var objects = List.of(
                new ReportedObjects.ReportedObject("T1", ReportedObjects.TRACK, ReportedObjects.KNOWN, List.of("A"), "a", position, null, null, null),
                new ReportedObjects.ReportedObject("T2", ReportedObjects.TRACK, ReportedObjects.NO_KNOWN_MATCH, List.of(), "b", position, null, null, null));
        var applied = LaterIdentifications.apply(objects, loaded);
        assertTrue(applied.get(0) == objects.get(0));
        assertEquals(ReportedObjects.POSSIBLE, applied.get(1).status());
        assertEquals("Identify all: 0″ away", applied.get(1).note());
        assertEquals(position, applied.get(1).positions());
        Files.writeString(folder.getRoot().toPath().resolve(LaterIdentifications.FILE_NAME), "{ not json");
        assertTrue(LaterIdentifications.load(folder.getRoot()).isEmpty());
        assertTrue(LaterIdentifications.load(folder.newFolder("empty")).isEmpty());
    }
}
