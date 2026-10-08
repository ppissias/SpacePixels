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

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * An image fitted into the component, with mouse-wheel zoom around the cursor down to single image pixels, drag to
 * pan, and double-click to switch between the fitted view and 100 % (one image pixel per screen pixel). Views that
 * share a {@link ViewState} zoom and pan together.
 *
 * <p>Zoomed out, a large image is drawn from smoothed half-size copies (computed once), which is fast and avoids the
 * shimmer of shrinking a huge image. An optional overlay of the same size is drawn on top; its reduced copies can be
 * supplied so that thin features (for example single masked pixels) stay visible.</p>
 */
final class ZoomableImageView extends JComponent {

    /** Zoom and centre shared by linked views; the centre is a fraction of the image width and height. */
    static final class ViewState {
        double zoom = 1.0;
        double centerX = 0.5;
        double centerY = 0.5;
        private final List<ZoomableImageView> views = new ArrayList<>();

        void reset() {
            zoom = 1.0;
            centerX = 0.5;
            centerY = 0.5;
            repaintAll();
        }

        void repaintAll() {
            for (ZoomableImageView view : views) {
                view.repaint();
            }
        }
    }

    /** Largest zoom relative to the fitted view, for small images. */
    static final double MAX_ZOOM = 16.0;
    /** Screen pixels per image pixel at the deepest zoom, whatever the image size. */
    static final double MAX_PIXEL_SCALE = 8.0;
    /** Reduced copies are made down to this size. */
    private static final int SMALLEST_LEVEL_SIDE = 512;

    private final ViewState state;
    private BufferedImage image;
    /** The image and its half-size copies (level k is reduced by 2^k), built when first needed. */
    private List<BufferedImage> imageLevels = Collections.emptyList();
    private List<BufferedImage> overlayLevels = Collections.emptyList();
    private boolean overlayVisible = true;
    private String placeholder = "No preview";
    private Point dragStart;
    private double dragCenterX;
    private double dragCenterY;

