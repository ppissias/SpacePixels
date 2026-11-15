package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import eu.startales.spacepixels.solarsystem.SolarSystemObjects;
import eu.startales.spacepixels.solarsystem.SolarSystemQuery;
import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Compact read-only fetch settings beside their commands; all construction and updates belong to the EDT. */
final class FetchSettingsPanel extends JPanel {
    private final WrappingHelpLabel sky = new WrappingHelpLabel("", 180);
    private final WrappingHelpLabel solar = new WrappingHelpLabel("", 180);
    /** What the session holds from earlier fetches, so the observer can judge whether to fetch again. */
    private final WrappingHelpLabel saved = new WrappingHelpLabel("", 400);

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
        saved.getAccessibleContext().setAccessibleName("Fetched data saved with this session");
        saved.setFont(UiStyle.bodyFont());
        saved.setForeground(UiStyle.helpColor());
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        add(saved, c);
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
        showSettings(config, files, catalogue, null);
    }

    /**
     * As {@link #showSettings(AppConfig, FitsFileInformation[], SkyCatalogue)}, and a line below with what the session
     * holds from earlier fetches: the star depth, the asteroid depth and site, and when each was fetched (UTC), marked
     * where the next fetch would differ. The line is hidden without frames.
     *
     * @param objects the session's predicted asteroids and comets, or null
     */
    void showSettings(AppConfig config, FitsFileInformation[] files, SkyCatalogue catalogue, SolarSystemObjects objects) {
        double setting = config.solarSystemMagnitude();
        var check = catalogue == null ? null : catalogue.photometryCheck;
        double frames = check == null ? Double.NaN : check.solarSystemDepth(AppConfig.MAX_SOLAR_SYSTEM_MAGNITUDE);
        boolean deeper = frames > setting;
        String depth = deeper ? String.format(Locale.US, "V ≤ <b>%.1f</b> · frames' depth", frames)
                : String.format(Locale.US, "V ≤ <b>%.1f</b>", setting);
        sky.setText(String.format(Locale.US, "<html>Gaia G ≤ <b>%.1f</b></html>", config.skyCatalogueStarMagnitude()));
        String site;
        String source;
        SolarSystemQuery.Observer next = null;
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
            next = observer;
            if (observer == null) {
                site = "Site: <b>none</b>, set one in Astrometry Config";
                source = "Neither the FITS headers nor the settings give an observing site.";
            } else if (observer.site() != null && ObservingSite.FROM_HEADER.equals(observer.site().source())) {
                site = "FITS header: <b>" + degrees(observer.site().latitudeDeg()) + " / " + degrees(observer.site().longitudeDeg())
                        + "</b> · <b>" + String.format(Locale.US, "%.0f m", observer.site().altitudeMeters()) + "</b>";
                source = "The FITS headers give the observing site, so it is used rather than the settings.";
            } else if (observer.site() == null) {
                site = "IAU <b>" + value(observer.observatoryCode()) + "</b>" + named(config);
                source = "The IAU observatory code from Astrometry Config is used; the FITS headers give no site.";
            } else {
                site = (config.observingSite == null || config.observingSite.isBlank() ? "Settings"
                        : "Site " + value(config.observingSite)) + ": <b>" + degrees(observer.site().latitudeDeg()) + " / " + degrees(observer.site().longitudeDeg())
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
        showSaved(files != null && files.length > 0, catalogue, objects, config.skyCatalogueStarMagnitude(), deeper ? frames : setting, next);
    }

    /** The settings' site name after an IAU code, as " (Home)"; nothing when the site has no name. */
    private static String named(AppConfig config) {
        return config.observingSite == null || config.observingSite.isBlank() ? "" : " (" + value(config.observingSite) + ")";
    }

    /** The line on the session's fetched data; see {@link #showSettings(AppConfig, FitsFileInformation[], SkyCatalogue, SolarSystemObjects)}. */
    private void showSaved(boolean session, SkyCatalogue catalogue, SolarSystemObjects objects, double nextStars, double nextDepth,
                           SolarSystemQuery.Observer next) {
        saved.setVisible(session);
        if (!session) return;
        var parts = new java.util.ArrayList<String>();
        var tip = new StringBuilder("<html>What this session holds from earlier fetches, kept with its frames.");
        if (catalogue != null && catalogue.fetchedAtUtc != null) {
            boolean shallower = nextStars > catalogue.starMagnitudeLimit + 0.01;
            parts.add(String.format(Locale.US, "stars&nbsp;G&nbsp;≤&nbsp;<b>%.1f</b> (%s)", catalogue.starMagnitudeLimit, when(catalogue.fetchedAtUtc))
                    + (shallower ? mark("next fetch deeper") : ""));
            tip.append(String.format(Locale.US, "<br>Sky catalogue: Gaia G ≤ %.1f, fetched %s.", catalogue.starMagnitudeLimit, fullTime(catalogue.fetchedAtUtc)));
            if (shallower) tip.append(String.format(Locale.US, " The next fetch goes to G %.1f.", nextStars));
        } else {
            parts.add("no sky catalogue");
        }
        if (objects != null && objects.fetchedAtUtc != null) {
            boolean shallower = nextDepth > objects.magnitudeLimit + 0.01;
            String where = shortObserver(objects.observer);
            boolean otherSite = next != null && objects.observer != null && !sameSite(objects.observer, next.description());
            String marks = (shallower ? "next fetch deeper" : "") + (shallower && otherSite ? ", " : "") + (otherSite ? "other site now" : "");
            parts.add(String.format(Locale.US, "asteroids&nbsp;V&nbsp;≤&nbsp;<b>%.1f</b>", objects.magnitudeLimit) + (where.isEmpty() ? "" : ", " + where)
                    + " (" + when(objects.fetchedAtUtc) + ")" + (marks.isEmpty() ? "" : mark(marks)));
            tip.append(String.format(Locale.US, "<br>Asteroids and comets: V ≤ %.1f", objects.magnitudeLimit))
                    .append(where.isEmpty() ? "" : ", for " + (where.startsWith("FITS") ? "the " : "") + where)
                    .append(", fetched ").append(fullTime(objects.fetchedAtUtc)).append('.');
            if (shallower) tip.append(String.format(Locale.US, " The next fetch goes to V %.1f.", nextDepth));
            if (otherSite) tip.append("<br>The next fetch uses the ").append(value(next.description())).append('.');
        } else {
            parts.add("no asteroids");
        }
        saved.setText("<html>Saved: " + String.join(" · ", parts) + "</html>");
        saved.setToolTipText(tip.append("<br>Fetch again to replace them with the settings above.</html>").toString());
    }

    /** A short note in the warning colour. */
    private static String mark(String text) {
        Color color = UIManager.getColor("Actions.Yellow");
        String hex = String.format("#%06x", (color != null ? color : new Color(0xE0A030)).getRGB() & 0xFFFFFF);
        return " <font color='" + hex + "'>· " + text + "</font>";
    }

    /** "14 Nov 21:03 UTC", with the year when it is not this year. */
    static String when(String isoInstant) {
        try {
            var time = java.time.Instant.parse(isoInstant).atZone(java.time.ZoneOffset.UTC);
            boolean thisYear = time.getYear() == java.time.Year.now(java.time.ZoneOffset.UTC).getValue();
            return java.time.format.DateTimeFormatter.ofPattern(thisYear ? "d MMM HH:mm 'UTC'" : "d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH)
                    .format(time).replace(" ", "&nbsp;");
        } catch (RuntimeException e) {
            return value(isoInstant);
        }
    }

    private static String fullTime(String isoInstant) {
        try {
            return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
                    .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.parse(isoInstant));
        } catch (RuntimeException e) {
            return value(isoInstant);
        }
    }

    /** The saved observer in a few words: "FITS-header site", "IAU J95 (Home)" or "site Home". */
    static String shortObserver(String description) {
        if (description == null || description.isBlank()) return "";
        String text = normalised(description);
        if (text.startsWith("site from the FITS header")) return "FITS-header site";
        if (text.startsWith("IAU code ")) {
            String rest = text.substring("IAU code ".length());
            int comma = rest.indexOf(", site ");
            return "IAU " + value(comma < 0 ? rest : rest.substring(0, comma))
                    + (comma < 0 ? "" : " (" + value(rest.substring(comma + ", site ".length())) + ")");
        }
        if (text.endsWith(" from the Astrometry Config tab")) {
            String name = text.substring(0, text.length() - " from the Astrometry Config tab".length()).trim();
            return name.equals("site") ? "settings' site" : value(name);
        }
        return value(text);
    }

    /**
     * Whether the saved fetch and the next one use the same site, as far as the descriptions tell: both the FITS
     * header's, the same IAU code, or the settings' coordinates (by name when both have one).
     */
    static boolean sameSite(String saved, String next) {
        String a = normalised(saved), b = normalised(next);
        if (a.startsWith("site from the FITS header") || b.startsWith("site from the FITS header")) {
            return a.startsWith("site from the FITS header") && b.startsWith("site from the FITS header");
        }
        if (a.startsWith("IAU code ") || b.startsWith("IAU code ")) {
            return a.startsWith("IAU code ") && b.startsWith("IAU code ") && code(a).equalsIgnoreCase(code(b));
        }
        return a.equals("site from the Astrometry Config tab") || b.equals("site from the Astrometry Config tab") || a.equals(b);
    }

    /** A bare code, as files from earlier versions hold it, read as "IAU code W84". */
    private static String normalised(String description) {
        String text = description.trim();
        return text.matches("(?i)[A-Z0-9]{3}") ? "IAU code " + text.toUpperCase(Locale.ROOT) : text;
    }

    private static String code(String description) {
        String rest = description.substring("IAU code ".length());
        int comma = rest.indexOf(',');
        return (comma < 0 ? rest : rest.substring(0, comma)).trim();
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
