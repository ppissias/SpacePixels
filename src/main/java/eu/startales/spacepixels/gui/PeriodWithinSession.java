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

import java.util.Locale;

/**
 * Which fetched VSX stars are drawn orange: a published period at most twice the session's observing span.
 * A lower-limit period is not a period of that length, and the uncertainty flag does not move the cut.
 */
final class PeriodWithinSession {

    static final String TITLE = "Period within session";
    private static final double MILLIS_PER_DAY = 86_400_000.0;

    private PeriodWithinSession() {
    }

    /**
     * Whether {@code star} is drawn orange for an observing span of {@code spanMillis}
     * (first exposure start to last exposure end). A non-positive span qualifies none.
     */
    static boolean qualifies(SkyCatalogue.VariableStar star, long spanMillis) {
        if (star == null || spanMillis <= 0 || star.periodDays == null || !Double.isFinite(star.periodDays)
                || star.periodDays <= 0 || isLowerLimit(star.periodLimit)) {
            return false;
        }
        return star.periodDays * MILLIS_PER_DAY <= 2.0 * spanMillis;
    }

    /** The Known variables note for the current frame. {@code spanMillis} is negative when no frame has a time. */
    static String note(boolean catalogueFetched, boolean placed, int orangeOnFrame, long spanMillis) {
        if (!catalogueFetched) {
            return "Fetch Sky Catalogue in the main window (2 Astrometry) first. "
                    + "Orange diamonds are known variables whose published period is at most twice this session.";
        }
        if (spanMillis <= 0) {
            return "This session has no exposure times, so no known variable is drawn orange. "
                    + "Orange diamonds are known variables whose published period is at most twice the session.";
        }
        String span = formatDuration(spanMillis);
        String limit = formatDuration(spanMillis * 2);
        if (!placed) {
            return "This session spans " + span + ", so a known variable is drawn orange when its published period is "
                    + limit + " or less. This frame has no plate solution, so none are placed.";
        }
        String count = orangeOnFrame == 1 ? "1 of the known variables" : orangeOnFrame + " of the known variables";
        return count + " on this frame. This session spans " + span
                + ", so the orange diamonds have a period of " + limit + " or less.";
    }

    /** A VSX period limit of {@code >} (or {@code >=}): the true period may be longer than the published number. */
    private static boolean isLowerLimit(String periodLimit) {
        return periodLimit != null && periodLimit.trim().startsWith(">");
    }

    /** Hours from one hour up, otherwise minutes, otherwise seconds. */
    static String formatDuration(long millis) {
        if (millis >= 3_600_000L) {
            return String.format(Locale.US, "%.1f h", millis / 3_600_000.0);
        }
        if (millis >= 60_000L) {
            return String.format(Locale.US, "%.0f min", millis / 60_000.0);
        }
        return String.format(Locale.US, "%.0f s", Math.max(0, millis) / 1000.0);
    }
}
