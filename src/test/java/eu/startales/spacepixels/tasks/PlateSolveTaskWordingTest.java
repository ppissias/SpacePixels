package eu.startales.spacepixels.tasks;

import io.github.ppissias.jplatesolve.PlateSolveOperation;
import org.junit.Test;

import java.time.Duration;

import static org.junit.Assert.assertEquals;

/** A local solve's progress names the local solver, not Astrometry.net. */
public class PlateSolveTaskWordingTest {
    @Test
    public void theLocalSolverIsNamed() {
        assertEquals("Uploading image to the local solver", word("Uploading image to Astrometry.net"));
        assertEquals("The local solver is solving", word("Astrometry.net is solving"));
        assertEquals("Preparing plate solve", word("Preparing plate solve"));
    }

    private static String word(String message) {
        return PlateSolveTask.localWording(new PlateSolveOperation.Progress(PlateSolveOperation.Phase.SOLVING,
                Duration.ZERO, message)).message();
    }
}
