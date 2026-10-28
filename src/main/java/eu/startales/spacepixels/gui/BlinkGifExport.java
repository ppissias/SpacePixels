package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.report.GifSequenceWriter;
import eu.startales.spacepixels.util.StagedFile;
import javax.imageio.stream.FileImageOutputStream;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Owns Blink's viewport selection and one cancellable, streamed GIF export. UI state belongs to the EDT. */
final class BlinkGifExport {
    /** Immutable frame identity and projected catalogue marks, borrowing read-only Blink pixel buffers. */
    record FrameInput(BlinkSequence.Frame frame, List<SkyMark> marks) {
        FrameInput { marks = List.copyOf(marks); }
    }
    /** Captured frame order, display stretch and playback timing; no worker reads live viewer controls. */
    record Source(List<FrameInput> frames, StretchControls.Snapshot stretch, int delayMillis) {
        Source { frames = List.copyOf(frames); }
    }
    /** A completed single-file export, including the actual GIF timing after 10-ms quantization. */
    record Result(File file, int frames, int width, int height, int delayMillis) { }
    /** Supplies one complete cropped frame; the encoder releases it before asking for the next one. */
    @FunctionalInterface interface FrameSource { BufferedImage frame(int index) throws Exception; }

    private final FrameView view;
    private final Supplier<Source> source;
    private final Runnable pause;
    private final JLayer<FrameView> layer;
    private final SelectionGlass glass = new SelectionGlass();
    private final JToggleButton select = new JToggleButton("Select GIF region");
    private final JButton cancel = new JButton("Cancel GIF export");
    private final JLabel status = new JLabel(" ");
    private final JPanel controls = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
    private Rectangle selection;
    private Point anchor;
    private boolean available;
    private long generation;
    private SwingWorker<Result, Integer> worker;
    private AtomicBoolean cancellation;

