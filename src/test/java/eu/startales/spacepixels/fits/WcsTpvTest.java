package eu.startales.spacepixels.fits;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
/** TPV convention and real ZTF coordinates independently evaluated by Astropy 8.0.1/WCSLIB. */
public class WcsTpvTest {
    /** Verify real-frame corners, centre and interior in both directions, in zero-based pixels. */
    @Test
    public void agreesWithWcslibOnRealZtfFrame() {
        double[][] points = {
            {0, 0, 120.0642023906718, 68.75678286905391},
            {948, 0, 119.3371851488434, 68.71676672298358},
            {0, 949, 120.1688860779664, 68.49266227467723},
            {948, 949, 119.4501655011529, 68.453123420491},
            {474, 474.5, 119.7551001671379, 68.60523343887547},
            {161.33, 788.5, 120.0286246263553, 68.53081423583711}};
        var t = WcsCoordinateTransformer.fromHeader(realHeader());
        assertNotNull(t);
        assertTrue(t.hasDistortion());
        for (var p : points) {
            var sky = t.pixelToSky(p[0], p[1]);
            assertEquals(p[2], sky.getRaDegrees(), 1e-9);
            assertEquals(p[3], sky.getDecDegrees(), 1e-9);
            assertArrayEquals(new double[]{p[0], p[1]}, t.skyToPixel(p[2], p[3]), 1e-5);
        }
    }
    /** Preserve both axis polynomials and fitted displacement through copied/exported solutions. */
    @Test
    public void nominalHeaderRoundtripAndCorrectionPreserveTpv() {
        var t = WcsCoordinateTransformer.fromHeader(realHeader());
        Map<String, String> h = new LinkedHashMap<>();
        t.nominalHeaderCards().forEach((k,v) -> h.put(k,v.toString()));
        var copy = WcsCoordinateTransformer.fromHeader(h);
        assertEquals(t.solutionKey(), copy.solutionKey());
        var sky = t.pixelToSky(123,456);
        assertArrayEquals(new double[]{123, 456}, copy.skyToPixel(sky.getRaDegrees(),sky.getDecDegrees()),1e-5);
        var corrected = t.withDisplacement((x,y)->new double[]{2,-3});
        assertEquals(t.nominalHeaderCards(), corrected.nominalHeaderCards());
        assertArrayEquals(new double[]{125, 453}, corrected.skyToPixel(sky.getRaDegrees(),sky.getDecDegrees()),1e-5);
        var back = corrected.pixelToSky(125,453);
        assertEquals(sky.getRaDegrees(),back.getRaDegrees(),1e-10);
        h.put("PV2_4", "0.123");
        assertNotEquals(t.solutionKey(),WcsCoordinateTransformer.fromHeader(h).solutionKey());
    }
    /** Registry defaults make missing PV cards identity; reject unsupported, mismatched, singular and nonfinite headers. */
    @Test
    public void solvedUsesSupportedFiniteNonsingularSolution() {
        var h = realHeader();
        h.keySet().removeIf(k -> k.startsWith("PV"));
        var t = WcsCoordinateTransformer.fromHeader(h);
        // The TPV registry specifies missing PV1_1/PV2_1 = 1, even with other terms present.
        var offsetHeader = new LinkedHashMap<>(h);
        offsetHeader.put("PV1_0", "0.001");
        var explicitDefaults = new LinkedHashMap<>(offsetHeader);
        explicitDefaults.put("PV1_1", "1");
        explicitDefaults.put("PV2_1", "1");
        assertEquals(WcsCoordinateTransformer.fromHeader(explicitDefaults).pixelToSky(12,34).getRaDegrees(),
                WcsCoordinateTransformer.fromHeader(offsetHeader).pixelToSky(12,34).getRaDegrees(), 1e-12);
        h.put("CTYPE1", "RA---TAN");
            h.put("CTYPE2", "DEC--TAN");
        var sky=t.pixelToSky(12,34);
        var plain=WcsCoordinateTransformer.fromHeader(h).pixelToSky(12,34);
        assertEquals(plain.getRaDegrees(),sky.getRaDegrees(),1e-12);
        for (String projection : new String[]{"RA---AIT","RA---TPV","RA---TAN-SIP"}) {
            h.put("CTYPE1",projection);
            assertFalse(new FitsFileInformation("a","a",true,100,100,h).isWcsSolved());
        }
        h = realHeader();
            h.put("PV1_4", "NaN");
        assertNull(WcsCoordinateTransformer.fromHeader(h));
        h = realHeader();
            h.put("CRVAL1", "Infinity");
        assertNull(WcsCoordinateTransformer.fromHeader(h));
        h = realHeader();
            h.put("CD1_1", "0");
            h.put("CD1_2", "0");
        assertNull(WcsCoordinateTransformer.fromHeader(h));
        assertTrue(new FitsFileInformation("a","a",true,100,100,realHeader()).isWcsSolved());
        assertFalse(new FitsFileInformation("a","a",true,100,100,Map.of("CTYPE1","RA---TAN","CRVAL1","150")).isWcsSolved());
    }
    /** A degenerate TPV inverse must fail explicitly. */
    @Test
    public void noninvertiblePolynomialReturnsNull() {
        var h = realHeader();
        h.keySet().removeIf(k->k.startsWith("PV"));
        h.put("PV1_1", "0");
            h.put("PV2_1", "0");
        assertNull(WcsCoordinateTransformer.fromHeader(h).skyToPixel(150,20));
    }
    /** Every coefficient, including r, r^3, r^5 and r^7 on both axes, against WCSLIB. */
    @Test
    public void everyTpvTermAgreesWithWcslib() {
        double[][] reference = {
            {1, 0, 0.7405528507999861, 29.58735951253755},
            {1, 1, 0.7402424312016933, 29.58736146764705},
            {1, 2, 0.7389317701321829, 29.58736971459529},
            {1, 3, 0.7403657453097594, 29.58736069106675},
            {1, 4, 0.7400158248638604, 29.58736289442114},
            {1, 5, 0.7390590422587593, 29.58736891433736},
            {1, 6, 0.7395964133686448, 29.58736553413273},
            {1, 7, 0.7398504022206112, 29.58736393572332},
            {1, 8, 0.7391519509059776, 29.58736833007245},
            {1, 9, 0.7395442318120331, 29.58736586246323},
            {1, 10, 0.7393239096689967, 29.58736724852265},
            {1, 11, 0.7400779288647072, 29.58736250343603},
            {1, 12, 0.7397296436822103, 29.58736469574446},
            {1, 13, 0.7392197742155986, 29.58736790351823},
            {1, 14, 0.7395061392748516, 29.58736610213161},
            {1, 15, 0.7393453041097473, 29.58736711394492},
            {1, 16, 0.7394356361904215, 29.58736654569006},
            {1, 17, 0.7396414899445176, 29.58736525049091},
            {1, 18, 0.7392692852301934, 29.58736759211188},
            {1, 19, 0.7394783317222959, 29.58736627708266},
            {1, 20, 0.7393609220513895, 29.58736701570102},
            {1, 21, 0.7394268644701469, 29.58736660087328},
            {1, 22, 0.7393898283174281, 29.58736683386273},
            {1, 23, 0.7398761695114331, 29.5873637735369},
            {1, 24, 0.739577137713411, 29.58736565541906},
            {1, 25, 0.7393054282700291, 29.58736736477366},
            {1, 26, 0.7394580322086313, 29.58736640479328},
            {1, 27, 0.7393723231487002, 29.58736694398181},
            {1, 28, 0.7394204611143209, 29.58736664115666},
            {1, 29, 0.7393934247227776, 29.58736681123868},
            {1, 30, 0.7394086095454782, 29.58736671571383},
            {1, 31, 0.7395301605833993, 29.58736595099701},
            {1, 32, 0.7393318126887038, 29.58736719881058},
            {1, 33, 0.7394432135635611, 29.58736649802006},
            {1, 34, 0.7393806459496659, 29.58736689162618},
            {1, 35, 0.7394157866645514, 29.58736667056334},
            {1, 36, 0.7393960500987191, 29.58736679472306},
            {1, 37, 0.7394071350192917, 29.58736672498988},
            {1, 38, 0.739400909242022, 29.5873667641552},
            {1, 39, 0.7397347361923607, 29.5873646636958},
            {2, 0, 0.7394115707978699, 29.58836659102206},
            {2, 1, 0.7393996951773829, 29.58695681535087},
            {2, 2, 0.7394092967168717, 29.58809663394034},
            {2, 3, 0.7394102000934595, 29.58820387431645},
            {2, 4, 0.7394045641411253, 29.58753482331619},
            {2, 5, 0.7394006275277434, 29.58706749772077},
            {2, 6, 0.7394076366455238, 29.58789956528213},
            {2, 7, 0.7394025678592016, 29.58729784004033},
            {2, 8, 0.7394041818736241, 29.58748944353888},
            {2, 9, 0.7394013081448634, 29.58714829585271},
            {2, 10, 0.7394064247976075, 29.58775570516774},
            {2, 11, 0.7394080916059806, 29.58795357414832},
            {2, 12, 0.7394033863336062, 29.58739500318175},
            {2, 13, 0.7394027245881603, 29.58731644574809},
            {2, 14, 0.7394039028185944, 29.58745631630176},
            {2, 15, 0.7394018049960209, 29.58720727849007},
            {2, 16, 0.7394055401508126, 29.58765068728749},
            {2, 17, 0.7394030507589378, 29.58735516629348},
            {2, 18, 0.7394033220746223, 29.58738737484141},
            {2, 19, 0.7394028390004337, 29.5873300279148},
            {2, 20, 0.7394036991084931, 29.58743213341885},
            {2, 21, 0.73940216769779, 29.58725033581588},
            {2, 22, 0.7394048943598364, 29.58757402423665},
            {2, 23, 0.7394066135625508, 29.58777811373975},
            {2, 24, 0.7394031883445109, 29.58737149941762},
            {2, 25, 0.7394030771050666, 29.58735829391299},
            {2, 26, 0.7394032751655573, 29.58738180615297},
            {2, 27, 0.7394029225213785, 29.58733994289653},
            {2, 28, 0.7394035504002545, 29.5874144799144},
            {2, 29, 0.7394024324702286, 29.58728176766401},
            {2, 30, 0.7394044229330348, 29.58751806021045},
            {2, 31, 0.7394031319344094, 29.58736480283673},
            {2, 32, 0.739403177542556, 29.58737021709362},
            {2, 33, 0.73940309633781, 29.58736057707524},
            {2, 34, 0.7394032409219449, 29.58737774101042},
            {2, 35, 0.7394029834916864, 29.5873471808332},
            {2, 36, 0.7394034418431943, 29.58740159285622},
            {2, 37, 0.7394026257542237, 29.58730471291331},
            {2, 38, 0.7394040787918357, 29.58747720647182},
            {2, 39, 0.7394055774572053, 29.58765511599792}};
        for (var row : reference) {
            Map<String, String> h = new LinkedHashMap<>();
            h.put("CTYPE1", "RA---TPV");
            h.put("CTYPE2", "DEC--TPV");
            h.put("CRPIX1", "1");
            h.put("CRPIX2", "1");
            h.put("CRVAL1", "359.9");
            h.put("CRVAL2", "30");
            h.put("CD1_1", "0.001");
            h.put("CD1_2", "0");
            h.put("CD2_1", "0");
            h.put("CD2_2", "0.001");
            // Explicit identity terms avoid WCSLIB defaults for partially supplied PV axes.
            h.put("PV1_1", "1");
            h.put("PV2_1", "1");
            h.put("PV"+(int)row[0]+"_"+(int)row[1], row[1]==1 ? "1.001" : "0.001");
            var t = WcsCoordinateTransformer.fromHeader(h);
            var sky = t.pixelToSky(730,-410);
            assertEquals(row[2],sky.getRaDegrees(),1e-10);
            assertEquals(row[3],sky.getDecDegrees(),1e-10);
            assertArrayEquals(new double[]{730,-410},t.skyToPixel(row[2],row[3]),1e-6);
        }
    }

