package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.DetectionSummary;
import eu.startales.spacepixels.report.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;

import javax.swing.*;
import java.awt.GridBagLayout;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/** Tests desktop report contents and EDT handoff without requiring a visible desktop. */
public class GuiDetectionReportPromptTest {
    /** Quality questions reach the EDT and proceed only for the explicit override, including dismissal. */
    @Test(timeout = 10_000)
    public void qualityWarningChoicesAreDispatchedAndFailClosed() {
        var inspection = new eu.startales.spacepixels.fits.FrameCalibrationInspector.InspectionResult(
                true, false, 40, 242, 0, "sensor defects");
        for (int answer : new int[]{-1, 0, 1}) {
            AtomicBoolean asked = new AtomicBoolean();
            GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(panel -> {
                throw new AssertionError("Not a report question");
            }, panel -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                asked.set(true);
                return answer;
            });
            assertEquals(answer == 1, prompt.confirmPreFlight(inspection));
            assertTrue(asked.get());
        }
    }

    /** Preserves row-to-checkbox mapping and report counts without changing analysis settings. */
    @Test
    public void localRescueAndCommonMotionCanBeUncheckedOnTheirOwnRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var summary = new DetectionSummary(
                    328, 0, 1, 0, 1, 0, 274, 0, "READY", 10, 18, 52, 1991, 0);
            var config = new DetectionConfig();
            config.enableVariableStarDetection = true;
            var choice = new Choice(summary, config);
            assertNotNull(choice.checkbox("Single-frame anomalies"));
            assertNull("A zero slow-mover count must not receive the anomaly checkbox",
                    choice.checkbox("Slow-mover candidates"));
            assertNotNull(choice.checkbox("Variable stars"));
            var rescue = choice.checkbox("Local rescue candidates");
            var motion = choice.checkbox("Common-motion groups");
            assertNotNull(rescue);
            assertNotNull(motion);
            rescue.doClick();
            assertTrue(choice.hasLabel("The report will include 54 of these detections."));
            motion.doClick();
            assertTrue(choice.hasLabel("The report will include 2 of these detections."));
            var inclusion = choice.inclusion();
            assertFalse(inclusion.localRescue);
            assertFalse(inclusion.commonMotion);
            assertTrue(inclusion.anomalies);
            assertTrue(inclusion.variableStars);
            assertTrue(config.enableResidualTransientAnalysis);
            assertTrue(config.enableLocalRescueCandidates);
            assertTrue(config.enableCommonMotionRejection);
        });
    }

    /** Keeps every optional group independently selectable after extracting the dialog. */
    @Test
    public void everyOptionalCheckboxControlsItsMatchingCategory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var summary = new DetectionSummary(
                    46, 2, 1, 4, 3, 5, 7, 11, "READY", 1, 1, 13, 0, 17);
            var config = new DetectionConfig();
            config.enableLocalActivityClusters = true;
            config.enableVariableStarDetection = true;
            var choice = new Choice(summary, config);
            for (String row : new String[]{"Single-frame anomalies", "Slow-mover candidates", "Local rescue candidates",
                    "Local activity clusters", "Common-motion groups", "Camera-fixed groups", "Variable stars"}) {
                var checkbox = choice.checkbox(row);
                assertNotNull("Missing checkbox beside " + row, checkbox);
                checkbox.doClick();
            }
            var inclusion = choice.inclusion();
            assertFalse(inclusion.anomalies);
            assertFalse(inclusion.slowMovers);
            assertFalse(inclusion.localRescue);
            assertFalse(inclusion.localActivity);
            assertFalse(inclusion.commonMotion);
            assertFalse(inclusion.variableStars);
            assertFalse(inclusion.cameraFixed);
            assertTrue(choice.hasLabel("The report will include 7 of these detections."));
        });
    }


    /** Checks worker-to-EDT dispatch, accepted choices and unchanged detection settings. */
    @Test(timeout = 10_000)
    public void acceptedChoicesAreAppliedAfterTheEdtAnswers() {
        DetectionSummary summary = summary();
        DetectionConfig config = new DetectionConfig();
        GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(panel -> {
            assertTrue(SwingUtilities.isEventDispatchThread());
            new Choice(panel).checkbox("Single-frame anomalies").setSelected(false);
            return JOptionPane.YES_OPTION;
        });
        assertTrue(prompt.confirm(summary, true, 50, "Iterative pass: 5 frames", config));
        assertFalse(summary.reportInclusion().anomalies);
        assertTrue(config.enableAnomalyRescue);
        assertEquals(60, summary.totalDetections);
    }

    /** A caller already on the EDT must not invokeAndWait or deadlock. */
    @Test(timeout = 10_000)
    public void confirmationCanRunDirectlyOnTheEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(panel -> JOptionPane.YES_OPTION);
            assertTrue(prompt.confirm(summary(), false, 50, null, new DetectionConfig()));
        });
    }

    /** Low counts and interrupted workers never enqueue a modal question. */
    @Test
    public void smallReportsAndInterruptedWorkersDoNotAsk() {
        AtomicBoolean asked = new AtomicBoolean();
        GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(panel -> {
            asked.set(true);
            return JOptionPane.YES_OPTION;
        });
        assertTrue(prompt.confirm(summary(), false, 60, null, null));
        Thread.currentThread().interrupt();
        try {
            assertFalse(prompt.confirm(summary(), false, 50, null, null));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(asked.get());
    }

    /** Interrupting a waiting worker declines immediately and never applies a later accepted answer. */
    @Test(timeout = 20_000)
    public void interruptionWhileTheQuestionIsOpenPreservesTheFlagAndInclusion() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var done = new java.util.concurrent.CountDownLatch(1);
        AtomicBoolean proceeded = new AtomicBoolean(true);
        AtomicBoolean interrupted = new AtomicBoolean();
        DetectionSummary summary = summary();
        ReportInclusion before = summary.reportInclusion();
        GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(panel -> {
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Question not released");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new AssertionError(failure);
            }
            return JOptionPane.YES_OPTION;
        });
        Thread worker = new Thread(() -> {
            proceeded.set(prompt.confirm(summary, false, 50, null, new DetectionConfig()));
            interrupted.set(Thread.currentThread().isInterrupted());
            done.countDown();
        });
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            worker.interrupt();
            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(proceeded.get());
            assertTrue(interrupted.get());
        } finally {
            release.countDown();
            worker.join(5_000);
        }
        SwingUtilities.invokeAndWait(() -> assertSame(before, summary.reportInclusion()));
    }

    /** No and window dismissal discard unchecked choices rather than changing future reports. */
    @Test
    public void declinedChoicesAreNotApplied() {
        for (int answer : new int[]{JOptionPane.NO_OPTION, JOptionPane.CLOSED_OPTION}) {
            DetectionSummary summary = summary();
            ReportInclusion before = summary.reportInclusion();
            GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(panel -> {
                new Choice(panel).checkbox("Single-frame anomalies").setSelected(false);
                return answer;
            });
            assertFalse(prompt.confirm(summary, false, 50, null, new DetectionConfig()));
            assertSame(before, summary.reportInclusion());
        }
    }

    /** Disabled analyses and unrun photometry are omitted, even when stale counts are supplied. */
    @Test
    public void disabledGroupsAreNotOffered() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DetectionConfig config = new DetectionConfig();
            config.enableAnomalyRescue = false;
            config.enableResidualTransientAnalysis = false;
            config.enableVariableStarDetection = false;
            Choice choice = new Choice(summary(), config);
            assertFalse(choice.hasRow("Single-frame anomalies"));
            assertFalse(choice.hasRow("Local rescue candidates"));
            assertFalse(choice.hasRow("Local activity clusters"));
            assertFalse(choice.hasRow("Variable stars"));
        });
    }

    /** Creates a count snapshot above the desktop safety threshold. */
    private static DetectionSummary summary() {
        return new DetectionSummary(60, 0, 0, 1, 59, 0, 0, 0, "READY", 2, 1, 0, 0, 0);
    }

    /** States the photometry readiness and, without a plate solution, that the report has no sky coordinates. */
    @Test
    public void photometryReadinessAndMissingAstrometryAreStated() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var config = new DetectionConfig();
            config.enableVariableStarDetection = true;
            var notReady = new Choice(new DetectionSummary(
                    60, 0, 0, 60, 0, 0, 0, 0, "NOT_READY", 0, 0, 0, 0, 0), config);
            assertTrue(notReady.hasText("session not ready for photometry"));
            assertTrue(notReady.hasText("no plate-solved frame, so the report has no sky coordinates or identification."));
            var ready = new Choice(new DetectionSummary(
                    80, 2, 1, 4, 50, 5, 6, 2, "READY", 3, 1, 4, 12, 3), config);
            assertTrue(ready.hasText("3 candidates, 1 possible"));
            assertTrue(ready.hasText("Largest group: single-frame anomalies (50)."));
        });
    }

    /** Builds and inspects real dialog contents on the EDT without reflective access or modal windows. */
    private static final class Choice {
        private final GuiDetectionReportPrompt.ReportChoice choice;
        private final JComponent panel;
        private final JPanel grid;

        /** Builds the adapter's actual content for one run. */
        Choice(DetectionSummary summary, DetectionConfig config) {
            this(new GuiDetectionReportPrompt(component -> JOptionPane.YES_OPTION).warningPanel(summary, false, null, config));
        }

        /** Inspects an already built dialog passed to the confirmation boundary. */
        Choice(JComponent panel) {
            this(new GuiDetectionReportPrompt.ReportChoice(panel, null, null, null, null, null, null, null));
        }

        /** Retains the panel and its category grid for row-specific assertions. */
        private Choice(GuiDetectionReportPrompt.ReportChoice choice) {
            this.choice = choice;
            this.panel = choice.panel;
            this.grid = (JPanel) Arrays.stream(panel.getComponents())
                    .filter(component -> component instanceof JPanel candidate && candidate.getLayout() instanceof GridBagLayout)
                    .findFirst().orElseThrow();
        }

        /** Finds the optional choice in the named category's row, or null when the count is zero. */
        JCheckBox checkbox(String label) {
            GridBagLayout layout = (GridBagLayout) grid.getLayout();
            int row = Arrays.stream(grid.getComponents())
                    .filter(component -> component instanceof JLabel name && name.getText().equals(label))
                    .mapToInt(component -> layout.getConstraints(component).gridy).findFirst().orElseThrow();
            return (JCheckBox) Arrays.stream(grid.getComponents())
                    .filter(component -> component instanceof JCheckBox && layout.getConstraints(component).gridy == row)
                    .findFirst().orElse(null);
        }

        /** Reports whether an enabled category has a row. */
        boolean hasRow(String text) {
            return Arrays.stream(grid.getComponents())
                    .anyMatch(component -> component instanceof JLabel label && text.equals(label.getText()));
        }

        /** Reads each optional report category from the adapter's controls. */
        ReportInclusion inclusion() {
            return choice.inclusion();
        }

        /** Finds the changing included-count line among the surrounding explanation labels. */
        /** Whether a label or wrapped text anywhere in the dialog, including the category grid, contains {@code text}. */
        boolean hasText(String text) {
            return contains(panel, text);
        }

        private static boolean contains(java.awt.Container container, String text) {
            for (var component : container.getComponents()) {
                if (component instanceof JLabel label && label.getText() != null && label.getText().contains(text)) return true;
                if (component instanceof javax.swing.text.JTextComponent area && area.getText().contains(text)) return true;
                if (component instanceof java.awt.Container child && contains(child, text)) return true;
            }
            return false;
        }

        boolean hasLabel(String text) {
            return Arrays.stream(panel.getComponents())
                    .anyMatch(component -> component instanceof JLabel label && text.equals(label.getText()));
        }
    }
}
