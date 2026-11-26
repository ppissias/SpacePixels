package eu.startales.spacepixels.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for linked-night table and export-note formatting in {@link AsteroidsCometsWindow}.
 */
public class AsteroidsCometsWindowHelpersTest {

    /**
     * Table rows distinguish linked nights included in the ADES file from linked nights omitted from it.
     */
    @Test
    public void linkedNightsSuffixClarifiesIncludedAndOmittedNights() {
        assertEquals("", AsteroidsCometsWindow.linkedNightsSuffix(0, 0));
        assertEquals(" · +1 night", AsteroidsCometsWindow.linkedNightsSuffix(1, 1));
        assertEquals(" · +2 nights", AsteroidsCometsWindow.linkedNightsSuffix(2, 2));
        assertEquals(" · +1 night (1 not in file)", AsteroidsCometsWindow.linkedNightsSuffix(2, 1));
        assertEquals(" · 1 linked night (not in file)", AsteroidsCometsWindow.linkedNightsSuffix(1, 0));
        assertEquals(" · 3 linked nights (not in file)", AsteroidsCometsWindow.linkedNightsSuffix(3, 0));
    }

    /**
     * Export notes state singular and plural omitted linked nights clearly.
     */
    @Test
    public void omittedNightsPhraseHandlesSingularAndPlural() {
        assertEquals("this night only; 1 linked night is not in this file",
                AsteroidsCometsWindow.omittedNightsPhrase(1));
        assertEquals("this night only; 2 linked nights are not in this file",
                AsteroidsCometsWindow.omittedNightsPhrase(2));
    }

    /**
     * Nearby &amp; MPC ITF matches info text explains both Offset and Motion calculations and key uncertainties.
     */
    @Test
    public void nearbyMatchesExplanationCoversOffsetMotionAndUncertainties() {
        String text = AsteroidsCometsWindow.nearbyMatchesExplanationText();
        assertTrue(text.contains("Identify all"));
        assertTrue(text.contains("MPC ITF"));
        assertTrue(text.contains("Offset"));
        assertTrue(text.contains("Δv"));
        assertTrue(text.contains("ΔPA"));
        assertTrue(text.contains("curvature"));
        assertTrue(text.contains("parallax"));
        assertTrue(text.contains("3σ"));
        assertTrue(text.contains("itf.txt.gz"));
    }
}
