package eu.startales.spacepixels.report;

import com.google.gson.JsonParser;
import eu.startales.spacepixels.config.DetectionRunOptions;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.skycatalog.SkyField;
import eu.startales.spacepixels.skycatalog.SkySnapshot;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.*;

/** Checks report identity, inclusion, finite export and provider-text safety across the two additive association models. */
public class ReportAssociationsIntegrationTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();
    private static final long START = Instant.parse("2026-10-01T00:00:00Z").toEpochMilli();

    /** Competing ordinary tracks and a rescue keep all identities, reciprocal links and explicit ambiguity. */
    @Test public void preservesFindingsAndEveryPlausibleAssociationInOfflineExports() throws Exception {
        var c = context(ReportInclusion.all(), false);
        assertEquals(List.of("T1", "T2", "LR1"), c.data.findings.stream().map(ReportData.Finding::id).toList());
        assertEquals(2, c.rescueAssociations.size());
        assertEquals(2, c.knownSolarSystem.bodies().getFirst().trackComparisons().size());
        assertEquals("unavailable", c.knownSolarSystem.clock().status());
        var edge = c.knownSolarSystem.bodies().get(1);
        assertEquals(5, edge.inFieldFrames());
        assertEquals(4, edge.usablePathFrames());
        assertEquals(5, edge.starMaskFrames());
        var text = new StringWriter(); var out = new PrintWriter(text);
        RescueTrackAssociations.write(out, c, "LR1");
        RescueTrackAssociations.write(out, c, "T1");
        KnownSolarSystemReport.write(out, c, new FieldDepth());
        out.flush();
        String html = text.toString();
        for (var id : List.of("t1", "t2", "lr1")) assertTrue(html.contains("href='#finding-" + id + "'"));
        assertTrue(html.contains("do not confirm identity or merge findings"));
        assertTrue(html.contains("&lt;synthetic object&gt;"));
        assertTrue(html.contains("Partial fetch: one &lt;request&gt; failed"));
        assertTrue(html.contains("Image FILTER metadata: L"));
        assertTrue(html.contains("not a moving-object completeness limit"));
        c.data.writeJson(folder.getRoot());
        var json = JsonParser.parseString(Files.readString(folder.getRoot().toPath().resolve("report-data.json"))).getAsJsonObject();
        assertEquals(1, json.get("schemaVersion").getAsInt());
        assertEquals(3, json.getAsJsonArray("findings").size());
        assertEquals(2, json.getAsJsonArray("rescueAssociations").size());
        assertEquals(3, c.movingTargets.getFirst().points.size());
        assertEquals(2, c.localRescueCandidates.getFirst().points.size());
    }

    /** An excluded rescue category cannot leave a hidden finding, dangling association or JSON entry. */
    @Test public void reportExclusionRemovesRescueLinksWithoutChangingTracks() throws Exception {
        var inclusion = new ReportInclusion(true, true, false, true, true, true, true, true);
        var c = context(inclusion, false);
        assertTrue(c.rescueAssociations.isEmpty());
        assertEquals(List.of("T1", "T2"), c.data.findings.stream().map(ReportData.Finding::id).toList());
        c.data.writeJson(folder.getRoot());
        var json = Files.readString(folder.getRoot().toPath().resolve("report-data.json"));
        assertFalse(JsonParser.parseString(json).getAsJsonObject().has("rescueAssociations"));
        assertFalse(json.contains("finding-lr1"));
    }

    /** Malformed optional numeric provenance and non-UTC metadata must not invent evidence or break standard JSON. */
    @Test public void unavailableOptionalValuesAndClockSystemsRemainExplicit() throws Exception {
        var c = context(ReportInclusion.all(), true);
        assertNull(c.knownSolarSystem.magnitudeLimit());
        assertEquals(0, c.knownSolarSystem.bodies().getFirst().predictedFrames());
        assertTrue(c.knownSolarSystem.bodies().getFirst().trackComparisons().isEmpty());
        assertTrue(c.rescueAssociations.isEmpty());
        c.data.writeJson(folder.getRoot());
        String text = Files.readString(folder.getRoot().toPath().resolve("report-data.json"));
        assertFalse(text.contains("NaN")); assertFalse(text.contains("Infinity"));
    }

    /** Builds a tiny admitted sequence and provider snapshot with deliberately competing tracks and separate rescue frames. */
    private DetectionReportContext context(ReportInclusion inclusion, boolean invalid) throws Exception {
        var files = new FitsFileInformation[5];
        var raw = new ArrayList<short[][]>();
        for (int i = 0; i < files.length; i++) {
            var h = new HashMap<String, String>();
            h.put("DATE-OBS", Instant.ofEpochMilli(START + i * 120_000L).toString()); h.put("EXPTIME", "120");
            h.put("FILTER", "L"); h.put("TIMESYS", invalid ? "TAI" : "UTC");
            h.put("CTYPE1", "RA---TAN"); h.put("CTYPE2", "DEC--TAN"); h.put("CRPIX1", "51"); h.put("CRPIX2", "51");
            h.put("CRVAL1", "1"); h.put("CRVAL2", "0"); h.put("CD1_1", "-.000277777777778"); h.put("CD1_2", "0");
            h.put("CD2_1", "0"); h.put("CD2_2", ".000277777777778");
            files[i] = new FitsFileInformation(new File(folder.getRoot(), "frame" + i + ".fit").toString(),
                    "frame" + i + ".fit", true, 100, 100, h);
            raw.add(new short[100][100]);
        }
        var tracks = new ArrayList<Track>();
        for (double y : new double[]{20, 20.2}) {
            var track = new Track();
            for (int frame : new int[]{0, 2, 4}) track.points.add(detection(frame, y));
            tracks.add(track);
        }
        var points = List.of(detection(1, 20), detection(3, 20));
        var metrics = new ResidualTransientAnalysis.LocalTransientMetrics(2, 2, 3, 1, 2, 2, 2, 0, 1, 10, 2.0 / 3, 22, 20);
        var rescue = new ResidualTransientAnalysis.LocalRescueCandidate(ResidualTransientAnalysis.LocalRescueKind.LOCAL_REPEAT, points, metrics, 1);
        var residual = new ResidualTransientAnalysis(List.of(rescue), List.of());
        raw.getFirst()[20][0] = (short) io.github.ppissias.jtransient.core.DataArea.FILL_THRESHOLD;
        var mask = new boolean[100][100]; mask[20][0] = true;
        var result = PipelineResult.builder().tracks(tracks).masterStars(List.of()).anomalies(List.of()).masterVetoMask(mask)
                .allTransients(List.of()).unclassifiedTransients(List.of()).residualTransientAnalysis(residual).build();
        var wcs = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        var catalogue = new SolarSystemObjects(); catalogue.field = SkyField.of(wcs, 100, 100);
        catalogue.startMillis = START; catalogue.endMillis = START + 600_000; catalogue.magnitudeLimit = invalid ? Double.NaN : 19;
        catalogue.source = "synthetic provider"; catalogue.fetchedAtUtc = "2026-10-01T00:00:00Z";
        catalogue.problems.add("one <request> failed"); if (invalid) catalogue.problems.add(null);
        var body = new SolarSystemObjects.Body(); body.name = "<synthetic object>";
        for (int minutes : new int[]{0, 10}) {
            var sky = wcs.pixelToSky(19.5 + .5 * minutes, 20);
            var sample = new SolarSystemObjects.Sample(); sample.timeMillis = START + minutes * 60_000L;
            sample.ra = sky.getRaDegrees(); sample.dec = sky.getDecDegrees(); sample.magnitude = 18.0;
            sample.raSigmaArcsec = .1; sample.decSigmaArcsec = .1; body.samples.add(sample);
        }
        catalogue.bodies.add(body);
        var edgeBody = new SolarSystemObjects.Body(); edgeBody.name = "Edge coverage control";
        for (int minutes : new int[]{0, 10}) {
            var sky = wcs.pixelToSky(-.2, 20);
            var sample = new SolarSystemObjects.Sample(); sample.timeMillis = START + minutes * 60_000L;
            sample.ra = sky.getRaDegrees(); sample.dec = sky.getDecDegrees(); edgeBody.samples.add(sample);
        }
        catalogue.bodies.add(edgeBody);
        var run = ReportRunContext.capture(DetectionRunOptions.defaults(), files, new SkySnapshot(folder.getRoot(), null, null, catalogue));
        return DetectionReportContext.create(new ReportRequest(result, raw, files, folder.getRoot(), new DetectionConfig(), run, inclusion, null),
                folder.getRoot(), new ReportProgress(null));
    }

    /** A source with a known capture start and exposure duration; position follows half a pixel per minute. */
    private static DetectedObject detection(int frame, double y) {
        var p = new DetectedObject(20 + frame, y, 100, 5);
        p.sourceFrameIndex = frame; p.timestamp = START + frame * 120_000L; p.exposureDuration = 120_000;
        return p;
    }
}
