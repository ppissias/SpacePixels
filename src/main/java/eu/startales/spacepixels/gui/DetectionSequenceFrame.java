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

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.SourceExtractor;
import nom.tam.fits.Fits;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Preview Frame: runs the source extraction of the detection, with the current settings, on one frame at a time and
 * shows what it finds. Steps through all frames of the session, starting at the selected one.
 */
public class DetectionSequenceFrame extends SequenceViewerFrame {

    private FitsFileInformation[] files;
    private DetectionConfig config;

    public DetectionSequenceFrame(ApplicationWindow mainAppWindow) {
        super("Preview Frame", "detecting…", false, true);
        setLocationRelativeTo(mainAppWindow.getFrame());
    }

    /** Shows the frames of the session from {@code startIndex}, detecting with this configuration. */
    public void openSequence(FitsFileInformation[] files, int startIndex, DetectionConfig config, StretchPanel stretchPanel) {
        this.files = files;
        this.config = config;
        openAt(startIndex, stretchPanel, files);
    }

    @Override
    protected int frameCount() {
        return files == null ? 0 : files.length;
    }

    @Override
    protected FitsFileInformation infoOf(int index) {
        return files[index];
    }

    /** Reads the frame as the detection does (16-bit mono, first HDU) and extracts its sources. */
    @Override
    protected Result prepare(int index) throws Exception {
        FitsFileInformation info = files[index];
        short[][] data;
        try (Fits fits = new Fits(new File(info.getFilePath()))) {
            Object kernel = fits.getHDU(0).getKernel();
            if (!(kernel instanceof short[][])) {
                return Result.problem("The detection needs 16-bit monochrome frames. Convert 32-bit, compressed or XISF "
                        + "frames when you import them, and colour frames with Convert to Mono.");
            }
            data = (short[][]) kernel;
        }
        List<SourceExtractor.DetectedObject> objects = SourceExtractor.extractSources(
                data, config.detectionSigmaMultiplier, config.minDetectionPixels, config).objects;
        int stars = 0;
        int streaks = 0;
        for (SourceExtractor.DetectedObject object : objects) {
            if (object.isNoise) {
                continue;
            }
            if (object.isStreak) {
                streaks++;
            } else {
                stars++;
            }
        }
        BlinkSequence.Frame frame = BlinkSequence.fromMono(info, data);
        return Result.of(frame, objects, String.format(Locale.US, "%d %s · %d %s",
                stars, stars == 1 ? "point source" : "point sources", streaks, streaks == 1 ? "streak" : "streaks"));
    }

    @Override
    protected void released() {
        files = null;
        config = null;
    }
}
