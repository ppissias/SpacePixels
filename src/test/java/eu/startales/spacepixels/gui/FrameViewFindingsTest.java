package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.detection.DetectionOverlays;
import eu.startales.spacepixels.detection.FaintMoverOverlay;
import eu.startales.spacepixels.detection.SavedReportOverlays;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.report.FindingCategory;
import io.github.ppissias.jtransient.engine.PipelineResult;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** A finding is faint on the frames it was not measured in and vivid on its own, in every category. */
public class FrameViewFindingsTest {

    private static final int WIDTH = 200, HEIGHT = 160;
    /** Painted at twice the frame's size, so the marks of one frame pixel are easy to find. */
    private static final int SCALE = 2;
    /** What separates the two looks: the channel value of a mark on a black frame. */
    private static final int FAINTEST = 40, FAINT_LIMIT = 140, VIVID = 200;
    /** How far from a colour's direction in RGB a pixel can be and still count as that colour. */
    private static final double HUE_TOLERANCE = 25;

    /** S1 was measured in frame 2 only: faint in frames 1 and 3, vivid in frame 2. */
    @Test public void aSingleFrameFindingIsFaintElsewhereAndVividOnItsFrame() throws Exception {
        Fixture fixture = new Fixture("{\"id\":\"S1\",\"x\":50,\"y\":40,\"frames\":[2],\"positions\":[" + position(2, 50, 40) + "]}");
        try {
            SwingUtilities.invokeAndWait(() -> {
                FrameView view = fixture.view();
                Color color = FindingCategory.SINGLE_STREAK.color();
                for (int frame : new int[]{0, 2}) {
                    fixture.show(view, frame);
                    int faint = peak(view, color, 50, 40);
                    assertTrue("faint on frame " + (frame + 1) + ": " + faint, faint >= FAINTEST && faint <= FAINT_LIMIT);
                }
                fixture.show(view, 1);
                assertTrue("vivid on its own frame", peak(view, color, 50, 40) >= VIVID);
            });
        } finally {
            fixture.delete();
        }
    }

    /** A group with rings on top of each other in other frames looks as faint as a finding with one. */
    @Test public void ringsOnTopOfEachOtherDoNotAddUp() throws Exception {
        Fixture fixture = new Fixture(
                "{\"id\":\"FC1\",\"x\":50,\"y\":40,\"frames\":[2,3,4,5,6],\"positions\":[" + position(2, 50, 40) + "," + position(3, 50, 40) + ","
                        + position(4, 50, 40) + "," + position(5, 50, 40) + "," + position(6, 50, 40) + "]},"
                        + "{\"id\":\"FC2\",\"x\":120,\"y\":90,\"frames\":[2],\"positions\":[" + position(2, 120, 90) + "]}", 6);
        try {
            SwingUtilities.invokeAndWait(() -> {
                FrameView view = fixture.view();
                fixture.show(view, 0);
                Color color = FindingCategory.CAMERA_FIXED.color();
                int one = peak(view, color, 120, 90);
                int five = peak(view, color, 50, 40);
                assertTrue("one ring: " + one, one > 20);
                assertEquals("five rings look like one", one, five, 3);
            });
        } finally {
            fixture.delete();
        }
    }

    /** Rings that nearly coincide on screen collapse into one instead of merging into a thick band. */
    @Test public void ringsNextToEachOtherDoNotMergeIntoABand() throws Exception {
        StringBuilder shifted = new StringBuilder();
        for (int frame = 2; frame <= 6; frame++) {
            shifted.append(frame > 2 ? "," : "").append(position(frame, 50 + (frame - 2) * 0.25, 40));
        }
        Fixture fixture = new Fixture("{\"id\":\"FC1\",\"x\":50,\"y\":40,\"frames\":[2,3,4,5,6],\"positions\":[" + shifted + "]},"
                + "{\"id\":\"FC2\",\"x\":120,\"y\":90,\"frames\":[2],\"positions\":[" + position(2, 120, 90) + "]}", 6);
        try {
            SwingUtilities.invokeAndWait(() -> {
                FrameView view = fixture.view();
                fixture.show(view, 0);
                Color color = FindingCategory.CAMERA_FIXED.color();
                assertEquals("a group of nearly coinciding rings is as thin as one ring",
                        markedPixels(view, color, 120, 90), markedPixels(view, color, 50, 40));
            });
        } finally {
            fixture.delete();
        }
    }

    /** Hovering a faint mark says where the finding was measured; on its own frame it does not need to. */
    @Test public void hoverNamesTheFrameOfAFaintMark() throws Exception {
        Fixture fixture = new Fixture("{\"id\":\"A1\",\"x\":50,\"y\":40,\"frames\":[2],\"positions\":[" + position(2, 50, 40) + "]}");
        try {
            SwingUtilities.invokeAndWait(() -> {
                FrameView view = fixture.view();
                fixture.show(view, 0);
                assertEquals("A1 · Single-frame anomaly · in frame 2 of the session", view.getToolTipText(mouse(view, 50, 40)));
                assertNull(view.getToolTipText(mouse(view, 100, 100)));
                fixture.show(view, 1);
                assertEquals("A1 · Single-frame anomaly", view.getToolTipText(mouse(view, 50, 40)));
                view.setLayers(without(ViewerLayer.REPORT_FINDINGS));
                assertNull("a hidden group has nothing to name", view.getToolTipText(mouse(view, 50, 40)));
            });
        } finally {
            fixture.delete();
        }
    }

