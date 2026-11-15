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
}
