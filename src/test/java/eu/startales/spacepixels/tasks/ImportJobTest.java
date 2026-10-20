package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Import runs as a job: a ready folder gives the new session, and a folder without frames fails with the reason. */
public class ImportJobTest {

    private static final int FRAME_COUNT = 4;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void aReadyFolderBecomesTheImportedSession() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "mono16", FRAME_COUNT);
        JobOutcome<FitsImportTask.Result> outcome = outcomeOf(new FitsImportTask(sequence.toFile()));
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Completed<?>);
        FitsImportTask.Result session = ((JobOutcome.Completed<FitsImportTask.Result>) outcome).result();
        try {
            assertNotNull(session.processing());
            assertEquals(FRAME_COUNT, session.files().length);
        } finally {
            session.processing().close();
        }
    }

    @Test
    public void aFolderWithoutFramesFailsWithTheReason() throws Exception {
        JobOutcome<FitsImportTask.Result> outcome = outcomeOf(new FitsImportTask(folder.newFolder("empty")));
        assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Failed<?> failed
                && failed.reason().startsWith("No fits files in directory"));
    }

    /** Runs the job under a coordinator that works on the calling thread, and returns its one outcome. */
    private static <R> JobOutcome<R> outcomeOf(Job<R> job) {
        List<JobOutcome<R>> outcomes = new ArrayList<>();
        new JobCoordinator(Runnable::run, Runnable::run, () -> false).start(JobKind.IMPORT, null, job, (handle, outcome) -> outcomes.add(outcome));
        assertEquals(1, outcomes.size());
        return outcomes.get(0);
    }
}
