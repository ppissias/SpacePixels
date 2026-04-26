/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class IterativeDetectionPipelineServiceTest {

    @Test
    public void sampleFrameIndicesPrefersTimeSpacingWhenItCanProduceUniqueSamples() {
        long[] times = {100L, 200L, 300L, 400L, 500L};

        List<Integer> sampled = IterativeDetectionPipelineService.sampleFrameIndices(times, true, times.length, 5);

        assertEquals(Arrays.asList(0, 1, 2, 3, 4), sampled);
    }

    @Test
    public void sampleFrameIndicesFallsBackToIndexSpacingWhenTimeSpacingCollapsesToDuplicates() {
        long[] times = {100L, 100L, 100L, 400L, 500L};

        List<Integer> sampled = IterativeDetectionPipelineService.sampleFrameIndices(times, true, times.length, 5);

        assertEquals(Arrays.asList(0, 1, 2, 3, 4), sampled);
    }

    @Test
    public void sampleFrameIndicesFallsBackToIndexSpacingWhenTimestampsAreUnavailable() {
        long[] times = {10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L};

        List<Integer> sampled = IterativeDetectionPipelineService.sampleFrameIndices(times, false, times.length, 5);

        assertEquals(Arrays.asList(0, 2, 4, 6, 8), sampled);
    }
}
