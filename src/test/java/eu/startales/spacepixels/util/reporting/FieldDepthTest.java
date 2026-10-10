package eu.startales.spacepixels.util.reporting;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FieldDepthTest {

    @Test
    public void theDepthIsWhereHalfAsManyStarsAreFound() {
        // All stars found to G 15, none fainter: the share falls through half at 15.
        double[] depth = FieldDepth.depth(stars(1.0, 15.0, 1));
        assertEquals(15.0, depth[0], 0.3);
        assertEquals(0, depth[1], 0);
    }

    @Test
    public void aCrowdedFieldIsMeasuredAgainstItsOwnBestShare() {
        // Blending keeps even bright stars at 70 % found; the depth is still where the share halves, at 14.
        double[] depth = FieldDepth.depth(stars(0.7, 14.0, 2));
        assertEquals(14.0, depth[0], 0.3);
    }

    @Test
    public void aFrameDeeperThanTheStarsFetchedIsSaidToBe() {
        double[] depth = FieldDepth.depth(stars(1.0, 30.0, 3));
        assertEquals(1, depth[1], 0);
        assertTrue(depth[0] > 20);
    }

    @Test
    public void tooFewStarsOrFoundGiveNoDepth() {
        assertTrue(Double.isNaN(FieldDepth.depth(new ArrayList<>())[0]));
        assertTrue(Double.isNaN(FieldDepth.depth(stars(0.1, 15.0, 4))[0]));
    }

    /** Stars from G 10 to 20.5, more of them fainter, found with this chance up to the limit and never beyond. */
    private static List<double[]> stars(double share, double limit, long seed) {
        Random random = new Random(seed);
        List<double[]> stars = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            double g = 10 + 10.5 * Math.sqrt(random.nextDouble());
            stars.add(new double[]{g, g < limit && random.nextDouble() < share ? 1 : 0});
        }
        return stars;
    }
}