    /** A reference belongs to no frame, so it looks the same on all of them. */
    @Test public void aReferenceLooksTheSameOnEveryFrame() throws Exception {
        Fixture fixture = new Fixture("{\"id\":\"DS1\",\"x\":50,\"y\":40,\"frames\":[]}");
        try {
            SwingUtilities.invokeAndWait(() -> {
                FrameView view = fixture.view();
                Color color = FindingCategory.SLOW_MOVER.color();
                fixture.show(view, 0);
                int first = peak(view, color, 50, 40);
                fixture.show(view, 2);
                assertTrue("drawn", first > FAINT_LIMIT);
                assertEquals(first, peak(view, color, 50, 40));
            });
        } finally {
            fixture.delete();
        }
    }

    /** Moving object and streak tracks are drawn by their own layers, and the Report findings switch hides both. */
    @Test public void tracksFollowTheirOwnLayers() throws Exception {
        Fixture fixture = new Fixture(
                "{\"id\":\"T1\",\"x\":50,\"y\":40,\"frames\":[1,2],\"positions\":[" + position(1, 50, 40) + "," + position(2, 60, 40) + "]},"
                        + "{\"id\":\"ST1\",\"x\":120,\"y\":90,\"frames\":[1,2],\"positions\":[" + position(1, 120, 90) + "," + position(2, 130, 90) + "]}");
        try {
            SwingUtilities.invokeAndWait(() -> {
                FrameView view = fixture.view();
                fixture.show(view, 0);
                Color moving = FindingCategory.MOVING_TARGET.color(), streak = FindingCategory.STREAK_TRACK.color();
                assertTrue(peak(view, moving, 50, 40, 12) >= VIVID);
                assertTrue(peak(view, streak, 120, 90, 12) >= VIVID);

                view.setLayers(without(ViewerLayer.STREAK_TRACKS));
                assertTrue(peak(view, moving, 50, 40, 12) >= VIVID);
                assertEquals("streak tracks hidden", 0, peak(view, streak, 120, 90, 12));

                view.setLayers(without(ViewerLayer.MOVING_TRACKS));
                assertEquals("moving object tracks hidden", 0, peak(view, moving, 50, 40, 12));
                assertTrue(peak(view, streak, 120, 90, 12) >= VIVID);

                view.setLayers(without(ViewerLayer.REPORT_FINDINGS));
                assertEquals(0, peak(view, moving, 50, 40, 12));
                assertEquals(0, peak(view, streak, 120, 90, 12));
            });
        } finally {
            fixture.delete();
        }
    }

    /** A fitted path is faint on every frame; only a searched frame has a vivid ring, at its exposure midpoint. */
    @Test public void aFaintMoverIsVividOnlyWhereItsFrameWasSearched() throws Exception {
        FitsFileInformation searched = timed("a.fit", "2026-03-10T02:00:00");
        FitsFileInformation notSearched = timed("b.fit", "2026-03-10T02:10:00");
        FitsFileInformation alsoSearched = timed("c.fit", "2026-03-10T02:20:00");
        var telemetry = new io.github.ppissias.jtransient.telemetry.PipelineTelemetry();
        telemetry.faintMoverSearchTelemetry = new io.github.ppissias.jtransient.telemetry.FaintMoverSearchTelemetry();
        telemetry.faintMoverSearchTelemetry.searchedFrameIndices = List.of(0, 2);
        // Two pixels a minute: x 50 in the first frame, 70 in the second and 90 in the third.
        var candidate = new com.google.gson.Gson().fromJson("{\"x\":50,\"y\":40,\"vx\":2,\"vy\":0,\"referenceTimeMillis\":"
                + searched.getObservationTimestamp() + "}", io.github.ppissias.jtransient.core.FaintMoverCandidate.class);
        FaintMoverOverlay overlay = FaintMoverOverlay.from(PipelineResult.builder().telemetry(telemetry)
                .faintMoverCandidates(List.of(candidate)).build(), new FitsFileInformation[]{searched, notSearched, alsoSearched});
        SwingUtilities.invokeAndWait(() -> {
            FrameView view = new FrameView();
            view.setSize(WIDTH * SCALE, HEIGHT * SCALE);
            view.setFaintMoverOverlay(overlay);
            Color color = FindingCategory.FAINT_MOVER.color();
            view.showFrame(BlinkSequence.fromMono(searched, new short[HEIGHT][WIDTH]), new byte[1][65536], false);
            assertTrue("a ring at this frame's midpoint", peak(view, color, 50, 40, 22) >= VIVID);
            assertTrue("the path further on is faint", peak(view, color, 90, 40, 6) <= FAINT_LIMIT);
            view.showFrame(BlinkSequence.fromMono(notSearched, new short[HEIGHT][WIDTH]), new byte[1][65536], false);
            int path = peak(view, color, 70, 40, 6);
            assertTrue("only the path where its ring would be: " + path, path >= FAINTEST && path <= FAINT_LIMIT);
        });
    }

