package eu.startales.spacepixels.gui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Locale;

/** Searchable camera-preset control. Typing filters the list; choosing an entry applies that preset. */
final class CameraPresetField extends JPanel {
    private final JTextField query = new JTextField(28);
    private final JPopupMenu popup = new JPopupMenu();
    private final DefaultListModel<CameraBayerPreset> model = new DefaultListModel<>();
    private final JList<CameraBayerPreset> matches = new JList<>(model);
    private final Runnable chosen;
    private CameraBayerPreset selected = CameraBayerPreset.METADATA;
    private boolean updating;

    /** Builds the field without applying a preset; the form supplies the initial recipe separately. */
    CameraPresetField(Runnable chosen) {
        this.chosen = chosen;
        setLayout(new BorderLayout());
        query.setText(selected.toString());
        query.setToolTipText("Search nominal camera patterns. Capture software, cropping or flipping can change the file's mosaic. Prefer correct file metadata.");
        add(query, BorderLayout.CENTER);
        matches.setVisibleRowCount(12);
        matches.setFocusable(false);
        popup.setFocusable(false);
        popup.add(new JScrollPane(matches));
        query.getDocument().addDocumentListener(new DocumentListener() {
            /** Refreshes the match list after inserted text. */
            @Override public void insertUpdate(DocumentEvent event) { showMatches(); }
            /** Refreshes the match list after deleted text. */
            @Override public void removeUpdate(DocumentEvent event) { showMatches(); }
            /** Styled edits use the same filter as ordinary typing. */
            @Override public void changedUpdate(DocumentEvent event) { showMatches(); }
        });
        query.addActionListener(event -> { if (model.getSize() > 0) commit(matches.getSelectedIndex() < 0 ? model.get(0) : matches.getSelectedValue()); });
        query.addKeyListener(new KeyAdapter() {
            /** Moves the highlight without leaving the search field. Escape restores the current camera. */
            @Override public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_DOWN && popup.isVisible() && model.getSize() > 0) {
                    matches.setSelectedIndex(Math.min(model.getSize() - 1, matches.getSelectedIndex() + 1));
                    matches.ensureIndexIsVisible(matches.getSelectedIndex());
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_UP && popup.isVisible() && model.getSize() > 0) {
                    matches.setSelectedIndex(Math.max(0, matches.getSelectedIndex() - 1));
                    matches.ensureIndexIsVisible(matches.getSelectedIndex());
                    event.consume();
                } else if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    restore();
                    event.consume();
                }
            }
        });
        matches.addMouseListener(new MouseAdapter() {
            /** A click chooses that camera; the popup does not take focus, so the click is not lost. */
            @Override public void mousePressed(MouseEvent event) {
                int index = matches.locationToIndex(event.getPoint());
                if (index >= 0) commit(model.get(index));
            }
        });
        query.addFocusListener(new FocusAdapter() {
            /** Selects the current name so the next character starts a new search. */
            @Override public void focusGained(FocusEvent event) { query.selectAll(); }
            /** An abandoned search returns to the camera that is actually selected. */
            @Override public void focusLost(FocusEvent event) { if (!updating) restore(); }
        });
    }

    /** The camera currently applied to the recipe. */
    CameraBayerPreset selected() { return selected; }

    /** Applies one preset and notifies the form. Programmatic updates do not reopen the search list. */
    void select(CameraBayerPreset preset) {
        if (preset == null) return;
        updating = true;
        try {
            selected = preset;
            query.setText(preset.toString());
            query.select(0, 0);
            popup.setVisible(false);
        } finally { updating = false; }
        chosen.run();
    }

    /** Disables the search field together with the rest of the form. */
    @Override public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        query.setEnabled(enabled);
        if (!enabled) popup.setVisible(false);
    }

    /** Shows cameras whose label contains the typed text. An unchanged label does not open the list. */
    private void showMatches() {
        if (updating || !query.isShowing()) return;
        String typed = query.getText().trim().toLowerCase(Locale.ROOT);
        if (typed.equals(selected.toString().toLowerCase(Locale.ROOT))) { popup.setVisible(false); return; }
        model.clear();
        for (CameraBayerPreset preset : CameraBayerPreset.values()) {
            if (typed.isEmpty() || preset.toString().toLowerCase(Locale.ROOT).contains(typed)) model.addElement(preset);
        }
        if (model.isEmpty()) { popup.setVisible(false); return; }
        matches.setSelectedIndex(0);
        popup.pack();
        popup.show(query, 0, query.getHeight());
    }

    /** Commits a list entry and closes the search. */
    private void commit(CameraBayerPreset preset) {
        popup.setVisible(false);
        select(preset);
    }

    /** Puts the applied camera name back and closes the list. */
    private void restore() {
        updating = true;
        try { query.setText(selected.toString()); }
        finally { updating = false; }
        popup.setVisible(false);
    }
}
