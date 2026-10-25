package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.RunPreview;
import eu.startales.spacepixels.detection.RunPreviewSink;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The progress of the running job, modal over the main window. A job that can be cancelled shows Cancel, and closing
 * the dialog cancels it too; the button then says the job is stopping, and offers to exit SpacePixels if the job does
 * not stop. For a job that cannot be cancelled, closing the dialog asks whether to exit SpacePixels.
 * <p>
 * Detection runs also show a live view: a small picture of the work under way, at most one new picture every few
 * seconds, which can be hidden. The text above it stays the authoritative progress.
 */
public class ProcessingProgressDialog extends JDialog {

    /** How long a cancelled job may take to stop before the dialog offers to exit SpacePixels instead. */
    private static final int EXIT_OFFER_DELAY_MILLIS = 15_000;
    /** At most one new live-view picture per this interval; a stage change is shown at once. */
    static final long PREVIEW_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3);
    /** The picture box and the text width, in logical pixels. */
    private static final int PICTURE_WIDTH = 300;
    private static final int PICTURE_HEIGHT = 200;
    private static final int TEXT_WIDTH = 380;
    static final String WAITING = "The first picture appears once a frame has loaded.";

    private final JProgressBar progressBar;
    private final JLabel statusLabel = new JLabel();
    private final JButton cancelButton = new JButton("Cancel");
    private final Timer exitOffer = new Timer(EXIT_OFFER_DELAY_MILLIS, e -> offerExit());
    private Runnable cancel;
    private boolean exitOffered;
    private int dialogWidth;

    private final JPanel previewPanel = new JPanel(new BorderLayout(0, 6));
    private final JPanel previewBody = new JPanel(new BorderLayout(0, 6));
    private final JLabel previewPicture = new JLabel(WAITING, SwingConstants.CENTER);
    private final JLabel previewCaption = new JLabel();
    private final JButton previewToggle;
    /** Whether the observer wants the picture shown: the saved preference, changed with the toggle. */
    private boolean previewShown = true;
    private Consumer<Boolean> previewPreferenceSaver = shown -> { };
    /** Counts the jobs' preview sinks; a picture from an older sink is dropped. Used on the event thread only. */
    private long previewGeneration;
    /** Read by the job's thread before it makes a picture: true while a visible live view would show one. */
    private volatile boolean previewWanted;
    /** The pictures of the latest preview that has several, shown in turn; used on the event thread only. */
    private List<RunPreview> slides = List.of();
    private int slideIndex;
    private final Timer slideTimer = new Timer((int) TimeUnit.NANOSECONDS.toMillis(PREVIEW_INTERVAL_NANOS), e -> showNextSlide());

    public ProcessingProgressDialog(JFrame parentWindow) {
        // true makes it Modal (blocks input to the main window)
        super(parentWindow, "Processing...", true);

        setLayout(new BorderLayout(15, 15));

        // --- Setup Components ---
        setWrapped(statusLabel, "Starting...", 2);
        statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true); // Shows the percentage text
        progressBar.setAlignmentX(Component.CENTER_ALIGNMENT);

        // --- Live view ---
        JLabel previewTitle = new JLabel("Live view");
        previewToggle = AccessibleControls.link("Hide picture", "Hide the live-view picture",
                () -> setPreviewShown(!previewShown, true));
        previewTitle.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        previewToggle.setToolTipText("Show or hide the picture of the work under way. SpacePixels remembers the choice.");
        JPanel previewHeader = new JPanel(new BorderLayout());
        previewHeader.add(previewTitle, BorderLayout.WEST);
        previewHeader.add(previewToggle, BorderLayout.EAST);
        Dimension pictureBox = new Dimension(UiStyle.space(PICTURE_WIDTH), UiStyle.space(PICTURE_HEIGHT));
        previewPicture.setPreferredSize(pictureBox);
        previewPicture.setMinimumSize(pictureBox);
        previewPicture.setForeground(UiStyle.helpColor());
        previewPicture.setBorder(BorderFactory.createLineBorder(UIManager.getColor("Separator.foreground")));
        previewPicture.getAccessibleContext().setAccessibleName("Live view");
        previewCaption.setForeground(UiStyle.helpColor());
        setWrapped(previewCaption, " ", 2);
        previewBody.add(previewPicture, BorderLayout.CENTER);
        previewBody.add(previewCaption, BorderLayout.SOUTH);
        previewPanel.add(previewHeader, BorderLayout.NORTH);
        previewPanel.add(previewBody, BorderLayout.CENTER);
        previewPanel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        previewPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        // Shown only for the jobs that make pictures (beginPreview).
        previewPanel.setVisible(false);

        // --- Layout ---
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 5, 20));
        centerPanel.add(statusLabel);
        centerPanel.add(Box.createVerticalStrut(5));
        centerPanel.add(progressBar);
        centerPanel.add(previewPanel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 20, 12, 20));
        buttons.add(cancelButton);
        // Shown only for a job that can be cancelled (offerCancel); the import's own progress windows have none.
        cancelButton.setVisible(false);
        cancelButton.addActionListener(e -> {
            if (exitOffered) {
                confirmAndExit();
            } else {
                requestCancel();
            }
        });

        add(centerPanel, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        // --- Close Logic ---
        // When the user clicks the native window 'X' button
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (cancel != null && !exitOffered) {
                    requestCancel();
                } else {
                    confirmAndExit();
                }
            }
        });
        exitOffer.setRepeats(false);

        fitSize();
        setResizable(false);
        setLocationRelativeTo(parentWindow); // Centers it over the main app
    }

    /**
     * Fits the height to the content, with or without the Cancel button and the live view. The width is set once,
     * from the wrapped text width, so a long message does not widen the dialog.
     */
    private void fitSize() {
        pack();
        if (dialogWidth == 0) {
            dialogWidth = Math.max(420, getWidth());
        }
        setSize(dialogWidth, getHeight());
    }

    /** Grows the dialog when its text needs more lines; it does not shrink during a job, so it does not jump. */
    private void growIfNeeded() {
        if (getPreferredSize().height > getHeight()) {
            fitSize();
        }
    }

    /** Centred, wrapped text with room for at least {@code lines} lines so short messages keep the size. */
    private static void setWrapped(JLabel label, String text, int lines) {
        String escaped = breakLongTokens(text == null || text.isEmpty() ? " " : text, label.getFontMetrics(label.getFont()))
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        label.setText("<html><div style='text-align:center;width:"
                + UiStyle.space(TEXT_WIDTH) + "px'>" + escaped + "</div></html>");
        label.setPreferredSize(null);
        Dimension natural = label.getPreferredSize();
        int minimum = label.getFontMetrics(label.getFont()).getHeight() * lines;
        label.setPreferredSize(new Dimension(natural.width, Math.max(natural.height, minimum)));
        label.setToolTipText(text);
    }

    /** Adds invisible wrap opportunities inside filenames that cannot fit as one word at the current font. */
    private static String breakLongTokens(String text, FontMetrics metrics) {
        StringBuilder wrapped = new StringBuilder();
        int tokenWidth = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int width = metrics.charWidth(codePoint);
            if (Character.isWhitespace(codePoint)) {
                tokenWidth = 0;
            } else {
                if (tokenWidth > 0 && tokenWidth + width > UiStyle.space(TEXT_WIDTH)) {
                    wrapped.append('\u200b');
                    tokenWidth = 0;
                }
                tokenWidth += width;
            }
            wrapped.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return wrapped.toString();
    }

    /**
     * Prepares the dialog for the next job: {@code cancel} stops it, or is null when the job cannot be cancelled, which
     * hides the Cancel button.
     */
    public void offerCancel(Runnable cancel) {
        this.cancel = cancel;
        exitOffered = false;
        exitOffer.stop();
        cancelButton.setText("Cancel");
        cancelButton.setEnabled(true);
        if (cancelButton.isVisible() != (cancel != null)) {
            cancelButton.setVisible(cancel != null);
            fitSize();
        }
    }

    @Override
    public void setVisible(boolean visible) {
        if (!visible) {
            exitOffer.stop();
        }
        super.setVisible(visible);
    }

    private void requestCancel() {
        if (cancel == null || !cancelButton.isEnabled()) {
            return;
        }
        cancel.run();
        cancelButton.setText("Stopping…");
        cancelButton.setEnabled(false);
        exitOffer.restart();
    }

    /** The job has not stopped: the button now exits SpacePixels, as closing the dialog used to. */
    private void offerExit() {
        exitOffered = true;
        cancelButton.setText("Exit SpacePixels");
        cancelButton.setEnabled(true);
    }

    private void confirmAndExit() {
        int choice = JOptionPane.showConfirmDialog(this,
                "This will terminate the current process and exit SpacePixels. Are you sure?",
                "Exit Application",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (choice == JOptionPane.YES_OPTION) {
            System.exit(0); // Kills the JVM entirely
        }
    }

    // A simple method to push updates to this dialog
    public void updateProgress(int percentage, String message) {
        progressBar.setIndeterminate(false);
        progressBar.setValue(percentage);
        setWrapped(statusLabel, message, 2);
        growIfNeeded();
    }

    public void showIndeterminateProgress(String message) {
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        setWrapped(statusLabel, message, 2);
        growIfNeeded();
    }

    // ==========================================
    // LIVE VIEW
    // ==========================================

    /** Restores the saved Hide/Show choice; {@code save} keeps a change for the next start. */
    void setPreviewPreference(boolean shown, Consumer<Boolean> save) {
        previewPreferenceSaver = save;
        setPreviewShown(shown, false);
    }

    /**
     * A sink for the next job's pictures, handed to its task. Pictures from an earlier job's sink are dropped, and
     * only the newest picture waits for the event thread.
     */
    RunPreviewSink previewSink() {
        long generation = ++previewGeneration;
        AtomicLong lastAccepted = new AtomicLong();
        AtomicReference<RunPreview> pending = new AtomicReference<>();
        return new RunPreviewSink() {
            @Override
            public boolean wantsPreview(boolean stageChange) {
                if (!previewWanted) {
                    return false;
                }
                long now = System.nanoTime();
                long last = lastAccepted.get();
                if (!stageChange && last != 0 && now - last < PREVIEW_INTERVAL_NANOS) {
                    return false;
                }
                lastAccepted.set(now == 0 ? 1 : now);
                return true;
            }

            @Override
            public void preview(RunPreview preview) {
                if (pending.getAndSet(preview) == null) {
                    EventQueue.invokeLater(() -> {
                        RunPreview newest = pending.getAndSet(null);
                        if (newest != null && generation == previewGeneration && previewPanel.isVisible()) {
                            showPreview(newest);
                        }
                    });
                }
            }
        };
    }

    /** At the start of a job: the live view for a job that makes pictures, waiting for its first one; none otherwise. */
    void beginPreview(boolean available) {
        clearPreview(WAITING, " ");
        previewPanel.setVisible(available);
        previewWanted = available && previewShown;
        fitSize();
    }

    /** At the end of a job: later pictures of its sink are dropped and the picture is released. */
    void endPreview() {
        previewGeneration++;
        previewWanted = false;
        clearPreview(WAITING, " ");
    }

    /** Shows a new preview; one with several slides starts with the first and turns every few seconds. */
    private void showPreview(RunPreview preview) {
        slideTimer.stop();
        slides = preview.slides();
        slideIndex = 0;
        showPicture(slides.isEmpty() ? preview : slides.get(0));
        if (slides.size() > 1 && previewShown) {
            slideTimer.start();
        }
    }

    /** Called by the slide timer: the next picture of the shown preview. */
    void showNextSlide() {
        if (slides.size() < 2 || !previewPanel.isVisible()) {
            slideTimer.stop();
            return;
        }
        slideIndex = (slideIndex + 1) % slides.size();
        showPicture(slides.get(slideIndex));
    }

    private void showPicture(RunPreview preview) {
        if (preview.image() != null) {
            previewPicture.setIcon(new ImageIcon(fitToBox(preview.image())));
            previewPicture.setText(null);
        } else if (!preview.keepImage()) {
            previewPicture.setIcon(null);
            previewPicture.setText(" ");
        }
        setWrapped(previewCaption, preview.caption(), 2);
        previewPicture.getAccessibleContext().setAccessibleDescription(preview.caption());
        growIfNeeded();
    }

    private void clearPreview(String pictureText, String caption) {
        slideTimer.stop();
        slides = List.of();
        previewPicture.setIcon(null);
        previewPicture.setText(pictureText);
        previewPicture.getAccessibleContext().setAccessibleDescription(null);
        setWrapped(previewCaption, caption, 2);
    }

    private void setPreviewShown(boolean shown, boolean save) {
        previewShown = shown;
        previewBody.setVisible(shown);
        previewToggle.setText(shown ? "Hide picture" : "Show picture");
        previewToggle.getAccessibleContext().setAccessibleName(shown ? "Hide the live-view picture" : "Show the live-view picture");
        previewWanted = shown && previewPanel.isVisible();
        if (!shown) {
            slideTimer.stop();
        } else if (slides.size() > 1 && previewPanel.isVisible()) {
            slideTimer.start();
        }
        if (save) {
            previewPreferenceSaver.accept(shown);
        }
        fitSize();
    }

    /** The picture scaled to fit the box, keeping its proportions. */
    private BufferedImage fitToBox(BufferedImage image) {
        Dimension box = previewPicture.getPreferredSize();
        double scale = Math.min((box.width - 2) / (double) image.getWidth(), (box.height - 2) / (double) image.getHeight());
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = fitted.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return fitted;
    }

    // For tests: what the live view shows now.
    String previewCaptionText() {
        return previewCaption.getText();
    }

    boolean previewHasPicture() {
        return previewPicture.getIcon() != null;
    }

    /** Whether the pictures of the shown preview are being shown in turn. */
    boolean slidesTurning() {
        return slideTimer.isRunning();
    }

    boolean isPreviewAreaShown() {
        return previewPanel.isVisible() && previewBody.isVisible();
    }
}
