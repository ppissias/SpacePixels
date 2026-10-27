package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.session.ApplicationStorage;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Desktop-only appearance editor. Saving uses the owned settings queue; changes apply on the next application start. */
final class AppearancePanel extends JPanel {
    private final JComboBox<AppearanceSettings.Theme> theme = new JComboBox<>(AppearanceSettings.Theme.values());
    private final JSpinner scale = UiStyle.spinner(new SpinnerNumberModel(100, 100, 300, 25));
    private final JTextField custom = new JTextField(28);
    private final JButton browse = new JButton("Browse…");
    private final JButton clear = new JButton("Clear");
    private final JButton save = new JButton("Save appearance");
    private final JButton reset = new JButton("Restore defaults");
    private final WrappingHelpLabel status = new WrappingHelpLabel(" ", 780);
    private final ApplicationStorage storage;
    private final BooleanSupplier closing;
    private final File preferences;
    private final Runnable restart;

    /** Builds a scrollable form from the appearance already loaded before startup; performs no file I/O on the EDT. */
    AppearancePanel(AppearanceSettings opening, String warning, File preferences,
                    ApplicationStorage storage, BooleanSupplier closing, Runnable restart) {
        super(new BorderLayout());
        this.storage = storage;
        this.closing = closing;
        this.preferences = preferences;
        this.restart = restart;
        browse.setToolTipText("Choose a FlatLaf .properties file to customise the theme, with an option to save and restart.");
        clear.setToolTipText("Clear the custom style file selection. Save and restart to use the built-in theme.");
        save.setToolTipText("Save these appearance settings, then choose when to restart SpacePixels to apply them.");
        reset.setToolTipText("Select Dark at 100% and clear the custom style file. Save and restart to apply these defaults.");
        ((JSpinner.DefaultEditor) scale.getEditor()).getTextField().setColumns(4);
        Form form = new Form();
        JLabel title = new JLabel("Appearance");
        title.setFont(UiStyle.sectionFont());
        form.row(title);
        form.note("Choose comfortable colours and text size. Save your changes, then restart SpacePixels to apply them to every window.");
        form.field("Theme", theme, "High contrast dark uses white text and clearer borders on a near-black background.");
        form.field("Interface size (%)", scale, "100% follows your system size. Larger values enlarge text and controls in addition to your operating system's display scaling.");
        JPanel fileRow = new JPanel(new BorderLayout(UiStyle.space(8), 0));
        fileRow.add(custom, BorderLayout.CENTER);
        JPanel fileActions = new WrappingControlsPanel(UiStyle.space(8), UiStyle.space(4));
        fileActions.add(browse);
        fileActions.add(clear);
        fileRow.add(fileActions, BorderLayout.SOUTH);
        JLabel fileLabel = new JLabel("Custom style file (optional)");
        AccessibleControls.label(fileLabel, custom, "An optional FlatLaf .properties file that overrides the selected theme.");
        form.row(fileLabel);
        form.compact(fileRow);
        form.note("Advanced: choose a FlatLaf .properties file to customise colours or fonts. Clear uses the built-in theme. An example is included in config/appearance-example.properties.");
        JPanel actions = new WrappingControlsPanel(UiStyle.space(8), UiStyle.space(4));
        actions.add(save);
        actions.add(reset);
        form.row(actions);
        status.getAccessibleContext().setAccessibleName("Appearance status");
        form.notes.add(status);
        form.row(status);
        form.fillRemainder();
        add(new JScrollPane(form), BorderLayout.CENTER);

        show(opening);
        if (warning != null) status.setText(warning + " The built-in appearance is available; correct the file or restore defaults.");
        browse.addActionListener(event -> chooseFile());
        clear.addActionListener(event -> custom.setText(""));
        reset.addActionListener(event -> {
            show(AppearanceSettings.defaults());
            message("Defaults selected. Save and restart SpacePixels to apply them.");
        });
        save.addActionListener(event -> save(false));
    }

    /** Shows values only; restoring defaults does not change a file or other application settings. */
    private void show(AppearanceSettings settings) {
        theme.setSelectedItem(settings.theme());
        scale.setValue(settings.scalePercent());
        custom.setText(settings.customProperties());
    }

