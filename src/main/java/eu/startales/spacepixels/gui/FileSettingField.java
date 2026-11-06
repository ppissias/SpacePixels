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

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A setting that names a file: a button that shows the chosen file's name and opens a file chooser, and a Clear
 * button. The button has the width of a number field, so the page's control column keeps its width; its tooltip
 * shows the whole path. An empty path means no file.
 */
public final class FileSettingField extends JPanel {

    private final JButton choose = new JButton();
    private final JButton clear = new JButton("Clear");
    private final List<Runnable> listeners = new ArrayList<>();
    private final String dialogTitle;
    private final FileNameExtensionFilter filter;
    private String path = "";

    FileSettingField(String dialogTitle, FileNameExtensionFilter filter) {
        super(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(4), 0));
        this.dialogTitle = dialogTitle;
        this.filter = filter;
        setOpaque(false);
        Dimension natural = choose.getPreferredSize();
        choose.setPreferredSize(new Dimension(UiStyle.space(UiStyle.NUMBER_INPUT_WIDTH), natural.height));
        choose.addActionListener(event -> chooseFile());
        clear.addActionListener(event -> setPath(""));
        add(choose);
        add(clear);
        showPath();
    }

    /** The chosen file's path, or an empty string for none. */
    public String path() {
        return path;
    }

    /** Shows {@code value} (null or blank for none) and tells the listeners when it differs from the shown path. */
    public void setPath(String value) {
        String next = value == null ? "" : value.strip();
        if (next.equals(path)) return;
        path = next;
        showPath();
        listeners.forEach(Runnable::run);
    }

    void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private void chooseFile() {
        File current = path.isEmpty() ? null : new File(path);
        JFileChooser chooser = WindowSizing.fileChooser(current == null ? null : current.getParentFile());
        chooser.setDialogTitle(dialogTitle);
        chooser.setFileFilter(filter);
        if (current != null) chooser.setSelectedFile(current);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            setPath(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void showPath() {
        choose.setText(path.isEmpty() ? "Choose…" : new File(path).getName());
        choose.setToolTipText(path.isEmpty() ? "Choose a file" : path);
        clear.setEnabled(!path.isEmpty());
    }
}
