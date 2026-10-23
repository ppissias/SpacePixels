/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Binds a run's {@link RunPreviewSink} to its thread, as JTransient's cancellation scope does, so the loading loop and
 * the report writers can offer pictures without passing the sink through every call. Without an open scope, as in
 * headless and API runs, an offer does nothing and no picture is made.
 */
public final class RunPreviewScope implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(RunPreviewScope.class.getName());
    private static final ThreadLocal<RunPreviewSink> CURRENT = new ThreadLocal<>();

    private final Thread owner = Thread.currentThread();
    private final RunPreviewSink previous;

    private RunPreviewScope(RunPreviewSink sink) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(sink, "sink"));
    }

    public static RunPreviewScope open(RunPreviewSink sink) {
        return new RunPreviewScope(sink);
    }

    /**
     * Makes a picture with {@code maker} and hands it over, only when this thread's sink wants one now. A picture that
     * cannot be made is skipped: a preview never fails or delays the run.
     */
    public static void offer(boolean stageChange, Supplier<RunPreview> maker) {
        RunPreviewSink sink = CURRENT.get();
        if (sink == null || !sink.wantsPreview(stageChange)) {
            return;
        }
        try {
            RunPreview preview = maker.get();
            if (preview != null) {
                sink.preview(preview);
            }
        } catch (RuntimeException failure) {
            LOGGER.log(Level.FINE, "Skipped a live-view picture", failure);
        }
    }

    @Override
    public void close() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("A preview scope must close on its opening thread.");
        }
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
