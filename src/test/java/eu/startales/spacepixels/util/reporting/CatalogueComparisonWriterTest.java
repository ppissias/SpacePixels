package eu.startales.spacepixels.util.reporting;

import com.google.gson.JsonObject;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.photometry.PhotometryFlags;
import io.github.ppissias.jtransient.photometry.PhotometricReadiness;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CatalogueComparisonWriterTest {
    @Test
    public void usesOnlyUsableMeasurementsAndTheirActualFrameFilters() throws Exception {
        StarLightCurve star = star(3);
        star.amplitude = 0.16;
        star.expectedScatter = 0.02;
        star.deltaMag = new double[]{0.1, 0.2, -0.1};
        star.flags[1] = PhotometryFlags.CROSSING;
        FitsFileInformation[] files = {file("a.fit", "'L   '"), file("b.fit", "G"), file("c.fit", "L")};
        // Filename fallback handles a filtered/reordered frame list; excluded G must not make this mixed.
        List<PipelineTelemetry.PhotometryFrameStat> frames = List.of(frame(2, "a.fit", 100),
                frame(1, "b.fit", 100.001), frame(2, "c.fit", 100 + 10.0 / 1440));
        JsonObject result = CatalogueComparisonWriter.observation(star, frames, new CatalogueComparisonWriter.FrameHeaders(files));
        assertEquals(2, result.get("usablePoints").getAsInt());
        assertEquals(10, result.get("spanMinutes").getAsDouble(), 1e-6);
        assertEquals(10, result.get("largestGapMinutes").getAsDouble(), 1e-6);
        assertEquals("[\"L\"]", result.get("filters").toString());
        assertFalse(result.get("missingFilter").getAsBoolean());
        assertEquals(PhotometryFlags.CROSSING, star.flags[1]);
        assertEquals(0.16, star.amplitude, 0);
    }

    @Test
    public void incompleteTimestampsAndUnknownOrAmbiguousHeadersRemainUnknown() throws Exception {
        StarLightCurve star = star(2);
        star.deltaMag = new double[]{0, 0};
        List<PipelineTelemetry.PhotometryFrameStat> frames = List.of(frame(-1, "a.fit", 100), frame(-1, "missing.fit", Double.NaN));
        FitsFileInformation[] files = {file("a.fit", "L"), file("a.fit", "G")};
        JsonObject result = CatalogueComparisonWriter.observation(star, frames, new CatalogueComparisonWriter.FrameHeaders(files));
        assertFalse(result.has("spanMinutes"));
        assertTrue(result.get("missingFilter").getAsBoolean());
        assertTrue(result.getAsJsonArray("filters").isEmpty());
    }

    @Test
    public void fitsTextCannotTerminateTheEmbeddedJsonScript() throws Exception {
        StarLightCurve star = star(1);
        star.deltaMag[0] = 0;
        FitsFileInformation[] files = {file("a.fit", "</script><script>alert(1)</script>")};
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();
        telemetry.frames.add(frame(0, "a.fit", 100));
        VariableStarAnalysis analysis = new VariableStarAnalysis(new PhotometricReadiness(), List.of(star), telemetry);
        DetectionReportContext context = new DetectionReportContext(null, null, null, files, new DetectionConfig(),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        Constructor<SessionCatalogue> constructor = SessionCatalogue.class.getDeclaredConstructor(SkyCatalogue.class, long.class);
        constructor.setAccessible(true);
        SessionCatalogue catalogue = constructor.newInstance(new SkyCatalogue(), 0L);
        StringWriter text = new StringWriter();
        CatalogueComparisonWriter.writeData(new PrintWriter(text), context, analysis, List.of(star), catalogue);
        assertFalse(text.toString().contains("</script><script>"));
        assertTrue(text.toString().contains("\\u003c/script\\u003e"));
    }

    @Test
    public void oldSavedSharedBandRemainsReadableAndIsMarkedProvisional() {
        SkyCatalogue.VariableStar old = new SkyCatalogue.VariableStar();
        old.band = "G";
        old.max = 12.0;
        old.min = 12.07;
        JsonObject result = CatalogueComparisonWriter.match(old, 0.4, "2026-10-15");
        assertEquals("G", result.get("maxBand").getAsString());
        assertEquals("G", result.get("minBand").getAsString());
        assertTrue(result.get("legacySharedBand").getAsBoolean());
        assertFalse(result.get("comparisonMetadataComplete").getAsBoolean());
        assertEquals(12.07, old.min, 0);
        old.maxBand = "G";
        old.minBand = "g";
        assertEquals("g", CatalogueComparisonWriter.match(old, 0.4, null).get("minBand").getAsString());
    }

    private static StarLightCurve star(int frames) throws Exception {
        Constructor<StarLightCurve> constructor = StarLightCurve.class.getDeclaredConstructor(int.class, double.class, double.class, double.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(1, 10, 10, 1000, frames);
    }

    private static FitsFileInformation file(String name, String filter) {
        FitsFileInformation file = new FitsFileInformation("unused", name, true, 10, 10);
        file.getFitsHeader().put("FILTER", filter);
        return file;
    }

    private static PipelineTelemetry.PhotometryFrameStat frame(int index, String name, double jd) {
        PipelineTelemetry.PhotometryFrameStat frame = new PipelineTelemetry.PhotometryFrameStat();
        frame.frameIndex = index;
        frame.filename = name;
        frame.julianDate = jd;
        frame.used = true;
        return frame;
    }
}
