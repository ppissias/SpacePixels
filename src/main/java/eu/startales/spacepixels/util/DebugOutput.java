/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 */
package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import io.github.ppissias.jtransient.engine.JTransientEngine;

import java.util.Locale;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SpacePixels prints only problems to the console. Starting the JVM with {@code -Dspacepixels.debug=true} adds the
 * diagnostics: per-frame statistics, timings, the engine's own debug output and the INFO log.
 */
public final class DebugOutput {

    /** The system property that turns the diagnostics on. */
    public static final String PROPERTY = "spacepixels.debug";

    private DebugOutput() {
    }

    public static boolean isEnabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void println(String message) {
        if (isEnabled()) {
            System.out.println(message);
        }
    }

    public static void printf(String format, Object... arguments) {
        if (isEnabled()) {
            System.out.printf(format, arguments);
        }
    }

    public static void printf(Locale locale, String format, Object... arguments) {
        if (isEnabled()) {
            System.out.printf(locale, format, arguments);
        }
    }

    /** Sets the engine's debug switches to match; the pipeline calls this before each run. */
    public static void applyToEngine() {
        JTransientEngine.DEBUG = isEnabled();
        JTransientAutoTuner.DEBUG = isEnabled();
    }

    /** For the application entry points: the console log shows only warnings and errors unless debugging. */
    public static void configureApplicationLogging() {
        Level level = isEnabled() ? Level.INFO : Level.WARNING;
        Logger root = Logger.getLogger("");
        root.setLevel(level);
        for (Handler handler : root.getHandlers()) {
            handler.setLevel(level);
        }
        applyToEngine();
    }
}
