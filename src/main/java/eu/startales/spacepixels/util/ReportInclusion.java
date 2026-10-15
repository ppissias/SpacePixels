/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */

package eu.startales.spacepixels.util;

/**
 * Which optional result groups a report should contain. Unchecked groups were measured and are left out of
 * this report only. The detection settings are unchanged.
 */
public final class ReportInclusion {

    public final boolean anomalies;
    public final boolean slowMovers;
    public final boolean localRescue;
    public final boolean localActivity;
    public final boolean commonMotion;
    public final boolean variableStars;

    public ReportInclusion(boolean anomalies,
                           boolean slowMovers,
                           boolean localRescue,
                           boolean localActivity,
                           boolean commonMotion,
                           boolean variableStars) {
        this.anomalies = anomalies;
        this.slowMovers = slowMovers;
        this.localRescue = localRescue;
        this.localActivity = localActivity;
        this.commonMotion = commonMotion;
        this.variableStars = variableStars;
    }

    /** Every optional group is written. */
    public static ReportInclusion all() {
        return new ReportInclusion(true, true, true, true, true, true);
    }
}
