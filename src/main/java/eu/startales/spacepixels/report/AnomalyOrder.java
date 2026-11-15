package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.AnomalyDetection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The one order in which single-frame anomalies are numbered (A1, A2, …), shared by the report and the viewers so an
 * anomaly keeps its identity in both. The most interesting come first, by {@link #score}: the integrated sigma (the
 * signal-to-noise of the whole footprint) times the square root of the footprint's length. For a thin line or worm of
 * a given brightness the score grows with its length, so long cosmic-ray tracks and faint trails rank beside bright
 * compact flashes instead of far below them, while faint noise blobs stay low. Ties go to the higher peak sigma, then
 * the larger area, then frame and position, so the order is deterministic. Never changes the engine's list.
 */
public final class AnomalyOrder {
    private static final Comparator<AnomalyDetection> MOST_SIGNIFICANT_FIRST = Comparator
            .comparingDouble((AnomalyDetection a) -> descending(score(a)))
            .thenComparingDouble(a -> descending(a.object == null ? Double.NaN : a.object.peakSigma))
            .thenComparingDouble(a -> descending(a.object == null ? Double.NaN : a.object.pixelArea))
            .thenComparingInt(a -> a.object == null ? Integer.MAX_VALUE : a.object.sourceFrameIndex)
            .thenComparingDouble(a -> a.object == null ? Double.POSITIVE_INFINITY : a.object.y)
            .thenComparingDouble(a -> a.object == null ? Double.POSITIVE_INFINITY : a.object.x);

    /** The report's anomaly mosaic: {@value #MOSAIC_COLUMNS} tiles across, at most {@value #MOSAIC_ROWS} rows. */
    public static final int MOSAIC_COLUMNS = 8;
    public static final int MOSAIC_ROWS = 5;

    /**
     * How many of {@code total} anomalies the report's mosaic shows, the first ones in this order: up to
     * {@value #MOSAIC_COLUMNS} × {@value #MOSAIC_ROWS} when there are more than fit one row or when some get no card
     * ({@code cards} fewer than {@code total}); else none.
     */
    public static int mosaicTiles(int total, int cards) {
        return total > MOSAIC_COLUMNS || cards < total ? Math.min(total, MOSAIC_COLUMNS * MOSAIC_ROWS) : 0;
    }

    /** Prevents construction of this ordering utility. */
    private AnomalyOrder() { }

    /**
     * The ranking score: integrated sigma times the square root of the footprint's length in pixels (its major extent,
     * at least one pixel; one pixel when unmeasured). NaN when the anomaly has no measured integrated sigma.
     */
    public static double score(AnomalyDetection anomaly) {
        if (anomaly == null || anomaly.object == null || !Double.isFinite(anomaly.object.integratedSigma)) return Double.NaN;
        double length = anomaly.object.majorExtent;
        return anomaly.object.integratedSigma * Math.sqrt(Double.isFinite(length) ? Math.max(1, length) : 1);
    }

    /** Returns a new list in numbering order; null entries and a null list are dropped. */
    public static List<AnomalyDetection> sorted(List<AnomalyDetection> anomalies) {
        var ordered = new ArrayList<AnomalyDetection>();
        if (anomalies != null) for (var anomaly : anomalies) if (anomaly != null) ordered.add(anomaly);
        ordered.sort(MOST_SIGNIFICANT_FIRST);
        return ordered;
    }

    /** Sorts larger values first and unmeasured (non-finite) values last. */
    private static double descending(double value) {
        return Double.isFinite(value) ? -value : Double.POSITIVE_INFINITY;
    }
}
