package eu.startales.spacepixels.gui.mechanics;

import eu.startales.spacepixels.gui.GuiDetectionReportPrompt;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.assertj.swing.core.GenericTypeMatcher;
import org.assertj.swing.edt.GuiActionRunner;
import org.assertj.swing.finder.JFileChooserFinder;
import org.assertj.swing.finder.WindowFinder;
import org.assertj.swing.fixture.DialogFixture;
import org.junit.Test;

import javax.swing.JDialog;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Drives the extracted report question and explicit GUI import adapter through real modal dialogs. */
public class ReportPromptMechanicsTest extends MechanicsTestCase {
    /** Accepts and declines the real report dialog, preserving report-only choices and iterative headings. */
    @Test(timeout = 120_000)
    public void reportConfirmationAcceptsChoicesAndDeclinesWithoutMutatingSettings() throws Exception {
        GuiDetectionReportPrompt prompt = new GuiDetectionReportPrompt(application().getFrame());
        DetectionConfig config = new DetectionConfig();
        ImageProcessing.DetectionSummary summary = new ImageProcessing.DetectionSummary(
                60, 0, 0, 1, 59, 0, 0, 0, null, 0, 0, 0, 0, 0);
        CompletableFuture<Boolean> accepted = startReportQuestion(prompt, summary, true, "Iterative pass: 5 frames", config);
        DialogFixture dialog = dialog("Detection summary");
        dialog.label(VisibleText.labelContaining("Iterative pass: 5 frames — This run found 60 detections.")).requireVisible();
        dialog.checkBox(VisibleText.checkBox("In report")).uncheck();
        dialog.label(VisibleText.labelContaining("The report will include 1 of these detections.")).requireVisible();
        dialog.button(VisibleText.button("Yes")).click();
        assertTrue(accepted.get(15, TimeUnit.SECONDS));
        assertFalse(summary.reportInclusion().anomalies);
        assertTrue(config.enableAnomalyRescue);

        CompletableFuture<Boolean> declined = startReportQuestion(prompt, summary, false, null, config);
        dialog("Detection summary").button(VisibleText.button("No")).click();
        assertFalse(declined.get(15, TimeUnit.SECONDS));
        assertFalse(summary.reportInclusion().anomalies);
    }

    /** The application still asks about conversion now that its import job receives the GUI adapter explicitly. */
    @Test(timeout = 180_000)
    public void floatImportStillOffersConversionAndLeavesTheCurrentSessionIntactWhenDeclined() throws Exception {
        importSyntheticSequence();
        var original = application().getSession();
        Path sequence = SyntheticDatasetFactory.createMono32FloatFitsSequence(folder.getRoot().toPath(), "float", 5);
        window().button(VisibleText.button("Import Aligned Frames…")).click();
        var chooser = JFileChooserFinder.findFileChooser().withTimeout(15_000).using(robot());
        chooser.selectFile(sequence.toFile());
        chooser.approve();
        dialog("Format Conversion Required").button(VisibleText.button("No")).click();
        waitFor("declined import releases the foreground job", () -> application().getJobCoordinator().current() == null);
        assertSame(original, application().getSession());
    }

    /** Opens the question inside a real foreground job after its modal progress window is visible. */
    private CompletableFuture<Boolean> startReportQuestion(GuiDetectionReportPrompt prompt,
            ImageProcessing.DetectionSummary summary, boolean solved, String heading, DetectionConfig config) {
        CountDownLatch ready = new CountDownLatch(1);
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        var admission = GuiActionRunner.execute(() -> application().getJobCoordinator().start(JobKind.DETECTION,
                application().getSession(), context -> {
                    if (!ready.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Progress window did not open");
                    return JobOutcome.completed(prompt.confirm(summary, solved, 50, heading, config));
                }, (handle, outcome) -> {
                    if (outcome instanceof JobOutcome.Completed<Boolean> completed) answer.complete(completed.result());
                    else answer.completeExceptionally(new AssertionError(outcome));
                }));
        assertTrue(admission.admitted());
        try {
            dialog("Processing...").requireVisible();
        } finally {
            ready.countDown();
        }
        return answer;
    }

    /** Finds the visible modal question by its user-facing title rather than component names. */
    private DialogFixture dialog(String title) {
        return WindowFinder.findDialog(new GenericTypeMatcher<>(JDialog.class) {
            /** Matches the showing question and excludes the job's progress window. */
            @Override protected boolean isMatching(JDialog dialog) {
                return dialog.isShowing() && title.equals(dialog.getTitle());
            }
        }).using(robot());
    }
}
