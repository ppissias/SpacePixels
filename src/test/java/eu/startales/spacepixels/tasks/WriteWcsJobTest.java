package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.jobs.Job;
import eu.startales.spacepixels.jobs.JobCoordinator;
import eu.startales.spacepixels.jobs.JobKind;
import eu.startales.spacepixels.jobs.JobOutcome;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import eu.startales.spacepixels.util.FitsImages;
import eu.startales.spacepixels.util.PlateSolveService;
import io.github.ppissias.jplatesolve.PlateSolveResult;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Writing the WCS solution runs as a job: it updates the frame's header and removes the solver's files either way. */
public class WriteWcsJobTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theSolutionIsWrittenIntoTheFrameAndTheSolverFilesAreRemoved() throws Exception {
        File frame = frame();
        File sidecar = sidecarOf(frame);
        try (PlateSolveService solver = new PlateSolveService(new AppConfig())) {
            JobOutcome<Map<String, String>> outcome = outcomeOf(new WriteWcsTask(solver, frame.getAbsolutePath(),
                    new PlateSolveResult(true, "", "", solution())));
            assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Completed<?>);
            assertEquals("50.5", ((JobOutcome.Completed<Map<String, String>>) outcome).result().get("CRPIX1"));
        }
        try (Fits fits = new Fits(frame)) {
            Header header = FitsImages.getImageHDU(fits).getHeader();
            assertEquals("RA---TAN", header.getStringValue("CTYPE1"));
        }
        assertFalse(sidecar.exists());
    }

    @Test
    public void aFailedWriteLeavesTheFrameAndStillRemovesTheSolverFiles() throws Exception {
        File frame = frame();
        File sidecar = sidecarOf(frame);
        byte[] before = Files.readAllBytes(frame.toPath());
        try (PlateSolveService solver = new PlateSolveService(new AppConfig())) {
            JobOutcome<Map<String, String>> outcome = outcomeOf(new WriteWcsTask(solver, frame.getAbsolutePath(),
                    new PlateSolveResult(true, "", "", new LinkedHashMap<>())));
            assertTrue(String.valueOf(outcome), outcome instanceof JobOutcome.Failed<?> failed
                    && failed.reason().equals("No WCS solution metadata was provided."));
        }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(frame.toPath())));
        assertFalse(sidecar.exists());
    }

    private File frame() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.getRoot().toPath(), "frames", 1);
        File[] frames = sequence.toFile().listFiles();
        assertEquals(1, frames.length);
        return frames[0];
    }

    /** A solver's leftover WCS file beside the frame, as ASTAP writes it. */
    private static File sidecarOf(File frame) throws Exception {
        String path = frame.getAbsolutePath();
        File sidecar = new File(path.substring(0, path.lastIndexOf('.')) + ".wcs");
        Files.writeString(sidecar.toPath(), "solver output");
        return sidecar;
    }

    private static Map<String, String> solution() {
        Map<String, String> solveInfo = new LinkedHashMap<>();
        solveInfo.put("source", "astap");
        solveInfo.put("WCSAXES", "2");
        solveInfo.put("CTYPE1", "RA---TAN");
        solveInfo.put("CTYPE2", "DEC--TAN");
        solveInfo.put("CUNIT1", "deg");
        solveInfo.put("CUNIT2", "deg");
        solveInfo.put("CRPIX1", "50.5");
        solveInfo.put("CRPIX2", "40.5");
        solveInfo.put("CRVAL1", "140.89806");
        solveInfo.put("CRVAL2", "-7.0669513");
        solveInfo.put("CD1_1", "0.000467862222");
        solveInfo.put("CD1_2", "0.0");
        solveInfo.put("CD2_1", "0.0");
        solveInfo.put("CD2_2", "-0.000467862222");
        return solveInfo;
    }

    /** Runs the job under a coordinator that works on the calling thread, and returns its one outcome. */
    private static <R> JobOutcome<R> outcomeOf(Job<R> job) {
        List<JobOutcome<R>> outcomes = new ArrayList<>();
        new JobCoordinator(Runnable::run, Runnable::run, () -> false).start(JobKind.WRITE_WCS, null, job, (handle, outcome) -> outcomes.add(outcome));
        assertEquals(1, outcomes.size());
        return outcomes.get(0);
    }
}
