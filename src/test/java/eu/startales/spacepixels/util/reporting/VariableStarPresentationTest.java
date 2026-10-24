package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.VisualizationSettings;
import com.google.gson.*;
import eu.startales.spacepixels.config.*;
import eu.startales.spacepixels.util.*;
import eu.startales.spacepixels.util.skycatalog.*;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.ResidualTransientAnalysis;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.photometry.*;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

/** Protects variable-star identity across card presentation, tabular exports, maps and narrative subsets. */
public class VariableStarPresentationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();

    @Test public void prioritiesKeepConfidenceAndIdentityWhileUsingObservedChangesOrCataloguePeriods() throws Exception {
        var steady = star(1, "HIGH_CONFIDENCE", 10, .8, false);
        var cycle = star(2, "HIGH_CONFIDENCE", 9, .3, true);
        var possible = star(3, "POSSIBLE", 100, 5, true);
        var analysis = analysis(List.of(steady, cycle, possible));
        File input = temporary.newFolder();
        var files = files(input);
        var astrometry = AstrometryContextFactory.buildContext(files, null, null);
        var catalogue = new SkyCatalogue(); catalogue.field = SkyField.of(astrometry.getTransformer(), 160, 120);
        for (var star : List.of(steady, cycle)) {
            var sky = astrometry.getTransformer().pixelToSky(star.x, star.y);
            var variable = new SkyCatalogue.VariableStar(); variable.ra = sky.getRaDegrees(); variable.dec = sky.getDecDegrees();
            variable.name = "Known " + star.id; variable.periodDays = star.id == 1 ? 3.0 : .125;
            catalogue.variables.add(variable);
        }
        SkyCatalogueStore.save(input, catalogue);
        String before = gson.toJson(analysis);
        var observed = context(temporary.newFolder(), files, new VariableStarReportOptions(1, VariableStarReportOptions.SortOrder.OBSERVED_CHANGES));
        var plan = observed.variablePresentation(analysis);
        assertEquals(List.of(2, 1, 3), plan.ordered.stream().map(s -> s.id).toList());
        assertEquals(2, plan.number(cycle)); assertTrue(plan.hasCard(cycle)); assertFalse(plan.hasCard(steady));
        var periods = context(temporary.newFolder(), files, new VariableStarReportOptions(40, VariableStarReportOptions.SortOrder.SHORTEST_PERIOD));
        assertEquals(List.of(2, 1, 3), periods.variablePresentation(analysis).ordered.stream().map(s -> s.id).toList());
        var largest = context(temporary.newFolder(), files, new VariableStarReportOptions(40, VariableStarReportOptions.SortOrder.LARGEST_VARIATION));
        assertEquals(List.of(1, 2, 3), largest.variablePresentation(analysis).ordered.stream().map(s -> s.id).toList());
        var significance = context(temporary.newFolder(), files, new VariableStarReportOptions(40, VariableStarReportOptions.SortOrder.SIGNIFICANCE));
        assertEquals(List.of(1, 2, 3), significance.variablePresentation(analysis).ordered.stream().map(s -> s.id).toList());
        catalogue.variables.remove(0); SkyCatalogueStore.save(input, catalogue);
        periods = context(temporary.newFolder(), files, new VariableStarReportOptions(40, VariableStarReportOptions.SortOrder.SHORTEST_PERIOD));
        assertEquals("Unknown periods follow known periods within each tier", List.of(2, 1, 3),
                periods.variablePresentation(analysis).ordered.stream().map(s -> s.id).toList());
        assertEquals(before, gson.toJson(analysis));
    }

    @Test public void fortyAndSixtyCardsHaveValidLinksAndUnchangedCsvAndViewerIdentities() throws Exception {
        List<StarLightCurve> stars = new ArrayList<>();
        for (int i = 1; i <= 65; i++) stars.add(star(i, i == 65 ? "POSSIBLE" : "HIGH_CONFIDENCE", 100 - i, i / 100.0, i % 2 == 0));
        var analysis = analysis(stars); String before = gson.toJson(analysis);
        var files = files(temporary.newFolder());
        List<short[][]> frames = new ArrayList<>(); for (int i = 0; i < 9; i++) frames.add(stack());
        var telemetry = new PipelineTelemetry(); telemetry.totalFramesLoaded = 9; telemetry.totalFramesKept = 9; telemetry.photometryTelemetry = analysis.telemetry;
        var result = PipelineResult.builder()
                .tracks(List.of())
                .telemetry(telemetry)
                .masterStackData(frames.get(0))
                .masterStars(List.of())
                .slowMoverCandidates(List.of())
                .anomalies(List.of())
                .allTransients(List.of())
                .unclassifiedTransients(List.of())
                .residualTransientAnalysis(ResidualTransientAnalysis.empty())
                .driftPoints(List.of())
                .maximumStackData(frames.get(0))
                .variableStarAnalysis(analysis)
                .build();
        var config = new DetectionConfig(); config.enableVariableStarDetection = true; config.enableSlowMoverDetection = false;
        Map<String, String> csv = new HashMap<>();
        for (var options : List.of(new VariableStarReportOptions(40, VariableStarReportOptions.SortOrder.SIGNIFICANCE),
                new VariableStarReportOptions(60, VariableStarReportOptions.SortOrder.LARGEST_VARIATION))) {
            File output = temporary.newFolder(); var app = new AppConfig();
            DetectionReportGenerator.exportTrackVisualizations(result, frames, files, output, config,
                    ReportRunContext.capture(DetectionRunOptions.capture(app, SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES)
                            .withVariableStarReportOptions(options), files),
                    ReportInclusion.all(), null);
            String html = Files.readString(new File(output, "detection_report.html").toPath());
            assertEquals(options.maxCandidateCards(), count(html, "id='variable-v[0-9]+'"));
            var ids = new HashSet<String>(); var matches = Pattern.compile("(?<![\\w-])id=['\"]([^'\"]+)['\"]").matcher(html);
            while (matches.find()) assertTrue("Duplicate anchor", ids.add(matches.group(1)));
            matches = Pattern.compile("href=['\"]#([^'\"]+)['\"]").matcher(html);
            while (matches.find()) assertTrue("Missing target " + matches.group(1), ids.contains(matches.group(1)));
            var findings = JsonParser.parseString(Files.readString(new File(output, "report-data.json").toPath())).getAsJsonObject().getAsJsonArray("findings");
            assertEquals(65, findings.size());
            for (var finding : findings) assertTrue(ids.contains(finding.getAsJsonObject().get("anchor").getAsString()));
            var first = findings.get(0).getAsJsonObject();
            assertEquals(options.maxCandidateCards() == 40 ? "V1" : "V64", first.get("id").getAsString());
            assertEquals(options.maxCandidateCards() == 40 ? 1 : 64, first.get("starId").getAsInt());
            var overlay = VariableStarOverlay.from(result, files, null, null);
            assertEquals("V64", overlay.getCandidates().get(63).label);
            for (String name : List.of("photometry_stars.csv", "photometry_lightcurves.csv", "photometry_frames.csv")) {
                String contents = Files.readString(new File(output, name).toPath());
                if (csv.containsKey(name)) assertEquals("Presentation must not change CSV measurements or labels", csv.get(name), contents);
                csv.put(name, contents);
            }
            assertTrue(Files.readString(new File(output, VariableStarMapWriter.PREVIEW_FILE).toPath()).contains("data-candidate='V65'"));
        }
        assertEquals(before, gson.toJson(analysis));
    }

    /** A synthetic external candidate ordering must retain V identities in the presentation and the report anchors. */
    @Test public void presentationRetainsIdsWhenPossibleCandidatesComeFirst() throws Exception {
        var possible = star(10, "POSSIBLE", 5, .1, false);
        var rejected = star(20, "NOT_SCORED", 0, .2, false);
        var high = star(30, "HIGH_CONFIDENCE", 10, .8, true);
        var analysis = analysis(List.of(possible, rejected, high));
        var snapshot = gson.toJsonTree(analysis).getAsJsonObject();
        snapshot.add("candidates", gson.toJsonTree(List.of(possible, rejected, high)));
        analysis = gson.fromJson(snapshot, VariableStarAnalysis.class);
        var files = files(temporary.newFolder());
        var context = context(temporary.newFolder(), files,
                new VariableStarReportOptions(1, VariableStarReportOptions.SortOrder.SIGNIFICANCE));
        var presentation = context.variablePresentation(analysis);
        assertEquals(List.of(10, 30), presentation.identities.stream().map(star -> star.id).toList());
        assertEquals("V1", presentation.label(possible));
        assertEquals("V2", presentation.label(high));
        assertEquals(Map.of(10, 1, 30, 2), FindingCategory.variableNumbers(analysis.candidates));
        var telemetry = new PipelineTelemetry();
        telemetry.photometryTelemetry = analysis.telemetry;
        telemetry.totalFramesLoaded = 9;
        telemetry.totalFramesKept = 9;
        context.data = ReportData.create(context, telemetry, analysis, List.of(), ReportInclusion.all());
        assertEquals("variable-v2", context.findingAnchor(FindingCategory.VARIABLE_STAR, 2));
        assertEquals("variable-candidates", context.findingAnchor(FindingCategory.VARIABLE_STAR, 1));
    }

    private StarLightCurve star(int id, String tier, double score, double amplitude, boolean cycle) {
        double[] curve = cycle ? new double[]{0,0,0,.8,.8,.8,0,0,0} : new double[]{0,.1,.2,.3,.4,.5,.6,.7,.8};
        var object = new JsonObject(); object.addProperty("id", id); object.addProperty("tier", tier);
        object.addProperty("x", 10 + ((id-1)%10)*12); object.addProperty("y", 10 + ((id-1)/10)*16);
        object.addProperty("scatterZ", score); object.addProperty("amplitude", amplitude); object.addProperty("usableFrames", 9);
        object.add("failedGates", new JsonArray());
        object.add("deltaMag", gson.toJsonTree(curve)); object.add("flags", gson.toJsonTree(new int[9]));
        object.add("magError", gson.toJsonTree(new double[]{.01,.01,.01,.01,.01,.01,.01,.01,.01}));
        return gson.fromJson(object, StarLightCurve.class);
    }
    private VariableStarAnalysis analysis(List<StarLightCurve> stars) {
        var ready = new PhotometricReadiness(); ready.verdict = PhotometricReadiness.Verdict.READY;
        var telemetry = new PipelineTelemetry.PhotometryTelemetry(); telemetry.verdict = "READY";
        for (int i = 0; i < 9; i++) { var frame = new PipelineTelemetry.PhotometryFrameStat(); frame.frameIndex = i;
            frame.filename = "frame" + i + ".fit"; frame.julianDate = 2460000 + i / 1440.0; frame.used = true; frame.fwhm = 3; telemetry.frames.add(frame); }
        return new VariableStarAnalysis(ready, stars, telemetry);
    }
    private FitsFileInformation[] files(File input) {
        var files = new FitsFileInformation[9];
        for (int i = 0; i < 9; i++) {
            files[i] = new FitsFileInformation(new File(input,"frame"+i+".fit").toString(),"frame"+i+".fit",true,160,120);
            var h = files[i].getFitsHeader(); h.put("DATE-OBS", "2026-10-01T00:0"+i+":00"); h.put("EXPTIME","60");
            h.put("CTYPE1","RA---TAN"); h.put("CTYPE2","DEC--TAN"); h.put("CRPIX1","80.5"); h.put("CRPIX2","60.5");
            h.put("CRVAL1","150"); h.put("CRVAL2","20"); h.put("CD1_1","-0.0004166667");h.put("CD1_2","0");h.put("CD2_1","0");h.put("CD2_2","0.0004166667");
        } return files;
    }
    private DetectionReportContext context(File output, FitsFileInformation[] files, VariableStarReportOptions options) {
        return new DetectionReportContext(VisualizationSettings.defaults().withAutoStretchBlackSigma(3).withAutoStretchWhiteSigma(10).withGifBlinkSpeedMs(100)
                .withTrackCropPadding(10).withTrackObjectCentricCropSize(100).withTargetCircleRadius(12).withTargetCircleStrokeWidth(1)
                .withVariableStarReportOptions(options),output,List.of(),files,
                new DetectionConfig(),AstrometryContextFactory.buildContext(files, null, null),null,null,null,null,null,List.of(),List.of(),null,List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
    private short[][] stack() { short[][] pixels = new short[120][160]; for (int y=0;y<120;y++)for(int x=0;x<160;x++)pixels[y][x]=(short)(-32000+(x*y)%100);return pixels; }
    private int count(String text, String regex) { int count=0;var m=Pattern.compile(regex).matcher(text);while(m.find())count++;return count; }
}
