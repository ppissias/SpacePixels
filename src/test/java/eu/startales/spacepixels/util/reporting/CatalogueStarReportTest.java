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
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

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
        for (String mode : List.of("named", "gaia", "vsx", "legacy", "no-catalogue", "unsolved")) {
            if (mode.equals("gaia")) catalogue.namedStars.clear();
            if (mode.equals("vsx")) {
                catalogue.namedStars.add(named);
                SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
                variable.name = "Known variable"; variable.type = "EA"; variable.ra = named.ra; variable.dec = named.dec;
                catalogue.variables.add(variable);
            }
            if (mode.equals("legacy")) { catalogue.namedStars.clear(); catalogue.variables.clear(); gaia.sourceId = null; }
            SkyCatalogueStore.save(input, catalogue);
            if (mode.equals("no-catalogue")) Files.delete(new File(input, SkyCatalogue.FILE_NAME).toPath());
            if (mode.equals("unsolved")) for (var file : files) file.getFitsHeader().remove("CRVAL1");
            File output = temporary.newFolder(mode);
            DetectionReportGenerator.exportTrackVisualizations(result, frames, files, output, config, new AppConfig());
            String html = Files.readString(new File(output, "detection_report.html").toPath());
            String map = Files.readString(new File(output, VariableStarMapWriter.FULL_SIZE_FILE).toPath());
            String preview = Files.readString(new File(output, VariableStarMapWriter.PREVIEW_FILE).toPath());
            String expected = switch (mode) {
                case "named" -> "Named &lt;star&gt;";
                case "gaia" -> "Gaia DR3 3131123006665005568";
                case "vsx" -> "Known variable";
                default -> "V1";
            };
            assertTrue(map.contains(">" + expected + "</text>"));
            assertTrue(preview.contains(">" + expected + "</text>"));
            assertTrue(map.contains("data-candidate='V1'"));
            assertTrue(map.contains("Δmag 0.600 mag"));
            assertEquals(mode.equals("legacy"), html.contains("This saved sky catalogue has no Gaia star identifiers"));
            if (mode.equals("named")) {
                assertTrue(html.contains("<strong>Named &lt;star&gt;</strong>"));
                assertTrue(html.contains("data-catalogue-star='Catalogue star:"));
                assertTrue(html.indexOf("<strong>Named &lt;star&gt;</strong>") != html.lastIndexOf("<strong>Named &lt;star&gt;</strong>"));
            } else if (mode.equals("gaia")) assertTrue(html.contains("Gaia DR3 3131123006665005568"));
            else if (mode.equals("vsx")) {
                assertTrue(html.contains("Known variable"));
                assertFalse(html.contains("Catalogue star:"));
            } else assertFalse(html.contains("Catalogue star:"));
            if (!mode.equals("no-catalogue") && !mode.equals("unsolved"))
                assertTrue(html.contains(mode.equals("vsx") ? "1 of 1 candidates were matched" : "0 of 1 candidates were matched"));
            assertTrue(html.contains("id='variable-follow-up'"));
            assertTrue(html.contains("not automatically a calibrated submission"));
            if (mode.equals("unsolved")) {
                assertFalse(html.contains("class='star-research'"));
                assertTrue(html.contains("Plate-solve the session to search variable-star and general star catalogues"));
            } else {
                assertTrue(html.contains("<strong>Research this star</strong>"));
                String coords = String.format(Locale.US, "%.6f %+.6f", sky.getRaDegrees(), sky.getDecDegrees());
                var gaiaLink = link(html, "Gaia DR3");
                assertEquals("vizier.cds.unistra.fr", gaiaLink.getHost());
                assertTrue(gaiaLink.getQuery().contains("-source=I/355/gaiadr3"));
                assertTrue(gaiaLink.getQuery().contains("-c=" + coords));
                assertTrue(gaiaLink.getQuery().contains("-c.rs=10"));
                assertFalse("General-star research must not inherit the downloaded magnitude limit", gaiaLink.getQuery().contains("Gmag"));
                var otherLink = link(html, "Other catalogues — VizieR");
                assertEquals("/viz-bin/VizieR", otherLink.getPath());
                assertTrue(otherLink.getQuery().contains("-c=" + coords));
                assertFalse("Other catalogues must not be restricted to VSX or Gaia", otherLink.getQuery().contains("-source"));
                assertEquals("simbad.cds.unistra.fr", link(html, "SIMBAD").getHost());
                assertEquals("/viz-bin/VizieR-4", link(html, "VSX in VizieR").getPath());
                assertTrue(html.contains("href='#variable-follow-up'"));
            }
            assertTrue(html.contains("id='variable-v1'"));
            assertEquals(measurements, gson.toJson(analysis));
        }
    }

    private static URI link(String html, String label) {
        var matcher = Pattern.compile("<a\\b[^>]*href='([^']*)'[^>]*>" + Pattern.quote(label) + "</a>").matcher(html);
        assertTrue("Missing research link: " + label, matcher.find());
        return URI.create(matcher.group(1).replace("&amp;", "&"));
    }
}
