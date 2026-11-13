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

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsInput;
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
 * A frame the detection cannot read, such as a colour frame from the stretch editor, is shown without raw detections.
 */
public class DetectionSequenceFrame extends SequenceViewerFrame {

    private FitsFileInformation[] files;
    /** The settings of the frame being prepared, taken on the Swing thread just before. */
    private volatile DetectionConfig config;
    private final SessionAccess sessions;
    private final DetectionConfigurationPanel detection;
    private final TrialSettingsControls trials;
    private final ProcessingSession ownerSession;
    private final JButton blinkButton = new JButton("Blink ↗");
    /** The session's excluded areas, drawn and edited here; null without a session. */
    private final ExcludedAreasSection excludedAreas;
    private java.util.function.Consumer<FitsFileInformation> blinkAction = shown -> { };

    /** Captures the session identity; trial controls copy values while each preparation reads fresh other settings. */
    public DetectionSequenceFrame(JFrame owner, SessionAccess sessions, DetectionConfigurationPanel detection,
                                  ViewerLayerState layers) {
        super("Preview Frame", "detecting…", false, true, layers);
        this.sessions = sessions;
        this.detection = detection;
        setMarkerSettings(detection::visualizationSettings);
        ownerSession = sessions.current();
        trials = new TrialSettingsControls(detection.perFrameDetectionSettings(), detection.settingsInUse(),
                this::refresh, this::focusFrame,
                changes -> sessions.isCurrent(ownerSession) ? detection.applyTrialSettings(changes) : null);
        setRawDetections(trials);
        blinkButton.setToolTipText("Blink the frames selected in the main window, or all frames when fewer than three "
                + "are selected, starting on this frame. An open blink comes to the front on this frame.");
        blinkButton.addActionListener(e -> {
            if (files != null && currentIndex() < files.length) blinkAction.accept(files[currentIndex()]);
        });
        addToolbarButton(blinkButton);
        excludedAreas = ownerSession == null ? null
                : new ExcludedAreasSection(layers, ownerSession, this::setExcludedAreaEditing, this::showExcludedAreas);
        if (excludedAreas != null) addSideSection(excludedAreas.section());
        setLocationRelativeTo(owner);
    }

    /** Turns drawing excluded areas on, for the main window's Exclude Areas command. */
    void startDrawingExcludedAreas() {
        if (excludedAreas != null) excludedAreas.startDrawing();
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

    /** Takes all the findings of the session's last detection run, for example after a run ends. */
    void showLastRun() {
        if (files == null || !isVisible()) {
            return;
        }
        setLastRun(sessions.current(), files);
    }

    /** Enables trial edits, Save and Blink only while the application has no active job; an open blink is not one. */
    @Override
    public void setSettingsEditable(boolean editable) {
        trials.setEditable(editable);
        blinkButton.setEnabled(editable);
        if (excludedAreas != null) excludedAreas.setEditable(editable);
    }

    /** What the Blink button does with the frame shown. */
    void setBlinkAction(java.util.function.Consumer<FitsFileInformation> blinkAction) {
        this.blinkAction = blinkAction;
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

    @Override
    protected Result prepare(int index) throws Exception {
        return read(files[index], config);
    }

    /**
     * Reads a frame as the detection does (16-bit mono, the image HDU) and extracts its sources. Any other frame is
     * read for display only, as Blink reads it.
     */
    static Result read(FitsFileInformation info, DetectionConfig settings) throws Exception {
        short[][] data;
        try (Fits fits = new Fits(new File(info.getFilePath()))) {
            Object kernel = FitsInput.getImageHDU(fits).getKernel();
            data = kernel instanceof short[][] mono ? mono : null;
        }
        if (data == null) {
            BlinkSequence.Frame shown = BlinkSequence.load(info);
            return Result.of(shown, null, (shown.getChannelCount() == 3 ? "colour" : "not 16-bit") + " · no raw detections");
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
