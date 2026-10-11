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

import javax.imageio.ImageIO;
import javax.swing.JOptionPane;
import java.awt.Image;
import java.awt.Taskbar;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The application icon, in several sizes so the system can pick a sharp one for the title bar, the taskbar and the
 * window switcher.
 */
final class AppIcon {

    private static final Logger logger = Logger.getLogger(AppIcon.class.getName());
    private static final int[] SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256, 512};
    private static List<Image> images;

    private AppIcon() {
    }

    /** The icon in every available size, largest last; empty if the images cannot be read. */
    static synchronized List<Image> images() {
        if (images == null) {
            List<Image> loaded = new ArrayList<>();
            for (int size : SIZES) {
                try (InputStream in = AppIcon.class.getResourceAsStream("icons/spacepixels-" + size + ".png")) {
                    if (in != null) {
                        loaded.add(ImageIO.read(in));
                    }
                } catch (IOException e) {
                    logger.log(Level.FINE, "Could not read the " + size + " px application icon", e);
                }
            }
            images = Collections.unmodifiableList(loaded);
        }
        return images;
    }

    /** Sets the icon on a window. */
    static void apply(Window window) {
        List<Image> icons = images();
        if (!icons.isEmpty()) {
            window.setIconImages(icons);
        }
    }

    /**
     * Sets the icon for the whole application: the macOS Dock (where supported) and the message boxes opened without
     * a parent window.
     */
    static void installForApplication() {
        List<Image> icons = images();
        if (icons.isEmpty()) {
            return;
        }
        JOptionPane.getRootFrame().setIconImages(icons);
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            try {
                Taskbar.getTaskbar().setIconImage(icons.get(icons.size() - 1));
            } catch (UnsupportedOperationException | SecurityException e) {
                logger.log(Level.FINE, "Could not set the Dock icon", e);
            }
        }
    }
}
