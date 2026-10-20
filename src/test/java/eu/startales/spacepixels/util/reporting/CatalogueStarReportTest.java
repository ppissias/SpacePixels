package eu.startales.spacepixels.util.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogueStore;
import eu.startales.spacepixels.util.skycatalog.SkyField;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.photometry.PhotometricReadiness;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CatalogueStarReportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void tableAndCardUseCatalogueNamesWithoutCountingThemAsVsxMatchesOrChangingMeasurements() throws Exception {
        File input = temporary.newFolder("input");
        FitsFileInformation[] files = new FitsFileInformation[3];
        List<short[][]> frames = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            files[i] = new FitsFileInformation(new File(input, "frame" + i + ".fit").toString(), "frame" + i + ".fit", true, 160, 120);
            var h = files[i].getFitsHeader();
            h.put("DATE-OBS", "2026-10-01T00:0" + (i * 2) + ":00"); h.put("EXPTIME", "120"); h.put("FILTER", "L");
            h.put("CTYPE1", "RA---TAN"); h.put("CTYPE2", "DEC--TAN"); h.put("CRPIX1", "80.5"); h.put("CRPIX2", "60.5");
            h.put("CRVAL1", "150"); h.put("CRVAL2", "20");
            h.put("CD1_1", "-0.0004166667"); h.put("CD1_2", "0"); h.put("CD2_1", "0"); h.put("CD2_2", "0.0004166667");
            short[][] data = new short[120][160];
            for (int y = 0; y < 120; y++) for (int x = 0; x < 160; x++) data[y][x] = (short) (-32000 + (x * y) % 100);
            frames.add(data);
        }
        var astrometry = DetectionReportAstrometry.buildContext(files, null);
        var sky = astrometry.getTransformer().pixelToSky(80, 60);
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.field = SkyField.of(astrometry.getTransformer(), 160, 120);
        catalogue.starMagnitudeLimit = 15;
        SkyCatalogue.NamedStar named = new SkyCatalogue.NamedStar();
        named.name = "Named <star>"; named.ra = sky.getRaDegrees(); named.dec = sky.getDecDegrees();
        catalogue.namedStars.add(named);
        SkyCatalogue.Star gaia = new SkyCatalogue.Star();
        gaia.ra = named.ra; gaia.dec = named.dec; gaia.g = 12; gaia.sourceId = "3131123006665005568";
        catalogue.stars.add(gaia);
        var photo = new PipelineTelemetry.PhotometryTelemetry(); photo.verdict = "READY"; photo.highConfidence = 1;
        for (int i = 0; i < 3; i++) {
            var stat = new PipelineTelemetry.PhotometryFrameStat(); stat.frameIndex = i; stat.filename = files[i].getFileName();
            stat.julianDate = 2460000 + i / 720.0; stat.used = true; stat.fwhm = 3; photo.frames.add(stat);
        }
        StarLightCurve star = new Gson().fromJson("{\"id\":7,\"x\":80,\"y\":60,\"tier\":\"HIGH_CONFIDENCE\",\"amplitude\":0.6,\"scatterZ\":10,\"deltaMag\":[-0.3,0,0.3],\"flags\":[0,0,0],\"magError\":[0.01,0.01,0.01],\"failedGates\":[]}", StarLightCurve.class);
        var readiness = new PhotometricReadiness(); readiness.verdict = PhotometricReadiness.Verdict.READY;
        var analysis = new VariableStarAnalysis(readiness, List.of(star), photo);
        var telemetry = new PipelineTelemetry(); telemetry.totalFramesLoaded = 3; telemetry.totalFramesKept = 3; telemetry.photometryTelemetry = photo;
        var result = new PipelineResult(List.of(), telemetry, frames.get(0), List.of(), null, null, null,
                List.of(), List.of(), List.of(), List.of(), ResidualTransientAnalysis.empty(), null, List.of(), frames.get(0), analysis);
        var config = new DetectionConfig(); config.enableVariableStarDetection = true; config.enableSlowMoverDetection = false;
        Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        String measurements = gson.toJson(analysis);
        for (String mode : List.of("named", "gaia", "vsx", "legacy")) {
            if (mode.equals("gaia")) catalogue.namedStars.clear();
            if (mode.equals("vsx")) {
                catalogue.namedStars.add(named);
                SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
                variable.name = "Known variable"; variable.type = "EA"; variable.ra = named.ra; variable.dec = named.dec;
                catalogue.variables.add(variable);
            }
            if (mode.equals("legacy")) { catalogue.namedStars.clear(); catalogue.variables.clear(); gaia.sourceId = null; }
            SkyCatalogueStore.save(input, catalogue);
            File output = temporary.newFolder(mode);
            DetectionReportGenerator.exportTrackVisualizations(result, frames, files, output, config, new AppConfig());
            String html = Files.readString(new File(output, "detection_report.html").toPath());
            if (mode.equals("named")) {
                assertTrue(html.contains("<strong>Named &lt;star&gt;</strong>"));
                assertTrue(html.contains("data-catalogue-star='Catalogue star:"));
                assertTrue(html.indexOf("<strong>Named &lt;star&gt;</strong>") != html.lastIndexOf("<strong>Named &lt;star&gt;</strong>"));
            } else if (mode.equals("gaia")) assertTrue(html.contains("Gaia DR3 3131123006665005568"));
            else if (mode.equals("vsx")) {
                assertTrue(html.contains("Known variable"));
                assertFalse(html.contains("Catalogue star:"));
            } else assertFalse(html.contains("Catalogue star:"));
            assertTrue(html.contains(mode.equals("vsx") ? "1 of 1 candidates were matched" : "0 of 1 candidates were matched"));
            assertTrue(html.contains("id='variable-v1'"));
            assertEquals(measurements, gson.toJson(analysis));
        }
    }
}
