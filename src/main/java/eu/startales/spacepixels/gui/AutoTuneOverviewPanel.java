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

    private static final String[] COLUMNS = {
            "Profile", "Detection σ / grow / min px", "Star mask σ", "False / MPix / frame",
            "Expected false (session)", "Recovered", "SNR50", "Sky masked"};
    private static final String[] COLUMN_TOOLTIPS = {
            "Sensitivity profile and its false-detection budget per megapixel per frame. ● marks the profile in use.",
            "Per-frame detection threshold, grow threshold and minimum object size chosen for this profile.",
            "Detection threshold of the master star mask that hides stars.",
            "Measured false detections caused by these settings (noise and star leakage), per megapixel per frame. ⚠: no setting met the profile's budget, so the cleanest one was used.",
            "False detections to expect in a full run on this session: the measured rate × sensor megapixels × frames.",
            "Share of synthetic stars (peak SNR 2 to 15) that were detected and kept.",
            "Peak signal-to-noise ratio at which half of the synthetic stars are recovered. Lower is more sensitive.",
            "Share of the sky hidden by the star mask; nothing can be detected there."};

    private final Host host;

    private final JComboBox<AutoTunerRunner.Algorithm> algorithmCombo = new JComboBox<>(AutoTunerRunner.Algorithm.values());
    private final JComboBox<AutoTuneProfile> profileCombo = new JComboBox<>(AutoTuneProfile.values());
    private final JButton runButton = new JButton("Run Auto-Tune");
    private final JButton useProfileButton = new JButton("Use Selected Profile");
    private final JButton reportButton = new JButton("Measurement Report…");
    private final JButton previewButton = new JButton("Preview on Frame…");
    private final JLabel profileLabel = new JLabel();
    private final JLabel tableNoteLabel = new JLabel(" ");
    private final JLabel lastRunLabel = new JLabel(" ");
    private final JLabel statusLabel = new JLabel(" ");
    private final ProfileTableModel tableModel = new ProfileTableModel();
    private final JTable profileTable = new JTable(tableModel);

    /** Core settings in display order, with their tuned-value markers. */
    private final Map<JSpinner, JLabel> coreMarkers = new LinkedHashMap<>();
    private final Map<JSpinner, Number> tunedValues = new LinkedHashMap<>();
    private final Map<JSpinner, Number> valuesBeforeTune = new LinkedHashMap<>();
    private final JPanel coreGrid = new JPanel(new GridBagLayout());
    private int coreRowCount;
    private final JPanel analysesGrid = new JPanel(new GridBagLayout());
    private int analysisRowCount;

    private JTransientAutoTuner.AutoTunerResult lastResult;
    private AutoTunerRunner.Algorithm lastAlgorithm;
    private AutoTuneProfile appliedProfile;
    private String sessionName;
    private int sessionFrames;
    private int sessionWidth;
    private int sessionHeight;
    private boolean sessionReady;
    private boolean tuning;
    private long tuneStartedMillis;
    private String tunedSessionText;

    AutoTuneOverviewPanel(Host host) {
        this.host = host;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(10, 20, 20, 20));

        JLabel intro = new JLabel("<html><div style='color: #999999; font-size: 12px; padding-bottom: 6px; width: 780px;'>"
                + "Start here. One Auto-Tune run measures how many false detections each setting causes and how many synthetic "
                + "faint stars it still finds, for all four profiles. The other tabs hold the detailed settings."
                + "</div></html>");
        add(left(intro));

        add(left(DetectionConfigurationPanel.createSectionHeader("Auto-Tune")));
        add(left(buildAutoTuneBox()));

        // Core settings and analyses side by side, so both are visible without scrolling.
        JPanel coreColumn = column();
        coreColumn.add(left(DetectionConfigurationPanel.createSectionHeader("Core Settings")));
        coreColumn.add(left(hint("The settings Auto-Tune chooses. <span style='color: #4da6ff;'>●</span> marks a value set by "
                + "Auto-Tune; editing it removes the mark. Hover a setting for its description.")));
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

        JPanel columns = new JPanel(new GridLayout(1, 2, 40, 0));
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
        profileCombo.setSelectedItem(AutoTuneProfile.BALANCED);
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

        algorithmCombo.addActionListener(e -> updateProfileLabel());
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
                return column < 0 ? null : COLUMN_TOOLTIPS[profileTable.convertColumnIndexToModel(column)];
            }
        });
        profileTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        profileTable.setRowHeight(26);
        profileTable.setFillsViewportHeight(true);
        profileTable.getTableHeader().setReorderingAllowed(false);
        profileTable.setDefaultRenderer(Object.class, new ProfileCellRenderer());
        int[] widths = {165, 190, 95, 140, 165, 90, 70, 95};
        for (int i = 0; i < widths.length; i++) {
            profileTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
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
        Dimension tableSize = new Dimension(Math.min(980, sum(widths) + 4), profileTable.getRowHeight() * 4 + 32);
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
        box.add(left(actions));

        statusLabel.setBorder(new EmptyBorder(8, 2, 0, 0));
        box.add(left(statusLabel));
        return box;
    }

    /** Adds a core setting row; the spinner stays owned by the settings panel. */
    void addCoreSetting(String group, String title, String description, JSpinner spinner) {
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
            tableModel.fireTableDataChanged();
        }
        refreshControls();
    }

    void tuneStarted() {
        tuning = true;
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
            lastResult = result;
            lastAlgorithm = (AutoTunerRunner.Algorithm) algorithmCombo.getSelectedItem();
            long seconds = result.calibration != null
                    ? Math.round(result.calibration.elapsedSeconds)
                    : (System.currentTimeMillis() - tuneStartedMillis) / 1000;
            tunedSessionText = String.format(Locale.US, "Last run: %s, %d frames, %d×%d (%.1f MPix) · %s tuner · %s",
                    sessionName == null ? "session" : sessionName, sessionFrames, sessionWidth, sessionHeight,
                    sessionMegapixels(), lastAlgorithm == AutoTunerRunner.Algorithm.LEGACY ? "legacy" : "calibrated",
                    formatDuration(seconds));
            tableModel.fireTableDataChanged();
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
        DetectionConfig tuned = lastResult.calibration != null
                ? CalibratedAutoTuner.configFor(lastResult.calibration, host.currentConfig(), profile)
                : lastResult.optimizedConfig;
        Map<JSpinner, Number> before = snapshotCoreValues();
        host.applyTunedConfig(tuned);
        valuesBeforeTune.clear();
        valuesBeforeTune.putAll(before);
        tunedValues.clear();
        tunedValues.putAll(snapshotCoreValues());
        appliedProfile = lastResult.calibration != null ? profile : (AutoTuneProfile) profileCombo.getSelectedItem();
        tableModel.fireTableDataChanged();
        selectTableRow(appliedProfile);
        refreshMarkers();
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

    private void refreshMarkers() {
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
                    + ". They are in use for detection; Save keeps them for the next start.");
        }
        host.tuningStateChanged();
    }

    private void showProblem(String text) {
        statusLabel.setForeground(warningColor());
        statusLabel.setText("<html><div style='width: 700px;'>" + text + "</div></html>");
    }

    /**
     * The calibrated tuner measures every setting once and each profile only picks from those measurements, so the
     * profile box chooses what is applied after the run; for the legacy tuner it steers the search itself.
     */
    private void updateProfileLabel() {
        boolean calibrated = algorithmCombo.getSelectedItem() != AutoTunerRunner.Algorithm.LEGACY;
        profileLabel.setText(calibrated ? "Apply after run:" : "Profile:");
        String profiles = "Conservative: fewest false detections. Balanced: medium. Aggressive: close to the noise level.<br>"
                + "Maximum: as sensitive as possible, with many more candidates to review (for small sensors or targeted searches for faint objects).";
        profileCombo.setToolTipText(calibrated
                ? "<html>The run measures every setting once and picks the best one for <b>every</b> profile.<br>"
                + "This box only chooses which profile is applied when the run finishes; you can switch in the table afterwards without re-running.<br>"
                + profiles + "</html>"
                : "<html>The legacy tuner searches for this profile only; another profile needs another run.<br>" + profiles + "</html>");
        profileLabel.setToolTipText(profileCombo.getToolTipText());
        tableNoteLabel.setText(calibrated
                ? "One run measures every setting. Each profile then picks the most sensitive one within its budget of false detections per MPix per frame (the ≤ value)."
                : "The legacy tuner tunes one profile per run.");
    }

    private void refreshControls() {
        boolean canTune = sessionReady && !tuning;
        runButton.setEnabled(canTune);
        runButton.setText(tuning ? "Tuning…" : "Run Auto-Tune");
        algorithmCombo.setEnabled(canTune);
        profileCombo.setEnabled(!tuning);
        previewButton.setEnabled(sessionReady && !tuning);
        reportButton.setEnabled(lastResult != null && !tuning);
        useProfileButton.setEnabled(lastResult != null && lastResult.calibration != null && !tuning
                && profileTable.getSelectedRow() >= 0);
        if (lastResult != null) {
            lastRunLabel.setText(tunedSessionText);
        } else if (sessionName == null) {
            lastRunLabel.setText("Import monochrome frames to run Auto-Tune.");
        } else if (!sessionReady) {
            lastRunLabel.setText("Auto-Tune needs enough monochrome frames (convert colour frames first).");
        } else {
            lastRunLabel.setText(String.format(Locale.US, "Not run yet for %s (%d frames, %.1f MPix).",
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

        @Override
        public void fireTableDataChanged() {
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

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            AutoTuneProfile profile = rows.get(row);
            String name = (profile == appliedProfile ? "● " : "   ") + profileName(profile);
            CalibratedAutoTuner.Calibration calibration = lastResult.calibration;
            if (calibration == null) {
                DetectionConfig c = lastResult.optimizedConfig;
                switch (column) {
                    case 0: return name;
                    case 1: return String.format(Locale.US, "%.2f / %.2f / %d", c.detectionSigmaMultiplier, c.growSigmaMultiplier, c.minDetectionPixels);
                    case 2: return String.format(Locale.US, "%.2f", c.masterSigmaMultiplier);
                    default: return "—";
                }
            }
            CalibratedAutoTuner.Candidate cand = calibration.chosen[profile.ordinal()];
            double frameArea = sessionMegapixels() * sessionFrames;
            switch (column) {
                case 0:
                    return name + "  (≤ " + BigDecimal.valueOf(CalibratedAutoTuner.FALSE_POSITIVE_BUDGET_PER_MPIX_FRAME[profile.ordinal()])
                            .stripTrailingZeros().toPlainString() + ")";
                case 1:
                    return String.format(Locale.US, "%.2f / %.2f / %d", cand.sigma, cand.growSigma, cand.minPixels);
                case 2:
                    return String.format(Locale.US, "%.2f", cand.masterSigma);
                case 3:
                    return String.format(Locale.US, "%.2f", cand.falsePositivesPerMpixFrame)
                            + (calibration.withinBudget[profile.ordinal()] ? "" : "  ⚠");
                case 4:
                    if (frameArea <= 0) {
                        return "—";
                    }
                    if (cand.falsePositivesPerMpixFrame <= 0) {
                        return "< " + formatCount(Math.ceil(cand.falsePositiveUpperPerMpixFrame * frameArea));
                    }
                    return "≈ " + formatCount(Math.round(cand.falsePositivesPerMpixFrame * frameArea));
                case 5:
                    return String.format(Locale.US, "%.0f %%", 100 * cand.recoveredFraction);
                case 6:
                    return Double.isNaN(cand.snr50) ? "—" : String.format(Locale.US, "%.1f", cand.snr50);
                case 7:
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
            c.setFont(c.getFont().deriveFont(profile == appliedProfile ? Font.BOLD : Font.PLAIN));
            setHorizontalAlignment(column == 0 ? LEFT : CENTER);
            boolean overBudget = column == 3 && lastResult != null && lastResult.calibration != null
                    && !lastResult.calibration.withinBudget[profile.ordinal()];
            if (!isSelected) {
                c.setForeground(overBudget ? warningColor() : table.getForeground());
            }
            if (column == 0 && profile == appliedProfile && !isSelected) {
                c.setForeground(DetectionConfigurationPanel.accentColor());
            }
            if (overBudget) {
                setToolTipText(String.format(Locale.US, "No setting met the %s budget of %.2f per MPix per frame; the cleanest one was used.",
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
        if (profile == null) {
            return "";
        }
        String name = profile.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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

    private static int sum(int[] values) {
        int total = 0;
        for (int v : values) {
            total += v;
        }
        return total;
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
