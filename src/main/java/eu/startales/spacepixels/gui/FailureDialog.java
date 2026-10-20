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

import eu.startales.spacepixels.util.BuildInfo;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * The message of a job that failed, with a Copy details button. It copies the message, the versions and the full
 * error, ready to paste into a bug report.
 */
final class FailureDialog {

    private FailureDialog() {
    }

    /** Shows {@code message} as an error; {@code cause} (or null) is what the copied details explain. */
    static void show(Component parent, String title, String message, Throwable cause) {
        JButton copy = new JButton("Copy details");
        copy.setToolTipText("Copies this message, the versions and the full error, to paste into a bug report.");
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(details(message, cause)), null);
            copy.setText("Copied");
        });
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 8));
        row.add(copy);
        JOptionPane.showMessageDialog(parent, new Object[]{message, row}, title, JOptionPane.ERROR_MESSAGE);
    }

    /** The message, the versions, then the error with its causes and stack traces. */
    static String details(String message, Throwable cause) {
        StringBuilder text = new StringBuilder(message).append("\n\n")
                .append("SpacePixels ").append(BuildInfo.version("spacepixels"))
                .append(" · JTransient ").append(BuildInfo.version("jtransient"))
                .append(" · Java ").append(System.getProperty("java.version"))
                .append(" (").append(System.getProperty("java.vendor")).append(")")
                .append(" · ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
                .append("\n\n");
        if (cause == null) {
            text.append("No error was recorded.\n");
        } else {
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            text.append(trace);
        }
        return text.toString();
    }
}
