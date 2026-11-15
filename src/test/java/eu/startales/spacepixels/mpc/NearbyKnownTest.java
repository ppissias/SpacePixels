package eu.startales.spacepixels.mpc;

import eu.startales.spacepixels.solarsystem.SolarSystemClient;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Identify all: a finding's place and motion at one time, the known objects within 1′, and pieces of one track. */
public class NearbyKnownTest {
    private static final long T0 = 1_660_000_000_000L;

    @Test
    public void findsKnownObjectsNearAFinding() {
        var wcs = LightCurveTestAccess.wcs();
        var samples = new ArrayList<LightCurve.Sample>();
        // 2 px per minute along +x on a 1″/px sky with RA decreasing to +x: 120″/h westward.
        for (int k = 0; k < 6; k++) samples.add(new LightCurve.Sample(k + 1, T0 + k * 300_000L, 120_000, 100 + 10 * k, 200));
        long epoch = T0 + 750_000;
        var place = NearbyKnown.place(samples, null, epoch, wcs);
        var sky = wcs.pixelToSky(125, 200);
        assertEquals(sky.getRaDegrees(), place.ra(), 1e-6);
        assertEquals(-120, place.raRateArcsecPerHour(), 0.5);
        assertEquals(0, place.decRateArcsecPerHour(), 0.5);
        var known = List.of(
                new SolarSystemClient.Known("same", false, place.ra(), place.dec() + 10 / 3600.0, 23.2, -118, 1),
                new SolarSystemClient.Known("slow", false, place.ra(), place.dec() - 20 / 3600.0, 21.0, -30, 0),
                new SolarSystemClient.Known("far", false, place.ra(), place.dec() + 90 / 3600.0, 20.0, -120, 0));
        var near = NearbyKnown.near(place, known);
        assertEquals(2, near.size());
        assertEquals("same", near.get(0).name());
        assertEquals(10, near.get(0).offsetArcsec(), 0.1);
        assertTrue(near.get(0).motionAgrees());
        assertFalse("a 90″/h difference", near.get(1).motionAgrees());
        var motion = new ReportedObjects.Motion(100, 200, T0, 2, 0);
        assertEquals(place.ra(), NearbyKnown.place(List.of(), motion, epoch, wcs).ra(), 1e-6);
        assertNull(NearbyKnown.place(samples.subList(0, 1), null, epoch, wcs));
    }

    @Test
    public void notesANearerFinding() {
        var byFinding = new LinkedHashMap<String, List<NearbyKnown.Match>>();
        byFinding.put("T2", List.of(new NearbyKnown.Match("800463 (2014 FM90)", 23.2, 0.4, 0.1, true, null, null)));
        byFinding.put("T7", List.of(new NearbyKnown.Match("800463 (2014 FM90)", 23.2, 38, 4, true, null, null)));
        var noted = NearbyKnown.noteNearer(byFinding);
        assertNull(noted.get("T2").getFirst().nearerFinding());
        assertEquals("T2", noted.get("T7").getFirst().nearerFinding());
        assertEquals(0.4, noted.get("T7").getFirst().nearerOffsetArcsec(), 1e-9);
    }
}
