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

import io.github.ppissias.jtransient.model.DetectedObject;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.detection.TrackOverlay;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual Transient Inspection: after the engine has run on all frames of the session, shows each frame with the
 * transients left after the star mask and the other vetoes. The frames stay in memory until the window closes.
 */
public class TransientInspectionFrame extends SequenceViewerFrame {

    private final StretchPanel stretchPanel;
    private final FitsFileInformation[] filesInfo;
    private List<FrameTransients> allTransients;
    private final Map<String, FitsFileInformation> fileInfoByName = new HashMap<>();
    private final Map<String, ImageFrame> frameByName = new HashMap<>();

    public TransientInspectionFrame(List<ImageFrame> frames, List<FrameTransients> allTransients,
                                    FitsFileInformation[] filesInfo, StretchPanel stretchPanel) {
        super("Manual Transient Inspection", "preparing…", true, true);
        this.allTransients = allTransients != null ? allTransients : Collections.emptyList();
        if (filesInfo != null) {
            for (FitsFileInformation info : filesInfo) {
                if (info != null && info.getFileName() != null) {
                    fileInfoByName.put(info.getFileName(), info);
                }
            }
        }
        for (ImageFrame frame : frames) {
            if (frame.filename != null) {
                frameByName.put(frame.filename, frame);
            }
        }
        this.stretchPanel = stretchPanel;
        this.filesInfo = filesInfo == null ? null : filesInfo.clone();
    }

    /** Replaces only this window's own file: the same descriptor or the same full path, never another folder's namesake. */
    @Override
    protected void onFrameMetadataReplaced(FitsFileInformation previous, FitsFileInformation updated) {
        if (filesInfo != null) {
            for (int i = 0; i < filesInfo.length; i++) {
                if (sameFile(filesInfo[i], previous)) {
                    filesInfo[i] = updated;
                }
            }
        }
        FitsFileInformation existing = updated.getFileName() == null ? null : fileInfoByName.get(updated.getFileName());
        if (sameFile(existing, previous)) {
            fileInfoByName.put(updated.getFileName(), updated);
        }
    }

    private static boolean sameFile(FitsFileInformation own, FitsFileInformation previous) {
        return own != null && (own == previous
                || (own.getFilePath() != null && own.getFilePath().equals(previous.getFilePath())));
    }

    /**
     * Shows the first frame; returns false, showing nothing, when the engine kept no frames.
     *
     * @param lastRunTracks the tracks of the session's last detection run, for "Show tracks"; null when none has run
     */
    public boolean open(TrackOverlay lastRunTracks) {
        if (allTransients.isEmpty()) {
            dispose();
            return false;
        }
        openAt(0, stretchPanel, filesInfo);
        setTrackOverlay(lastRunTracks, filesInfo);
        return true;
    }

    @Override
    protected int frameCount() {
        return allTransients.size();
    }

    @Override
    protected FitsFileInformation infoOf(int index) {
        String name = allTransients.get(index).filename;
        FitsFileInformation info = fileInfoByName.get(name);
        if (info == null) {
            // Not an imported file name: describe the frame by its name only.
            info = new FitsFileInformation(name, name, true, 0, 0);
            fileInfoByName.put(name, info);
        }
        return info;
    }

    @Override
    protected Result prepare(int index) {
        FrameTransients transients = allTransients.get(index);
        ImageFrame frame = frameByName.get(transients.filename);
        if (frame == null) {
            return Result.problem("The image data of " + transients.filename + " was not found.");
        }
        List<DetectedObject> objects = transients.transients != null
                ? transients.transients : Collections.emptyList();
        int count = 0;
        for (DetectedObject object : objects) {
            if (!object.isNoise) {
                count++;
            }
        }
        BlinkSequence.Frame display = BlinkSequence.fromMono(infoOf(index), frame.pixelData);
        return Result.of(display, objects, count + (count == 1 ? " transient" : " transients"));
    }

    @Override
    protected void released() {
        frameByName.clear();
        allTransients = Collections.emptyList();
    }
}
