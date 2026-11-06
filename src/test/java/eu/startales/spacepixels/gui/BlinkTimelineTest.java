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

import org.junit.Test;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** Checks Blink's gap rule and the timeline's placement of frames by capture time. */
public class BlinkTimelineTest {

    private static final long MINUTE = 60_000;

    /** A gap is an interval longer than 1.5 times the median; frames without times have none. */
    @Test public void gapsFollowTheMedianInterval() {
        long[] times = {MINUTE, 3 * MINUTE, 5 * MINUTE, 7 * MINUTE, 12 * MINUTE, 14 * MINUTE};
        boolean[] gaps = BlinkTimeline.gaps(times);
        assertArrayEquals(new boolean[]{false, false, false, false, true, false}, gaps);
        assertArrayEquals("an interval of exactly 1.5 times the median is no gap",
                new boolean[4], BlinkTimeline.gaps(new long[]{MINUTE, 3 * MINUTE, 5 * MINUTE, 8 * MINUTE}));
        assertArrayEquals("a longer one is", new boolean[]{false, false, false, true},
                BlinkTimeline.gaps(new long[]{MINUTE, 3 * MINUTE, 5 * MINUTE, 8 * MINUTE + 1}));
        assertArrayEquals(new boolean[4], BlinkTimeline.gaps(new long[]{MINUTE, 0, 5 * MINUTE, 9 * MINUTE}));
        assertArrayEquals(new boolean[2], BlinkTimeline.gaps(new long[]{MINUTE, 9 * MINUTE}));
        assertEquals("1:02:03", BlinkTimeline.clock(3_723_000));
        assertEquals("37:17", BlinkTimeline.clock(2_237_000));
    }

    /** Ticks sit by capture time, so a click lands on the frame after a gap, not on its neighbour by index. */
    @Test public void clicksSelectTheFrameAtThatTime() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            BlinkTimeline timeline = new BlinkTimeline();
            timeline.setSize(416, 40);
            long[] times = {MINUTE, 2 * MINUTE, 3 * MINUTE, 11 * MINUTE};
            timeline.setFrames(times, new boolean[4], BlinkTimeline.gaps(times));
            int left = UiStyle.space(8);
            int width = 416 - 2 * left;
            assertEquals(2, timeline.nearest(left + width / 5));
            assertEquals(3, timeline.nearest(left + width));
            assertEquals("between frames far from any tick", -1, timeline.nearest(left + width / 2));
            AtomicInteger selected = new AtomicInteger(-1);
            timeline.setOnSelect(selected::set);
            assertNotNull(timeline.getAccessibleContext());
        });
    }

    /** The transport bar keeps one row while it fits and moves the speed to a second row when narrow. */
    @Test public void transportMovesTheSpeedBelowWhenNarrow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComponent buttons = fixed(150, 28), place = fixed(120, 20), speed = fixed(300, 28);
            BlinkTimeline timeline = new BlinkTimeline();
            JPanel transport = new JPanel(new TransportLayout(buttons, timeline, place, speed));
            transport.add(buttons);
            transport.add(timeline);
            transport.add(place);
            transport.add(speed);
            transport.setSize(1400, 80);
            transport.doLayout();
            assertTrue("one row", speed.getY() < buttons.getY() + buttons.getHeight());
            assertTrue(timeline.getWidth() > 0);
            int minimum = transport.getMinimumSize().width;
            transport.setSize(minimum, 80);
            transport.doLayout();
            assertTrue("the speed on a second row", speed.getY() >= buttons.getY() + buttons.getHeight());
            assertTrue("nothing is cut off", place.getX() + place.getWidth() <= minimum && speed.getWidth() <= minimum);
            assertTrue(timeline.getWidth() > 0);
        });
    }

    private static JComponent fixed(int width, int height) {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new java.awt.Dimension(width, height));
        return panel;
    }
}
