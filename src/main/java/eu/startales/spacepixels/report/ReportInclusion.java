/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

/**
 * Which optional result groups a report should contain. Unchecked groups were measured and are left out of
 * this report only. The detection settings are unchanged.
 */
public final class ReportInclusion {

    /** {@link #anomalyCards} when no choice was made: the report settings' per-category limit applies. */
    public static final int SETTINGS_CARD_LIMIT = -1;

    public final boolean anomalies;
    public final boolean slowMovers;
    public final boolean localRescue;
    public final boolean localActivity;
    public final boolean commonMotion;
    public final boolean cameraFixed;
    public final boolean variableStars;
    public final boolean faintMovers;
    /**
     * How many single-frame anomaly cards to draw, as chosen in the report dialog: the first ones in
     * {@link AnomalyOrder}; zero draws none (the mosaic stays). {@link #SETTINGS_CARD_LIMIT} leaves it to the report
     * settings' per-category limit. The data and the overlays keep every anomaly either way.
     */
    public final int anomalyCards;

    public ReportInclusion(boolean anomalies,
                           boolean slowMovers,
                           boolean localRescue,
                           boolean localActivity,
                           boolean commonMotion,
                           boolean cameraFixed,
                           boolean variableStars, boolean faintMovers) {
        this(anomalies, slowMovers, localRescue, localActivity, commonMotion, cameraFixed, variableStars, faintMovers, SETTINGS_CARD_LIMIT);
    }

    private ReportInclusion(boolean anomalies, boolean slowMovers, boolean localRescue, boolean localActivity,
                            boolean commonMotion, boolean cameraFixed, boolean variableStars, boolean faintMovers,
                            int anomalyCards) {
        this.anomalies = anomalies;
        this.slowMovers = slowMovers;
        this.localRescue = localRescue;
        this.localActivity = localActivity;
        this.commonMotion = commonMotion;
        this.cameraFixed = cameraFixed;
        this.variableStars = variableStars;
        this.faintMovers = faintMovers;
        this.anomalyCards = Math.max(SETTINGS_CARD_LIMIT, anomalyCards);
    }

    /** The same groups, drawing only the first {@code cards} anomaly cards ({@link #SETTINGS_CARD_LIMIT}: the settings decide). */
    public ReportInclusion withAnomalyCards(int cards) {
        return new ReportInclusion(anomalies, slowMovers, localRescue, localActivity, commonMotion, cameraFixed,
                variableStars, faintMovers, cards);
    }

    /**
     * How many of {@code total} anomaly cards to draw: the dialog's choice when one was made, else the settings'
     * per-category limit ({@code settingsLimit}, zero for none).
     */
    public int anomalyCardCount(int total, int settingsLimit) {
        if (anomalyCards >= 0) return Math.min(anomalyCards, total);
        return settingsLimit > 0 && total > settingsLimit ? settingsLimit : total;
    }

    /** Every optional group is written. */
    public static ReportInclusion all() {
        return new ReportInclusion(true, true, true, true, true, true, true, true);
    }
}