    private static FitsFileInformation timed(String name, String date) {
        return new FitsFileInformation(name, name, true, WIDTH, HEIGHT, Map.of("DATE-OBS", date));
    }

    private static String position(int frame, double x, double y) {
        return "{\"frameNumber\":" + frame + ",\"x\":" + x + ",\"y\":" + y + "}";
    }

    /** A saved report of these findings and the frames it belongs to. */
    private static final class Fixture {
        private final Path folder;
        private final FitsFileInformation[] files;
        private final DetectionOverlays overlays;

        Fixture(String findings) throws Exception {
            this(findings, 3);
        }

        Fixture(String findings, int frames) throws Exception {
            folder = Files.createTempDirectory("frame-view-findings");
            Files.writeString(folder.resolve("report-data.json"),
                    "{\"schemaVersion\":1,\"frameCount\":" + frames + ",\"findings\":[" + findings + "]}");
            Files.writeString(folder.resolve("detection_report.html"), "report");
            List<FitsFileInformation> list = new ArrayList<>();
            for (int frame = 0; frame < frames; frame++) {
                String name = "frame_" + frame + ".fit";
                list.add(new FitsFileInformation(folder.resolve(name).toString(), name, true, WIDTH, HEIGHT));
            }
            files = list.toArray(new FitsFileInformation[0]);
            overlays = SavedReportOverlays.read(folder.resolve("detection_report.html").toFile(), files).overlays();
        }

        FrameView view() {
            FrameView view = new FrameView();
            view.setSize(WIDTH * SCALE, HEIGHT * SCALE);
            view.setOverlay(overlays.tracks());
            view.setFindingOverlay(overlays.findings());
            return view;
        }

        void show(FrameView view, int frame) {
            view.showFrame(BlinkSequence.fromMono(files[frame], new short[HEIGHT][WIDTH]), new byte[1][65536], false);
        }

        void delete() throws Exception {
            try (var paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    /** How strongly this colour shows around a frame position, on a black frame, by the largest channel of its pixels. */
    private static int peak(FrameView view, Color color, int frameX, int frameY) {
        return peak(view, color, frameX, frameY, 7);
    }

    private static int peak(FrameView view, Color color, int frameX, int frameY, int reach) {
        BufferedImage image = new BufferedImage(WIDTH * SCALE, HEIGHT * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        view.paint(graphics);
        graphics.dispose();
        double length = Math.sqrt(square(color.getRed()) + square(color.getGreen()) + square(color.getBlue()));
        int centreX = frameX * SCALE + 1, centreY = frameY * SCALE + 1, peak = 0;
        for (int y = centreY - reach; y <= centreY + reach; y++) {
            for (int x = centreX - reach; x <= centreX + reach; x++) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;
                double along = (red * color.getRed() + green * color.getGreen() + blue * color.getBlue()) / length;
                double away = Math.sqrt(Math.max(0, square(red) + square(green) + square(blue) - along * along));
                if (along > 0 && away < HUE_TOLERANCE) peak = Math.max(peak, Math.max(red, Math.max(green, blue)));
            }
        }
        return peak;
    }

    /** How many pixels around a frame position show this colour, on a black frame. */
    private static int markedPixels(FrameView view, Color color, int frameX, int frameY) {
        BufferedImage image = new BufferedImage(WIDTH * SCALE, HEIGHT * SCALE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        view.paint(graphics);
        graphics.dispose();
        int centreX = frameX * SCALE + 1, centreY = frameY * SCALE + 1, reach = 7, marked = 0;
        for (int y = centreY - reach; y <= centreY + reach; y++) {
            for (int x = centreX - reach; x <= centreX + reach; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != 0) marked++;
            }
        }
        return marked;
    }

    private static double square(double value) {
        return value * value;
    }

    private static ViewerLayerState.Snapshot without(ViewerLayer... hidden) {
        ViewerLayerState.Snapshot defaults = ViewerLayerState.Snapshot.defaults();
        Set<ViewerLayer> visible = EnumSet.copyOf(defaults.visible());
        for (ViewerLayer layer : hidden) visible.remove(layer);
        return new ViewerLayerState.Snapshot(visible, defaults.labelled());
    }

    private static MouseEvent mouse(FrameView view, int frameX, int frameY) {
        return new MouseEvent(view, MouseEvent.MOUSE_MOVED, 0, 0, frameX * SCALE + 1, frameY * SCALE + 1, 0, false);
    }
}
