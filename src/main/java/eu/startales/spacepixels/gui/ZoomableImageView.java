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
import java.util.List;

/**
 * An image fitted into the component, with mouse-wheel zoom around the cursor, drag to pan and double-click to fit.
 * Views that share a {@link ViewState} zoom and pan together.
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

    static final double MAX_ZOOM = 16.0;

    private final ViewState state;
    private BufferedImage image;
    private BufferedImage overlay;
    private boolean overlayVisible = true;
    private String placeholder = "No preview";
    private Point dragStart;
    private double dragCenterX;
    private double dragCenterY;

    ZoomableImageView(ViewState state) {
        this.state = state;
        state.views.add(this);
        setPreferredSize(new Dimension(400, 300));
        setToolTipText("Scroll to zoom, drag to pan, double-click to fit. Both previews move together.");
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
                if (e.getClickCount() == 2) {
                    state.reset();
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (image == null) {
                    return;
                }
                // Keep the image point under the cursor in place while zooming.
                double before = scale();
                double pointX = imageX(e.getX(), before);
                double pointY = imageY(e.getY(), before);
                state.zoom = Math.max(1.0, Math.min(MAX_ZOOM, state.zoom * Math.pow(1.25, -e.getPreciseWheelRotation())));
                double after = scale();
                state.centerX = clamp(pointX - (e.getX() - getWidth() / 2.0) / (after * image.getWidth()));
                state.centerY = clamp(pointY - (e.getY() - getHeight() / 2.0) / (after * image.getHeight()));
                state.repaintAll();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** An image of the same size drawn over the main one, for example a mask with transparent pixels. */
    void setOverlay(BufferedImage overlay) {
        this.overlay = overlay;
        repaint();
    }

    void setOverlayVisible(boolean visible) {
        this.overlayVisible = visible;
        repaint();
    }

    void setImage(BufferedImage image) {
        this.image = image;
        repaint();
    }

    void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    /** Screen pixels per image pixel: the fit-to-view scale times the zoom. */
    private double scale() {
        if (image == null || getWidth() <= 0 || getHeight() <= 0) {
            return 0;
        }
        double fit = Math.min(getWidth() / (double) image.getWidth(), getHeight() / (double) image.getHeight());
        return fit * state.zoom;
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
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, state.zoom > 2
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(image, (int) Math.round(left), (int) Math.round(top),
                (int) Math.round(drawWidth), (int) Math.round(drawHeight), null);
        if (overlay != null && overlayVisible) {
            // Same size as the image, so it is drawn over exactly the same area.
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(overlay, (int) Math.round(left), (int) Math.round(top),
                    (int) Math.round(drawWidth), (int) Math.round(drawHeight), null);
        }
        if (state.zoom > 1.0) {
            String label = String.format("%.1f×", state.zoom);
            g2.setColor(new Color(10, 12, 20, 190));
            g2.fillRect(6, 6, g2.getFontMetrics().stringWidth(label) + 10, 18);
            g2.setColor(Color.WHITE);
            g2.drawString(label, 11, 19);
        }
        g2.dispose();
    }
}
