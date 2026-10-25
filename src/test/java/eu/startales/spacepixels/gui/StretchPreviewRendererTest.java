package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.StretchAlgorithm;
import org.junit.Test;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/** Only the newest request's previews are shown, however the renders are timed. */
public class StretchPreviewRendererTest {

    private final Map<String, CountDownLatch> held = new ConcurrentHashMap<>();
    private final List<String> rendered = new CopyOnWriteArrayList<>();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger mostAtOnce = new AtomicInteger();
    private final LinkedBlockingQueue<StretchPreviewRenderer.Previews> shown = new LinkedBlockingQueue<>();
    private final StretchPreviewRenderer renderer = new StretchPreviewRenderer(this::render, shown::add);

    @Test
    public void anOlderRequestFinishingLastIsNotShown() throws Exception {
        CountDownLatch releaseA = hold("a.fit");
        StretchPreviewRenderer.Request a = request("a.fit");
        StretchPreviewRenderer.Request b = request("b.fit");

        onEdt(() -> renderer.request(a));
        waitUntilRendering("a.fit");
        onEdt(() -> renderer.request(b));
        releaseA.countDown();

        StretchPreviewRenderer.Previews previews = next();
        assertSame(b, previews.request);
        assertEquals("b.fit", previews.stretched.getProperty("file"));
        assertEquals("b.fit", previews.original.getProperty("file"));
        assertArrayEquals(histogram("b.fit"), previews.stretchedHistogram);
        assertArrayEquals(histogram("b.fit"), previews.originalHistogram);
        assertEquals(List.of("a.fit", "b.fit"), rendered);
        assertEquals("one render at a time", 1, mostAtOnce.get());
        assertNull(shown.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void onlyTheNewestWaitingRequestIsRendered() throws Exception {
        CountDownLatch releaseA = hold("a.fit");
        onEdt(() -> renderer.request(request("a.fit")));
        waitUntilRendering("a.fit");
        onEdt(() -> {
            renderer.request(request("b.fit"));
            renderer.request(request("c.fit"));
        });
        releaseA.countDown();

        assertEquals("c.fit", next().request.filePath);
        assertEquals(List.of("a.fit", "c.fit"), rendered);
    }

    @Test
    public void aCancelledRenderIsNotShown() throws Exception {
        CountDownLatch releaseA = hold("a.fit");
        onEdt(() -> renderer.request(request("a.fit")));
        waitUntilRendering("a.fit");
        onEdt(renderer::cancel);
        releaseA.countDown();
        onEdt(() -> renderer.request(request("d.fit")));

        assertEquals("d.fit", next().request.filePath);
        assertNull(shown.poll(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void aFailedRenderIsShownAsNotRendered() throws Exception {
        onEdt(() -> renderer.request(request("broken.fit")));

        StretchPreviewRenderer.Previews previews = next();
        assertEquals("broken.fit", previews.request.filePath);
        assertFalse(previews.isRendered());
        assertNull(previews.stretchedHistogram);
    }

    private StretchPreviewRenderer.Previews render(StretchPreviewRenderer.Request request) throws Exception {
        mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
        try {
            rendered.add(request.filePath);
            CountDownLatch latch = held.get(request.filePath);
            if (latch != null && !latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
            if (request.filePath.startsWith("broken")) {
                throw new java.io.IOException("cannot read " + request.filePath);
            }
            return new StretchPreviewRenderer.Previews(request, image(request.filePath), image(request.filePath),
                    histogram(request.filePath), histogram(request.filePath));
        } finally {
            running.decrementAndGet();
        }
    }

    private CountDownLatch hold(String file) {
        CountDownLatch latch = new CountDownLatch(1);
        held.put(file, latch);
        return latch;
    }

    private void waitUntilRendering(String file) throws InterruptedException {
        long end = System.currentTimeMillis() + 5000;
        while (!rendered.contains(file) && System.currentTimeMillis() < end) {
            Thread.sleep(5);
        }
        assertEquals(List.of(file), rendered);
    }

    private StretchPreviewRenderer.Previews next() throws InterruptedException {
        StretchPreviewRenderer.Previews previews = shown.poll(10, TimeUnit.SECONDS);
        assertNotNull("nothing was shown", previews);
        return previews;
    }

    private static StretchPreviewRenderer.Request request(String file) {
        return new StretchPreviewRenderer.Request(null, file, StretchAlgorithm.ASINH, 10, 1);
    }

    /** A one-pixel image that names its frame. */
    private static BufferedImage image(String file) {
        java.util.Hashtable<String, Object> properties = new java.util.Hashtable<>();
        properties.put("file", file);
        BufferedImage template = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        return new BufferedImage(template.getColorModel(), template.getRaster(), false, properties);
    }

    private static int[] histogram(String file) {
        int[] bins = new int[256];
        bins[file.charAt(0)] = 1;
        return bins;
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }
}
