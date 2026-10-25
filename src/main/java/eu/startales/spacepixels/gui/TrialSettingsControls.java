package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.DetectionSetting;
import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** EDT-owned trial inputs shared by the existing detection previews; computation stays with each viewer. */
final class TrialSettingsControls {
    /** A detached numeric input specification; no live spinner model is retained. */
    record Input(DetectionSetting<? extends Number> setting, Number value, Comparable<?> minimum,
                 Comparable<?> maximum, Number step) {
        /** Copies a settings page's current value and range without sharing its mutable model. */
        static Input copyOf(DetectionSetting<? extends Number> setting, SpinnerNumberModel model) {
            return new Input(setting, model.getNumber(), model.getMinimum(), model.getMaximum(), model.getStepSize());
        }
    }

    /** One editor and its opening/applied values; automatic pair alignment does not mark an untouched field edited. */
    private final class Entry {
        final DetectionSetting<? extends Number> setting;
        final JSpinner spinner;
        final CheckedNumberEditor editor;
        final JLabel label;
        Number initial;
        Number baseline;
        boolean edited;

        /** Creates an independent editor with the common validation and image-focus rules. */
        Entry(Input input) {
            setting = input.setting();
            initial = baseline = input.value();
            spinner = UiStyle.spinner(new SpinnerNumberModel(initial, input.minimum(), input.maximum(), input.step()));
            editor = new CheckedNumberEditor(spinner, initial instanceof Integer ? "#0" : "#0.0", TrialSettingsControls.this::showNote);
            spinner.setEditor(editor);
            spinner.setRequestFocusEnabled(false);
            JFormattedTextField field = editor.getTextField();
            field.setColumns(5);
            bind(field, "ENTER", () -> {
                if (commit(this)) focusImage.run();
            });
            bind(field, "ESCAPE", () -> {
                field.setValue(spinner.getValue());
                focusImage.run();
            });
            label = new JLabel(setting.title());
            AccessibleControls.label(label, spinner, setting.help());
            String help = "<html><div style='width: 320px;'>" + setting.help() + "</div></html>";
            label.setToolTipText(help);
            spinner.setToolTipText(help);
            spinner.addChangeListener(event -> changed(this));
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final JButton apply = new JButton("Apply to Settings");
    private final JButton reset = new JButton("Reset");
    private final JLabel note = new JLabel(" ");
    private final Timer debounce;
    private final Runnable focusImage;
    private final Function<Map<String, Number>, DetectionConfig> applyChanges;
    private DetectionConfig basis;
    private boolean adjusting;
    private boolean editable = true;
    private boolean active = true;
    private boolean refreshPending;
    private String message;

    /** Owns independent inputs, a 300 ms refresh delay, and a callback that merges edited fields into live settings. */
    TrialSettingsControls(List<Input> inputs, DetectionConfig basis, Runnable refresh, Runnable focusImage,
                          Function<Map<String, Number>, DetectionConfig> applyChanges) {
        this.basis = basis.clone();
        this.focusImage = focusImage;
        this.applyChanges = applyChanges;
        debounce = new Timer(300, event -> {
            if (active && editable) {
                refreshPending = false;
                refresh.run();
            }
        });
        debounce.setRepeats(false);
        for (Input input : inputs) entries.add(new Entry(input));
        apply.setToolTipText("Apply only the values edited in this preview; other current settings are kept.");
        apply.addActionListener(event -> apply());
        reset.setToolTipText("Restore the values from when this preview opened. Apply to Settings copies the changes.");
        reset.addActionListener(event -> reset());
        showNote();
    }

    /** Builds the horizontal input row used under Preview Frame's image controls. */
    JComponent horizontal() {
        JPanel inputs = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Entry entry : entries) {
            inputs.add(entry.label);
            inputs.add(entry.spinner);
            inputs.add(Box.createHorizontalStrut(6));
        }
        inputs.add(reset);
        inputs.add(apply);
        JPanel panel = new JPanel(new BorderLayout(0, 3));
        panel.add(inputs, BorderLayout.NORTH);
        panel.add(note, BorderLayout.SOUTH);
        ViewerSupport.prepareControls(panel);
        return panel;
    }

