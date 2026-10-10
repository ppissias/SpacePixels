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

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import io.github.ppissias.jtransient.core.SourceExtractor;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * A window that steps through frames, laid out like the blink window: controls and the shared stretch on top, the
 * zoomable frame in the middle, the frame and cursor in the status bar. Each frame is prepared in the background by
 * the subclass; while it is, the window says so and keeps the previous frame. ← and → step. A window that shows
 * detections has a "Show detections" switch (D).
 */
abstract class SequenceViewerFrame extends JFrame {

    /** What the window shows for one frame. */
    static final class Result {
        final BlinkSequence.Frame frame;
        final List<SourceExtractor.DetectedObject> detections;
        /** Appended to the frame description, for example "812 stars · 2 streaks". */
        final String summary;
        /** Why the frame cannot be shown, or null. */
        final String problem;

        private Result(BlinkSequence.Frame frame, List<SourceExtractor.DetectedObject> detections, String summary, String problem) {
            this.frame = frame;
            this.detections = detections;
            this.summary = summary;
            this.problem = problem;
        }

        static Result of(BlinkSequence.Frame frame, List<SourceExtractor.DetectedObject> detections, String summary) {
            return new Result(frame, detections, summary, null);
        }

        static Result problem(String problem) {
            return new Result(null, null, null, problem);
        }
    }

    private final FrameView view = new FrameView();
    private final JButton previousButton = new JButton("◀");
    private final JButton nextButton = new JButton("▶");
    private final JCheckBox detectionsBox = new JCheckBox("Show detections", true);
    private final JPanel stretchRow = new JPanel(new BorderLayout());
    private final JLabel frameLabel = new JLabel(" ");
    private final JLabel cursorLabel = new JLabel(" ");
    private final ViewerSupport.CursorReadout cursorReadout = new ViewerSupport.CursorReadout();
    private final String windowName;
    private final String busyText;
    private final boolean disposeOnClose;
    private final boolean showsDetections;

    private StretchControls stretch;
    private boolean open;
    private int current;
    /** The frame whose result is shown, or -1. */
    private int shownIndex = -1;
    private Result shown;
    private SwingWorker<Result, Void> worker;
    private Point cursorPixel;

