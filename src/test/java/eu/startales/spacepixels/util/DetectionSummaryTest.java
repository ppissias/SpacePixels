package eu.startales.spacepixels.util;

import org.junit.Test;
import io.github.ppissias.jtransient.config.DetectionConfig;
import javax.swing.*;
import java.awt.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DetectionSummaryTest {

    @Test
    public void warningKeepsSlowMoversAndLocalRescueApart() {
        ImageProcessing.DetectionSummary summary = new ImageProcessing.DetectionSummary(
                77, 2, 1, 4, 50, 5, 6, 2, "READY", 3, 1, 4, 12, 7);
        String message = summary.warningMessage(true, 50);

        assertTrue(message.contains("Slow-mover candidates (maximum stack): 5"));
        assertTrue(message.contains("Local rescue candidates: 6"));
        assertFalse("no combined slow-mover figure", message.contains(": 11"));
        assertTrue(message.contains("Variable stars: 3 candidates, 1 possible"));
        assertTrue(message.contains("a plate-solved frame is available"));
        assertTrue(message.contains("Largest group: single-frame anomalies (50)"));
        assertTrue(message.contains("Common-motion groups: 4"));
        assertTrue(message.contains("Still unclassified, not counted above: 12"));
        assertTrue(message.contains("Single-frame anomalies: 50 (7 low confidence)"));
    }

    @Test
    public void warningSaysWhenPhotometryAndAstrometryAreMissing() {
        ImageProcessing.DetectionSummary off = new ImageProcessing.DetectionSummary(
                60, 0, 0, 60, 0, 0, 0, 0, null, 0, 0, 0, 0, 0);
        assertTrue(off.warningMessage(false, 50).contains("Variable stars: off"));
        assertTrue(off.warningMessage(false, 50).contains("no plate-solved frame"));

        ImageProcessing.DetectionSummary notReady = new ImageProcessing.DetectionSummary(
                60, 0, 0, 60, 0, 0, 0, 0, "NOT_READY", 0, 0, 0, 0, 0);
        assertTrue(notReady.warningMessage(false, 50).contains("not ready for photometry"));
    }

    @Test
    public void reportChoiceIsOfferedOnlyWhenTheDetectionRanThatGroup() {
        assertFalse(ImageProcessing.DetectionSummary.offerInReport(false, 40));
        assertFalse(ImageProcessing.DetectionSummary.offerInReport(true, 0));
        assertTrue(ImageProcessing.DetectionSummary.offerInReport(true, 40));
    }

    @Test
    public void localRescueAndCommonMotionCanBeUncheckedOnTheirOwnRows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var summary = new ImageProcessing.DetectionSummary(
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

    @Test
    public void everyOptionalCheckboxControlsItsMatchingCategory() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var summary = new ImageProcessing.DetectionSummary(
                    46, 2, 1, 4, 3, 5, 7, 11, "READY", 1, 1, 13, 0, 0);
            var config = new DetectionConfig();
            config.enableLocalActivityClusters = true;
            config.enableVariableStarDetection = true;
            var choice = new Choice(summary, config);
            for (String row : new String[]{"Single-frame anomalies", "Slow-mover candidates", "Local rescue candidates",
                    "Local activity clusters", "Common-motion groups", "Variable stars"}) {
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
            assertTrue(choice.hasLabel("The report will include 7 of these detections."));
        });
    }

    /** Build the real dialog contents on the EDT without displaying a modal window. */
    private static final class Choice {
        final Object choice;
        final JPanel panel;
        final JPanel grid;
        Choice(ImageProcessing.DetectionSummary summary, DetectionConfig config) {
            try {
                var factory = summary.getClass().getDeclaredMethod("warningPanel", boolean.class, String.class, DetectionConfig.class);
                factory.setAccessible(true);
                choice = factory.invoke(summary, false, null, config);
                var field = choice.getClass().getDeclaredField("panel"); field.setAccessible(true);
                panel = (JPanel) field.get(choice);
                grid = (JPanel) java.util.Arrays.stream(panel.getComponents())
                        .filter(c -> c instanceof JPanel p && p.getLayout() instanceof GridBagLayout).findFirst().orElseThrow();
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        JCheckBox checkbox(String label) {
            GridBagLayout layout = (GridBagLayout) grid.getLayout();
            int row = java.util.Arrays.stream(grid.getComponents())
                    .filter(c -> c instanceof JLabel l && l.getText().equals(label))
                    .mapToInt(c -> layout.getConstraints(c).gridy).findFirst().orElseThrow();
            return (JCheckBox) java.util.Arrays.stream(grid.getComponents())
                    .filter(c -> c instanceof JCheckBox && layout.getConstraints(c).gridy == row)
                    .findFirst().orElse(null);
        }
        ReportInclusion inclusion() {
            try {
                var method = choice.getClass().getDeclaredMethod("inclusion"); method.setAccessible(true);
                return (ReportInclusion) method.invoke(choice);
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        boolean hasLabel(String text) {
            return java.util.Arrays.stream(panel.getComponents()).anyMatch(c -> c instanceof JLabel l && text.equals(l.getText()));
        }
    }
}
