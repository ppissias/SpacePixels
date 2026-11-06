/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.FindingOverlay;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.FindingCategory;
import org.junit.Test;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

import static org.junit.Assert.*;

/** Checks the shared layer choices, the legend rows that change them and the Report findings counts. */
public class ViewerLayerStateTest {

    /** Choices are remembered, groups hide their children, and listeners hear each change once. */
    @Test public void choicesPersistAndGroupsHideTheirLayers() throws Exception {
        Preferences storage = Preferences.userRoot().node("spacepixels-test/" + UUID.randomUUID());
        try {
            ViewerLayerState state = new ViewerLayerState(storage);
            assertTrue(state.isVisible(ViewerLayer.TRACKS));
            assertTrue(state.isLabelled(ViewerLayer.VARIABLE_STARS));
            assertFalse("reference labels are off by default", state.isLabelled(ViewerLayer.SLOW_MOVERS));
            assertFalse("catalogue annotations start off", state.isVisible(ViewerLayer.CATALOGUE));

            AtomicInteger changes = new AtomicInteger();
            Runnable listener = changes::incrementAndGet;
            state.addListener(listener);
            state.setVisible(ViewerLayer.OTHER_FINDINGS, false);
            state.setVisible(ViewerLayer.OTHER_FINDINGS, false);
            assertEquals("an unchanged choice is not announced", 1, changes.get());
            ViewerLayerState.Snapshot snapshot = state.snapshot();
            assertFalse("a hidden group hides its layers", snapshot.shows(ViewerLayer.SLOW_MOVERS));
            assertTrue("each layer keeps its own choice", state.isVisible(ViewerLayer.SLOW_MOVERS));
            state.setLabelled(ViewerLayer.TRACKS, false);
            assertEquals(2, changes.get());
            assertTrue("a snapshot does not follow later changes", snapshot.labels(ViewerLayer.TRACKS));

            ViewerLayerState reopened = new ViewerLayerState(storage);
            assertFalse(reopened.isVisible(ViewerLayer.OTHER_FINDINGS));
            assertFalse(reopened.isLabelled(ViewerLayer.TRACKS));
            state.removeListener(listener);
            state.setVisible(ViewerLayer.OTHER_FINDINGS, true);
            assertEquals(2, changes.get());
        } finally {
            storage.removeNode();
        }
    }

    /** Every report category has a layer; the categories outside the tracks and candidates are in Other findings. */
    @Test public void everyCategoryHasALayer() {
        for (FindingCategory category : FindingCategory.values()) {
            assertNotNull(category.name(), ViewerLayer.of(category));
        }
        assertEquals(ViewerLayer.TRACKS, ViewerLayer.of(FindingCategory.STREAK_TRACK));
        assertEquals(ViewerLayer.OTHER_FINDINGS, ViewerLayer.of(FindingCategory.ANOMALY).parent);
        assertEquals(ViewerLayer.CATALOGUE, ViewerLayer.of(SkyMark.Kind.NAMED_STAR).parent);
    }

    /** An older report saved one reference position per track: the Tracks row offers them, not Other findings. */
    @Test public void olderReportTracksAppearUnderTracks() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("older-report");
        try {
            java.nio.file.Files.writeString(folder.resolve("report-data.json"), "{\"schemaVersion\":1,\"frameCount\":2,\"findings\":["
                    + "{\"id\":\"T1\",\"x\":10,\"y\":10,\"frames\":[1,2]},{\"id\":\"DS1\",\"x\":20,\"y\":20,\"frames\":[]}]}");
            java.nio.file.Files.writeString(folder.resolve("detection_report.html"), "older report");
            FitsFileInformation[] files = {
                    new FitsFileInformation(folder.resolve("a.fit").toString(), "a.fit", true, 100, 80),
                    new FitsFileInformation(folder.resolve("b.fit").toString(), "b.fit", true, 100, 80)};
            var overlays = eu.startales.spacepixels.detection.SavedReportOverlays.read(
                    folder.resolve("detection_report.html").toFile(), files).overlays();
            SwingUtilities.invokeAndWait(() -> {
                FindingsSection section = new FindingsSection(new ViewerLayerState(null));
                section.setTracks(overlays.tracks(), overlays.tracks().coversAnyOf(java.util.Set.of("a.fit", "b.fit")));
                section.setFindings(overlays.findings(), overlays.findings().coversAnyOf(files));
                assertTrue("reference-only tracks can be shown", section.isAvailable(ViewerLayer.TRACKS));
                assertTrue(section.isAvailable(ViewerLayer.SLOW_MOVERS));
                assertTrue(section.isAvailable(ViewerLayer.OTHER_FINDINGS));
            });
        } finally {
            try (var paths = java.nio.file.Files.walk(folder)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The eye of a row changes the shared state; an unavailable row says why and cannot be switched. */
    @Test public void rowsSwitchTheStateAndExplainWhyTheyAreUnavailable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewerLayerState state = new ViewerLayerState(null);
            FindingsSection section = new FindingsSection(state);
            LayerRow tracks = section.row(ViewerLayer.TRACKS);
            assertFalse(section.isAvailable(ViewerLayer.TRACKS));
            assertEquals("Show tracks", tracks.eye().getAccessibleContext().getAccessibleName());

            FitsFileInformation frame = new FitsFileInformation("frame_01.fit", "frame_01.fit", true, 200, 160);
            FindingOverlay overlay = FindingOverlay.from(null, new FitsFileInformation[]{frame});
            section.setFindings(overlay, false);
            section.setTracks(null, true);
            assertTrue(section.isAvailable(ViewerLayer.TRACKS));
            tracks.eye().doClick();
            assertFalse(state.isVisible(ViewerLayer.TRACKS));
            assertFalse(section.isAvailable(ViewerLayer.SLOW_MOVERS));

            BufferedImage image = new BufferedImage(400, 600, BufferedImage.TYPE_INT_ARGB);
            JComponent panel = section.section();
            panel.setSize(panel.getPreferredSize());
            panel.doLayout();
            panel.paint(image.createGraphics());
            for (ViewerLayer layer : List.of(ViewerLayer.values())) {
                OverlayStyle.Swatch swatch = OverlayStyle.swatch(layer);
                if (swatch != null) swatch.paintIcon(panel, image.createGraphics(), 0, 0);
            }
        });
    }
}
