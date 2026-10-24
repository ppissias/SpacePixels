package eu.startales.spacepixels.util.reporting;

import eu.startales.spacepixels.util.DetectionSummary;
import eu.startales.spacepixels.util.ReportInclusion;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Test;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Protects compatibility IDs, anchors, category counts and independent report-only inclusion switches. */
public class FindingCategoryTest {
    /** Category prefixes are unique and S/ST, variables and artifact anchors retain their existing identities. */
    @Test public void idsAnchorsAndMapMembershipRemainStable() {
        Set<String> prefixes = new HashSet<>();
        for (FindingCategory category : FindingCategory.values()) {
            assertTrue(prefixes.add(category.prefix()));
            assertSame(category, FindingCategory.fromId(category.id(12)));
            assertThrows(IllegalArgumentException.class, () -> category.id(0));
            assertThrows(IllegalArgumentException.class, () -> category.anchor(0));
        }
        assertEquals("finding-st1", FindingCategory.STREAK_TRACK.anchor(1));
        assertEquals("finding-s1", FindingCategory.SINGLE_STREAK.anchor(1));
        assertEquals("variable-v3", FindingCategory.VARIABLE_STAR.anchor(3));
        assertEquals("finding-fc2", FindingCategory.CAMERA_FIXED.anchor(2));
        assertEquals(12, FindingCategory.STREAK_TRACK.number("ST12"));
        assertEquals("finding-st12", FindingCategory.anchorForId("ST12"));
        assertEquals("variable-candidates", FindingCategory.VARIABLE_STAR.anchor(12, false));
        assertEquals("variable-v12", FindingCategory.VARIABLE_STAR.anchor(12, true));
        assertThrows(IllegalArgumentException.class, () -> FindingCategory.SINGLE_STREAK.number("ST12"));
        assertThrows(IllegalArgumentException.class, () -> FindingCategory.anchorForId("V0"));
        assertThrows(IllegalArgumentException.class, () -> FindingCategory.VARIABLE_STAR.anchor(0, false));
        assertFalse(FindingCategory.CAMERA_FIXED.onDetectionMap());
        assertFalse(FindingCategory.COMMON_MOTION.onDetectionMap());
        assertTrue(FindingCategory.VARIABLE_STAR.onDetectionMap());
        for (String malformed : new String[]{"S", "ST0", "T-1", "A01", "Unknown1"}) {
            assertThrows(IllegalArgumentException.class, () -> FindingCategory.fromId(malformed));
        }
    }

    /** Each report option maps to exactly its measured category; photometry keeps both confidence tiers. */
    @Test public void summaryCountsAndInclusionUseTheSameCategories() {
        var summary = new DetectionSummary(0, 2, 3, 5, 7, 11, 13, 17, "READY", 19, 23, 29, 0, 31);
        assertEquals(5, FindingCategory.MOVING_TARGET.count(summary));
        assertEquals(3, FindingCategory.STREAK_TRACK.count(summary));
        assertEquals(2, FindingCategory.SINGLE_STREAK.count(summary));
        assertEquals(7, FindingCategory.ANOMALY.count(summary));
        assertEquals(11, FindingCategory.SLOW_MOVER.count(summary));
        assertEquals(13, FindingCategory.LOCAL_RESCUE.count(summary));
        assertEquals(17, FindingCategory.LOCAL_ACTIVITY.count(summary));
        assertEquals(42, FindingCategory.VARIABLE_STAR.count(summary));
        assertEquals(29, FindingCategory.COMMON_MOTION.count(summary));
        assertEquals(31, FindingCategory.CAMERA_FIXED.count(summary));
        ReportInclusion none = new ReportInclusion(false, false, false, false, false, false, false);
        for (FindingCategory category : FindingCategory.values()) {
            assertEquals(!category.optional(), category.included(none));
            assertTrue(category.included(ReportInclusion.all()));
        }
        ReportInclusion anomalies = new ReportInclusion(true, false, false, false, false, false, false);
        for (FindingCategory category : FindingCategory.values()) {
            assertEquals(!category.optional() || category == FindingCategory.ANOMALY, category.included(anomalies));
        }
    }

    /** Disabled residual analyses hide their rows while independently enabled common-motion filtering remains visible. */
    @Test public void computedRowsPreserveIndependentFiltersAndPhotometryVerdict() {
        DetectionConfig config = new DetectionConfig();
        config.enableResidualTransientAnalysis = false;
        config.enableLocalRescueCandidates = true;
        config.enableCommonMotionRejection = true;
        config.enableVariableStarDetection = true;
        var summary = new DetectionSummary(0, 0, 0, 0, 0, 0, 0, 0, "NOT_RUN", 0, 0, 0, 0, 0);
        assertFalse(FindingCategory.LOCAL_RESCUE.computed(config, summary));
        assertTrue(FindingCategory.COMMON_MOTION.computed(config, summary));
        assertFalse(FindingCategory.VARIABLE_STAR.computed(config, summary));
        assertEquals(new java.awt.Color(77, 166, 255), FindingCategory.MOVING_TARGET.color());
        assertEquals(new java.awt.Color(255, 90, 210), FindingCategory.VARIABLE_STAR.color());
    }
}
