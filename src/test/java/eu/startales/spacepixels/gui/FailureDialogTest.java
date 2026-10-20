package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.BuildInfo;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertTrue;

/** The details a failure message copies: the message, the versions and the whole error. */
public class FailureDialogTest {

    @Test
    public void theDetailsHoldTheMessageTheVersionsAndTheError() {
        IOException cause = new IOException("disk full", new IllegalStateException("inner reason"));
        String details = FailureDialog.details("Detection failed: disk full", cause);
        assertTrue(details, details.startsWith("Detection failed: disk full\n\nSpacePixels " + BuildInfo.version("spacepixels")
                + " · JTransient " + BuildInfo.version("jtransient") + " · Java "));
        assertTrue(details, details.contains("java.io.IOException: disk full"));
        assertTrue(details, details.contains("Caused by: java.lang.IllegalStateException: inner reason"));
        assertTrue(details, details.contains("at eu.startales.spacepixels.gui.FailureDialogTest"));
    }

    @Test
    public void withoutAnErrorTheDetailsSaySo() {
        assertTrue(FailureDialog.details("Cannot stretch images: no frames", null).endsWith("No error was recorded.\n"));
    }
}
