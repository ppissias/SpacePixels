package eu.startales.spacepixels.gui;

import io.github.ppissias.jplatesolve.PlateSolveResult;
import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Verifies the user-visible rejection text across the library's optional reason/warning contract. */
public class PlateSolveFailureMessageTest {
    /** An absent warning never appears as the literal word null. */
    @Test
    public void missingWarningLeavesOnlyTheReason() {
        assertEquals("No solution", MainApplicationPanel.plateSolveFailureMessage(
                new PlateSolveResult(false, "No solution", "", Map.of())));
    }

    /** A real backend warning remains visible alongside a rejection or a fallback reason. */
    @Test
    public void realWarningsAndMissingReasonsStayUseful() {
        assertEquals("No solution Too few stars", MainApplicationPanel.plateSolveFailureMessage(
                new PlateSolveResult(false, "No solution", "Too few stars", Map.of())));
        assertEquals("The solver returned no solution. Too few stars", MainApplicationPanel.plateSolveFailureMessage(
                new PlateSolveResult(false, null, "Too few stars", Map.of())));
        assertEquals("The solver returned no solution.", MainApplicationPanel.plateSolveFailureMessage(
                new PlateSolveResult(false, null, null, Map.of())));
    }
}
