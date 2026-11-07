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
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * The "Did you know?" screen: one tip at a time, with a link to its section of the manual. It does not block the main
 * window. Each start shows the next tip, and the choice to show it at all is remembered in the Java preferences.
 */
final class TipsDialog extends JDialog {

    /** Java preference (node of this package): whether the tips open with the application. */
    static final String SHOW_AT_STARTUP = "tips.showAtStartup";
    /** Java preference (node of this package): the tip to show next. */
    static final String NEXT_TIP = "tips.next";
    /** Logical width the tips wrap at. */
    private static final int TEXT_WIDTH = 500;

    private final List<StartupTips.Tip> tips = StartupTips.ALL;
    private final Consumer<String> openInBrowser;
    private final WrappingHelpLabel text = new WrappingHelpLabel("", TEXT_WIDTH);
    private final JLabel position = new JLabel();
    private final JButton next = new JButton("Next ▶");
    private int index;

    /** Builds the screen on the EDT; {@code openInBrowser} opens a manual section. */
    TipsDialog(Window owner, Consumer<String> openInBrowser) {
        super(owner, "SpacePixels tips", ModalityType.MODELESS);
        this.openInBrowser = openInBrowser;
        setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        AppIcon.apply(this);

        JLabel heading = new JLabel("Did you know?");
        heading.setFont(UiStyle.sectionFont());
        heading.setForeground(DetectionConfigurationPanel.accentColor());
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, UiStyle.space(8), 0));
        text.setForeground(UIManager.getColor("Label.foreground"));
        text.setVerticalAlignment(SwingConstants.TOP);
        // As tall as the longest tip, so the buttons stay where they are from one tip to the next.
        JPanel textArea = new JPanel(new BorderLayout());
        textArea.add(text, BorderLayout.NORTH);
        textArea.setPreferredSize(new Dimension(UiStyle.space(TEXT_WIDTH), tallestTip()));

        JButton manual = AccessibleControls.link("Read more in the manual", "Open this tip's section of the manual",
                () -> openInBrowser.accept(ApplicationWindow.MANUAL_URL + "#" + tips.get(index).manualSection()));
        manual.setToolTipText("Opens this tip's section of the manual in your browser.");
        manual.setHorizontalAlignment(SwingConstants.LEFT);
        manual.setMargin(new Insets(0, 0, 0, 0));
        JPanel manualRow = new JPanel(new BorderLayout());
        manualRow.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(4), 0, UiStyle.space(10), 0));
        manualRow.add(manual, BorderLayout.WEST);

        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(14), UiStyle.space(16), UiStyle.space(4), UiStyle.space(16)));
        body.add(heading, BorderLayout.NORTH);
        body.add(textArea, BorderLayout.CENTER);
        body.add(manualRow, BorderLayout.SOUTH);

        JCheckBox atStartup = new JCheckBox("Show tips at startup", wantedAtStartup());
        atStartup.setToolTipText("You can always open the tips from the Help menu at the right end of the tab bar.");
        atStartup.addActionListener(event -> remember(preferences -> preferences.putBoolean(SHOW_AT_STARTUP, atStartup.isSelected())));
        JButton previous = new JButton("◀ Previous");
        previous.addActionListener(event -> show(index - 1));
        next.addActionListener(event -> show(index + 1));
        JButton close = new JButton("Close");
        close.addActionListener(event -> setVisible(false));
        position.setForeground(UiStyle.helpColor());
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiStyle.space(6), 0));
        navigation.add(position);
        navigation.add(previous);
        navigation.add(next);
        navigation.add(close);
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBorder(BorderFactory.createEmptyBorder(UiStyle.space(4), UiStyle.space(12), UiStyle.space(12), UiStyle.space(16)));
        buttons.add(atStartup, BorderLayout.WEST);
        buttons.add(navigation, BorderLayout.EAST);

        add(body, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(next);
        getRootPane().registerKeyboardAction(event -> setVisible(false),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        WindowSizing.install(this, 480, 220);
        show(savedNextTip());
        pack();
        setLocationRelativeTo(owner);
    }

    /** Brings the screen up on the tip it is due to show. */
    void open() {
        setLocationRelativeTo(getOwner());
        next.requestFocusInWindow();
        setVisible(true);
        toFront();
    }

    /** Shows this tip, wrapping around at either end, and makes the one after it the next start's tip. */
    private void show(int requested) {
        index = wrap(requested, tips.size());
        text.setText("<html>" + tips.get(index).html() + "</html>");
        position.setText((index + 1) + " of " + tips.size());
        remember(preferences -> preferences.putInt(NEXT_TIP, wrap(index + 1, tips.size())));
    }

    /** The height of the longest tip at the width they wrap at. */
    private int tallestTip() {
        int tallest = 0;
        for (StartupTips.Tip tip : tips) {
            tallest = Math.max(tallest, new WrappingHelpLabel(tip.html(), TEXT_WIDTH).getPreferredSize().height);
        }
        return tallest;
    }

    /** This index within {@code count} tips, going round at either end. */
    static int wrap(int index, int count) {
        return Math.floorMod(index, count);
    }

    /** Whether the tips open with the application; true until the user switches them off. */
    static boolean wantedAtStartup() {
        try {
            return Preferences.userNodeForPackage(TipsDialog.class).getBoolean(SHOW_AT_STARTUP, true);
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static int savedNextTip() {
        try {
            return Preferences.userNodeForPackage(TipsDialog.class).getInt(NEXT_TIP, 0);
        } catch (RuntimeException unavailable) {
            return 0;
        }
    }

    /** Stores a choice where the preferences work; without them the screen just starts from its first tip. */
    private static void remember(Consumer<Preferences> change) {
        try {
            change.accept(Preferences.userNodeForPackage(TipsDialog.class));
        } catch (RuntimeException unavailable) {
            // The choice lasts until the application closes.
        }
    }
}
