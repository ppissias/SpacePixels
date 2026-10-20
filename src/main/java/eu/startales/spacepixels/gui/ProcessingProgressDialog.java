package eu.startales.spacepixels.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * The progress of the running job, modal over the main window. A job that can be cancelled shows Cancel, and closing
 * the dialog cancels it too; the button then says the job is stopping, and offers to exit SpacePixels if the job does
 * not stop. For a job that cannot be cancelled, closing the dialog asks whether to exit SpacePixels.
 */
public class ProcessingProgressDialog extends JDialog {

    /** How long a cancelled job may take to stop before the dialog offers to exit SpacePixels instead. */
    private static final int EXIT_OFFER_DELAY_MILLIS = 15_000;

    private final JProgressBar progressBar;
    private final JLabel statusLabel;
    private final JButton cancelButton = new JButton("Cancel");
    private final Timer exitOffer = new Timer(EXIT_OFFER_DELAY_MILLIS, e -> offerExit());
    private Runnable cancel;
    private boolean exitOffered;

    public ProcessingProgressDialog(JFrame parentWindow) {
        // true makes it Modal (blocks input to the main window)
        super(parentWindow, "Processing...", true);

        setLayout(new BorderLayout(15, 15));

        // --- Setup Components ---
        statusLabel = new JLabel("Starting...", SwingConstants.CENTER);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true); // Shows the percentage text

        // --- Layout ---
        JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(15, 20, 5, 20));
        centerPanel.add(statusLabel, BorderLayout.NORTH);
        centerPanel.add(progressBar, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 20, 12, 20));
        buttons.add(cancelButton);
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

        pack();
        setSize(Math.max(420, getWidth()), getHeight());
        setResizable(false);
        setLocationRelativeTo(parentWindow); // Centers it over the main app
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
        cancelButton.setVisible(cancel != null);
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
        statusLabel.setText(message);
    }

    public void showIndeterminateProgress(String message) {
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);
        statusLabel.setText(message);
    }
}
