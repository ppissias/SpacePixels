package eu.startales.spacepixels.report;

import static org.junit.Assert.assertEquals;

import io.github.ppissias.jtransient.model.AnomalyDetection;
import io.github.ppissias.jtransient.model.AnomalyType;
import io.github.ppissias.jtransient.model.DetectedObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Checks the shared anomaly numbering order: most significant first, deterministic, engine list untouched. */
public class AnomalyOrderTest {

    /** Integrated sigma ranks first; peak sigma and area break ties; unmeasured values and missing objects go last. */
    @Test
    public void ordersByIntegratedSigmaThenPeakThenArea() {
        var weak = anomaly(0, 10, 30, 4);
        var strong = anomaly(5, 80, 12, 40);
        var tiedSharp = anomaly(1, 40, 50, 6);
        var tiedBroad = anomaly(2, 40, 20, 30);
        var tiedBroadLarger = anomaly(3, 40, 20, 31);
        var unmeasured = anomaly(0, Double.NaN, 90, 9);
        var noObject = new AnomalyDetection(null, AnomalyType.PEAK_SIGMA);
        List<AnomalyDetection> engine = new ArrayList<>(Arrays.asList(
                unmeasured, weak, noObject, tiedBroad, strong, null, tiedBroadLarger, tiedSharp));
        var engineBefore = new ArrayList<>(engine);

        var ordered = AnomalyOrder.sorted(engine);

        assertEquals(List.of(strong, tiedSharp, tiedBroadLarger, tiedBroad, weak, unmeasured, noObject), ordered);
        assertEquals(engineBefore, engine);
    }

    /**
     * A long thin event outranks a brighter compact one when its length makes up for it: the score is integrated
     * sigma times the square root of the footprint length, and an unmeasured length counts as one pixel.
     */
    @Test
    public void longFootprintsRankByIntegratedSigmaTimesRootLength() {
        var compact = anomaly(0, 300, 200, 20);
        compact.object.majorExtent = 6;
        var worm = anomaly(1, 220, 30, 220);
        worm.object.majorExtent = 64;
        var faintBlob = anomaly(2, 44, 6, 220);
        faintBlob.object.majorExtent = 32;
        var unmeasuredLength = anomaly(3, 250, 40, 9);
        unmeasuredLength.object.majorExtent = Double.NaN;

        assertEquals(220 * 8, AnomalyOrder.score(worm), 1e-9);
        assertEquals(250, AnomalyOrder.score(unmeasuredLength), 1e-9);
        assertEquals(List.of(worm, compact, unmeasuredLength, faintBlob),
                AnomalyOrder.sorted(List.of(faintBlob, unmeasuredLength, compact, worm)));
    }

    /** The mosaic shows up to 8 × 5 anomalies when there are more than one row or some have no card. */
    @Test
    public void mosaicTilesFollowTheCountsAndTheCards() {
        assertEquals(0, AnomalyOrder.mosaicTiles(0, 0));
        assertEquals(0, AnomalyOrder.mosaicTiles(8, 8));
        assertEquals(8, AnomalyOrder.mosaicTiles(8, 3));
        assertEquals(9, AnomalyOrder.mosaicTiles(9, 9));
        assertEquals(40, AnomalyOrder.mosaicTiles(1200, 10));
    }

    /** Equal measurements fall back to frame, then row, then column. */
    @Test
    public void breaksFullTiesByFrameAndPosition() {
        var later = anomaly(4, 20, 20, 9);
        var lowerRow = anomaly(2, 20, 20, 9);
        lowerRow.object.y = 50;
        var upperRow = anomaly(2, 20, 20, 9);
        upperRow.object.y = 10;

        assertEquals(List.of(upperRow, lowerRow, later), AnomalyOrder.sorted(List.of(later, lowerRow, upperRow)));
        assertEquals(List.of(), AnomalyOrder.sorted(null));
    }

    /** Builds one measured anomaly on the given frame. */
    private static AnomalyDetection anomaly(int frame, double integratedSigma, double peakSigma, double area) {
        var point = new DetectedObject(100, 100, 1000, (int) area);
        point.sourceFrameIndex = frame;
        point.integratedSigma = integratedSigma;
        point.peakSigma = peakSigma;
        point.pixelArea = area;
        return new AnomalyDetection(point, AnomalyType.PEAK_SIGMA);
    }
}
