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

import eu.startales.spacepixels.util.AutoTunerRunner;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.engine.CalibratedAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner.AutoTuneProfile;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Overview page of the Detection Settings: the Auto-Tune box with the measured result of every profile, and the
 * core settings the tuner sets, each marked while it still holds the tuned value.
 */
public class AutoTuneOverviewPanel extends JPanel {

    /** What the overview needs from the settings panel that owns the spinners. */
    interface Host {
        void startAutoTune(AutoTuneProfile profile, AutoTunerRunner.Algorithm algorithm);

        /** Moves the settings spinners to the tuned configuration. */
        void applyTunedConfig(DetectionConfig tuned);

        /** The current settings, used as the base for a tuned configuration. */
        DetectionConfig currentConfig();

        void showPreview();

        void showReport(String reportText);

        /** Called when the applied profile or the tuned markers change. */
        void tuningStateChanged();

        /** Opens the settings tab with this title. */
        void showSettingsTab(String tabTitle);
    }

    /** Two-line headings, so the table stays narrow enough for small windows. */
    private static final String[] COLUMNS = {
            "Profile", "Detection<br>σ / grow / min px", "Star mask<br>σ / grow / min px", "Mask<br>overlap",
            "Star<br>jitter (px)", "Noise detections<br>/ MPix / frame", "Expected noise<br>detections", "Test stars<br>found",
            "Detection<br>limit (SNR)", "Sky<br>masked"};
    private static final String[] COLUMN_TOOLTIPS = {
            "Sensitivity profile and its budget of noise detections per megapixel per frame. ● marks the profile in use.",
            "Per-frame detection threshold, grow threshold and minimum object size chosen for this profile.",
            "Master star mask: detection threshold, grow threshold and minimum size of the stars it hides.",
            "Largest share of a detection that may overlap the star mask before it is vetoed as star residue.",
            "Base star jitter radius measured on the session: detections that move less than this are treated as star jitter, "
                    + "not as moving objects. The same for every profile.",
            "Measured detections that are not real objects (noise peaks and star leftovers the settings let through), "
                    + "per megapixel per frame. ⚠: no setting met the profile's budget, so the cleanest one was used.",
            "Noise detections to expect in a full run on this session: the measured rate × sensor megapixels × frames.",
            "Share of synthetic test stars (added at peak SNR 2 to 15) that the settings still find and keep.",
            "Peak signal-to-noise ratio at which half of the test stars are found: the detection limit. Lower is more "
                    + "sensitive; halving it reaches objects about 0.75 mag fainter.",
            "Share of the sky hidden by the star mask; nothing can be detected there. Measured on the tuner's crops, where it can "
                    + "be higher than on the whole frame; Test Star Mask… shows the whole-frame mask."};
    private static final int[] COLUMN_WIDTHS = {150, 130, 130, 70, 70, 120, 105, 80, 85, 70};
    private static final int COL_PROFILE = 0;
    private static final int COL_DETECTION = 1;
    private static final int COL_STAR_MASK = 2;
    private static final int COL_OVERLAP = 3;
    private static final int COL_JITTER = 4;
    private static final int COL_NOISE = 5;
    private static final int COL_EXPECTED_NOISE = 6;
    private static final int COL_TEST_STARS = 7;
    private static final int COL_SNR_LIMIT = 8;
    private static final int COL_SKY_MASKED = 9;
    private static final int[] CALIBRATED_COLUMNS = {COL_PROFILE, COL_DETECTION, COL_STAR_MASK, COL_OVERLAP, COL_JITTER,
            COL_NOISE, COL_EXPECTED_NOISE, COL_TEST_STARS, COL_SNR_LIMIT, COL_SKY_MASKED};
    /** The legacy tuner keeps the star mask settings and measures no noise rates, so only what it sets is shown. */
    private static final int[] LEGACY_COLUMNS = {COL_PROFILE, COL_DETECTION, COL_OVERLAP, COL_JITTER};

    private final Host host;

    private final JComboBox<AutoTunerRunner.Algorithm> algorithmCombo = new JComboBox<>(AutoTunerRunner.Algorithm.values());
    private final JComboBox<AutoTuneProfile> profileCombo = new JComboBox<>(AutoTuneProfile.values());
    private final JButton runButton = new JButton("Run Auto-Tune");
    private final JButton useProfileButton = new JButton("Use Selected Profile");
    private final JButton reportButton = new JButton("Measurement Report…");
    private final JButton previewButton = new JButton("Preview on Frame…");
    private final JButton detectButton = new JButton("Detect Moving Targets");
    private final JLabel profileLabel = new JLabel();
    private final JLabel tableNoteLabel = new JLabel(" ");
    private final JLabel lastRunLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final ProfileTableModel tableModel = new ProfileTableModel();
    private final JTable profileTable = new JTable(tableModel);

    /** Core settings in display order, with their tuned-value markers. */
    private final Map<JSpinner, JLabel> coreMarkers = new LinkedHashMap<>();
    private final Map<JSpinner, JLabel> coreLabels = new LinkedHashMap<>();
    /** Core settings the legacy tuner sets; it keeps the others (the star mask). */
    private final java.util.Set<JSpinner> legacyTunedSettings = new java.util.HashSet<>();
    /** Saved value of each core setting, for marking changes since the last save. */
    private java.util.function.Function<JSpinner, Number> savedValues = spinner -> null;
    private final Map<JSpinner, Number> tunedValues = new LinkedHashMap<>();
    private final Map<JSpinner, Number> valuesBeforeTune = new LinkedHashMap<>();
    private final JPanel coreGrid = new JPanel(new GridBagLayout());
    private final List<JButton> coreActions = new ArrayList<>();
    private int coreRowCount;
    private final JPanel analysesGrid = new JPanel(new GridBagLayout());
    private int analysisRowCount;

