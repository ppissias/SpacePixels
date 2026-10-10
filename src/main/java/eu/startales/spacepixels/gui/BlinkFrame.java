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

import com.google.common.eventbus.EventBus;
import eu.startales.spacepixels.events.BlinkFrameClosedEvent;
import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.TrackOverlay;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Plays the selected frames in turn, the classic way of hunting moving objects by eye. The speed, the display
 * stretch (shared with the Image Stretch tab) and the zoom can change while it plays, and the tracks of the last
 * detection run can be drawn on top. The list on the left shows every frame; while paused, frames can be left out of
 * the loop. The window opens as soon as the frames start loading and shows the progress.
 */
public class BlinkFrame extends JFrame {

    private static final String SPEED_PREFERENCE = "blink.frameMillis";
    private static final int MIN_MILLIS = 50;
    private static final int MAX_MILLIS = 2000;
    private static final int DEFAULT_MILLIS = 500;
    /** Steps of the speed slider; fine enough that a saved speed comes back as itself. */
    private static final int SPEED_STEPS = 1000;
    private static final String BLINK_CARD = "blink";
    private static final String LOADING_CARD = "loading";
    private static final String SKIP_TOOLTIP = "Leave this frame out of the loop, for example a cloudy or trailed frame (S). "
            + "Stepping by hand still shows it.";

    private final EventBus eventBus;
    private final FrameView view = new FrameView();
    private final JButton playButton = new JButton("❚❚ Pause");
    private final JButton previousButton = new JButton("◀");
    private final JButton nextButton = new JButton("▶");
    private final JSlider speedSlider = new JSlider(0, SPEED_STEPS);
    private final JLabel speedLabel = new JLabel();
    private final JCheckBox skipBox = new JCheckBox("Skip this frame");
    private final JCheckBox tracksBox = new JCheckBox("Show tracks");
    /** Holds the stretch row, which is made when the first blink brings the Image Stretch tab. */
    private final JPanel stretchRow = new JPanel(new BorderLayout());
    private final JLabel frameLabel = new JLabel(" ");
    private final JLabel cursorLabel = new JLabel(" ");
    private final FrameTableModel frameModel = new FrameTableModel();
    private final JTable frameTable = new JTable(frameModel);
    private final JLabel framesHeader = new JLabel(" ");
    private final JTextArea framesHint = new JTextArea();
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final JPanel loadingPanel = new JPanel(new GridBagLayout());
    private final JProgressBar loadBar = new JProgressBar();
    private final JLabel loadLabel = new JLabel(" ");
    private final JLabel loadFileLabel = new JLabel(" ");
    private final JComponent controls;
    private final JComponent statusBar;
    private final Timer timer;

    private StretchControls stretch;
    private final ViewerSupport.CursorReadout cursorReadout = new ViewerSupport.CursorReadout();

    private BlinkSequence sequence;
    private FitsFileInformation[] files;
    private boolean[] skipped;
    private int current;
    private boolean open;
    /** Frames are being read for the next blink; the window shows the progress. */
    private boolean loading;
    private FitsFileInformation[] loadingFiles;
    private Point cursorPixel;

    public BlinkFrame(EventBus eventBus) {
        this.eventBus = eventBus;
        setTitle("Blink");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        setSize((int) (screenSize.width * 0.8), (int) (screenSize.height * 0.8));
        setLocationRelativeTo(null);

        timer = new Timer(DEFAULT_MILLIS, e -> step(1, true));

        JPanel contentPane = new JPanel(new BorderLayout(0, 6));
        contentPane.setBorder(new EmptyBorder(6, 6, 6, 6));
        setContentPane(contentPane);
        controls = buildControls();
        statusBar = buildStatusBar();
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildFrameList(), view);
        split.setFocusable(false);
        split.setContinuousLayout(true);
        // The split pane would take the arrow keys to move its divider; they step frames here.
        SwingUtilities.replaceUIInputMap(split, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, null);
        center.add(split, BLINK_CARD);
        center.add(buildLoadingPanel(), LOADING_CARD);
        contentPane.add(controls, BorderLayout.NORTH);
        contentPane.add(center, BorderLayout.CENTER);
        contentPane.add(statusBar, BorderLayout.SOUTH);

