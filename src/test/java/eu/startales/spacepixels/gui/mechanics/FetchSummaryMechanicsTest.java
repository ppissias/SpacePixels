package eu.startales.spacepixels.gui.mechanics;

import org.assertj.swing.edt.GuiActionRunner;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * The fetch-summary indicator on the main window. Run with {@code ./gradlew guiMechanicsTest}.
 */
public class FetchSummaryMechanicsTest extends MechanicsTestCase {

    /** A freshly imported sequence has no catalogue, so the summary control stays hidden. */
    @Test(timeout = 120_000)
    public void theIndicatorStaysHiddenUntilAFetchHasSomethingToShow() throws Exception {
        importSyntheticSequence();
        assertEquals(0, GuiActionRunner.execute(() ->
                window().robot().finder().findAll(window().target(), VisibleText.button("ⓘ")).size()).intValue());
    }
}
