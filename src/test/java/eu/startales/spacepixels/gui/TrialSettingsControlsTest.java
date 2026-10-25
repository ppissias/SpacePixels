package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.CoreDetectionSettings;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Checks trial isolation, selective merging and typed-value commits without opening a window. */
public class TrialSettingsControlsTest {
    /** A restored spinner or typed edit stays clean when another trial later aligns its value automatically. */
    @Test public void returningToBaselineClearsTheEditBeforeLaterPairAlignment() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (boolean typed : new boolean[]{false, true}) {
                DetectionConfig live = config();
                AtomicReference<Map<String, Number>> delta = new AtomicReference<>();
                TrialSettingsControls controls = controls(live, delta);
                JComponent row = controls.horizontal();
                try {
                    JSpinner grow = spinner(row, "Grow Sigma (Hysteresis)");
                    if (typed) {
                        field(grow).setText("2.0");
                        grow.commitEdit();
                        field(grow).setText("3.0");
                        grow.commitEdit();
                    } else {
                        grow.setValue(2.0);
                        grow.setValue(3.0);
                    }
                    spinner(row, "Detection Sigma").setValue(2.0);
                    live.growSigmaMultiplier = 1.0;
                    button(row, "Apply to Settings").doClick();
                    assertEquals(Set.of("detectionSigmaMultiplier"), delta.get().keySet());
                    assertEquals("the restored edit cannot overwrite a newer live value", 1.0, live.growSigmaMultiplier, 0);
                } catch (java.text.ParseException invalidFixture) {
                    throw new AssertionError(invalidFixture);
                } finally {
                    controls.stop();
                }
            }
        });
    }

    /** Delayed refresh waits for the application job to unlock and cannot run after the viewer closes. */
    @Test public void delayedRefreshPausesForAJobAndStopsOnClose() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        CountDownLatch refreshed = new CountDownLatch(1);
        TrialSettingsControls[] controls = {null};
        JComponent[] row = {null};
        SwingUtilities.invokeAndWait(() -> {
            DetectionConfig initial = config();
            controls[0] = new TrialSettingsControls(List.of(new TrialSettingsControls.Input(
                    CoreDetectionSettings.DETECTION_SIGMA_MULTIPLIER, 5.0, 1.0, 20.0, 0.1)),
                    initial, () -> { refreshes.incrementAndGet(); refreshed.countDown(); }, () -> {}, changes -> initial.clone());
            row[0] = controls[0].horizontal();
            spinner(row[0], "Detection Sigma").setValue(4.0);
            controls[0].setEditable(false);
        });
        try {
            assertFalse("locked trials do not refresh", refreshed.await(450, TimeUnit.MILLISECONDS));
            SwingUtilities.invokeAndWait(() -> controls[0].setEditable(true));
            assertTrue(refreshed.await(2, TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(() -> {
                spinner(row[0], "Detection Sigma").setValue(6.0);
                controls[0].stop();
            });
            Thread.sleep(450);
            assertEquals(1, refreshes.get());
        } finally {
            SwingUtilities.invokeAndWait(() -> controls[0].stop());
        }
    }

    /** An automatic grow adjustment stays local and cannot overwrite an untouched field changed elsewhere. */
    @Test public void applyingOnlyExplicitEditsPreservesNewerUntouchedValues() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DetectionConfig live = config();
            AtomicReference<Map<String, Number>> delta = new AtomicReference<>();
            TrialSettingsControls controls = controls(live, delta);
            JComponent row = controls.horizontal();
            try {
                spinner(row, "Detection Sigma").setValue(2.0);
                assertEquals(5.0, live.detectionSigmaMultiplier, 0);
                assertEquals(2.0, controls.snapshot(live).growSigmaMultiplier, 0);
                live.growSigmaMultiplier = 1.0;
                live.minDetectionPixels = 17;
                button(row, "Apply to Settings").doClick();
                assertEquals(Set.of("detectionSigmaMultiplier"), delta.get().keySet());
                assertEquals(2.0, live.detectionSigmaMultiplier, 0);
                assertEquals(1.0, live.growSigmaMultiplier, 0);
                assertEquals(17, live.minDetectionPixels);
                live.detectionSigmaMultiplier = 8.0;
                button(row, "Apply to Settings").doClick();
                assertEquals("repeated Apply has no stale delta", 8.0, live.detectionSigmaMultiplier, 0);
            } finally {
                controls.stop();
            }
        });
    }

    /** Apply commits all valid pending text together before aligning a pair; invalid text blocks the whole action. */
    @Test public void applyingTypedValuesDoesNotLoseTheSecondFieldOrAcceptInvalidText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DetectionConfig live = config();
            AtomicReference<Map<String, Number>> delta = new AtomicReference<>();
            TrialSettingsControls controls = controls(live, delta);
            JComponent row = controls.horizontal();
            try {
                field(spinner(row, "Detection Sigma")).setText("7.0");
                field(spinner(row, "Grow Sigma (Hysteresis)")).setText("6.0");
                button(row, "Apply to Settings").doClick();
                assertEquals(7.0, live.detectionSigmaMultiplier, 0);
                assertEquals(6.0, live.growSigmaMultiplier, 0);
                assertEquals(Set.of("detectionSigmaMultiplier", "growSigmaMultiplier"), delta.get().keySet());
                delta.set(null);
                field(spinner(row, "Detection Sigma")).setText("99999");
                assertFalse(button(row, "Apply to Settings").isEnabled());
                button(row, "Apply to Settings").doClick();
                assertNull(delta.get());
                assertEquals(7.0, live.detectionSigmaMultiplier, 0);
            } finally {
                controls.stop();
            }
        });
    }

    /** Reopening starts from current settings, and a job lock prevents applying local edits. */
    @Test public void reopeningDiscardsUnappliedValuesAndJobLockBlocksApply() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DetectionConfig live = config();
            AtomicReference<Map<String, Number>> delta = new AtomicReference<>();
            TrialSettingsControls controls = controls(live, delta);
            JComponent row = controls.horizontal();
            try {
                spinner(row, "Detection Sigma").setValue(4.0);
                controls.setEditable(false);
                assertFalse(spinner(row, "Detection Sigma").isEnabled());
                assertFalse(button(row, "Apply to Settings").isEnabled());
                button(row, "Apply to Settings").doClick();
                assertNull(delta.get());
                controls.stop();
                live.detectionSigmaMultiplier = 9.0;
                controls.load(live);
                controls.setEditable(true);
                assertEquals(9.0, controls.value(CoreDetectionSettings.DETECTION_SIGMA_MULTIPLIER).doubleValue(), 0);
                button(row, "Apply to Settings").doClick();
                assertNull(delta.get());
            } finally {
                controls.stop();
            }
        });
    }

    /** Uses ordinary valid per-frame settings with a grow threshold that can be aligned downward. */
    private static DetectionConfig config() {
        DetectionConfig config = new DetectionConfig();
        config.detectionSigmaMultiplier = 5.0;
        config.growSigmaMultiplier = 3.0;
        config.minDetectionPixels = 8;
        return config;
    }

    /** Supplies a live merge callback that records the exact edited-field contract. */
    private static TrialSettingsControls controls(DetectionConfig live, AtomicReference<Map<String, Number>> delta) {
        return new TrialSettingsControls(List.of(
                new TrialSettingsControls.Input(CoreDetectionSettings.DETECTION_SIGMA_MULTIPLIER, live.detectionSigmaMultiplier, 1.0, 20.0, 0.1),
                new TrialSettingsControls.Input(CoreDetectionSettings.GROW_SIGMA_MULTIPLIER, live.growSigmaMultiplier, 0.1, 20.0, 0.1),
                new TrialSettingsControls.Input(CoreDetectionSettings.MIN_DETECTION_PIXELS, live.minDetectionPixels, 1, 2000, 1)),
                live, () -> {}, () -> {}, changes -> {
                    delta.set(changes);
                    changes.forEach((id, value) -> {
                        switch (id) {
                            case "detectionSigmaMultiplier" -> live.detectionSigmaMultiplier = value.doubleValue();
                            case "growSigmaMultiplier" -> live.growSigmaMultiplier = value.doubleValue();
                            case "minDetectionPixels" -> live.minDetectionPixels = value.intValue();
                            default -> throw new AssertionError(id);
                        }
                    });
                    return live.clone();
                });
    }

    /** Finds the input by the same accessible title used in the real viewers. */
    private static JSpinner spinner(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JSpinner spinner && name.equals(spinner.getAccessibleContext().getAccessibleName())) return spinner;
            if (child instanceof Container container) {
                JSpinner found = spinner(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Finds a shared action by its visible caption. */
    private static JButton button(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container container) {
                JButton found = button(container, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Gets the validated numeric text editor without changing its focus. */
    private static JFormattedTextField field(JSpinner spinner) {
        return ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
    }
}
