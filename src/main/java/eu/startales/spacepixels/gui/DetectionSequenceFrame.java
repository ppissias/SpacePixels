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

import eu.startales.spacepixels.util.DebugOutput;

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.core.DataArea;
import io.github.ppissias.jtransient.core.SourceExtractor;
import nom.tam.fits.Fits;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.File;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Preview Frame: runs the source extraction of the detection on one frame at a time and shows what it finds. Steps
 * through all frames of the session, starting at the selected one. Each frame is detected with the settings in use
 * when it is prepared. The per-frame thresholds can be changed here: they are the settings of the Detection Settings
 * tab, and the frame on show is detected again shortly after a change.
 */
public class DetectionSequenceFrame extends SequenceViewerFrame {

    /** How long after the last change of a threshold the frame is detected again, so holding an arrow detects once. */
    private static final int REDETECT_DELAY_MILLIS = 300;

    private FitsFileInformation[] files;
    /** The settings of the frame being prepared, taken on the Swing thread just before. */
    private volatile DetectionConfig config;
    private final ApplicationWindow mainAppWindow;
    // The editor of each threshold, with the setting's title.
    private final Map<CheckedNumberEditor, String> editors = new LinkedHashMap<>();
    private final List<JSpinner> settingSpinners = new ArrayList<>();
    private final JLabel settingsNote = new JLabel(" ");
    private final Timer redetect = new Timer(REDETECT_DELAY_MILLIS, e -> refresh());

    public DetectionSequenceFrame(ApplicationWindow mainAppWindow) {
        super("Preview Frame", "detecting…", false, true);
        this.mainAppWindow = mainAppWindow;
        redetect.setRepeats(false);
        addControlRow(buildSettingsRow(mainAppWindow.getDetectionConfigurationPanel().perFrameDetectionSettings()));
        setLocationRelativeTo(mainAppWindow.getFrame());
    }

    /** Shows the frames of the session from {@code startIndex}. */
    public void openSequence(FitsFileInformation[] files, int startIndex, StretchPanel stretchPanel) {
        this.files = files;
        if (mainAppWindow.getSession() != null) setSkyCatalogueModel(mainAppWindow.getSession().getSkyCatalogue());
        openAt(startIndex, stretchPanel, files);
        setVariableStarOverlay(mainAppWindow.getSession() == null ? null
                : mainAppWindow.getSession().getVariableStarOverlay(), files);
    }

    /** Whether the thresholds can be changed here: not while a job runs, as on the Detection Settings tab. */
    void setSettingsEditable(boolean editable) {
        for (JSpinner spinner : settingSpinners) {
            spinner.setEnabled(editable);
        }
    }

    // ==========================================
    // THE DETECTION SETTINGS
    // ==========================================

    /**
     * A spinner for each threshold, on the model of the setting itself. Clicking an arrow leaves the keys on the
     * frame; clicking into the field lets you type a value, which Enter applies and Esc discards.
     */
    private JComponent buildSettingsRow(List<DetectionConfigurationPanel.SharedSetting> settings) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (DetectionConfigurationPanel.SharedSetting setting : settings) {
            JSpinner spinner = new JSpinner(setting.model());
            CheckedNumberEditor editor = new CheckedNumberEditor(spinner,
                    setting.model().getValue() instanceof Integer ? "#0" : "#0.0", this::showSettingsNote);
            spinner.setEditor(editor);
            spinner.setRequestFocusEnabled(false);
            JFormattedTextField field = editor.getTextField();
            field.setColumns(4);
            bindFieldKey(field, "ENTER", "applyValue", () -> {
                try {
                    field.commitEdit();
                    focusFrame();
                } catch (ParseException e) {
                    // The text stays, outlined in red, and the note says what to enter.
                    UIManager.getLookAndFeel().provideErrorFeedback(field);
                }
            });
            bindFieldKey(field, "ESCAPE", "discardValue", () -> {
                field.setValue(field.getValue());
                focusFrame();
            });
            spinner.addChangeListener(e -> redetect.restart());

            String tooltip = "<html><div style='width: 320px;'>" + setting.description() + "</div></html>";
            JLabel label = new JLabel(setting.title());
            AccessibleControls.label(label, spinner, setting.description());
            label.setToolTipText(tooltip);
            spinner.setToolTipText(tooltip);
            editors.put(editor, setting.title());
            settingSpinners.add(spinner);
            row.add(label);
            row.add(spinner);
            row.add(Box.createHorizontalStrut(6));
        }
        row.add(settingsNote);
        showSettingsNote();
        return row;
    }

    private static void bindFieldKey(JFormattedTextField field, String key, String name, Runnable action) {
        field.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        field.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Says what to enter while a field holds invalid text; meanwhile the frame is detected with the last valid value. */
    private void showSettingsNote() {
        for (Map.Entry<CheckedNumberEditor, String> entry : editors.entrySet()) {
            if (!entry.getKey().isTextValid()) {
                Color error = UIManager.getColor("Actions.Red");
                settingsNote.setForeground(error != null ? error : new Color(0xDB5860));
                settingsNote.setText(entry.getValue() + ": " + entry.getKey().expected() + ". The frame keeps the last valid value.");
                return;
            }
        }
        settingsNote.setForeground(UIManager.getColor("Label.disabledForeground"));
        settingsNote.setText("The session's detection settings, as on the Detection Settings tab · Enter applies a typed value");
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
        config = mainAppWindow.getDetectionConfigurationPanel().settingsInUse();
    }

    /** Reads the frame as the detection does (16-bit mono, the image HDU) and extracts its sources. */
    @Override
    protected Result prepare(int index) throws Exception {
        FitsFileInformation info = files[index];
        DetectionConfig settings = config;
        short[][] data;
        try (Fits fits = new Fits(new File(info.getFilePath()))) {
            Object kernel = ImageProcessing.getImageHDU(fits).getKernel();
            if (!(kernel instanceof short[][])) {
                return Result.problem("The detection needs 16-bit monochrome frames. Convert 32-bit, compressed or XISF "
                        + "frames when you import them, and colour frames with Convert to Mono.");
            }
            data = (short[][]) kernel;
        }
        DataArea frameArea = DataArea.ofFrame(data);
        List<SourceExtractor.DetectedObject> objects = DebugOutput.withEngineDiagnostics(() -> SourceExtractor.extractSources(
                data, settings.detectionSigmaMultiplier, settings.minDetectionPixels, settings, frameArea, frameArea)).objects;
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

    /** Lets go of the frames; a field left with invalid text shows its value again. */
    @Override
    protected void released() {
        redetect.stop();
        for (CheckedNumberEditor editor : editors.keySet()) {
            if (!editor.isTextValid()) {
                editor.getTextField().setValue(editor.getTextField().getValue());
            }
        }
        files = null;
        config = null;
    }
}
