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

import eu.startales.spacepixels.config.VisualizationSettings;
import io.github.ppissias.jtransient.model.DetectedObject;
import io.github.ppissias.jtransient.model.Pixel;
import eu.startales.spacepixels.fits.BlinkSequence;
import eu.startales.spacepixels.detection.TrackOverlay;
import eu.startales.spacepixels.detection.VariableStarOverlay;

import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.ToolTipManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Shows one frame from its 16-bit data through the stretch tables, drawing only the part on screen (from a reduced
 * copy when zoomed out). The zoom and centre stay when the frame changes. Scroll zooms around the cursor, drag pans
 * and double-click switches between the fitted view and 100 %. Used by Blink, Preview Frame and Manual Transient
 * Inspection; catalogue labels, tracks and detections are drawn on top, so they stay sharp at any zoom.
 */
final class FrameView extends JComponent implements javax.accessibility.Accessible {

    /** Exposes the displayed frame and navigation state as an accessible image, without retaining extra pixels. */
    @Override
    public javax.accessibility.AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) accessibleContext = new AccessibleFrameView();
        return accessibleContext;
    }

    /** Supplies image semantics and current frame/status text for the custom-painted component. */
    protected class AccessibleFrameView extends AccessibleJComponent {
        /** Identifies the custom-painted astronomical frame as an image. */
        @Override public javax.accessibility.AccessibleRole getAccessibleRole() {
            return javax.accessibility.AccessibleRole.ICON;
        }

        /** Reports the owning viewer's image name and currently loaded filename. */
        @Override public String getAccessibleName() {
            String name = super.getAccessibleName();
            return (name == null ? "Astronomical frame" : name)
                    + (frame == null ? " · " + placeholder : " · " + frame.getInfo().getFileName());
        }

        /** Describes dimensions, zoom and enabled overlays together with the viewer's keyboard guidance. */
        @Override public String getAccessibleDescription() {
            String guidance = super.getAccessibleDescription();
            return (guidance == null ? "" : guidance + " ") + (frame == null ? placeholder
                    : frame.getWidth() + " by " + frame.getHeight() + " pixels; "
                    + Math.round(scale() * 100) + " percent zoom. "
                    + (skyMarks == null ? "Catalogue annotations off. " : "Catalogue annotations on. ")
                    + (overlayVisible && overlay != null ? "Tracks on. " : "")
                    + (faintMoversVisible && faintMoverOverlay != null ? "Faint movers on. " : "")
                    + (findingsVisible && findingOverlay != null ? "Other findings on. " : "")
                    + (variableStarsVisible && variableStarOverlay != null ? "Variable candidates on." : ""));
        }
    }

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
    private static final Color SKY_SOLAR_SYSTEM_COLOR = new Color(190, 255, 90);
    /** Screen pixels per catalogue mark: when more would be on screen, only the brightest (or largest) are drawn. */
    private static final double SCREEN_AREA_PER_STAR = 2500;
    private static final double SCREEN_AREA_PER_VARIABLE = 2500;
    private static final double SCREEN_AREA_PER_DEEP_SKY = 5000;
    private static final double SCREEN_AREA_PER_SOLAR_SYSTEM = 5000;
    /** Labels are written only while no more marks of the kind than this are drawn. */
    private static final int MAX_LABELLED_STARS = 60;
    private static final int MAX_LABELLED_VARIABLES = 60;
    private static final int MAX_LABELLED_DEEP_SKY = 150;
    private static final int MAX_LABELLED_SOLAR_SYSTEM = 40;
    /** The radius of an asteroid's or comet's ring on screen. */
    private static final double SOLAR_SYSTEM_RING = 9;
    /** Long survey names ("Gaia DR3 3131…", "ZTF J0639…") are written only while this few variables are drawn. */
    private static final int MAX_LABELLED_SURVEY_VARIABLES = 15;
    private static final int SHORT_NAME_LENGTH = 12;
    static final double MAX_ZOOM = 16.0;
    static final double MAX_PIXEL_SCALE = 8.0;
    /** Reduced picture copies are made down to this size. */
    private static final int SMALLEST_LEVEL_SIDE = 512;

    /** Zoom and centre shared by linked views. A lone view keeps a private link. */
    static final class ViewLink {
        private final java.util.List<FrameView> views = new java.util.ArrayList<>();
        double zoom = 1.0;
        double centerX = 0.5;
        double centerY = 0.5;

        private void add(FrameView view) { views.add(view); }

        private void repaint() {
            for (FrameView view : views) view.repaint();
        }
    }

    private final ViewLink link;
    private BlinkSequence.Frame frame;
    /** Stretch table of each channel of {@link #frame}. */
    private byte[][] tables;
    private boolean extremeColour;
    private TrackOverlay overlay;
    private boolean overlayVisible;
    private List<DetectedObject> detections;
    private boolean detectionsVisible = true;
    /** The marker sizes of detections, read at each paint so they follow the settings page. */
    private Supplier<VisualizationSettings> markerSettings = VisualizationSettings::defaults;
    private List<SkyMark> skyMarks;
    private double skyStarLimit;
    private boolean skyStars = true;
    private boolean skyDeepSky = true;
    private boolean skyVariables = true;
    private boolean skySolarSystem = true;
    private ViewerAnnotationPreferences skyPreferences = ViewerAnnotationPreferences.DEFAULT;
    /** The catalogue marks drawn last, for {@link #skyMarkAt}. */
    private List<SkyMark> drawnSkyMarks = new java.util.ArrayList<>();
    private VariableStarOverlay variableStarOverlay;
    private eu.startales.spacepixels.detection.FaintMoverOverlay faintMoverOverlay;
    private eu.startales.spacepixels.detection.FindingOverlay findingOverlay;
    private boolean findingsVisible = true;
    private boolean faintMoversVisible;
    private boolean variableStarsVisible = true;
    private String placeholder = "Loading…";
    private CursorListener cursorListener;
    /** A prepared picture, used by the stretch previews and the star-mask stack instead of a FITS frame. */
    private BufferedImage picture;
    private java.util.List<BufferedImage> pictureLevels = java.util.List.of();
    private java.util.List<BufferedImage> imageOverlayLevels = java.util.List.of();
    private boolean imageOverlayVisible = true;

    private BufferedImage buffer;
    private int[] bufferPixels;
    private Point dragStart;
    private double dragCenterX;
    private double dragCenterY;

    FrameView() { this(new ViewLink()); }

    /** Shares zoom and pan with the other views of {@code link}, as the two stretch previews do. */
    FrameView(ViewLink link) {
        this.link = link;
        link.add(this);
        setPreferredSize(new Dimension(800, 600));
        setOpaque(true);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getPoint();
                dragCenterX = link.centerX;
                dragCenterY = link.centerY;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                double scale = scale();
                if (dragStart == null || shownWidth() <= 0 || scale <= 0) {
                    return;
                }
                link.centerX = clamp(dragCenterX - (e.getX() - dragStart.x) / (scale * shownWidth()));
                link.centerY = clamp(dragCenterY - (e.getY() - dragStart.y) / (scale * shownHeight()));
                link.repaint();
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
                if (e.getClickCount() != 2 || shownWidth() <= 0) {
                    return;
                }
                if (link.zoom > 1.0) {
                    fit();
                } else {
                    zoomAround(e.getX(), e.getY(), 1.0 / fitScale());
                }
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (shownWidth() > 0) {
                    zoomAround(e.getX(), e.getY(), link.zoom * Math.pow(1.25, -e.getPreciseWheelRotation()));
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

    /** Makes an export-owned view with frozen navigation, overlays and marker settings. Called and used only on EDT. */
    FrameView copyForExport() {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Capture the viewer on the EDT.");
        FrameView copy = new FrameView();
        ToolTipManager.sharedInstance().unregisterComponent(copy);
        copy.setSize(getSize());
        copy.link.zoom = link.zoom; copy.link.centerX = link.centerX; copy.link.centerY = link.centerY;
        copy.frame = frame; copy.extremeColour = extremeColour;
        copy.tables = tables == null ? null : java.util.Arrays.stream(tables).map(byte[]::clone).toArray(byte[][]::new);
        copy.overlay = overlay; copy.overlayVisible = overlayVisible;
        copy.detections = detections == null ? null : List.copyOf(detections); copy.detectionsVisible = detectionsVisible;
        VisualizationSettings markers = markerSettings.get(); copy.markerSettings = () -> markers;
        copy.variableStarOverlay = variableStarOverlay; copy.variableStarsVisible = variableStarsVisible;
        copy.faintMoverOverlay = faintMoverOverlay; copy.faintMoversVisible = faintMoversVisible;
        copy.skyMarks = skyMarks == null ? null : List.copyOf(skyMarks); copy.skyStarLimit = skyStarLimit;
        copy.skyStars = skyStars; copy.skyDeepSky = skyDeepSky; copy.skyVariables = skyVariables;
        copy.skySolarSystem = skySolarSystem; copy.skyPreferences = skyPreferences;
        return copy;
    }

    /** Frozen catalogue depth for an export's independently captured per-frame marks. EDT only. */
    double exportStarLimit() { return skyStarLimit; }

    /** Device-pixel bounds of a viewport rectangle; fractional HiDPI edges include the pixels touched by the selection. */
    static java.awt.Rectangle deviceBounds(java.awt.Rectangle area, java.awt.geom.AffineTransform device) {
        double sx = device.getScaleX(), sy = device.getScaleY();
        if (!(sx > 0 && sy > 0) || !Double.isFinite(sx) || !Double.isFinite(sy)) throw new IllegalArgumentException("Invalid display scale.");
        int x = (int) Math.floor(area.x * sx), y = (int) Math.floor(area.y * sy);
        return new java.awt.Rectangle(x, y, (int) Math.ceil(area.getMaxX() * sx) - x, (int) Math.ceil(area.getMaxY() * sy) - y);
    }

    /** Paints the same viewport and overlays into a selected device-pixel crop; selection guides live outside this view. EDT only. */
    BufferedImage renderRegion(java.awt.Rectangle area, java.awt.geom.AffineTransform device) {
        if (!javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Render the viewer on the EDT.");
        if (area.isEmpty() || !new java.awt.Rectangle(0, 0, getWidth(), getHeight()).contains(area))
            throw new IllegalArgumentException("Select a nonempty area inside the image view.");
        java.awt.Rectangle pixels = deviceBounds(area, device);
        BufferedImage image = new BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.translate(-pixels.x, -pixels.y);
            graphics.scale(device.getScaleX(), device.getScaleY());
            paint(graphics);
        } finally { graphics.dispose(); }
        return image;
    }

    /** Shows this frame with these stretch tables; {@code extremeColour} draws a colour frame as the brightest channel. */
    void showFrame(BlinkSequence.Frame frame, byte[][] tables, boolean extremeColour) {
        this.picture = null;
        this.pictureLevels = java.util.List.of();
        this.imageOverlayLevels = java.util.List.of();
        this.frame = frame;
        this.tables = tables;
        this.extremeColour = extremeColour;
        if (accessibleContext != null) accessibleContext.firePropertyChange(
                javax.accessibility.AccessibleContext.ACCESSIBLE_VISIBLE_DATA_PROPERTY, null, frame.getInfo().getFileName());
        repaint();
    }

    void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    /** Shows a prepared picture. A null picture clears it and does not change the zoom. */
    void showPicture(BufferedImage image) {
        this.picture = image;
        this.pictureLevels = image == null ? java.util.List.of() : java.util.List.of(image);
        if (image != null) {
            frame = null;
            tables = null;
        }
        repaint();
    }

    /** A same-sized picture drawn over {@link #showPicture}, for example the star mask. */
    void setImageOverlay(BufferedImage overlay) {
        setImageOverlayLevels(overlay == null ? java.util.List.of() : java.util.List.of(overlay));
    }

    /**
     * The mask and its reduced copies: entry k is reduced by 2^k. A missing level uses the closest finer one.
     */
    void setImageOverlayLevels(java.util.List<BufferedImage> levels) {
        this.imageOverlayLevels = levels == null ? java.util.List.of() : levels;
        repaint();
    }

    /** Shows or hides the picture overlay without affecting the track overlay. */
    void setImageOverlayVisible(boolean visible) {
        this.imageOverlayVisible = visible;
        repaint();
    }

    boolean isImageOverlayVisible() { return imageOverlayVisible; }

    void clear() {
        frame = null;
        tables = null;
        picture = null;
        pictureLevels = java.util.List.of();
        imageOverlayLevels = java.util.List.of();
        buffer = null;
        bufferPixels = null;
        overlay = null;
        variableStarOverlay = null;
        faintMoverOverlay = null;
        findingOverlay = null;
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
        drawnSkyMarks = new java.util.ArrayList<>();
        repaint();
    }

    /** Which kinds of catalogue objects are drawn; {@code solarSystem} is the predicted asteroids and comets. */
    void setSkyLayers(boolean stars, boolean deepSky, boolean variables, boolean solarSystem) {
        this.skyStars = stars;
        this.skyDeepSky = deepSky;
        this.skyVariables = variables;
        this.skySolarSystem = solarSystem;
        drawnSkyMarks = new java.util.ArrayList<>();
        repaint();
    }

    /** Changes catalogue presentation only, clearing hover targets until the filtered marks are repainted. */
    void setSkyPreferences(ViewerAnnotationPreferences preferences) {
        skyPreferences = java.util.Objects.requireNonNull(preferences);
        drawnSkyMarks = new java.util.ArrayList<>();
        repaint();
    }

    void setVariableStarOverlay(VariableStarOverlay overlay) {
        variableStarOverlay = overlay;
        repaint();
    }

    /** Installs detached fitted paths from the completed run; called on the EDT. */
    void setFaintMoverOverlay(eu.startales.spacepixels.detection.FaintMoverOverlay overlay) {
        faintMoverOverlay = overlay;
        repaint();
    }

    /** Changes only the experimental fitted-path display layer. */
    void setFaintMoversVisible(boolean visible) { faintMoversVisible = visible; repaint(); }

    /** Installs detached report markers; actual frame identities govern measured points, and references stay labelled. */
    void setFindingOverlay(eu.startales.spacepixels.detection.FindingOverlay overlay) { findingOverlay = overlay; repaint(); }

    /** Changes only the additional saved/run finding display layer. */
    void setFindingsVisible(boolean visible) { findingsVisible = visible; repaint(); }

    void setVariableStarsVisible(boolean visible) {
        variableStarsVisible = visible;
        repaint();
    }

    /** Catalogue names remain available beside the pointer even when density limits hide their labels. */
    @Override
    public String getToolTipText(MouseEvent event) {
        Point pixel = pixelAt(event.getX(), event.getY());
        VariableStarOverlay.Candidate candidate = variableStarAt(pixel);
        if (candidate != null) return candidate.description;
        String finding = findingAt(pixel);
        if (finding != null) return finding;
        SkyMark mark = skyMarkAt(pixel);
        return mark == null ? null : mark.description;
    }

    private VariableStarOverlay.Candidate variableStarAt(Point pixel) {
        if (!hasVariableStars() || pixel == null) return null;
        double nearest = 8 / scale();
        VariableStarOverlay.Candidate found = null;
        for (VariableStarOverlay.Candidate candidate : variableStarOverlay.getCandidates()) {
            double distance = Math.hypot(candidate.x - pixel.x, candidate.y - pixel.y);
            if (distance <= nearest) {
                nearest = distance;
                found = candidate;
            }
        }
        return found;
    }

    private boolean hasVariableStars() {
        return variableStarsVisible && frame != null && variableStarOverlay != null
                && variableStarOverlay.covers(frame.getInfo());
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
        for (SkyMark.Kind kind : new SkyMark.Kind[]{SkyMark.Kind.SOLAR_SYSTEM, SkyMark.Kind.VARIABLE, SkyMark.Kind.NAMED_STAR,
                SkyMark.Kind.STAR}) {
            for (SkyMark mark : drawnSkyMarks) {
                double distance = Math.hypot(mark.x - pixel.x, mark.y - pixel.y);
                if (mark.kind == kind && mark.isPlaced() && distance <= reach && distance < bestDistance) {
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
    void setDetections(List<DetectedObject> detections) {
        this.detections = detections;
        repaint();
    }

    /** Where the marker sizes of detections come from; the built-in sizes until a window sets it. */
    void setMarkerSettings(Supplier<VisualizationSettings> settings) {
        markerSettings = java.util.Objects.requireNonNull(settings);
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
        link.zoom = 1.0;
        link.centerX = 0.5;
        link.centerY = 0.5;
        link.repaint();
    }

    /** Zooms to this many screen pixels per frame pixel (1 is 100 %), keeping the centre. */
    void zoomToScale(double pixelScale) {
        double fit = fitScale();
        if (fit <= 0) {
            return;
        }
        link.zoom = Math.max(1.0, Math.min(maxZoom(), pixelScale / fit));
        link.repaint();
    }

    /** The zoom as a percentage (screen pixels per frame pixel). */
    double zoomPercent() {
        return scale() * 100;
    }

    private void zoomAround(int screenX, int screenY, double newZoom) {
        double before = scale();
        double pointX = frameX(screenX, before);
        double pointY = frameY(screenY, before);
        link.zoom = Math.max(1.0, Math.min(maxZoom(), newZoom));
        double after = scale();
        link.centerX = clamp(pointX - (screenX - getWidth() / 2.0) / (after * shownWidth()));
        link.centerY = clamp(pointY - (screenY - getHeight() / 2.0) / (after * shownHeight()));
        link.repaint();
    }

    private double maxZoom() {
        double fit = fitScale();
        return fit <= 0 ? MAX_ZOOM : Math.max(MAX_ZOOM, MAX_PIXEL_SCALE / fit);
    }

    private int shownWidth() {
        if (picture != null) return picture.getWidth();
        return frame == null ? 0 : frame.getWidth();
    }

    private int shownHeight() {
        if (picture != null) return picture.getHeight();
        return frame == null ? 0 : frame.getHeight();
    }

    private double fitScale() {
        if (shownWidth() <= 0 || shownHeight() <= 0 || getWidth() <= 0 || getHeight() <= 0) {
            return 0;
        }
        return Math.min(getWidth() / (double) shownWidth(), getHeight() / (double) shownHeight());
    }

    /** Screen pixels per frame pixel. */
    private double scale() {
        return fitScale() * link.zoom;
    }

    /** Fraction of the frame width under this screen column. */
    private double frameX(int screenX, double scale) {
        return (screenX - left(scale)) / (scale * shownWidth());
    }

    private double frameY(int screenY, double scale) {
        return (screenY - top(scale)) / (scale * shownHeight());
    }

    /** Screen position of the frame's left edge: centred when it fits, else set by the centre. */
    private double left(double scale) {
        double drawWidth = shownWidth() * scale;
        return drawWidth <= getWidth() ? (getWidth() - drawWidth) / 2 : getWidth() / 2.0 - link.centerX * drawWidth;
    }

    private double top(double scale) {
        double drawHeight = shownHeight() * scale;
        return drawHeight <= getHeight() ? (getHeight() - drawHeight) / 2 : getHeight() / 2.0 - link.centerY * drawHeight;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private void reportCursor(int screenX, int screenY) {
        if (cursorListener == null) {
            return;
        }
        cursorListener.cursorMoved(pixelAt(screenX, screenY));
    }

    private Point pixelAt(int screenX, int screenY) {
        double scale = scale();
        if (shownWidth() <= 0 || scale <= 0) return null;
        int x = (int) Math.floor((screenX - left(scale)) / scale);
        int y = (int) Math.floor((screenY - top(scale)) / scale);
        boolean inside = x >= 0 && y >= 0 && x < shownWidth() && y < shownHeight();
        return inside ? new Point(x, y) : null;
    }

    /** The reduced copy to draw from: the coarsest one that still has at least one pixel per device pixel. */
    static int levelFor(double devicePixelsPerFramePixel) {
        if (devicePixelsPerFramePixel >= 1.0 || devicePixelsPerFramePixel <= 0) {
            return 0;
        }
        return (int) Math.floor(Math.log(1.0 / devicePixelsPerFramePixel) / Math.log(2.0));
    }

    /** Draws a prepared picture with the same fit, zoom and pan as a frame, plus its optional mask. */
    private void drawPicture(Graphics2D g2) {
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        int x = (int) Math.round(left);
        int y = (int) Math.round(top);
        int w = (int) Math.round(picture.getWidth() * scale);
        int h = (int) Math.round(picture.getHeight() * scale);
        int level = levelFor(scale);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, scale >= 1.0
                ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(pictureLevel(level), x, y, w, h, null);
        if (!imageOverlayLevels.isEmpty() && imageOverlayVisible) {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(imageOverlayLevels.get(Math.min(level, imageOverlayLevels.size() - 1)), x, y, w, h, null);
        }
    }

    private BufferedImage pictureLevel(int level) {
        if (pictureLevels.size() == 1 && picture != null
                && Math.max(picture.getWidth(), picture.getHeight()) > SMALLEST_LEVEL_SIDE * 2) {
            pictureLevels = halvings(picture);
        }
        return pictureLevels.get(Math.min(level, pictureLevels.size() - 1));
    }

    /** Smoothed half-size copies, down to {@link #SMALLEST_LEVEL_SIDE}. */
    private static java.util.List<BufferedImage> halvings(BufferedImage source) {
        java.util.List<BufferedImage> levels = new java.util.ArrayList<>();
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

    private void drawZoomLabel(Graphics2D g2) {
        if (link.zoom <= 1.0) return;
        String label = String.format(Locale.US, "%.0f %%", zoomPercent());
        g2.setFont(UiStyle.bodyFont());
        drawNote(g2, label, UiStyle.space(6), Color.WHITE);
    }

    /** Height of one image-overlay note, including padding, at the selected interface font size. */
    private static int noteHeight(Graphics2D g2) {
        return g2.getFontMetrics().getHeight() + UiStyle.space(8);
    }

    /** A readable backdrop fitted to its current font instead of a fixed 18-pixel rectangle. */
    private static void drawNote(Graphics2D g2, String text, int y, Color foreground) {
        int x = UiStyle.space(6), padding = UiStyle.space(4);
        FontMetrics metrics = g2.getFontMetrics();
        g2.setColor(new Color(10, 12, 20, 190));
        g2.fillRect(x, y, metrics.stringWidth(text) + 2 * padding, noteHeight(g2));
        g2.setColor(foreground);
        g2.drawString(text, x + padding, y + padding + metrics.getAscent());
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (picture != null) {
                drawPicture(g2);
                drawZoomLabel(g2);
                return;
            }
            if (frame == null || tables == null) {
                g2.setFont(UiStyle.bodyFont());
                g2.setColor(UiStyle.imageTextColor());
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
            if (hasVariableStars()) drawVariableStars(g2);
            if (faintMoversVisible && faintMoverOverlay != null) drawFaintMovers(g2);
            if (findingsVisible && findingOverlay != null) drawFindings(g2);
            drawZoomLabel(g2);
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

    /** The last run's variable candidates, in frame pixels, with fixed-size rings and report numbers. */
    private void drawVariableStars(Graphics2D g2) {
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        List<VariableStarOverlay.Candidate> visible = new java.util.ArrayList<>();
        for (VariableStarOverlay.Candidate candidate : variableStarOverlay.getCandidates()) {
            double x = left + (candidate.x + 0.5) * scale;
            double y = top + (candidate.y + 0.5) * scale;
            if (x >= 0 && y >= 0 && x <= getWidth() && y <= getHeight()) visible.add(candidate);
        }
        java.awt.Shape clip = g2.getClip();
        g2.clip(new java.awt.geom.Rectangle2D.Double(left, top, frame.getWidth() * scale, frame.getHeight() * scale));
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        for (VariableStarOverlay.Candidate candidate : visible) {
            double x = left + (candidate.x + 0.5) * scale;
            double y = top + (candidate.y + 0.5) * scale;
            g2.setColor(SKY_VARIABLE_COLOR);
            g2.setStroke(candidate.highConfidence ? new BasicStroke(1.5f)
                    : new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{3, 3}, 0));
            g2.draw(new Ellipse2D.Double(x - 9, y - 9, 18, 18));
            String text = candidate.label + (candidate.name != null && visible.size() <= 40 ? " · " + candidate.name : "");
            drawLabel(g2, text, x + 12, y - 8, SKY_VARIABLE_COLOR);
        }
        g2.setClip(clip);
    }

    /** Catalogue marks are thinned by screen area, with labels shown while few enough marks are drawn. */
    private void drawSkyMarks(Graphics2D g2) {
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        double area = (double) getWidth() * getHeight();
        List<SkyMark> stars = new java.util.ArrayList<>();
        List<SkyMark> variables = new java.util.ArrayList<>();
        List<SkyMark> deepSky = new java.util.ArrayList<>();
        List<SkyMark> namedStars = new java.util.ArrayList<>();
        List<SkyMark> solarSystem = new java.util.ArrayList<>();
        for (SkyMark mark : skyMarks) {
            boolean shown = mark.kind == SkyMark.Kind.STAR || mark.kind == SkyMark.Kind.NAMED_STAR ? skyStars
                    : mark.kind == SkyMark.Kind.VARIABLE ? skyVariables
                    : mark.kind == SkyMark.Kind.SOLAR_SYSTEM ? skySolarSystem : skyDeepSky;
            if (!shown || !skyPreferences.includes(mark)) {
                continue;
            }
            if (mark.kind == SkyMark.Kind.NAMED_STAR) {
                namedStars.add(mark);
                continue;
            }
            if (mark.kind == SkyMark.Kind.SOLAR_SYSTEM) {
                // Kept by its path too: an object can cross the screen before or after this frame's time.
                solarSystem.add(mark);
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
        int solarSystemTotal = solarSystem.size();
        stars = brightest(stars, (int) (area / SCREEN_AREA_PER_STAR), brightestFirst);
        solarSystem = brightest(solarSystem, Math.max(10, (int) (area / SCREEN_AREA_PER_SOLAR_SYSTEM)), brightestFirst);
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
        g2.setFont(UiStyle.bodyFont());
        g2.setStroke(new BasicStroke(1.2f));
        boolean labelStars = skyPreferences.names() && stars.size() <= MAX_LABELLED_STARS;
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
        boolean labelVariables = skyPreferences.names() && variables.size() <= MAX_LABELLED_VARIABLES;
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
        boolean labelDeepSky = skyPreferences.names() && deepSky.size() <= MAX_LABELLED_DEEP_SKY;
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
        for (SkyMark mark : namedStars) {
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            double radius = 16 * circleZoom;
            g2.setColor(SKY_NAMED_STAR_COLOR);
            g2.draw(new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius));
            g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
            if (skyPreferences.names()) {
                drawLabel(g2, mark.label, x + radius + 4, y - radius / 2, SKY_NAMED_STAR_COLOR);
            }
            g2.setFont(UiStyle.bodyFont());
            drawn.add(mark);
        }
        drawSolarSystem(g2, solarSystem, left, top, scale, drawn);
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
        if (solarSystem.size() < solarSystemTotal) {
            clipped.add(String.format(Locale.US, "the brightest %,d of %,d asteroids and comets", solarSystem.size(), solarSystemTotal));
        }
        if (!clipped.isEmpty()) {
            String note = "Showing " + String.join(", ", clipped) + " on screen · zoom in for more";
            g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
            drawNote(g2, note, getHeight() - noteHeight(g2) - UiStyle.space(6), SKY_STAR_COLOR);
        }
    }

    /**
     * Asteroids and comets: the predicted path over the session as a faint dashed line, the motion during this
     * exposure as a solid line, a ring at the predicted position with its name and magnitude, and the 3-sigma
     * uncertainty as a dotted ellipse when it is larger than the ring. Marks without a position at this frame's time
     * show their path only.
     */
    private void drawSolarSystem(Graphics2D g2, List<SkyMark> marks, double left, double top, double scale, List<SkyMark> drawn) {
        boolean label = skyPreferences.names() && marks.size() <= MAX_LABELLED_SOLAR_SYSTEM;
        Stroke dashed = new BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{6, 4}, 0);
        Stroke dotted = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[]{2, 3}, 0);
        for (SkyMark mark : marks) {
            if (mark.path != null && mark.path.length >= 4) {
                Path2D.Double path = new Path2D.Double();
                path.moveTo(left + (mark.path[0] + 0.5) * scale, top + (mark.path[1] + 0.5) * scale);
                for (int i = 2; i + 1 < mark.path.length; i += 2) {
                    path.lineTo(left + (mark.path[i] + 0.5) * scale, top + (mark.path[i + 1] + 0.5) * scale);
                }
                g2.setColor(withAlpha(SKY_SOLAR_SYSTEM_COLOR, 170));
                g2.setStroke(dashed);
                g2.draw(path);
            }
            if (!mark.isPlaced()) {
                continue;
            }
            double x = left + (mark.x + 0.5) * scale;
            double y = top + (mark.y + 0.5) * scale;
            g2.setColor(SKY_SOLAR_SYSTEM_COLOR);
            if (mark.trail != null) {
                double x0 = left + (mark.trail[0] + 0.5) * scale;
                double y0 = top + (mark.trail[1] + 0.5) * scale;
                double x1 = left + (mark.trail[2] + 0.5) * scale;
                double y1 = top + (mark.trail[3] + 0.5) * scale;
                if (Math.hypot(x1 - x0, y1 - y0) >= 3) {
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.draw(new java.awt.geom.Line2D.Double(x0, y0, x1, y1));
                }
            }
            if (mark.semiMajor * scale >= SOLAR_SYSTEM_RING) {
                java.awt.geom.AffineTransform saved = g2.getTransform();
                g2.rotate(mark.angle, x, y);
                double a = mark.semiMajor * scale;
                double b = Math.max(2, mark.semiMinor * scale);
                g2.setStroke(dotted);
                g2.draw(new Ellipse2D.Double(x - a, y - b, 2 * a, 2 * b));
                g2.setTransform(saved);
            }
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(new Ellipse2D.Double(x - SOLAR_SYSTEM_RING, y - SOLAR_SYSTEM_RING, 2 * SOLAR_SYSTEM_RING, 2 * SOLAR_SYSTEM_RING));
            if (label) {
                drawLabel(g2, mark.label, x + SOLAR_SYSTEM_RING + 3, y + SOLAR_SYSTEM_RING + 3, SKY_SOLAR_SYSTEM_COLOR);
            }
            drawn.add(mark);
        }
        g2.setStroke(new BasicStroke(1.2f));
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
        g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
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

    /** Draws report-category markers only at measured frame positions; dashed squares explicitly denote references. */
    private void drawFindings(Graphics2D g2) {
        if (frame == null) return;
        Integer number = findingOverlay.frameNumber(frame.getInfo());
        if (number == null) return;
        double scale = scale(), left = left(scale), top = top(scale), radius = Math.max(5, scale * 2);
        g2.setFont(UiStyle.bodyFont().deriveFont(Math.max(9f, UiStyle.bodyFont().getSize2D() - 1)));
        for (var marker : findingOverlay.markers()) {
            var category = eu.startales.spacepixels.report.FindingCategory.fromId(marker.id());
            boolean reference = marker.positions().isEmpty();
            var positions = reference ? java.util.List.of(new eu.startales.spacepixels.detection.FindingOverlay.Position(number,marker.x(),marker.y())) : marker.positions();
            g2.setColor(withAlpha(category.color(), 190));
            g2.setStroke(reference ? new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10,new float[]{3,3},0) : new BasicStroke(1.2f));
            for (var point : positions) if (point.frameNumber() == number) {
                double x = left + (point.x() + .5) * scale, y = top + (point.y() + .5) * scale;
                if (reference) g2.draw(new java.awt.geom.Rectangle2D.Double(x-radius,y-radius,2*radius,2*radius));
                else g2.draw(new Ellipse2D.Double(x-radius,y-radius,2*radius,2*radius));
                g2.drawString(marker.id() + (reference ? " ref" : ""),(float)(x+radius+3),(float)(y-radius));
            }
        }
    }

    /** Returns a nearby additional finding's evidence kind without claiming a reference is a current-frame detection. */
    private String findingAt(Point pixel) {
        if (!findingsVisible || findingOverlay == null || frame == null || pixel == null) return null;
        Integer number = findingOverlay.frameNumber(frame.getInfo());
        if (number == null) return null;
        double distance = 8 / scale();String found = null;
        for (var marker : findingOverlay.markers()) {
            var positions = marker.positions().isEmpty()
                    ? java.util.List.of(new eu.startales.spacepixels.detection.FindingOverlay.Position(number,marker.x(),marker.y())) : marker.positions();
            for (var point : positions) if (point.frameNumber() == number) {
                double separation = Math.hypot(point.x()-pixel.x,point.y()-pixel.y);
                if (separation <= distance) { distance = separation; found = marker.id() + " · " + marker.description(); }
            }
        }
        return found;
    }

    /** Draws dashed fitted paths and exposure-midpoint rings only on frames actually searched by the engine. */
    private void drawFaintMovers(Graphics2D g2) {
        if (frame == null) return;
        Long midpoint = faintMoverOverlay.midpoint(frame.getInfo());
        var times = faintMoverOverlay.times();
        if (midpoint == null || times.isEmpty()) return;
        double scale = scale(), left = left(scale), top = top(scale), radius = Math.max(9, scale * 4);
        g2.setColor(eu.startales.spacepixels.report.FindingCategory.FAINT_MOVER.color());
        g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10, new float[]{5, 4}, 0));
        for (var candidate : faintMoverOverlay.candidates()) {
            double[] start = candidate.positionAt(times.getFirst()), end = candidate.positionAt(times.getLast());
            double[] here = candidate.positionAt(midpoint);
            double x = left + (here[0] + .5) * scale, y = top + (here[1] + .5) * scale;
            g2.draw(new java.awt.geom.Line2D.Double(left + (start[0] + .5) * scale, top + (start[1] + .5) * scale,
                    left + (end[0] + .5) * scale, top + (end[1] + .5) * scale));
            g2.draw(new Ellipse2D.Double(x - radius, y - radius, radius * 2, radius * 2));
            g2.drawString(candidate.id(), (float) (x + radius + 3), (float) (y - radius));
        }
    }

    /**
     * Green boxes around point sources and red lines along streaks, sized in frame pixels by the marker settings
     * (the visualization preferences). From 100 % zoom the exact pixels of each detection are tinted too. Only
     * detections on screen are drawn.
     */
    private void drawDetections(Graphics2D g2) {
        VisualizationSettings markers = markerSettings.get();
        double streakLineScale = markers.getStreakLineScaleFactor();
        double streakBoxRadius = markers.getStreakCentroidBoxRadius();
        double scale = scale();
        double left = left(scale);
        double top = top(scale);
        boolean footprints = scale >= 1.0;
        List<DetectedObject> visible = new java.util.ArrayList<>();
        for (DetectedObject object : detections) {
            if (object.isNoise) {
                continue;
            }
            double reach = object.isStreak
                    ? Math.max(streakBoxRadius, object.elongation * streakLineScale)
                    : pointBoxRadius(object, markers);
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
            g2.setFont(UiStyle.bodyFont().deriveFont(Font.BOLD));
            drawNote(g2, note, noteHeight(g2) + UiStyle.space(12), DETECTION_POINT_COLOR);
            return;
        }
        for (DetectedObject object : visible) {
            double x = left + (object.x + 0.5) * scale;
            double y = top + (object.y + 0.5) * scale;
            Color color = object.isStreak ? DETECTION_STREAK_COLOR : DETECTION_POINT_COLOR;
            if (footprints && object.rawPixels != null) {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                g2.setColor(withAlpha(color, 110));
                for (Pixel pixel : object.rawPixels) {
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
                double box = Math.max(MIN_MARKER_RADIUS, pointBoxRadius(object, markers) * scale);
                g2.draw(new java.awt.geom.Rectangle2D.Double(x - box, y - box, 2 * box, 2 * box));
            }
        }
    }

    /** The box clears the object: its rough radius plus a margin, and is at least the minimum box size. */
    private static double pointBoxRadius(DetectedObject object, VisualizationSettings markers) {
        int minimum = markers.getPointSourceMinBoxRadius();
        if (object.pixelArea <= 0) {
            return minimum;
        }
        return Math.max(minimum, Math.round(Math.sqrt(object.pixelArea / Math.PI)) + markers.getDynamicBoxPadding());
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
