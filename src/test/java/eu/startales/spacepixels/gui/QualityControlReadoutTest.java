package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.QualityControlSnapshot;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.FrameQualityResult;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

/** Checks original-row mapping, stale profiles, unavailable measurements and theme/selection-safe quality tints. */
public class QualityControlReadoutTest {
    /** Model/view reordering does not attach another frame's reason or tint, and changing settings removes current tints. */
    @Test public void mappingStalenessAndSelectionAreExplicit() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var files = new FitsFileInformation[]{file("a.fit"), file("b.fit")};
            DetectionConfig config = new DetectionConfig();
            String key = QualityControlSnapshot.settingsKey(config);
            var metrics = List.of(frame(0, false), frame(1, true));
            var result = new FrameQualityResult(metrics, new FrameQualityResult.Thresholds(false,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN), 0.9, 0.95);
            var snapshot = new QualityControlSnapshot(QualityControlSnapshot.inputs(files), key, result);
            var model = new FitsFileTableModel(files);
            var table = new QualityControlTable();
            table.setModel(model);
            table.setAutoCreateRowSorter(true);
            model.showQuality(snapshot, key);
            table.getRowSorter().toggleSortOrder(FitsFileTableModel.COL_FILENAME);
            table.getRowSorter().toggleSortOrder(FitsFileTableModel.COL_FILENAME);
            table.moveColumn(FitsFileTableModel.COL_QUALITY, 1);
            assertEquals("Would reject", table.getValueAt(0, 1));
            assertEquals("Unavailable", model.getValueAt(0, FitsFileTableModel.COL_FWHM));
            var rejectColor = table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0).getBackground();
            var keepColor = table.prepareRenderer(table.getCellRenderer(1, 0), 1, 0).getBackground();
            assertNotEquals(rejectColor, keepColor);
            table.setRowSelectionInterval(0, 0);
            assertEquals(table.getSelectionBackground(), table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0).getBackground());
            table.clearSelection();
            config.qualitySigmaMultiplier += 0.25;
            model.activeSettingsChanged(QualityControlSnapshot.settingsKey(config));
            assertFalse(model.isQualityCurrent());
            assertTrue(table.getValueAt(0, 1).toString().startsWith("Stale"));
            assertEquals(table.getBackground(), table.prepareRenderer(table.getCellRenderer(0, 0), 0, 0).getBackground());
            String detail = QualityControlDetails.describe(snapshot, metrics.getFirst(), false);
            assertTrue(detail.contains("STALE RESULT"));
            assertTrue(detail.contains("fewer than 3"));
            assertTrue(detail.contains("FWHM: Unavailable"));
            assertTrue(detail.contains("Statistical session thresholds unavailable"));
            assertTrue(detail.contains("not residual stellar alignment"));
            String shown = labels(QualityControlDetails.content(snapshot, metrics.getFirst(), false));
            assertTrue(shown.contains("Stale result"));
            assertTrue(shown.contains("fewer than 3"));
            assertTrue("FWHM without reference stars has no number", shown.contains("FWHM") && shown.contains("Unavailable"));
            assertFalse("The engine's fallback width is not shown as measured", shown.contains("999"));
            assertTrue(shown.contains("Statistical session thresholds unavailable"));
            assertTrue(shown.contains("Unavailable (registration failed)"));
            assertTrue(shown.contains("not residual stellar alignment"));
            model.activeSettingsChanged(key);
            assertTrue(model.isQualityCurrent());
            model.replaceFile(files[0], files[0].withFitsHeader(Map.of("CRVAL1", "123")));
            assertTrue("WCS-only updates preserve QC", model.isQualityCurrent());
            model.replaceFile(files[1], files[1].withFitsHeader(Map.of("EXPTIME", "60")));
            assertFalse("Changed exposure invalidates the cohort", model.isQualityCurrent());
            assertEquals(2, model.getRowCount());
        });
    }

    /** The readout shows every session limit beside its measurement, in the engine's values and fixed units. */
    @Test public void detailsShowLimitsBesideMeasurements() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var files = new FitsFileInformation[]{file("a.fit"), file("b.fit"), file("c.fit")};
            var fit = new FrameQualityResult.Registration(1.25, -0.5, 0, 0.125, true);
            var frame = new FrameQualityResult.Frame(1, "b.fit", true, "Too few stars <60%", 1102.5, 56.5, 3.125, 1.25, 1.125, 812, 300, 12, 120, fit);
            var metrics = List.of(frame(0, false), frame, new FrameQualityResult.Frame(2, "c.fit", false, "OK", 1100, 55, 3, 1.2, 1.1, 900, 310, 13, 125, fit));
            var limits = new FrameQualityResult.Thresholds(true, 300.5, 4.5, 1.6, 1.55, 1100, 50, 1050, 1150);
            var result = new FrameQualityResult(metrics, limits, 0.983, 0.978);
            var snapshot = new QualityControlSnapshot(QualityControlSnapshot.inputs(files), QualityControlSnapshot.settingsKey(new DetectionConfig()), result);
            String shown = labels(QualityControlDetails.content(snapshot, frame, true));
            assertTrue(shown.contains("Would reject"));
            assertTrue("The reason stays literal in the HTML label", shown.contains("Too few stars &lt;60%"));
            assertTrue(shown.contains("Frame 2 of 3"));
            assertTrue(shown.contains("at least 300.5"));
            assertTrue(shown.contains("3.13 px"));
            assertTrue(shown.contains("at most 4.50 px"));
            assertTrue(shown.contains("1,102.5 ADU"));
            assertTrue(shown.contains("1,050.0 to 1,150.0 ADU"));
            assertTrue(shown.contains("Baseline 1,100.0 ± 50.0"));
            assertTrue(shown.contains("98.3 %"));
            assertTrue(shown.contains("1.25 / -0.50 px"));
            assertTrue(shown.contains("Usable"));
            assertFalse(shown.contains("Stale result"));
        });
    }

    /** Every label's text in a readout, one per line. */
    private static String labels(java.awt.Component component) {
        StringBuilder text = new StringBuilder();
        if (component instanceof javax.swing.JLabel label) text.append(label.getText()).append('\n');
        if (component instanceof java.awt.Container container) {
            for (var child : container.getComponents()) text.append(labels(child));
        }
        return text.toString();
    }

    /** Minimal immutable imported descriptor with absent clocks. */
    private static FitsFileInformation file(String name) {
        return new FitsFileInformation(name, name, true, 96, 96);
    }

    /** Zero FWHM references must not display the engine's conservative fallback as a measured width. */
    private static FrameQualityResult.Frame frame(int index, boolean rejected) {
        return new FrameQualityResult.Frame(index, index == 0 ? "a.fit" : "b.fit", rejected, rejected ? "Too few stars" : "OK",
                1000, 8, 999, 1.1, Double.NaN, 8, 8, 0, 0, null);
    }
}
