package eu.startales.spacepixels.config;

import org.junit.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Named observing sites: the selected one is the site in use, and the two never drift apart. */
public class ObservingSitesTest {
    private static AppConfig oldConfig() {
        AppConfig config = new AppConfig();
        config.observatoryCode = "J95";
        config.siteLat = "37.98";
        config.siteLong = "23.73";
        config.siteAltitude = "120";
        return config;
    }

    @Test
    public void aSiteFromBeforeNamesBecomesTheFirstAndStaysInUse() throws Exception {
        // A file written before sites had names: only the four fields.
        String json = "{\"observatoryCode\":\"J95\",\"siteLat\":\"37.98\",\"siteLong\":\"23.73\",\"siteAltitude\":\"120\"}";
        AppConfig config = SpacePixelsAppConfigIO.load(new StringReader(json));
        ObservingSites.ensure(config);
        assertEquals(1, config.observingSites.size());
        assertEquals(ObservingSites.FIRST_SITE_NAME, config.observingSite);
        ObservingSites.Site site = ObservingSites.selected(config);
        assertEquals(List.of("J95", "37.98", "23.73", "120"), List.of(site.observatoryCode, site.siteLat, site.siteLong, site.siteAltitude));
        assertEquals("J95", config.observatoryCode);
    }

    @Test
    public void choosingAddingAndRemovingKeepTheSiteInUseInStep() {
        AppConfig config = oldConfig();
        ObservingSites.ensure(config);
        ObservingSites.Site added = ObservingSites.add(config);
        assertEquals(ObservingSites.NEW_SITE_NAME, added.name);
        assertEquals("a new site is empty and in use", "", config.observatoryCode);
        assertEquals(ObservingSites.NEW_SITE_NAME + " 2", ObservingSites.add(config).name);

        assertTrue(ObservingSites.select(config, "my site"));
        assertEquals("My site", config.observingSite);
        assertEquals("37.98", config.siteLat);
        assertFalse(ObservingSites.select(config, "nowhere"));

        assertTrue(ObservingSites.remove(config, "My site"));
        assertEquals("the next site takes the removed one's place", ObservingSites.NEW_SITE_NAME, config.observingSite);
        assertEquals("", config.siteLat);
        assertTrue(ObservingSites.remove(config, ObservingSites.NEW_SITE_NAME + " 2"));
        assertFalse("the last site stays", ObservingSites.remove(config, ObservingSites.NEW_SITE_NAME));
    }

    @Test
    public void namesAreRequiredAndUnique() {
        AppConfig config = oldConfig();
        ObservingSites.ensure(config);
        ObservingSites.add(config);
        assertNotNull(ObservingSites.nameProblem(config, " "));
        assertNotNull(ObservingSites.nameProblem(config, "MY SITE"));
        assertNull(ObservingSites.nameProblem(config, ObservingSites.NEW_SITE_NAME));
        ObservingSites.rename(config, "My site");
        assertEquals("a taken name is refused", ObservingSites.NEW_SITE_NAME, config.observingSite);
        ObservingSites.rename(config, " Dark site ");
        assertEquals("Dark site", config.observingSite);
        assertEquals("Dark site", ObservingSites.selected(config).name);

        AppConfig hand = new AppConfig();
        ObservingSites.Site a = new ObservingSites.Site();
        a.name = "Home";
        ObservingSites.Site b = new ObservingSites.Site();
        b.name = "home";
        ObservingSites.Site c = new ObservingSites.Site();
        hand.observingSites = new java.util.ArrayList<>(List.of(a, b, c));
        hand.observingSite = "elsewhere";
        ObservingSites.ensure(hand);
        assertEquals(List.of("Home", "home 2", ObservingSites.NEW_SITE_NAME), hand.observingSites.stream().map(s -> s.name).toList());
        assertEquals("Home", hand.observingSite);
    }

    @Test
    public void copiesAndFilesKeepTheSites() throws Exception {
        AppConfig config = oldConfig();
        ObservingSites.ensure(config);
        ObservingSites.add(config);
        ObservingSites.rename(config, "Dark site");
        config.siteLat = "-24.6";
        ObservingSites.selected(config).siteLat = "-24.6";

        AppConfig copy = config.copy();
        copy.observingSites.getFirst().siteLat = "0";
        assertEquals("the copy is deep", "37.98", config.observingSites.getFirst().siteLat);

        StringWriter written = new StringWriter();
        SpacePixelsAppConfigIO.write(written, config);
        AppConfig read = SpacePixelsAppConfigIO.load(new StringReader(written.toString()));
        assertEquals(config.observingSites, read.observingSites);
        assertEquals("Dark site", read.observingSite);
        ObservingSites.ensure(read);
        assertEquals("-24.6", read.siteLat);
    }
}
