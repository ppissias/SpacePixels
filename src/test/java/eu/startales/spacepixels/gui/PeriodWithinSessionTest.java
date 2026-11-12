/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.skycatalog.SkyCatalogue;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The orange known-variable rule and the note beside Known variables. */
public class PeriodWithinSessionTest {

    private static final long THREE_HOURS = 3L * 3_600_000L;

    @Test
    public void aPeriodOfTwiceTheSpanIsIncludedAndALongerOneIsNot() {
        assertTrue(PeriodWithinSession.qualifies(star(6.0 / 24.0, null), THREE_HOURS));
        assertFalse(PeriodWithinSession.qualifies(star(6.0 / 24.0 + 1.0 / 24.0, null), THREE_HOURS));
        assertFalse(PeriodWithinSession.qualifies(star(null, null), THREE_HOURS));
        assertFalse(PeriodWithinSession.qualifies(star(1.0 / 24.0, null), 0));
        assertFalse(PeriodWithinSession.qualifies(star(1.0 / 24.0, null), -1));
    }

    @Test
    public void aLowerLimitIsLeftOutAndTheUncertaintyDoesNotMoveTheCut() {
        assertFalse(PeriodWithinSession.qualifies(star(6.0 / 24.0, ">"), THREE_HOURS));
        assertFalse(PeriodWithinSession.qualifies(star(6.0 / 24.0, " >= "), THREE_HOURS));
        SkyCatalogue.VariableStar upper = star(6.0 / 24.0, "<");
        assertTrue(PeriodWithinSession.qualifies(upper, THREE_HOURS));
        SkyCatalogue.VariableStar uncertain = star(6.0 / 24.0, null);
        uncertain.periodUncertainty = ":";
        assertTrue(PeriodWithinSession.qualifies(uncertain, THREE_HOURS));
    }

    @Test
    public void theNoteGivesTheCountAndTheSpan() {
        assertEquals("12 of the known variables on this frame. This session spans 3.0 h, "
                        + "so the orange diamonds have a period of 6.0 h or less.",
                PeriodWithinSession.note(true, true, 12, THREE_HOURS));
        assertEquals("1 of the known variables on this frame. This session spans 3.0 h, "
                        + "so the orange diamonds have a period of 6.0 h or less.",
                PeriodWithinSession.note(true, true, 1, THREE_HOURS));
        assertTrue(PeriodWithinSession.note(false, false, 0, -1).startsWith("Fetch Sky Catalogue"));
        assertTrue(PeriodWithinSession.note(true, true, 0, -1).startsWith("This session has no exposure times"));
        assertTrue(PeriodWithinSession.note(true, false, 0, THREE_HOURS).contains("no plate solution"));
    }

    private static SkyCatalogue.VariableStar star(Double periodDays, String periodLimit) {
        SkyCatalogue.VariableStar star = new SkyCatalogue.VariableStar();
        star.periodDays = periodDays;
        star.periodLimit = periodLimit;
        return star;
    }
}
