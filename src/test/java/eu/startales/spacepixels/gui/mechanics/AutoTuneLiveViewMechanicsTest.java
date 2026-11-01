package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.ApplicationWindow;
import eu.startales.spacepixels.gui.MainApplicationPanel;
import eu.startales.spacepixels.jobs.JobKind;
import nom.tam.fits.Fits;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.WindowFinder;
import org.junit.Test;

import javax.swing.JDialog;
import javax.swing.JLabel;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Random;
import java.util.prefs.Preferences;

import static org.junit.Assert.*;

/** Drives the real Auto-Tune command, image toggle and cancellation with restored preferences and synthetic sky. */
public class AutoTuneLiveViewMechanicsTest extends MechanicsTestCase {
    /** Starts with the picture visible regardless of the user's saved choice; the base fixture restores it. */
    @Override protected ApplicationWindow createApplicationWindow() {
        Preferences.userNodeForPackage(MainApplicationPanel.class).putBoolean("progressDialog.liveView", true);
        return super.createApplicationWindow();
    }

    /** A real tuner job shows images, supports Hide/Show, and releases the dialog and controls after Cancel. */
    @Test(timeout = 120_000) public void autoTuneShowsPicturesAndCancelsThroughTheWindow() throws Exception {
        Path sequence = createSequence();
        importFolder(sequence);
        window().button(VisibleText.button("Auto-Tune Settings")).click();
        var dialog = WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class) {
            /** Matches only the currently visible processing dialog. */
            @Override protected boolean isMatching(JDialog item) { return item.isShowing() && "Processing...".equals(item.getTitle()); }
        }).using(robot());
        var picture = dialog.label(new GenericTypeMatcher<>(JLabel.class) {
            /** Finds the image by the same accessible name used by screen readers. */
            @Override protected boolean isMatching(JLabel item) {
                return "Live view".equals(item.getAccessibleContext().getAccessibleName()) && !"Live view".equals(item.getText());
            }
        });
        waitFor("an actual Auto-Tune image", () -> GuiActionRunner.execute(() -> picture.target().getIcon() != null
                && picture.target().getAccessibleContext().getAccessibleDescription().contains("Auto-Tune")));
        assertEquals(JobKind.AUTO_TUNE, GuiActionRunner.execute(() -> application().getJobCoordinator().current().kind()));
        dialog.button(VisibleText.button("Hide picture")).click();
        assertFalse(GuiActionRunner.execute(() -> picture.target().isShowing()));
        dialog.button(VisibleText.button("Show picture")).click();
        picture.requireVisible();
        dialog.button(VisibleText.button("Cancel")).click();
        waitFor("Auto-Tune cancellation and dialog cleanup", () -> GuiActionRunner.execute(() ->
                application().getJobCoordinator().current() == null && !dialog.target().isShowing()));
        assertTrue(GuiActionRunner.execute(() -> application().getFrame().isEnabled()));
    }

    /** Makes enough registered mono16 sky for the real tuner to stay active while the controls are exercised. */
    private Path createSequence() throws Exception {
        Path sequence = folder.newFolder("tuning-sky").toPath();
        Random random = new Random(101);
        for (int i = 0; i < 5; i++) {
            short[][] pixels = new short[1800][1800];
            for (short[] row : pixels) for (int x = 0; x < row.length; x++)
                row[x] = (short) ((int) Math.round(1000 + 20 * random.nextGaussian()) - 32768);
            try (Fits fits = new Fits()) {
                var hdu = Fits.makeHDU(pixels);
                hdu.addValue("BZERO", 32768, null);
                hdu.addValue("BSCALE", 1, null);
                hdu.addValue("EXPTIME", 60, null);
                hdu.addValue("DATE-OBS", Instant.parse("2020-01-01T00:00:00Z").plusSeconds(i * 60L).toString(), null);
                fits.addHDU(hdu);
                fits.write(sequence.resolve("frame-" + i + ".fit").toFile());
            }
        }
        return sequence;
    }
}
