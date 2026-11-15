package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.solarsystem.SolarSystemQuery;
import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Compact read-only fetch settings beside their commands; all construction and updates belong to the EDT. */
final class FetchSettingsPanel extends JPanel {
    private final WrappingHelpLabel sky = new WrappingHelpLabel("", 180);
    private final WrappingHelpLabel solar = new WrappingHelpLabel("", 180);

    /** Keeps the existing shared command buttons and supplies keyboard-accessible navigation to their settings. */
    FetchSettingsPanel(JButton skyButton, JButton solarButton, Runnable editSettings) {
        super(new GridBagLayout());
        setOpaque(false);
        sky.getAccessibleContext().setAccessibleName("Sky catalogue fetch settings");
        solar.getAccessibleContext().setAccessibleName("Asteroid and comet fetch settings");
        sky.setToolTipText("The Gaia G magnitude limit used by the next Fetch Sky Catalogue.");
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(2, 4, 2, 4);
        c.gridx = 0; c.gridy = 0;
        add(skyButton, c);
        c.gridy = 1;
        add(solarButton, c);
        c.gridx = 1; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        add(readout(sky), c);
        c.gridy = 1;
        c.gridheight = 2;
        add(readout(solar), c);
        JButton edit = AccessibleControls.link("Edit settings…", "Edit astrometry settings", editSettings);
        edit.setFont(UiStyle.bodyFont());
        edit.setMargin(new Insets(0, 0, 0, 0));
        edit.setHorizontalAlignment(SwingConstants.LEFT);
        edit.setToolTipText("Open Astrometry Config to change fetch depth and observing-site defaults.");
        c.gridx = 0; c.gridy = 2; c.gridheight = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        add(edit, c);
    }

    /**
     * Shows what the next fetches will use: the sky catalogue's depth, and for the asteroids and comets the depth (the
     * setting, or deeper when the session's frames reach fainter) and the observer chosen as the fetch chooses it
     * ({@link SolarSystemQuery#observer}: the FITS headers' site first, then a compatible IAU code, then the
     * Astrometry Config site). Without a session it shows the settings. Retains no mutable settings.
     *
     * @param files     the session's frames, empty without a session
     * @param catalogue the session's sky catalogue, or null
     */
    void showSettings(AppConfig config, FitsFileInformation[] files, SkyCatalogue catalogue) {
        double setting = config.solarSystemMagnitude();
        var check = catalogue == null ? null : catalogue.photometryCheck;
        double frames = check == null ? Double.NaN : check.solarSystemDepth(AppConfig.MAX_SOLAR_SYSTEM_MAGNITUDE);
        boolean deeper = frames > setting;
        String depth = deeper ? String.format(Locale.US, "V ≤ <b>%.1f</b> · frames' depth", frames)
                : String.format(Locale.US, "V ≤ <b>%.1f</b>", setting);
        sky.setText(String.format(Locale.US, "<html>Gaia G ≤ <b>%.1f</b></html>", config.skyCatalogueStarMagnitude()));
        String site;
        String source;
        if (files == null || files.length == 0) {
            site = siteText(config.observatoryCode, coordinate(config.siteLat), coordinate(config.siteLong), ObservingSite.parseAltitude(config.siteAltitude));
            source = "Without frames, the settings are shown.";
        } else {
            SolarSystemQuery.Observer observer;
            try {
                observer = SolarSystemQuery.observer(files, config);
            } catch (IllegalArgumentException missing) {
                observer = null;
            }
            if (observer == null) {
                site = "Site: <b>none</b>, set one in Astrometry Config";
                source = "Neither the FITS headers nor the settings give an observing site.";
            } else if (observer.site() != null && ObservingSite.FROM_HEADER.equals(observer.site().source())) {
                site = "FITS header: <b>" + degrees(observer.site().latitudeDeg()) + " / " + degrees(observer.site().longitudeDeg())
                        + "</b> · <b>" + String.format(Locale.US, "%.0f m", observer.site().altitudeMeters()) + "</b>";
                source = "The FITS headers give the observing site, so it is used rather than the settings.";
            } else if (observer.site() == null) {
                site = "IAU <b>" + value(observer.observatoryCode()) + "</b>";
                source = "The IAU observatory code from Astrometry Config is used; the FITS headers give no site.";
            } else {
                site = "Settings: <b>" + degrees(observer.site().latitudeDeg()) + " / " + degrees(observer.site().longitudeDeg())
                        + "</b> · <b>" + String.format(Locale.US, "%.0f m", observer.site().altitudeMeters()) + "</b>";
                source = "The site from Astrometry Config is used; the FITS headers give none and no compatible IAU code is set.";
            }
        }
        solar.wrapAt(UiStyle.space(240));
        solar.setText("<html>" + depth + "<br>" + site + "</html>");
        solar.setToolTipText("<html>What the next Fetch Asteroids and Comets uses.<br>" + source
                + (deeper ? String.format(Locale.US, "<br>The frames reach Gaia G %.1f, so the depth goes beyond the setting (V %.1f).",
                check.limitingMagnitude, setting) : "")
                + "<br>The observing site comes from the FITS headers first, then a compatible IAU code,<br>"
                + "then the default coordinates. North and east are positive.<br>"
                + "Settings: IAU " + value(config.observatoryCode) + " · latitude " + value(config.siteLat) + " · longitude "
                + value(config.siteLong) + " · altitude (m) " + value(config.siteAltitude)
                + "<br>The readout rounds coordinates to two decimals and altitude to metres; fetches use full precision.</html>");
    }

    /** The settings' site in the readout's words. */
    private static String siteText(String code, String latitude, String longitude, Double altitude) {
        return "IAU <b>" + value(code) + "</b>" + (altitude == null ? "" : String.format(Locale.US, " · <b>%.0f m</b>", altitude))
                + "<br>Lat / Lon: <b>" + latitude + " / " + longitude + "</b>";
    }

    /** A coordinate in degrees to two decimals. */
    private static String degrees(double value) {
        return String.format(Locale.US, "%.2f°", value);
    }

    /** Shortens only the display, using the settings editor's coordinate parsing and keeping exact values in its tooltip. */
    private static String coordinate(String text) {
        Double degrees = ConfigurationPanel.parseDegrees(text == null ? "" : text);
        return degrees == null ? "—" : String.format(Locale.US, "%.2f°", degrees);
    }

    /** Uses the detection readout's quiet border and small padding without fixing a text height. */
    private static JPanel readout(JComponent text) {
        JPanel card = new JPanel(new BorderLayout());
        Color border = UIManager.getColor("Component.borderColor");
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border != null ? border : new Color(0x666666)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        card.add(text);
        return card;
    }

    /** Shows unset values explicitly and keeps configured text from becoming HTML markup. */
    private static String value(String text) {
        if (text == null || text.isBlank()) return "Not set";
        return text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Lets the workflow allocate available width while retaining the measured readout height. */
    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