    /** Selects a custom style file and offers to save appearance settings and restart. */
    private void chooseFile() {
        JFileChooser chooser = WindowSizing.fileChooser(null);
        chooser.setDialogTitle("Choose appearance properties");
        chooser.setFileFilter(new FileNameExtensionFilter("FlatLaf properties (*.properties)", "properties"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            custom.setText(chooser.getSelectedFile().getAbsolutePath());
            Object[] choices = {"Save and restart", "Keep editing"};
            int answer = JOptionPane.showOptionDialog(this,
                    "Custom style file selected. Save appearance settings and restart SpacePixels to apply the new look?\n"
                            + "Running work will finish first.",
                    "Apply custom appearance", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                    null, choices, choices[1]);
            if (answer == 0) save(true);
        }
    }

    /** Saves checked controls on the settings worker; an agreed restart runs on the EDT only after a successful save. */
    void save(boolean restartAfterSave) {
        AppearanceSettings requested;
        try {
            scale.commitEdit();
            requested = new AppearanceSettings((AppearanceSettings.Theme) theme.getSelectedItem(),
                    ((Number) scale.getValue()).intValue(), custom.getText());
        } catch (ParseException | IllegalArgumentException failure) {
            message("Choose a valid interface size between 100% and 300%.");
            return;
        }
        setEditable(false);
        message("Saving appearance…");
        storage.submit("Appearance", () -> {
            AppearanceTheme.prepare(requested, preferences);
            requested.save(preferences);
            return requested;
        }).whenComplete((saved, failure) -> EventQueue.invokeLater(() -> {
            if (closing.getAsBoolean()) return;
            setEditable(true);
            message(failure == null ? "Appearance saved. Restart SpacePixels to apply your changes."
                    : "Appearance was not saved: " + failure.getMessage());
            if (failure == null) {
                if (restartAfterSave) {
                    restart.run();
                    return;
                }
                Object[] choices = {"Restart now", "Later"};
                int answer = JOptionPane.showOptionDialog(this,
                        "Appearance saved. Restart SpacePixels to apply your changes?\nRunning work will finish first.",
                        "Restart SpacePixels", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                        null, choices, choices[1]);
                if (answer == 0 && !closing.getAsBoolean()) restart.run();
            }
        }));
    }

    /** Keeps save/recovery feedback visible even when large text puts the last row below the viewport. */
    private void message(String text) {
        status.setText(text);
        EventQueue.invokeLater(() -> {
            if (!isShowing()) return;
            Window owner = SwingUtilities.getWindowAncestor(this);
            if (owner != null) owner.validate();
            status.scrollRectToVisible(new Rectangle(0, 0, status.getWidth(), status.getHeight()));
        });
    }

    /** Prevents edits during this form's own write without participating in detection's job lock. */
    private void setEditable(boolean editable) {
        for (JComponent control : new JComponent[]{theme, scale, custom, browse, clear, save, reset}) {
            control.setEnabled(editable);
        }
    }

    /** A single-column form whose help text rewraps to the viewport before its content height is measured. */
    private static final class Form extends WidthTrackingPanel {
        private final List<WrappingHelpLabel> notes = new ArrayList<>();
        private int nextRow;

        /** Leaves vertical space below the controls and keeps each editor at its natural, scaled height. */
        Form() {
            setLayout(new GridBagLayout());
            setBorder(BorderFactory.createEmptyBorder(UiStyle.space(18), UiStyle.space(22), UiStyle.space(18), UiStyle.space(22)));
        }

        /** Adds one full-width component without a fixed height. */
        void row(Component component) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = nextRow++;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.anchor = GridBagConstraints.NORTHWEST;
            c.insets = new Insets(0, 0, UiStyle.space(10), 0);
            add(component, c);
        }

        /** Adds a body-sized wrapping explanation. */
        void note(String text) {
            WrappingHelpLabel label = new WrappingHelpLabel(text, 780);
            notes.add(label);
            row(label);
        }

        /** Associates an editor with its label and accessible description. */
        void field(String title, JComponent editor, String help) {
            JLabel label = new JLabel(title);
            AccessibleControls.label(label, editor, help);
            row(label);
            compact(editor);
            note(help);
        }

        /** Uses the editor's text/option-based preferred width instead of stretching it across the viewport. */
        void compact(JComponent editor) {
            JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0)) {
                /** A narrow viewport scrolls instead of clipping a native-sized editor. */
                @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            };
            holder.add(editor);
            row(holder);
        }

        /** Consumes unused height without stretching the editors. */
        void fillRemainder() {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = nextRow;
            c.weighty = 1;
            add(Box.createVerticalGlue(), c);
        }

        /** Computes the actual wrapped height at any window width and startup font size. */
        @Override public Dimension getPreferredSize() {
            int width = getParent() == null ? UiStyle.space(780) : getParent().getWidth();
            Insets insets = getInsets();
            for (WrappingHelpLabel label : notes) label.wrapAt(Math.max(100, width - insets.left - insets.right));
            return super.getPreferredSize();
        }
    }
}
