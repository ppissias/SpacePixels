package eu.startales.spacepixels.jobs;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Out of memory leaves a job for the application's handler; every other failure becomes the job's failure. */
public class JobFailuresTest {

    @Test
    public void outOfMemoryIsFoundBehindWrappers() {
        assertTrue(JobFailures.isOutOfMemory(new OutOfMemoryError()));
        assertTrue(JobFailures.isOutOfMemory(new RuntimeException(new ExecutionException(new OutOfMemoryError()))));
        assertFalse(JobFailures.isOutOfMemory(new StackOverflowError()));
        assertFalse(JobFailures.isOutOfMemory(new IOException("disk full")));
    }

    @Test
    public void outOfMemoryIsThrownAgainAndOtherFailuresGiveTheirMessage() {
        OutOfMemoryError outOfMemory = new OutOfMemoryError();
        assertSame(outOfMemory, assertThrows(OutOfMemoryError.class, () -> JobFailures.rethrowOutOfMemory(outOfMemory)));
        Exception wrapped = new Exception(outOfMemory);
        assertSame(wrapped, assertThrows(IllegalStateException.class, () -> JobFailures.rethrowOutOfMemory(wrapped)).getCause());

        // An Error other than out of memory ends the job with a failure like an exception does.
        assertEquals("StackOverflowError", JobFailures.rethrowOutOfMemory(new StackOverflowError()));
        assertEquals("disk full", JobFailures.rethrowOutOfMemory(new IOException("disk full")));
    }

    @Test
    public void engineCancellationIsRecognizedThroughWrappers() {
        assertTrue(JobFailures.isCancellation(new java.util.concurrent.CancellationException("engine cancelled")));
        assertTrue(JobFailures.isCancellation(new ExecutionException(new java.util.concurrent.CancellationException())));
        assertFalse(JobFailures.isCancellation(new IOException("worker failed")));
    }

    @Test
    public void aCauseChainThatLoopsEnds() {
        Exception first = new Exception("first");
        Exception second = new Exception("second", first);
        first.initCause(second);
        assertFalse(JobFailures.isOutOfMemory(first));
    }
}