    /**
     * @param windowName     the start of the title, for example "Preview Frame"
     * @param busyText       shown while a frame is prepared, for example "detecting…"
     * @param disposeOnClose whether closing disposes the window (else it hides and is opened again later)
     * @param showsDetections whether the frames come with detections, which get a "Show detections" switch
     */
    SequenceViewerFrame(String windowName, String busyText, boolean disposeOnClose, boolean showsDetections) {
        this.windowName = windowName;
        this.busyText = busyText;
        this.disposeOnClose = disposeOnClose;
        this.showsDetections = showsDetections;
        setTitle(windowName);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(null);

        JPanel contentPane = new JPanel(new BorderLayout(0, 6));
        contentPane.setBorder(new EmptyBorder(6, 6, 6, 6));
        setContentPane(contentPane);
        JComponent controls = buildControls();
        JComponent statusBar = ViewerSupport.statusBar(frameLabel, cursorLabel, "← → previous / next frame · "
                + (showsDetections ? "D detections · " : "") + "F fit · 1 100 % · scroll to zoom, drag to pan · Esc closes");
        contentPane.add(controls, BorderLayout.NORTH);
        contentPane.add(view, BorderLayout.CENTER);
        contentPane.add(statusBar, BorderLayout.SOUTH);

        // Only the frame view takes the keyboard focus, so the keys below always reach the window.
        ViewerSupport.removeFocus(controls);
        ViewerSupport.removeFocus(statusBar);
        view.setFocusable(true);
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                view.requestFocusInWindow();
            }
        });
        view.setCursorListener(pixel -> {
            cursorPixel = pixel;
            updateCursorLabel();
        });

        ViewerSupport.bind(getRootPane(), "LEFT", "previous", () -> step(-1));
        ViewerSupport.bind(getRootPane(), "RIGHT", "next", () -> step(1));
        if (showsDetections) {
            ViewerSupport.bind(getRootPane(), "D", "detections", () -> {
                detectionsBox.setSelected(!detectionsBox.isSelected());
                view.setDetectionsVisible(detectionsBox.isSelected());
            });
        }
        ViewerSupport.bindZoomKeys(getRootPane(), view);
        ViewerSupport.bind(getRootPane(), "ESCAPE", "close", this::close);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
    }

    /** The number of frames to step through. */
    protected abstract int frameCount();

    /** The file of this frame, for the status bar while it is prepared. */
    protected abstract FitsFileInformation infoOf(int index);

    /** Prepares a frame for display; runs in the background. */
    protected abstract Result prepare(int index) throws Exception;

    /** Called when the window closes, to let go of what the subclass holds. */
    protected void released() {
    }

    // ==========================================
    // OPEN AND CLOSE
    // ==========================================

    /** Shows the window at this frame. {@code files} are the frames of the session, which can share a plate solution. */
    protected void openAt(int index, StretchPanel stretchPanel, FitsFileInformation[] files) {
        bindStretch(stretchPanel);
        cursorReadout.reset(files);
        open = true;
        shown = null;
        shownIndex = -1;
        view.clear();
        view.setDetectionsVisible(detectionsBox.isSelected());
        FitsFileInformation first = infoOf(0);
        setTitle(windowName + " · " + sessionName(first) + " · " + frameCount() + " frames");
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        view.requestFocusInWindow();
        goTo(Math.max(0, Math.min(index, frameCount() - 1)));
    }

    private void close() {
        open = false;
        if (worker != null) {
            worker.cancel(false);
            worker = null;
        }
        shown = null;
        shownIndex = -1;
        cursorPixel = null;
        cursorReadout.reset(null);
        view.clear();
        if (stretch != null) {
            stretch.clearTables();
        }
        released();
        if (disposeOnClose) {
            if (stretch != null) {
                stretch.detach();
                stretch = null;
            }
            dispose();
        } else {
            setVisible(false);
        }
    }

    private void bindStretch(StretchPanel panel) {
        if (stretch != null && stretch.getPanel() == panel) {
            return;
        }
        if (stretch != null) {
            stretch.detach();
        }
        stretch = new StretchControls(panel, this::redraw);
        stretchRow.removeAll();
        stretchRow.add(stretch.getRow(), BorderLayout.CENTER);
        stretchRow.revalidate();
    }

    private static String sessionName(FitsFileInformation info) {
        if (info == null || info.getFilePath() == null) {
            return "";
        }
        File file = new File(info.getFilePath());
        return file.getParentFile() != null ? file.getParentFile().getName() : file.getName();
    }

    // ==========================================
    // CONTROLS
    // ==========================================

    private JComponent buildControls() {
        previousButton.setToolTipText("Previous frame (←).");
        previousButton.addActionListener(e -> step(-1));
        nextButton.setToolTipText("Next frame (→).");
        nextButton.addActionListener(e -> step(1));
        detectionsBox.setToolTipText("Green boxes: point sources. Red lines: streaks. From 100 % zoom the detected "
                + "pixels are tinted too (D).");
        detectionsBox.addActionListener(e -> view.setDetectionsVisible(detectionsBox.isSelected()));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.add(previousButton);
        row.add(nextButton);
        row.add(Box.createHorizontalStrut(10));
        if (showsDetections) {
            row.add(detectionsBox);
            row.add(Box.createHorizontalStrut(10));
        }
        for (JComponent zoomControl : ViewerSupport.zoomControls(view)) {
            row.add(zoomControl);
        }

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        stretchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(row);
        controls.add(stretchRow);
        return controls;
    }

    // ==========================================
    // STEPPING
    // ==========================================

    /** Moves one frame back or forward; stops at the first and last frame. */
    private void step(int direction) {
        if (open) {
            goTo(Math.max(0, Math.min(frameCount() - 1, current + direction)));
        }
    }

    private void goTo(int index) {
        if (index == current && shownIndex == index) {
            return;
        }
        current = index;
        previousButton.setEnabled(index > 0);
        nextButton.setEnabled(index < frameCount() - 1);
        frameLabel.setText(frameText(index, null) + " · " + busyText);
        prepareCurrent();
    }

    /**
     * Prepares the frame asked for. One frame is prepared at a time: keys pressed meanwhile only move the target,
     * and the frame then asked for is prepared next.
     */
    private void prepareCurrent() {
        if (worker != null) {
            return;
        }
        int index = current;
        worker = new SwingWorker<Result, Void>() {
            @Override
            protected Result doInBackground() throws Exception {
                return prepare(index);
            }

            @Override
            protected void done() {
                if (worker != this) {
                    return;
                }
                worker = null;
                if (!open) {
                    return;
                }
                if (index != current) {
                    prepareCurrent();
                    return;
                }
                Result result;
                try {
                    result = get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    result = Result.problem(cause instanceof OutOfMemoryError
                            ? "Not enough memory to show this frame."
                            : "Cannot show this frame: " + cause.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                show(index, result);
            }
        };
        worker.execute();
    }

    private void show(int index, Result result) {
        boolean first = shownIndex < 0;
        shown = result;
        shownIndex = index;
        if (result.frame == null) {
            view.showFrame(null, null, false);
            view.setDetections(null);
            view.setPlaceholder(result.problem);
            frameLabel.setText(frameText(index, null));
        } else {
            view.showFrame(result.frame, stretch.tablesFor(result.frame), stretch.extremeColour(result.frame));
            view.setDetections(result.detections);
            if (first) {
                view.fit();
            }
            frameLabel.setText(frameText(index, result.summary));
        }
        updateCursorLabel();
    }

    /** Draws the frame shown again, after the stretch changed. */
    private void redraw() {
        if (open && shown != null && shown.frame != null) {
            view.showFrame(shown.frame, stretch.tablesFor(shown.frame), stretch.extremeColour(shown.frame));
        }
    }

    private String frameText(int index, String summary) {
        FitsFileInformation info = infoOf(index);
        if (info == null) {
            return String.format("Frame %d of %d", index + 1, frameCount());
        }
        String text = ViewerSupport.frameText(index, frameCount(), info, infoOf(0));
        return summary == null ? text : text + " · " + summary;
    }

    private void updateCursorLabel() {
        cursorLabel.setText(open && shown != null ? cursorReadout.describe(shown.frame, cursorPixel) : " ");
    }
}
