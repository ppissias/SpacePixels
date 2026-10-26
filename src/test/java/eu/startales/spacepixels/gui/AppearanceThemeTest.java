package eu.startales.spacepixels.gui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.swing.*;
import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.util.Map;

import static org.junit.Assert.*;

/** Checks real FlatLaf colours, font scaling and fallback while restoring shared Swing defaults between tests. */
public class AppearanceThemeTest {
    private LookAndFeel original;
    private float zoom;
    private Font labelFont;
    private Font defaultFont;

    /** Removes test-installed font overrides before measuring the actual look-and-feel defaults. */
    @Before public void captureDefaults() throws Exception {
        EventQueue.invokeAndWait(() -> {
            original = UIManager.getLookAndFeel();
            zoom = UIScale.getZoomFactor();
            labelFont = UIManager.getFont("Label.font");
            defaultFont = UIManager.getFont("defaultFont");
            UIManager.put("Label.font", null);
            UIManager.put("defaultFont", null);
        });
    }

    /** Restores the previous theme, zoom and any application-supplied fonts. */
    @After public void restoreDefaults() throws Exception {
        EventQueue.invokeAndWait(() -> {
            UIScale.setZoomFactor(zoom);
            try { UIManager.setLookAndFeel(original); } catch (UnsupportedLookAndFeelException failure) { throw new AssertionError(failure); }
            UIManager.put("Label.font", labelFont);
            UIManager.put("defaultFont", defaultFont);
        });
    }

    /** All built-in themes keep body/help/link text readable on their actual panel surface. */
    @Test public void builtInThemesHaveReadableTextAndHelp() throws Exception {
        EventQueue.invokeAndWait(() -> {
            for (var theme : AppearanceSettings.Theme.values()) {
                assertNull(install(theme, 100, Map.of()));
                assertEquals(theme != AppearanceSettings.Theme.LIGHT, FlatLaf.isLafDark());
                Color background = UIManager.getColor("Panel.background");
                assertTrue(theme + " body", contrast(UIManager.getColor("Label.foreground"), background) >= 7);
                assertTrue(theme + " help", contrast(UiStyle.helpColor(), background) >= 7);
                assertTrue(theme + " link", contrast(UiStyle.linkColor(), background) >= 4.5);
                assertTrue(theme + " primary button", contrast(UIManager.getColor("Button.default.foreground"),
                        UIManager.getColor("Button.default.background")) >= 4.5);
                if (theme == AppearanceSettings.Theme.HIGH_CONTRAST_DARK) {
                    assertEquals(Color.WHITE, UIManager.getColor("Label.foreground"));
                    assertEquals(new Color(0x101010), background);
                }
            }
        });
    }

    /** The dark image surface retains visible placeholder text when application controls use a light theme. */
    @Test public void imagePlaceholderRemainsVisibleInLightTheme() throws Exception {
        EventQueue.invokeAndWait(() -> {
            install(AppearanceSettings.Theme.LIGHT, 200, Map.of());
            FrameView view = new FrameView();
            view.setSize(400, 160);
            view.setPlaceholder("No image loaded");
            BufferedImage image = new BufferedImage(400, 160, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try { view.paint(graphics); } finally { graphics.dispose(); }
            int bright = 0;
            for (int y = 0; y < 160; y++) for (int x = 0; x < 400; x++) {
                if (((image.getRGB(x, y) >> 16) & 255) > 200) bright++;
            }
            assertTrue("placeholder pixels remain visible on the dark canvas", bright > 20);
        });
    }

    /** Startup scale enlarges standard editors, custom body text and application spacing together. */
    @Test public void interfaceSizeScalesFontsAndSpacing() throws Exception {
        EventQueue.invokeAndWait(() -> {
            install(AppearanceSettings.Theme.DARK, 100, Map.of());
            float small = UiStyle.bodyFont().getSize2D();
            float button = new JButton("Test").getFont().getSize2D();
            int gap = UiStyle.space(100);
            install(AppearanceSettings.Theme.DARK, 200, Map.of());
            assertEquals(small * 2, UiStyle.bodyFont().getSize2D(), 1);
            assertEquals(button * 2, new JButton("Test").getFont().getSize2D(), 1);
            assertEquals(gap * 2, UiStyle.space(100), 1);
        });
    }

    /** An explicit custom colour wins, and a new built-in theme does not inherit it afterwards. */
    @Test public void customDefaultsBelongToOneThemeInstance() throws Exception {
        EventQueue.invokeAndWait(() -> {
            install(AppearanceSettings.Theme.DARK, 100, Map.of("Label.foreground", "#123456"));
            assertEquals(new Color(0x123456), UIManager.getColor("Label.foreground"));
            install(AppearanceSettings.Theme.LIGHT, 100, Map.of());
            assertNotEquals(new Color(0x123456), UIManager.getColor("Label.foreground"));
        });
    }

    /** An optional font override remains relative to the selected startup interface size. */
    @Test public void customFontUsesTheChosenSize() throws Exception {
        EventQueue.invokeAndWait(() -> {
            install(AppearanceSettings.Theme.DARK, 100, Map.of("defaultFont", "18 \"SansSerif\""));
            assertEquals(18, UiStyle.bodyFont().getSize());
            install(AppearanceSettings.Theme.DARK, 200, Map.of("defaultFont", "18 \"SansSerif\""));
            assertEquals(36, UiStyle.bodyFont().getSize());
        });
    }

    /** A broken essential colour cannot prevent the application opening with its selected built-in theme. */
    @Test public void invalidEssentialDefaultsRecover() throws Exception {
        EventQueue.invokeAndWait(() -> {
            assertNotNull(install(AppearanceSettings.Theme.LIGHT, 150, Map.of("Label.foreground", "null")));
            assertNotNull(UIManager.getColor("Label.foreground"));
            assertFalse(FlatLaf.isLafDark());
        });
    }

    /** Installs a detached startup snapshot without reading or writing any preferences. */
    private static String install(AppearanceSettings.Theme theme, int percent, Map<String, String> overrides) {
        return AppearanceTheme.install(new AppearanceTheme.Prepared(new AppearanceSettings(theme, percent, ""), overrides, null));
    }

    /** Computes the sRGB contrast ratio between text and its background. */
    private static double contrast(Color text, Color background) {
        double a = luminance(text), b = luminance(background);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /** Relative luminance with the standard sRGB transfer function. */
    private static double luminance(Color color) {
        double[] channels = {color.getRed() / 255.0, color.getGreen() / 255.0, color.getBlue() / 255.0};
        for (int index = 0; index < channels.length; index++) {
            double value = channels[index];
            channels[index] = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    }
}
