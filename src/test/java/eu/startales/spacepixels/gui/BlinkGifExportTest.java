package eu.startales.spacepixels.gui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import javax.imageio.ImageIO;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

/** GIF export streams one frame at a time, preserves timing/order and never replaces a file with partial output. */
public class BlinkGifExportTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    /** The live viewer can change after admission; export keeps its original region, loop order and timing. */
    @Test public void selectionAndWorkerCaptureViewportAndIncludedFrames() throws Exception {
        File target = new File(temporary.getRoot(), "selected.gif");
        var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<BlinkGifExport.Result>>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView(); view.setSize(240, 180);
            short[][] first = new short[90][120], last = new short[90][120];
            for (int y = 0; y < 90; y++) for (int x = 0; x < 120; x++) { first[y][x] = (short) (16000 - 32768); last[y][x] = (short) (48000 - 32768); }
            var a = eu.startales.spacepixels.fits.BlinkSequence.fromMono(
                    new eu.startales.spacepixels.fits.FitsFileInformation("first.fit", "first.fit", true, 120, 90), first);
            var c = eu.startales.spacepixels.fits.BlinkSequence.fromMono(
                    new eu.startales.spacepixels.fits.FitsFileInformation("third.fit", "third.fit", true, 120, 90), last);
            view.showFrame(a, new byte[][]{eu.startales.spacepixels.fits.DisplayStretch.linearLookupTable()}, false);
            var settings = new StretchControls.Snapshot(false, eu.startales.spacepixels.fits.StretchAlgorithm.values()[0], 0, 0);
            var source = new java.util.concurrent.atomic.AtomicReference<>(new BlinkGifExport.Source(java.util.List.of(
                    new BlinkGifExport.FrameInput(a, java.util.List.of()), new BlinkGifExport.FrameInput(c, java.util.List.of())), settings, 205));
            BlinkGifExport export = new BlinkGifExport(view, source::get, () -> { });
            var layer = (javax.swing.JLayer<?>) export.image(); layer.setSize(240, 180); layer.doLayout();
            export.setAvailable(true); export.toggleSelection();
            var glass = layer.getGlassPane();
            glass.dispatchEvent(new java.awt.event.MouseEvent(glass, java.awt.event.MouseEvent.MOUSE_PRESSED, 1,
                    java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 20, 30, 1, false, java.awt.event.MouseEvent.BUTTON1));
            // A release alone also finalizes the rectangle when motion events have been coalesced.
            glass.dispatchEvent(new java.awt.event.MouseEvent(glass, java.awt.event.MouseEvent.MOUSE_RELEASED, 2,
                    0, 120, 100, 1, false, java.awt.event.MouseEvent.BUTTON1));
            future.set(export.exportTo(target));
            source.set(new BlinkGifExport.Source(java.util.List.of(new BlinkGifExport.FrameInput(c, java.util.List.of())), settings, 1000));
            view.zoomToScale(4); view.setSize(100, 80);
        });
        BlinkGifExport.Result result = future.get().get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(2, result.frames()); assertEquals(100, result.width()); assertEquals(70, result.height()); assertEquals(210, result.delayMillis());
        try (var input = ImageIO.createImageInputStream(target)) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try { reader.setInput(input); assertEquals(2, reader.getNumImages(true));
                assertTrue((reader.read(0).getRGB(50, 35) & 255) < (reader.read(1).getRGB(50, 35) & 255));
            } finally { reader.dispose(); }
        }
    }

    /** Closing/reloading the viewer cancels pending work and suppresses publication over a previous complete file. */
    @Test public void releaseCancelsAnAdmittedExportWithoutReplacingItsTarget() throws Exception {
        File target = new File(temporary.getRoot(), "previous.gif");
        var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        BlinkGifExport.writeGif(target, 1, 100, index -> image, () -> false, count -> { });
        byte[] previous = Files.readAllBytes(target.toPath());
        var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<BlinkGifExport.Result>>();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView(); view.setSize(100, 80);
            var frame = eu.startales.spacepixels.fits.BlinkSequence.fromMono(
                    new eu.startales.spacepixels.fits.FitsFileInformation("frame.fit", "frame.fit", true, 10, 10), new short[10][10]);
            view.showFrame(frame, new byte[][]{eu.startales.spacepixels.fits.DisplayStretch.linearLookupTable()}, false);
            var source = new BlinkGifExport.Source(java.util.Collections.nCopies(100,
                    new BlinkGifExport.FrameInput(frame, java.util.List.of())),
                    new StretchControls.Snapshot(false, eu.startales.spacepixels.fits.StretchAlgorithm.values()[0], 0, 0), 100);
            BlinkGifExport export = new BlinkGifExport(view, () -> source, () -> { });
            var layer = (javax.swing.JLayer<?>) export.image(); layer.setSize(100, 80); layer.doLayout();
            export.setAvailable(true); export.toggleSelection(); var glass = layer.getGlassPane();
            glass.dispatchEvent(new java.awt.event.MouseEvent(glass, java.awt.event.MouseEvent.MOUSE_PRESSED, 1, 0, 10, 10, 1, false, java.awt.event.MouseEvent.BUTTON1));
            glass.dispatchEvent(new java.awt.event.MouseEvent(glass, java.awt.event.MouseEvent.MOUSE_RELEASED, 2, 0, 40, 40, 1, false, java.awt.event.MouseEvent.BUTTON1));
            future.set(export.exportTo(target)); export.release();
        });
        Throwable failure = future.get().handle((value, problem) -> problem).get(15, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(failure instanceof CancellationException);
        assertArrayEquals(previous, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new String[]{"previous.gif"}, temporary.getRoot().list());
    }

    /** Reused buffers are written immediately; one looping GIF has the chosen dimensions and nearest valid delay. */
    @Test public void writesOnlyTheGifWithCorrectOrderDimensionsTimingAndLoop() throws Exception {
        File target = new File(temporary.getRoot(), "blink.gif");
        BufferedImage reusable = new BufferedImage(51, 37, BufferedImage.TYPE_INT_RGB);
        int[] greys = {16, 80, 192};
        BlinkGifExport.writeGif(target, greys.length, 205, index -> {
            var graphics = reusable.createGraphics(); graphics.setColor(new Color(greys[index], greys[index], greys[index]));
            graphics.fillRect(0, 0, 51, 37); graphics.dispose(); return reusable;
        }, () -> false, count -> { });
        try (var input = ImageIO.createImageInputStream(target)) {
            var reader = ImageIO.getImageReadersByFormatName("gif").next();
            try {
                reader.setInput(input); assertEquals(3, reader.getNumImages(true));
                for (int index = 0; index < 3; index++) {
                    assertEquals(51, reader.getWidth(index)); assertEquals(37, reader.getHeight(index));
                    assertEquals(greys[index], reader.read(index).getRGB(20, 20) & 255);
                    var metadata = reader.getImageMetadata(index);
                    var tree = (IIOMetadataNode) metadata.getAsTree(metadata.getNativeMetadataFormatName());
                    var control = (IIOMetadataNode) tree.getElementsByTagName("GraphicControlExtension").item(0);
                    assertEquals("21", control.getAttribute("delayTime"));
                    if (index == 0) {
                        var application = (IIOMetadataNode) tree.getElementsByTagName("ApplicationExtension").item(0);
                        assertEquals("NETSCAPE", application.getAttribute("applicationID"));
                        assertArrayEquals(new byte[]{1, 0, 0}, (byte[]) application.getUserObject());
                    }
                }
            } finally { reader.dispose(); }
        }
        assertArrayEquals(new String[]{"blink.gif"}, temporary.getRoot().list());
    }

    /** Cancellation and renderer failure remove the staged file and leave a prior complete GIF byte-identical. */
    @Test public void failureAndCancellationPreservePreviousExport() throws Exception {
        File target = new File(temporary.getRoot(), "keep.gif");
        var frame = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        BlinkGifExport.writeGif(target, 1, 100, index -> frame, () -> false, count -> { });
        byte[] before = Files.readAllBytes(target.toPath());
        assertThrows(IOException.class, () -> BlinkGifExport.writeGif(target, 2, 100, index -> {
            if (index == 1) throw new IOException("Rendering failed"); return frame;
        }, () -> false, count -> { }));
        assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        AtomicBoolean cancelled = new AtomicBoolean();
        assertThrows(CancellationException.class, () -> BlinkGifExport.writeGif(target, 3, 100, index -> frame,
                cancelled::get, count -> cancelled.set(true)));
        assertArrayEquals(before, Files.readAllBytes(target.toPath()));
        assertArrayEquals(new String[]{"keep.gif"}, temporary.getRoot().list());
    }
}
