package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.FitsImportInteraction;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Synchronous decisions on the EDT, with conversion and progress production left on the import worker. */
public final class GuiFitsImportInteraction implements FitsImportInteraction {
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
