package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.AppConfig;
import eu.startales.spacepixels.config.ApplicationSettings;
import eu.startales.spacepixels.preparation.FramePreparationService;
import eu.startales.spacepixels.preparation.PreparationInputs;
import eu.startales.spacepixels.preparation.PreparationInputs.Role;
import eu.startales.spacepixels.preparation.PreparationProcess;
import eu.startales.spacepixels.preparation.PreparationRequest;
import eu.startales.spacepixels.preparation.SirilInstallation;
import eu.startales.spacepixels.session.ApplicationStorage;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Preparation form, input readiness and owned installation checks; heavy processing is delegated as an immutable request. */
final class PreparationPanel extends JPanel implements AutoCloseable {
    /** Follow-up choices are captured with the recipe, never read from live controls after a job starts. */
    record Selection(PreparationRequest request, boolean importFrames) { }

    /** A checked executable and the exact CLI identity it returned. */
    record Installation(Path executable, String version) { }

    /** Installation inspection seam used by UI contract tests without launching an external application. */
    @FunctionalInterface interface Probe { Installation check(Path selected) throws Exception; }

    private final ApplicationSettings settings;
    private final ApplicationStorage storage;
    private final BooleanSupplier closing;
    private final Consumer<Selection> run;
    private final Probe probe;
    private final SirilRow siril;
    private final Map<Role, JTextField> inputs = new EnumMap<>(Role.class);
    private final Map<Role, JLabel> counts = new EnumMap<>(Role.class);
    private final Map<Role, JComboBox<PreparationInputs.Candidate>> foundInputs = new EnumMap<>(Role.class);
    private final Map<Role, JPanel> foundRows = new EnumMap<>(Role.class);
    private final List<JComponent> editors = new ArrayList<>();
    private final JTextField root = new JTextField(28);
    private final JTextField output = new JTextField(28);
    private final JComboBox<PreparationRequest.Sensor> sensor = new JComboBox<>(PreparationRequest.Sensor.values());
    private final JComboBox<PreparationRequest.Bayer> bayer = new JComboBox<>(PreparationRequest.Bayer.values());
    private final JComboBox<PreparationRequest.RowOrder> rowOrder = new JComboBox<>(PreparationRequest.RowOrder.values());
    private final JComboBox<Integer> offsetX = new JComboBox<>(new Integer[]{0, 1});
    private final JComboBox<Integer> offsetY = new JComboBox<>(new Integer[]{0, 1});
    private final JCheckBox rolesConfirmed = new JCheckBox("<html>These folders contain the intended lights<br>and matching calibration frames.</html>");
    private final JCheckBox recipeConfirmed = new JCheckBox("<html>I confirm this recipe, including<br>omitted calibration and<br>camera interpretation.</html>");
    private final JCheckBox stack = new JCheckBox("Generate a reference stack");
    private final JCheckBox importFrames = new JCheckBox("Import frames after preparation", true);
    private final JCheckBox keepWorkingFiles = new JCheckBox("Keep working files");
    private final JButton prepare = new JButton("Prepare with Siril");
    private final JButton preview = new JButton("Preview processing instructions");
    private final JTextArea readiness = new JTextArea(17, 25);
    private final JLabel feedback = new JLabel("Check Siril, map the input folders and inspect the inputs.");
    private final JPanel cfaOptions = new JPanel(new GridBagLayout());
    private SwingWorker<?, ?> inspection;
    private long inspectionVersion;
    private FramePreparationService.Inputs inspected;
    private Path scannedRoot;
    private boolean updating;
    private boolean busy;
    private boolean closed;

    /** Builds the production form with owned native-tool probes and explicit callbacks to the application shell. */
    PreparationPanel(ApplicationSettings settings, ApplicationStorage storage, BooleanSupplier closing,
                     Consumer<Selection> run) {
        this(settings, storage, closing, run, PreparationPanel::checkInstallation);
    }

