package eu.startales.spacepixels.report;

import io.github.ppissias.jtransient.model.AnomalyDetection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The one order in which single-frame anomalies are numbered (A1, A2, …), shared by the report and the viewers so an
 * anomaly keeps its identity in both. The most significant come first: the highest integrated sigma, the
 * signal-to-noise of the whole footprint, which grows with both brightness and size. Ties go to the higher peak
 * sigma, then the larger area, then frame and position, so the order is deterministic. Never changes the engine's list.
 */
public final class AnomalyOrder {
    private static final Comparator<AnomalyDetection> MOST_SIGNIFICANT_FIRST = Comparator
            .comparingDouble((AnomalyDetection a) -> descending(a.object == null ? Double.NaN : a.object.integratedSigma))
            .thenComparingDouble(a -> descending(a.object == null ? Double.NaN : a.object.peakSigma))
            .thenComparingDouble(a -> descending(a.object == null ? Double.NaN : a.object.pixelArea))
            .thenComparingInt(a -> a.object == null ? Integer.MAX_VALUE : a.object.sourceFrameIndex)
            .thenComparingDouble(a -> a.object == null ? Double.POSITIVE_INFINITY : a.object.y)
            .thenComparingDouble(a -> a.object == null ? Double.POSITIVE_INFINITY : a.object.x);

    /** Prevents construction of this ordering utility. */
    private AnomalyOrder() { }

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
