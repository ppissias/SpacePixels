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
import java.util.Set;
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
        Set<String> used = new TreeSet<>();
        for (String sheet : new String[]{"report.css", "report-index.css", "faint-mover-review.css"}) {
            used.addAll(matches(USE, withoutComments(resource(sheet))));
        }
        used.removeAll(defined);
        assertEquals("roles used but not defined", Set.of(), used);
        assertTrue(defined.size() > 40);
    }

    /** The report page carries the theme before its own styles, so the roles apply. */
    @Test public void reportPageCarriesTheThemeFirst() {
        StringWriter out = new StringWriter();
        DetectionReportDocumentWriter.appendDetectionReportStart(new PrintWriter(out, true));
        String html = out.toString();
        int theme = html.indexOf("--sp-page:");
        int body = html.indexOf("background-color: var(--sp-page)");
        assertTrue(theme > 0 && body > theme);
        assertFalse("no hardcoded colour remains in the report's own styles",
                withoutComments(html.substring(html.indexOf("}", theme))).matches("(?s).*#[0-9a-fA-F]{6}\\b.*"));
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
