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

import eu.startales.spacepixels.util.BlinkSequence;
import eu.startales.spacepixels.util.RawImageAnnotator;
import eu.startales.spacepixels.util.TrackOverlay;
import io.github.ppissias.jtransient.core.SourceExtractor;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Locale;

/**
 * Shows one frame from its 16-bit data through the stretch tables, drawing only the part on screen (from a reduced
 * copy when zoomed out). The zoom and centre stay when the frame changes. Scroll zooms around the cursor, drag pans
 * and double-click switches between the fitted view and 100 %. Used by Blink, Preview Frame and Manual Transient
 * Inspection; catalogue labels, tracks and detections are drawn on top, so they stay sharp at any zoom.
 */
final class FrameView extends JComponent {

    /** Receives the frame pixel under the cursor, or null when the cursor leaves the frame. */
    interface CursorListener {
        void cursorMoved(Point framePixel);
    }

    private static final Color BACKGROUND = new Color(0x14121f);
    private static final Color MOVING_COLOR = new Color(80, 220, 255);
    private static final Color STREAK_COLOR = new Color(255, 170, 60);
    private static final Color DETECTION_POINT_COLOR = new Color(50, 255, 50);
    private static final Color DETECTION_STREAK_COLOR = new Color(255, 50, 50);
    /** The smallest marker on screen, so detections stay visible when zoomed out. */
    private static final double MIN_MARKER_RADIUS = 5;
    /** Below this many screen pixels per detection on screen, the markers are not drawn. */
    private static final double MIN_SCREEN_AREA_PER_MARKER = 400;
    private static final Color SKY_STAR_COLOR = new Color(170, 160, 255);
    private static final Color SKY_DEEP_SKY_COLOR = new Color(255, 215, 70);
    private static final Color SKY_VARIABLE_COLOR = new Color(255, 90, 210);
    private static final Color SKY_NAMED_STAR_COLOR = new Color(235, 235, 255);
    /** Screen pixels per catalogue mark: when more would be on screen, only the brightest (or largest) are drawn. */
    private static final double SCREEN_AREA_PER_STAR = 2500;
    private static final double SCREEN_AREA_PER_VARIABLE = 2500;
    private static final double SCREEN_AREA_PER_DEEP_SKY = 5000;
    /** Labels are written only while no more marks of the kind than this are drawn. */
    private static final int MAX_LABELLED_STARS = 60;
    private static final int MAX_LABELLED_VARIABLES = 60;
    private static final int MAX_LABELLED_DEEP_SKY = 150;
    /** Long survey names ("Gaia DR3 3131…", "ZTF J0639…") are written only while this few variables are drawn. */
    private static final int MAX_LABELLED_SURVEY_VARIABLES = 15;
    private static final int SHORT_NAME_LENGTH = 12;
    static final double MAX_ZOOM = 16.0;
    static final double MAX_PIXEL_SCALE = 8.0;

    private BlinkSequence.Frame frame;
    /** Stretch table of each channel of {@link #frame}. */
    private byte[][] tables;
    private boolean extremeColour;
    private TrackOverlay overlay;
    private boolean overlayVisible;
    private List<SourceExtractor.DetectedObject> detections;
    private boolean detectionsVisible = true;
    private List<SkyMark> skyMarks;
    private double skyStarLimit;
    private boolean skyStars = true;
    private boolean skyDeepSky = true;
    private boolean skyVariables = true;
    /** The catalogue marks drawn last, for {@link #skyMarkAt}. */
    private List<SkyMark> drawnSkyMarks = new java.util.ArrayList<>();
    private String placeholder = "Loading…";
    private CursorListener cursorListener;

    /** Zoom relative to the fitted view, and the centre as a fraction of the frame width and height. */
    private double zoom = 1.0;
    private double centerX = 0.5;
    private double centerY = 0.5;

    private BufferedImage buffer;
    private int[] bufferPixels;
    private Point dragStart;
    private double dragCenterX;
    private double dragCenterY;

