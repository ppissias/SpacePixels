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
            model.activeSettingsChanged(key);
            assertTrue(model.isQualityCurrent());
            model.replaceFile(files[0], files[0].withFitsHeader(Map.of("CRVAL1", "123")));
            assertTrue("WCS-only updates preserve QC", model.isQualityCurrent());
            model.replaceFile(files[1], files[1].withFitsHeader(Map.of("EXPTIME", "60")));
            assertFalse("Changed exposure invalidates the cohort", model.isQualityCurrent());
            assertEquals(2, model.getRowCount());
        });
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
