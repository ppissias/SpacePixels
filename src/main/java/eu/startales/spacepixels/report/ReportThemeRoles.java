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

import java.awt.Color;
import java.util.Locale;

/**
 * The colour roles of the finding types, made from the application's category colours so a finding has one colour
 * in its cards, the legend, the maps and the labels. Each type's role, for example {@code --sp-finding-st}, colours
 * text and borders on the page: the category colour itself in the dark theme, and a darker shade of the same hue in
 * the light theme, so it stays readable on a light page. Marks drawn over the always-dark images keep the category
 * colour in both themes.
 */
final class ReportThemeRoles {

    /** The contrast a finding colour keeps against a white page in the light theme: WCAG AA for normal text. */
    static final double LIGHT_THEME_CONTRAST = 4.5;

    private ReportThemeRoles() {
    }

    /** The CSS role of a category, for example {@code --sp-finding-st}. */
    static String role(FindingCategory category) {
        return "--sp-finding-" + category.prefix().toLowerCase(Locale.ROOT);
    }

    /** {@code var(--sp-finding-…)} for a category, for inline styles. */
    static String var(FindingCategory category) {
        return "var(" + role(category) + ")";
    }

    /** The finding roles for the dark theme (the root) and the light theme. */
    static String findingRoles() {
        StringBuilder dark = new StringBuilder(":root {\n");
        StringBuilder light = new StringBuilder(":root[data-theme='light'] {\n");
        for (FindingCategory category : FindingCategory.values()) {
            dark.append("  ").append(role(category)).append(": ").append(hex(category.color())).append(";\n");
            light.append("  ").append(role(category)).append(": ").append(hex(onLightPage(category.color()))).append(";\n");
        }
        return dark.append("}\n").append(light).append("}\n").toString();
    }

    /** The same hue and saturation, darkened until it has the light theme's contrast against a white page. */
    static Color onLightPage(Color color) {
        float[] hsl = hsl(color);
        Color result = color;
        for (float lightness = hsl[2]; lightness > 0 && contrastWithWhite(result) < LIGHT_THEME_CONTRAST; lightness -= 0.01f) {
            result = fromHsl(hsl[0], hsl[1], Math.max(0, lightness));
        }
        return result;
    }

    /** The WCAG contrast ratio of a colour against white. */
    static double contrastWithWhite(Color color) {
        return 1.05 / (luminance(color) + 0.05);
    }

    /** Relative luminance as WCAG defines it. */
    private static double luminance(Color color) {
        double[] c = {color.getRed() / 255.0, color.getGreen() / 255.0, color.getBlue() / 255.0};
        for (int i = 0; i < 3; i++) c[i] = c[i] <= 0.03928 ? c[i] / 12.92 : Math.pow((c[i] + 0.055) / 1.055, 2.4);
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    static String hex(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static float[] hsl(Color color) {
        float r = color.getRed() / 255f, g = color.getGreen() / 255f, b = color.getBlue() / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2, h = 0, s = 0;
        if (max != min) {
            float d = max - min;
            s = l > 0.5f ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new float[]{h, s, l};
    }

    private static Color fromHsl(float h, float s, float l) {
        if (s == 0) {
            int v = Math.round(l * 255);
            return new Color(v, v, v);
        }
        float q = l < 0.5f ? l * (1 + s) : l + s - l * s, p = 2 * l - q;
        return new Color(Math.round(hue(p, q, h + 1f / 3) * 255), Math.round(hue(p, q, h) * 255),
                Math.round(hue(p, q, h - 1f / 3) * 255));
    }

    private static float hue(float p, float q, float t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1f / 6) return p + (q - p) * 6 * t;
        if (t < 1f / 2) return q;
        if (t < 2f / 3) return p + (q - p) * (2f / 3 - t) * 6;
        return p;
    }
}
