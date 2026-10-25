/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.tasks;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;
import io.github.ppissias.jtransient.engine.ImageFrame;
import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import nom.tam.fits.Fits;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads every imported 16-bit monochrome frame as an engine {@link ImageFrame}, with its timestamp and exposure,
 * for the tasks that run the engine on the whole session.
 */
final class SessionFrameLoader {

    private SessionFrameLoader() {
    }

    /**
     * @param filesInfo the imported frames, in sequence order
     * @param listener progress from 0 to {@code progressEnd} percent, told before each frame; a job's progress stops
     *                 the loading once the application has run out of memory
     */
    static List<ImageFrame> loadAll(FitsFileInformation[] filesInfo, TransientEngineProgressListener listener, int progressEnd)
            throws Exception {
        int numFrames = filesInfo.length;
        List<ImageFrame> frames = new ArrayList<>();
        for (int i = 0; i < numFrames; i++) {
            if (listener != null) {
                listener.onProgressUpdate((int) (((float) i / numFrames) * progressEnd), "Loading frame " + (i + 1) + " of " + numFrames + "...");
            }
            File currentFile = new File(filesInfo[i].getFilePath());
            try (Fits fitsFile = new Fits(currentFile)) {
                Object kernel = FitsInput.getImageHDU(fitsFile).getKernel();
                if (!(kernel instanceof short[][])) {
                    throw new Exception("Cannot process: Expected short[][]");
                }
                frames.add(new ImageFrame(i, currentFile.getName(), (short[][]) kernel,
                        filesInfo[i].getObservationTimestamp(), filesInfo[i].getExposureDurationMillis()));
            }
        }
        return frames;
    }
}
