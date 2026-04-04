package eu.startales.spacepixels.gui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

public class ProcessingProgressDialog extends JDialog {

    private final JProgressBar progressBar;
    private final JLabel statusLabel;

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

        add(centerPanel, BorderLayout.CENTER);

        // --- Exit Logic ---
        // When the user clicks the native window 'X' button
        setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmAndExit();
            }
        });

        setSize(400, 90);
        setResizable(false);
        setLocationRelativeTo(parentWindow); // Centers it over the main app
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