    /** Injects installation inspection for deterministic panel tests, without substituting processing behaviour. */
    PreparationPanel(ApplicationSettings settings, ApplicationStorage storage, BooleanSupplier closing,
                     Consumer<Selection> run, Probe probe) {
        this.settings = settings;
        this.storage = storage;
        this.closing = closing;
        this.run = run;
        this.probe = probe;
        setLayout(new BorderLayout(UiStyle.space(12), UiStyle.space(12)));
        setBorder(BorderFactory.createEmptyBorder(UiStyle.space(14), UiStyle.space(16), UiStyle.space(14), UiStyle.space(16)));
        WidthTrackingPanel form = new WidthTrackingPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(heading("Prepare an observing session"));
        form.add(help("Calibrate and register individual exposures for SpacePixels. Source files remain untouched."));
        JPanel integrations = section("Siril integration");
        siril = new SirilRow(settings.getConfig().sirilExecutablePath);
        integrations.add(siril);
        integrations.add(help("<i>PixInsight WBPP: use Import Aligned Frames for individual calibrated and registered exposures (FITS or supported XISF).</i>"));
        form.add(integrations);
        JPanel folders = section("1  Input folders");
        JButton scan = new JButton("Scan and suggest folders");
        scan.setToolTipText("Replace the input mapping with a fresh scan; keep target, camera, filter and exposure groups separate.");
        scan.addActionListener(event -> scan());
        folders.add(pathRow("Observation session (optional)", root, false, scan));
        folders.add(help("Optional shortcut: scan a session folder to suggest input folders, or choose the lights and calibration inputs directly below."));
        for (Role role : Role.values()) {
            JTextField field = new JTextField(24);
            JLabel count = new JLabel("Not inspected");
            inputs.put(role, field);
            counts.put(role, count);
            JPanel roleRow = pathRow(role == Role.LIGHTS ? "Lights (required)" : role + " (optional; folder or master FITS)", field, role != Role.LIGHTS, null);
            JComboBox<PreparationInputs.Candidate> choices = new JComboBox<>();
            choices.setRenderer(new DefaultListCellRenderer() {
                /** Shows the group relative to the scanned session; the full path remains available as a tooltip. */
                @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                    JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
                    if (value instanceof PreparationInputs.Candidate candidate) {
                        Path displayed = scannedRoot != null && candidate.path().startsWith(scannedRoot) ? scannedRoot.relativize(candidate.path()) : candidate.path();
                        label.setText(displayed + (candidate.master() ? " (existing master FITS)" : " (" + candidate.images() + " image candidates)"));
                        label.setToolTipText(candidate.path().toString());
                    } else label.setText("Choose a found input…");
                    return label;
                }
            });
            choices.setPreferredSize(new Dimension(UiStyle.space(310), choices.getPreferredSize().height));
            JLabel foundLabel = new JLabel("Found:");
            AccessibleControls.label(foundLabel, choices, role + " found inputs");
            choices.getAccessibleContext().setAccessibleName(role + " found inputs");
            JPanel foundRow = row(foundLabel, choices);
            foundRow.setVisible(false);
            foundInputs.put(role, choices);
            foundRows.put(role, foundRow);
            JPanel detail = new JPanel(new BorderLayout());
            detail.add(count, BorderLayout.NORTH);
            detail.add(foundRow, BorderLayout.SOUTH);
            roleRow.add(detail, BorderLayout.SOUTH);
            choices.addActionListener(event -> {
                if (!updating && choices.getSelectedItem() instanceof PreparationInputs.Candidate candidate) field.setText(candidate.path().toString());
            });
            editors.add(choices);
            folders.add(roleRow);
            changed(field, this::invalidateInputs);
        }
        JButton inspect = new JButton("Inspect inputs");
        inspect.setToolTipText("Count candidate images and verify that each selected role can be captured; RAW decoding occurs in the external tool.");
        inspect.addActionListener(event -> inspect());
        editors.add(inspect);
        folders.add(row(inspect));
        folders.add(help("Choose one found group per role when a scan finds multiple targets, cameras, filters or exposures. Existing masters require an explicit file choice. Extensions identify candidates, not camera compatibility; confirm that calibration matches the selected lights."));
        folders.add(rolesConfirmed);
        form.add(folders);

        JPanel camera = section("2  Camera interpretation");
        cfaOptions.setAlignmentX(LEFT_ALIGNMENT);
        camera.add(row(new JLabel("Input data (required):"), sensor));
        addCfaRow(0, "Bayer pattern", bayer);
        addCfaRow(1, "Row order", rowOrder);
        addCfaRow(2, "X offset (px)", offsetX);
        addCfaRow(3, "Y offset (px)", offsetY);
        camera.add(cfaOptions);
        camera.add(help("A single image plane may still contain a colour mosaic. Unknown Bayer metadata requires an explicit choice. Row order and offsets are applied once; already RGB data are never debayered again."));
        form.add(camera);