    FrameView() {
        setPreferredSize(new Dimension(800, 600));
        setOpaque(true);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragCenterX = centerX;
                dragCenterY = centerY;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                double scale = scale();
                if (dragStart == null || frame == null || scale <= 0) {
                    return;
                }
                centerX = clamp(dragCenterX - (e.getX() - dragStart.x) / (scale * frame.getWidth()));
                centerY = clamp(dragCenterY - (e.getY() - dragStart.y) / (scale * frame.getHeight()));
                repaint();
                reportCursor(e.getX(), e.getY());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                reportCursor(e.getX(), e.getY());
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (cursorListener != null) {
                    cursorListener.cursorMoved(null);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || frame == null) {
                    return;
                }
                if (zoom > 1.0) {
                    fit();
                } else {
                    zoomAround(e.getX(), e.getY(), 1.0 / fitScale());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (frame != null) {
                    zoomAround(e.getX(), e.getY(), zoom * Math.pow(1.25, -e.getPreciseWheelRotation()));
                    reportCursor(e.getX(), e.getY());
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    void setCursorListener(CursorListener listener) {
        this.cursorListener = listener;
    }

    /** Shows this frame with these stretch tables; {@code extremeColour} draws a colour frame as the brightest channel. */
    void showFrame(BlinkSequence.Frame frame, byte[][] tables, boolean extremeColour) {
        this.frame = frame;
        this.tables = tables;
        this.extremeColour = extremeColour;
        repaint();
    }

    void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    /** Forgets the frame and the drawing buffer, so the frame data can be freed. */
    void clear() {
        frame = null;
        tables = null;
        buffer = null;
        bufferPixels = null;
        overlay = null;
        detections = null;
        skyMarks = null;
        drawnSkyMarks = new java.util.ArrayList<>();
        placeholder = "Loading…";
        fit();
    }

    /**
     * The catalogue objects on the frame shown, or null for none. {@code starMagnitudeLimit} is the depth of the
     * catalogue, which sets the size of the star circles.
     */
    void setSkyMarks(List<SkyMark> marks, double starMagnitudeLimit) {
        this.skyMarks = marks;
        this.skyStarLimit = starMagnitudeLimit;
        if (marks == null) {
            drawnSkyMarks = new java.util.ArrayList<>();
        }
        repaint();
    }

    /** Which kinds of catalogue objects are drawn. */
    void setSkyLayers(boolean stars, boolean deepSky, boolean variables) {
        this.skyStars = stars;
        this.skyDeepSky = deepSky;
        this.skyVariables = variables;
        repaint();
    }

    /**
     * The catalogue object at this frame pixel among those drawn: a variable or star near it (the nearest), else
     * the smallest deep-sky object around it. Null when there is none.
     */
    SkyMark skyMarkAt(Point pixel) {
        double scale = scale();
        if (pixel == null || scale <= 0 || drawnSkyMarks.isEmpty()) {
            return null;
        }
        double reach = Math.max(3, 8 / scale);
        SkyMark best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SkyMark.Kind kind : new SkyMark.Kind[]{SkyMark.Kind.VARIABLE, SkyMark.Kind.NAMED_STAR, SkyMark.Kind.STAR}) {
            for (SkyMark mark : drawnSkyMarks) {
                double distance = Math.hypot(mark.x - pixel.x, mark.y - pixel.y);
                if (mark.kind == kind && distance <= reach && distance < bestDistance) {
                    best = mark;
                    bestDistance = distance;
                }
            }
            if (best != null) {
                return best;
            }
        }
        double smallest = Double.MAX_VALUE;
        for (SkyMark mark : drawnSkyMarks) {
            if (mark.kind != SkyMark.Kind.DEEP_SKY) {
                continue;
            }
            double dx = pixel.x - mark.x;
            double dy = pixel.y - mark.y;
            boolean inside;
            if (mark.semiMajor * scale >= 4) {
                double u = dx * Math.cos(mark.angle) + dy * Math.sin(mark.angle);
                double v = -dx * Math.sin(mark.angle) + dy * Math.cos(mark.angle);
                double minor = Math.max(mark.semiMinor, 1e-6);
                inside = (u * u) / (mark.semiMajor * mark.semiMajor) + (v * v) / (minor * minor) <= 1;
            } else {
                inside = Math.hypot(dx, dy) <= reach;
            }
            if (inside && mark.semiMajor < smallest) {
                best = mark;
                smallest = mark.semiMajor;
            }
        }
        return best;
    }

    /** The detections of the frame shown, or null. */
    void setDetections(List<SourceExtractor.DetectedObject> detections) {
        this.detections = detections;
        repaint();
    }

    void setDetectionsVisible(boolean visible) {
        this.detectionsVisible = visible;
        repaint();
    }

    void setOverlay(TrackOverlay overlay) {
        this.overlay = overlay;
        repaint();
    }

    void setOverlayVisible(boolean visible) {
        this.overlayVisible = visible;
        repaint();
    }

    void fit() {
        zoom = 1.0;
        centerX = 0.5;
        centerY = 0.5;
        repaint();
    }

    /** Zooms to this many screen pixels per frame pixel (1 is 100 %), keeping the centre. */
    void zoomToScale(double pixelScale) {
        double fit = fitScale();
        if (fit <= 0) {
            return;
        }
        zoom = Math.max(1.0, Math.min(maxZoom(), pixelScale / fit));
        repaint();
    }

    /** The zoom as a percentage (screen pixels per frame pixel). */
    double zoomPercent() {
        return scale() * 100;
    }

    private void zoomAround(int screenX, int screenY, double newZoom) {
        double before = scale();
        double pointX = frameX(screenX, before);
        double pointY = frameY(screenY, before);
        zoom = Math.max(1.0, Math.min(maxZoom(), newZoom));
        double after = scale();
        centerX = clamp(pointX - (screenX - getWidth() / 2.0) / (after * frame.getWidth()));
        centerY = clamp(pointY - (screenY - getHeight() / 2.0) / (after * frame.getHeight()));
        repaint();
    }

    private double maxZoom() {
        double fit = fitScale();
        return fit <= 0 ? MAX_ZOOM : Math.max(MAX_ZOOM, MAX_PIXEL_SCALE / fit);
    }

    private double fitScale() {
        if (frame == null || getWidth() <= 0 || getHeight() <= 0) {
            return 0;
        }
        return Math.min(getWidth() / (double) frame.getWidth(), getHeight() / (double) frame.getHeight());
    }

    /** Screen pixels per frame pixel. */
    private double scale() {
        return fitScale() * zoom;
    }

    /** Fraction of the frame width under this screen column. */
    private double frameX(int screenX, double scale) {
        return (screenX - left(scale)) / (scale * frame.getWidth());
    }

    private double frameY(int screenY, double scale) {
        return (screenY - top(scale)) / (scale * frame.getHeight());
    }

    /** Screen position of the frame's left edge: centred when it fits, else set by the centre. */
    private double left(double scale) {
        double drawWidth = frame.getWidth() * scale;
        return drawWidth <= getWidth() ? (getWidth() - drawWidth) / 2 : getWidth() / 2.0 - centerX * drawWidth;
    }

    private double top(double scale) {
        double drawHeight = frame.getHeight() * scale;
        return drawHeight <= getHeight() ? (getHeight() - drawHeight) / 2 : getHeight() / 2.0 - centerY * drawHeight;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void reportCursor(int screenX, int screenY) {
        if (cursorListener == null) {
            return;
        }
        double scale = scale();
        if (frame == null || scale <= 0) {
            cursorListener.cursorMoved(null);
            return;
        }
        int x = (int) Math.floor((screenX - left(scale)) / scale);
        int y = (int) Math.floor((screenY - top(scale)) / scale);
        boolean inside = x >= 0 && y >= 0 && x < frame.getWidth() && y < frame.getHeight();
        cursorListener.cursorMoved(inside ? new Point(x, y) : null);
    }

    /** The reduced copy to draw from: the coarsest one that still has at least one pixel per device pixel. */
    static int levelFor(double devicePixelsPerFramePixel) {
        if (devicePixelsPerFramePixel >= 1.0 || devicePixelsPerFramePixel <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.log(1.0 / devicePixelsPerFramePixel) / Math.log(2.0));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (frame == null || tables == null) {
                g2.setColor(UIManager.getColor("Label.disabledForeground"));
                FontMetrics metrics = g2.getFontMetrics();
                g2.drawString(placeholder, (getWidth() - metrics.stringWidth(placeholder)) / 2, getHeight() / 2);
                return;
            }
            drawFrame(g2);
            if (skyMarks != null) {
                drawSkyMarks(g2);
            }
            if (detectionsVisible && detections != null) {
                drawDetections(g2);
            }
            if (overlayVisible && overlay != null) {
                drawOverlay(g2);
            }
            if (zoom > 1.0) {
                String label = String.format(Locale.US, "%.0f %%", zoomPercent());
                g2.setColor(new Color(10, 12, 20, 190));
                g2.fillRect(6, 6, g2.getFontMetrics().stringWidth(label) + 10, 18);
                g2.setColor(Color.WHITE);
                g2.drawString(label, 11, 19);
            }
        } finally {
            g2.dispose();
        }
    }

    /** Fills a device-resolution buffer from the frame data and draws it over the whole component. */
    private void drawFrame(Graphics2D g2) {
        double deviceScale = Math.max(1.0, g2.getTransform().getScaleX());
        int deviceWidth = (int) Math.ceil(getWidth() * deviceScale);
        int deviceHeight = (int) Math.ceil(getHeight() * deviceScale);
        if (deviceWidth <= 0 || deviceHeight <= 0) {
            return;
        }
        if (buffer == null || buffer.getWidth() != deviceWidth || buffer.getHeight() != deviceHeight) {
            buffer = new BufferedImage(deviceWidth, deviceHeight, BufferedImage.TYPE_INT_RGB);
            bufferPixels = ((DataBufferInt) buffer.getRaster().getDataBuffer()).getData();
        }
        double scale = scale();
        double devicePerFrame = scale * deviceScale;
        BlinkSequence.Level level = frame.getLevel(levelFor(devicePerFrame));
        double framePerLevelX = frame.getWidth() / (double) level.width;
        double framePerLevelY = frame.getHeight() / (double) level.height;
        double left = left(scale) * deviceScale;
        double top = top(scale) * deviceScale;

        int[] columns = new int[deviceWidth];
        for (int x = 0; x < deviceWidth; x++) {
            double frameX = (x + 0.5 - left) / devicePerFrame;
            columns[x] = frameX < 0 || frameX >= frame.getWidth() ? -1 : Math.min(level.width - 1, (int) (frameX / framePerLevelX));
        }
        int background = BACKGROUND.getRGB() & 0xFFFFFF;
        int channels = level.channels.length;
        for (int y = 0; y < deviceHeight; y++) {
            int rowStart = y * deviceWidth;
            double frameY = (y + 0.5 - top) / devicePerFrame;
            if (frameY < 0 || frameY >= frame.getHeight()) {
                java.util.Arrays.fill(bufferPixels, rowStart, rowStart + deviceWidth, background);
                continue;
            }
            int levelRow = Math.min(level.height - 1, (int) (frameY / framePerLevelY)) * level.width;
            if (channels == 1) {
                short[] data = level.channels[0];
                byte[] table = tables[0];
                for (int x = 0; x < deviceWidth; x++) {
                    int column = columns[x];
                    if (column < 0) {
                        bufferPixels[rowStart + x] = background;
                    } else {
                        int grey = table[data[levelRow + column] - Short.MIN_VALUE] & 0xFF;
                        bufferPixels[rowStart + x] = (grey << 16) | (grey << 8) | grey;
                    }
                }
            } else {
                for (int x = 0; x < deviceWidth; x++) {
                    int column = columns[x];
                    if (column < 0) {
                        bufferPixels[rowStart + x] = background;
                        continue;
                    }
                    int index = levelRow + column;
                    int red = tables[0][level.channels[0][index] - Short.MIN_VALUE] & 0xFF;
                    int green = tables[1][level.channels[1][index] - Short.MIN_VALUE] & 0xFF;
                    int blue = tables[2][level.channels[2][index] - Short.MIN_VALUE] & 0xFF;
                    if (extremeColour) {
                        red = green = blue = Math.max(red, Math.max(green, blue));
                    }
                    bufferPixels[rowStart + x] = (red << 16) | (green << 8) | blue;
                }
            }
        }
        g2.drawImage(buffer, 0, 0, getWidth(), getHeight(), null);
    }

    /**
     * The catalogue objects on screen: deep-sky objects as yellow ellipses (or crosses when they have no size),
     * variable stars as magenta diamonds and stars as blue circles, larger for brighter stars. When more would be on
     * screen than can be told apart, only the brightest (or largest) are drawn and a note says so. Labels are
     * written while few enough marks are drawn.
     */
    private void drawSkyMarks(Graphics2D g2) {
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        double area = (double) getWidth() * getHeight();
        List<SkyMark> stars = new java.util.ArrayList<>();
        List<SkyMark> variables = new java.util.ArrayList<>();
        List<SkyMark> deepSky = new java.util.ArrayList<>();
        List<SkyMark> namedStars = new java.util.ArrayList<>();
        for (SkyMark mark : skyMarks) {
            boolean shown = mark.kind == SkyMark.Kind.STAR || mark.kind == SkyMark.Kind.NAMED_STAR ? skyStars
                    : mark.kind == SkyMark.Kind.VARIABLE ? skyVariables : skyDeepSky;
            if (!shown) {
                continue;
            }
            if (mark.kind == SkyMark.Kind.NAMED_STAR) {
                namedStars.add(mark);
                continue;
            }
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            double reach = Math.max(16, mark.semiMajor * scale);
            if (x + reach < 0 || y + reach < 0 || x - reach > getWidth() || y - reach > getHeight()) {
                continue;
            }
            (mark.kind == SkyMark.Kind.STAR ? stars : mark.kind == SkyMark.Kind.VARIABLE ? variables : deepSky).add(mark);
        }
        java.util.Comparator<SkyMark> brightestFirst = java.util.Comparator.comparingDouble(
                mark -> Double.isNaN(mark.magnitude) ? Double.MAX_VALUE : mark.magnitude);
        int starTotal = stars.size();
        int variableTotal = variables.size();
        int deepSkyTotal = deepSky.size();
        stars = brightest(stars, (int) (area / SCREEN_AREA_PER_STAR), brightestFirst);
        variables = brightest(variables, (int) (area / SCREEN_AREA_PER_VARIABLE), brightestFirst);
        deepSky = brightest(deepSky, (int) (area / SCREEN_AREA_PER_DEEP_SKY),
                java.util.Comparator.comparingDouble((SkyMark mark) -> -mark.semiMajor));

        List<SkyMark> drawn = new java.util.ArrayList<>(stars.size() + variables.size() + deepSky.size());
        // Marks and labels stay on the frame.
        java.awt.Shape screenClip = g2.getClip();
        g2.clip(new java.awt.geom.Rectangle2D.Double(left, top, frame.getWidth() * scale, frame.getHeight() * scale));
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // Circles grow with the zoom, more slowly than the stars, so they stay around them.
        double circleZoom = Math.max(1, Math.pow(scale, 0.75));
        g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
        g2.setStroke(new BasicStroke(1.2f));
        boolean labelStars = stars.size() <= MAX_LABELLED_STARS;
        for (SkyMark mark : stars) {
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            double radius = Math.max(3, Math.min(14, 3 + (skyStarLimit - mark.magnitude) * 1.1)) * circleZoom;
            g2.setColor(withAlpha(SKY_STAR_COLOR, 190));
            g2.draw(new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius));
            if (labelStars) {
                drawLabel(g2, mark.label, x + radius + 2, y + radius + 9, SKY_STAR_COLOR);
            }
            drawn.add(mark);
        }
        boolean labelVariables = variables.size() <= MAX_LABELLED_VARIABLES;
        boolean labelSurveyNames = variables.size() <= MAX_LABELLED_SURVEY_VARIABLES;
        for (SkyMark mark : variables) {
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            double size = 7;
            Path2D.Double diamond = new Path2D.Double();
            diamond.moveTo(x, y - size);
            diamond.lineTo(x + size, y);
            diamond.lineTo(x, y + size);
            diamond.lineTo(x - size, y);
            diamond.closePath();
            g2.setColor(SKY_VARIABLE_COLOR);
            g2.draw(diamond);
            if (labelVariables && (labelSurveyNames || mark.label.length() <= SHORT_NAME_LENGTH)) {
                drawLabel(g2, mark.label, x + size + 3, y - 3, SKY_VARIABLE_COLOR);
            }
            drawn.add(mark);
        }
        boolean labelDeepSky = deepSky.size() <= MAX_LABELLED_DEEP_SKY;
        for (SkyMark mark : deepSky) {
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            g2.setColor(SKY_DEEP_SKY_COLOR);
            double labelX;
            double labelY;
            if (mark.semiMajor * scale >= 4) {
                java.awt.geom.AffineTransform saved = g2.getTransform();
                g2.rotate(mark.angle, x, y);
                double a = mark.semiMajor * scale;
                double b = Math.max(2, mark.semiMinor * scale);
                g2.draw(new Ellipse2D.Double(x - a, y - b, 2 * a, 2 * b));
                g2.setTransform(saved);
                labelX = x + Math.min(a, 200) * 0.7 + 4;
                labelY = y - Math.min(a, 200) * 0.7 - 2;
            } else {
                double arm = 6;
                g2.draw(new java.awt.geom.Line2D.Double(x - arm, y, x - 2, y));
                g2.draw(new java.awt.geom.Line2D.Double(x + 2, y, x + arm, y));
                g2.draw(new java.awt.geom.Line2D.Double(x, y - arm, x, y - 2));
                g2.draw(new java.awt.geom.Line2D.Double(x, y + 2, x, y + arm));
                labelX = x + arm + 3;
                labelY = y - arm;
            }
            if (labelDeepSky) {
                drawLabel(g2, mark.label, labelX, labelY, SKY_DEEP_SKY_COLOR);
            }
            drawn.add(mark);
        }
        // The few bright stars with a proper name are always named.
        for (SkyMark mark : namedStars) {
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            double radius = 16 * circleZoom;
            g2.setColor(SKY_NAMED_STAR_COLOR);
            g2.draw(new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
            drawLabel(g2, mark.label, x + radius + 4, y - radius / 2, SKY_NAMED_STAR_COLOR);
            g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 11f));
            drawn.add(mark);
        }
        g2.setClip(screenClip);
        drawnSkyMarks = drawn;

        List<String> clipped = new java.util.ArrayList<>();
        if (stars.size() < starTotal) {
            clipped.add(String.format(Locale.US, "the brightest %,d of %,d stars", stars.size(), starTotal));
        }
        if (variables.size() < variableTotal) {
            clipped.add(String.format(Locale.US, "the brightest %,d of %,d variables", variables.size(), variableTotal));
        }
        if (deepSky.size() < deepSkyTotal) {
            clipped.add(String.format(Locale.US, "the largest %,d of %,d deep-sky objects", deepSky.size(), deepSkyTotal));
        }
        if (!clipped.isEmpty()) {
            String note = "Showing " + String.join(", ", clipped) + " on screen · zoom in for more";
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            int noteY = getHeight() - 26;
            g2.setColor(new Color(10, 12, 20, 190));
            g2.fillRect(6, noteY, g2.getFontMetrics().stringWidth(note) + 10, 18);
            g2.setColor(SKY_STAR_COLOR);
            g2.drawString(note, 11, noteY + 13);
        }
    }

