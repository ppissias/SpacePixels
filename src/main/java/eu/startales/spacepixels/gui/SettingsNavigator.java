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
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The Detection Settings navigation: a page list with group headings on the left, the pages on the right, a search
 * box and a "show only changed" filter that work across all pages, and expert sections that start collapsed.
 */
final class SettingsNavigator extends JPanel {

    /** A titled block of rows on a page; expert sections can be collapsed. */
    static final class Section {
        final JLabel header;
        final String title;
        final boolean expert;
        final List<SettingRow> rows = new ArrayList<>();
        boolean collapsed;

        Section(JLabel header, String title, boolean expert) {
            this.header = header;
            this.title = title;
            this.expert = expert;
            this.collapsed = expert;
        }
    }

    private static final class Page {
        final String group;
        final String title;
        final JPanel content;
        final List<String> extraSearchTitles;
        final List<Section> sections = new ArrayList<>();
        final JLabel emptyLabel = new JLabel("No settings on this page match the filter.");
        int matchCount;
        int changedCount;

        Page(String group, String title, JPanel content, List<String> extraSearchTitles) {
            this.group = group;
            this.title = title;
            this.content = content;
            this.extraSearchTitles = extraSearchTitles;
        }
    }

    /** An entry of the page list: a group heading or a page. */
    private static final class NavItem {
        final String heading;
        final Page page;

        NavItem(String heading, Page page) {
            this.heading = heading;
            this.page = page;
        }
    }

