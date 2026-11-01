package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.fits.FitsFileInformation;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
import nom.tam.fits.Fits;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The crops of the stretch preview: what is picked from a frame, where track crops come from, and the clipping shares. */
public class StretchSamplePanelTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final int WIDTH = 1000;
    private static final int HEIGHT = 600;

    /**
     * A noisy sky with registration fill in one corner, a field of small stars, one large saturated star and a hot
     * pixel: the picks are a quiet patch, the star field and the saturated star, and none has fill.
     */
    @Test public void aFrameGivesQuietSkyABusyFieldAndTheBrightestStar() {
        Random random = new Random(7);
        short[] data = new short[WIDTH * HEIGHT];
        for (int i = 0; i < data.length; i++) {
            data[i] = stored(1000 + random.nextGaussian() * 20);
        }
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                data[y * WIDTH + x] = Short.MIN_VALUE;
            }
        }
        // Forty small stars in the tile at column 3, row 1.
        for (int star = 0; star < 40; star++) {
            int cx = 610 + (star % 8) * 22;
            int cy = 215 + (star / 8) * 35;
            for (int y = cy - 1; y <= cy + 1; y++) {
                for (int x = cx - 1; x <= cx + 1; x++) {
                    data[y * WIDTH + x] = stored(3000);
                }
            }
        }
        // A saturated star in the tile at column 1, row 2, and a hot pixel in the tile at column 4, row 0.
        for (int y = 492; y <= 508; y++) {
            for (int x = 292; x <= 308; x++) {
                if ((x - 300) * (x - 300) + (y - 500) * (y - 500) <= 64) {
                    data[y * WIDTH + x] = stored(65535);
                }
            }
        }
        data[100 * WIDTH + 900] = stored(65535);

        List<StretchSamplePanel.Sample> picks = StretchSamplePanel.picks(data, WIDTH, HEIGHT);

        assertEquals(List.of("Quiet sky", "Busy star field", "Brightest star"), picks.stream().map(StretchSamplePanel.Sample::title).toList());
        for (StretchSamplePanel.Sample pick : picks) {
            assertEquals(StretchSamplePanel.CROP_SIZE, pick.pixels().length);
            assertEquals(0, count(pick.pixels(), Short.MIN_VALUE, Short.MIN_VALUE));
        }
        assertTrue(count(picks.get(2).pixels(), stored(65535), stored(65535)) > 100);
        assertEquals(40 * 9, count(picks.get(1).pixels(), stored(3000), stored(3000)));
        assertTrue(count(picks.get(0).pixels(), stored(2500), Short.MAX_VALUE) <= 1);
    }

    /** A crop near an edge moves inside the frame, and is smaller only along a side where the frame is. */
    @Test public void aCropStaysInsideTheFrame() {
        short[] data = new short[300 * 150];
        for (int i = 0; i < data.length; i++) {
            data[i] = (short) i;
        }
        short[][] corner = StretchSamplePanel.crop(data, 300, 150, 295, 5, 200);
        assertEquals(150, corner.length);
        assertEquals(200, corner[0].length);
        assertEquals(100, corner[0][0]);
        assertEquals((short) (299 + 149 * 300), corner[149][199]);
    }

    /** The shares count valid pixels only, with the black and white points themselves clipped. */
    @Test public void clippingCountsValidPixelsAtOrBeyondTheLevels() {
        short[][] crop = {{Short.MIN_VALUE, stored(10), stored(20)}, {stored(30), stored(40), stored(50)}};
        StretchSamplePanel.Clipping clipping = StretchSamplePanel.clipping(crop, 20, 40);
        assertEquals(0.4, clipping.black(), 1e-12);
        assertEquals(0.4, clipping.white(), 1e-12);
    }

    /**
     * After a run the crops are around its tracks, in the report's order, each centred on a position of its track;
     * the second track's crop comes from the frame already read for the first.
     */
    @Test public void tracksShareAFrameWhereTheyCan() throws Exception {
        FitsFileInformation[] files = new FitsFileInformation[4];
        for (int frame = 0; frame < files.length; frame++) {
            short[][] pixels = new short[300][400];
            for (short[] row : pixels) {
                java.util.Arrays.fill(row, stored(1000 + 100 * frame));
            }
            File file = new File(folder.getRoot(), "frame" + frame + ".fit");
            try (Fits fits = new Fits()) {
                fits.addHDU(Fits.makeHDU(pixels));
                fits.write(file);
            }
            files[frame] = new FitsFileInformation(file.getPath(), file.getName(), true, 400, 300);
        }
        Track first = track(files, new int[]{0, 1, 2}, 100, 100);
        Track second = track(files, new int[]{1, 2, 3}, 300, 200);
        TrackOverlay overlay = TrackOverlay.from(PipelineResult.builder().tracks(List.of(first, second)).build());

        StretchSamplePanel.Samples samples = StretchSamplePanel.trackSamples(files, overlay);

        assertEquals("Crops around T1 and T2 of the last detection run.", samples.source());
        assertEquals(List.of("T1 · frame 2", "T2 · frame 2"), samples.crops().stream().map(StretchSamplePanel.Sample::title).toList());
        for (StretchSamplePanel.Sample crop : samples.crops()) {
            assertEquals(stored(1100), crop.pixels()[0][0]);
            assertEquals(StretchSamplePanel.CROP_SIZE, crop.pixels().length);
        }
    }

    private static Track track(FitsFileInformation[] files, int[] frames, double x, double y) {
        Track track = new Track();
        for (int frame : frames) {
            DetectedObject point = new DetectedObject(x + frame, y + frame, 1000, 9);
            point.sourceFrameIndex = frame;
            point.sourceFilename = files[frame].getFileName();
            track.addPoint(point);
        }
        return track;
    }

    private static short stored(double value) {
        return (short) (Math.max(0, Math.min(65535, Math.round(value))) - 32768);
    }

    private static int count(short[][] pixels, short low, short high) {
        int count = 0;
        for (short[] row : pixels) {
            for (short value : row) {
                if (value >= low && value <= high) {
                    count++;
                }
            }
        }
        return count;
    }
}
