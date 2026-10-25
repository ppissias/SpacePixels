package eu.startales.spacepixels.detection;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A picture is made only when a sink on this thread wants one, and making it can never fail the run. */
public class RunPreviewScopeTest {

    private static final class RecordingSink implements RunPreviewSink {
        boolean wants = true;
        final List<Boolean> asked = new ArrayList<>();
        final List<RunPreview> received = new ArrayList<>();

        @Override
        public boolean wantsPreview(boolean stageChange) {
            asked.add(stageChange);
            return wants;
        }

        @Override
        public void preview(RunPreview preview) {
            received.add(preview);
        }
    }

    private static RunPreview picture(String caption) {
        return RunPreview.of(new BufferedImage(4, 3, BufferedImage.TYPE_BYTE_GRAY), caption);
    }

    @Test
    public void withoutAScopeNoPictureIsMade() {
        AtomicBoolean made = new AtomicBoolean();
        RunPreviewScope.offer(true, () -> {
            made.set(true);
            return picture("unused");
        });
        assertFalse("headless and API runs do no preview work", made.get());
    }

    @Test
    public void aSinkThatDeclinesSavesTheWorkOfMakingThePicture() {
        RecordingSink sink = new RecordingSink();
        sink.wants = false;
        AtomicBoolean made = new AtomicBoolean();
        try (RunPreviewScope scope = RunPreviewScope.open(sink)) {
            RunPreviewScope.offer(false, () -> {
                made.set(true);
                return picture("unused");
            });
        }
        assertFalse(made.get());
        assertEquals(List.of(false), sink.asked);
        assertTrue(sink.received.isEmpty());
    }

    @Test
    public void aWantedPictureReachesTheSinkWithItsStage() {
        RecordingSink sink = new RecordingSink();
        try (RunPreviewScope scope = RunPreviewScope.open(sink)) {
            RunPreviewScope.offer(true, () -> picture("Frame 1 of 5"));
            RunPreviewScope.offer(false, () -> RunPreview.captionOnly("running"));
        }
        assertEquals(List.of(true, false), sink.asked);
        assertEquals("Frame 1 of 5", sink.received.get(0).caption());
        assertTrue(sink.received.get(1).keepImage());
    }

    @Test
    public void aPictureThatCannotBeMadeIsSkippedAndTheRunGoesOn() {
        RecordingSink sink = new RecordingSink();
        try (RunPreviewScope scope = RunPreviewScope.open(sink)) {
            RunPreviewScope.offer(true, () -> {
                throw new IllegalStateException("no pixels");
            });
            RunPreviewScope.offer(true, () -> picture("next"));
        }
        assertEquals(1, sink.received.size());
        assertEquals("next", sink.received.get(0).caption());
    }

    @Test
    public void scopesNestAndRestoreTheOuterSink() {
        RecordingSink outer = new RecordingSink();
        RecordingSink inner = new RecordingSink();
        try (RunPreviewScope outerScope = RunPreviewScope.open(outer)) {
            try (RunPreviewScope innerScope = RunPreviewScope.open(inner)) {
                RunPreviewScope.offer(true, () -> picture("inner"));
            }
            RunPreviewScope.offer(true, () -> picture("outer"));
        }
        RunPreviewScope.offer(true, () -> picture("none"));
        assertEquals("inner", inner.received.get(0).caption());
        assertEquals(1, outer.received.size());
        assertEquals("outer", outer.received.get(0).caption());
    }

    @Test
    public void aScopeClosesOnlyOnItsOwnThread() throws Exception {
        RunPreviewScope scope = RunPreviewScope.open(new RecordingSink());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread other = new Thread(() -> failure.set(assertThrows(IllegalStateException.class, scope::close)));
        other.start();
        other.join();
        scope.close();
        assertTrue(failure.get() instanceof IllegalStateException);
    }
}
