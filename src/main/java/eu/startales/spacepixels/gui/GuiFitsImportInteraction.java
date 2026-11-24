package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BayerMosaic;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.FitsImportInteraction;
import eu.startales.spacepixels.preparation.PreparationRequest;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Synchronous decisions on the EDT, with conversion and progress production left on the import worker. */
public final class GuiFitsImportInteraction implements FitsImportInteraction {
    private final String sirilPath;
    /** Only this import adapter's worker accesses the attempt flag; prevents repeated alignment prompts after redirect. */
    private boolean alignmentAttempted;
    /** Set by the worker once the observer imports raw colour exposures anyway, so a redirect does not ask again. */
    private boolean rawColourAccepted;
    /** The folder the observer chose to prepare instead; written on the worker, read on the EDT after the import ends. */
    private volatile java.nio.file.Path preparationFolder;

    /** Creates an adapter without an external alignment offer. */
    public GuiFitsImportInteraction() { this(""); }

    /** Captures the configured CLI path before import starts; workers never read live settings. */
    public GuiFitsImportInteraction(String sirilPath) { this.sirilPath = sirilPath; }

    /** Names the raw colour exposures and offers the preparation window, monochrome import or cancel. Worker thread. */
    @Override public RawColourChoice rawColour(BayerMosaic mosaic, java.io.File folder) {
        if (rawColourAccepted) return RawColourChoice.IMPORT_AS_MONOCHROME;
        String message = mosaic.rawColourExplanation() + "\n\n"
                + "Prepare observing session calibrates, debayers and registers them with Siril, then imports the result. "
                + "Imported as monochrome, the mosaic's colour pattern stays in every frame.";
        String[] options = {"Prepare observing session…", "Import as monochrome anyway", "Cancel"};
        int choice = choose(message, "Raw colour exposures", options);
        if (choice == 0) {
            preparationFolder = folder.toPath().toAbsolutePath().normalize();
            return RawColourChoice.PREPARE;
        }
        if (choice == 1) {
            rawColourAccepted = true;
            return RawColourChoice.IMPORT_AS_MONOCHROME;
        }
        return RawColourChoice.CANCEL;
    }

    /** The folder the observer chose to prepare instead of importing, or null. Read after the import job has ended. */
    public java.nio.file.Path preparationFolder() { return preparationFolder; }

    /**
     * Resolves only an explicitly configured CLI on the worker; preparation performs its own version validation.
     * Raw colour frames are offered only when their row order is known, from the files or the camera's preset.
     */
    @Override public boolean canAlignFrames(FitsFileInformation[] frames) {
        if (alignmentAttempted || sirilPath == null || sirilPath.isBlank() || interpretation(frames) == null) return false;
        try {
            eu.startales.spacepixels.preparation.SirilInstallation.resolve(java.nio.file.Path.of(sirilPath));
            return true;
        } catch (java.io.IOException | java.nio.file.InvalidPathException unavailable) { return false; }
    }

    /** How alignment reads the frames: as monochrome, or as colour with a row order and the preset that gave it. */
    record Interpretation(PreparationRequest.Sensor sensor, PreparationRequest.RowOrder rowOrder, String preset) { }

    /**
     * Monochrome frames, or raw colour frames with the files' pattern and the row order from the files or the camera's
     * preset. Null when raw colour frames give no row order.
     */
    static Interpretation interpretation(FitsFileInformation[] frames) {
        BayerMosaic mosaic = BayerMosaic.firstOf(frames);
        if (mosaic == null) return new Interpretation(PreparationRequest.Sensor.MONO, PreparationRequest.RowOrder.METADATA, null);
        if (mosaic.hasRowOrder()) return new Interpretation(PreparationRequest.Sensor.CFA, PreparationRequest.RowOrder.METADATA, null);
        CameraBayerPreset camera = CameraBayerPreset.forCamera(mosaic);
        if (camera == null || camera.rowOrder() == PreparationRequest.RowOrder.METADATA) return null;
        return new Interpretation(PreparationRequest.Sensor.CFA, camera.rowOrder(), camera.label());
    }

    /** Runs the existing private-copy preparation recipe under the import's cancellation scope, then redirects import. */
    @Override public java.io.File alignFrames(FitsFileInformation[] frames) throws Exception {
        alignmentAttempted = true;
        Interpretation interpretation = interpretation(frames);
        if (interpretation == null) throw new java.io.IOException("The row order of these raw colour exposures is unknown. Use Prepare observing session.");
        var source = java.nio.file.Path.of(frames[0].getFilePath()).toAbsolutePath().getParent();
        var request = new PreparationRequest(java.nio.file.Path.of(sirilPath),
                java.util.Map.of(eu.startales.spacepixels.preparation.PreparationInputs.Role.LIGHTS, source),
                interpretation.sensor(), PreparationRequest.Bayer.METADATA, interpretation.rowOrder(),
                0, 0, source, false, false, java.util.Set.of(), interpretation.preset());
        try (Progress progress = progress("Aligning frames with Siril...")) {
            var result = new eu.startales.spacepixels.preparation.FramePreparationService().prepare(request,
                    () -> { io.github.ppissias.jtransient.execution.CancellationScope.check(); return false; },
                    progress::onProgressUpdate, ignored -> { }, eu.startales.spacepixels.fits.ImageScaling.interactive(this));
            return result.aligned().toFile();
        }
    }

    @Override public int confirm(String message, String title) {
        return onEdt(() -> JOptionPane.showConfirmDialog(null, message, title,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE));
    }

    @Override public int choose(String message, String title, String[] options) {
        return onEdt(() -> JOptionPane.showOptionDialog(null, choiceContent(message), title,
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]));
    }

    /** Keeps long plain-text diagnostics selectable and scrollable, with the decision buttons visible. EDT only. */
    private static Object choiceContent(String message) {
        if (message.length() < 700 || message.stripLeading().startsWith("<html>")) return message;
        javax.swing.JTextArea details = new javax.swing.JTextArea(message, 14, 64);
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setFont(javax.swing.UIManager.getFont("OptionPane.messageFont"));
        details.setCaretPosition(0);
        return new javax.swing.JScrollPane(details);
    }

    @Override public void error(String message, String title) {
        onEdt(() -> {
            JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE);
            return null;
        });
    }

    @Override public Progress progress(String title) {
        ProcessingProgressDialog dialog = onEdt(() -> {
            ProcessingProgressDialog created = new ProcessingProgressDialog(null);
            created.setTitle(title);
            return created;
        });
        SwingUtilities.invokeLater(() -> dialog.setVisible(true));
        return new Progress() {
            @Override public void onProgressUpdate(int percent, String message) {
                SwingUtilities.invokeLater(() -> dialog.updateProgress(percent, message));
            }
            @Override public void close() { SwingUtilities.invokeLater(dialog::dispose); }
        };
    }

    private static <Value> Value onEdt(Callable<Value> action) {
        FutureTask<Value> task = new FutureTask<>(action);
        if (SwingUtilities.isEventDispatchThread()) task.run(); else SwingUtilities.invokeLater(task);
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking an import question.", interrupted);
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not ask an import question.", failure.getCause());
        }
    }
}