    private static List<SkyMark> brightest(List<SkyMark> marks, int limit, java.util.Comparator<SkyMark> order) {
        if (marks.size() <= limit) {
            return marks;
        }
        marks.sort(order);
        return new java.util.ArrayList<>(marks.subList(0, Math.max(0, limit)));
    }

    /** Text with a dark edge, readable on bright and dark sky. */
    private static void drawLabel(Graphics2D g2, String text, double x, double y, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float textX = (float) x;
        float textY = (float) y;
        g2.setColor(new Color(0, 0, 0, 170));
        g2.drawString(text, textX + 1, textY + 1);
        g2.drawString(text, textX - 1, textY + 1);
        g2.setColor(color);
        g2.drawString(text, textX, textY);
    }

    /**
     * Each track as a thin line through all its positions, with a ring and its label at the position in the shown
     * frame. Tracks that are not in this frame are drawn fainter.
     */
    private void drawOverlay(Graphics2D g2) {
        String fileName = new java.io.File(frame.getInfo().getFilePath()).getName();
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
        double ring = Math.max(9, 4 * scale);
        for (TrackOverlay.Track track : overlay.getTracks()) {
            Color color = track.streak ? STREAK_COLOR : MOVING_COLOR;
            List<TrackOverlay.Point> here = track.pointsIn(fileName);
            Path2D.Double path = new Path2D.Double();
            boolean first = true;
            for (TrackOverlay.Point point : track.points) {
                double x = left + (point.x + 0.5) * scale;
                double y = top + (point.y + 0.5) * scale;
                if (first) {
                    path.moveTo(x, y);
                    first = false;
                } else {
                    path.lineTo(x, y);
                }
            }
            g2.setStroke(new BasicStroke(1f));
            g2.setColor(withAlpha(color, here.isEmpty() ? 70 : 120));
            g2.draw(path);
            if (here.isEmpty()) {
                TrackOverlay.Point start = track.points.get(0);
                g2.setColor(withAlpha(color, 110));
                g2.drawString(track.label, (float) (left + (start.x + 0.5) * scale + 6), (float) (top + (start.y + 0.5) * scale - 6));
                continue;
            }
            g2.setStroke(new BasicStroke(1.6f));
            g2.setColor(color);
            for (TrackOverlay.Point point : here) {
                double x = left + (point.x + 0.5) * scale;
                double y = top + (point.y + 0.5) * scale;
                g2.draw(new Ellipse2D.Double(x - ring, y - ring, 2 * ring, 2 * ring));
            }
            TrackOverlay.Point labelled = here.get(0);
            g2.drawString(track.label, (float) (left + (labelled.x + 0.5) * scale + ring + 3),
                    (float) (top + (labelled.y + 0.5) * scale - ring));
        }
    }

