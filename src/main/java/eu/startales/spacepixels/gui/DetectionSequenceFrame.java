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
import eu.startales.spacepixels.util.DebugOutput;

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.FitsInput;
import eu.startales.spacepixels.session.ProcessingSession;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.SourceExtractor;
import nom.tam.fits.Fits;

import javax.swing.*;
import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Preview Frame: runs the source extraction of the detection on one frame at a time and shows what it finds. Steps
 * through all frames of the session, starting at the selected one. Each frame is detected with the settings in use
 * when it is prepared, with this window's three trial thresholds. Changes refresh this preview only; Apply to
 * Settings copies edited values into the current session, preserving other settings. Closing discards unapplied trials.
 */
public class DetectionSequenceFrame extends SequenceViewerFrame {

    private FitsFileInformation[] files;
    /** The settings of the frame being prepared, taken on the Swing thread just before. */
    private volatile DetectionConfig config;
    private final SessionAccess sessions;
    private final DetectionConfigurationPanel detection;
    private final TrialSettingsControls trials;
    private final ProcessingSession ownerSession;

    /** Captures the session identity; trial controls copy values while each preparation reads fresh other settings. */
    public DetectionSequenceFrame(JFrame owner, SessionAccess sessions, DetectionConfigurationPanel detection) {
        super("Preview Frame", "detecting…", false, true);
        this.sessions = sessions;
        this.detection = detection;
        setMarkerSettings(detection::visualizationSettings);
        ownerSession = sessions.current();
        trials = new TrialSettingsControls(detection.perFrameDetectionSettings(), detection.settingsInUse(),
                this::refresh, this::focusFrame,
                changes -> sessions.isCurrent(ownerSession) ? detection.applyTrialSettings(changes) : null);
        addControlRow(trials.horizontal());
        setLocationRelativeTo(owner);
    }

    /** Shows the frames of the session from {@code startIndex}. */
    public void openSequence(FitsFileInformation[] files, int startIndex, StretchPanel stretchPanel) {
        if (this.files == null) trials.load(detection.settingsInUse());
        this.files = files == null ? null : files.clone();
        if (sessions.current() != null) setSkyCatalogueModel(sessions.current().getSkyCatalogue());
        openAt(startIndex, stretchPanel, this.files);
        showLastRun();
    }

    @Override
    protected void onFrameMetadataReplaced(FitsFileInformation previous, FitsFileInformation updated) {
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i] == previous || (files[i] != null && files[i].getFilePath() != null
                    && files[i].getFilePath().equals(previous.getFilePath()))) {
                files[i] = updated;
            }
        }
        showLastRun();
    }

    /** Takes the tracks and variable-star candidates of the session's last detection run, for example after a run ends. */
    void showLastRun() {
        if (files == null || !isVisible()) {
            return;
        }
        ProcessingSession session = sessions.current();
        setTrackOverlay(session == null ? null : session.getTrackOverlay(), files);
        setVariableStarOverlay(session == null ? null : session.getVariableStarOverlay(), files);
    }

    /** Enables trial edits and Apply only while the application has no active job or blink. */
    @Override
    public void setSettingsEditable(boolean editable) {
        trials.setEditable(editable);
    }

    // ==========================================
    // THE FRAMES
    // ==========================================

    @Override
    protected int frameCount() {
        return files == null ? 0 : files.length;
    }

    @Override
    protected FitsFileInformation infoOf(int index) {
        return files[index];
    }

    @Override
    protected void preparing() {
        config = trials.snapshot(detection.settingsInUse());
    }

    /** Reads the frame as the detection does (16-bit mono, the image HDU) and extracts its sources. */
    @Override
    protected Result prepare(int index) throws Exception {
        FitsFileInformation info = files[index];
        DetectionConfig settings = config;
        short[][] data;
        try (Fits fits = new Fits(new File(info.getFilePath()))) {
            Object kernel = FitsInput.getImageHDU(fits).getKernel();
            if (!(kernel instanceof short[][])) {
                return Result.problem("The detection needs 16-bit monochrome frames. Convert 32-bit, compressed or XISF "
                        + "frames when you import them, and colour frames with Convert to Mono.");
            }
            data = (short[][]) kernel;
        }
        DataArea frameArea = DataArea.ofFrame(data);
        List<DetectedObject> objects = DebugOutput.withEngineDiagnostics(() -> SourceExtractor.extractSources(
                data, settings.detectionSigmaMultiplier, settings.minDetectionPixels, settings, frameArea, frameArea)).objects;
        int stars = 0;
        int streaks = 0;
        for (DetectedObject object : objects) {
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

    /** Lets go of the frames; a field left with invalid text shows its value again. */
    @Override
    protected void released() {
        trials.stop();
        files = null;
        config = null;
    }
}