    ZoomableImageView(ViewState state) {
        this.state = state;
        state.views.add(this);
        setPreferredSize(new Dimension(400, 300));
        setToolTipText("Scroll to zoom, drag to pan, double-click to switch between fit and 100 %. Both previews move together.");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragCenterX = state.centerX;
                dragCenterY = state.centerY;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                double scale = scale();
                if (dragStart == null || image == null || scale <= 0) {
                    return;
                }
                state.centerX = clamp(dragCenterX - (e.getX() - dragStart.x) / (scale * image.getWidth()));
                state.centerY = clamp(dragCenterY - (e.getY() - dragStart.y) / (scale * image.getHeight()));
                state.repaintAll();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || image == null) {
                    return;
                }
                if (state.zoom > 1.0) {
                    state.reset();
                } else {
                    zoomAround(e.getX(), e.getY(), 1.0 / fitScale());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (image != null) {
                    zoomAround(e.getX(), e.getY(), state.zoom * Math.pow(1.25, -e.getPreciseWheelRotation()));
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** Sets the zoom, keeping the image point under the screen position in place. */
    private void zoomAround(int screenX, int screenY, double zoom) {
        double before = scale();
        double pointX = imageX(screenX, before);
        double pointY = imageY(screenY, before);
        state.zoom = Math.max(1.0, Math.min(maxZoom(), zoom));
        double after = scale();
        state.centerX = clamp(pointX - (screenX - getWidth() / 2.0) / (after * image.getWidth()));
        state.centerY = clamp(pointY - (screenY - getHeight() / 2.0) / (after * image.getHeight()));
        state.repaintAll();
    }

    /** Deep enough to reach {@link #MAX_PIXEL_SCALE} screen pixels per image pixel, and at least {@link #MAX_ZOOM}. */
    private double maxZoom() {
        double fit = fitScale();
        return fit <= 0 ? MAX_ZOOM : Math.max(MAX_ZOOM, MAX_PIXEL_SCALE / fit);
    }

    /** An image of the same size drawn over the main one, for example a mask with transparent pixels. */
    void setOverlay(BufferedImage overlay) {
        setOverlayLevels(overlay == null ? Collections.emptyList() : Collections.singletonList(overlay));
    }

    /**
     * The overlay and its reduced copies: entry k is reduced by 2^k. Missing levels fall back to the closest finer
     * one.
     */
    void setOverlayLevels(List<BufferedImage> levels) {
        this.overlayLevels = levels;
        repaint();
    }

    void setOverlayVisible(boolean visible) {
        this.overlayVisible = visible;
        repaint();
    }

    boolean isOverlayVisible() {
        return overlayVisible;
    }

    void setImage(BufferedImage image) {
        this.image = image;
        this.imageLevels = image == null ? Collections.emptyList() : Collections.singletonList(image);
        repaint();
    }

    void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    /** Screen pixels per image pixel at zoom 1 (the whole image fitted into the view). */
    private double fitScale() {
        if (image == null || getWidth() <= 0 || getHeight() <= 0) {
            return 0;
        }
        return Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
    }

    /** Screen pixels per image pixel: the fit-to-view scale times the zoom. */
    private double scale() {
        return fitScale() * state.zoom;
    }

    private double imageX(int screenX, double scale) {
        return state.centerX + (screenX - getWidth() / 2.0) / (scale * image.getWidth());
    }

    private double imageY(int screenY, double scale) {
        return state.centerY + (screenY - getHeight() / 2.0) / (scale * image.getHeight());
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    /** The reduction level to draw from: the coarsest copy that still has at least one pixel per screen pixel. */
    static int levelFor(double scale) {
        if (scale >= 1.0 || scale <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.log(1.0 / scale) / Math.log(2.0));
    }

    private BufferedImage imageLevel(int level) {
        if (imageLevels.size() == 1 && image != null && Math.max(image.getWidth(), image.getHeight()) > SMALLEST_LEVEL_SIDE * 2) {
            imageLevels = halvings(image);
        }
        return imageLevels.get(Math.min(level, imageLevels.size() - 1));
    }

    /** Smoothed half-size copies, down to {@link #SMALLEST_LEVEL_SIDE}. */
    private static List<BufferedImage> halvings(BufferedImage source) {
        List<BufferedImage> levels = new ArrayList<>();
        levels.add(source);
        BufferedImage current = source;
        while (Math.max(current.getWidth(), current.getHeight()) > SMALLEST_LEVEL_SIDE) {
            int width = Math.max(1, current.getWidth() / 2);
            int height = Math.max(1, current.getHeight() / 2);
            int type = current.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : current.getType();
            BufferedImage half = new BufferedImage(width, height, type);
            Graphics2D g = half.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, width, height, null);
            g.dispose();
            levels.add(half);
            current = half;
        }
        return levels;
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(new Color(0x14121f));
        g2.fillRect(0, 0, getWidth(), getHeight());
        if (image == null) {
            g2.setColor(UIManager.getColor("Label.disabledForeground"));
            FontMetrics metrics = g2.getFontMetrics();
            g2.drawString(placeholder, (getWidth() - metrics.stringWidth(placeholder)) / 2, getHeight() / 2);
            g2.dispose();
            return;
        }
        double scale = scale();
        double drawWidth = image.getWidth() * scale;
        double drawHeight = image.getHeight() * scale;
        // At zoom 1 the image is centred; when zoomed, the shared centre decides what is shown.
        double left = getWidth() / 2.0 - state.centerX * drawWidth;
        double top = getHeight() / 2.0 - state.centerY * drawHeight;
        if (drawWidth <= getWidth()) {
            left = (getWidth() - drawWidth) / 2;
        }
        if (drawHeight <= getHeight()) {
            top = (getHeight() - drawHeight) / 2;
        }
        int x = (int) Math.round(left);
        int y = (int) Math.round(top);
        int w = (int) Math.round(drawWidth);
        int h = (int) Math.round(drawHeight);
        int level = levelFor(scale);

        // Enlarged pixels stay sharp so single pixels can be judged; reduced views are smoothed.
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1.0
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(imageLevel(level), x, y, w, h, null);
        if (!overlayLevels.isEmpty() && overlayVisible) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(overlayLevels.get(Math.min(level, overlayLevels.size() - 1)), x, y, w, h, null);
        }
        if (state.zoom > 1.0) {
            String label = String.format(Locale.US, "%.0f %%", scale * 100);
            g2.setColor(new Color(10, 12, 20, 190));
            g2.fillRect(6, 6, g2.getFontMetrics().stringWidth(label) + 10, 18);
            g2.setColor(Color.WHITE);
            g2.drawString(label, 11, 19);
        }
        g2.dispose();
    }
}
