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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Report-owned registry collecting animation metadata during a single report export operation.
 * Instances are owned by {@link DetectionReportContext}, ensuring bounded lifetime without global state
 * and isolating concurrent export operations.
 */
public final class AnimationRegistry {

    private final Map<String, AnimationMetadata> animations = new ConcurrentHashMap<>();

    /**
     * Creates a new animation registry for an export operation.
     */
    public AnimationRegistry() {
    }

    /**
     * Registers animation metadata for an exported animation asset.
     *
     * @param metadata the animation metadata to register
     */
    public void register(AnimationMetadata metadata) {
        if (metadata != null) {
            animations.put(metadata.fileName(), metadata);
        }
    }

    /**
     * Returns an unmodifiable snapshot of all animations registered during this export.
     *
     * @return map of filename to animation metadata
     */
    public Map<String, AnimationMetadata> getAnimations() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(animations));
    }

    /**
     * Checks whether an animation with the specified filename has been registered.
     *
     * @param fileName filename of the animation asset
     * @return true if registered
     */
    public boolean contains(String fileName) {
        return animations.containsKey(Objects.requireNonNull(fileName, "fileName"));
    }

    /**
     * Returns the total count of animations registered during this export.
     *
     * @return number of animations
     */
    public int size() {
        return animations.size();
    }
}
