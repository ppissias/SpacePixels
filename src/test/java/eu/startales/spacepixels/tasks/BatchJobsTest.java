package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.FitsBatchService;
import eu.startales.spacepixels.util.FitsVisualizationRenderer;
import eu.startales.spacepixels.util.StretchAlgorithm;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Convert to Mono and Batch Stretch run as jobs and write their new folders. */
public class BatchJobsTest {

    private static final int FRAME_COUNT = 3;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void convertToMonoReturnsTheFolderOfTheMonoFrames() throws Exception {
        Path sequence = SyntheticDatasetFactory.createColor16FitsSequence(folder.getRoot().toPath(), "color16", FRAME_COUNT);
        FitsBatchService batch = new FitsBatchService(sequence.toFile(), new FitsVisualizationRenderer());
        JobOutcome<File> outcome = outcomeOf(new BatchConvertMonoTask(batch, false, 1, 1, StretchAlgorithm.values()[0]));
        File mono = ((JobOutcome.Completed<File>) outcome).result();
        assertNotNull(mono);
        assertEquals(FRAME_COUNT, mono.listFiles().length);
    }

    @Test
    public void batchStretchWritesAStretchedCopyOfEveryFrame() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", FRAME_COUNT);
        FitsBatchService batch = new FitsBatchService(sequence.toFile(), new FitsVisualizationRenderer());
        JobOutcome<Void> outcome = outcomeOf(new BatchStretchTask(batch, 1, 1, StretchAlgorithm.values()[0]));
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Completed<Void>);
        File[] stretched = sequence.toFile().listFiles(file -> file.isDirectory() && file.getName().contains("stretched"));
        assertNotNull(stretched);
        assertEquals(1, stretched.length);
        assertEquals(FRAME_COUNT, stretched[0].listFiles().length);
    }

    /** Runs the job under a coordinator that works on the calling thread, and returns its one outcome. */
    private static <R> JobOutcome<R> outcomeOf(Job<R> job) {
        List<JobOutcome<R>> outcomes = new ArrayList<>();
        new JobCoordinator(Runnable::run, Runnable::run, () -> false).start(JobKind.BATCH_STRETCH, null, job, (handle, outcome) -> outcomes.add(outcome));
        assertEquals(1, outcomes.size());
        return outcomes.get(0);
    }
}
