package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.Track;
import eu.startales.spacepixels.util.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.skycatalog.SkyCatalogue;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The optional report sections fail on their own: the rest of the report is still written. */
public class ReportSectionFailureTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aFailingSessionPictureLeavesANoteAndNoHalfWrittenHtml() throws Exception {
        File exportDir = folder.newFolder("export");
        StringWriter output = new StringWriter();
        try (PrintWriter report = new PrintWriter(output)) {
            // A null track makes the detections layer fail after the images are written.
            List<Track> brokenTracks = Collections.singletonList(null);
            SessionPictureSectionWriter.write(report, context(exportDir, new short[40][60]), brokenTracks);
        }
        String html = output.toString();
        assertTrue(html.contains("The session picture could not be written"));
        assertFalse(html.contains("session-picture"));
    }

    @Test
    public void aFailingCatalogueLeavesTheMapWithoutLabels() {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.deepSky = null;
        BufferedImage map = new BufferedImage(60, 40, BufferedImage.TYPE_INT_RGB);
        assertEquals(0, CatalogueMapPainter.paint(map, catalogue, WcsCoordinateTransformer.fromHeader(header())));
    }

    private static DetectionReportContext context(File exportDir, short[][] masterStack) {
        return new DetectionReportContext(
                VisualizationSettings.defaults().withAutoStretchBlackSigma(3.0).withAutoStretchWhiteSigma(10.0).withGifBlinkSpeedMs(100)
                        .withTrackCropPadding(10).withTrackObjectCentricCropSize(100).withTargetCircleRadius(12).withTargetCircleStrokeWidth(1.0f),
                exportDir,
                Collections.emptyList(),
                null,
                new DetectionConfig(),
                null,
                masterStack,
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
    }

    private static Map<String, String> header() {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        header.put("CRPIX1", "30.5");
        header.put("CRPIX2", "20.5");
        header.put("CRVAL1", "150.0");
        header.put("CRVAL2", "20.0");
        header.put("CD1_1", Double.toString(-1.5 / 3600));
        header.put("CD1_2", "0");
        header.put("CD2_1", "0");
        header.put("CD2_2", Double.toString(1.5 / 3600));
        return header;
    }
}
