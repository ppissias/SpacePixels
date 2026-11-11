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

import eu.startales.spacepixels.skycatalog.FetchStep;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.List;

/**
 * The modeless account of a sky-catalogue fetch and an asteroid and comet fetch. One window is reused for the
 * session. The main window owns it and closes it when the session changes. Built and updated on the EDT.
 */
final class FetchSummaryDialog extends JDialog {

    private final JScrollPane scroll = new JScrollPane();
    private final JLabel note = new JLabel(" ");
    private String shown = "";

    /** An empty summary owned by the main window. The caller fills it with {@link #present}. */
    FetchSummaryDialog(Window owner) {
        super(owner, "Fetch summary", Dialog.ModalityType.MODELESS);
        setName("fetchSummary");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        AppIcon.apply(this);
        note.setForeground(UiStyle.helpColor());
        note.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel content = new JPanel(new BorderLayout());
        content.add(scroll, BorderLayout.CENTER);
        content.add(note, BorderLayout.SOUTH);
        setContentPane(content);
        getRootPane().registerKeyboardAction(event -> dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        WindowSizing.install(this, 720, 420);
        setSize(Math.min(UiStyle.space(880), 1100), Math.min(UiStyle.space(520), 800));
        setLocationRelativeTo(owner);
    }

    /**
     * Shows these steps. An unchanged list leaves the scroll position alone. {@code dataset} is the folder name
     * used in the title. Called on the EDT.
     */
    void present(String dataset, List<FetchStep> catalogueSteps, boolean catalogueRunning,
            List<FetchStep> solarSteps, boolean solarRunning) {
        String title = dataset == null || dataset.isBlank() ? "Fetch summary" : "Fetch summary — " + dataset;
        String key = title + "\n" + catalogueRunning + "\n" + textOf(catalogueSteps) + "\n" + solarRunning + "\n" + textOf(solarSteps);
        if (key.equals(shown)) {
            return;
        }
        shown = key;
        int at = scroll.getVerticalScrollBar().getValue();
        setTitle(title);
        JPanel page = new JPanel(new GridBagLayout());
        page.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        GridBagConstraints layout = new GridBagConstraints();
        layout.gridx = 0;
        layout.weightx = 1;
        layout.fill = GridBagConstraints.HORIZONTAL;
        layout.anchor = GridBagConstraints.NORTHWEST;
        addSection(page, layout, "Sky catalogue", catalogueSteps, catalogueRunning);
        layout.insets = new Insets(16, 0, 0, 0);
        addSection(page, layout, "Asteroids and comets", solarSteps, solarRunning);
        layout.weighty = 1;
        page.add(new JPanel(), layout);
        scroll.setViewportView(page);
        scroll.getVerticalScrollBar().setValue(at);
    }

    /** The warning colour already used for a failed setting. */
    static Color warningColor() {
        Color color = UIManager.getColor("Actions.Yellow");
        return color == null ? new Color(0xE0A030) : color;
    }

    /** One section: its heading, then a row for every step, or a line when there is nothing to list yet. */
    private void addSection(JPanel page, GridBagConstraints layout, String title, List<FetchStep> steps, boolean running) {
        JLabel heading = new JLabel(title);
        heading.setFont(UiStyle.sectionFont());
        layout.gridy = page.getComponentCount();
        page.add(heading, layout);
        if (steps == null || steps.isEmpty()) {
            JLabel empty = new JLabel(running ? "No step has finished yet." : "Not fetched.");
            empty.setForeground(UiStyle.helpColor());
            layout.gridy = page.getComponentCount();
            layout.insets = new Insets(4, 0, 0, 0);
            page.add(empty, layout);
            return;
        }
        JPanel table = new JPanel(new GridBagLayout());
        addHeader(table);
        int row = 1;
        for (FetchStep step : steps) {
            if (step != null) {
                addRow(table, row++, step);
            }
        }
        layout.gridy = page.getComponentCount();
        layout.insets = new Insets(4, 0, 0, 0);
        page.add(table, layout);
        if (running) {
            JLabel still = new JLabel("Still asking the services.");
            still.setForeground(UiStyle.helpColor());
            layout.gridy = page.getComponentCount();
            page.add(still, layout);
        }
    }

    /** The column titles. */
    private static void addHeader(JPanel table) {
        String[] titles = {"Step", "Result", "What came back", "Detail"};
        for (int column = 0; column < titles.length; column++) {
            JLabel label = new JLabel(titles[column]);
            label.setFont(label.getFont().deriveFont(java.awt.Font.BOLD));
            GridBagConstraints cell = cell(0, column, column == 3 ? 16 : 8);
            table.add(label, cell);
        }
    }

    /** One service question, with Open and Copy query when the step has an address or an ADQL query. */
    private void addRow(JPanel table, int row, FetchStep step) {
        String service = step.service == null ? "" : step.service;
        String name = step.question == null || step.question.isBlank() ? service
                : service.isBlank() ? step.question : service + " — " + step.question;
        table.add(wrapped(name, Color.BLACK), cell(row, 0, 8));
        JLabel result = new JLabel(step.resultWord());
        result.setForeground(color(step));
        table.add(result, cell(row, 1, 8));
        table.add(wrapped(step.outcome == null ? "" : step.outcome, null), cell(row, 2, 8));
        table.add(wrapped(step.detail == null ? "" : step.detail, null), cell(row, 3, 16));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        actions.setOpaque(false);
        String address = step.openUrl();
        if (address != null && !address.isBlank()) {
            JButton open = AccessibleControls.link("Open", "Open " + name, () -> openAddress(address, name));
            open.setMargin(new Insets(0, 2, 0, 2));
            actions.add(open);
        }
        if (step.query != null && !step.query.isBlank()) {
            JButton copy = AccessibleControls.link("Copy query", "Copy query for " + name, () -> copyQuery(step.query, name));
            copy.setMargin(new Insets(0, 2, 0, 2));
            actions.add(copy);
        }
        table.add(actions, cell(row, 4, 0));
    }

    /** Wrapped text in the ordinary foreground, or in {@code color} when one is given. Black means the theme foreground. */
    private static JLabel wrapped(String text, Color color) {
        JLabel label = new JLabel(UiStyle.htmlWrappedTo(220, escape(text)));
        label.setVerticalAlignment(SwingConstants.TOP);
        if (color != null && color != Color.BLACK) {
            label.setForeground(color);
        }
        return label;
    }

    /** Mild colour for the result word: ordinary text, the warning colour, or the quiet help colour. */
    private static Color color(FetchStep step) {
        if (step.status == FetchStep.Status.FAILED) {
            return warningColor();
        }
        if (step.status == FetchStep.Status.NOT_ASKED) {
            return UiStyle.helpColor();
        }
        Color ordinary = UIManager.getColor("Label.foreground");
        return ordinary == null ? Color.BLACK : ordinary;
    }

    /** Opens a request address. A failure is written under the list and the button stays usable. */
    private void openAddress(String address, String name) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            note.setText("This computer cannot open links. The address is still available from Copy query, when the step has one.");
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create(address));
            note.setText(" ");
        } catch (Exception failure) {
            note.setText("Could not open " + name + ": " + failure.getMessage());
        }
    }

    /** Copies the ADQL. A failure is written under the list and the button stays usable. */
    private void copyQuery(String query, String name) {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(query), null);
            note.setText("Copied the query for " + name + ".");
        } catch (IllegalStateException failure) {
            note.setText("Could not copy the query for " + name + ": " + failure.getMessage());
        }
    }

    /** A grid cell. The last columns take the remaining width. */
    private static GridBagConstraints cell(int row, int column, int right) {
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = column;
        cell.gridy = row;
        cell.anchor = GridBagConstraints.NORTHWEST;
        cell.insets = new Insets(3, 0, 3, right);
        cell.weightx = column == 0 || column == 3 ? 0.3 : 0.15;
        cell.fill = GridBagConstraints.HORIZONTAL;
        return cell;
    }

    /** A stable description of the steps, so an unchanged summary is not rebuilt. */
    private static String textOf(List<FetchStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (FetchStep step : steps) {
            if (step == null) {
                continue;
            }
            text.append(step.service).append('|').append(step.question).append('|').append(step.status).append('|')
                    .append(step.outcome).append('|').append(step.detail).append('|').append(step.openUrl()).append('|')
                    .append(step.query).append('\n');
        }
        return text.toString();
    }

    /** Text safe to put in the HTML renderer. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
