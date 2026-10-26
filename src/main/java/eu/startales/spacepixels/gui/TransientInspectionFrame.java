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

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.JTransientEngine;
import io.github.ppissias.jtransient.engine.TransientReference;
import io.github.ppissias.jtransient.model.DetectedObject;
import eu.startales.spacepixels.session.ProcessingSession;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.util.DebugOutput;
import io.github.ppissias.jtransient.engine.FrameTransients;
import io.github.ppissias.jtransient.engine.ImageFrame;

import javax.swing.JLabel;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manual Transient Inspection: after the engine has run on all frames of the session, shows each frame with the
 * transients left after the star mask and the other vetoes. The frames stay in memory until the window closes.
 */
public class TransientInspectionFrame extends SequenceViewerFrame {

    private final StretchPanel stretchPanel;
    private final FitsFileInformation[] filesInfo;
    private final TransientReference reference;
    private final DetectionConfigurationPanel detection;
    private final TrialSettingsControls trials;
    private final JLabel masterNote = new JLabel(" ");
    private final JTransientEngine engine = new JTransientEngine();
    private final Map<String, List<DetectedObject>> detectedBySettings = new HashMap<>();
    private volatile DetectionConfig trialConfig;
    private List<FrameTransients> allTransients;
    private final Map<String, FitsFileInformation> fileInfoByName = new HashMap<>();
    private final Map<String, ImageFrame> frameByName = new HashMap<>();

    public TransientInspectionFrame(List<ImageFrame> frames, List<FrameTransients> allTransients,
                                    FitsFileInformation[] filesInfo, StretchPanel stretchPanel,
                                    TransientReference reference, DetectionConfigurationPanel detection,
                                    SessionAccess sessions) {
        super("Manual Transient Inspection", "preparing…", true, true);
        this.reference = reference;
        this.detection = detection;
        this.allTransients = allTransients != null ? allTransients : Collections.emptyList();
        ProcessingSession ownerSession = sessions.current();
        trials = new TrialSettingsControls(detection.perFrameDetectionSettings(), detection.settingsInUse(),
                this::refresh, this::focusFrame,
                changes -> sessions.isCurrent(ownerSession) ? detection.applyTrialSettings(changes) : null);
        addControlRow(trials.horizontal());
        masterNote.setFont(UiStyle.bodyFont());
        addControlRow(masterNote);
        rememberOpening(detection.settingsInUse());
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

    /** Enables trial edits only while the application has no active job. */
    @Override
    public void setSettingsEditable(boolean editable) {
        trials.setEditable(editable);
    }

    /** Remembers the opening detection, so the first view does not extract the frame again. */
    private void rememberOpening(DetectionConfig settings) {
        for (int index = 0; index < allTransients.size(); index++) {
            detectedBySettings.put(cacheKey(index, settings), allTransients.get(index).transients);
        }
    }

    @Override
    protected void preparing() {
        trialConfig = trials.snapshot(detection.settingsInUse());
        DetectionConfig live = detection.settingsInUse();
        boolean masterChanged = live.masterSigmaMultiplier != reference.masterSigmaMultiplier()
                || live.masterGrowSigmaMultiplier != reference.masterGrowSigmaMultiplier()
                || live.masterMinDetectionPixels != reference.masterMinDetectionPixels()
                || live.maxStarJitter != reference.maxStarJitter();
        masterNote.setText(masterChanged
                ? "The Detection Settings tab's master values now differ. Reopening this window rebuilds the reference."
                : String.format(Locale.US, "Master mask: sigma %.2f, grow %.2f, minimum pixels %d, jitter %.1f px",
                reference.masterSigmaMultiplier(), reference.masterGrowSigmaMultiplier(),
                reference.masterMinDetectionPixels(), reference.maxStarJitter()));
    }

    @Override
    protected Result prepare(int index) {
        FrameTransients transients = allTransients.get(index);
        ImageFrame frame = frameByName.get(transients.filename);
        if (frame == null) {
            return Result.problem("The image data of " + transients.filename + " was not found.");
        }
        DetectionConfig settings = trialConfig;
        List<DetectedObject> objects = detectedBySettings.computeIfAbsent(cacheKey(index, settings), key ->
                DebugOutput.withEngineDiagnostics(() -> engine.redetectFrame(reference, index, settings).transients));
        if (objects == null) {
            objects = Collections.emptyList();
        }
        int count = 0;
        for (DetectedObject object : objects) {
            if (!object.isNoise) {
                count++;
            }
        }
        BlinkSequence.Frame display = BlinkSequence.fromMono(infoOf(index), frame.pixelData);
        return Result.of(display, objects, count + (count == 1 ? " transient" : " transients"));
    }

    /** The three trial values identify a cached detection of one frame. */
    private static String cacheKey(int index, DetectionConfig settings) {
        return index + ":" + settings.detectionSigmaMultiplier + ":" + settings.growSigmaMultiplier + ":"
                + settings.minDetectionPixels;
    }

    @Override
    protected void released() {
        trials.stop();
        engine.shutdown();
        frameByName.clear();
        allTransients = Collections.emptyList();
        detectedBySettings.clear();
    }
}