    /** Builds the two-column inputs used in the Star Mask Explorer sidebar. */
    JComponent grid() {
        JPanel grid = new JPanel(new GridBagLayout());
        int row = 0;
        for (Entry entry : entries) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = row++;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(3, 0, 3, 12);
            grid.add(entry.label, c);
            c.gridx = 1;
            c.weightx = 1;
            grid.add(entry.spinner, c);
        }
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, grid.getPreferredSize().height));
        return grid;
    }

    /** Builds the common Reset/Apply actions and the validation note for a sidebar. */
    JComponent actions() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(reset);
        buttons.add(apply);
        buttons.setMaximumSize(new Dimension(Integer.MAX_VALUE, buttons.getPreferredSize().height));
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(buttons);
        panel.add(Box.createVerticalStrut(4));
        panel.add(note);
        ViewerSupport.prepareControls(panel);
        return panel;
    }

    /** Takes a fresh opening baseline when a hidden Preview Frame is reopened, discarding its unapplied trials. */
    void load(DetectionConfig settings) {
        adjusting = true;
        try {
            basis = settings.clone();
            for (Entry entry : entries) {
                entry.initial = entry.baseline = entry.setting.read(settings);
                entry.edited = false;
                entry.spinner.setValue(entry.initial);
                entry.editor.getTextField().setValue(entry.initial);
            }
        } finally {
            adjusting = false;
        }
        active = true;
        message = null;
        refreshPending = false;
        debounce.stop();
        showNote();
    }

    /** Copies all trial values over a fresh settings snapshot for this preview's next computation. */
    DetectionConfig snapshot(DetectionConfig base) {
        DetectionConfig trial = base.clone();
        for (Entry entry : entries) write(entry.setting, trial, (Number) entry.spinner.getValue());
        return DetectionConfigRules.align(trial, DetectionConfigRules.Operation.CONFIGURATION).config();
    }

    /** Reads one last-valid trial value without committing unfinished text. */
    Number value(DetectionSetting<? extends Number> setting) {
        return (Number) entries.stream().filter(entry -> entry.setting.equals(setting)).findFirst().orElseThrow().spinner.getValue();
    }

    /** Pauses trial edits and delayed refresh during an application job, retaining any pending refresh for unlock. */
    void setEditable(boolean editable) {
        this.editable = editable;
        for (Entry entry : entries) {
            entry.spinner.setEnabled(editable);
            entry.editor.getTextField().setEnabled(editable);
        }
        reset.setEnabled(editable);
        if (!editable) debounce.stop();
        else if (active && refreshPending) debounce.restart();
        showNote();
    }

    /** Stops delayed work on close and discards unfinished editor text; an explicit load starts a reopened viewer. */
    void stop() {
        active = false;
        debounce.stop();
        refreshPending = false;
        for (Entry entry : entries) entry.editor.getTextField().setValue(entry.spinner.getValue());
        showNote();
    }

    /** Tracks the user's current change, clearing a restored baseline before automatic pair alignment. */
    private void changed(Entry edited) {
        if (adjusting) return;
        edited.edited = !Objects.equals(edited.baseline, edited.spinner.getValue());
        message = null;
        alignValues();
        refreshPending = true;
        if (active && editable) debounce.restart();
        showNote();
    }

    /** Aligns displayed trial pairs without treating an automatic adjustment as an edit of an untouched field. */
    private void alignValues() {
        adjusting = true;
        try {
            DetectionConfig aligned = snapshot(basis);
            for (Entry entry : entries) entry.spinner.setValue(entry.setting.read(aligned));
        } finally {
            adjusting = false;
        }
    }

    /** Validates every field before applying an edited-field delta; a stale session callback cannot report success. */
    private void apply() {
        if (!active || !editable) return;
        for (Entry entry : entries) {
            if (!entry.editor.isTextValid()) {
                entry.editor.getTextField().requestFocusInWindow();
                showNote();
                return;
            }
        }
        if (!commitAll()) return;
        Map<String, Number> changes = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry.edited && !Objects.equals(entry.baseline, entry.spinner.getValue())) {
                changes.put(entry.setting.id(), (Number) entry.spinner.getValue());
            }
        }
        if (changes.isEmpty()) {
            message = "No trial changes to apply.";
        } else {
            DetectionConfig applied = applyChanges.apply(Map.copyOf(changes));
            if (applied == null) {
                message = "These settings could not be applied<br>to the current session.";
                showNote();
                return;
            }
            adjusting = true;
            try {
                basis = applied.clone();
                for (Entry entry : entries) {
                    if (changes.containsKey(entry.setting.id())) {
                        entry.baseline = entry.setting.read(applied);
                        entry.spinner.setValue(entry.baseline);
                        entry.edited = false;
                    }
                }
            } finally {
                adjusting = false;
            }
            alignValues();
            refreshPending = true;
            debounce.restart();
            message = "Edited values applied to the<br>Detection Settings.";
        }
        showNote();
        focusImage.run();
    }

    /** Restores opening trial values without changing the session settings. */
    private void reset() {
        if (!active || !editable) return;
        adjusting = true;
        try {
            for (Entry entry : entries) {
                entry.spinner.setValue(entry.initial);
                entry.editor.getTextField().setValue(entry.initial);
                entry.edited = !Objects.equals(entry.baseline, entry.initial);
            }
        } finally {
            adjusting = false;
        }
        message = null;
        refreshPending = true;
        debounce.restart();
        showNote();
        focusImage.run();
    }

    /** Commits one valid typed trial value, leaving invalid text and its explanation visible. */
    private boolean commit(Entry entry) {
        try {
            entry.spinner.commitEdit();
            return true;
        } catch (ParseException invalid) {
            UIManager.getLookAndFeel().provideErrorFeedback(entry.editor.getTextField());
            showNote();
            return false;
        }
    }

    /** Commits a batch of typed values before pair alignment can replace another field's unfinished text. */
    private boolean commitAll() {
        adjusting = true;
        try {
            for (Entry entry : entries) {
                Object previous = entry.spinner.getValue();
                if (!commit(entry)) return false;
                if (!Objects.equals(previous, entry.spinner.getValue())) {
                    entry.edited = !Objects.equals(entry.baseline, entry.spinner.getValue());
                }
            }
        } finally {
            adjusting = false;
        }
        alignValues();
        return true;
    }

    /** Explains invalid text and makes clear that Enter previews a value while Apply changes the session. */
    private void showNote() {
        for (Entry entry : entries) {
            if (!entry.editor.isTextValid()) {
                Color error = UIManager.getColor("Actions.Red");
                note.setForeground(error != null ? error : new Color(0xDB5860));
                note.setText("<html>" + entry.setting.title() + ":<br>" + entry.editor.expected()
                        + ".<br>The preview keeps the last valid value.</html>");
                apply.setEnabled(false);
                return;
            }
        }
        note.setForeground(UiStyle.helpColor());
        note.setText("<html>" + (!editable ? "Trial settings are paused while a job runs."
                : message != null ? message : "Trial values · Enter previews a typed value.<br>Apply to Settings copies only edited values.") + "</html>");
        apply.setEnabled(active && editable);
    }

    /** Installs editor-local keys, so image navigation never consumes numeric editing keys. */
    private static void bind(JFormattedTextField field, String key, Runnable action) {
        String name = "trial" + key;
        field.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        field.getActionMap().put(name, new AbstractAction() {
            /** Runs the editor action on the EDT. */
            @Override public void actionPerformed(ActionEvent event) { action.run(); }
        });
    }

    /** Writes a numeric value with the definition's declared type; no unchecked binding cast is needed. */
    private static <N extends Number> void write(DetectionSetting<N> setting, DetectionConfig config, Number value) {
        setting.write(config, setting.type().cast(value));
    }
}
