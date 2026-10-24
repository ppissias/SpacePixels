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

import eu.startales.spacepixels.util.FitsVisualizationRenderer;
import eu.startales.spacepixels.util.StretchAlgorithm;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Renders the previews of the Image Stretch tab one request at a time, as Preview Frame prepares its frames. A request
 * made while another renders waits, replacing any request already waiting, and a result is shown only if its request
 * is still the newest one; so a slow render of an earlier frame or stretch can never replace a later one. All methods
 * are called on the event thread.
 */
final class StretchPreviewRenderer {

    /** What to preview: a frame of a session and a stretch. Every request is a new object; only the newest is shown. */
    static final class Request {
        /** Draws the previews; it keeps no state. */
        final FitsVisualizationRenderer renderer;
        final String filePath;
        final StretchAlgorithm algorithm;
        final int strength;
        final int iterations;
        final boolean stretchEnabled;

        Request(FitsVisualizationRenderer renderer, String filePath, StretchAlgorithm algorithm, int strength, int iterations) {
            this(renderer, filePath, algorithm, strength, iterations, true);
        }

        Request(FitsVisualizationRenderer renderer, String filePath, StretchAlgorithm algorithm, int strength, int iterations,
                boolean stretchEnabled) {
            this.renderer = renderer;
            this.filePath = filePath;
            this.algorithm = algorithm;
            this.strength = strength;
            this.iterations = iterations;
            this.stretchEnabled = stretchEnabled;
        }
    }

    /** The previews of a request with their histograms; the images are null when it could not be rendered. */
    static final class Previews {
        final Request request;
        final BufferedImage original;
        final BufferedImage stretched;
        final int[] originalHistogram;
        final int[] stretchedHistogram;

        Previews(Request request, BufferedImage original, BufferedImage stretched, int[] originalHistogram, int[] stretchedHistogram) {
            this.request = request;
            this.original = original;
            this.stretched = stretched;
            this.originalHistogram = originalHistogram;
            this.stretchedHistogram = stretchedHistogram;
        }

        boolean isRendered() {
            return original != null && stretched != null;
        }
    }

    /** Renders a request; runs on a worker thread. */
    interface Render {
        Previews render(Request request) throws Exception;
    }

    private final Render render;
    private final Consumer<Previews> show;
    private Request wanted;
    private SwingWorker<Previews, Void> worker;

    StretchPreviewRenderer(Render render, Consumer<Previews> show) {
        this.render = render;
        this.show = show;
    }

    /** Renders this request next, after the render in progress, if any; an older waiting request is dropped. */
    void request(Request request) {
        wanted = request;
        if (worker == null) {
            start(request);
        }
    }

    /** Shows nothing of the requests made so far, for example when the frame, stretch or session changes. */
    void cancel() {
        wanted = null;
    }

    private void start(Request request) {
        worker = new SwingWorker<Previews, Void>() {
            @Override
            protected Previews doInBackground() throws Exception {
                return render.render(request);
            }

            @Override
            protected void done() {
                if (worker != this) {
                    return;
                }
                worker = null;
                if (wanted == null) {
                    return;
                }
                if (wanted != request) {
                    start(wanted);
                    return;
                }
                Previews previews;
                try {
                    previews = get();
                } catch (ExecutionException e) {
                    ApplicationWindow.logger.log(Level.WARNING, "Preview generation failed", e.getCause() != null ? e.getCause() : e);
                    previews = new Previews(request, null, null, null, null);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                show.accept(previews);
            }
        };
        worker.execute();
    }
}
