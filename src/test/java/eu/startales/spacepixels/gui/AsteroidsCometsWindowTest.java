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
                    VisualizationSettings.defaults(), new ObservingSite(-30, -70, 2000, ObservingSite.FROM_CONFIGURATION), null);
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

            // A light curve, then the same curve in bins of 2.
            onEdt(() -> { button(window, "Measure light curve").doClick(); return null; });
            waitFor("the light curve", () -> onEdt(() -> labels(window).stream().anyMatch(l -> l.getText().contains("T1 · 5 frames ·"))));
            JLabel curveLabel = onEdt(() -> labelContaining(window, "T1 · 5 frames ·"));
            assertTrue(onEdt(curveLabel::getText), onEdt(curveLabel::getText).contains("uncorrected"));
            onEdt(() -> { combo(window, "Bins of 2").setSelectedItem("Bins of 2"); return null; });
            assertTrue(onEdt(curveLabel::getText), onEdt(curveLabel::getText).contains("2 bins of 2 frames"));
        } finally {
            onEdt(() -> { window.dispose(); return null; });
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
            for (int y = 0; y < 96; y++) for (int x = 0; x < 96; x++) {
                double value = 1000 + 10 * random.nextGaussian() + 2000 * Math.exp(-((x - ox) * (x - ox) + (y - oy) * (y - oy)) / (2 * sigma * sigma));
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
                + ",\"vxPixelsPerMinute\":0,\"vyPixelsPerMinute\":180,\"fittedPositions\":[" + fm1 + "]}],"
                + "\"faintMoverSearch\":{\"fwhmPixels\":2.5},"
                + "\"knownSolarSystem\":{\"identifications\":["
                + "{\"id\":\"T1\",\"status\":\"known\",\"names\":[\"649341 (2011 DK30)\"],\"note\":\"Position and motion agree.\"},"
                + "{\"id\":\"FM1\",\"status\":\"check\",\"names\":[],\"note\":\"No known object agrees.\",\"unusualMotion\":\"faster than a main-belt asteroid: possibly near Earth\"}]}}";
        Files.writeString(new File(folder, "report-data.json").toPath(), json, StandardCharsets.UTF_8);
        return folder;
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

    private static JComboBox<?> combo(JFrame window, String item) {
        for (JComboBox<?> box : all(window.getContentPane(), JComboBox.class, new ArrayList<>())) {
            for (int i = 0; i < box.getItemCount(); i++) if (item.equals(box.getItemAt(i))) return box;
        }
        throw new AssertionError("no combo with " + item);
    }
}