    /**
     * Green boxes around point sources and red lines along streaks, sized in frame pixels by the marker settings of
     * {@link RawImageAnnotator} (the visualization preferences). From 100 % zoom the exact pixels of each detection
     * are tinted too. Only detections on screen are drawn.
     */
    private void drawDetections(Graphics2D g2) {
        double streakLineScale = RawImageAnnotator.streakLineScaleFactor;
        double streakBoxRadius = RawImageAnnotator.streakCentroidBoxRadius;
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        boolean footprints = scale >= 1.0;
        List<SourceExtractor.DetectedObject> visible = new java.util.ArrayList<>();
        for (SourceExtractor.DetectedObject object : detections) {
            if (object.isNoise) {
                continue;
            }
            double reach = object.isStreak
                    ? Math.max(streakBoxRadius, object.elongation * streakLineScale)
                    : pointBoxRadius(object);
            double x = left + (object.x + 0.5) * scale;
            double y = top + (object.y + 0.5) * scale;
            double screenReach = Math.max(MIN_MARKER_RADIUS, reach * scale) + 2;
            if (x + screenReach >= 0 && y + screenReach >= 0 && x - screenReach <= getWidth() && y - screenReach <= getHeight()) {
                visible.add(object);
            }
        }
        // Too many markers to tell apart would only cover the frame: say so instead, until zoomed in.
        if (visible.size() > (double) getWidth() * getHeight() / MIN_SCREEN_AREA_PER_MARKER) {
            String note = String.format(Locale.US, "%,d detections on screen · zoom in to see them", visible.size());
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 12f));
            g2.setColor(new Color(10, 12, 20, 190));
            g2.fillRect(6, 30, g2.getFontMetrics().stringWidth(note) + 10, 18);
            g2.setColor(DETECTION_POINT_COLOR);
            g2.drawString(note, 11, 43);
            return;
        }
        for (SourceExtractor.DetectedObject object : visible) {
            double x = left + (object.x + 0.5) * scale;
            double y = top + (object.y + 0.5) * scale;
            Color color = object.isStreak ? DETECTION_STREAK_COLOR : DETECTION_POINT_COLOR;
            if (footprints && object.rawPixels != null) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g2.setColor(withAlpha(color, 110));
                for (SourceExtractor.Pixel pixel : object.rawPixels) {
                    int x0 = (int) Math.floor(left + pixel.x * scale);
                    int y0 = (int) Math.floor(top + pixel.y * scale);
                    g2.fillRect(x0, y0, (int) Math.floor(left + (pixel.x + 1) * scale) - x0,
                            (int) Math.floor(top + (pixel.y + 1) * scale) - y0);
                }
            }
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(color);
            if (object.isStreak) {
                double length = object.elongation * streakLineScale * scale;
                double dx = Math.cos(object.angle) * length;
                double dy = Math.sin(object.angle) * length;
                g2.draw(new java.awt.geom.Line2D.Double(x - dx, y - dy, x + dx, y + dy));
                double box = Math.max(3, streakBoxRadius * scale);
                g2.draw(new java.awt.geom.Rectangle2D.Double(x - box, y - box, 2 * box, 2 * box));
            } else {
                double box = Math.max(MIN_MARKER_RADIUS, pointBoxRadius(object) * scale);
                g2.draw(new java.awt.geom.Rectangle2D.Double(x - box, y - box, 2 * box, 2 * box));
            }
        }
    }

    /** The box clears the object: its rough radius plus a margin, and is at least the minimum box size. */
    private static double pointBoxRadius(SourceExtractor.DetectedObject object) {
        int minimum = RawImageAnnotator.pointSourceMinBoxRadius;
        if (object.pixelArea <= 0) {
            return minimum;
        }
        return Math.max(minimum, Math.round(Math.sqrt(object.pixelArea / Math.PI)) + RawImageAnnotator.dynamicBoxPadding);
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
