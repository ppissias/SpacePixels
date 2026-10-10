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
import eu.startales.spacepixels.util.TrackOverlay;

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
 * Shows one blink frame from its 16-bit data through the stretch tables, drawing only the part on screen (from a
 * reduced copy when zoomed out). The zoom and centre stay when the frame changes. Scroll zooms around the cursor,
 * drag pans and double-click switches between the fitted view and 100 %.
 */
final class BlinkView extends JComponent {

    /** Receives the frame pixel under the cursor, or null when the cursor leaves the frame. */
    interface CursorListener {
        void cursorMoved(Point framePixel);
    }

    private static final Color BACKGROUND = new Color(0x14121f);
    private static final Color MOVING_COLOR = new Color(80, 220, 255);
    private static final Color STREAK_COLOR = new Color(255, 170, 60);
    static final double MAX_ZOOM = 16.0;
    static final double MAX_PIXEL_SCALE = 8.0;

    private BlinkSequence.Frame frame;
    /** Stretch table of each channel of {@link #frame}. */
    private byte[][] tables;
    private boolean extremeColour;
    private TrackOverlay overlay;
    private boolean overlayVisible;
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

    BlinkView() {
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
        placeholder = "Loading…";
        fit();
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

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
