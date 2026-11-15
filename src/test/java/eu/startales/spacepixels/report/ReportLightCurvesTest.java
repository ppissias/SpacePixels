package eu.startales.spacepixels.report;

import eu.startales.spacepixels.mpc.LightCurve;
import io.github.ppissias.jtransient.photometry.PhotometryFlags;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The report's light-curve block: summaries for each bin size, the drawn points and bins, the CSV link, and a missing curve. */
public class ReportLightCurvesTest {
    @Test
    public void writesTheCurveBlock() {
        var points = new ArrayList<LightCurve.Point>();
        for (int k = 0; k < 12; k++) {
            points.add(new LightCurve.Point(k + 1, 1_000_000L + k * 60_000, 10, 20, 100 + (k % 3), 2, 0.01, k == 5 ? PhotometryFlags.CONTAMINATED : 0));
        }
        var curves = ReportLightCurves.none();
        curves.curves.put("T1", new LightCurve.Curve("T1", true, 3, 4.5, 12, true, null, points));
        curves.problems.put("FM2", "too few positions to draw a curve");
        var text = new StringWriter();
        try (var out = new PrintWriter(text)) {
            curves.write(out, "T1");
            curves.write(out, "FM2");
            curves.write(out, "FM3");
        }
        String html = text.toString();
        assertTrue(html, html.contains("<div class='lc' data-bin='1'>") && html.contains("href='light_curves/t1_lightcurve.csv'"));
        assertTrue("one summary per bin size", html.contains("lc-s1'>11 frames · 12 comparison stars") && html.contains("lc-s2'>5 bins of 2 frames")
                && html.contains("lc-s4'>2 bins of 4 frames"));
        assertTrue("two bins: no amplitude", html.contains("lc-s4'>2 bins of 4 frames · 12 comparison stars · fixed stars subtracted · median – · amplitude (5–95 %) – mag"));
        assertEquals("eleven counted points and one flagged", 11, html.split("class='lc-pt'", -1).length - 1);
        assertEquals(1, html.split("class='lc-flagged'", -1).length - 1);
        assertTrue(html.contains("<g class='lc-bins lc-bins2'>") && html.contains("<g class='lc-bins lc-bins4'>"));
        assertTrue(html.contains("Light curve: not measured (too few positions to draw a curve)."));
        assertTrue("no curve and no problem: nothing", !html.contains("FM3"));
    }
}
