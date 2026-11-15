package eu.startales.spacepixels.mpc;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The MPC records beside a report: saved and read back, emptied entries dropped, and designations named in the file. */
public class MpcRecordsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void savesLoadsAndRemovesTheFileWhenNothingIsLeft() throws Exception {
        var entry = new MpcRecords.Entry();
        entry.id = "T1";
        entry.designation = "2018 RX5";
        entry.submittedUtc = "2026-11-16";
        var empty = new MpcRecords.Entry();
        empty.id = "T2";
        var entries = new LinkedHashMap<String, MpcRecords.Entry>();
        entries.put("T1", entry);
        entries.put("T2", empty);
        MpcRecords.save(folder.getRoot(), entries);
        var loaded = MpcRecords.load(folder.getRoot());
        assertEquals(List.of("T1"), List.copyOf(loaded.keySet()));
        assertEquals("2018 RX5", loaded.get("T1").designation);
        assertEquals("2026-11-16", loaded.get("T1").submittedUtc);
        entry.designation = " ";
        entry.submittedUtc = null;
        MpcRecords.save(folder.getRoot(), entries);
        assertFalse(new File(folder.getRoot(), MpcRecords.FILE_NAME).exists());
        assertTrue(MpcRecords.load(folder.getRoot()).isEmpty());
    }

    @Test
    public void anUnreadableFileHoldsNothing() throws Exception {
        Files.writeString(new File(folder.getRoot(), MpcRecords.FILE_NAME).toPath(), "[ broken");
        assertTrue(MpcRecords.load(folder.getRoot()).isEmpty());
    }

    @Test
    public void designationsNameTheObjectAsTheMpcGaveThem() {
        var observations = List.<MpcObservation>of();
        var numbered = MpcRecords.tracklet(" (2320) ", observations);
        assertEquals("2320", numbered.permID());
        var provisional = MpcRecords.tracklet("2018  RX5", observations);
        assertEquals("2018 RX5", provisional.provID());
        assertNull(provisional.trkSub());
        var neocp = MpcRecords.tracklet("P21Abcd", observations);
        assertEquals("P21Abcd", neocp.trkSub());
        assertNull(neocp.permID());
        assertNull(MpcRecords.problem(""));
        assertNotNull(MpcRecords.problem("TOOLONG12"));
        assertNotNull(MpcRecords.problem("2018 rx5?"));
        assertNull(MpcRecords.tracklet("TOOLONG12", observations));
        assertNull(MpcRecords.tidy("   "));
    }
}
