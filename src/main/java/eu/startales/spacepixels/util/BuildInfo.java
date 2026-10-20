/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** The versions this build was made with, from {@code spacepixels-build.properties}; "development" when unknown. */
public final class BuildInfo {

    private static final Properties BUILD = new Properties();

    static {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/spacepixels-build.properties")) {
            if (in != null) {
                BUILD.load(in);
            }
        } catch (IOException ignored) {
            // Without the file the versions read "development".
        }
    }

    private BuildInfo() {
    }

    /** The version of {@code component}: "spacepixels" or "jtransient". */
    public static String version(String component) {
        return BUILD.getProperty(component, "development");
    }
}
