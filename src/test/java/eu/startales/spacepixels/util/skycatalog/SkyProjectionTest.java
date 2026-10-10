package eu.startales.spacepixels.util.skycatalog;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SkyProjectionTest {

    @Test
    public void classicVariableNamesAreTheOnesPeopleKnow() {
        for (String name : new String[]{"LL UMa", "R Leo", "V0640 Mon", "V1395 Cyg", "DK Per", "bet Per", "del Cep", "alf Ori"}) {
            assertTrue(name, SkyProjection.isClassicVariableName(name));
        }
        for (String name : new String[]{"Gaia DR3 3131123006665005568", "ZTF J063910.95+060001.7", "ASASSN-V J095834.56+682719.0",
                "NSV 24739", "Mis V1395", "LINEAR 17070363", "HD 258428", null}) {
            assertFalse(name, SkyProjection.isClassicVariableName(name));
        }
    }

    @Test
    public void wellKnownDeepSkyObjectsAreMessierNgcIcAndSharpless() {
        for (String name : new String[]{"M 81", "NGC 2264", "IC 448", "Sh 2-275"}) {
            assertTrue(name, SkyProjection.isWellKnownDeepSky(name));
        }
        for (String name : new String[]{"UGC 5139", "Abell 864", "LEDA 28731", "LBN 929", "NAME F12D1", "NGC 2024S", null}) {
            assertFalse(name, SkyProjection.isWellKnownDeepSky(name));
        }
        // A common name makes any object well known; so does a proper name without a number.
        SkyCatalogue.DeepSkyObject horsehead = deepSky("Barnard 33", "Horsehead Nebula");
        assertTrue(SkyProjection.isWellKnownDeepSky(horsehead));
        assertEquals("Barnard 33 (Horsehead Nebula)", SkyProjection.deepSkyLabel(horsehead));
        assertTrue(SkyProjection.isWellKnownDeepSky(deepSky("sigma Orionis Open Cluster", null)));
        assertFalse(SkyProjection.isWellKnownDeepSky(deepSky("UGC 5139", null)));
        assertFalse(SkyProjection.isWellKnownDeepSky(deepSky("NAME IKN", null)));
    }

    @Test
    public void namesOnlyTheNotableVariablesOnAMap() {
        SkyCatalogue catalogue = new SkyCatalogue();
        catalogue.variables.add(variable("V0931 Ori", 84.0, -2.0, 11.0));
        catalogue.variables.add(variable("RU Ori", 84.1, -2.1, 12.5));
        catalogue.variables.add(variable("TZ Ori", 84.2, -2.2, 10.0));
        catalogue.variables.add(variable("zet Ori", 85.19, -1.94, 1.8));
        catalogue.variables.add(variable("Gaia DR3 3131", 84.3, -2.3, 9.0));
        SkyCatalogue.NamedStar alnitak = new SkyCatalogue.NamedStar();
        alnitak.name = "Alnitak";
        alnitak.ra = 85.1897;
        alnitak.dec = -1.9426;
        catalogue.namedStars.add(alnitak);
        java.util.List<SkyCatalogue.VariableStar> notable = SkyProjection.notableVariables(catalogue, 10);
        assertEquals(2, notable.size());
        assertEquals("TZ Ori", notable.get(0).name);
        assertEquals("RU Ori", notable.get(1).name);
        assertEquals(1, SkyProjection.notableVariables(catalogue, 1).size());
    }

    private static SkyCatalogue.DeepSkyObject deepSky(String name, String commonName) {
        SkyCatalogue.DeepSkyObject object = new SkyCatalogue.DeepSkyObject();
        object.name = name;
        object.commonName = commonName;
        return object;
    }

    private static SkyCatalogue.VariableStar variable(String name, double ra, double dec, double max) {
        SkyCatalogue.VariableStar variable = new SkyCatalogue.VariableStar();
        variable.name = name;
        variable.ra = ra;
        variable.dec = dec;
        variable.max = max;
        return variable;
    }
}
