package eu.startales.spacepixels.report;

import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Checks that optical summaries distinguish local measurements from shared and fitted field-wide components. */
public class NightEquipmentSectionWriterTest {

    /** A small radial average must not hide larger stretches in individual regions. */
    @Test
    public void localStretchRemainsVisibleWhenRadialAverageIsSmall() {
        NightEquipment ne = field();
        double ratio = 1.259;
        double ellipticity = (ratio * ratio - 1) / (ratio * ratio + 1);
        ne.shapeE1[0][0] = ellipticity;
        ne.shapeE1[1][1] = -ellipticity;

        String html = render(ne);

        assertTrue(html.contains("Measured stretch in this region: 25.9 % (shared stretch removed)"));
        assertTrue(html.contains("local stretches can cancel in this average"));
        assertTrue(html.contains("reaches 25.9 % across the available map regions"));
        assertFalse(html.contains("Round on average"));
        assertFalse(html.contains("the stars keep their shape"));
    }

    /** A local six-percent offset and a fitted two-percent radial change must have distinct labels. */
    @Test
    public void localOffsetIsSeparateFromSmallFittedRadialChange() {
        NightEquipment ne = field();
        radialOffset(ne, 0.02);
        ne.comaX[0][0] = 0.12;

        String html = render(ne);

        assertTrue(html.contains("Measured light-to-core offset in this region: 0.12 px (6 % of the reference star size)"));
        assertTrue(html.contains("fitted radial change from the centre to the corners"));
        assertTrue(html.contains("(2 % of the reference star size)"));
        assertTrue(html.contains("excludes the fitted centre offset and local deviations"));
        assertTrue(html.contains("local light-to-core offsets reach 6 %"));
        assertTrue(html.contains("local offsets can be larger"));
        assertFalse(html.contains("No coma at the edges"));
    }

    /** An uncertain fit must be identified as unresolved even when its nominal radial change is large. */
    @Test
    public void uncertainRadialFitDoesNotClaimAnAbsenceOfComa() {
        NightEquipment ne = field();
        radialOffset(ne, 0.12);
        ne.comaSlopeError = Math.abs(ne.comaSlope);
        ne.comaX[0][0] = 0.12;

        String html = render(ne);

        assertTrue(html.contains("(12 % of the reference star size)"));
        assertTrue(html.contains("A radial coma pattern is not statistically resolved"));
        assertTrue(html.contains("local light-to-core offsets reach 6 %"));
        assertFalse(html.contains("No coma"));
        assertFalse(html.contains("This pattern is consistent with coma"));
    }

    /** The fitted centre offset, radial change and local maximum remain separate in a resolved field. */
    @Test
    public void resolvedRadialFitKeepsCentreAndLocalOffsetsSeparate() {
        NightEquipment ne = field();
        radialOffset(ne, 0.20);
        ne.comaCentreX = 0.20;
        ne.comaCentreY = 0;
        ne.comaX[0][0] = 0.60;

        String html = render(ne);

        assertTrue(html.contains("offset at the field centre is 10 %"));
        assertTrue(html.contains("(20 % of the reference star size)"));
        assertTrue(html.contains("local light-to-core offsets reach 30 %"));
        assertTrue(html.contains("This pattern is consistent with coma"));
        assertFalse(html.contains("All stars lean the same way"));
    }

    /** Unavailable local measurements must not be rendered as a reassuring zero or a non-finite value. */
    @Test
    public void missingMeasurementsRemainExplicit() {
        NightEquipment ne = field();
        ne.shapeE1 = map(Double.NaN);
        ne.shapeE2 = map(Double.NaN);
        ne.comaX = map(Double.NaN);
        ne.comaY = map(Double.NaN);
        ne.trackingE1 = Double.NaN;
        ne.trackingE2 = Double.NaN;
        ne.radialEllipticity = Double.NaN;

        String html = render(ne);

        assertTrue(html.contains("Local stretch measurements are unavailable"));
        assertTrue(html.contains("Local light-to-core measurements are unavailable"));
        assertTrue(html.contains("Too few bright stars across the field to fit a radial coma pattern"));
        assertFalse(html.contains("NaN"));
        assertFalse(html.contains("No coma"));
        assertFalse(html.contains("reaches 0.0 %"));
    }

    /** Builds a small field with a two-pixel reference size and no shared or radial stretch. */
    private static NightEquipment field() {
        NightEquipment ne = new NightEquipment();
        ne.width = 400;
        ne.height = 400;
        ne.rows = 2;
        ne.columns = 2;
        ne.centreSizePx = 2;
        ne.relativeSize = map(1);
        ne.sizeArcsecOrPx = map(2);
        ne.cornerSizes = new double[4];
        ne.shapeE1 = map(0);
        ne.shapeE2 = map(0);
        ne.comaX = map(0);
        ne.comaY = map(0);
        ne.trackingE1 = 0;
        ne.trackingE2 = 0;
        ne.radialEllipticity = 0;
        return ne;
    }

    /** Returns an independently owned two-by-two map with the supplied measurement in every cell. */
    private static double[][] map(double value) {
        double[][] map = new double[2][2];
        for (double[] row : map) Arrays.fill(row, value);
        return map;
    }

    /** Sets a resolved radial change as a fraction of the reference star size from centre to corner. */
    private static void radialOffset(NightEquipment ne, double fraction) {
        ne.comaSlope = fraction * ne.centreSizePx / (Math.hypot(ne.width, ne.height) / 2);
        ne.comaSlopeError = Math.abs(ne.comaSlope) / 10;
    }

    /** Renders the production optics section without loading settings or changing the supplied measurements. */
    private static String render(NightEquipment ne) {
        StringWriter output = new StringWriter();
        try (PrintWriter writer = new PrintWriter(output)) {
            NightEquipmentSectionWriter.writeOptics(writer, ne);
        }
        return output.toString();
    }
}
