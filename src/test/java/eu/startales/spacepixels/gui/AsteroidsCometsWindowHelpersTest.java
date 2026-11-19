package eu.startales.spacepixels.gui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