    /** The result shown in the table: the last run of the tuner selected in the Tuner box. */
    private JTransientAutoTuner.AutoTunerResult lastResult;
    /** Last run of each tuner on this session, and its "Last run" line. */
    private final Map<AutoTunerRunner.Algorithm, JTransientAutoTuner.AutoTunerResult> resultsByTuner = new EnumMap<>(AutoTunerRunner.Algorithm.class);
    private final Map<AutoTunerRunner.Algorithm, String> runTextByTuner = new EnumMap<>(AutoTunerRunner.Algorithm.class);
    private AutoTunerRunner.Algorithm runningAlgorithm;
    private AutoTuneProfile appliedProfile;
    private boolean adjustingProfiles;
    /** The tuner whose result is applied, so the ● marker only shows in that tuner's table. */
    private AutoTunerRunner.Algorithm appliedAlgorithm;
    private String sessionName;
    private int sessionFrames;
    private int sessionWidth;
    private int sessionHeight;
    private boolean sessionReady;
    private boolean tuning;
    private long tuneStartedMillis;

    AutoTuneOverviewPanel(Host host) {
        this.host = host;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(10, 20, 20, 20));

        JLabel intro = new JLabel("<html><div style='color: #999999; font-size: 12px; padding-bottom: 6px; width: 480px;'>"
                + "<b>Start here:</b> click <b>Run Auto-Tune</b>. It tests your frames and prepares a ready-to-use set of settings for "
                + "each sensitivity profile, from <b>Low</b> (fewest false candidates) to <b>Maximum</b> (faintest objects, many more "
                + "candidates to review). Pick the profile that suits your session in the table (<b>High</b> is the default), then click "
                + "<b>Detect Moving Targets</b>. The pages on the left hold every setting in detail."
                + "</div></html>");
        add(left(intro));

        add(left(DetectionConfigurationPanel.createSectionHeader("Auto-Tune")));
        add(left(buildAutoTuneBox()));

        // Core settings and analyses side by side, so both are visible without scrolling.
        JPanel coreColumn = column();
        coreColumn.add(left(DetectionConfigurationPanel.createSectionHeader("Core Settings")));
        coreColumn.add(left(hint("The settings Auto-Tune chooses. <span style='color: #4da6ff;'>●</span> marks a value set by "
                + "Auto-Tune; a blue name marks a change since the last save. Save clears both. Hover a setting for its description.")));
        coreGrid.setBorder(new EmptyBorder(8, 0, 0, 0));
        coreColumn.add(left(coreGrid));
        coreColumn.add(Box.createVerticalGlue());

        JPanel analysesColumn = column();
        analysesColumn.add(left(DetectionConfigurationPanel.createSectionHeader("Analyses in This Run")));
        analysesColumn.add(left(hint("What Detect Moving Targets looks for besides moving objects. Switch an analysis on or off "
                + "here; its detailed settings are behind <i>Settings ›</i>.")));
        analysesGrid.setBorder(new EmptyBorder(8, 0, 0, 0));
        analysesColumn.add(left(analysesGrid));
        analysesColumn.add(Box.createVerticalGlue());

        JPanel columns = new JPanel(new TwoColumnLayout(40, 16));
        columns.setBorder(new EmptyBorder(10, 0, 0, 0));
        columns.add(coreColumn);
        columns.add(analysesColumn);
        add(left(columns));
        add(Box.createVerticalGlue());

