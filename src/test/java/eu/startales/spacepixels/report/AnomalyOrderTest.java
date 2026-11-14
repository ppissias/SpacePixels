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