    /** Wraps the image with an event-catching selection layer; ordinary pan/zoom listeners are unchanged. EDT only. */
    BlinkGifExport(FrameView view, Supplier<Source> source, Runnable pause) {
        this.view = view; this.source = source; this.pause = pause;
        layer = new JLayer<>(view);
        layer.setGlassPane(glass); glass.setVisible(false);
        select.setName("selectGifRegion"); select.setEnabled(false);
        select.setToolTipText("Select GIF region (G), drag a rectangle, then right-click inside it or press Ctrl+E to export.");
        select.addActionListener(event -> {
            if (select.isSelected()) pause.run();
            else { selection = null; anchor = null; }
            glass.setVisible(select.isSelected()); updateSelectionStatus(); glass.repaint();
            view.requestFocusInWindow();
        });
        cancel.setName("cancelGifExport"); cancel.setVisible(false);
        cancel.setToolTipText("Stop this GIF export and keep any earlier complete file at the destination.");
        cancel.addActionListener(event -> cancelExport());
        JLabel guidance = new JLabel("Drag a rectangle; right-click inside it to export.");
        guidance.setToolTipText("The GIF includes the current loop, visible annotations, zoom and stretch, at the current playback speed.");
        status.setName("blinkGifStatus");
        controls.add(select); controls.add(guidance); controls.add(cancel); controls.add(status);
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) {
                view.requestFocusInWindow();
                if (popup(event) || worker != null || !SwingUtilities.isLeftMouseButton(event)) return;
                anchor = point(event); selection = null; glass.repaint();
            }
            @Override public void mouseDragged(MouseEvent event) {
                updateDrag(event);
            }
            @Override public void mouseReleased(MouseEvent event) {
                if (!popup(event)) { if (SwingUtilities.isLeftMouseButton(event)) updateDrag(event); anchor = null; }
            }
        };
        glass.addMouseListener(mouse); glass.addMouseMotionListener(mouse);
        view.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) {
                if (selection != null) selection = selection.intersection(new Rectangle(view.getSize()));
                updateSelectionStatus(); glass.repaint();
            }
        });
    }

    /** Component used in place of the plain image view in Blink's split pane. */
    JComponent image() { return layer; }
    /** Selection, guidance, progress and cancellation controls for Blink's wrapping controls row. */
    JComponent controls() { return controls; }
    /** Enables admission only for a loaded blink; an old worker must actually finish before another export starts. */
    void setAvailable(boolean available) { this.available = available; select.setEnabled(available && worker == null); }
    /** Keyboard G follows the same action as the selection control. */
    void toggleSelection() { if (select.isEnabled()) select.doClick(); }
    /** Escape first stops export or leaves selection, before the owning viewer handles window closure. */
    boolean escape() {
        if (worker != null) { cancelExport(); return true; }
        if (select.isSelected()) { select.doClick(); return true; }
        return false;
    }
    /** Invalidates UI callbacks and cooperatively cancels the owned writer on close, reload or session replacement. */
    void release() {
        generation++; available = false;
        if (cancellation != null) cancellation.set(true);
        selection = null; anchor = null; select.setSelected(false); select.setEnabled(false); glass.setVisible(false);
        status.setText(" ");
    }

    /** Shows a save dialog only for a valid region; overwrite confirmation precedes staged replacement. EDT only. */
    void chooseFile() {
        if (!available || worker != null || selection == null || selection.isEmpty()) return;
        pause.run();
        JFileChooser chooser = new JFileChooser(); chooser.setDialogTitle("Export Blink region as animated GIF");
        chooser.setAcceptAllFileFilterUsed(false); chooser.setFileFilter(new FileNameExtensionFilter("Animated GIF (*.gif)", "gif"));
        chooser.setSelectedFile(new File("blink-region.gif"));
        if (chooser.showSaveDialog(layer) != JFileChooser.APPROVE_OPTION) return;
        File target = chooser.getSelectedFile();
        if (!target.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".gif")) target = new File(target.getPath() + ".gif");
        if (target.exists() && JOptionPane.showConfirmDialog(layer, "Replace " + target.getName() + "?", "Replace GIF",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) return;
        exportTo(target);
    }

    /** Captures admission on the EDT and returns a completion useful to the owning UI and mechanics checks. */
    CompletableFuture<Result> exportTo(File target) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Admit export on the EDT.");
        if (!available || worker != null || selection == null || selection.isEmpty()) throw new IllegalStateException("Select a GIF region first.");
        pause.run();
        Source captured = source.get();
        if (captured == null || captured.frames().isEmpty()) throw new IllegalStateException("No frames are in the Blink loop.");
        Rectangle area = selection.intersection(new Rectangle(view.getSize()));
        if (area.isEmpty()) throw new IllegalStateException("The selected region is outside the image view.");
        AffineTransform device = device();
        Rectangle pixels = FrameView.deviceBounds(area, device);
        FrameView renderer = view.copyForExport();
        AtomicBoolean stopping = new AtomicBoolean(); cancellation = stopping;
        long admitted = generation;
        int delay = gifDelay(captured.delayMillis());
        CompletableFuture<Result> completed = new CompletableFuture<>();
        worker = new SwingWorker<>() {
            @Override protected Result doInBackground() throws Exception {
                writeGif(target, captured.frames().size(), delay, index -> {
                    check(stopping::get);
                    FrameInput input = captured.frames().get(index);
                    byte[][] tables = captured.stretch().tablesFor(input.frame());
                    FutureTask<BufferedImage> render = new FutureTask<>(() -> {
                        check(stopping::get);
                        renderer.showFrame(input.frame(), tables, captured.stretch().extremeColour(input.frame()));
                        renderer.setSkyMarks(input.marks(), renderer.exportStarLimit());
                        return renderer.renderRegion(area, device);
                    });
                    SwingUtilities.invokeLater(render);
                    try {
                        while (true) {
                            check(stopping::get);
                            try { return render.get(100, TimeUnit.MILLISECONDS); }
                            catch (TimeoutException pending) { /* Observe close/cancel while waiting for the EDT. */ }
                        }
                    } finally { if (!render.isDone()) render.cancel(false); }
                }, stopping::get, count -> publish(count));
                return new Result(target, captured.frames().size(), pixels.width, pixels.height, delay);
            }
            @Override protected void process(List<Integer> counts) {
                if (worker == this && generation == admitted && !stopping.get())
                    status.setText("Exporting GIF: " + counts.getLast() + " / " + captured.frames().size());
            }
            @Override protected void done() {
                renderer.clear();
                try {
                    Result result = get(); completed.complete(result);
                    if (generation == admitted) {
                        status.setText("Saved " + result.frames() + " frames · " + result.width() + " × " + result.height()
                                + " px · " + result.delayMillis() + " ms/frame");
                        status.setToolTipText(result.file().getAbsolutePath());
                    }
                } catch (Exception failure) {
                    Throwable cause = failure;
                    while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null)
                        cause = cause.getCause();
                    completed.completeExceptionally(cause);
                    if (cause instanceof OutOfMemoryError memory) throw memory;
                    if (generation == admitted) {
                        status.setText(cause instanceof CancellationException ? "GIF export cancelled." : "GIF export failed.");
                        if (!(cause instanceof CancellationException) && layer.isShowing())
                            JOptionPane.showMessageDialog(layer, cause.getMessage(), "GIF export failed", JOptionPane.ERROR_MESSAGE);
                    }
                } finally {
                    if (worker == this) { worker = null; cancellation = null; cancel.setVisible(false); setAvailable(available); }
                }
            }
        };
        select.setEnabled(false); cancel.setEnabled(true); cancel.setVisible(true); status.setText("Exporting GIF…");
        worker.execute();
        return completed;
    }

    /** Signals cancellation without marking a SwingWorker complete before its writer and staged file actually stop. */
    private void cancelExport() {
        if (cancellation != null) { cancellation.set(true); cancel.setEnabled(false); status.setText("Cancelling GIF export…"); }
    }
    /** Maps a mouse point into viewport coordinates and clamps it to the drawable selection boundaries. */
    private Point point(MouseEvent event) {
        Point point = SwingUtilities.convertPoint(glass, event.getPoint(), view);
        return new Point(Math.max(0, Math.min(view.getWidth(), point.x)), Math.max(0, Math.min(view.getHeight(), point.y)));
    }
    /** Finalizes the release point too, so coalesced mouse motion cannot shorten a fast drag. */
    private void updateDrag(MouseEvent event) {
        if (anchor == null || worker != null) return;
        Point end = point(event);
        selection = new Rectangle(Math.min(anchor.x, end.x), Math.min(anchor.y, end.y),
                Math.abs(end.x - anchor.x), Math.abs(end.y - anchor.y));
        updateSelectionStatus(); glass.repaint();
    }
    /** Opens the context menu inside the selected rectangle; supports press/release popup conventions. */
    private boolean popup(MouseEvent event) {
        if (!event.isPopupTrigger()) return false;
        if (selection == null || !selection.contains(point(event))) return true;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem export = new JMenuItem("Export animated GIF…"); export.setName("exportBlinkRegionGif");
        export.setEnabled(worker == null); export.addActionListener(action -> chooseFile()); menu.add(export);
        JMenuItem clear = new JMenuItem("Clear region"); clear.setEnabled(worker == null);
        clear.addActionListener(action -> { selection = null; updateSelectionStatus(); glass.repaint(); }); menu.add(clear);
        menu.show(glass, event.getX(), event.getY()); return true;
    }
    /** Current display device scale, copied before the worker starts. */
    private AffineTransform device() {
        GraphicsConfiguration configuration = view.getGraphicsConfiguration();
        return configuration == null ? new AffineTransform() : new AffineTransform(configuration.getDefaultTransform());
    }
    /** Shows actual exported pixel dimensions, including the monitor's HiDPI scale. */
    private void updateSelectionStatus() {
        if (worker != null) return;
        if (selection == null || selection.isEmpty()) { if (select.isSelected()) status.setText("Draw a GIF region."); return; }
        Rectangle pixels = FrameView.deviceBounds(selection, device());
        status.setText(pixels.width + " × " + pixels.height + " px · right-click to export");
    }
    /** GIF stores delays in 10-ms units; round once rather than truncating the current playback interval. */
    static int gifDelay(int milliseconds) { return Math.max(10, (int) Math.round(milliseconds / 10.0) * 10); }
    /** Observes cooperative cancellation before rendering, between frames and before publication. */
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("GIF export cancelled.");
    }
    /** Streams only the GIF through staged replacement; it creates no report posters or frame-sheet sidecars. */
    static void writeGif(File target, int count, int delay, FrameSource frames, BooleanSupplier cancelled, IntConsumer progress) throws Exception {
        if (count < 1) throw new IOException("No frames selected for GIF export.");
        try (StagedFile staged = new StagedFile(target)) {
            try (FileImageOutputStream output = new FileImageOutputStream(staged.path().toFile());
                 GifSequenceWriter writer = new GifSequenceWriter(output, BufferedImage.TYPE_INT_RGB, gifDelay(delay), true)) {
                int width = -1, height = -1;
                for (int index = 0; index < count; index++) {
                    check(cancelled);
                    BufferedImage frame = frames.frame(index);
                    check(cancelled);
                    if (index == 0) { width = frame.getWidth(); height = frame.getHeight(); }
                    if (frame.getWidth() != width || frame.getHeight() != height) throw new IOException("GIF frame dimensions changed during export.");
                    writer.writeToSequence(frame); progress.accept(index + 1);
                }
            }
            check(cancelled);
            staged.publish();
        }
    }

    /** Selection guides intercept mouse gestures only while enabled and are never painted into exported image data. */
    private final class SelectionGlass extends JPanel {
        SelectionGlass() { setOpaque(false); setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)); }
        @Override protected void paintComponent(Graphics graphics) {
            if (selection == null || selection.isEmpty()) return;
            Point origin = SwingUtilities.convertPoint(view, selection.getLocation(), this);
            Graphics2D guide = (Graphics2D) graphics.create();
            try {
                guide.setColor(new Color(255, 220, 80)); guide.setStroke(new BasicStroke(2));
                guide.drawRect(origin.x, origin.y, selection.width, selection.height);
            } finally { guide.dispose(); }
        }
    }
}
