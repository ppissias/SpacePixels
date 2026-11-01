package eu.startales.spacepixels.report;

import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.PipelineResult;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The enhanced stack in a report: the setting, the FITS file and its header, and which stack the field pictures show. */
public class EnhancedStackReportTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** Switched off, the report builds no stack and keeps showing the median stack. */
    @Test public void switchedOffTheReportShowsTheMedianStack() throws Exception {
        DetectionReportContext context = context(VisualizationSettings.defaults().withEnhancedStack(false));
        assertNull(context.enhancedStack);
        assertEquals("median stack", context.fieldName());
        assertFalse(EnhancedStackFile.in(context.exportDir).exists());
    }

    /** Switched on (the default), the stack is a float image with a new header: no integer scaling, no site. */
    @Test public void theFitsFileHasAFloatImageAndANewHeader() throws Exception {
        assertTrue(VisualizationSettings.defaults().isEnhancedStack());
        DetectionReportContext context = context(VisualizationSettings.defaults());
        assertEquals("enhanced stack", context.fieldName());
        assertEquals(6, context.enhancedStack.frames);
        // The field picture is the stack under the automatic asinh stretch, made once for all the field pictures.
        java.awt.image.BufferedImage field = context.fieldDisplayImage(null);
        assertTrue(field == context.fieldDisplayImage(null));
        var levels = eu.startales.spacepixels.fits.DisplayImageRenderer.asinhLevels(context.enhancedStack.pixels);
        assertEquals(Math.round(levels.display(context.enhancedStack.pixels[80][80]) * 255),
                field.getRaster().getSample(80, 80, 0), 1);
        assertEquals(0, field.getRaster().getSample(0, 0, 0));
        File file = EnhancedStackFile.write(context);
        assertEquals(new File(new File(context.exportDir, "enhanced_stack"), "enhanced_stack.fits"), file);
        try (Fits fits = new Fits(file)) {
            BasicHDU<?> hdu = fits.readHDU();
            Header header = hdu.getHeader();
            assertEquals(-32, header.getIntValue("BITPIX"));
            assertFalse(header.containsKey("BZERO"));
            assertFalse(header.containsKey("BSCALE"));
            assertFalse(header.containsKey("SITELAT"));
            assertFalse(header.containsKey("SITELONG"));
            assertEquals("M13", header.getStringValue("OBJECT"));
            assertEquals(6, header.getIntValue("NCOMBINE"));
            assertEquals(720.0, header.getDoubleValue("EXPTOTAL"), 1e-9);
            assertTrue(header.getStringValue("DATE-OBS").startsWith("2026-03-10T03:00:00"));
            // No time of writing or time zone of the computer, and no text card continued in the middle of a word.
            assertEquals("conforms to the FITS standard", header.findCard("SIMPLE").getComment());
            for (var cards = header.iterator(); cards.hasNext(); ) {
                var card = cards.next();
                assertFalse(card.toString(), card.toString().trim().endsWith("&"));
            }
            float[][] pixels = (float[][]) hdu.getKernel();
            assertTrue(Float.isNaN(pixels[0][0]));
            assertEquals(context.enhancedStack.pixels[40][40], pixels[40][40], 0.0f);
            assertEquals(1000, pixels[40][40], 5);
        }
    }

    /** Six noisy frames with registration fill in one corner of every frame, one start time each. */
    private DetectionReportContext context(VisualizationSettings settings) throws Exception {
        List<short[][]> frames = new ArrayList<>();
        FitsFileInformation[] files = new FitsFileInformation[6];
        for (int i = 0; i < 6; i++) {
            short[][] frame = EnhancedStackTest.noise(new Random(50 + i), 1000, 20);
            frame[0][0] = Short.MIN_VALUE;
            frames.add(frame);
            files[i] = new FitsFileInformation("f" + i + ".fit", "f" + i + ".fit", true, 160, 160,
                    Map.of("DATE-OBS", "2026-03-10T03:0" + i + ":00", "EXPTIME", "120", "OBJECT", "M13",
                            "SITELAT", "-00 30 00", "SITELONG", "000 30 00"));
        }
        PipelineResult result = PipelineResult.builder().masterStackData(EnhancedStackTest.median(frames)).build();
        return ReportContextFixtures.create(settings, folder.newFolder(), frames, files, new DetectionConfig(), result);
    }
}
