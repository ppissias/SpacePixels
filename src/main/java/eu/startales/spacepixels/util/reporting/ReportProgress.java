/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util.reporting;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;

/**
 * The progress of writing one report, from 0 to 99 %: each step has a share of the bar, and a step with many items
 * (cutouts, cards) reports each one. Every report is also a point where a cancelled job stops, so the slow steps
 * report often. A listener may throw to stop the report; nothing here catches it.
 */
final class ReportProgress {

    /** For a report written without a listener. */
    static final ReportProgress NONE = new ReportProgress(null);

    private final TransientEngineProgressListener listener;
    private int from;
    private int to;
    private String step = "";
    private int count;
    private int done;

    ReportProgress(TransientEngineProgressListener listener) {
        this.listener = listener;
    }

    /** Starts a step, which runs from {@code from} to {@code to} percent. */
    void step(int from, int to, String step) {
        this.from = from;
        this.to = to;
        this.step = step;
        count = 0;
        done = 0;
        report(from, "Writing the report: " + step + "...");
    }

    /** Says how many items the current step writes; {@link #next} then reports each one. */
    void expect(int count) {
        this.count = count;
        done = 0;
    }

    /** The next item of the current step is being written. */
    void next() {
        if (done >= count) {
            return;
        }
        report(from + (int) ((to - from) * (long) done / count),
                "Writing the report: " + step + " (" + (done + 1) + " of " + count + ")...");
        done++;
    }

    private void report(int percent, String message) {
        if (listener != null) {
            listener.onProgressUpdate(Math.max(0, Math.min(99, percent)), message);
        }
    }
}
