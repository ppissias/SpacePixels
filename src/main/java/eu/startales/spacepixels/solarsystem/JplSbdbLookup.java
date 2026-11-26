/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.solarsystem;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds JPL Small-Body Database (SBDB) lookup URLs from saved Solar System object names and designations without
 * making network requests. Shared by HTML detection reports and the Asteroids &amp; Comets window.
 */
public final class JplSbdbLookup {

    private static final String PREFIX = "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=";

    private JplSbdbLookup() {
    }

    /**
     * Builds a JPL SBDB lookup URL for a named asteroid or comet, inferring comet designations when the object type
     * is not separately recorded (for example, from later identifications).
     */
    public static String informationUrl(String name) {
        return informationUrl(name, looksLikeComet(name));
    }

    /**
     * Builds a JPL SBDB lookup URL from a saved designation; returns {@code null} when {@code name} is blank.
     */
    public static String informationUrl(String name, boolean comet) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String search = name.trim();
        if (!comet && search.matches("\\(?\\d{4} (?:[A-Z]{2}\\d*|[PT]-[L123])\\)?")) {
            search = search.replace("(", "").replace(")", "");
        } else if (!comet && search.matches("\\d+(?: .*)?")) {
            search = search.split(" ", 2)[0];
        } else if (comet) {
            search = search.split(" \\(", 2)[0];
            if (search.matches("\\d+[PDCXAI](?:-[A-Z0-9]+)?/.*")) {
                search = search.substring(0, search.indexOf('/'));
            }
        }
        return PREFIX + URLEncoder.encode(search, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Whether a saved object name uses a standard IAU comet designation prefix (e.g. {@code C/2023 A3}, {@code 73P-B/...}). */
    public static boolean looksLikeComet(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        return trimmed.matches("(?:[CPDXAI]/|\\d+[PDCXAI](?:-[A-Z0-9]+)?(?:/|\\s|$)).*");
    }
}
