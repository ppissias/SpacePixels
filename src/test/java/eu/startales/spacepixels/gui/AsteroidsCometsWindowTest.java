package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.config.VisualizationSettings;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.ObservingSite;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.mpc.ReportedObjects;
import eu.startales.spacepixels.platesolve.PlateSolutionCorrection;
import eu.startales.spacepixels.session.ApplicationStorage;
import nom.tam.fits.Fits;
import nom.tam.fits.HeaderCard;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * The Asteroids &amp; Comets window on a small synthetic session and report: its objects and statuses, observation
 * ticks that keep the table, the ADES export and its missing details, the e-mail detail, and a light curve with bins.
 * The window opens off screen and is driven on the event thread; the mouse is not moved. Skipped without a display.
 */
public class AsteroidsCometsWindowTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test(timeout = 120_000)
    public void reviewsExportsAndMeasuresObjects() throws Exception {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        Path session = session(folder.newFolder("session").toPath());
        FitsFileInformation[] files = files(session);
        WcsCoordinateTransformer wcs = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        File reportFolder = report(folder.newFolder("report"), files);
        var report = ReportedObjects.load(reportFolder);
        var config = new AppConfig();
        config.observatoryCode = "W84";
        config.mpcSubmitter = "A. Tester";
        config.mpcObservers = "A. Observer";
        config.mpcMeasurers = "A. Tester";
        config.mpcTelescopeDesign = "Reflector";
        config.mpcAperture = "0.2";
        config.mpcDetector = "CMOS";
        var settings = ApplicationSettings.inMemory(config);
        var window = onEdt(() -> {
            var w = new AsteroidsCometsWindow(null, files, report, wcs, correction(wcs), settings, new ApplicationStorage(),
                    VisualizationSettings.defaults(), new ObservingSite(-30, -70, 2000, ObservingSite.FROM_CONFIGURATION), null,
                    () -> predictions(wcs, files[0].getObservationTimestamp()));
            w.setFocusableWindowState(false);
            w.setSize(1500, 950);
            w.setLocation(-4000, -4000);
            w.setVisible(true);
            return w;
        });
        try {
            JTable objects = onEdt(() -> tables(window).getFirst());
            assertEquals(2, (int) onEdt(objects::getRowCount));
            assertEquals("T1", onEdt(() -> objects.getValueAt(0, 1)));
            assertEquals("FM1", onEdt(() -> objects.getValueAt(1, 1)));
            assertEquals("Known", onEdt(() -> objects.getValueAt(0, 2)));
            assertEquals("Check", onEdt(() -> objects.getValueAt(1, 2)));
            // External JPL SBDB details link is shown for the identified object T1 and hidden for unmatched FM1.
            assertTrue(onEdt(() -> button(window, "JPL details: 649341 (2011 DK30) ↗").isVisible()));
            assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=649341",
                    onEdt(() -> button(window, "JPL details: 649341 (2011 DK30) ↗").getToolTipText()));
            // Identification details appear in a structured key/value table (Field / Details), and the right pane opens at the top ("Before you report").
            JTable idTable = onEdt(() -> tables(window).get(2));
            assertEquals("Field", onEdt(() -> idTable.getColumnName(0)));
            assertEquals("Details", onEdt(() -> idTable.getColumnName(1)));
            assertTrue("Identification table shows T1", onEdt(() -> tableContains(idTable, "T1 · Track")));
            assertTrue("Identification table shows known name", onEdt(() -> tableContains(idTable, "649341 (2011 DK30)")));
            JScrollPane sideScroll = onEdt(() -> {
                JLabel beforeLabel = labelContaining(window, "Before you report");
                return (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, beforeLabel);
            });
            waitFor("right scroll pane starts at top showing Before you report",
                    () -> onEdt(() -> sideScroll.getVerticalScrollBar().getValue() == 0
                            && sideScroll.getViewport().getViewPosition().y == 0));
            // All three main panes have modest minimums so the horizontal splitters can be resized freely in either direction.
            List<JSplitPane> splits = onEdt(() -> all(window.getContentPane(), JSplitPane.class, new ArrayList<>()));
            assertEquals(3, splits.size());
            onEdt(() -> {
                JSplitPane mainSplit = splits.getFirst();
                mainSplit.setDividerLocation(900);
                mainSplit.validate();
                assertTrue("Main split moves to 900", Math.abs(mainSplit.getDividerLocation() - 900) <= 5);
                mainSplit.setDividerLocation(1100);
                mainSplit.validate();
                assertTrue("Main split moves to 1100", Math.abs(mainSplit.getDividerLocation() - 1100) <= 5);
                return null;
            });
            // Applying MPC ITF results populates both the Identification summary table and the structured matches table.
            onEdt(() -> {
                var trk = new eu.startales.spacepixels.mpc.MpcItfCatalog.Tracklet("W84", "Iuja1KU", 4, 0L, 3_600_000L, 1_800_000L,
                        56.0, 24.0, 24.8, 262.0, "23.2 V");
                var same = new eu.startales.spacepixels.mpc.MpcItfCatalog.Match(trk, true, 0.0, 0.2, 0.1, 0.0);
                var fm = new eu.startales.spacepixels.mpc.MpcItfCatalog.FindingMatches("T1", List.of(same), List.of());
                window.applyItfResult(new eu.startales.spacepixels.mpc.MpcItfCatalog.SearchResult(
                        java.time.Instant.parse("2026-11-26T00:00:00Z"), 100L, 1, Map.of("T1", fm)));
                return null;
            });
            JTable matchTable = onEdt(() -> tables(window).get(3));
            assertTrue("ITF match table becomes visible", onEdt(matchTable::isShowing));
            assertTrue("ITF match table lists W84 Iuja1KU", onEdt(() -> tableContains(matchTable, "W84 Iuja1KU")));
            assertTrue("Nearby & MPC ITF info icon becomes visible beside the matches header",
                    onEdt(() -> all(window.getContentPane(), AbstractButton.class, new ArrayList<>()).stream()
                            .anyMatch(b -> "ⓘ".equals(b.getText())
                                    && "How Nearby and MPC ITF Offset and Motion are calculated".equals(b.getAccessibleContext().getAccessibleName())
                                    && b.isShowing())));
            // Local per-frame Asinh stretch controls and loading progress bar are present.
            assertTrue(onEdt(() -> all(window.getContentPane(), JProgressBar.class, new ArrayList<>()).size() >= 1));
            assertEquals(eu.startales.spacepixels.fits.StretchAlgorithm.ASINH,
                    onEdt(() -> combo(window, eu.startales.spacepixels.fits.StretchAlgorithm.ASINH).getSelectedItem()));
            // Wait for T1 frames to load, then check FM1 faint-mover review assets (residual + shift stack).
            waitFor("T1 frames loaded", () -> onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("T1 · frame 1"))));
            // Find_Orb shows its program path ("Not set" initially), executable hint, and updates orbit status right in its section.
            assertTrue(onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("Program: Not set"))));
            assertTrue(onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("fo64.exe") && l.getText().contains("fo32.exe"))));
            // Selecting faint mover FM1 automatically loads review assets and measures both sub-stacks and its light curve.
            onEdt(() -> { objects.setRowSelectionInterval(1, 1); return null; });
            waitFor("FM1 frames and faint-mover assets loaded", () -> onEdt(() -> button(window, "Residual & stack").isVisible()));
            assertTrue(onEdt(() -> button(window, "Residual & stack").isSelected()));
            waitFor("FM1 sub-stacks measured automatically", () -> onEdt(() -> tableContains(idTable, "#1 ·")));
            waitFor("FM1 light curve measured automatically", () -> onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("FM1 · 5 frames ·"))));
            // Clearing FM1's selected observations and clicking Compute orbit reports the minimum-observation message.
            onEdt(() -> { button(window, "None").doClick(); button(window, "Compute orbit").doClick(); return null; });
            assertTrue(onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("needs at least three selected observations of FM1"))));
            onEdt(() -> { button(window, "All").doClick(); objects.setRowSelectionInterval(0, 0); return null; });
            assertFalse("T1 track hides faint-mover Residual & stack toggle", onEdt(() -> button(window, "Residual & stack").isVisible()));

            // Unticking an observation keeps the table and the selected object (once it emptied the table).
            JTable observationsTable = onEdt(() -> tables(window).get(1));
            waitFor("T1's observations", () -> onEdt(observationsTable::getRowCount) == 5);
            onEdt(() -> { observationsTable.getModel().setValueAt(Boolean.FALSE, 0, 0); return null; });
            assertEquals(5, (int) onEdt(observationsTable::getRowCount));
            assertEquals(0, (int) onEdt(objects::getSelectedRow));

            // The export shows the known object by its number; a missing detail is named instead of a file.
            JTextArea psv = onEdt(() -> areas(window).stream().filter(a -> !a.isEditable() && a.getFont().getFamily().equals(Font.MONOSPACED))
                    .findFirst().orElseThrow());
            waitFor("the ADES file", () -> onEdt(psv::getText).contains("649341 |"));
            assertTrue(onEdt(psv::getText).contains("! mpcCode W84"));
            assertTrue(onEdt(() -> button(window, "Check schema").isEnabled()));
            assertTrue(onEdt(() -> button(window, "Test with the MPC…").isEnabled()));
            JTextField submitter = onEdt(() -> fieldWithText(window, "A. Tester"));
            onEdt(() -> { submitter.setText(""); return null; });
            waitFor("the missing submitter", () -> onEdt(psv::getText).isEmpty());
            assertFalse(onEdt(() -> button(window, "Test with the MPC…").isEnabled()));
            onEdt(() -> { submitter.setText("A. Tester"); return null; });

            // The e-mail detail is kept with the other details, never in the file.
            JTextField email = onEdt(() -> emailField(window));
            onEdt(() -> { email.setText("someone@example.org"); button(window, "Save details").doClick(); return null; });
            waitFor("the saved e-mail", () -> "someone@example.org".equals(settings.getConfig().mpcEmail));
            assertFalse(onEdt(psv::getText).contains("someone@example.org"));

            // Compare again with predictions that now include FM1's object: it becomes Known in the window.
            onEdt(() -> { button(window, "Compare again").doClick(); return null; });
            assertEquals("Known", onEdt(() -> objects.getValueAt(1, 2)));
            assertEquals("2026 AB1", onEdt(() -> objects.getValueAt(1, 3)));
            onEdt(() -> { objects.setRowSelectionInterval(1, 1); return null; });
            assertTrue(onEdt(() -> button(window, "JPL details: 2026 AB1 ↗").isVisible()));
            assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=2026%20AB1",
                    onEdt(() -> button(window, "JPL details: 2026 AB1 ↗").getToolTipText()));
            String compared = onEdt(() -> labels(window).stream().map(JLabel::getText).filter(t -> t.contains("Compared with")).findFirst().orElse("none"));
            assertTrue(compared, compared.contains("FM1 Check → Known 2026 AB1") && compared.contains("Saved."));
            assertEquals("T1 keeps its identification", "Known", onEdt(() -> objects.getValueAt(0, 2)));
            assertTrue("saved beside the report", new File(reportFolder, eu.startales.spacepixels.mpc.LaterIdentifications.FILE_NAME).isFile());
            onEdt(() -> { objects.setRowSelectionInterval(0, 0); return null; });

            // T1's light curve is also measured automatically when selected, then switched to bins of 2.
            waitFor("the light curve", () -> onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("T1 · 5 frames ·"))));
            JLabel curveLabel = onEdt(() -> labelContaining(window, "T1 · 5 frames ·"));
            assertTrue(onEdt(curveLabel::getText), onEdt(curveLabel::getText).contains("uncorrected"));
            onEdt(() -> { combo(window, "Bins of 2").setSelectedItem("Bins of 2"); return null; });
            assertTrue(onEdt(curveLabel::getText), onEdt(curveLabel::getText).contains("2 bins of 2 frames"));
        } finally {
            onEdt(() -> { window.dispose(); return null; });
        }

        // Opened again, the window shows the identification made after the report.
        var reopened = onEdt(() -> {
            AsteroidsCometsWindow w;
            try {
                w = new AsteroidsCometsWindow(null, files, ReportedObjects.load(reportFolder), wcs, correction(wcs), settings, new ApplicationStorage(),
                        VisualizationSettings.defaults(), new ObservingSite(-30, -70, 2000, ObservingSite.FROM_CONFIGURATION), null, () -> null);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            w.setFocusableWindowState(false);
            w.setSize(1500, 950);
            w.setLocation(-4000, -4000);
            w.setVisible(true);
            return w;
        });
        try {
            JTable objects = onEdt(() -> tables(reopened).getFirst());
            assertEquals("Known", onEdt(() -> objects.getValueAt(1, 2)));
            assertEquals("2026 AB1", onEdt(() -> objects.getValueAt(1, 3)));
            onEdt(() -> { objects.setRowSelectionInterval(1, 1); return null; });
            assertTrue(onEdt(() -> button(reopened, "JPL details: 2026 AB1 ↗").isVisible()));
            assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=2026%20AB1",
                    onEdt(() -> button(reopened, "JPL details: 2026 AB1 ↗").getToolTipText()));
            assertTrue(onEdt(() -> labels(reopened).stream().anyMatch(l -> l.getText().contains("made after the report"))));
        } finally {
            onEdt(() -> { reopened.dispose(); return null; });
        }
    }

    @Test(timeout = 120_000)
    public void identifyAllPromotesConfirmedMatchesToKnownAndKeepsLooserOnesPossible() throws Exception {
        assumeFalse("needs a display", GraphicsEnvironment.isHeadless());
        Path session = session(folder.newFolder("identify-all-session").toPath());
        FitsFileInformation[] files = files(session);
        WcsCoordinateTransformer wcs = WcsCoordinateTransformer.fromHeader(files[0].getFitsHeader());
        long start = files[0].getObservationTimestamp();
        File reportFolder = report(folder.newFolder("identify-all-report"), files);
        var report = ReportedObjects.load(reportFolder);
        long first = report.frames().stream().mapToLong(ReportedObjects.Frame::timestampMillis).min().orElseThrow();
        long last = report.frames().stream().mapToLong(f -> f.timestampMillis() + Math.max(0, f.exposureMillis())).max().orElseThrow();
        long epoch = first + (last - first) / 2;

        // Place one known asteroid squarely on T1 (already Known) and one right on FM1 (unidentified).
        var t1Place = eu.startales.spacepixels.mpc.NearbyKnown.place(
                eu.startales.spacepixels.mpc.LightCurve.samples(report, report.objects().get(0)), null, epoch, wcs);
        var fm1Place = eu.startales.spacepixels.mpc.NearbyKnown.place(
                eu.startales.spacepixels.mpc.LightCurve.samples(report, report.objects().get(1)), report.objects().get(1).motion(), epoch, wcs);
        var knownT1 = new eu.startales.spacepixels.solarsystem.SolarSystemClient.Known(
                "649341 (2011 DK30)", false, t1Place.ra(), t1Place.dec(), 18.2, t1Place.raRateArcsecPerHour(), t1Place.decRateArcsecPerHour());
        var knownFm1 = new eu.startales.spacepixels.solarsystem.SolarSystemClient.Known(
                "2026 AB1", false, fm1Place.ra(), fm1Place.dec(), 20.5, fm1Place.raRateArcsecPerHour(), fm1Place.decRateArcsecPerHour());
        var fieldObjects = new eu.startales.spacepixels.solarsystem.SolarSystemClient.FieldObjects(
                List.of(knownT1, knownFm1), List.of(), List.of());

        var rawNearby = new java.util.LinkedHashMap<String, List<eu.startales.spacepixels.mpc.NearbyKnown.Match>>();
        for (var obj : report.objects()) {
            var p = eu.startales.spacepixels.mpc.NearbyKnown.place(
                    eu.startales.spacepixels.mpc.LightCurve.samples(report, obj), obj.motion(), epoch, wcs);
            rawNearby.put(obj.id(), eu.startales.spacepixels.mpc.NearbyKnown.near(p, fieldObjects.objects()));
        }
        var byFinding = eu.startales.spacepixels.mpc.NearbyKnown.noteNearer(rawNearby);
        var selected = AsteroidsCometsWindow.agreeingKnownForUnidentified(report.objects(), fieldObjects.objects(), byFinding);
        assertEquals(List.of("2026 AB1"), selected.stream().map(eu.startales.spacepixels.solarsystem.SolarSystemClient.Known::name).toList());

        var config = new AppConfig();
        config.observatoryCode = "500";
        ApplicationSettings settings = ApplicationSettings.inMemory(config);

        // 1) Ephemeris matches FM1 -> promoted to Known (same PredictionMatch rules as Compare again).
        AsteroidsCometsWindow w1 = onEdt(() -> new AsteroidsCometsWindow(null, files, report, wcs, correction(wcs), settings,
                new ApplicationStorage(), VisualizationSettings.defaults(), null, null, () -> null));
        try {
            var exactEphem = predictions(wcs, start);
            exactEphem.magnitudeLimit = Double.NaN;
            onEdt(() -> {
                w1.applyIdentifyOutcome(new AsteroidsCometsWindow.IdentifyOutcome(fieldObjects, byFinding, exactEphem));
                return null;
            });
            JTable objects = onEdt(() -> tables(w1).getFirst());
            assertEquals("Known", onEdt(() -> objects.getValueAt(1, 2)));
            assertEquals("2026 AB1", onEdt(() -> objects.getValueAt(1, 3)));
            onEdt(() -> { objects.setRowSelectionInterval(1, 1); return null; });
            assertTrue(onEdt(() -> button(w1, "JPL details: 2026 AB1 ↗").isVisible()));
            assertEquals("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=2026%20AB1",
                    onEdt(() -> button(w1, "JPL details: 2026 AB1 ↗").getToolTipText()));
            waitFor("Identify all saved later_identifications",
                    () -> eu.startales.spacepixels.mpc.LaterIdentifications.load(reportFolder).containsKey("FM1"));
            assertEquals(ReportedObjects.KNOWN, eu.startales.spacepixels.mpc.LaterIdentifications.load(reportFolder).get("FM1").status);
        } finally {
            onEdt(() -> { w1.dispose(); return null; });
        }

        // 2) Ephemeris fails strict PredictionMatch (e.g. shifted by 20 px) -> falls back to Possible from 1' nearby match.
        File reportFolder2 = report(folder.newFolder("identify-all-possible-report"), files);
        var report2 = ReportedObjects.load(reportFolder2);
        AsteroidsCometsWindow w2 = onEdt(() -> new AsteroidsCometsWindow(null, files, report2, wcs, correction(wcs), settings,
                new ApplicationStorage(), VisualizationSettings.defaults(), null, null, () -> null));
        try {
            var shifted = new eu.startales.spacepixels.solarsystem.SolarSystemObjects();
            shifted.magnitudeLimit = Double.NaN;
            shifted.bodies = List.of(body(wcs, start, "2026 AB1", 80, 30, 0, 1.5));
            onEdt(() -> {
                w2.applyIdentifyOutcome(new AsteroidsCometsWindow.IdentifyOutcome(fieldObjects, byFinding, shifted));
                return null;
            });
            JTable objects = onEdt(() -> tables(w2).getFirst());
            assertEquals("Possible", onEdt(() -> objects.getValueAt(1, 2)));
            assertEquals("2026 AB1", onEdt(() -> objects.getValueAt(1, 3)));
        } finally {
            onEdt(() -> { w2.dispose(); return null; });
        }
    }

    /**
     * Five solved 96 x 96 frames, two minutes apart, of sky 1000 with noise 10 and an object of FWHM 3 moving along
     * T1's path at (30 + 4k, 40).
     */
    private static Path session(Path folder) throws Exception {
        java.util.Random random = new java.util.Random(3);
        double sigma = 3 / 2.3548;
        for (int k = 0; k < 5; k++) {
            short[][] pixels = new short[96][96];
            double ox = 30 + 4 * k, oy = 40;
            double fx = 60, fy = 30 + 3 * k;
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
                double value = 1000 + 10 * random.nextGaussian()
                        + 2000 * Math.exp(-((x - ox) * (x - ox) + (y - oy) * (y - oy)) / (2 * sigma * sigma))
                        + 400 * Math.exp(-((x - fx) * (x - fx) + (y - fy) * (y - fy)) / (2 * sigma * sigma));
                pixels[y][x] = io.github.ppissias.jtransient.core.PixelEncoding.fromShiftedPositiveInt((int) Math.round(value));
            }
            try (Fits fits = new Fits()) {
                var hdu = Fits.makeHDU(pixels);
                var header = hdu.getHeader();
                header.addValue("BZERO", 32768.0, null);
                header.addValue("BSCALE", 1.0, null);
                header.addValue("DATE-OBS", String.format(java.util.Locale.US, "2026-04-08T00:%02d:00", 2 * k), null);
                header.addValue("EXPTIME", 30.0, null);
                header.addValue("CTYPE1", "RA---TAN", null);
                header.addValue("CTYPE2", "DEC--TAN", null);
                header.addValue("CRPIX1", 48.5, null);
                header.addValue("CRPIX2", 48.5, null);
                header.addValue("CRVAL1", 56.0, null);
                header.addValue("CRVAL2", 24.0, null);
                header.addValue("CD1_1", -0.0003, null);
                header.addValue("CD1_2", 0.0, null);
                header.addValue("CD2_1", 0.0, null);
                header.addValue("CD2_2", 0.0003, null);
                fits.addHDU(hdu);
                fits.write(folder.resolve(String.format(java.util.Locale.US, "frame_%02d.fit", k + 1)).toFile());
            }
        }
        return folder;
    }

    /** The synthetic frames as the session holds them. */
    private static FitsFileInformation[] files(Path session) throws Exception {
        File[] fits = session.toFile().listFiles((d, n) -> n.endsWith(".fit"));
        java.util.Arrays.sort(fits);
        var files = new FitsFileInformation[fits.length];
        for (int i = 0; i < fits.length; i++) {
            Map<String, String> header = new HashMap<>();
            try (Fits f = new Fits(fits[i])) {
                var cursor = f.getHDU(0).getHeader().iterator();
                while (cursor.hasNext()) {
                    HeaderCard card = cursor.next();
                    if (card.getKey() != null && card.getValue() != null) header.put(card.getKey(), card.getValue());
                }
            }
            files[i] = new FitsFileInformation(fits[i].getPath(), fits[i].getName(), true, 96, 96, header);
        }
        return files;
    }

    /** A report with a known track (T1) and a faint mover marked Check (FM1), on these frames. */
    private static File report(File folder, FitsFileInformation[] files) throws Exception {
        var frames = new StringBuilder();
        var t1 = new StringBuilder();
        var fm1 = new StringBuilder();
        for (int k = 0; k < files.length; k++) {
            String separator = k == 0 ? "" : ",";
            frames.append(separator).append(String.format(java.util.Locale.US,
                    "{\"fileName\":\"%s\",\"width\":96,\"height\":96,\"timestampMillis\":%d,\"exposureMillis\":30000}",
                    files[k].getFileName(), files[k].getObservationTimestamp()));
            t1.append(separator).append(String.format(java.util.Locale.US,
                    "{\"frameNumber\":%d,\"x\":%.1f,\"y\":40.0,\"fwhm\":3.0,\"snr\":40.0}", k + 1, 30.0 + 4 * k));
            fm1.append(separator).append(String.format(java.util.Locale.US,
                    "{\"frameNumber\":%d,\"x\":60.0,\"y\":%.1f}", k + 1, 30.0 + 3 * k));
        }
        String json = "{\"inputFrames\":[" + frames + "],"
                + "\"findings\":[{\"id\":\"T1\",\"positions\":[" + t1 + "]}],"
                + "\"faintMovers\":[{\"id\":\"FM1\",\"x\":60,\"y\":30,\"referenceTimeMillis\":" + files[0].getObservationTimestamp()
                + ",\"vxPixelsPerMinute\":0,\"vyPixelsPerMinute\":1.5,\"cutout\":\"faint_movers/fm1_stack.png\","
                + "\"review\":{\"data\":\"faint_movers/fm1_view.js\"},\"fittedPositions\":[" + fm1 + "]}],"
                + "\"faintMoverSearch\":{\"fwhmPixels\":2.5},"
                + "\"knownSolarSystem\":{\"bodies\":[{\"name\":\"649341 (2011 DK30)\",\"comet\":false,\"minimumMagnitude\":18.4,"
                + "\"maximumMagnitude\":18.4,\"informationUrl\":\"https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=649341\"}],"
                + "\"identifications\":["
                + "{\"id\":\"T1\",\"status\":\"known\",\"names\":[\"649341 (2011 DK30)\"],\"note\":\"Position and motion agree.\"},"
                + "{\"id\":\"FM1\",\"status\":\"check\",\"names\":[],\"note\":\"No known object agrees.\",\"unusualMotion\":\"faster than a main-belt asteroid: possibly near Earth\"}]}}";
        Files.writeString(new File(folder, "report-data.json").toPath(), json, StandardCharsets.UTF_8);
        File fmDir = new File(folder, "faint_movers");
        assertTrue(fmDir.mkdirs());
        var stack = new java.awt.image.BufferedImage(15, 15, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
        stack.getRaster().setSample(7, 7, 0, 230);
        javax.imageio.ImageIO.write(stack, "png", new File(fmDir, "fm1_stack.png"));
        int rw = 15, rh = 15;
        var buf = java.nio.ByteBuffer.allocate(rw * rh * files.length * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        var fmFrames = new StringBuilder();
        for (int k = 0; k < files.length; k++) {
            if (k > 0) fmFrames.append(",");
            fmFrames.append(String.format(java.util.Locale.US, "{\"number\":%d,\"x\":7.0,\"y\":7.0,\"unmasked\":true}", k + 1));
            for (int i = 0; i < rw * rh; i++) buf.putFloat(i == 7 * rw + 7 ? 2.5f : 0f);
        }
        String viewJs = "globalThis.SpacePixelsFaintMovers.accept(\"FM1\",{\"version\":1,\"width\":" + rw + ",\"height\":" + rh
                + ",\"followsPath\":false,\"frames\":[" + fmFrames + "],\"residual\":\""
                + java.util.Base64.getEncoder().encodeToString(buf.array()) + "\"});";
        Files.writeString(new File(fmDir, "fm1_view.js").toPath(), viewJs, StandardCharsets.UTF_8);
        return folder;
    }

    /**
     * Saved predictions with T1's object (2 pixels per minute along y = 40 from x = 30) and one on FM1's path (1.5
     * pixels per minute down from (60, 30)), from {@code start}.
     */
    private static eu.startales.spacepixels.solarsystem.SolarSystemObjects predictions(WcsCoordinateTransformer wcs, long start) {
        var objects = new eu.startales.spacepixels.solarsystem.SolarSystemObjects();
        objects.magnitudeLimit = 25;
        objects.startMillis = start - 300_000;
        objects.endMillis = start + 900_000;
        objects.bodies = new ArrayList<>(List.of(body(wcs, start, "649341 (2011 DK30)", 30, 40, 2, 0), body(wcs, start, "2026 AB1", 60, 30, 0, 1.5)));
        objects.fetchedAtUtc = "2026-11-15T00:00:00Z";
        return objects;
    }

    /** A predicted object at (x, y) at {@code start}, moving (vx, vy) pixels per minute, sampled every minute. */
    private static eu.startales.spacepixels.solarsystem.SolarSystemObjects.Body body(WcsCoordinateTransformer wcs, long start, String name,
                                                                                    double x, double y, double vx, double vy) {
        var body = new eu.startales.spacepixels.solarsystem.SolarSystemObjects.Body();
        body.name = name;
        for (int minute = -5; minute <= 15; minute++) {
            var sky = wcs.pixelToSky(x + vx * minute, y + vy * minute);
            var sample = new eu.startales.spacepixels.solarsystem.SolarSystemObjects.Sample();
            sample.timeMillis = start + minute * 60_000L;
            sample.ra = sky.getRaDegrees();
            sample.dec = sky.getDecDegrees();
            body.samples.add(sample);
        }
        return body;
    }

    /** A checked plate correction for this solution, so positions are reduced. */
    private static PlateSolutionCorrection correction(WcsCoordinateTransformer wcs) {
        var global = new PlateSolutionCorrection.Polynomial();
        global.order = 0;
        global.cx = 48;
        global.cy = 48;
        global.scale = 48;
        global.ax = new double[]{0};
        global.ay = new double[]{0};
        var correction = new PlateSolutionCorrection();
        correction.solutionKey = wcs.solutionKey();
        correction.width = 96;
        correction.height = 96;
        correction.global = global;
        correction.beforeMedianPx = 0.3;
        correction.afterMedianPx = 0.2;
        return correction;
    }

    private static <T> T onEdt(Supplier<T> action) throws Exception {
        var result = new ArrayList<T>(1);
        SwingUtilities.invokeAndWait(() -> result.add(action.get()));
        return result.getFirst();
    }

    private static void waitFor(String what, CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.met()) return;
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private interface CheckedCondition { boolean met() throws Exception; }

    private static <C extends Component> List<C> all(Container root, Class<C> type, List<C> found) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c)) found.add(type.cast(c));
            if (c instanceof Container k) all(k, type, found);
        }
        return found;
    }

    private static List<JTable> tables(JFrame window) { return all(window.getContentPane(), JTable.class, new ArrayList<>()); }

    private static boolean tableContains(JTable table, String needle) {
        for (int r = 0; r < table.getRowCount(); r++) {
            for (int c = 0; c < table.getColumnCount(); c++) {
                Object val = table.getValueAt(r, c);
                if (val != null && val.toString().contains(needle)) return true;
            }
        }
        return false;
    }

    private static List<JTextArea> areas(JFrame window) { return all(window.getContentPane(), JTextArea.class, new ArrayList<>()); }

    private static AbstractButton button(JFrame window, String text) {
        return all(window.getContentPane(), AbstractButton.class, new ArrayList<>()).stream().filter(b -> text.equals(b.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("no button " + text));
    }

    private static JTextField fieldWithText(JFrame window, String text) {
        return all(window.getContentPane(), JTextField.class, new ArrayList<>()).stream().filter(f -> text.equals(f.getText()))
                .findFirst().orElseThrow();
    }

    private static JTextField emailField(JFrame window) {
        return all(window.getContentPane(), JTextField.class, new ArrayList<>()).stream()
                .filter(f -> f.getToolTipText() != null && f.getToolTipText().startsWith("Where the MPC sends its replies")).findFirst().orElseThrow();
    }

    private static List<JLabel> labels(JFrame window) {
        var found = all(window.getContentPane(), JLabel.class, new ArrayList<>());
        found.removeIf(l -> l.getText() == null);
        return found;
    }

    private static JLabel labelContaining(JFrame window, String text) {
        return all(window.getContentPane(), JLabel.class, new ArrayList<>()).stream().filter(l -> l.getText() != null && l.getText().contains(text))
                .findFirst().orElseThrow(() -> new AssertionError("no label with " + text));
    }

    private static JComboBox<?> combo(JFrame window, Object item) {
        for (JComboBox<?> box : all(window.getContentPane(), JComboBox.class, new ArrayList<>())) {
            for (int i = 0; i < box.getItemCount(); i++) if (item.equals(box.getItemAt(i))) return box;
        }
        throw new AssertionError("no combo with " + item);
    }
}
