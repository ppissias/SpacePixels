package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.skycatalog.PhotometryStars;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import org.junit.Test;

import javax.swing.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The fetch readout shows what the next fetch uses: the FITS site first, then the IAU code, then the settings, and the depth. */
public class FetchSettingsPanelTest {
    private static FitsFileInformation frame(boolean withSite) {
        var header = new LinkedHashMap<String, String>();
        if (withSite) {
            header.put("SITELAT", "-30.16966");
            header.put("SITELONG", "-70.80653");
            header.put("SITEELEV", "2207");
        }
        return new FitsFileInformation("f.fit", "f.fit", true, 100, 100, header);
    }

    private static String readout(FetchSettingsPanel panel) {
        var labels = new ArrayList<JLabel>();
        collect(panel, labels);
        return labels.stream().map(JLabel::getText).filter(t -> t != null && t.contains("V ≤")).findFirst().orElse("");
    }

    private static void collect(java.awt.Container root, List<JLabel> found) {
        for (var c : root.getComponents()) {
            if (c instanceof JLabel l) found.add(l);
            if (c instanceof java.awt.Container k) collect(k, found);
        }
    }

    @Test
    public void showsTheSiteAndDepthTheFetchUses() {
        var panel = new FetchSettingsPanel(new JButton("Sky"), new JButton("Solar"), () -> { });
        var config = new AppConfig();
        config.observatoryCode = "W84";
        config.siteLat = "10";
        config.siteLong = "20";

        panel.showSettings(config, new FitsFileInformation[]{frame(true)}, null);
        String text = readout(panel);
        assertTrue(text, text.contains("FITS header: <b>-30.17° / -70.81°</b> · <b>2207 m</b>") && text.contains("V ≤ <b>19.0</b>"));

        panel.showSettings(config, new FitsFileInformation[]{frame(false)}, null);
        assertTrue(readout(panel), readout(panel).contains("IAU <b>W84</b>"));

        config.observatoryCode = "";
        panel.showSettings(config, new FitsFileInformation[]{frame(false)}, null);
        assertTrue(readout(panel), readout(panel).contains("Settings: <b>10.00° / 20.00°</b>"));

        panel.showSettings(config, new FitsFileInformation[0], null);
        assertTrue("without frames, the settings", readout(panel).contains("Lat / Lon: <b>10.00° / 20.00°</b>"));

        var catalogue = new SkyCatalogue();
        catalogue.photometryCheck = new PhotometryStars.Check();
        catalogue.photometryCheck.limitingMagnitude = 23.8;
        panel.showSettings(config, new FitsFileInformation[]{frame(true)}, catalogue);
        assertTrue(readout(panel), readout(panel).contains("V ≤ <b>25.0</b> · frames' depth"));
    }

    private static JLabel savedLine(FetchSettingsPanel panel) {
        var labels = new ArrayList<JLabel>();
        collect(panel, labels);
        return labels.stream().filter(l -> l.getText() != null && l.getText().contains("Saved:")).findFirst().orElse(null);
    }

    /** The line below shows what the session holds and marks where the next fetch would differ. */
    @Test
    public void showsWhatTheSessionHoldsFromEarlierFetches() {
        var panel = new FetchSettingsPanel(new JButton("Sky"), new JButton("Solar"), () -> { });
        var config = new AppConfig();
        config.observatoryCode = "J95";
        config.observingSite = "Home";
        var catalogue = new SkyCatalogue();
        catalogue.fetchedAtUtc = "2025-11-14T21:03:00Z";
        catalogue.starMagnitudeLimit = 15;
        var objects = new eu.startales.spacepixels.solarsystem.SolarSystemObjects();
        objects.fetchedAtUtc = "2025-11-15T08:10:00Z";
        objects.magnitudeLimit = 19;
        objects.observer = "site from the FITS header";

        panel.showSettings(config, new FitsFileInformation[]{frame(false)}, catalogue, objects);
        String text = savedLine(panel).getText();
        assertTrue(text, text.contains("<b>15.0</b> (14&nbsp;Nov&nbsp;2025&nbsp;21:03&nbsp;UTC)"));
        assertTrue(text, text.contains("<b>19.0</b>, FITS-header site (15&nbsp;Nov&nbsp;2025&nbsp;08:10&nbsp;UTC)"));
        assertTrue("the next fetch uses the IAU code, not the header site", text.contains("other site now"));
        assertFalse(text, text.contains("next fetch deeper"));
        assertTrue(readout(panel), readout(panel).contains("IAU <b>J95</b> (Home)"));

        config.solarSystemMagnitude = 21;
        objects.observer = "IAU code J95";
        panel.showSettings(config, new FitsFileInformation[]{frame(false)}, catalogue, objects);
        text = savedLine(panel).getText();
        assertTrue(text, text.contains("next fetch deeper") && !text.contains("other site now"));

        panel.showSettings(config, new FitsFileInformation[]{frame(false)}, null, null);
        assertTrue(savedLine(panel).getText().contains("no sky catalogue") && savedLine(panel).getText().contains("no asteroids"));
        panel.showSettings(config, new FitsFileInformation[0], null, null);
        assertFalse("hidden without frames", savedLine(panel).isVisible());
    }

    @Test
    public void savedObserversInAFewWords() {
        assertEquals("FITS-header site", FetchSettingsPanel.shortObserver("site from the FITS header"));
        assertEquals("IAU J95", FetchSettingsPanel.shortObserver("IAU code J95"));
        assertEquals("IAU J95 (Home)", FetchSettingsPanel.shortObserver("IAU code J95, site Home"));
        assertEquals("site Home", FetchSettingsPanel.shortObserver("site Home from the Astrometry Config tab"));
        assertEquals("settings' site", FetchSettingsPanel.shortObserver("site from the Astrometry Config tab"));
        assertTrue(FetchSettingsPanel.sameSite("IAU code J95", "IAU code J95, site Home"));
        assertEquals("an earlier version saved the bare code", "IAU W84", FetchSettingsPanel.shortObserver("W84"));
        assertTrue(FetchSettingsPanel.sameSite("W84", "IAU code W84, site Away"));
        assertFalse(FetchSettingsPanel.sameSite("IAU code J95, site Home", "IAU code W84, site Away"));
        assertFalse(FetchSettingsPanel.sameSite("site Home from the Astrometry Config tab", "site Away from the Astrometry Config tab"));
        assertTrue("an old file names no site", FetchSettingsPanel.sameSite("site from the Astrometry Config tab", "site Away from the Astrometry Config tab"));
    }
}
