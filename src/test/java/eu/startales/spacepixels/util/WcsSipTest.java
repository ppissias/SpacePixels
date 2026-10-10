package eu.startales.spacepixels.util;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** TAN-SIP solutions, against astropy's all_pix2world on the header of a real frame (dc-reg, 2502 × 1624). */
public class WcsSipTest {

    /** {x, y, RA, Dec} from astropy 7 with this header. */
    private static final double[][] ASTROPY = {
            {0, 0, 33.5271913014, 58.3138426570},
            {2501, 0, 33.5745049961, 55.9520368194},
            {0, 1623, 36.4428051141, 58.2982864021},
            {2501, 1623, 36.3252310574, 55.9391191113},
            {1250.5, 811.5, 34.9663145858, 57.1356881075},
            {300.25, 1400.75, 36.0316093765, 58.0220011784}};

    @Test
    public void appliesTheSipDistortionLikeAstropy() {
        WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(sipHeader(true));
        assertNotNull(transformer);
        assertTrue(transformer.hasDistortion());
        for (double[] point : ASTROPY) {
            WcsCoordinateTransformer.SkyCoordinate sky = transformer.pixelToSky(point[0], point[1]);
            // 1e-7 degrees is 0.4 milliarcseconds.
            assertEquals(point[2], sky.getRaDegrees(), 1e-7);
            assertEquals(point[3], sky.getDecDegrees(), 1e-7);
            double[] pixel = transformer.skyToPixel(point[2], point[3]);
            assertEquals(point[0], pixel[0], 1e-4);
            assertEquals(point[1], pixel[1], 1e-4);
        }
    }

    @Test
    public void goesBackToPixelsWithoutTheInverseTerms() {
        WcsCoordinateTransformer transformer = WcsCoordinateTransformer.fromHeader(sipHeader(false));
        assertTrue(transformer.hasDistortion());
        for (double[] point : ASTROPY) {
            double[] pixel = transformer.skyToPixel(point[2], point[3]);
            assertEquals(point[0], pixel[0], 1e-4);
            assertEquals(point[1], pixel[1], 1e-4);
        }
    }

    @Test
    public void theDistortionMovesTheCornersByPixels() {
        Map<String, String> plain = sipHeader(true);
        plain.put("CTYPE1", "RA---TAN");
        plain.put("CTYPE2", "DEC--TAN");
        WcsCoordinateTransformer withoutSip = WcsCoordinateTransformer.fromHeader(plain);
        assertFalse(withoutSip.hasDistortion());
        double[] corner = withoutSip.skyToPixel(ASTROPY[3][2], ASTROPY[3][3]);
        // Ignoring the distortion, as SpacePixels did, puts this corner more than 4 px away.
        assertTrue(Math.hypot(corner[0] - 2501, corner[1] - 1623) > 4);
    }

    @Test
    public void ignoresSipKeywordsOfAPlainTanSolutionAndIncompleteTerms() {
        Map<String, String> header = sipHeader(true);
        header.put("CTYPE1", "RA---TAN");
        header.put("CTYPE2", "DEC--TAN");
        assertFalse(WcsCoordinateTransformer.fromHeader(header).hasDistortion());
        header = sipHeader(true);
        header.remove("B_ORDER");
        assertFalse(WcsCoordinateTransformer.fromHeader(header).hasDistortion());
    }

    private static Map<String, String> sipHeader(boolean inverseTerms) {
        Map<String, String> header = new LinkedHashMap<>();
        header.put("CTYPE1", "'RA---TAN-SIP'");
        header.put("CTYPE2", "'DEC--TAN-SIP'");
        header.put("CRVAL1", "35.1241901623");
        header.put("CRVAL2", "57.3449607859");
        header.put("CRPIX1", "1028.91094971");
        header.put("CRPIX2", "900.680399577");
        header.put("CD1_1", "-1.03611828957e-05");
        header.put("CD1_2", "0.000945559328473");
        header.put("CD2_1", "-0.000944220254938");
        header.put("CD2_2", "-1.16440272374e-05");
        header.put("A_ORDER", "2");
        header.put("A_0_0", "0");
        header.put("A_0_1", "0");
        header.put("A_0_2", "3.59459686793e-06");
        header.put("A_1_0", "0");
        header.put("A_1_1", "-5.28242280791e-07");
        header.put("A_2_0", "-1.75455469014e-07");
        header.put("B_ORDER", "2");
        header.put("B_0_0", "0");
        header.put("B_0_1", "0");
        header.put("B_0_2", "-3.43514411458e-06");
        header.put("B_1_0", "0");
        header.put("B_1_1", "2.28586211146e-06");
        header.put("B_2_0", "1.75283233418e-06");
        if (inverseTerms) {
            header.put("AP_ORDER", "2");
            header.put("AP_0_0", "-0.00117266769065");
            header.put("AP_0_1", "-3.95876191056e-06");
            header.put("AP_0_2", "-3.58359060343e-06");
            header.put("AP_1_0", "3.62884973319e-06");
            header.put("AP_1_1", "5.30360933593e-07");
            header.put("AP_2_0", "1.7387360373e-07");
            header.put("BP_ORDER", "2");
            header.put("BP_0_0", "0.000683436864617");
            header.put("BP_0_1", "7.56641254812e-06");
            header.put("BP_0_2", "3.42390357297e-06");
            header.put("BP_1_0", "-2.11477627111e-07");
            header.put("BP_1_1", "-2.28772832305e-06");
            header.put("BP_2_0", "-1.7497987489e-06");
        }
        return header;
    }
}
