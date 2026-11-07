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
import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.FindingCategory;
import io.github.ppissias.jtransient.engine.PipelineResult;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Track;
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
            assertTrue(state.isVisible(ViewerLayer.MOVING_TRACKS));
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
            state.setLabelled(ViewerLayer.MOVING_TRACKS, false);
            assertEquals(2, changes.get());
            assertTrue("a snapshot does not follow later changes", snapshot.labels(ViewerLayer.MOVING_TRACKS));

            ViewerLayerState reopened = new ViewerLayerState(storage);
            assertFalse(reopened.isVisible(ViewerLayer.OTHER_FINDINGS));
            assertFalse(reopened.isLabelled(ViewerLayer.MOVING_TRACKS));
            state.removeListener(listener);
            state.setVisible(ViewerLayer.OTHER_FINDINGS, true);
            assertEquals(2, changes.get());
        } finally {
            storage.removeNode();
        }
    }

    /** A group hides everything under it, also through another group, and names the outermost group to switch on. */
    @Test public void groupsWorkThroughEveryLevel() {
        ViewerLayerState state = new ViewerLayerState(null);
        assertTrue(state.snapshot().shows(ViewerLayer.SLOW_MOVERS));
        assertNull(state.offGroup(ViewerLayer.SLOW_MOVERS));

        state.setVisible(ViewerLayer.OTHER_FINDINGS, false);
        assertFalse(state.snapshot().shows(ViewerLayer.SLOW_MOVERS));
        assertTrue("the other rows are not in that group", state.snapshot().shows(ViewerLayer.MOVING_TRACKS));
        assertEquals(ViewerLayer.OTHER_FINDINGS, state.offGroup(ViewerLayer.SLOW_MOVERS));

        state.setVisible(ViewerLayer.REPORT_FINDINGS, false);
        assertFalse(state.snapshot().shows(ViewerLayer.MOVING_TRACKS));
        assertEquals("switch on the outermost first", ViewerLayer.REPORT_FINDINGS, state.offGroup(ViewerLayer.SLOW_MOVERS));
        assertEquals(ViewerLayer.REPORT_FINDINGS, state.offGroup(ViewerLayer.OTHER_FINDINGS));
        assertNull("a group is not its own group", state.offGroup(ViewerLayer.REPORT_FINDINGS));
    }

    /** Every report category has a layer; the categories outside the tracks and candidates are in Other findings. */
    @Test public void everyCategoryHasALayer() {
        for (FindingCategory category : FindingCategory.values()) {
            assertNotNull(category.name(), ViewerLayer.of(category));
        }
        assertEquals(ViewerLayer.MOVING_TRACKS, ViewerLayer.of(FindingCategory.MOVING_TARGET));
        assertEquals(ViewerLayer.STREAK_TRACKS, ViewerLayer.of(FindingCategory.STREAK_TRACK));
        assertEquals(ViewerLayer.REPORT_FINDINGS, ViewerLayer.of(FindingCategory.MOVING_TARGET).parent);
        assertEquals(ViewerLayer.OTHER_FINDINGS, ViewerLayer.of(FindingCategory.ANOMALY).parent);
        assertEquals(ViewerLayer.CATALOGUE, ViewerLayer.of(SkyMark.Kind.NAMED_STAR).parent);
    }

    /** An older report saved one reference position per track: the tracks' own rows offer them, not Other findings. */
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
                assertTrue("reference-only tracks can be shown", section.isAvailable(ViewerLayer.MOVING_TRACKS));
                assertFalse("each kind of track has its own row", section.isAvailable(ViewerLayer.STREAK_TRACKS));
                assertTrue(section.isAvailable(ViewerLayer.SLOW_MOVERS));
                assertTrue(section.isAvailable(ViewerLayer.OTHER_FINDINGS));
            });
        } finally {
            try (var paths = java.nio.file.Files.walk(folder)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** Moving object and streak tracks have separate rows with their own counts and switches. */
    @Test public void movingObjectAndStreakTracksAreSeparate() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewerLayerState state = new ViewerLayerState(null);
            FindingsSection section = new FindingsSection(state);
            section.setTracks(TrackOverlay.from(PipelineResult.builder().tracks(List.of(track(false), track(false), track(true))).build()), true);
            assertEquals("2", section.row(ViewerLayer.MOVING_TRACKS).countText());
            assertEquals("1", section.row(ViewerLayer.STREAK_TRACKS).countText());
            section.row(ViewerLayer.STREAK_TRACKS).eye().doClick();
            assertFalse(state.isVisible(ViewerLayer.STREAK_TRACKS));
            assertTrue(state.isVisible(ViewerLayer.MOVING_TRACKS));

            section.setTracks(TrackOverlay.from(PipelineResult.builder().tracks(List.of(track(false))).build()), true);
            assertTrue(section.isAvailable(ViewerLayer.MOVING_TRACKS));
            assertFalse("a run without streak tracks has nothing to switch", section.isAvailable(ViewerLayer.STREAK_TRACKS));
            assertEquals("none in report", section.row(ViewerLayer.STREAK_TRACKS).countText());
        });
    }

    /** Switching a group off disables every control under it, says why, and leaves each row's own choices alone. */
    @Test public void aGroupSwitchedOffDisablesTheControlsUnderIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewerLayerState state = new ViewerLayerState(null);
            FindingsSection section = new FindingsSection(state);
            assertFalse("no run: the header switch has nothing to switch", headerSwitch(section).isEnabled());
            section.toggleShown();
            assertTrue(state.isVisible(ViewerLayer.REPORT_FINDINGS));

            section.setTracks(TrackOverlay.from(PipelineResult.builder().tracks(List.of(track(false))).build()), true);
            assertTrue(headerSwitch(section).isEnabled());
            LayerRow tracks = section.row(ViewerLayer.MOVING_TRACKS);
            tracks.eye().doClick();
            assertFalse(state.isVisible(ViewerLayer.MOVING_TRACKS));

            headerSwitch(section).doClick();
            assertFalse(state.isVisible(ViewerLayer.REPORT_FINDINGS));
            section.refresh();
            assertFalse(tracks.eye().isEnabled());
            assertFalse(section.isAvailable(ViewerLayer.MOVING_TRACKS));
            assertTrue(tracks.eye().getToolTipText(), tracks.eye().getToolTipText().contains("switch on Report findings first"));
            tracks.eye().doClick();
            assertFalse("a disabled row cannot be switched", state.isVisible(ViewerLayer.MOVING_TRACKS));

            section.toggleShown();
            section.refresh();
            assertTrue(state.isVisible(ViewerLayer.REPORT_FINDINGS));
            assertTrue(tracks.eye().isEnabled());
            assertFalse("the row kept its own choice", tracks.eye().isSelected());
            assertFalse(tracks.eye().getToolTipText(), tracks.eye().getToolTipText().contains("Not available"));
        });
    }

    /** Other findings is a group too: off, its categories cannot be switched. */
    @Test public void otherFindingsDisablesItsCategories() throws Exception {
        java.nio.file.Path folder = java.nio.file.Files.createTempDirectory("other-findings");
        try {
            java.nio.file.Files.writeString(folder.resolve("report-data.json"), "{\"schemaVersion\":1,\"frameCount\":2,\"findings\":["
                    + "{\"id\":\"DS1\",\"x\":20,\"y\":20,\"frames\":[]}]}");
            java.nio.file.Files.writeString(folder.resolve("detection_report.html"), "report");
            FitsFileInformation[] files = {
                    new FitsFileInformation(folder.resolve("a.fit").toString(), "a.fit", true, 100, 80),
                    new FitsFileInformation(folder.resolve("b.fit").toString(), "b.fit", true, 100, 80)};
            var overlays = eu.startales.spacepixels.detection.SavedReportOverlays.read(
                    folder.resolve("detection_report.html").toFile(), files).overlays();
            SwingUtilities.invokeAndWait(() -> {
                ViewerLayerState state = new ViewerLayerState(null);
                FindingsSection section = new FindingsSection(state);
                section.setFindings(overlays.findings(), true);
                assertTrue(section.isAvailable(ViewerLayer.SLOW_MOVERS));
                section.row(ViewerLayer.OTHER_FINDINGS).eye().doClick();
                section.refresh();
                assertFalse(section.isAvailable(ViewerLayer.SLOW_MOVERS));
                String tip = section.row(ViewerLayer.SLOW_MOVERS).eye().getToolTipText();
                assertTrue(tip, tip.contains("switch on Other findings first"));
            });
        } finally {
            try (var paths = java.nio.file.Files.walk(folder)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** The on/off switch in the section's header, by the name screen readers get. */
    private static JCheckBox headerSwitch(FindingsSection section) {
        for (JCheckBox box : checkBoxes(section.section())) {
            if ("Show report findings".equals(box.getAccessibleContext().getAccessibleName())) return box;
        }
        throw new AssertionError("no switch in the header");
    }

    private static List<JCheckBox> checkBoxes(java.awt.Container container) {
        List<JCheckBox> boxes = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof JCheckBox box) boxes.add(box);
            else if (child instanceof java.awt.Container inner) boxes.addAll(checkBoxes(inner));
        }
        return boxes;
    }

    /** A linked track of two positions. */
    private static Track track(boolean streak) {
        Track track = new Track();
        track.isStreakTrack = streak;
        for (int frame = 0; frame < 2; frame++) {
            DetectedObject point = new DetectedObject(20 + 10 * frame, 30, 1000, 9);
            point.sourceFrameIndex = frame;
            point.sourceFilename = "frame_0" + (frame + 1) + ".fit";
            track.addPoint(point);
        }
        return track;
    }

    /** The eye of a row changes the shared state; an unavailable row says why and cannot be switched. */
    @Test public void rowsSwitchTheStateAndExplainWhyTheyAreUnavailable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ViewerLayerState state = new ViewerLayerState(null);
            FindingsSection section = new FindingsSection(state);
            LayerRow tracks = section.row(ViewerLayer.MOVING_TRACKS);
            assertFalse(section.isAvailable(ViewerLayer.MOVING_TRACKS));
            assertEquals("Show moving object tracks", tracks.eye().getAccessibleContext().getAccessibleName());

            FitsFileInformation frame = new FitsFileInformation("frame_01.fit", "frame_01.fit", true, 200, 160);
            FindingOverlay overlay = FindingOverlay.from(null, new FitsFileInformation[]{frame});
            section.setFindings(overlay, false);
            section.setTracks(TrackOverlay.from(PipelineResult.builder().tracks(List.of(track(false))).build()), true);
            assertTrue(section.isAvailable(ViewerLayer.MOVING_TRACKS));
            tracks.eye().doClick();
            assertFalse(state.isVisible(ViewerLayer.MOVING_TRACKS));
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
