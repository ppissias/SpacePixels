package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.util.IterativeDetectionPlan;
import eu.startales.spacepixels.util.IterativeDetectionPipelineService;

import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;

final class IterativeDetectionDialog extends JDialog {
    record Selection(File directory, IterativeDetectionPlan plan, AutoTuneProfile autoTuneProfile,
                     int autoTuneFrames, boolean autoPrepare, boolean keepNegative, int availableFrames) {}

    private final JTextField directory = new JTextField(36);
    private final JSpinner start = UiStyle.spinner(new SpinnerNumberModel(5, 5, Integer.MAX_VALUE, 1));
    private final JSpinner increase = UiStyle.spinner(new SpinnerNumberModel(5, 1, Integer.MAX_VALUE, 1));
    private final JSpinner maximum = UiStyle.spinner(new SpinnerNumberModel(50, 0, Integer.MAX_VALUE, 1));
    private final JCheckBox autoTune = new JCheckBox("Auto-Tune once before the passes (calibrated)");
    private final JComboBox<AutoTuneProfile> profile = new JComboBox<>(AutoTuneProfile.values());
    private final JSpinner tuneFrames = UiStyle.spinner(new SpinnerNumberModel(30, 5, Integer.MAX_VALUE, 1));
    private final JCheckBox prepare = new JCheckBox("Prepare non-ready inputs as 16-bit monochrome copies", true);
    private final JCheckBox keepNegative = new JCheckBox("Keep negative pixel values by adding an offset", true);
    private final JLabel preview = new JLabel("Choose a source directory.");
    private final JLabel settings = new JLabel("Uses current Detection Settings, including unsaved edits.");
    private final JButton run = new JButton("Run iterations");
    private final Timer scanDelay;
    private SwingWorker<Integer, Void> scan;
    private int availableFrames;
    private Selection selection;

    IterativeDetectionDialog(Window owner, File initialDirectory) {
        super(owner, "Iterative detection configuration", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(16, 16, 16, 16));
        JPanel source = new JPanel(new BorderLayout(8, 0));
        JButton browse = new JButton("Browse…");
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(directory.getText().isBlank() ? null : new File(directory.getText()));
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setDialogTitle("Choose iterative source directory");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) directory.setText(chooser.getSelectedFile().getAbsolutePath());
        });
        source.add(directory, BorderLayout.CENTER);
        source.add(browse, BorderLayout.EAST);
        addRow(form, 0, "Source directory", source);
        addRow(form, 1, "Start frames", start);
        addRow(form, 2, "Increase by", increase);
        addRow(form, 3, "Maximum frames (0 = all)", maximum);
        addRow(form, 4, "Planned passes", preview);
        addRow(form, 5, "", autoTune);
        profile.setSelectedItem(AutoTuneProfile.MEDIUM);
        profile.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof AutoTuneProfile item ? item.displayName() : value,
                        index, selected, focus);
            }
        });
        addRow(form, 6, "Auto-Tune sensitivity", profile);
        addRow(form, 7, "Auto-Tune sample limit", tuneFrames);
        addRow(form, 8, "", settings);
        addRow(form, 9, "", prepare);
        addRow(form, 10, "", keepNegative);
        JLabel manual = new JLabel("<html>For manual tuning, import the dataset normally and use Detection Settings<br>"
                + "and Preview Frame to experiment. Then run iteratively with Auto-Tune disabled.</html>");
        manual.setFont(manual.getFont().deriveFont(Font.ITALIC));
        addRow(form, 11, "", manual);
        JLabel explanation = new JLabel("<html>Each pass spans the dataset and builds its own master. Larger subsets are selected afresh.<br>"
                + "The open session stays unchanged. Results are written beside the source dataset.</html>");
        addRow(form, 12, "", explanation);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        cancel.addActionListener(event -> dispose());
        run.addActionListener(event -> accept());
        run.setEnabled(false);
        buttons.add(cancel);
        buttons.add(run);
        add(form, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(run);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        scanDelay = new Timer(250, event -> scanDirectory());
        scanDelay.setRepeats(false);
        directory.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
            private void changed() {
                if (scan != null) scan.cancel(true);
                scan = null;
                availableFrames = 0;
                run.setEnabled(false);
                preview.setText("Scanning directory…");
                scanDelay.restart();
            }
        });
        for (JSpinner spinner : new JSpinner[]{start, increase, maximum}) spinner.addChangeListener(event -> updatePreview());
        autoTune.addActionListener(event -> updateTuning());
        prepare.addActionListener(event -> keepNegative.setEnabled(prepare.isSelected()));
        updateTuning();
        if (initialDirectory != null) directory.setText(initialDirectory.getAbsolutePath());
        pack();
        setLocationRelativeTo(owner);
    }

    Selection selection() { return selection; }

    private static void addRow(JPanel panel, int row, String title, JComponent control) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(5, 0, 5, 12);
        JLabel label = new JLabel(title);
        label.setLabelFor(control instanceof JPanel source ? source.getComponent(0) : control);
        panel.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(control, constraints);
    }

    private void scanDirectory() {
        if (scan != null) scan.cancel(true);
        File source = new File(directory.getText());
        scan = new SwingWorker<>() {
            @Override protected Integer doInBackground() throws Exception { return IterativeDetectionPipelineService.countInputFiles(source); }
            @Override protected void done() {
                if (scan != this || isCancelled() || !isDisplayable()) return;
                try {
                    availableFrames = get();
                    updatePreview();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    preview.setText(failure.getCause().getMessage());
                    run.setEnabled(false);
                }
            }
        };
        scan.execute();
    }

    private IterativeDetectionPlan plan() {
        return new IterativeDetectionPlan((Integer) start.getValue(), (Integer) increase.getValue(), (Integer) maximum.getValue());
    }

    private void updatePreview() {
        run.setEnabled(false);
        if (availableFrames < 5) {
            preview.setText("At least 5 input frames are required (found " + availableFrames + ").");
            return;
        }
        try {
            List<Integer> counts = plan().frameCounts(availableFrames);
            String sizes = counts.size() <= 12 ? counts.toString()
                    : counts.subList(0, 10) + " … " + counts.getLast() + " (" + counts.size() + " passes)";
            preview.setText(availableFrames + " available; passes: " + sizes);
            run.setEnabled(true);
        } catch (IllegalArgumentException failure) {
            preview.setText(failure.getMessage());
        }
    }

    private void updateTuning() {
        profile.setEnabled(autoTune.isSelected());
        tuneFrames.setEnabled(autoTune.isSelected());
        settings.setText(autoTune.isSelected() ? "Auto-Tune adjusts settings once; every pass then uses the tuned settings."
                : "Uses current Detection Settings, including unsaved edits.");
    }

    private void accept() {
        try {
            for (JSpinner spinner : new JSpinner[]{start, increase, maximum}) spinner.commitEdit();
            if (autoTune.isSelected()) tuneFrames.commitEdit();
            updatePreview();
            if (!run.isEnabled()) return;
            selection = new Selection(new File(directory.getText()), plan(),
                    autoTune.isSelected() ? (AutoTuneProfile) profile.getSelectedItem() : null,
                    (Integer) tuneFrames.getValue(), prepare.isSelected(), keepNegative.isSelected(), availableFrames);
            dispose();
        } catch (java.text.ParseException | IllegalArgumentException failure) {
            preview.setText("Enter valid whole numbers for the frame limits.");
        }
    }

    @Override public void dispose() {
        scanDelay.stop();
        if (scan != null) scan.cancel(true);
        super.dispose();
    }
}
