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

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * A panel whose layout puts its controls on more rows when it is narrow. A change of width also changes the height it
 * needs, so the enclosing layouts measure it again.
 */
final class WidthFollowingPanel extends JPanel {

    @Override
    public void setBounds(int x, int y, int width, int height) {
        boolean changed = width != getWidth();
        super.setBounds(x, y, width, height);
        if (changed) SwingUtilities.invokeLater(this::revalidate);
    }
}
