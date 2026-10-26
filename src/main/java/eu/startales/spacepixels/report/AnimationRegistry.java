/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that collects animation metadata per output directory during report export.
 */
public final class AnimationRegistry {

    private static final Map<String, Map<String, AnimationMetadata>> REGISTRY = new ConcurrentHashMap<>();

    private AnimationRegistry() {
    }

    private static String normalizeDir(File dir) {
        if (dir == null) {
            return "";
        }
        try {
            return dir.getCanonicalPath();
        } catch (Exception e) {
            return dir.getAbsolutePath();
        }
    }

    public static void register(File dir, AnimationMetadata metadata) {
        if (metadata == null) {
            return;
        }
        String key = normalizeDir(dir);
        REGISTRY.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(metadata.fileName(), metadata);
    }

    public static Map<String, AnimationMetadata> getAnimations(File dir) {
        String key = normalizeDir(dir);
        Map<String, AnimationMetadata> map = REGISTRY.get(key);
        if (map == null || map.isEmpty()) {
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(map);
    }

    public static void clear(File dir) {
        String key = normalizeDir(dir);
        REGISTRY.remove(key);
    }

    public static void clearAll() {
        REGISTRY.clear();
    }
}
