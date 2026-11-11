/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.Locale;

/**
 * Dialog displaying acknowledgements for community test data contributors, external astronomical
 * services, software, and scientific methods used by SpacePixels and its libraries.
 */
final class AcknowledgementsDialog extends JDialog {

    /** Opens a modeless credits window; called on the EDT. */
    static void show(Component parent) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        AcknowledgementsDialog dialog = new AcknowledgementsDialog(owner);
        dialog.setVisible(true);
    }

    /** Builds the four credits tabs on the EDT without starting external services. */
    AcknowledgementsDialog(Window owner) {
        super(owner, "Credits & Acknowledgements", ModalityType.MODELESS);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        AppIcon.apply(this);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Test Data", createHtmlTab(testDataHtml()));
        tabs.addTab("External Services & APIs", createHtmlTab(externalServicesHtml()));
        tabs.addTab("Software & Utilities", createHtmlTab(softwareUtilitiesHtml()));
        tabs.addTab("Scientific References", createHtmlTab(scientificReferencesHtml()));

        JButton closeButton = new JButton("Close");
        closeButton.addActionListener(e -> dispose());
        getRootPane().setDefaultButton(closeButton);

        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 10));
        bottomRow.add(closeButton);

        JPanel content = new JPanel(new BorderLayout());
        content.add(tabs, BorderLayout.CENTER);
        content.add(bottomRow, BorderLayout.SOUTH);
        setContentPane(content);

        // Escape dismisses the dialog
        getRootPane().registerKeyboardAction(e -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        WindowSizing.install(this, 780, 560);
        Rectangle bounds = WindowSizing.usableBounds(this);
        setSize(Math.min(UiStyle.space(820), bounds.width), Math.min(UiStyle.space(620), bounds.height));
        setLocationRelativeTo(owner);
    }

    /** Wraps read-only, linked HTML in a scroll pane on the EDT. */
    private static JComponent createHtmlTab(String bodyHtml) {
        JEditorPane editor = new JEditorPane();
        editor.setEditable(false);
        editor.setContentType("text/html");
        editor.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        editor.setFont(UiStyle.bodyFont());
        editor.setText(wrapHtml(bodyHtml));
        editor.setCaretPosition(0);
        editor.addHyperlinkListener(e -> {
            if (e.getEventType() == HyperlinkEvent.EventType.ACTIVATED) {
                String url = e.getURL() != null ? e.getURL().toString() : e.getDescription();
                openUrl(url);
            }
        });

        JScrollPane scroll = new JScrollPane(editor);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    /** Applies the current desktop theme to a credits fragment. */
    private static String wrapHtml(String body) {
        String textHex = colorToHex(UIManager.getColor("Label.foreground"));
        String linkHex = colorToHex(UiStyle.linkColor());
        String mutedHex = colorToHex(UiStyle.helpColor());
        return "<html><head><style>"
                + "body { font-family: sans-serif; font-size: 13px; color: " + textHex + "; margin: 12px 16px; }"
                + "h3 { margin-top: 6px; margin-bottom: 6px; font-size: 14px; }"
                + "p { margin-top: 4px; margin-bottom: 8px; line-height: 1.4; }"
                + "ul { margin-top: 4px; margin-bottom: 8px; padding-left: 20px; }"
                + "li { margin-bottom: 6px; line-height: 1.35; }"
                + "a { color: " + linkHex + "; text-decoration: underline; }"
                + ".muted { color: " + mutedHex + "; font-size: 12px; }"
                + "</style></head><body>"
                + body
                + "</body></html>";
    }

    /** Formats a theme colour for Swing HTML, with a neutral fallback. */
    private static String colorToHex(Color c) {
        if (c == null) return "#888888";
        return String.format(Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Opens an activated credits link in the system browser when available. */
    private static void openUrl(String url) {
        if (url == null || url.isBlank() || !Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(new URI(url));
        } catch (Exception ignored) {
            // Browser invocation failure is safely ignored
        }
    }

    /** Credits the observers whose data support development and validation. */
    private static String testDataHtml() {
        return "<h3>Test Datasets & Observers</h3>"
                + "<p>SpacePixels relies on real-world astrophotography datasets to validate transient and moving-target detection, "
                + "calibrate Auto-Tune algorithms, verify variable-star photometry, and refine star masking.</p>"
                + "<p>Many thanks to <b>Klangwolke at Cloudynights</b>, "
                + "<b>Duncan Warren</b>, <b>TheStarsabove at Cloudynights</b>,  "
                + "<a href='https://github.com/chvvkumar'><b>Kumar</b></a> and <b>Austral Kiwi at Cloudynights</b> "
                + "for providing some of the test data used by SpacePixels.</p>"
                + "<p class='muted'>If you have sub-exposure sequences of moving objects (asteroids, comets), optical transients, "
                + "or variable stars that you would like to contribute for testing and algorithm development, please reach out via "
                + "the <a href='https://github.com/ppissias/SpacePixels'>SpacePixels GitHub repository</a> or email me at petrospis@gmail.com.</p>";
    }

    /** Resolves a bundled logo for use by Swing's HTML image renderer. */
    private static String logoUrl(String name) {
        java.net.URL url = AcknowledgementsDialog.class.getResource("/eu/startales/spacepixels/gui/logos/" + name);
        return url != null ? url.toExternalForm() : name;
    }

    /** Pairs a logo, sized in display pixels, with its provider's credits. */
    private static String logoCard(String logoName, int width, int height, String contentHtml) {
        String url = logoUrl(logoName);
        return "<table cellpadding='0' cellspacing='0' border='0' width='100%' style='margin-bottom: 10px;'>"
                + "<tr>"
                + "<td width='84' valign='top'>"
                + "  <table cellpadding='4' cellspacing='0' border='0' bgcolor='#ffffff' style='border: 1px solid #777777;' width='80'>"
                + "    <tr><td align='center' valign='middle' height='46'>"
                + "      <img src='" + url + "' width='" + width + "' height='" + height + "' alt='" + logoName + "'>"
                + "    </td></tr>"
                + "  </table>"
                + "</td>"
                + "<td width='14'>&nbsp;</td>"
                + "<td valign='top'>"
                + contentHtml
                + "</td>"
                + "</tr>"
                + "</table>";
    }

    /** Distinguishes downloaded data and optional services from report links. */
    private static String externalServicesHtml() {
        return "<h3>Astronomical Services & Data Providers</h3>"
                + "<p>These services supply catalogue data, predictions and optional plate solutions for sky annotations, "
                + "diagnostics and identification checks in SpacePixels.</p>"
                + logoCard("nasa.png", 48, 40,
                        "<b>NASA JPL (Jet Propulsion Laboratory)</b>:"
                        + "<ul>"
                        + "  <li><a href='https://ssd-api.jpl.nasa.gov/doc/sb_ident.html'>SB-Ident API</a>: Searches for known asteroids and comets in the field and around report findings.</li>"
                        + "  <li><a href='https://ssd.jpl.nasa.gov/horizons/'>Horizons System</a>: Supplies observer ephemerides for known-object paths and exposure-midpoint annotations, including positional uncertainties when available. <a href='https://ssd.jpl.nasa.gov/about/'>Data citation guidance</a>.</li>"
                        + "</ul>")
                + logoCard("esa.png", 72, 28,
                        "<b>European Space Agency (ESA)</b>:"
                        + "<ul>"
                        + "  <li><a href='https://www.cosmos.esa.int/web/gaia'>Gaia DR3</a>: Star positions, proper motions, <i>G</i> magnitudes and <i>BP&minus;RP</i> colours support sky overlays, plate-solution correction, field-depth and observing-condition diagnostics. Queried through VizieR TAP, then the ESA Gaia Archive, then <a href='https://gaia.ari.uni-heidelberg.de/'>ARI Heidelberg</a>, for the main star catalogue and for the faint field-depth patches.</li>"
                        + "</ul>"
                        + "<p class='muted'>Credit: ESA/Gaia/DPAC. Gaia data are processed by the "
                        + "<a href='https://www.cosmos.esa.int/web/gaia/dpac/consortium'>Data Processing and Analysis Consortium</a>; "
                        + "national institutions support DPAC through the Gaia Multilateral Agreement. Requested references: <a href='https://doi.org/10.1051/0004-6361/201629272'>Gaia Collaboration (2016), mission paper</a>, and <a href='https://doi.org/10.1051/0004-6361/202243940'>Gaia Collaboration (2023), DR3 release paper</a>. "
                        + "<a href='https://gea.esac.esa.int/archive/documentation/GDR3/Miscellaneous/sec_credit_and_citation_instructions/'>Gaia citation guidance</a>.</p>")
                + logoCard("cds.png", 67, 40,
                        "<b>CDS (Centre de Donn&eacute;es astronomiques de Strasbourg)</b>:"
                        + "<ul>"
                        + "  <li><a href='https://simbad.cds.unistra.fr/simbad/'>SIMBAD</a>: Supplies deep-sky objects and named bright stars for sky annotations, from Strasbourg, then the <a href='https://simbad.cfa.harvard.edu/simbad/'>Harvard</a> and <a href='https://simbad.idia.ac.za/'>IDIA</a> mirrors when Strasbourg does not answer. Reports also link to object searches. <a href='https://doi.org/10.1051/aas:2000332'>Wenger et al. (2000)</a>.</li>"
                        + "  <li><a href='https://vizier.cds.unistra.fr/'>VizieR</a>: TAP access to Gaia DR3 (<i>I/355/gaiadr3</i>) and AAVSO VSX (<i>B/vsx/vsx</i>), including faint Gaia samples for field-depth estimates. <a href='https://doi.org/10.1051/aas:2000169'>Ochsenbein, Bauer &amp; Marcout (2000)</a>; <a href='https://doi.org/10.26093/cds/vizier'>service DOI</a>.</li>"
                        + "</ul>")
                + logoCard("astrometry.png", 72, 22,
                        "<b>Astrometry.net</b>:"
                        + "<ul>"
                        + "  <li><a href='https://nova.astrometry.net/'>Nova Astrometry.net</a>: Optional online blind plate solving through JPlateSolve, using the user's configured account. <a href='https://doi.org/10.1088/0004-6256/139/5/1782'>Lang et al. (2010)</a>.</li>"
                        + "</ul>")
                + "<ul>"
                + "  <li><b>AAVSO (American Association of Variable Star Observers)</b> &mdash; <a href='https://vsx.aavso.org/'>International Variable Star Index (VSX)</a>: Variable-star positions, classifications, periods and brightness ranges, fetched for annotations from VizieR at CDS, then the <a href='https://vizier.cfa.harvard.edu/'>Harvard</a> and <a href='https://vizier.idia.ac.za/'>IDIA</a> copies when Strasbourg does not answer. Report cross-checks still ask the Strasbourg service. Details open on AAVSO's site. <a href='https://cdsarc.cds.unistra.fr/viz-bin/cat/B/vsx'>Watson et al. (2006&ndash;), catalogue B/vsx</a>.</li>"
                + "  <li><b>IAU CPS (Centre for the Protection of the Dark and Quiet Sky from Satellite Constellation Interference)</b> &mdash; <a href='https://satchecker.cps.iau.org/'>SatChecker</a>: On-demand satellite-pass and ephemeris queries for report lookups and measured streak speed/direction comparisons. <a href='https://satchecker.readthedocs.io/en/latest/'>API documentation</a>.</li>"
                + "</ul>"
                + "<h3>External Report Links</h3>"
                + "<p>These open additional checks or information in a browser:</p><ul>"
                + "  <li><b>IMCCE / Observatoire de Paris</b> &mdash; <a href='https://ssp.imcce.fr/webservices/skybot/'>SkyBoT</a>: Cone searches at a finding's sky position and observation epoch. Reference: Berthier et al. (2006), <i>ADASS XV</i>, ASP Conf. Ser. 351, 367.</li>"
                + "  <li><b>NASA JPL</b> &mdash; <a href='https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html'>Small-Body Database (SBDB)</a>: Object details for asteroid and comet lookup results.</li>"
                + "  <li><a href='https://celestrak.org/'>CelesTrak</a> &amp; <a href='https://www.n2yo.com/'>N2YO</a>: Catalogue and satellite-information links for returned NORAD identities.</li>"
                + "  <li><a href='https://stellarium-web.org/'>Stellarium Web</a>: Sky views centred on report findings. Local Stellarium control is described in Software &amp; Utilities.</li>"
                + "</ul>";
    }

    /** Credits optional executables, runtime libraries and development tools according to their roles. */
    private static String softwareUtilitiesHtml() {
        return "<h3>Astronomical Software & Utilities</h3>"
                + "<p>These optional tools are installed separately and used when configured:</p>"
                + logoCard("astap.png", 40, 40,
                        "<b>ASTAP</b> by Han Kleijn &mdash; The Astrometric STAcking Program supplies SpacePixels' local plate-solving backend through JPlateSolve (<a href='https://www.hnsky.org/astap.htm'>ASTAP</a>); its local star database is required for solving.")
                + logoCard("siril.png", 40, 40,
                        "<b>Siril</b> by the Siril Development Team &mdash; The command-line backend for observing-session preparation: calibration, demosaicing, registration and optional reference stacking, according to the selected recipe (<a href='https://siril.org/'>Siril</a>).")
                + logoCard("stellarium.png", 40, 40,
                        "<b>Stellarium</b> by the Stellarium Team &mdash; Report buttons send sky position, time, observer location and field of view to the local planetarium's Remote Control plugin (<a href='https://stellarium.org/'>Stellarium</a>).")
                + "<hr style='border: 0; border-top: 1px solid #666666; margin: 14px 0 10px 0;'>"
                + "<h3>Open Source Software & Libraries</h3>"
                + "<p>SpacePixels is built with the following open-source Java libraries and components:</p>"
                + "<ul>"
                + "  <li><b>FlatLaf</b> by FormDev Software &mdash; Desktop Look and Feel, themes and interface scaling (<a href='https://www.formdev.com/flatlaf/'>FlatLaf</a>).</li>"
                + "  <li><b>nom.tam.fits</b> &mdash; Java library for Flexible Image Transport System (FITS) I/O (<a href='https://nom-tam-fits.github.io/nom-tam-fits/'>nom-tam-fits</a>).</li>"
                + "  <li><b>Gson</b> by Google &mdash; Java library for JSON serialization and parsing (<a href='https://github.com/google/gson'>github.com/google/gson</a>).</li>"
                + "  <li><b>JTransient &amp; JPlateSolve</b> by Petros Pissias &mdash; Detection, tracking and photometry engine; ASTAP and Astrometry.net plate-solving integration (<a href='https://github.com/ppissias/JTransient'>JTransient</a>, <a href='https://github.com/ppissias/JPlateSolve'>JPlateSolve</a>).</li>"
                + "  <li><b>Java / OpenJDK</b> &mdash; Java 21 runtime and Swing desktop; Windows packages bundle the runtime (<a href='https://openjdk.org/'>OpenJDK</a>).</li>"
                + "</ul>"
                + "<h3>Development &amp; Packaging</h3>"
                + "<p><a href='https://gradle.org/'>Gradle</a> builds the application; "
                + "<a href='https://junit.org/junit4/'>JUnit</a> and "
                + "<a href='https://codeberg.org/miurahr/assertj-swing'>AssertJ Swing (Hiroshi Miura's fork)</a> support tests. "
                + "JDK jpackage/jlink and <a href='https://wixtoolset.org/'>WiX Toolset</a> create the Windows installer.</p>"
                + "<hr style='border: 0; border-top: 1px solid #666666; margin: 14px 0 10px 0;'>"
                + "<h3>AI Coding Assistants</h3>"
                + "<p>Since 2026, the development of SpacePixels and its libraries has been supported by these AI coding assistants:</p>"
                + logoCard("claude.png", 40, 40,
                        "<b>Claude</b> by Anthropic (<a href='https://www.anthropic.com/claude'>Claude</a>).")
                + logoCard("openai.png", 40, 40,
                        "<b>Codex</b> by OpenAI (<a href='https://openai.com/codex/'>Codex</a>).")
                + logoCard("gemini.png", 40, 40,
                        "<b>Gemini</b> by Google (<a href='https://gemini.google.com/'>Gemini</a>).")
                + logoCard("grok.png", 72, 20,
                        "<b>Grok</b> by xAI (<a href='https://x.ai/grok'>Grok</a>).")
                + "<p class='muted'>Logos are trademarks of their respective owners.</p>";
    }

    /** Maps verified publications and data references to the features that actually use them. */
    private static String scientificReferencesHtml() {
        return "<h3>Scientific References &amp; Algorithms</h3>"
                + "<p>References for methods and standards implemented in SpacePixels and its libraries. Service and catalogue credits are on the External Services &amp; APIs tab. "
                + "Each entry identifies its contribution; related literature is omitted unless it supplies an additional method used here. "
                + "Implementation choices and empirical selection thresholds are described in the "
                + "<a href='https://github.com/ppissias/JTransient/blob/main/ALGORITHM.md'>JTransient algorithm documentation</a>.</p>"
                + "<h3>Digital Tracking &amp; Faint-Mover Detection</h3><ul>"
                + "  <li><b>Heinze, Metchev &amp; Trollo (2015)</b> &mdash; <i>Digital Tracking Observations Can Discover Asteroids 10 Times Fainter than Conventional Searches</i>, "
                + "<a href='https://doi.org/10.1088/0004-6256/150/4/125'>AJ 150, 125</a> "
                + "(<a href='https://arxiv.org/abs/1508.01599'>author preprint</a>).<br>"
                + "<span class='muted'>Method background for FaintMoverSearch: constant-velocity shift-and-stack detection, motion-grid spacing tied to image quality, and checking support in separate time subsets.</span></li>"
                + "</ul><p class='muted'>The engine's residual preparation, empirical scores and selection rules are described in its algorithm documentation. "
                + "Published survey sensitivity is not a SpacePixels performance claim. The "
                + "<a href='https://github.com/ppissias/JTransient/blob/main/docs/research/slow-movers/README.md'>investigation archive</a> "
                + "keeps experimental alternatives separate from delivered methods.</p>"
                + "<h3>Variable-Star Photometry</h3>"
                + "<ul>"
                + "  <li><b>Honeycutt (1992)</b> &mdash; <i>CCD Ensemble Photometry on an Inhomogeneous Set of Exposures</i>, "
                + "<a href='https://doi.org/10.1086/133015'>PASP 104, 435&ndash;440</a>.<br>"
                + "<span class='muted'>JTransient's EnsembleSolver uses the star-magnitude plus frame-zero-point model for differential light curves, adding robust Cauchy reweighting and spatial transparency planes.</span></li>"
                + "  <li><b>Stetson (1996)</b> &mdash; <i>On the Automatic Determination of Light-Curve Parameters for Cepheid Variables</i>, "
                + "<a href='https://doi.org/10.1086/133808'>PASP 108, 851&ndash;876</a>.<br>"
                + "<span class='muted'>JTransient's VariabilityScorer adapts the J statistic to consecutive usable residuals with a magnitude-neighbour noise estimate, alongside excess scatter, when selecting variable-star candidates.</span></li>"
                + "  <li><b>Howell (1989)</b> &mdash; <i>Two-dimensional aperture photometry: Signal-to-noise ratio of point-source observations and optimal data-extraction techniques</i>, "
                + "<a href='https://doi.org/10.1086/132477'>PASP 101, 616&ndash;622</a>.<br>"
                + "<span class='muted'>The sky-background and finite-annulus terms inform ApertureMeasurer's flux errors; JTransient also measures an empirical blank-sky correction. These are background-noise estimates, rather than the complete source-photon/read-noise CCD equation.</span></li>"
                + "</ul>"
                + "<h3>Astrometry &amp; World Coordinate Systems (WCS)</h3>"
                + "<ul>"
                + "  <li><b>Greisen &amp; Calabretta (2002)</b> &mdash; <i>Representations of world coordinates in FITS</i>, "
                + "<a href='https://doi.org/10.1051/0004-6361:20021326'>A&amp;A 395, 1061&ndash;1075</a>.<br>"
                + "<span class='muted'>WcsCoordinateTransformer reads reference pixels, coordinate values and CD or PC/CDELT matrices to map image pixels to intermediate coordinates.</span></li>"
                + "  <li><b>Calabretta &amp; Greisen (2002)</b> &mdash; <i>Representations of celestial coordinates in FITS</i>, "
                + "<a href='https://doi.org/10.1051/0004-6361:20021327'>A&amp;A 395, 1077&ndash;1122</a>.<br>"
                + "<span class='muted'>WcsCoordinateTransformer uses the TAN (gnomonic) celestial projection for sky annotations, catalogue matching and report coordinates.</span></li>"
                + "  <li><b>Shupe et al. (2005)</b> &mdash; <i>The SIP Convention for Representing Distortion in FITS Image Headers</i>, "
                + "<a href='https://authors.library.caltech.edu/records/fqnyw-qdw79'>ASP Conf. Ser. 347, 491&ndash;495</a>.<br>"
                + "<span class='muted'>WcsCoordinateTransformer applies supplied SIP polynomial coefficients, with inverse iteration, in pixel/sky transformations for annotations and report coordinates.</span></li>"
                + "  <li><b>Meeus (1998)</b> &mdash; <a href='https://shopatsky.com/products/astronomical-algorithms-2nd-edition'><i>Astronomical Algorithms</i>, 2nd ed.</a>, Willmann-Bell.<br>"
                + "<span class='muted'>The report's local sidereal-time polynomial and equatorial-to-horizontal conversion follow the conventional formulae described in this reference; displayed Alt/Az uses UTC as an approximation to UT1.</span></li>"
                + "</ul>";
    }
}
