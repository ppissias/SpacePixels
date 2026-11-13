package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
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

    /** Displays current valid application values, including unsaved site edits; retains no mutable settings. */
    void showSettings(AppConfig config) {
        Double altitude = eu.startales.spacepixels.fits.ObservingSite.parseAltitude(config.siteAltitude);
        String height = altitude == null ? "" : String.format(Locale.US, " · <b>%.0f m</b>", altitude);
        solar.wrapAt(UiStyle.space(altitude == null ? 180 : 220));
        sky.setText(String.format(Locale.US, "<html>Gaia G ≤ <b>%.1f</b></html>", config.skyCatalogueStarMagnitude()));
        solar.setText(String.format(Locale.US, "<html>V ≤ <b>%.1f</b> · IAU <b>%s</b>%s"
                + "<br>Lat / Lon: <b>%s / %s</b></html>",
                config.solarSystemMagnitude(), value(config.observatoryCode), height, coordinate(config.siteLat), coordinate(config.siteLong)));
        solar.setToolTipText("<html>Settings used by the next Fetch Asteroids and Comets.<br>"
                + "Default latitude: " + value(config.siteLat) + "<br>Default longitude: " + value(config.siteLong)
                + "<br>Default altitude (m): " + value(config.siteAltitude)
                + "<br>The observing site comes from the FITS headers first, then a compatible IAU code,<br>"
                + "then these default coordinates. North and east are positive.<br>"
                + "A FITS elevation overrides the altitude default; otherwise an unset altitude means 0 m.<br>"
                + "The readout rounds coordinates to two decimals and altitude to metres; fetches use full precision.</html>");
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
