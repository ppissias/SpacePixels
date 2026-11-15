package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.fits.ObservingSite;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Links between nights: written beside both reports with relative paths, replaced per report, followed through a third
 * report, removed on both sides; the motion hint, the site check and the shared tracklet ID.
 */
public class NightLinksTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final long DAY = 86_400_000L;
    private static final long START = 1_535_750_251_000L; // 2018-08-31T21:17:31Z

    @Test
    public void linkIsWrittenBesideBothReportsWithRelativePaths() throws Exception {
        var a = night("night-a/detections_1", START, "T1", "T2");
        var b = night("night-b/detections_2", START + 3 * DAY, "T1");
        NightLinks.link(a, "T2", b, "T1");
        var fromA = NightLinks.load(a.report().folder());
        assertEquals(1, fromA.size());
        assertEquals("T2", fromA.getFirst().object);
        assertEquals("T1", fromA.getFirst().otherObject);
        assertEquals("../../night-b/detections_2", fromA.getFirst().report);
        assertEquals(START + 3 * DAY, fromA.getFirst().otherSessionStartMillis);
        var fromB = NightLinks.load(b.report().folder());
        assertEquals("T2", fromB.getFirst().otherObject);
        assertEquals(new File(a.report().folder().getAbsolutePath()).toPath().normalize(),
                NightLinks.resolve(b.report().folder(), fromB.getFirst().report).toPath());
    }

    @Test
    public void relinkingToTheSameReportReplacesTheEarlierLink() throws Exception {
        var a = night("a/r", START, "T1");
        var b = night("b/r", START + DAY, "T1", "T2");
        NightLinks.link(a, "T1", b, "T1");
        NightLinks.link(a, "T1", b, "T2");
        var fromA = NightLinks.load(a.report().folder());
        assertEquals(1, fromA.size());
        assertEquals("T2", fromA.getFirst().otherObject);
        var fromB = NightLinks.load(b.report().folder());
        // B's T1 keeps its old link to A: one link per object and report, written from each object's side.
        assertEquals(2, fromB.size());
    }

    @Test
    public void linkedNightsAreFollowedThroughAThirdReport() throws Exception {
        var a = night("a/r", START, "T1");
        var b = night("b/r", START + DAY, "T3");
        var c = night("c/r", START + 2 * DAY, "T2");
        NightLinks.link(a, "T1", b, "T3");
        NightLinks.link(c, "T2", b, "T3");
        var linked = NightLinks.linked(a.report().folder(), "T1");
        assertEquals(List.of("T3", "T2"), linked.nights().stream().map(NightLinks.Ref::objectId).toList());
        assertTrue(linked.missing().isEmpty());
        var fromC = NightLinks.linked(c.report().folder(), "T2");
        assertEquals(List.of("T3", "T1"), fromC.nights().stream().map(NightLinks.Ref::objectId).toList());
        assertTrue(NightLinks.linked(a.report().folder(), "T9").nights().isEmpty());
    }

    @Test
    public void aMovedReportIsListedAsMissing() throws Exception {
        var a = night("a/r", START, "T1");
        var b = night("b/r", START + DAY, "T1");
        NightLinks.link(a, "T1", b, "T1");
        Files.delete(new File(b.report().folder(), "report-data.json").toPath());
        var linked = NightLinks.linked(a.report().folder(), "T1");
        assertTrue(linked.nights().isEmpty());
        assertEquals(List.of("../../b/r"), linked.missing());
    }

    @Test
    public void unlinkRemovesBothSides() throws Exception {
        var a = night("a/r", START, "T1", "T2");
        var b = night("b/r", START + DAY, "T1");
        NightLinks.link(a, "T1", b, "T1");
        NightLinks.link(a, "T2", night("c/r", START + 2 * DAY, "T5"), "T5");
        NightLinks.unlink(a.report().folder(), "T1");
        assertEquals(List.of("T2"), NightLinks.load(a.report().folder()).stream().map(l -> l.object).toList());
        assertTrue(NightLinks.load(b.report().folder()).isEmpty());
        assertFalse(new File(b.report().folder(), NightLinks.FILE_NAME).exists());
    }

    @Test
    public void anUnreadableFileHoldsNoLinks() throws Exception {
        File report = folder.newFolder("broken");
        Files.writeString(new File(report, NightLinks.FILE_NAME).toPath(), "{ not json");
        assertTrue(NightLinks.load(report).isEmpty());
    }

    @Test
    public void motionHintIsSmallForTheSameStraightPath() {
        // 30″ per hour in RA and 10″ per hour in Dec near Dec -21°, observed for 2 h, then 3 days later.
        var first = path(START, 0, 7);
        var later = path(START + 3 * DAY, 72, 5);
        assertEquals(0, NightLinks.extrapolationMissArcmin(first, later), 0.01);
        var elsewhere = new ArrayList<MpcObservation>();
        for (var o : later) elsewhere.add(observation(o.midpointMillis(), o.raDegrees(), o.decDegrees() + 0.1));
        assertEquals(6, NightLinks.extrapolationMissArcmin(first, elsewhere), 0.05);
        assertNull(NightLinks.extrapolationMissArcmin(first.subList(0, 1), later));
    }

    @Test
    public void sitesWithinFiveKilometresAreOneSite() {
        var site = new ObservingSite(37.85, 23.75, 100, ObservingSite.FROM_HEADER);
        assertTrue(NightLinks.sameSite(site, new ObservingSite(37.86, 23.76, 100, ObservingSite.FROM_HEADER)));
        assertFalse(NightLinks.sameSite(site, new ObservingSite(38.85, 23.75, 100, ObservingSite.FROM_HEADER)));
        assertTrue(NightLinks.sameSite(site, null));
    }

    @Test
    public void nightTrackletIdFollowsTheReportOrder() throws Exception {
        var a = night("a/r", START, "T1", "T2");
        assertEquals("S083101", a.trkSub(a.object("T2")));
        assertEquals(6, NightLinks.defaultSelection(path(START, 0, 16)).size());
    }

    /** A report folder with a placeholder report-data.json, and a night holding these tracks. */
    private NightLinks.Night night(String path, long start, String... ids) throws Exception {
        File report = new File(folder.getRoot(), path);
        assertTrue(report.mkdirs());
        Files.writeString(new File(report, "report-data.json").toPath(), "{}");
        var objects = new ArrayList<ReportedObjects.ReportedObject>();
        for (String id : ids) {
            objects.add(new ReportedObjects.ReportedObject(id, ReportedObjects.TRACK, ReportedObjects.NO_KNOWN_MATCH, List.of(), null,
                    List.of(new ReportedObjects.Position(1, 10, 10, 3.0, 20.0)), null, null, null));
        }
        var frames = List.of(new ReportedObjects.Frame("f1.fit", 100, 100, start, 180_000));
        return new NightLinks.Night(new ReportedObjects.Report(report, frames, objects, null),
                new AstrometricReduction.Result(null, "no plate solution"), null);
    }

    /** Observations every 20 minutes along a straight path, starting {@code hours} after RA 337.3°, Dec -20.9°. */
    private static List<MpcObservation> path(long start, double hours, int count) {
        var list = new ArrayList<MpcObservation>();
        double cosDec = Math.cos(Math.toRadians(-20.9));
        for (int k = 0; k < count; k++) {
            double t = hours + k / 3.0;
            list.add(observation(start + Math.round(k * 1_200_000L), 337.3 + 30 * t / 3600 / cosDec, -20.9 + 10 * t / 3600));
        }
        return list;
    }

    private static MpcObservation observation(long time, double ra, double dec) {
        return new MpcObservation(1, time, 180_000, 0, 0, ra, dec, 0.5, 0.5, AstrometricReduction.GAIA_DR3, "");
    }
}