    private final List<SettingRow> rows = new ArrayList<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Map<String, IntSupplier> extraChangedCounts = new LinkedHashMap<>();
    private final DefaultListModel<NavItem> navModel = new DefaultListModel<>();
    private final JList<NavItem> navList = new JList<>(navModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel cardPanel = new JPanel(cards);
    private final JTextField searchField = new JTextField();
    private final JCheckBox onlyChangedCheck = new JCheckBox("Show only unsaved changes");
    private String lastGroup;
    private boolean ready;

    SettingsNavigator() {
        super(new BorderLayout());

        searchField.putClientProperty("JTextField.placeholderText", "Search settings (Ctrl+F)");
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.setToolTipText("Filter all pages by setting name, description or section.");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilters();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilters();
            }
        });
        onlyChangedCheck.setToolTipText("Show only the settings changed since the configuration was last saved.");
        onlyChangedCheck.addItemListener(e -> applyFilters());

        navList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        navList.setCellRenderer(new NavRenderer());
        ToolTipManager.sharedInstance().registerComponent(navList);
        navList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }
            int index = navList.getSelectedIndex();
            if (index < 0) {
                return;
            }
            NavItem item = navModel.get(index);
            if (item.page == null) {
                // Headings are not pages; move to the first page of the group.
                if (index + 1 < navModel.size()) {
                    navList.setSelectedIndex(index + 1);
                }
                return;
            }
            cards.show(cardPanel, item.page.title);
        });

        JPanel side = new JPanel(new BorderLayout(0, 6));
        side.setBorder(new EmptyBorder(0, 0, 0, 10));
        JPanel filters = new JPanel();
        filters.setLayout(new BoxLayout(filters, BoxLayout.Y_AXIS));
        searchField.setAlignmentX(Component.LEFT_ALIGNMENT);
        searchField.setMaximumSize(new Dimension(Integer.MAX_VALUE, searchField.getPreferredSize().height));
        onlyChangedCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        filters.add(searchField);
        filters.add(Box.createVerticalStrut(4));
        filters.add(onlyChangedCheck);
        side.add(filters, BorderLayout.NORTH);
        JScrollPane navScroll = new JScrollPane(navList);
        navScroll.setPreferredSize(new Dimension(220, 300));
        side.add(navScroll, BorderLayout.CENTER);

        add(side, BorderLayout.WEST);
        add(cardPanel, BorderLayout.CENTER);
    }

    JTextField searchField() {
        return searchField;
    }

    /** Registers a setting row; it is assigned to its page when the page is added. */
    void registerRow(SettingRow row) {
        rows.add(row);
        row.onChange(() -> {
            if (ready) {
                applyFilters();
            }
        });
    }

    /**
     * Adds a page. {@code extraSearchTitles} are names of settings on the page that are not registered rows (the
     * core settings on the Overview), so a search still points to the page.
     */
    void addPage(String group, String title, JPanel content, List<String> extraSearchTitles) {
        Page page = new Page(group, title, content, extraSearchTitles);
        pages.put(title, page);
        if (group != null && !group.equals(lastGroup)) {
            navModel.addElement(new NavItem(group, null));
        }
        lastGroup = group;
        navModel.addElement(new NavItem(null, page));

        page.emptyLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        page.emptyLabel.setBorder(new EmptyBorder(10, 0, 0, 0));
        page.emptyLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        page.emptyLabel.setVisible(false);
        content.add(page.emptyLabel);

        JPanel holder = new WidthTrackingPanel();
        holder.add(content, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(holder);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        cardPanel.add(scrollPane, title);
    }

    /**
     * Groups the rows of each page under their section headers, makes the expert sections collapsible, and shows
     * the first page. Called once all pages are added and the defaults are captured.
     */
    void finishBuilding(Set<String> expertSections) {
        Map<JLabel, Section> sections = new LinkedHashMap<>();
        for (SettingRow row : rows) {
            Page page = pageOf(row);
            JLabel header = headerAbove(row);
            if (page == null || header == null) {
                continue;
            }
            Section section = sections.get(header);
            if (section == null) {
                String title = header.getText();
                section = new Section(header, title, expertSections.contains(title));
                sections.put(header, section);
                page.sections.add(section);
                if (section.expert) {
                    makeCollapsible(section);
                }
            }
            section.rows.add(row);
            row.section = section;
        }
        ready = true;
        for (SettingRow row : rows) {
            row.refresh();
        }
        applyFilters();
        navList.setSelectedIndex(0);
    }

    /** Takes the current values as the saved ones (after loading or saving the configuration) and refreshes the marks. */
    void markAllSaved() {
        for (SettingRow row : rows) {
            row.captureSaved();
        }
        if (ready) {
            for (SettingRow row : rows) {
                row.refresh();
            }
            applyFilters();
        }
    }

    /** Shows the page with this title. */
    void showPage(String title) {
        for (int i = 0; i < navModel.size(); i++) {
            NavItem item = navModel.get(i);
            if (item.page != null && item.page.title.equals(title)) {
                navList.setSelectedIndex(i);
                navList.ensureIndexIsVisible(i);
                return;
            }
        }
    }

    private Page pageOf(SettingRow row) {
        for (Page page : pages.values()) {
            if (page.content == row.parent) {
                return page;
            }
        }
        return null;
    }

    /** The section header placed above the row on its page. */
    private static JLabel headerAbove(SettingRow row) {
        Component[] components = row.parent.getComponents();
        JLabel header = null;
        for (Component c : components) {
            if (c == row.row) {
                return header;
            }
            if (c instanceof JLabel && Boolean.TRUE.equals(((JLabel) c).getClientProperty(DetectionConfigurationPanel.SECTION_HEADER))) {
                header = (JLabel) c;
            }
        }
        return null;
    }

    private void makeCollapsible(Section section) {
        section.header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        section.header.setToolTipText("Expert settings: click to show or hide.");
        section.header.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!isFiltering()) {
                    section.collapsed = !section.collapsed;
                    applyFilters();
                }
            }
        });
    }

    private boolean isFiltering() {
        return !searchField.getText().trim().isEmpty() || onlyChangedCheck.isSelected();
    }

    /** Shows the rows that match the search and the changed filter, and updates headers and page counts. */
    /** Counts settings changed since the last save that are not rows of the page (for example the Overview core settings). */
    void setExtraChangedCount(String pageTitle, IntSupplier count) {
        extraChangedCounts.put(pageTitle, count);
    }

    /** Re-evaluates the filters and counts after values changed outside a row. */
    void refreshFilters() {
        applyFilters();
    }

    private void applyFilters() {
        if (!ready) {
            return;
        }
        String query = searchField.getText().trim().toLowerCase(Locale.ROOT);
        boolean onlyChanged = onlyChangedCheck.isSelected();
        boolean filtering = !query.isEmpty() || onlyChanged;
        int totalChanged = 0;

        for (Page page : pages.values()) {
            page.matchCount = 0;
            page.changedCount = 0;
            for (Section section : page.sections) {
                int sectionMatches = 0;
                int sectionChanged = 0;
                for (SettingRow row : section.rows) {
                    boolean changed = row.isChanged();
                    boolean matches = (query.isEmpty() || row.matches(query)) && (!onlyChanged || changed);
                    row.row.setVisible(filtering ? matches : !(section.expert && section.collapsed));
                    if (matches) {
                        sectionMatches++;
                    }
                    if (changed) {
                        sectionChanged++;
                    }
                }
                section.header.setVisible(!filtering || sectionMatches > 0);
                section.header.setText(headerText(section, filtering, sectionChanged));
                page.matchCount += sectionMatches;
                page.changedCount += sectionChanged;
            }
            IntSupplier extraCount = extraChangedCounts.get(page.title);
            if (extraCount != null) {
                // Settings on the page that are not rows (the core settings on the Overview).
                int extraChanged = extraCount.getAsInt();
                page.changedCount += extraChanged;
                if (onlyChanged && query.isEmpty()) {
                    page.matchCount += extraChanged;
                }
            }
            if (!onlyChanged && !query.isEmpty()) {
                for (String extra : page.extraSearchTitles) {
                    if (extra.toLowerCase(Locale.ROOT).contains(query)) {
                        page.matchCount++;
                    }
                }
            }
            totalChanged += page.changedCount;
            page.emptyLabel.setVisible(filtering && page.matchCount == 0 && !page.sections.isEmpty());
            page.content.revalidate();
            page.content.repaint();
        }

        onlyChangedCheck.setText("Show only unsaved changes (" + totalChanged + ")");
        navList.repaint();

        NavItem selected = navList.getSelectedValue();
        if (filtering && selected != null && selected.page != null && selected.page.matchCount == 0) {
            for (Page page : pages.values()) {
                if (page.matchCount > 0) {
                    showPage(page.title);
                    break;
                }
            }
        }
    }

    private static String headerText(Section section, boolean filtering, int changed) {
        String details = (section.expert ? section.rows.size() + " settings" : "")
                + (changed > 0 ? (section.expert ? " · " : "") + changed + " changed" : "");
        String arrow = !section.expert ? "" : (filtering || !section.collapsed ? "▾ " : "▸ ");
        if (details.isEmpty()) {
            return arrow.isEmpty() ? section.title : arrow + section.title;
        }
        return "<html>" + arrow + section.title + "&nbsp;&nbsp;<span style='font-size: 0.8em; font-weight: normal; color: #999999;'>"
                + details + "</span></html>";
    }

    /**
     * Holds a page in its scroll pane and takes the width of the viewport, so a page reflows to the window instead
     * of scrolling sideways; only a viewport narrower than the page's minimum width brings the horizontal bar back.
     */
    static final class WidthTrackingPanel extends JPanel implements Scrollable {
        WidthTrackingPanel() {
            super(new BorderLayout());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.VERTICAL ? visibleRect.height - 32 : visibleRect.width - 32;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport && getParent().getWidth() >= getMinimumSize().width;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport && getParent().getHeight() > getPreferredSize().height;
        }
    }

    private final class NavRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            NavItem item = (NavItem) value;
            if (item.page == null) {
                JLabel heading = (JLabel) super.getListCellRendererComponent(list, item.heading, index, false, false);
                heading.setFont(heading.getFont().deriveFont(Font.BOLD, heading.getFont().getSize2D() - 1f));
                heading.setForeground(UIManager.getColor("Label.disabledForeground"));
                heading.setBorder(new EmptyBorder(index == 0 ? 2 : 10, 6, 2, 6));
                return heading;
            }
            Page page = item.page;
            boolean filtering = isFiltering();
            String suffix = filtering ? "  (" + page.matchCount + ")" : (page.changedCount > 0 ? "  •" : "");
            JLabel label = (JLabel) super.getListCellRendererComponent(list, page.title + suffix, index, isSelected, cellHasFocus);
            label.setBorder(new EmptyBorder(3, page.group == null ? 6 : 16, 3, 6));
            if (filtering && page.matchCount == 0 && !isSelected) {
                label.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            label.setToolTipText(!filtering && page.changedCount > 0
                    ? page.changedCount + (page.changedCount == 1 ? " setting" : " settings") + " changed since the last save"
                    : null);
            return label;
        }
    }
}