        // Only the frame view takes the keyboard focus: a focused button would take Space for itself.
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
        installKeyBindings();
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });
        setSpeedMillis(savedSpeedMillis());
    }

    // ==========================================
    // OPEN AND CLOSE
    // ==========================================

    /**
     * Shows the loaded frames and starts playing. {@code overlay} holds the tracks of the last detection run on this
     * session, or is null.
     */
    void open(BlinkSequence sequence, FitsFileInformation[] files, StretchPanel stretchPanel, TrackOverlay overlay) {
        bindStretch(stretchPanel);
        release();
        this.sequence = sequence;
        this.files = files;
        cursorReadout.reset(files);
        this.skipped = new boolean[sequence.getFrames().size()];
        this.current = 0;
        this.open = true;

        Set<String> names = new HashSet<>();
        for (BlinkSequence.Frame frame : sequence.getFrames()) {
            names.add(new File(frame.getInfo().getFilePath()).getName());
        }
        boolean hasTracks = overlay != null && overlay.coversAnyOf(names);
        view.setOverlay(hasTracks ? overlay : null);
        tracksBox.setEnabled(hasTracks);
        if (!hasTracks) {
            tracksBox.setSelected(false);
        }
        tracksBox.setToolTipText(hasTracks
                ? "Draw the tracks of the last detection run, numbered as in the report: T for moving objects, ST for streak tracks (T)."
                : "Run Detect Moving Targets on this session to see its tracks here.");
        view.setOverlayVisible(tracksBox.isSelected());

        setTitle("Blink · " + sessionName(sequence.getFrames().get(0).getInfo().getFilePath()) + " · "
                + sequence.getFrames().size() + " frames");
        frameModel.fireTableDataChanged();
        updateLoopCount();
        controls.setVisible(true);
        statusBar.setVisible(true);
        centerCards.show(center, BLINK_CARD);
        view.fit();
        showCurrent();
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        view.requestFocusInWindow();
        play();
    }

    /** Shows the window at once with the progress of reading these frames; {@link #open} then starts the blink. */
    void showLoading(FitsFileInformation[] files) {
        release();
        loading = true;
        loadingFiles = files;
        loadBar.setMaximum(files.length);
        showLoadProgress(0, files.length);
        setTitle("Blink · " + sessionName(files[0].getFilePath()) + " · reading " + files.length + " frames");
        controls.setVisible(false);
        statusBar.setVisible(false);
        centerCards.show(center, LOADING_CARD);
        if (!isVisible()) {
            setVisible(true);
        }
        toFront();
        loadingPanel.requestFocusInWindow();
    }

    /** {@code loaded} of {@code total} frames are read. */
    void showLoadProgress(int loaded, int total) {
        if (!loading) {
            return;
        }
        loadBar.setValue(loaded);
        loadBar.setString(loaded + " / " + total);
        loadLabel.setText(String.format(Locale.US, "Reading frame %d of %d…", Math.min(loaded + 1, total), total));
        loadFileLabel.setText(loadingFiles != null && loaded < loadingFiles.length ? loadingFiles[loaded].getFileName() : " ");
    }

    /**
     * Ends the blink, or the loading before it: stops, frees the frames and tells the main window. Only hides the
     * window when neither is going on.
     */
    void close() {
        boolean active = open || loading;
        release();
        setVisible(false);
        if (active) {
            eventBus.post(new BlinkFrameClosedEvent());
        }
    }

    /** Stops and lets go of the frames of the current blink, if any. */
    private void release() {
        open = false;
        loading = false;
        loadingFiles = null;
        timer.stop();
        sequence = null;
        files = null;
        if (stretch != null) {
            stretch.clearTables();
        }
        cursorReadout.reset(null);
        cursorPixel = null;
        view.clear();
        frameModel.fireTableDataChanged();
    }

    private static String sessionName(String filePath) {
        File file = new File(filePath);
        return file.getParentFile() != null ? file.getParentFile().getName() : file.getName();
    }

    // ==========================================
    // CONTROLS
    // ==========================================

    private JComponent buildControls() {
        playButton.setToolTipText("Play or pause (Space).");
        playButton.addActionListener(e -> togglePlay());
        previousButton.setToolTipText("Previous frame (←). Stepping pauses the blink.");
        previousButton.addActionListener(e -> stepByHand(-1));
        nextButton.setToolTipText("Next frame (→). Stepping pauses the blink.");
        nextButton.addActionListener(e -> stepByHand(1));

        speedSlider.setToolTipText("Time each frame is shown (↑ faster, ↓ slower).");
        speedSlider.setPreferredSize(new Dimension(160, speedSlider.getPreferredSize().height));
        speedSlider.addChangeListener(e -> speedChanged());
        ViewerSupport.fixWidth(speedLabel, "2000 ms (20.0 frames/s)");

        skipBox.addActionListener(e -> {
            if (open) {
                setSkipped(current, skipBox.isSelected());
                skipBox.setSelected(skipped[current]);
            }
        });
        tracksBox.addActionListener(e -> view.setOverlayVisible(tracksBox.isSelected()));

        JPanel playbackRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        playbackRow.add(playButton);
        playbackRow.add(previousButton);
        playbackRow.add(nextButton);
        playbackRow.add(Box.createHorizontalStrut(10));
        playbackRow.add(new JLabel("Speed:"));
        playbackRow.add(speedSlider);
        playbackRow.add(speedLabel);
        playbackRow.add(Box.createHorizontalStrut(10));
        playbackRow.add(skipBox);
        playbackRow.add(tracksBox);
        playbackRow.add(Box.createHorizontalStrut(10));
        for (JComponent zoomControl : ViewerSupport.zoomControls(view)) {
            playbackRow.add(zoomControl);
        }

        JPanel controls = new JPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        playbackRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        stretchRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        controls.add(playbackRow);
        controls.add(stretchRow);
        return controls;
    }

    /** The frames of the blink: the one shown is highlighted, and the tick says whether a frame is in the loop. */
    private JComponent buildFrameList() {
        frameTable.setFocusable(false);
        frameTable.setRowSelectionAllowed(false);
        frameTable.setShowGrid(false);
        frameTable.setIntercellSpacing(new Dimension(0, 0));
        frameTable.setFillsViewportHeight(true);
        frameTable.getTableHeader().setReorderingAllowed(false);
        TableColumnModel columns = frameTable.getColumnModel();
        fixColumnWidth(columns.getColumn(0), 30);
        fixColumnWidth(columns.getColumn(1), 40);
        fixColumnWidth(columns.getColumn(3), 76);
        columns.getColumn(0).setCellRenderer(new LoopCellRenderer());
        TextCellRenderer textRenderer = new TextCellRenderer();
        for (int column = 1; column < columns.getColumnCount(); column++) {
            columns.getColumn(column).setCellRenderer(textRenderer);
        }
        frameTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = frameTable.rowAtPoint(e.getPoint());
                if (!open || row < 0 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                // The tick works only while paused: during play the frame shown changes too fast to aim at.
                boolean toggle = frameTable.columnAtPoint(e.getPoint()) == 0 && !timer.isRunning();
                goTo(row);
                if (toggle) {
                    setSkipped(row, !skipped[row]);
                }
            }
        });
        JScrollPane scroll = new JScrollPane(frameTable);
        scroll.setFocusable(false);

        framesHint.setEditable(false);
        framesHint.setFocusable(false);
        framesHint.setOpaque(false);
        framesHint.setLineWrap(true);
        framesHint.setWrapStyleWord(true);
        framesHint.setFont(UIManager.getFont("Label.font"));
        framesHint.setForeground(UIManager.getColor("Label.disabledForeground"));

        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(new EmptyBorder(0, 0, 0, 4));
        panel.add(framesHeader, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(framesHint, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(360, 100));
        panel.setMinimumSize(new Dimension(160, 0));
        return panel;
    }

    private static void fixColumnWidth(TableColumn column, int width) {
        column.setMinWidth(width);
        column.setMaxWidth(width);
        column.setPreferredWidth(width);
    }

    private JComponent buildLoadingPanel() {
        loadLabel.setFont(loadLabel.getFont().deriveFont(Font.BOLD, loadLabel.getFont().getSize2D() + 2f));
        loadBar.setStringPainted(true);
        Dimension barSize = new Dimension(380, loadBar.getPreferredSize().height);
        loadBar.setPreferredSize(barSize);
        loadBar.setMaximumSize(barSize);
        loadFileLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JLabel note = new JLabel("Each frame is read once; the blink then plays from memory.");
        note.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton cancelButton = new JButton("Cancel");
        cancelButton.setFocusable(false);
        cancelButton.setToolTipText("Stop reading the frames (Esc).");
        cancelButton.addActionListener(e -> close());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        for (JComponent component : new JComponent[]{loadLabel, loadBar, loadFileLabel, note, cancelButton}) {
            component.setAlignmentX(Component.CENTER_ALIGNMENT);
        }
        column.add(loadLabel);
        column.add(Box.createVerticalStrut(10));
        column.add(loadBar);
        column.add(Box.createVerticalStrut(6));
        column.add(loadFileLabel);
        column.add(Box.createVerticalStrut(18));
        column.add(note);
        column.add(Box.createVerticalStrut(12));
        column.add(cancelButton);
        // Focusable, so that Esc works while nothing else in the window can take the focus.
        loadingPanel.setFocusable(true);
        loadingPanel.add(column);
        return loadingPanel;
    }

    private JComponent buildStatusBar() {
        return ViewerSupport.statusBar(frameLabel, cursorLabel, "Space play/pause · ← → step · ↑ ↓ speed · "
                + "S skip frame (paused) · T tracks · F fit · 1 100 % · scroll to zoom, drag to pan · Esc closes");
    }

    /** The stretch row shares its models with the Image Stretch tab (see {@link StretchControls}). */
    private void bindStretch(StretchPanel panel) {
        if (stretch != null && stretch.getPanel() == panel) {
            return;
        }
        if (stretch != null) {
            stretch.detach();
        }
        stretch = new StretchControls(panel, this::showCurrent);
        stretchRow.removeAll();
        stretchRow.add(stretch.getRow(), BorderLayout.CENTER);
        stretchRow.revalidate();
    }

    private void installKeyBindings() {
        bind("SPACE", "playPause", this::togglePlay);
        bind("LEFT", "previous", () -> stepByHand(-1));
        bind("RIGHT", "next", () -> stepByHand(1));
        bind("UP", "faster", () -> speedSlider.setValue(Math.min(SPEED_STEPS, speedSlider.getValue() + SPEED_STEPS / 20)));
        bind("DOWN", "slower", () -> speedSlider.setValue(Math.max(0, speedSlider.getValue() - SPEED_STEPS / 20)));
        bind("S", "skip", () -> {
            if (open) {
                setSkipped(current, !skipped[current]);
            }
        });
        bind("T", "tracks", () -> {
            if (tracksBox.isEnabled()) {
                tracksBox.setSelected(!tracksBox.isSelected());
                view.setOverlayVisible(tracksBox.isSelected());
            }
        });
        ViewerSupport.bindZoomKeys(getRootPane(), view);
        bind("ESCAPE", "close", this::close);
    }

    private void bind(String key, String name, Runnable action) {
        ViewerSupport.bind(getRootPane(), key, name, action);
    }

    // ==========================================
    // PLAYBACK
    // ==========================================

    private void play() {
        if (!open) {
            return;
        }
        timer.start();
        updatePlayState();
    }

    private void pause() {
        timer.stop();
        updatePlayState();
    }

    /** Frames can be left out of the loop only while paused. */
    private void updatePlayState() {
        boolean playing = timer.isRunning();
        playButton.setText(playing ? "❚❚ Pause" : "▶ Play");
        skipBox.setEnabled(open && !playing);
        skipBox.setToolTipText(playing ? "Pause (Space) to leave frames out of the loop." : SKIP_TOOLTIP);
        framesHint.setText(playing
                ? "Pause (Space) to choose the frames in the loop. Click a frame to show it."
                : "Untick a frame, or press S, to leave it out of the loop. Click a frame to show it.");
        frameTable.repaint();
    }

    /** Pauses and shows this frame. */
    private void goTo(int index) {
        pause();
        current = index;
        showCurrent();
    }

    private void togglePlay() {
        if (timer.isRunning()) {
            pause();
        } else {
            play();
        }
    }

    private void stepByHand(int direction) {
        pause();
        step(direction, false);
    }

    /** Moves to the next frame in this direction; while playing, skipped frames are passed over. */
    private void step(int direction, boolean playing) {
        if (!open) {
            return;
        }
        int count = skipped.length;
        int next = current;
        for (int i = 0; i < count; i++) {
            next = Math.floorMod(next + direction, count);
            if (!playing || !skipped[next]) {
                break;
            }
        }
        current = next;
        showCurrent();
    }

    /** Puts a frame in or out of the loop. Ignored while playing; at least two frames stay in. */
    private void setSkipped(int index, boolean skip) {
        if (!open || timer.isRunning() || skipped[index] == skip) {
            return;
        }
        if (skip && includedCount() <= 2) {
            Toolkit.getDefaultToolkit().beep();
            frameLabel.setText(frameText() + " · at least two frames must stay in the loop");
            return;
        }
        skipped[index] = skip;
        frameModel.fireTableRowsUpdated(index, index);
        updateLoopCount();
        if (index == current) {
            skipBox.setSelected(skip);
            frameLabel.setText(frameText());
        }
    }

    private void updateLoopCount() {
        framesHeader.setText(String.format(Locale.US, "Frames · %d of %d in the loop", includedCount(), skipped.length));
    }

    private int includedCount() {
        int included = 0;
        for (boolean skip : skipped) {
            if (!skip) {
                included++;
            }
        }
        return included;
    }

    private void speedChanged() {
        int millis = speedMillis();
        timer.setDelay(millis);
        timer.setInitialDelay(millis);
        speedLabel.setText(String.format(Locale.US, "%d ms (%.1f frames/s)", millis, 1000.0 / millis));
        try {
            Preferences.userNodeForPackage(BlinkFrame.class).putInt(SPEED_PREFERENCE, millis);
        } catch (Exception ignored) {
            // The speed is a convenience; not remembering it is harmless.
        }
    }

    /** The slider runs on a logarithmic scale from {@link #MIN_MILLIS} to {@link #MAX_MILLIS}, fastest on the right. */
    private int speedMillis() {
        double fraction = (SPEED_STEPS - speedSlider.getValue()) / (double) SPEED_STEPS;
        return (int) Math.round(MIN_MILLIS * Math.pow((double) MAX_MILLIS / MIN_MILLIS, fraction));
    }

    private void setSpeedMillis(int millis) {
        double fraction = Math.log((double) millis / MIN_MILLIS) / Math.log((double) MAX_MILLIS / MIN_MILLIS);
        speedSlider.setValue((int) Math.round(SPEED_STEPS * (1 - Math.max(0, Math.min(1, fraction)))));
        speedChanged();
    }

    private static int savedSpeedMillis() {
        try {
            int millis = Preferences.userNodeForPackage(BlinkFrame.class).getInt(SPEED_PREFERENCE, DEFAULT_MILLIS);
            return Math.max(MIN_MILLIS, Math.min(MAX_MILLIS, millis));
        } catch (Exception e) {
            return DEFAULT_MILLIS;
        }
    }

    // ==========================================
    // DISPLAY
    // ==========================================

    private void showCurrent() {
        if (!open) {
            return;
        }
        BlinkSequence.Frame frame = sequence.getFrames().get(current);
        view.showFrame(frame, stretch.tablesFor(frame), stretch.extremeColour(frame));
        skipBox.setSelected(skipped[current]);
        frameLabel.setText(frameText());
        updateCursorLabel();
        frameTable.repaint();
        frameTable.scrollRectToVisible(frameTable.getCellRect(current, 0, true));
    }

    /** "Frame 7 of 20 · name · time · +12 min 30 s", and whether the frame is skipped. */
    private String frameText() {
        List<BlinkSequence.Frame> frames = sequence.getFrames();
        String text = ViewerSupport.frameText(current, frames.size(), frames.get(current).getInfo(), frames.get(0).getInfo());
        return skipped[current] ? text + " · skipped" : text;
    }

    private void updateCursorLabel() {
        cursorLabel.setText(open ? cursorReadout.describe(sequence.getFrames().get(current), cursorPixel) : " ");
    }

    /** Time since the first frame, "+12:30" or "+1:02:03"; empty when a capture time is missing. */
    private String offsetText(int index) {
        long start = sequence.getFrames().get(0).getInfo().getObservationTimestamp();
        long timestamp = sequence.getFrames().get(index).getInfo().getObservationTimestamp();
        if (start <= 0 || timestamp <= 0) {
            return "";
        }
        long seconds = Math.round((timestamp - start) / 1000.0);
        long abs = Math.abs(seconds);
        String time = abs >= 3600
                ? String.format(Locale.US, "%d:%02d:%02d", abs / 3600, abs / 60 % 60, abs % 60)
                : String.format(Locale.US, "%d:%02d", abs / 60, abs % 60);
        return (seconds < 0 ? "−" : "+") + time;
    }

    // ==========================================
    // FRAME LIST
    // ==========================================

    private final class FrameTableModel extends AbstractTableModel {
        private final String[] names = {"", "#", "Frame", "Time"};

        @Override
        public int getRowCount() {
            return sequence == null ? 0 : sequence.getFrames().size();
        }

        @Override
        public int getColumnCount() {
            return names.length;
        }

        @Override
        public String getColumnName(int column) {
            return names[column];
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : column == 1 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            switch (column) {
                case 0:
                    return !skipped[row];
                case 1:
                    return row + 1;
                case 2:
                    return sequence.getFrames().get(row).getInfo().getFileName();
                default:
                    return offsetText(row);
            }
        }
    }

    /** The row of the frame shown is highlighted; the table's own selection is not used. */
    private void paintRow(JComponent cell, JTable table, int row) {
        boolean shown = row == current;
        cell.setBackground(shown ? UIManager.getColor("Table.selectionBackground") : table.getBackground());
        cell.setForeground(shown ? UIManager.getColor("Table.selectionForeground") : table.getForeground());
    }

    private final class LoopCellRenderer extends JCheckBox implements TableCellRenderer {
        LoopCellRenderer() {
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorderPainted(false);
            setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            boolean playing = timer.isRunning();
            setSelected(Boolean.TRUE.equals(value));
            setEnabled(!playing);
            paintRow(this, table, row);
            setToolTipText(playing ? "Pause (Space) to change the frames in the loop."
                    : "Untick to leave this frame out of the loop.");
            return this;
        }
    }

    private static final javax.swing.border.Border CELL_PADDING = new EmptyBorder(0, 6, 0, 6);

    private final class TextCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, false, false, row, column);
            setHorizontalAlignment(column == 2 ? SwingConstants.LEFT : SwingConstants.RIGHT);
            setBorder(CELL_PADDING);
            paintRow(this, table, row);
            if (row != current && skipped[row]) {
                setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            setToolTipText(column == 2 ? value + (skipped[row] ? " (not in the loop)" : "") : null);
            return this;
        }
    }
}