        JPanel destination = section("3  Output and next steps");
        destination.add(pathRow("Output parent folder (optional)", output, false, null));
        output.putClientProperty("JTextField.placeholderText", "Use the lights source folder");
        destination.add(help("Optional: leave blank to create the run folder inside the lights source folder, or choose another existing folder. Source images are preserved."));
        keepWorkingFiles.setToolTipText("Keep copied inputs, converted frames and calibration masters. Otherwise remove them after processing, including failed/cancelled runs; final images, scripts, settings, manifest and log remain.");
        for (JCheckBox option : List.of(stack, importFrames, keepWorkingFiles)) {
            option.setAlignmentX(LEFT_ALIGNMENT);
            destination.add(option);
        }
        destination.add(help("Each run creates its own folder, with individual linear 16-bit mono FITS, a log and provenance. One scale/offset is shared by the sequence; RGB uses a fixed arithmetic mean. Plate solve the prepared frames before astrometric work."));
        form.add(destination);
        JScrollPane scroll = new JScrollPane(form);
        scroll.getVerticalScrollBar().setUnitIncrement(UiStyle.space(20));
        scroll.setBorder(BorderFactory.createEmptyBorder());

        JPanel summary = new JPanel(new BorderLayout(UiStyle.space(8), UiStyle.space(10)));
        summary.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder("Preparation readiness"),
                BorderFactory.createEmptyBorder(UiStyle.space(8), UiStyle.space(8), UiStyle.space(8), UiStyle.space(8))));
        readiness.setEditable(false);
        readiness.setLineWrap(true);
        readiness.setWrapStyleWord(true);
        readiness.setFont(UiStyle.bodyFont());
        readiness.setOpaque(false);
        summary.add(new JScrollPane(readiness), BorderLayout.CENTER);
        JPanel actions = new JPanel();
        actions.setLayout(new BoxLayout(actions, BoxLayout.Y_AXIS));
        actions.add(recipeConfirmed);
        actions.add(Box.createVerticalStrut(UiStyle.space(10)));
        prepare.putClientProperty("FlatLaf.style", MainApplicationPanel.PRIMARY_BUTTON_STYLE);
        prepare.addActionListener(event -> start());
        prepare.setToolTipText("Run the confirmed recipe on private copies, validate every registered light, then perform the selected follow-up.");
        preview.addActionListener(event -> preview());
        preview.setToolTipText("Show generated instructions without launching processing or changing source files.");
        actions.add(prepare);
        actions.add(Box.createVerticalStrut(UiStyle.space(8)));
        actions.add(preview);
        summary.add(actions, BorderLayout.SOUTH);
        summary.setPreferredSize(new Dimension(Math.max(UiStyle.space(300), summary.getPreferredSize().width), UiStyle.space(600)));
        add(scroll, BorderLayout.CENTER);
        add(summary, BorderLayout.EAST);
        feedback.setForeground(UiStyle.helpColor());
        add(feedback, BorderLayout.SOUTH);
        editors.addAll(List.of(root, output, sensor, bayer, rowOrder, offsetX, offsetY, rolesConfirmed, recipeConfirmed, stack, importFrames, keepWorkingFiles, scan));
        inputs.values().forEach(editors::add);
        for (JComboBox<?> choice : List.of(sensor, bayer, rowOrder, offsetX, offsetY)) choice.addActionListener(event -> recipeChanged());
        for (JCheckBox choice : List.of(stack, importFrames, keepWorkingFiles)) choice.addActionListener(event -> recipeChanged());
        rolesConfirmed.addActionListener(event -> refresh());
        recipeConfirmed.addActionListener(event -> refresh());
        changed(output, this::recipeChanged);
        changed(root, () -> {
            if (!updating) { clearScanChoices(); invalidateInputs(); }
        });
        sensor.setSelectedIndex(-1);
        refresh();
    }

    /** Applies the shared application job lock; a preparation form cannot launch work beside a detection or import. */
    void setBusy(boolean busy) {
        this.busy = busy;
        for (JComponent editor : editors) editor.setEnabled(!busy && !closed);
        siril.setEditable(!busy && !closed);
        refresh();
    }

    /** Presents completed products even when a later import is not selected. */
    void completed(FramePreparationService.Result result) {
        if (closed) return;
        message("Prepared " + result.frames() + " of " + result.inputFrames() + " frames: " + result.aligned());
        readiness.setText("Preparation complete.\n\n" + result.frames() + " of " + result.inputFrames() + " validated individual exposures."
                + (result.excluded().isEmpty() ? "" : "\nSiril excluded " + result.excluded().size() + " during registration; see preparation.json.")
                + "\n\nOutput: " + result.aligned()
                + "\n\nLogs and provenance: " + result.directory() + "\n\nPlate solve the prepared sequence before astrometric analysis.");
    }

    /** Shows an actionable failure without changing the current imported session or clearing the user's form. */
    void failed(String reason) { if (!closed) { message(reason); readiness.setText(reason); } }

    /** Captures the exact selected values; filesystem inventory and processing run on owned workers. */
    Selection selection() throws IOException {
        if (siril.installation == null) throw new IOException("Check the Siril installation first.");
        Map<Role, Path> paths = inputPaths();
        Path parent = outputParent();
        if (sensor.getSelectedItem() == null) throw new IOException("Choose the input camera interpretation.");
        boolean cfa = sensor.getSelectedItem() == PreparationRequest.Sensor.CFA;
        boolean manualPattern = cfa && bayer.getSelectedItem() != PreparationRequest.Bayer.METADATA;
        PreparationRequest request = new PreparationRequest(siril.installation.executable(), paths,
                (PreparationRequest.Sensor) sensor.getSelectedItem(), cfa ? (PreparationRequest.Bayer) bayer.getSelectedItem() : PreparationRequest.Bayer.METADATA,
                cfa ? (PreparationRequest.RowOrder) rowOrder.getSelectedItem() : PreparationRequest.RowOrder.METADATA,
                manualPattern ? (Integer) offsetX.getSelectedItem() : 0, manualPattern ? (Integer) offsetY.getSelectedItem() : 0,
                parent, stack.isSelected(), keepWorkingFiles.isSelected());
        return new Selection(request, importFrames.isSelected());
    }

    /** Resolves the optional destination from current controls without filesystem I/O; explicit invalid text never falls back. */
    private Path outputParent() throws IOException {
        if (!output.getText().isBlank()) {
            Path selected = path(output.getText());
            if (selected == null) throw new IOException("Invalid output parent folder. Choose an existing folder or leave it blank to use the lights source folder.");
            return selected;
        }
        Path lights = path(inputs.get(Role.LIGHTS).getText());
        if (lights == null) throw new IOException("Choose the lights source folder to use the default output location.");
        return lights;
    }

    /** Maps only explicitly selected roles; empty calibration fields have a visible omitted meaning. */
    private Map<Role, Path> inputPaths() throws IOException {
        Map<Role, Path> paths = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            String text = inputs.get(role).getText();
            Path selected = path(text);
            if (selected == null && !text.isBlank()) throw new IOException("Invalid " + role + " path.");
            if (selected != null) paths.put(role, selected);
        }
        return Map.copyOf(paths);
    }

    /** Replaces the complete mapping, including omitted roles, before running a fresh worker-owned scan. */
    private void scan() {
        updating = true;
        try {
            inputs.values().forEach(field -> field.setText(""));
            clearScanChoices();
        } finally { updating = false; }
        invalidateInputs();
        Path selected = path(root.getText());
        if (selected == null) { message("Choose an observation-session folder first."); return; }
        background(() -> PreparationInputs.scan(selected), value -> {
            updating = true;
            try {
                scannedRoot = selected.toAbsolutePath().normalize();
                for (Role role : Role.values()) {
                    List<PreparationInputs.Candidate> candidates = value.candidates().getOrDefault(role, List.of());
                    JComboBox<PreparationInputs.Candidate> choices = foundInputs.get(role);
                    for (PreparationInputs.Candidate candidate : candidates) choices.addItem(candidate);
                    choices.setSelectedIndex(-1);
                    foundRows.get(role).setVisible(!candidates.isEmpty() && (candidates.size() > 1 || candidates.getFirst().master()));
                }
                value.suggestions().forEach((role, folder) -> inputs.get(role).setText(folder.toString()));
            } finally { updating = false; }
            invalidateInputs();
            message(value.suggestions().containsKey(Role.LIGHTS) ? "Suggested folders; choose any remaining found inputs, verify each role, then inspect."
                    : "No unique lights folder was found. Choose one of the found light groups, or browse to the input folder.");
        });
    }

    /** Removes choices from an earlier session without reading the filesystem or changing an explicitly chosen output folder. */
    private void clearScanChoices() {
        scannedRoot = null;
        foundInputs.values().forEach(JComboBox::removeAllItems);
        foundRows.values().forEach(row -> row.setVisible(false));
    }

    /** Inspects only a captured map; a late result cannot certify fields edited while inspection ran. */
    private void inspect() {
        try {
            Map<Role, Path> paths = inputPaths();
            background(() -> FramePreparationService.inspect(paths), value -> {
            inspected = value;
            for (Role role : Role.values()) {
                List<Path> files = value.files().get(role);
                counts.get(role).setText(files == null ? "Omitted" : value.masters().contains(role) ? "Existing master FITS" : files.size() + " image candidates");
            }
            message("Inputs counted. Confirm role matching and camera interpretation; RAW decoding is checked during preparation.");
            refresh();
            });
        } catch (IOException invalid) { failed(invalid.getMessage()); }
    }

    /** Runs one short operation off the EDT; edits invalidate its delivery and closing cancels its ownership. */
    private <T> void background(Checked<T> operation, Consumer<T> completed) {
        long version = ++inspectionVersion;
        if (inspection != null) inspection.cancel(true);
        message("Inspecting…");
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            /** Performs filesystem reads or instruction generation without blocking Swing. */
            @Override protected T doInBackground() throws Exception { return operation.get(); }
            /** Delivers only the current request while the window is still open. */
            @Override protected void done() {
                if (closed || closing.getAsBoolean() || inspectionVersion != version) return;
                try { completed.accept(get()); } catch (CancellationException ignored) { }
                catch (Exception failure) { failed(reason(failure)); }
            }
        };
        inspection = worker;
        worker.execute();
    }

    /** One checked background operation, including filesystem inspection and recipe generation. */
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }

    /** Requires all explicit confirmations again whenever a recipe control changes. */
    private void recipeChanged() {
        if (updating) return;
        recipeConfirmed.setSelected(false);
        refresh();
    }

    /** Invalidates an earlier file inventory immediately on folder edits. */
    private void invalidateInputs() {
        if (updating) return;
        inspectionVersion++;
        inspected = null;
        rolesConfirmed.setSelected(false);
        recipeConfirmed.setSelected(false);
        counts.values().forEach(label -> label.setText("Not inspected"));
        refresh();
    }

    /** Recomputes readiness from evidence and user choices; candidate suffixes never become decoded-image guarantees. */
    private void refresh() {
        boolean cfa = sensor.getSelectedItem() == PreparationRequest.Sensor.CFA;
        cfaOptions.setVisible(cfa);
        boolean manual = bayer.getSelectedItem() != PreparationRequest.Bayer.METADATA;
        offsetX.setEnabled(!busy && cfa && manual);
        offsetY.setEnabled(!busy && cfa && manual);
        stack.setEnabled(!busy);
        boolean available = siril.installation != null;
        Path destination = null;
        String outputProblem = null;
        try { destination = outputParent(); } catch (IOException invalid) { outputProblem = invalid.getMessage(); }
        boolean ready = !busy && !closed && available && inspected != null && rolesConfirmed.isSelected()
                && recipeConfirmed.isSelected() && sensor.getSelectedItem() != null && destination != null;
        prepare.setEnabled(ready);
        preview.setEnabled(!busy && available && inspected != null);
        StringBuilder text = new StringBuilder(busy ? "A processing job is running.\n\n" : "");
        text.append(available ? "✓ Siril CLI checked\n" : "○ Siril needs an installation check\n");
        text.append(inspected != null ? "✓ Inputs counted\n" : "○ Inspect selected inputs\n");
        text.append(rolesConfirmed.isSelected() ? "✓ Folder roles confirmed\n" : "○ Confirm folder roles\n");
        text.append(recipeConfirmed.isSelected() ? "✓ Recipe confirmed\n" : "○ Confirm the recipe\n");
        if (outputProblem != null) text.append("○ ").append(outputProblem).append('\n');
        text.append("\nEffective recipe\nEngine: Siril\nInput: ")
                .append(sensor.getSelectedItem() == null ? "choose camera interpretation" : sensor.getSelectedItem());
        if (cfa) text.append("\nBayer: ").append(bayer.getSelectedItem()).append("\nRows: ").append(rowOrder.getSelectedItem());
        if (inspected != null) {
            for (Role role : Role.values()) text.append('\n').append(role).append(": ")
                    .append(inspected.files().containsKey(role) ? inspected.files().get(role).size() : "omitted");
            if (!inspected.files().containsKey(Role.DARKS)) text.append("\n\nNo dark subtraction will be performed.");
            if (inspected.files().containsKey(Role.FLATS) && !inspected.files().containsKey(Role.BIASES) && !inspected.files().containsKey(Role.DARK_FLATS)) {
                text.append("\nFlats will be normalized without bias or dark-flat subtraction.");
            }
        }
        text.append("\n\nRegistration: reference size, dark margins retained for drift/dither analysis.");
        if (destination != null) text.append("\n\nOutput parent").append(output.getText().isBlank() ? " (lights source folder): " : ": ").append(destination)
                .append("\nA new spacepixels_prepared_… run folder will be created here.");
        text.append("\n\nReference stack: ").append(stack.isSelected() ? "yes" : "no");
        text.append("\nAfter preparation: ").append(importFrames.isSelected() ? "import, then close this window" : "keep output");
        text.append("\nWorking files: ").append(keepWorkingFiles.isSelected() ? "retained" : "removed; final images and logs kept");
        text.append("\n\nThe external tool validates RAW decoding. Every requested light must produce a registered exposure. A partial sequence is never imported automatically.");
        readiness.setText(text.toString());
        readiness.setCaretPosition(0);
        revalidate();
    }

    /** Starts only an enabled, still-confirmed request, rechecking the native application job slot in the shell. */
    private void start() {
        if (!prepare.isEnabled()) return;
        try { run.accept(selection()); } catch (Exception failure) { failed(reason(failure)); }
    }

    /** Shows generated instructions without executing external software or admitting a heavy job. */
    private void preview() {
        try {
            Selection selected = selection();
            background(() -> FramePreparationService.preview(selected.request()), text -> {
                JTextArea details = new JTextArea(text, 28, 80);
                details.setEditable(false);
                details.setCaretPosition(0);
                JOptionPane.showMessageDialog(this, new JScrollPane(details), "Preparation instructions", JOptionPane.INFORMATION_MESSAGE);
            });
        } catch (Exception failure) { failed(reason(failure)); }
    }

    /** A labelled path editor that keeps its preferred width, shrinks in narrow forms and retains a keyboard-accessible chooser. */
    private JPanel pathRow(String title, JTextField field, boolean files, JButton extra) {
        JPanel panel = new JPanel(new BorderLayout(UiStyle.space(6), UiStyle.space(3))) {
            /** A path editor retains its natural height rather than absorbing a tall window's spare vertical space. */
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(3), 0, UiStyle.space(5), 0));
        JLabel label = new JLabel(title);
        AccessibleControls.label(label, field, title);
        panel.add(label, BorderLayout.NORTH);
        JPanel edit = new JPanel(new BorderLayout(UiStyle.space(6), 0)) {
            /** Long paths scroll inside the editor instead of stretching it across a wide window. */
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        edit.add(field, BorderLayout.CENTER);
        JButton browse = new JButton("Browse…");
        browse.setToolTipText(files ? "Choose a folder of calibration exposures or an existing master FITS file." : "Choose the folder for " + title.toLowerCase(java.util.Locale.ROOT) + ".");
        browse.addActionListener(event -> {
            JFileChooser chooser = WindowSizing.fileChooser(path(field.getText()) == null ? null : path(field.getText()).toFile());
            chooser.setFileSelectionMode(files ? JFileChooser.FILES_AND_DIRECTORIES : JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle(title);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) field.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        editors.add(browse);
        Box buttons = fixedButtons(browse, extra);
        edit.add(buttons, BorderLayout.EAST);
        JPanel line = new JPanel();
        line.setLayout(new BoxLayout(line, BoxLayout.X_AXIS));
        line.add(edit);
        line.add(Box.createHorizontalGlue());
        panel.add(line, BorderLayout.CENTER);
        return panel;
    }

    /** Adds a CFA option with an associated label and natural editor size. */
    private void addCfaRow(int row, String title, JComponent value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(3, 3, 3, 12);
        JLabel label = new JLabel(title);
        AccessibleControls.label(label, value, title);
        cfaOptions.add(label, c);
        c.gridx = 1; c.weightx = 1;
        cfaOptions.add(value, c);
    }

    /** A theme-aware section containing vertically stacked, naturally sized controls. */
    private static JPanel section(String title) {
        JPanel panel = new JPanel() {
            /** Section height follows its controls, leaving spare viewport height below the complete form. */
            @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                BorderFactory.createEmptyBorder(UiStyle.space(7), UiStyle.space(9), UiStyle.space(7), UiStyle.space(9))));
        return panel;
    }

    /** A wrapping natural-size row, so controls remain reachable at enlarged interface sizes. */
    private static JPanel row(Component... components) {
        JPanel panel = new JPanel(new WrapLayout(FlowLayout.LEFT, UiStyle.space(6), UiStyle.space(4)));
        panel.setAlignmentX(LEFT_ALIGNMENT);
        for (Component component : components) panel.add(component);
        return panel;
    }

    /** Keeps paired actions at their natural width; the adjacent path editor absorbs width changes without wrapping buttons away. */
    private static Box fixedButtons(JComponent... components) {
        Box box = Box.createHorizontalBox();
        for (JComponent component : components) if (component != null) {
            if (box.getComponentCount() > 0) box.add(Box.createHorizontalStrut(UiStyle.space(6)));
            box.add(component);
        }
        return box;
    }

    /** A relative-size heading that follows the selected appearance theme. */
    private static JLabel heading(String title) {
        JLabel label = new JLabel(title);
        label.setFont(UiStyle.sectionFont());
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    /** Re-measures guidance after its allocated width changes, so the first narrow layout reserves every wrapped line. */
    private static JComponent help(String text) {
        WrappingHelpLabel label = new WrappingHelpLabel(text, 720);
        label.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int width = -1;
            /** Invalidates cached BoxLayout heights once the actual wrapping width is known. */
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                if (width != label.getWidth()) { width = label.getWidth(); label.revalidate(); }
            }
        });
        return label;
    }

    /** Converts an optional path field, retaining invalid text for a later actionable error. */
    private static Path path(String text) {
        if (text == null || text.isBlank()) return null;
        try { return Path.of(text.strip()); } catch (RuntimeException invalid) { return null; }
    }

    /** Observes edits without reading files or launching work from a document callback. */
    private static void changed(JTextField field, Runnable action) {
        field.getDocument().addDocumentListener(new DocumentListener() {
            /** Invalidates input evidence after inserted text. */
            @Override public void insertUpdate(DocumentEvent event) { action.run(); }
            /** Invalidates input evidence after removed text. */
            @Override public void removeUpdate(DocumentEvent event) { action.run(); }
            /** Treats styled document edits consistently with ordinary text edits. */
            @Override public void changedUpdate(DocumentEvent event) { action.run(); }
        });
    }

    /** Keeps operational messages selectable via the readiness pane and concise in the footer. */
    private void message(String text) {
        String line = text.replace('\n', ' ');
        feedback.setText(line.length() > 130 ? line.substring(0, 127) + "…" : line);
        feedback.setToolTipText(text);
    }

    /** Unwraps worker completion failures for a concise user-facing explanation. */
    private static String reason(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.ExecutionException || failure instanceof java.util.concurrent.CompletionException)) failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Probes only the selected CLI with a private workspace and joins its process tree before returning. */
    private static Installation checkInstallation(Path selected) throws Exception {
        Path executable = SirilInstallation.resolve(selected);
        Path workspace = Files.createTempDirectory("spacepixels-tool-check-");
        try {
            var result = PreparationProcess.run(SirilInstallation.probe(executable, workspace), workspace, Map.of("LC_ALL", "C", "LANG", "C"),
                    workspace.resolve("check.log"), Duration.ofSeconds(15), Thread.currentThread()::isInterrupted);
            return new Installation(executable, SirilInstallation.describeProbe(result.output()));
        } finally {
            try (var entries = Files.walk(workspace)) {
                for (Path entry : entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
    }

    /** Cancels form-owned inspection/probes; an admitted processing job is cancelled by the owning preparation window. */
    @Override public void close() {
        if (closed) return;
        closed = true;
        inspectionVersion++;
        if (inspection != null) inspection.cancel(true);
        siril.close();
    }

    /** One external application's path, bounded probe, saved selection and stale-result guard. */
    private final class SirilRow extends JPanel implements AutoCloseable {
        private final JTextField path = new JTextField(27);
        private final JLabel status = new JLabel("Not configured");
        private final JButton browse = new JButton("Browse…");
        private final JButton check = new JButton("Check");
        private SwingWorker<Installation, Void> worker;
        private Installation installation;
        private long version;
        private boolean settingPath;

        /** Creates an installation row without launching the tool or automatically saving any preference. */
        SirilRow(String saved) {
            setLayout(new BorderLayout(UiStyle.space(6), UiStyle.space(3)));
            setAlignmentX(LEFT_ALIGNMENT);
            path.setText(saved == null ? "" : saved);
            path.setToolTipText("Choose the installation folder or its command-line executable. Siril "
                    + SirilInstallation.MINIMUM_VERSION + " or newer is required.");
            path.getAccessibleContext().setAccessibleName("Siril installation path");
            if (!path.getText().isBlank()) status.setText("Saved path · not checked in this window");
            add(path, BorderLayout.CENTER);
            add(fixedButtons(browse, check), BorderLayout.EAST);
            JButton download = AccessibleControls.link("Get Siril", "Download Siril", () -> {
                try { Desktop.getDesktop().browse(URI.create(SirilInstallation.WEBSITE)); } catch (Exception failure) { message(reason(failure)); }
            });
            add(row(status, download), BorderLayout.SOUTH);
            browse.setToolTipText("Choose the installation folder, or select the command-line executable directly.");
            check.setToolTipText("Identify the selected command-line tool without processing any images.");
            browse.addActionListener(event -> {
                JFileChooser chooser = WindowSizing.fileChooser(null);
                chooser.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
                chooser.setDialogTitle("Siril installation or CLI executable");
                if (chooser.showOpenDialog(PreparationPanel.this) == JFileChooser.APPROVE_OPTION) {
                    path.setText(chooser.getSelectedFile().getAbsolutePath());
                    check();
                }
            });
            check.addActionListener(event -> check());
            changed(path, () -> {
                if (settingPath) return;
                version++;
                installation = null;
                status.setText(path.getText().isBlank() ? "Not configured" : "Changed path · check required");
                recipeChanged();
            });
        }

        /** Runs a request-specific check; later edits, checks or closure make its result obsolete. */
        private void check() {
            Path selected = PreparationPanel.path(path.getText());
            long request = ++version;
            installation = null;
            status.setText("Checking…");
            check.setEnabled(false);
            if (worker != null) worker.cancel(true);
            worker = new SwingWorker<>() {
                /** Performs the bounded CLI probe outside Swing. */
                @Override protected Installation doInBackground() throws Exception { return probe.check(selected); }
                /** Applies only the latest selected installation and saves its explicit preference snapshot. */
                @Override protected void done() {
                    if (closed || closing.getAsBoolean() || version != request) return;
                    check.setEnabled(!busy);
                    try {
                        installation = get();
                        settingPath = true;
                        path.setText(installation.executable().toString());
                        settingPath = false;
                        status.setText("Available · " + installation.version());
                        AppConfig config = settings.getConfig();
                        config.sirilExecutablePath = installation.executable().toString();
                        AppConfig snapshot = config.copy();
                        storage.submit("Application defaults", () -> { settings.save(snapshot); return null; }).whenComplete((value, failure) -> EventQueue.invokeLater(() -> {
                            if (!closed && version == request && failure != null) {
                                status.setText("Available · path not saved");
                                message("The selected tool works, but its path was not saved: " + reason(failure));
                            }
                        }));
                    } catch (CancellationException ignored) { }
                    catch (Exception failure) { status.setText("Cannot use this installation"); message(reason(failure)); }
                    recipeChanged();
                }
            };
            worker.execute();
            refresh();
        }

        /** Keeps the single installation row at its natural height on the first layout pass. */
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }

        /** Follows the shared job lock while keeping a running probe's Check action disabled. */
        void setEditable(boolean enabled) { path.setEnabled(enabled); browse.setEnabled(enabled); check.setEnabled(enabled && (worker == null || worker.isDone())); }

        /** Cancels the owned probe; its process helper still joins cleanup before its worker body exits. */
        @Override public void close() { version++; if (worker != null) worker.cancel(true); }
    }
}
