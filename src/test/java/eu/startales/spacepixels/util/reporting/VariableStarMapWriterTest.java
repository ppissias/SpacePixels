package eu.startales.spacepixels.util.reporting;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.PixelEncoding;
import io.github.ppissias.jtransient.photometry.PhotometricReadiness;
import io.github.ppissias.jtransient.photometry.StarLightCurve;
import io.github.ppissias.jtransient.photometry.VariabilityTier;
import io.github.ppissias.jtransient.photometry.VariableStarAnalysis;
import io.github.ppissias.jtransient.telemetry.PipelineTelemetry;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.*;

public class VariableStarMapWriterTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final String SVG = "http://www.w3.org/2000/svg";

    @Test public void mapsEveryReportedCandidateBeyondTheCardLimitAtNativeResolution() throws Exception {
        List<StarLightCurve> stars = new ArrayList<>();
        for (int i = 0; i < 41; i++) stars.add(star(i + 1, 30 + (i % 10) * 190, 25 + (i / 10) * 230,
                i % 2 == 0 ? "HIGH_CONFIDENCE" : "POSSIBLE"));
        stars.add(star(99, 0, 0, "REJECTED"));
        VariableStarAnalysis analysis = analysis(stars, false);
        List<StarLightCurve> accepted = analysis.candidates.stream().filter(s -> s.tier != VariabilityTier.REJECTED).toList();
        Gson gson = new GsonBuilder().serializeSpecialFloatingPointValues().create();
        String before = gson.toJson(analysis);
        File dir = temporary.newFolder();
        String html = render(dir, stack(2000, 1200), analysis, accepted);

        assertTrue(html.contains("href='" + VariableStarMapWriter.FULL_SIZE_FILE + "' target='_blank'"));
        assertTrue(html.contains("all 41 reported candidates"));
        Document full = svg(dir, VariableStarMapWriter.FULL_SIZE_FILE);
        assertEquals("2000", full.getDocumentElement().getAttribute("width"));
        assertEquals("1300", full.getDocumentElement().getAttribute("height"));
        assertEquals(41, groups(full, "variable-map-marker").size());
        List<Element> curves = groups(full, "variable-map-curve");
        assertEquals(41, curves.size());
        for (int i = 0; i < accepted.size(); i++) {
            assertEquals("V" + (i + 1), curves.get(i).getAttribute("data-candidate"));
            assertEquals(String.valueOf(accepted.get(i).id), curves.get(i).getAttribute("data-star-id"));
        }
        assertFalse(Files.readString(new File(dir, VariableStarMapWriter.FULL_SIZE_FILE).toPath()).contains("star #99"));
        assertEquals(2000, embeddedImageWidth(full));
        Document preview = svg(dir, VariableStarMapWriter.PREVIEW_FILE);
        assertEquals(1800, embeddedImageWidth(preview));
        assertEquals(41, groups(preview, "variable-map-marker").size());
        assertTrue(groups(preview, "variable-map-curve").isEmpty());
        assertEquals(before, gson.toJson(analysis));
    }

    @Test public void miniCurvesLeaveFlaggedGapsAndUseFramesWhenATimestampIsMissing() throws Exception {
        StarLightCurve star = star(7, 0, 399, "HIGH_CONFIDENCE");
        star.deltaMag = new double[]{-.2, 99, .2, Double.NaN};
        star.flags = new int[]{0, 1, 0, 0};
        VariableStarAnalysis analysis = analysis(List.of(star), true);
        File dir = temporary.newFolder();
        render(dir, stack(700, 400), analysis, analysis.candidates);
        Document full = svg(dir, VariableStarMapWriter.FULL_SIZE_FILE);
        Element curve = groups(full, "variable-map-curve").get(0);
        assertFalse(curve.getTextContent().contains("Frame"));
        assertFalse(curve.getTextContent().contains("Minutes since first frame"));
        assertTrue(curve.getTextContent().contains("Δmag 0.400 mag"));
        Element background = (Element) curve.getElementsByTagNameNS(SVG, "rect").item(0);
        assertEquals("0.18", background.getAttribute("fill-opacity"));
        assertEquals(1, curve.getElementsByTagNameNS(SVG, "rect").getLength());
        assertEquals(0, curve.getElementsByTagNameNS(SVG, "line").getLength());
        assertEquals(1, curve.getElementsByTagNameNS(SVG, "text").getLength());
        String path = ((Element) curve.getElementsByTagNameNS(SVG, "path").item(0)).getAttribute("d");
        assertEquals(2, path.chars().filter(c -> c == 'M').count());
        assertFalse("A flagged point must break the line", path.contains("L"));
        assertTrue(curve.getTextContent().contains("Δmag -0.2000"));
        assertTrue(curve.getTextContent().contains("Δmag 0.2000"));
        assertFalse(curve.getTextContent().contains("99.0000"));
        assertFalse(Files.readString(new File(dir, VariableStarMapWriter.FULL_SIZE_FILE).toPath()).contains("NaN"));
        Element circle = (Element) groups(full, "variable-map-marker").get(0).getElementsByTagNameNS(SVG, "circle").item(0);
        assertEquals("0.50", circle.getAttribute("cx"));
        assertEquals("399.50", circle.getAttribute("cy"));
    }

    @Test public void missingStackAndEmptyCandidatesDoNotLeaveBrokenImageLinks() throws Exception {
        VariableStarAnalysis analysis = analysis(List.of(star(1, 50, 50, "POSSIBLE")), false);
        File dir = temporary.newFolder();
        assertEquals("", render(dir, stack(300, 300), analysis, List.of()));
        String html = render(dir, null, analysis, analysis.candidates);
        assertTrue(html.contains("no median stack"));
        assertFalse(html.contains("<img"));
        assertEquals(0, dir.list().length);
    }

    private static String render(File dir, short[][] stack, VariableStarAnalysis analysis, List<StarLightCurve> candidates) throws Exception {
        DetectionReportContext context = new DetectionReportContext(
                new ExportVisualizationSettings(3, 10, 100, 10, 100, false, 12, 1), dir, List.of(), null,
                new DetectionConfig(), null, stack, null, null, null, null, List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        StringWriter output = new StringWriter();
        VariableStarMapWriter.write(new PrintWriter(output), context, analysis, candidates);
        return output.toString();
    }

    private static VariableStarAnalysis analysis(List<StarLightCurve> stars, boolean missingTime) {
        PhotometricReadiness readiness = new PhotometricReadiness(); readiness.verdict = PhotometricReadiness.Verdict.READY;
        PipelineTelemetry.PhotometryTelemetry telemetry = new PipelineTelemetry.PhotometryTelemetry();
        telemetry.verdict = "READY";
        for (int i = 0; i < 4; i++) {
            var frame = new PipelineTelemetry.PhotometryFrameStat();
            frame.frameIndex = i * 2;
            frame.julianDate = missingTime && i == 1 ? Double.NaN : 2460000 + i / 1440.0;
            telemetry.frames.add(frame);
        }
        return new VariableStarAnalysis(readiness, stars, telemetry);
    }

    private static StarLightCurve star(int id, double x, double y, String tier) {
        return new Gson().fromJson("{\"id\":" + id + ",\"x\":" + x + ",\"y\":" + y + ",\"tier\":\"" + tier
                + "\",\"amplitude\":0.4,\"deltaMag\":[0,-0.2,0.2,0],\"flags\":[0,0,0,0]}", StarLightCurve.class);
    }

    private static short[][] stack(int width, int height) {
        short[][] pixels = new short[height][width];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
            pixels[y][x] = PixelEncoding.fromShiftedPositiveInt(1000 + (x * y) % 80);
        return pixels;
    }

    private static Document svg(File dir, String name) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(dir, name));
    }

    private static List<Element> groups(Document doc, String className) {
        List<Element> out = new ArrayList<>();
        var groups = doc.getElementsByTagNameNS(SVG, "g");
        for (int i = 0; i < groups.getLength(); i++) {
            Element group = (Element) groups.item(i);
            if (className.equals(group.getAttribute("class"))) out.add(group);
        }
        return out;
    }

    private static int embeddedImageWidth(Document svg) throws Exception {
        Element image = (Element) svg.getElementsByTagNameNS(SVG, "image").item(0);
        String uri = image.getAttribute("href"); assertTrue(uri.startsWith("data:image/jpeg;base64,"));
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1)))).getWidth();
    }
}