        refreshControls();
    }

    private JComponent buildAutoTuneBox() {
        JPanel box = new JPanel();
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));

        algorithmCombo.setSelectedItem(AutoTunerRunner.DEFAULT_ALGORITHM);
        algorithmCombo.setToolTipText("Calibrated measures noise, star leakage and sensitivity on your frames and covers all profiles in one run; "
                + "Legacy is the original score-based tuner, kept for comparison, and tunes one profile per run.");
        // High unless the user chose another profile before (remembered between sessions).
        profileCombo.setSelectedItem(savedProfile());
        profileCombo.addActionListener(e -> rememberProfile());
        profileCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                return super.getListCellRendererComponent(list, profileName((AutoTuneProfile) value), index, isSelected, cellHasFocus);
            }
        });
        profileCombo.addActionListener(e -> selectTableRow((AutoTuneProfile) profileCombo.getSelectedItem()));
        runButton.putClientProperty("FlatLaf.style",
                "font: bold; background: $Button.default.background; foreground: $Button.default.foreground;"
                        + " borderColor: $Button.default.borderColor");
        runButton.setToolTipText("Measure the frames (the selected frames, or all frames when fewer than the minimum are selected) and choose settings.");
        runButton.addActionListener(e -> host.startAutoTune((AutoTuneProfile) profileCombo.getSelectedItem(),
                (AutoTunerRunner.Algorithm) algorithmCombo.getSelectedItem()));

        algorithmCombo.addActionListener(e -> {
            updateProfileLabel();
            updateProfileChoices();
            showResultsOf(selectedAlgorithm());
        });
        updateProfileLabel();

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        controls.add(new JLabel("Tuner:"));
        controls.add(algorithmCombo);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(profileLabel);
        controls.add(profileCombo);
        controls.add(Box.createHorizontalStrut(8));
        controls.add(runButton);
        box.add(left(controls));

        lastRunLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        lastRunLabel.setBorder(new EmptyBorder(8, 2, 6, 0));
        box.add(left(lastRunLabel));

        profileTable.setTableHeader(new JTableHeader(profileTable.getColumnModel()) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int column = columnAtPoint(e.getPoint());
                return column < 0 ? null : COLUMN_TOOLTIPS[tableModel.columnId(profileTable.convertColumnIndexToModel(column))];
            }

            @Override
            public Dimension getPreferredSize() {
                // Room for the two-line headings (the look and feel sizes the header for one line).
                Dimension size = super.getPreferredSize();
                size.height = Math.max(size.height, 2 * getFontMetrics(getFont()).getHeight() + 10);
                return size;
            }
        });
        profileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileTable.setRowHeight(26);
        profileTable.setFillsViewportHeight(true);
        profileTable.getTableHeader().setReorderingAllowed(false);
        profileTable.setDefaultRenderer(Object.class, new ProfileCellRenderer());
        tableModel.applyColumnWidths();
        profileTable.getSelectionModel().addListSelectionListener(e -> refreshControls());
        profileTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && useProfileButton.isEnabled()) {
                    useSelectedProfile();
                }
            }
        });
        JScrollPane tableScroll = new JScrollPane(profileTable);
        // The table takes the page width; the preferred width only sets where it starts to squeeze its columns.
        Dimension tableSize = new Dimension(640, profileTable.getRowHeight() * 4 + 48);
        tableScroll.setPreferredSize(tableSize);
        tableScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, tableSize.height));
        box.add(left(tableScroll));

        tableNoteLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        tableNoteLabel.setBorder(new EmptyBorder(6, 2, 0, 0));
        box.add(left(tableNoteLabel));

        useProfileButton.setToolTipText("Apply the settings of the profile selected in the table (or double-click a row).");
        useProfileButton.addActionListener(e -> useSelectedProfile());
        reportButton.setToolTipText("Show the full measurement report of the last Auto-Tune run.");
        reportButton.addActionListener(e -> {
            if (lastResult != null && lastResult.telemetryReport != null) {
                host.showReport(lastResult.telemetryReport);
            }
        });
        previewButton.setToolTipText("Run object detection on the frame selected in the Main tab with the current settings and show the detection mask.");
        previewButton.addActionListener(e -> host.showPreview());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        actions.setBorder(new EmptyBorder(8, 0, 0, 0));
        actions.add(useProfileButton);
        actions.add(reportButton);
        actions.add(previewButton);
        actions.add(Box.createHorizontalStrut(14));
        actions.add(detectButton);
        box.add(left(actions));

        statusLabel.setBorder(new EmptyBorder(8, 2, 0, 0));
        box.add(left(statusLabel));
        return box;
    }

    /** Adds a core setting row; the spinner stays owned by the settings panel. */
    /**
     * Makes the Detect Moving Targets button here the same as the main window's: same look, same action, and the
     * same enabled state and tooltip (including the reason when it is not available).
     */
    void bindDetectButton(JButton mainButton, Runnable runDetection) {
        detectButton.setText(mainButton.getText());
        detectButton.putClientProperty("FlatLaf.style", MainApplicationPanel.PRIMARY_BUTTON_STYLE);
        detectButton.addActionListener(e -> runDetection.run());
        Runnable mirror = () -> {
            detectButton.setEnabled(mainButton.isEnabled());
            detectButton.setToolTipText(mainButton.getToolTipText());
        };
        mainButton.addPropertyChangeListener("enabled", e -> mirror.run());
        mainButton.addPropertyChangeListener("ToolTipText", e -> mirror.run());
        mirror.run();
    }

    /** Adds a button under the core settings, enabled while frames are ready and no tune is running. */
    void addCoreAction(JButton button) {
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = coreRowCount++;
        c.gridwidth = 3;
        c.insets = new Insets(8, 12, 0, 0);
        coreGrid.add(button, c);
        coreActions.add(button);
        refreshControls();
    }

    void addCoreSetting(String group, String title, String description, JSpinner spinner, boolean setByLegacyTuner) {
        if (setByLegacyTuner) {
            legacyTunedSettings.add(spinner);
        }
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        if (group != null) {
            JLabel groupLabel = new JLabel(group);
            groupLabel.setFont(groupLabel.getFont().deriveFont(Font.BOLD));
            c.gridx = 0;
            c.gridy = coreRowCount++;
            c.gridwidth = 3;
            c.insets = new Insets(coreRowCount == 1 ? 0 : 10, 0, 4, 0);
            coreGrid.add(groupLabel, c);
            c.gridwidth = 1;
        }
        String tooltip = "<html><div style='width: 380px;'>" + description + "</div></html>";
        JLabel label = new JLabel(title);
        label.setToolTipText(tooltip);
        spinner.setToolTipText(tooltip);
        JLabel marker = new JLabel("●");
        marker.setForeground(DetectionConfigurationPanel.accentColor());
        marker.setPreferredSize(new Dimension(18, marker.getPreferredSize().height));
        marker.setVisible(true);
        marker.setText(" ");

        c.gridy = coreRowCount++;
        c.insets = new Insets(2, 12, 2, 12);
        c.gridx = 0;
        coreGrid.add(label, c);
        c.gridx = 1;
        c.insets = new Insets(2, 0, 2, 4);
        coreGrid.add(spinner, c);
        c.gridx = 2;
        c.weightx = 1;
        coreGrid.add(marker, c);

        coreMarkers.put(spinner, marker);
        coreLabels.put(spinner, label);
        spinner.addChangeListener(e -> refreshMarkers());
    }

    /**
     * Adds an analysis row. The switch shares {@code toggle} with the checkbox on the detailed tab, so both stay in
     * step; a null toggle shows an analysis that is always on.
     */
    void addAnalysis(String title, String description, ButtonModel toggle, String tabTitle) {
        JCheckBox check = new JCheckBox(title);
        check.setFont(check.getFont().deriveFont(Font.BOLD));
        if (toggle != null) {
            check.setModel(toggle);
        } else {
            check.setSelected(true);
            check.setEnabled(false);
            check.setToolTipText("Always on.");
        }
        JLabel descriptionLabel = new JLabel("<html><div style='width: 360px;'>" + description + "</div></html>");
        descriptionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel settingsLink = new JLabel("Settings ›");
        settingsLink.setForeground(new Color(0x4da6ff));
        settingsLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        settingsLink.setToolTipText("Open the " + tabTitle + " tab.");
        settingsLink.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                host.showSettingsTab(tabTitle);
            }
        });

        // Two lines per analysis: the switch and its settings link, then the description under the title.
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.gridy = analysisRowCount++;
        c.insets = new Insets(analysisRowCount == 1 ? 0 : 8, 6, 0, 12);
        c.gridx = 0;
        analysesGrid.add(check, c);
        c.gridx = 1;
        c.weightx = 1;
        analysesGrid.add(settingsLink, c);

        c.gridy = analysisRowCount++;
        c.gridx = 0;
        c.gridwidth = 2;
        c.weightx = 0;
        c.insets = new Insets(0, 30, 0, 0);
        analysesGrid.add(descriptionLabel, c);
    }

    // ==========================================
    // EVENTS FROM THE SETTINGS PANEL
    // ==========================================

    /** Describes the imported session; a previous tune result belongs to another session and is cleared. */
    void setSession(String name, int frames, int width, int height, boolean readyForTuning) {
        boolean sameSession = name != null && name.equals(sessionName) && frames == sessionFrames
                && width == sessionWidth && height == sessionHeight;
        sessionName = name;
        sessionFrames = frames;
        sessionWidth = width;
        sessionHeight = height;
        sessionReady = readyForTuning;
        if (!sameSession) {
            lastResult = null;
            resultsByTuner.clear();
            runTextByTuner.clear();
            tableModel.fireTableDataChanged();
        }
        refreshControls();
    }

    void tuneStarted() {
        tuning = true;
        runningAlgorithm = selectedAlgorithm();
        tuneStartedMillis = System.currentTimeMillis();
        statusLabel.setForeground(UIManager.getColor("Label.foreground"));
        statusLabel.setText("Auto-Tune is running; progress is shown in the status bar…");
        refreshControls();
    }

    void tuneFinished(boolean success, String message, JTransientAutoTuner.AutoTunerResult result) {
        tuning = false;
        if (!success || result == null) {
            showProblem("Auto-Tune failed: " + message);
        } else if (!result.success) {
            String reason = result.calibration != null && result.calibration.failureReason != null
                    ? result.calibration.failureReason
                    : "no stable star field met the noise limits (very noisy, clouded or unaligned frames).";
            showProblem("Auto-Tune found no usable settings: " + reason + " Your current settings are unchanged.");
        } else {
            AutoTunerRunner.Algorithm tuner = runningAlgorithm != null ? runningAlgorithm : selectedAlgorithm();
            long seconds = result.calibration != null
                    ? Math.round(result.calibration.elapsedSeconds)
                    : (System.currentTimeMillis() - tuneStartedMillis) / 1000;
            resultsByTuner.put(tuner, result);
            runTextByTuner.put(tuner, String.format(Locale.US, "Last run: %s, %d frames, %d×%d (%.1f MPix) · %s tuner · %s",
                    sessionName == null ? "session" : sessionName, sessionFrames, sessionWidth, sessionHeight,
                    sessionMegapixels(), tuner == AutoTunerRunner.Algorithm.LEGACY ? "legacy" : "calibrated",
                    formatDuration(seconds)));
            showResultsOf(tuner);
            applyProfile((AutoTuneProfile) profileCombo.getSelectedItem());
        }
        refreshControls();
    }

    /** Clears the tuned markers, for example after Load Defaults or Revert. */
    void forgetTunedValues() {
        tunedValues.clear();
        valuesBeforeTune.clear();
        appliedProfile = null;
        tableModel.fireTableDataChanged();
        refreshMarkers();
    }

    /**
     * Removes the "set by Auto-Tune" marks after the settings were saved; the applied profile is kept, so the main
     * window still says which profile the settings came from.
     */
    void clearTunedMarkers() {
        tunedValues.clear();
        valuesBeforeTune.clear();
        refreshMarkers();
        if (appliedProfile != null && !tuning) {
            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
            statusLabel.setText(profileName(appliedProfile) + " settings saved; they are used for detection and kept for the next start.");
        }
    }

    /** One-line description of the settings in use, for the main window. */
    String settingsSummary() {
        if (appliedProfile == null) {
            return "Manual settings";
        }
        return profileName(appliedProfile) + ", auto-tuned" + (editedSinceTune() ? ", edited" : "");
    }

    // ==========================================
    // PROFILES
    // ==========================================

    private void useSelectedProfile() {
        int row = profileTable.getSelectedRow();
        if (row >= 0) {
            AutoTuneProfile profile = tableModel.profileAt(row);
            profileCombo.setSelectedItem(profile);
            applyProfile(profile);
        }
    }

    private void applyProfile(AutoTuneProfile profile) {
        if (lastResult == null) {
            return;
        }
        boolean legacy = lastResult.calibration == null;
        DetectionConfig tuned = legacy
                ? legacyConfig(lastResult.optimizedConfig, host.currentConfig())
                : CalibratedAutoTuner.configFor(lastResult.calibration, host.currentConfig(), profile);
        Map<JSpinner, Number> before = snapshotCoreValues();
        host.applyTunedConfig(tuned);
        Map<JSpinner, Number> after = snapshotCoreValues();
        if (legacy) {
            before.keySet().retainAll(legacyTunedSettings);
            after.keySet().retainAll(legacyTunedSettings);
        }
        valuesBeforeTune.clear();
        valuesBeforeTune.putAll(before);
        tunedValues.clear();
        tunedValues.putAll(after);
        appliedProfile = lastResult.calibration != null ? profile : (AutoTuneProfile) profileCombo.getSelectedItem();
        appliedAlgorithm = selectedAlgorithm();
        tableModel.fireTableDataChanged();
        selectTableRow(appliedProfile);
        refreshMarkers();
    }

    /**
     * The current settings with the values the legacy tuner chose. Its result is a copy of the settings at the start
     * of the run, so applying it whole would undo changes made since then.
     */
    private static DetectionConfig legacyConfig(DetectionConfig result, DetectionConfig current) {
        DetectionConfig tuned = current.clone();
        tuned.detectionSigmaMultiplier = result.detectionSigmaMultiplier;
        tuned.growSigmaMultiplier = result.growSigmaMultiplier;
        tuned.minDetectionPixels = result.minDetectionPixels;
        tuned.maxMaskOverlapFraction = result.maxMaskOverlapFraction;
        tuned.maxStarJitter = result.maxStarJitter;
        return tuned;
    }

    private void selectTableRow(AutoTuneProfile profile) {
        for (int row = 0; row < tableModel.getRowCount(); row++) {
            if (tableModel.profileAt(row) == profile) {
                profileTable.setRowSelectionInterval(row, row);
                return;
            }
        }
    }

    private Map<JSpinner, Number> snapshotCoreValues() {
        Map<JSpinner, Number> values = new LinkedHashMap<>();
        for (JSpinner spinner : coreMarkers.keySet()) {
            values.put(spinner, (Number) spinner.getValue());
        }
        return values;
    }

    private boolean editedSinceTune() {
        for (Map.Entry<JSpinner, Number> entry : tunedValues.entrySet()) {
            if (!sameValue((Number) entry.getKey().getValue(), entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** Supplies the saved value of each core setting, so values changed since the last save are marked. */
    void setSavedValues(java.util.function.Function<JSpinner, Number> savedValues) {
        this.savedValues = savedValues;
        refreshMarkers();
    }

    /** Number of core settings that differ from the saved configuration. */
    int unsavedCoreCount() {
        int count = 0;
        for (JSpinner spinner : coreLabels.keySet()) {
            Number saved = savedValues.apply(spinner);
            if (saved != null && !sameValue((Number) spinner.getValue(), saved)) {
                count++;
            }
        }
        return count;
    }

    private void refreshUnsavedMarks() {
        Color normal = UIManager.getColor("Label.foreground");
        for (Map.Entry<JSpinner, JLabel> entry : coreLabels.entrySet()) {
            Number saved = savedValues.apply(entry.getKey());
            boolean unsaved = saved != null && !sameValue((Number) entry.getKey().getValue(), saved);
            entry.getValue().setForeground(unsaved ? DetectionConfigurationPanel.accentColor() : normal);
            entry.getValue().setToolTipText(unsaved
                    ? "Changed since the last save (saved: " + formatNumber(saved) + ")"
                    : entry.getKey().getToolTipText());
        }
    }

    private void refreshMarkers() {
        refreshUnsavedMarks();
        for (Map.Entry<JSpinner, JLabel> entry : coreMarkers.entrySet()) {
            Number tuned = tunedValues.get(entry.getKey());
            boolean marked = tuned != null && sameValue((Number) entry.getKey().getValue(), tuned);
            JLabel marker = entry.getValue();
            marker.setText(marked ? "●" : " ");
            Number before = valuesBeforeTune.get(entry.getKey());
            marker.setToolTipText(marked
                    ? "Set by Auto-Tune (" + profileName(appliedProfile) + ")" + (before == null ? "" : "; before: " + formatNumber(before))
                    : null);
        }
        if (!tuning && appliedProfile != null) {
            statusLabel.setForeground(UIManager.getColor("Label.foreground"));
            statusLabel.setText(profileName(appliedProfile) + " settings applied"
                    + (editedSinceTune() ? " · manual changes since the tune" : "")
                    + ". Click Detect Moving Targets to run; Save keeps them for the next start.");
        }
        host.tuningStateChanged();
    }

    private void showProblem(String text) {
        statusLabel.setForeground(warningColor());
        statusLabel.setText("<html><div style='width: 480px;'>" + text + "</div></html>");
    }

    /**
     * The calibrated tuner measures every setting once and each profile only picks from those measurements, so the
     * profile box chooses what is applied after the run; for the legacy tuner it steers the search itself.
     */
    private void updateProfileLabel() {
        boolean calibrated = algorithmCombo.getSelectedItem() != AutoTunerRunner.Algorithm.LEGACY;
        profileLabel.setText(calibrated ? "Apply after run:" : "Profile:");
        String profiles = "Low: fewest noise detections. Medium: in between. High (default): close to the noise level.<br>"
                + "Maximum: as sensitive as possible, with many more candidates to review (for small sensors or targeted searches for faint objects).";
        profileCombo.setToolTipText(calibrated
                ? "<html>The run measures every setting once and picks the best one for <b>every</b> profile.<br>"
                + "This box only chooses which profile is applied when the run finishes; you can switch in the table afterwards without re-running.<br>"
                + profiles + "</html>"
                : "<html>The legacy tuner searches for this profile only; another profile needs another run.<br>" + profiles + "</html>");
        profileLabel.setToolTipText(profileCombo.getToolTipText());
        tableNoteLabel.setText("<html><div style='width: 480px;'>" + (calibrated
                ? "One run measures every setting. Each profile then picks the most sensitive one within its budget of noise detections per MPix per frame (the ≤ value)."
                : "The legacy tuner tunes one profile per run and keeps the current star mask settings.") + "</div></html>");
    }

    private AutoTunerRunner.Algorithm selectedAlgorithm() {
        return (AutoTunerRunner.Algorithm) algorithmCombo.getSelectedItem();
    }

    /** Shows the last run of this tuner (or an empty table), so results of the other tuner are never mixed in. */
    private void showResultsOf(AutoTunerRunner.Algorithm tuner) {
        lastResult = resultsByTuner.get(tuner);
        tableModel.fireTableDataChanged();
        if (isApplied(appliedProfile)) {
            selectTableRow(appliedProfile);
        }
        refreshControls();
    }

    /** The legacy tuner has no Maximum profile (it would tune like High), so it is not offered there. */
    private void updateProfileChoices() {
        boolean legacy = selectedAlgorithm() == AutoTunerRunner.Algorithm.LEGACY;
        AutoTuneProfile current = (AutoTuneProfile) profileCombo.getSelectedItem();
        DefaultComboBoxModel<AutoTuneProfile> model = new DefaultComboBoxModel<>();
        for (AutoTuneProfile profile : AutoTuneProfile.values()) {
            if (!(legacy && profile == AutoTuneProfile.MAXIMUM)) {
                model.addElement(profile);
            }
        }
        adjustingProfiles = true;
        try {
            profileCombo.setModel(model);
            profileCombo.setSelectedItem(legacy && current == AutoTuneProfile.MAXIMUM ? AutoTuneProfile.HIGH : current);
        } finally {
            adjustingProfiles = false;
        }
    }

    /** Whether this profile of the shown tuner is the one applied to the settings. */
    private boolean isApplied(AutoTuneProfile profile) {
        return profile != null && profile == appliedProfile && appliedAlgorithm == selectedAlgorithm();
    }

    private void refreshControls() {
        boolean canTune = sessionReady && !tuning;
        runButton.setEnabled(canTune);
        runButton.setText(tuning ? "Tuning…" : "Run Auto-Tune");
        algorithmCombo.setEnabled(canTune);
        profileCombo.setEnabled(!tuning);
        previewButton.setEnabled(sessionReady && !tuning);
        for (JButton action : coreActions) {
            action.setEnabled(sessionReady && !tuning);
        }
        reportButton.setEnabled(lastResult != null && !tuning);
        useProfileButton.setEnabled(lastResult != null && lastResult.calibration != null && !tuning
                && profileTable.getSelectedRow() >= 0);
        if (lastResult != null) {
            lastRunLabel.setText(runTextByTuner.getOrDefault(selectedAlgorithm(), " "));
        } else if (sessionName == null) {
            lastRunLabel.setText("Import monochrome frames to run Auto-Tune.");
        } else if (!sessionReady) {
            lastRunLabel.setText("Auto-Tune needs enough monochrome frames (convert colour frames first).");
        } else {
            lastRunLabel.setText(String.format(Locale.US, "The %s tuner has not run yet for %s (%d frames, %.1f MPix).",
                    selectedAlgorithm() == AutoTunerRunner.Algorithm.LEGACY ? "legacy" : "calibrated",
                    sessionName, sessionFrames, sessionMegapixels()));
        }
    }

    private double sessionMegapixels() {
        return sessionWidth * (double) sessionHeight / 1e6;
    }

    // ==========================================
    // TABLE
    // ==========================================

    private final class ProfileTableModel extends AbstractTableModel {
        private final List<AutoTuneProfile> rows = new ArrayList<>();
        /** The columns shown for the selected tuner, as indexes into {@link #COLUMNS}. */
        private int[] columns = CALIBRATED_COLUMNS;

        @Override
        public void fireTableDataChanged() {
            int[] wanted = selectedAlgorithm() == AutoTunerRunner.Algorithm.LEGACY ? LEGACY_COLUMNS : CALIBRATED_COLUMNS;
            if (wanted != columns) {
                columns = wanted;
                rows.clear();
                fireTableStructureChanged();
                applyColumnWidths();
            }
            rows.clear();
            if (lastResult != null) {
                if (lastResult.calibration != null) {
                    for (AutoTuneProfile profile : AutoTuneProfile.values()) {
                        if (lastResult.calibration.chosen[profile.ordinal()] != null) {
                            rows.add(profile);
                        }
                    }
                } else {
                    rows.add((AutoTuneProfile) profileCombo.getSelectedItem());
                }
            }
            super.fireTableDataChanged();
        }

        AutoTuneProfile profileAt(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        /** Which of {@link #COLUMNS} the model column shows. */
        int columnId(int modelColumn) {
            return columns[modelColumn];
        }

        void applyColumnWidths() {
            for (int i = 0; i < columns.length; i++) {
                profileTable.getColumnModel().getColumn(i).setPreferredWidth(COLUMN_WIDTHS[columns[i]]);
            }
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return "<html><center>" + COLUMNS[columns[column]] + "</center></html>";
        }

        @Override
        public Object getValueAt(int row, int column) {
            AutoTuneProfile profile = rows.get(row);
            String name = (isApplied(profile) ? "● " : "   ") + profileName(profile);
            CalibratedAutoTuner.Calibration calibration = lastResult.calibration;
            if (calibration == null) {
                DetectionConfig c = lastResult.optimizedConfig;
                switch (columns[column]) {
                    case COL_PROFILE: return name;
                    case COL_DETECTION: return String.format(Locale.US, "%.2f / %.2f / %d", c.detectionSigmaMultiplier, c.growSigmaMultiplier, c.minDetectionPixels);
                    case COL_OVERLAP: return String.format(Locale.US, "%.2f", c.maxMaskOverlapFraction);
                    case COL_JITTER: return String.format(Locale.US, "%.2f", c.maxStarJitter);
                    default: return "—";
                }
            }
            CalibratedAutoTuner.Candidate cand = calibration.chosen[profile.ordinal()];
            double frameArea = sessionMegapixels() * sessionFrames;
            switch (columns[column]) {
                case COL_PROFILE:
                    return name + "  (≤ " + BigDecimal.valueOf(CalibratedAutoTuner.FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[profile.ordinal()])
                            .stripTrailingZeros().toPlainString() + ")";
                case COL_DETECTION:
                    return String.format(Locale.US, "%.2f / %.2f / %d", cand.sigma, cand.growSigma, cand.minPixels);
                case COL_STAR_MASK:
                    return String.format(Locale.US, "%.2f / %.2f / %d", cand.masterSigma, cand.masterGrowSigma, cand.masterMinPixels);
                case COL_OVERLAP:
                    return String.format(Locale.US, "%.2f", cand.maskOverlap);
                case COL_JITTER:
                    return String.format(Locale.US, "%.2f", calibration.jitter);
                case COL_NOISE:
                    return String.format(Locale.US, "%.2f", cand.falsePositivesPerMpixFrame)
                            + (calibration.withinBudget[profile.ordinal()] ? "" : "  ⚠");
                case COL_EXPECTED_NOISE:
                    if (frameArea <= 0) {
                        return "—";
                    }
                    if (cand.falsePositivesPerMpixFrame <= 0) {
                        return "< " + formatCount(Math.ceil(cand.falsePositiveUpperPerMpixFrame * frameArea));
                    }
                    return "≈ " + formatCount(Math.round(cand.falsePositivesPerMpixFrame * frameArea));
                case COL_TEST_STARS:
                    return String.format(Locale.US, "%.0f %%", 100 * cand.recoveredFraction);
                case COL_SNR_LIMIT:
                    return Double.isNaN(cand.snr50) ? "—" : String.format(Locale.US, "%.1f", cand.snr50);
                case COL_SKY_MASKED:
                    return String.format(Locale.US, "%.1f %%", 100 * cand.maskCoverage);
                default:
                    return "";
            }
        }
    }

    private final class ProfileCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            AutoTuneProfile profile = tableModel.profileAt(row);
            c.setFont(c.getFont().deriveFont(isApplied(profile) ? Font.BOLD : Font.PLAIN));
            int columnId = tableModel.columnId(table.convertColumnIndexToModel(column));
            setHorizontalAlignment(columnId == COL_PROFILE ? LEFT : CENTER);
            boolean overBudget = columnId == COL_NOISE && lastResult != null && lastResult.calibration != null
                    && !lastResult.calibration.withinBudget[profile.ordinal()];
            if (!isSelected) {
                c.setForeground(overBudget ? warningColor() : table.getForeground());
            }
            if (columnId == COL_PROFILE && isApplied(profile) && !isSelected) {
                c.setForeground(DetectionConfigurationPanel.accentColor());
            }
            if (overBudget) {
                setToolTipText(String.format(Locale.US, "No setting met the %s budget of %.2f noise detections per MPix per frame; the cleanest one was used.",
                        profileName(profile), CalibratedAutoTuner.FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[profile.ordinal()]));
            } else {
                setToolTipText(null);
            }
            return c;
        }
    }

    // ==========================================
    // HELPERS
    // ==========================================

    static String profileName(AutoTuneProfile profile) {
        return profile == null ? "" : profile.displayName();
    }

    private static final String PROFILE_PREFERENCE = "autoTune.profile";

    /** The profile chosen last time, or High. */
    private static AutoTuneProfile savedProfile() {
        try {
            String saved = java.util.prefs.Preferences.userNodeForPackage(AutoTuneOverviewPanel.class).get(PROFILE_PREFERENCE, null);
            return saved == null ? AutoTuneProfile.HIGH : AutoTuneProfile.parse(saved);
        } catch (Exception e) {
            return AutoTuneProfile.HIGH;
        }
    }

    /** Remembers the profile the user chose (not the automatic switch when the legacy tuner hides Maximum). */
    private void rememberProfile() {
        AutoTuneProfile profile = (AutoTuneProfile) profileCombo.getSelectedItem();
        if (adjustingProfiles || profile == null) {
            return;
        }
        try {
            java.util.prefs.Preferences.userNodeForPackage(AutoTuneOverviewPanel.class).put(PROFILE_PREFERENCE, profile.name());
        } catch (Exception ignored) {
            // Not remembered; the choice still applies to this session.
        }
    }

    private static boolean sameValue(Number a, Number b) {
        return a != null && b != null && Math.abs(a.doubleValue() - b.doubleValue()) < 1e-9;
    }

    private static String formatNumber(Number value) {
        if (value instanceof Integer || value instanceof Long) {
            return value.toString();
        }
        return String.format(Locale.US, "%.2f", value.doubleValue());
    }

    private static String formatCount(double value) {
        return String.format(Locale.US, "%,d", (long) value);
    }

    private static String formatDuration(long seconds) {
        return seconds < 60 ? seconds + " s" : (seconds / 60) + " min " + (seconds % 60) + " s";
    }

    private static Color warningColor() {
        Color color = UIManager.getColor("Actions.Yellow");
        return color != null ? color : new Color(0xE0A030);
    }

    /**
     * Two components side by side, each taking half of the width, when both fit at their preferred widths; on a
     * narrower page the second goes under the first.
     */
    static final class TwoColumnLayout implements LayoutManager {
        private final int horizontalGap;
        private final int verticalGap;
        private Boolean lastSideBySide;

        TwoColumnLayout(int horizontalGap, int verticalGap) {
            this.horizontalGap = horizontalGap;
            this.verticalGap = verticalGap;
        }

        private boolean sideBySide(Container parent, Component first, Component second) {
            int width = parent.getWidth();
            if (width <= 0) {
                return true;
            }
            Insets insets = parent.getInsets();
            int halfWidth = (width - insets.left - insets.right - horizontalGap) / 2;
            return halfWidth >= Math.max(first.getPreferredSize().width, second.getPreferredSize().width);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return size(parent, false);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return size(parent, true);
        }

        private Dimension size(Container parent, boolean minimum) {
            Insets insets = parent.getInsets();
            if (parent.getComponentCount() < 2) {
                return new Dimension(insets.left + insets.right, insets.top + insets.bottom);
            }
            Component first = parent.getComponent(0);
            Component second = parent.getComponent(1);
            Dimension a = minimum ? first.getMinimumSize() : first.getPreferredSize();
            Dimension b = minimum ? second.getMinimumSize() : second.getPreferredSize();
            Dimension size = minimum || !sideBySide(parent, first, second)
                    ? new Dimension(Math.max(a.width, b.width), a.height + verticalGap + b.height)
                    : new Dimension(a.width + horizontalGap + b.width, Math.max(a.height, b.height));
            if (minimum) {
                size.height = a.height + verticalGap + b.height;
            }
            size.width += insets.left + insets.right;
            size.height += insets.top + insets.bottom;
            return size;
        }

        @Override
        public void layoutContainer(Container parent) {
            if (parent.getComponentCount() < 2) {
                return;
            }
            Insets insets = parent.getInsets();
            Component first = parent.getComponent(0);
            Component second = parent.getComponent(1);
            int width = parent.getWidth() - insets.left - insets.right;
            boolean sideBySide = sideBySide(parent, first, second);
            if (lastSideBySide != null && lastSideBySide != sideBySide) {
                // The preferred height depends on the arrangement; let the page measure again.
                SwingUtilities.invokeLater(parent::revalidate);
            }
            lastSideBySide = sideBySide;
            if (sideBySide) {
                int half = (width - horizontalGap) / 2;
                int height = Math.max(first.getPreferredSize().height, second.getPreferredSize().height);
                first.setBounds(insets.left, insets.top, half, height);
                second.setBounds(insets.left + half + horizontalGap, insets.top, half, height);
            } else {
                int firstHeight = first.getPreferredSize().height;
                first.setBounds(insets.left, insets.top, width, firstHeight);
                second.setBounds(insets.left, insets.top + firstHeight + verticalGap, width, second.getPreferredSize().height);
            }
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }

    private static JPanel column() {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        return column;
    }

    private static JLabel hint(String html) {
        return new JLabel("<html><div style='color: #999999; font-size: 12px; width: 400px;'>" + html + "</div></html>");
    }

    private static JComponent left(JComponent component) {
        component.setAlignmentX(Component.LEFT_ALIGNMENT);
        return component;
    }
}
