package eu.startales.spacepixels.tasks;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Out of memory leaves a task for the application's handler; every other failure becomes the task's failure event. */
public class TaskFailuresTest {

    @Test
    public void outOfMemoryIsFoundBehindWrappers() {
        assertTrue(TaskFailures.isOutOfMemory(new OutOfMemoryError()));
        assertTrue(TaskFailures.isOutOfMemory(new RuntimeException(new ExecutionException(new OutOfMemoryError()))));
        assertFalse(TaskFailures.isOutOfMemory(new StackOverflowError()));
        assertFalse(TaskFailures.isOutOfMemory(new IOException("disk full")));
    }

    @Test
    public void outOfMemoryIsThrownAgainAndOtherFailuresGiveTheirMessage() {
        OutOfMemoryError outOfMemory = new OutOfMemoryError();
        assertSame(outOfMemory, assertThrows(OutOfMemoryError.class, () -> TaskFailures.rethrowOutOfMemory(outOfMemory)));
        Exception wrapped = new Exception(outOfMemory);
        assertSame(wrapped, assertThrows(IllegalStateException.class, () -> TaskFailures.rethrowOutOfMemory(wrapped)).getCause());

        // An Error other than out of memory ends the task with its failure event like an exception does.
        assertEquals("StackOverflowError", TaskFailures.rethrowOutOfMemory(new StackOverflowError()));
        assertEquals("disk full", TaskFailures.rethrowOutOfMemory(new IOException("disk full")));
    }

    @Test
    public void aCauseChainThatLoopsEnds() {
        Exception first = new Exception("first");
        Exception second = new Exception("second", first);
        first.initCause(second);
        assertFalse(TaskFailures.isOutOfMemory(first));
    }
}
