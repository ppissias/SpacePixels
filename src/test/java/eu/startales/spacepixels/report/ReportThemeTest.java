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

import org.junit.Test;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/** The report's colour roles: every role a stylesheet uses is defined by the theme, outside any comment. */
public class ReportThemeTest {

    private static final Pattern USE = Pattern.compile("var\\(--sp-([a-z-]+)\\)");
    private static final Pattern DEFINITION = Pattern.compile("--sp-([a-z-]+)\\s*:");

    @Test public void themeDefinesEveryRoleTheStylesheetsUse() throws Exception {
        String theme = withoutComments(resource("report-theme.css"));
        int root = theme.indexOf(":root");
        assertTrue("the theme's :root rule must not be inside a comment", root >= 0);
        Set<String> defined = matches(DEFINITION, theme.substring(root));
        defined.addAll(matches(DEFINITION, ReportThemeRoles.findingRoles()));
        Set<String> used = new TreeSet<>();
        for (String sheet : new String[]{"report.css", "iterative-results.css", "faint-mover-review.css",
                "session-pictures.css", "unclassified-inspector.css"}) {
            used.addAll(matches(USE, withoutComments(resource(sheet))));
        }
        used.removeAll(defined);
        assertEquals("roles used but not defined", Set.of(), used);
        assertTrue(defined.size() > 40);
    }

    /** Each finding type has one role: its category colour on the dark page, a darker shade of it on the light page. */
    @Test public void findingRolesFollowTheCategoryColours() {
        String roles = ReportThemeRoles.findingRoles();
        for (FindingCategory category : FindingCategory.values()) {
            assertTrue(roles.contains(ReportThemeRoles.role(category) + ": " + ReportThemeRoles.hex(category.color()) + ";"));
            java.awt.Color light = ReportThemeRoles.onLightPage(category.color());
            float[] hsb = java.awt.Color.RGBtoHSB(light.getRed(), light.getGreen(), light.getBlue(), null);
            assertTrue(category + " must keep WCAG AA contrast on a light page",
                    ReportThemeRoles.contrastWithWhite(light) >= ReportThemeRoles.LIGHT_THEME_CONTRAST);
            if (category.color().getRed() != category.color().getGreen()) {
                float[] original = java.awt.Color.RGBtoHSB(category.color().getRed(), category.color().getGreen(), category.color().getBlue(), null);
                assertEquals(category + " keeps its hue", original[0], hsb[0], 0.02);
            }
        }
    }

    /** The report's style file carries the theme before its own styles, so the roles apply. */
    @Test public void reportStyleCarriesTheThemeFirst() throws Exception {
        String style = ReportAssets.reportStyle();
        int theme = style.indexOf("--sp-page:");
        int body = style.indexOf("body { font-family");
        assertTrue(theme > 0 && body > theme);
        assertFalse("no hardcoded colour remains in the report's own styles",
                withoutComments(resource("report.css")).matches("(?s).*#[0-9a-fA-F]{6}\\b.*"));
    }

    /** The page links the folder's custom style after its own styles, so the custom rules take precedence. */
    @Test public void reportPagesApplyTheCustomStyleLast() {
        StringWriter out = new StringWriter();
        DetectionReportDocumentWriter.appendDetectionReportStart(new PrintWriter(out, true));
        String html = out.toString();
        assertFalse("the styles are in their own file", html.contains("<style"));
        assertTrue(html.contains(ReportAssets.STYLE_LINK + DetectionReportDocumentWriter.CUSTOM_STYLE));
        assertTrue(html.indexOf(DetectionReportDocumentWriter.CUSTOM_STYLE) < html.indexOf("</head>"));
    }

    /**
     * The chosen style file is copied into the report folder. Without one, or when it is missing, the folder gets a
     * file holding only a comment, and a file already there is kept.
     */
    @Test public void customStyleIsCopiedIntoTheReportFolder() throws Exception {
        Path folder = Files.createTempDirectory("report-style");
        try {
            Path style = Files.writeString(folder.resolve("mine.css"), ":root { --sp-accent: #ff9f1c; }");
            Path report = Files.createDirectory(folder.resolve("report"));
            DetectionReportDocumentWriter.writeCustomStyle(style.toString(), report.toFile());
            assertEquals(Files.readString(style), Files.readString(report.resolve("report-custom.css")));

            Path other = Files.createDirectory(folder.resolve("other"));
            DetectionReportDocumentWriter.writeCustomStyle(folder.resolve("missing.css").toString(), other.toFile());
            String note = Files.readString(other.resolve("report-custom.css"));
            assertTrue(note.contains("report-custom-example.css"));
            assertEquals("only a comment", "", withoutComments(note).strip());

            Files.writeString(other.resolve("report-custom.css"), "body { font-family: serif; }");
            DetectionReportDocumentWriter.writeCustomStyle("", other.toFile());
            assertEquals("body { font-family: serif; }", Files.readString(other.resolve("report-custom.css")));
        } finally {
            try (var paths = Files.walk(folder)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The shipped example lists every role of both themes with its built-in value, ready to uncomment. */
    @Test public void exampleStyleListsEveryRoleWithItsBuiltInValue() throws Exception {
        String example = Files.readString(Path.of("src/dist/config/report-custom-example.css"), StandardCharsets.UTF_8);
        String uncommented = withoutComments(example.replaceAll("/\\* (--sp-[a-z-]+: [^;]+;) \\*/", "$1"));
        String theme = withoutComments(resource("report-theme.css")) + ReportThemeRoles.findingRoles();
        for (String rule : new String[]{":root {", ":root[data-theme='light'] {"}) {
            Map<String, String> builtIn = declarations(theme, rule);
            assertTrue(builtIn.size() > 40);
            assertEquals(rule, builtIn, declarations(uncommented, rule));
        }
    }

    /** The role values set by every rule with this selector. */
    private static Map<String, String> declarations(String css, String selector) {
        Map<String, String> found = new TreeMap<>();
        for (int from = css.indexOf(selector); from >= 0; from = css.indexOf(selector, from + 1)) {
            Matcher m = Pattern.compile("(--sp-[a-z-]+):\\s*([^;]+);").matcher(css.substring(from, css.indexOf('}', from)));
            while (m.find()) found.put(m.group(1), m.group(2).trim());
        }
        return found;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    /** Removes comments as a browser does: an unclosed comment runs to the end. */
    private static String withoutComments(String css) {
        return css.replaceAll("(?s)/\\*.*?(\\*/|$)", "");
    }

    private static String resource(String name) throws Exception {
        try (InputStream in = DetectionReportDocumentWriter.class.getResourceAsStream(name)) {
            assertNotNull(name, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
