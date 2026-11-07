package eu.startales.spacepixels.gui;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The tips: each links to a section the manual really has, and going through them wraps around. */
public class StartupTipsTest {

    /** The anchors GitHub makes from the headings of the manual, which a tip's link relies on. */
    private static Set<String> manualAnchors() throws Exception {
        Set<String> anchors = new HashSet<>();
        for (String line : Files.readAllLines(Path.of("MANUAL.md"))) {
            if (line.matches("#{1,6} .*")) {
                String title = line.replaceFirst("#+ ", "").strip().toLowerCase(Locale.ROOT);
                anchors.add(title.replaceAll("[^\\p{L}\\p{N} _-]", "").replace(' ', '-'));
            }
        }
        return anchors;
    }

    @Test
    public void everyTipLinksToASectionOfTheManual() throws Exception {
        Set<String> anchors = manualAnchors();
        assertTrue(anchors.contains("blink-selected"));
        for (StartupTips.Tip tip : StartupTips.ALL) {
            assertTrue(tip.manualSection(), anchors.contains(tip.manualSection()));
        }
    }

    @Test
    public void theEmptyWindowLinksToTheManualsWorkflow() throws Exception {
        assertTrue(manualAnchors().contains(DropZonePanel.WORKFLOW_SECTION));
    }

    /** A bare ampersand or an unclosed tag would show as text or spoil the rest of the label. */
    @Test
    public void tipsAreWellFormedLabels() {
        Set<String> texts = new HashSet<>();
        for (StartupTips.Tip tip : StartupTips.ALL) {
            assertTrue("Repeated tip: " + tip.html(), texts.add(tip.html()));
            assertFalse(tip.html(), tip.html().matches("(?s).*&(?!amp;).*"));
            assertEquals(tip.html(), tip.html().split("<b>", -1).length, tip.html().split("</b>", -1).length);
        }
        assertTrue(StartupTips.ALL.size() >= 10);
    }

    @Test
    public void goingThroughTheTipsWrapsAround() {
        assertEquals(0, TipsDialog.wrap(0, 5));
        assertEquals(0, TipsDialog.wrap(5, 5));
        assertEquals(4, TipsDialog.wrap(-1, 5));
        assertEquals("A saved position beyond a shorter list stays usable", 2, TipsDialog.wrap(12, 5));
    }
}