    /** Sanitized coordinate-only header of a real ZTF science cutout; no site or private paths. */
    private static Map<String, String> realHeader() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("CTYPE1", "RA---TPV");
        h.put("CTYPE2", "DEC--TPV");
        h.put("CRPIX1", "484.5");
        h.put("CRPIX2", "478.5");
        h.put("CRVAL1", "119.7482212363");
        h.put("CRVAL2", "68.60396911131");
        h.put("CD1_1", "-0.0002781275680092");
        h.put("CD1_2", "4.187460566579e-05");
        h.put("CD2_1", "-4.192946412017e-05");
        h.put("CD2_2", "-0.0002781324038712");
        h.put("PV1_0", "-7.457803093609e-06");
        h.put("PV1_1", "1.000083394028");
        h.put("PV1_2", "2.847622852217e-05");
        h.put("PV1_4", "0.0002755070233403");
        h.put("PV1_5", "0.0001014434801065");
        h.put("PV1_6", "-0.0001110729155565");
        h.put("PV1_7", "7.467880058871e-05");
        h.put("PV1_8", "0.0005898887114827");
        h.put("PV1_9", "-0.0002184048542095");
        h.put("PV1_10", "-0.0002010000136599");
        h.put("PV1_12", "7.172586766256e-05");
        h.put("PV1_13", "-0.001138692011395");
        h.put("PV1_14", "0.001267990609656");
        h.put("PV1_15", "-0.0002953189561194");
        h.put("PV1_16", "-2.527445560297e-05");
        h.put("PV2_0", "3.137013745514e-05");
        h.put("PV2_1", "0.9997916651304");
        h.put("PV2_2", "0.0002485391145326");
        h.put("PV2_4", "-0.0004091556220753");
        h.put("PV2_5", "0.0003406385515283");
        h.put("PV2_6", "0.0002329381292785");
        h.put("PV2_7", "0.0001209546489608");
        h.put("PV2_8", "-0.0003828673661741");
        h.put("PV2_9", "0.0001407847131827");
        h.put("PV2_10", "0.0002654201187222");
        h.put("PV2_12", "0.0006478567518616");
        h.put("PV2_13", "-0.0002772181145061");
        h.put("PV2_14", "-0.001007983419735");
        h.put("PV2_15", "0.0002740803637329");
        h.put("PV2_16", "-0.001154021165184");
        return h;
    }
}
