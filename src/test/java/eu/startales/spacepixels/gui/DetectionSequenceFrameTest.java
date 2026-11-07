package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.testsupport.SyntheticDatasetFactory;
import io.github.ppissias.jtransient.config.DetectionConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;

import static org.junit.Assert.*;

/** What Preview Frame prepares for a frame, without opening its window. */
public class DetectionSequenceFrameTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    /** The stretch editor can show a colour session here: the frame in colour, without raw detections. */
    @Test public void aColourFrameIsShownWithoutRawDetections() throws Exception {
        Path sequence = SyntheticDatasetFactory.createColor16FitsSequence(folder.newFolder("colour").toPath(), "session", 2);
        SequenceViewerFrame.Result result = DetectionSequenceFrame.read(firstFrame(sequence, false), new DetectionConfig());
        assertNull("nothing went wrong", result.problem);
        assertEquals("shown in colour", 3, result.frame.getChannelCount());
        assertNull(result.detections);
        assertEquals("colour · no raw detections", result.summary);
    }

    /** A 16-bit monochrome frame is detected as before. */
    @Test public void aMonochromeFrameIsDetected() throws Exception {
        Path sequence = SyntheticDatasetFactory.createMono16FitsSequence(folder.newFolder("mono").toPath(), "session", 2);
        SequenceViewerFrame.Result result = DetectionSequenceFrame.read(firstFrame(sequence, true), new DetectionConfig());
        assertNull(result.problem);
        assertEquals(1, result.frame.getChannelCount());
        assertNotNull(result.detections);
        assertTrue(result.summary, result.summary.contains("point source") && result.summary.contains("streak"));
    }

    private static FitsFileInformation firstFrame(Path sequence, boolean monochrome) throws Exception {
        File[] frames = sequence.toFile().listFiles(file -> file.getName().endsWith(".fit") || file.getName().endsWith(".fits"));
        java.util.Arrays.sort(frames);
        FitsFileInformation unsized = new FitsFileInformation(frames[0].getPath(), frames[0].getName(), monochrome, 0, 0);
        BlinkSequence.Frame read = BlinkSequence.load(unsized);
        return new FitsFileInformation(frames[0].getPath(), frames[0].getName(), monochrome, read.getWidth(), read.getHeight());
    }
}
