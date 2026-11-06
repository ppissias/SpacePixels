package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.FitsFileTableModel;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import java.nio.file.Files;
import java.util.Arrays;
import javax.swing.JDialog;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import nom.tam.fits.Fits;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises the real Inspect action, all-input scope, measured row reasons, details and settings invalidation. */
public class QualityControlMechanicsTest extends MechanicsTestCase {
    /** One selected frame still assesses the full cohort and preserves all rows, the selection and original files. */
    @Test(timeout = 180_000) public void assessAllFramesAndShowReasonsAndStaleSettings() throws Exception {
        var directory = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("frames").toPath(), "session", 5);
        var failed = directory.resolve("frame_03.fit");
        short[][] blank = new short[96][96];
        for (var row : blank) Arrays.fill(row, Short.MIN_VALUE);
        var hdu = Fits.makeHDU(blank);
        hdu.getHeader().addValue("BZERO", 32768, null);
        hdu.getHeader().addValue("BSCALE", 1, null);
        hdu.getHeader().addValue("DATE-OBS", "2026-04-08T00:00:02", null);
        hdu.getHeader().addValue("EXPTIME", 30.0, null);
        Files.delete(failed);
        try (var fits = new Fits()) { fits.addHDU(hdu); fits.write(failed.toFile()); }
        byte[] original = Files.readAllBytes(failed);
        importFolder(directory);
        window().table().selectRows(2);
        window().button(VisibleText.button("Run quality control")).requireEnabled().click();
        waitFor("complete quality result", () -> GuiActionRunner.execute(() -> application().getSession().getQualityControl() != null));
        waitFor("controls unlocked", () -> window().button(VisibleText.button("Run quality control")).target().isEnabled());
        var snapshot = GuiActionRunner.execute(() -> application().getSession().getQualityControl());
        assertEquals(5, snapshot.result().frames().size());
        assertEquals(1, snapshot.result().rejectedFrames());
        assertEquals(5, window().table().rowCount());
        assertEquals(1, GuiActionRunner.execute(() -> window().table().target().getSelectedRowCount()).intValue());
        assertEquals(2, GuiActionRunner.execute(() -> window().table().target().getSelectedRow()).intValue());
        var model = (FitsFileTableModel) window().table().target().getModel();
        assertEquals("Would reject", GuiActionRunner.execute(() -> model.getValueAt(2, FitsFileTableModel.COL_QUALITY)));
        assertTrue(snapshot.result().frames().get(2).reason().contains("registration"));
        assertArrayEquals(original, Files.readAllBytes(failed));
        window().button(VisibleText.button("Quality details…")).requireEnabled().click();
        var details = WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class) {
            /** Finds the measured frame's user-facing detail window. */
            @Override protected boolean isMatching(JDialog dialog) { return dialog.isShowing() && dialog.getTitle().startsWith("Quality details"); }
        }).withTimeout(10_000).using(robot());
        var readout = details.textBox(new GenericTypeMatcher<>(JTextArea.class) {
            /** Selectable evidence text is accessible by its purpose. */
            @Override protected boolean isMatching(JTextArea text) {
                return "Frame quality measurements and session limits".equals(text.getAccessibleContext().getAccessibleName());
            }
        });
        assertTrue(readout.text().contains("All 5 imported frames assessed"));
        assertTrue(readout.text().contains("Background noise"));
        assertTrue(readout.text().contains("not residual stellar alignment"));
        details.button(VisibleText.button("OK")).click();
        window().tabbedPane().selectTab("Detection Settings");
        JSpinner sigma = window().spinner(new GenericTypeMatcher<>(JSpinner.class, true) {
            /** Finds the core setting on the default Overview page. */
            @Override protected boolean isMatching(JSpinner spinner) { return "Detection Sigma".equals(spinner.getAccessibleContext().getAccessibleName()); }
        }).target();
        GuiActionRunner.execute(() -> sigma.setValue(((Number) sigma.getValue()).doubleValue() + 0.25));
        waitFor("old quality marked stale", () -> !GuiActionRunner.execute(model::isQualityCurrent));
        assertTrue(GuiActionRunner.execute(() -> model.getValueAt(2, FitsFileTableModel.COL_QUALITY)).toString().startsWith("Stale"));
        window().tabbedPane().selectTab("Main");
        window().button(VisibleText.button("Run quality control")).click();
        waitFor("fresh complete quality replaces stale result", () -> GuiActionRunner.execute(() ->
                application().getSession().getQualityControl() != snapshot && model.isQualityCurrent()));
    }
}
