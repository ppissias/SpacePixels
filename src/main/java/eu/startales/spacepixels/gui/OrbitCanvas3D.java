package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.mpc.FindOrb;
import eu.startales.spacepixels.mpc.OrbitGeometry;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Interactive pure-Java2D 3D orbital canvas for {@link OrbitViewerDialog}. Renders a sky-dome starfield, a glowing Sun,
 * an adaptive ecliptic polar grid, all eight planets on their inclined 3D orbits (with Saturn's rings and Earth's lunar
 * ring during close NEO flybys), the fitted primary/revised orbit and optional single-night comparison orbit with
 * above/below-ecliptic depth cues, vertical inclination stems, motion trails, observation-night markers, Earth–object
 * sightline, and an on-canvas telemetry HUD. Supports mouse drag rotation, wheel zoom, pan and high-DPI PNG export.
 */
final class OrbitCanvas3D extends JComponent {
    /** Camera focus target mode. */
    enum FocusMode {
        SUN("Sun"),
        OBJECT("Object"),
        EARTH("Earth");

        private final String label;

        FocusMode(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    private static final DateTimeFormatter HUD_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US)
            .withZone(ZoneOffset.UTC);

    /** Distinct visual palette per planet (Mercury through Neptune). */
    private static final Color[] PLANET_COLORS = {
            new Color(185, 178, 170), // Mercury
            new Color(235, 206, 155), // Venus
            new Color(88, 172, 255),  // Earth
            new Color(224, 122, 86),  // Mars
            new Color(218, 178, 128), // Jupiter
            new Color(228, 204, 148), // Saturn
            new Color(138, 214, 224), // Uranus
            new Color(98, 138, 232)   // Neptune
    };

    /** Base screen radii in logical pixels for planet spheres (visual hierarchy, not to scale). */
    private static final float[] PLANET_RADII = {2.6f, 3.3f, 3.8f, 3.1f, 5.2f, 4.6f, 3.9f, 3.8f};

    /** Nice AU radii candidates for adaptive ecliptic reference rings. */
    private static final double[] GRID_RING_CANDIDATES = {
            0.005, 0.01, 0.02, 0.05, 0.1, 0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 30.0, 50.0, 100.0
    };

    private static final Color PRIMARY_ABOVE = new Color(78, 238, 224);
    private static final Color PRIMARY_BELOW = new Color(56, 156, 178);
    private static final Color PRIMARY_GLOW = new Color(78, 238, 224, 48);
    private static final Color ALONE_ABOVE = new Color(214, 156, 255);
    private static final Color ALONE_BELOW = new Color(148, 102, 190);
    private static final Color OBS_MARKER_COLOR = new Color(255, 214, 118);

    /** Deterministic sky-dome star on the unit sphere with tint and brightness. */
    private record Star(double dx, double dy, double dz, float radius, Color color) { }

    /** One depth-sorted 3D draw command executed back-to-front by painter's algorithm. */
    private record DrawItem(double depth, Runnable draw) { }

    private static final Star[] SKY_STARS = buildStarfield(260);

    private final OrbitCamera3D camera = new OrbitCamera3D();
    private Runnable onCameraChanged = () -> { };

    private String objectLabel = "";
    private FindOrb.Orbit orbit;
    private FindOrb.Orbit alone;
    private int nightsCount = 1;
    private double simulationJd = OrbitGeometry.J2000;
    private double defaultFitRadiusAu = 2.6;
    private double orbitReachAu = 6.0;

    // Cached static 3D paths for the active orbit(s) so 60 FPS animation stays ultra-lightweight.
    private double[][] primaryPath = new double[0][];
    private double[][] alonePath = new double[0][];
    private List<FindOrb.Orbit> sampleOrbits = List.of();
    private List<double[][]> samplePaths = List.of();
    private double[][][] planetPaths = new double[OrbitGeometry.PLANET_COUNT][][];
    private double planetPathsEpochJd = Double.NaN;
    private double[] primaryPerihelion;
    private double[] primaryAscNode;
    private double[] primaryDescNode;
    private double[][] observationNightPositions = new double[0][];

    // Display options.
    private FocusMode focusMode = FocusMode.SUN;
    private boolean showGridAndStems = true;
    private boolean showLabels = true;
    private boolean showSightline = true;
    private boolean showTrail = true;
    private boolean showSingleNight = true;
    private boolean showUncertaintyCloud = true;

    // Mouse drag state.
    private int dragLastX, dragLastY;
    private boolean draggingOrbit, draggingPan;

    OrbitCanvas3D() {
        setOpaque(true);
        setBackground(new Color(8, 11, 20));
        setPreferredSize(new Dimension(UiStyle.space(660), UiStyle.space(540)));
        setMinimumSize(new Dimension(UiStyle.space(360), UiStyle.space(320)));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        installMouseControls();
    }

    /** Registers a callback invoked whenever camera transitions need the animation timer running. */
    void setOnCameraChanged(Runnable callback) {
        this.onCameraChanged = callback == null ? () -> { } : callback;
    }

    /** Exposes the underlying camera for unit tests and keyboard/button controls. */
    OrbitCamera3D camera() { return camera; }

    /** Sets the displayed orbit(s) without an uncertainty sample cloud. */
    void setOrbit(String label, FindOrb.Orbit orbit, FindOrb.Orbit alone, int nightsCount, boolean resetCamera) {
        setOrbit(label, orbit, alone, List.of(), nightsCount, resetCamera);
    }

    /**
     * Sets the displayed orbit(s) and optional Monte Carlo / Statistical Ranging sample orbits for 3D uncertainty
     * cloud rendering, recomputes cached 3D paths, and frames the camera cleanly.
     */
    void setOrbit(String label, FindOrb.Orbit orbit, FindOrb.Orbit alone, List<FindOrb.Orbit> samples,
                  int nightsCount, boolean resetCamera) {
        this.objectLabel = label == null ? "" : label;
        this.orbit = orbit;
        this.alone = alone;
        this.sampleOrbits = samples == null ? List.of() : List.copyOf(samples);
        this.nightsCount = Math.max(1, nightsCount);
        this.simulationJd = orbit != null && Double.isFinite(orbit.epochJd()) ? orbit.epochJd() : OrbitGeometry.J2000;
        this.defaultFitRadiusAu = computeIdealFitRadius(orbit, alone);
        this.orbitReachAu = computeOrbitReach(orbit, alone, defaultFitRadiusAu);
        rebuildOrbitCaches();
        if (resetCamera) {
            this.focusMode = FocusMode.SUN;
            camera.setFocus(0, 0, 0);
            camera.setImmediate(OrbitCamera3D.DEFAULT_AZIMUTH, OrbitCamera3D.DEFAULT_ELEVATION, defaultFitRadiusAu);
        }
        updateCameraFocus();
        repaint();
    }

    /** Updates the simulation Julian Date (used by playback and timeline scrubbing). */
    void setSimulationJd(double jd) {
        this.simulationJd = jd;
        updateCameraFocus();
        repaint();
    }

    /** Returns the current simulation Julian Date. */
    double simulationJd() { return simulationJd; }

    /** Sets the camera focus mode (Sun, Object, or Earth) and clears any manual pan offset. */
    void setFocusMode(FocusMode mode) {
        this.focusMode = mode == null ? FocusMode.SUN : mode;
        camera.clearPan();
        updateCameraFocus();
        repaint();
    }

    FocusMode focusMode() { return focusMode; }

    void setShowGridAndStems(boolean show) { this.showGridAndStems = show; repaint(); }
    void setShowLabels(boolean show) { this.showLabels = show; repaint(); }
    void setShowSightline(boolean show) { this.showSightline = show; repaint(); }
    void setShowTrail(boolean show) { this.showTrail = show; repaint(); }
    void setShowSingleNight(boolean show) { this.showSingleNight = show; repaint(); }
    void setShowUncertaintyCloud(boolean show) { this.showUncertaintyCloud = show; repaint(); }

    /** Smoothly animates the camera to the angled 3D perspective preset. */
    void animateTo3DView() {
        camera.animateTo(OrbitCamera3D.DEFAULT_AZIMUTH, OrbitCamera3D.DEFAULT_ELEVATION, camera.viewRadiusAu(), System.nanoTime());
        onCameraChanged.run();
        repaint();
    }

    /** Smoothly animates the camera to the North Ecliptic Pole (classic top-down view). */
    void animateToTopView() {
        camera.animateTo(Math.toRadians(-90.0), Math.PI * 0.5, camera.viewRadiusAu(), System.nanoTime());
        onCameraChanged.run();
        repaint();
    }

    /** Smoothly animates the camera to an edge-on view of the ecliptic plane to highlight inclination. */
    void animateToEdgeOnView() {
        double targetAz = orbit != null && Double.isFinite(orbit.ascendingNode())
                ? Math.toRadians(orbit.ascendingNode())
                : OrbitCamera3D.DEFAULT_AZIMUTH;
        camera.animateTo(targetAz, 0.0, camera.viewRadiusAu(), System.nanoTime());
        onCameraChanged.run();
        repaint();
    }

    /** Smoothly resets camera orientation, zoom and pan to frame the orbit cleanly. */
    void animateResetView() {
        camera.animateTo(OrbitCamera3D.DEFAULT_AZIMUTH, OrbitCamera3D.DEFAULT_ELEVATION, defaultFitRadiusAu, System.nanoTime());
        onCameraChanged.run();
        repaint();
    }

    /** Smoothly fits zoom to the orbit without changing the current viewing angle. */
    void animateFitZoom() {
        camera.animateTo(camera.azimuth(), camera.elevation(), defaultFitRadiusAu, System.nanoTime());
        onCameraChanged.run();
        repaint();
    }

    /** Zooms in or out smoothly by a fixed step factor. */
    void zoomStep(boolean zoomIn) {
        camera.zoomBy(zoomIn ? 0.78 : 1.28);
        repaint();
    }

    /** Advances any smooth camera transition; returns true if more frames are needed. */
    boolean stepCameraTransition(long nowNanos) {
        boolean moved = camera.stepTransition(nowNanos);
        if (moved) repaint();
        return camera.isTransitioning();
    }

    /**
     * Renders the current 3D view into a crisp supersampled off-screen {@link BufferedImage} of logical size
     * {@code logicalWidth x logicalHeight} scaled by {@code pixelScale}, optionally suppressing interactive hint text
     * for clean saved pictures.
     */
    BufferedImage renderSnapshot(int logicalWidth, int logicalHeight, double pixelScale, boolean forExport) {
        int lw = Math.max(480, logicalWidth);
        int lh = Math.max(360, logicalHeight);
        double scale = Math.max(1.0, pixelScale);
        int pw = Math.max(1, (int) Math.round(lw * scale));
        int ph = Math.max(1, (int) Math.round(lh * scale));
        BufferedImage image = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.scale(scale, scale);
            paintScene(g, lw, lh, forExport);
        } finally {
            g.dispose();
        }
        return image;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            paintScene(g, getWidth(), getHeight(), false);
        } finally {
            g.dispose();
        }
    }

    private void paintScene(Graphics2D g, int w, int h, boolean forExport) {
        if (w <= 0 || h <= 0) return;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        // 1. Deep-space backdrop and rotating 3D celestial-sphere starfield.
        drawSpaceBackdrop(g, w, h);
        if (orbit == null) return;

        // Recompute cached planet orbits if simulation moved far (>15 years) from cache epoch.
        if (!Double.isFinite(planetPathsEpochJd) || Math.abs(simulationJd - planetPathsEpochJd) > 5500.0) {
            for (int p = 0; p < OrbitGeometry.PLANET_COUNT; p++) {
                planetPaths[p] = OrbitGeometry.planetOrbitPath3D(p, simulationJd, p >= 4 ? 120 : 96);
            }
            planetPathsEpochJd = simulationJd;
        }

        // Compute live 3D positions at the current simulation Julian Date.
        double[][] planetPos = new double[OrbitGeometry.PLANET_COUNT][];
        for (int p = 0; p < OrbitGeometry.PLANET_COUNT; p++) {
            planetPos[p] = OrbitGeometry.planetPosition3D(p, simulationJd);
        }
        double[] earthPos = planetPos[OrbitGeometry.EARTH_INDEX];
        double[] objPos = OrbitGeometry.positionAt(orbit, simulationJd);
        double[] alonePos = (alone != null && showSingleNight) ? OrbitGeometry.positionAt(alone, simulationJd) : null;

        // Keep camera anchored to the selected focus body before projecting.
        applyFocusPosition(objPos, earthPos);

        // 2. Ecliptic reference plane (rings, radial spokes, Vernal Equinox axis, orbit ecliptic shadow).
        if (showGridAndStems) {
            drawEclipticPlane(g, w, h, earthPos);
        }

        // 3. Collect all depth-sorted 3D scene items.
        List<DrawItem> items = new ArrayList<>(1200);
        addPlanetOrbits(items, g, w, h);
        if (showGridAndStems) {
            addInclinationStems(items, g, w, h, objPos, alonePos);
        }
        if (showUncertaintyCloud && !samplePaths.isEmpty()) {
            addUncertaintyCloud(items, g, w, h);
        }
        if (alone != null && showSingleNight && alonePath.length >= 2) {
            addOrbitSegments(items, g, w, h, alonePath, alone.bound(), ALONE_ABOVE, ALONE_BELOW, null, true, 1.35f);
        }
        if (primaryPath.length >= 2) {
            addOrbitSegments(items, g, w, h, primaryPath, orbit.bound(), PRIMARY_ABOVE, PRIMARY_BELOW, PRIMARY_GLOW, false, 2.0f);
        }
        if (showTrail) {
            addMotionTrail(items, g, w, h, orbit, PRIMARY_ABOVE, 2.5f);
            if (alone != null && showSingleNight) {
                addMotionTrail(items, g, w, h, alone, ALONE_ABOVE, 1.5f);
            }
        }
        if (showSightline && objPos != null) {
            addSightline(items, g, w, h, earthPos, objPos);
        }
        addSpecialMarkers(items, g, w, h);
        addSun(items, g, w, h);
        addPlanets(items, g, w, h, planetPos);
        if (alonePos != null) {
            addTargetMarker(items, g, w, h, alonePos, ALONE_ABOVE, 4.2f, false);
        }
        if (objPos != null) {
            addTargetMarker(items, g, w, h, objPos, PRIMARY_ABOVE, 5.4f, true);
        }

        // Painter's sort: draw farthest items (largest eyeDepth) first.
        items.sort(Comparator.comparingDouble(DrawItem::depth).reversed());
        for (DrawItem item : items) {
            item.draw().run();
        }

        // 4. Crisp screen-space labels with collision avoidance.
        if (showLabels) {
            drawSceneLabels(g, w, h, planetPos, objPos, alonePos);
        }

        // 5. Unobtrusive HUD: date, telemetry, legend and interaction hint.
        drawHud(g, w, h, objPos, earthPos, alonePos, forExport);
    }

    private void drawSpaceBackdrop(Graphics2D g, int w, int h) {
        float radius = (float) Math.hypot(w, h) * 0.65f;
        RadialGradientPaint bg = new RadialGradientPaint(
                new Point2D.Float(w * 0.5f, h * 0.48f), Math.max(1f, radius),
                new float[]{0f, 0.55f, 1f},
                new Color[]{new Color(17, 25, 44), new Color(10, 14, 26), new Color(5, 7, 14)});
        g.setPaint(bg);
        g.fillRect(0, 0, w, h);

        for (Star star : SKY_STARS) {
            double[] p = camera.projectSkyDirection(star.dx(), star.dy(), star.dz(), w, h);
            if (p == null) continue;
            g.setColor(star.color());
            double r = star.radius();
            g.fill(new Ellipse2D.Double(p[0] - r, p[1] - r, 2 * r, 2 * r));
        }
    }

    private void drawEclipticPlane(Graphics2D g, int w, int h, double[] earthPos) {
        double viewR = camera.viewRadiusAu();
        double maxGridAu = Math.min(100.0, Math.max(0.05, viewR * 2.2));
        double minGridAu = viewR * 0.14;

        // Collect 3–5 clean AU ring radii appropriate to the current zoom level.
        List<Double> rings = new ArrayList<>();
        for (double cand : GRID_RING_CANDIDATES) {
            if (cand >= minGridAu && cand <= maxGridAu) rings.add(cand);
        }
        if (rings.isEmpty()) {
            rings.add(Math.max(0.01, viewR * 0.5));
            rings.add(Math.max(0.02, viewR));
        }
        double outerRing = rings.getLast();

        // Subtle radial spokes every 30 degrees on the ecliptic plane (z = 0).
        g.setStroke(new BasicStroke(0.8f));
        for (int deg = 0; deg < 360; deg += 30) {
            double rad = Math.toRadians(deg);
            double[] inner = {0, 0, 0};
            double[] outer = {outerRing * Math.cos(rad), outerRing * Math.sin(rad), 0};
            var seg = camera.projectSegment(inner, outer, w, h);
            if (seg == null) continue;
            g.setColor(deg == 0 ? new Color(120, 165, 225, 72) : new Color(130, 155, 200, 22));
            g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
        }

        // Concentric AU reference rings.
        g.setStroke(new BasicStroke(0.9f));
        for (double r : rings) {
            g.setColor(new Color(130, 155, 200, 32));
            drawEclipticCircle(g, 0, 0, 0, r, 72, w, h);
        }

        // Vernal Equinox (+X, 0 deg) indicator at outer edge.
        if (showLabels) {
            Font gridFont = UiStyle.bodyFont().deriveFont(Math.max(9.5f, UiStyle.bodyFont().getSize2D() - 2.5f));
            g.setFont(gridFont);
            var vernal = camera.project(outerRing * 1.04, 0, 0, w, h);
            if (vernal != null && isInsideViewport(vernal.sx(), vernal.sy(), w, h, 24)) {
                g.setColor(new Color(145, 185, 235, 165));
                g.drawString("♈ 0°", (float) vernal.sx() + 3f, (float) vernal.sy() + 3f);
            }
        }

        // Faint ecliptic shadow projection (z = 0) of the primary orbit so 3D inclination is immediately legible.
        if (primaryPath.length >= 2 && Math.abs(orbit.i()) > 0.4) {
            g.setColor(new Color(78, 220, 210, 34));
            g.setStroke(new BasicStroke(1.0f));
            int count = orbit.bound() ? primaryPath.length : primaryPath.length - 1;
            for (int k = 0; k < count; k++) {
                double[] a = primaryPath[k];
                double[] b = primaryPath[(k + 1) % primaryPath.length];
                var seg = camera.projectSegment(new double[]{a[0], a[1], 0}, new double[]{b[0], b[1], 0}, w, h);
                if (seg != null) g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
            }
        }

        // When zoomed in close to an Earth encounter (< 0.08 AU), show the Moon's 1 LD orbit ring around Earth!
        if (earthPos != null && viewR < 0.08) {
            g.setColor(new Color(150, 195, 255, 95));
            g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{3f, 3f}, 0f));
            drawEclipticCircle(g, earthPos[0], earthPos[1], earthPos[2], OrbitGeometry.AU_PER_LUNAR_DISTANCE, 48, w, h);
            if (showLabels) {
                var moonLabel = camera.project(earthPos[0] + OrbitGeometry.AU_PER_LUNAR_DISTANCE, earthPos[1], earthPos[2], w, h);
                if (moonLabel != null && isInsideViewport(moonLabel.sx(), moonLabel.sy(), w, h, 24)) {
                    g.setColor(new Color(165, 205, 255, 185));
                    g.drawString("1 LD (Moon)", (float) moonLabel.sx() + 4f, (float) moonLabel.sy() + 3f);
                }
            }
        }
    }

    private void drawEclipticCircle(Graphics2D g, double cx, double cy, double cz, double radius, int segments, int w, int h) {
        double[] prev = {cx + radius, cy, cz};
        for (int k = 1; k <= segments; k++) {
            double ang = 2.0 * Math.PI * k / segments;
            double[] next = {cx + radius * Math.cos(ang), cy + radius * Math.sin(ang), cz};
            var seg = camera.projectSegment(prev, next, w, h);
            if (seg != null) g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
            prev = next;
        }
    }

    private void addPlanetOrbits(List<DrawItem> items, Graphics2D g, int w, int h) {
        double viewR = camera.viewRadiusAu();
        for (int p = 0; p < OrbitGeometry.PLANET_COUNT; p++) {
            if (!shouldShowPlanet(p, viewR)) continue;
            double[][] ring = planetPaths[p];
            if (ring == null || ring.length < 3) continue;
            Color base = PLANET_COLORS[p];
            boolean earth = p == OrbitGeometry.EARTH_INDEX;
            int baseAlpha = earth ? 120 : 72;
            float strokeWidth = earth ? 1.3f : 1.0f;
            for (int k = 0; k < ring.length; k++) {
                double[] a = ring[k];
                double[] b = ring[(k + 1) % ring.length];
                var seg = camera.projectSegment(a, b, w, h);
                if (seg == null) continue;
                int alpha = clampByte((int) Math.round(baseAlpha * depthBrightness(seg.midScale())));
                Color col = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
                items.add(new DrawItem(seg.midEyeDepth(), () -> {
                    g.setColor(col);
                    g.setStroke(new BasicStroke(strokeWidth));
                    g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
                }));
            }
        }
    }

    private void addInclinationStems(List<DrawItem> items, Graphics2D g, int w, int h,
                                     double[] objPos, double[] alonePos) {
        double viewR = camera.viewRadiusAu();
        double minZ = viewR * 0.012;
        if (primaryPath.length >= 12 && Math.abs(orbit.i()) > 0.4) {
            int step = Math.max(1, primaryPath.length / 32);
            for (int k = 0; k < primaryPath.length; k += step) {
                double[] pt = primaryPath[k];
                if (Math.abs(pt[2]) < minZ) continue;
                double[] foot = {pt[0], pt[1], 0};
                var seg = camera.projectSegment(foot, pt, w, h);
                if (seg == null) continue;
                Color stemColor = pt[2] >= 0 ? new Color(78, 228, 214, 58) : new Color(78, 148, 188, 42);
                items.add(new DrawItem(seg.midEyeDepth(), () -> {
                    g.setColor(stemColor);
                    g.setStroke(new BasicStroke(0.85f));
                    g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
                }));
            }
        }
        // Highlighted live height stem from the primary object down/up to the ecliptic plane.
        if (objPos != null && Math.abs(objPos[2]) > viewR * 0.005) {
            double[] foot = {objPos[0], objPos[1], 0};
            var seg = camera.projectSegment(foot, objPos, w, h);
            if (seg != null) {
                Color col = objPos[2] >= 0 ? new Color(110, 245, 230, 175) : new Color(90, 170, 210, 145);
                items.add(new DrawItem(seg.midEyeDepth(), () -> {
                    g.setColor(col);
                    g.setStroke(new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{3f, 3f}, 0f));
                    g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
                    g.setStroke(new BasicStroke(1.1f));
                    g.draw(new Ellipse2D.Double(seg.x1() - 3, seg.y1() - 3, 6, 6));
                }));
            }
        }
        // Subtle stem for the single-night ghost object when comparing.
        if (alonePos != null && Math.abs(alonePos[2]) > viewR * 0.005) {
            double[] foot = {alonePos[0], alonePos[1], 0};
            var seg = camera.projectSegment(foot, alonePos, w, h);
            if (seg != null) {
                items.add(new DrawItem(seg.midEyeDepth(), () -> {
                    g.setColor(new Color(214, 156, 255, 110));
                    g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{2.5f, 3f}, 0f));
                    g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
                }));
            }
        }
    }

    private void addUncertaintyCloud(List<DrawItem> items, Graphics2D g, int w, int h) {
        Stroke cloudStroke = new BasicStroke(0.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        for (int s = 0; s < samplePaths.size() && s < sampleOrbits.size(); s++) {
            double[][] path = samplePaths.get(s);
            if (path.length < 2) continue;
            boolean closed = sampleOrbits.get(s).bound();
            int count = closed ? path.length : path.length - 1;
            for (int k = 0; k < count; k++) {
                double[] a = path[k];
                double[] b = path[(k + 1) % path.length];
                var seg = camera.projectSegment(a, b, w, h);
                if (seg == null) continue;
                boolean above = 0.5 * (a[2] + b[2]) >= 0.0;
                Color col = above ? new Color(90, 215, 225, 34) : new Color(68, 145, 185, 20);
                items.add(new DrawItem(seg.midEyeDepth() + 2e-4, () -> {
                    g.setColor(col);
                    g.setStroke(cloudStroke);
                    g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
                }));
            }
        }
    }

    private void addOrbitSegments(List<DrawItem> items, Graphics2D g, int w, int h,
                                  double[][] path, boolean closed, Color aboveColor, Color belowColor,
                                  Color glowColor, boolean dashed, float widthPx) {
        int count = closed ? path.length : path.length - 1;
        Stroke solidStroke = new BasicStroke(widthPx, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        float[] belowSolidDash = {5.5f, 4.5f};
        float belowSolidCycle = 10.0f;
        float[] dashedAbovePattern = {7.0f, 5.0f};
        float dashedAboveCycle = 12.0f;
        float[] dashedBelowPattern = {4.5f, 5.5f};
        float dashedBelowCycle = 10.0f;
        float belowWidth = Math.max(1.1f, widthPx * 0.82f);
        Stroke glowStroke = glowColor == null ? null : new BasicStroke(widthPx + 3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        // Track cumulative screen-space arc length so dashes stay continuous and uniform across short 3D segments.
        float screenArc = 0f;
        for (int k = 0; k < count; k++) {
            double[] a = path[k];
            double[] b = path[(k + 1) % path.length];
            var seg = camera.projectSegment(a, b, w, h);
            if (seg == null) continue;
            float segLen = (float) Math.hypot(seg.x2() - seg.x1(), seg.y2() - seg.y1());
            boolean above = 0.5 * (a[2] + b[2]) >= 0.0;
            double bright = depthBrightness(seg.midScale());
            Color base = above ? aboveColor : belowColor;
            int alpha = clampByte((int) Math.round((above ? 238 : 168) * bright));
            Color col = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
            Stroke stroke;
            if (dashed) {
                float[] pat = above ? dashedAbovePattern : dashedBelowPattern;
                float cycle = above ? dashedAboveCycle : dashedBelowCycle;
                stroke = new BasicStroke(above ? widthPx : belowWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                        10f, pat, screenArc % cycle);
            } else if (above) {
                stroke = solidStroke;
            } else {
                stroke = new BasicStroke(belowWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                        10f, belowSolidDash, screenArc % belowSolidCycle);
            }
            screenArc += segLen;
            boolean drawGlow = glowColor != null && above;
            items.add(new DrawItem(seg.midEyeDepth(), () -> {
                if (drawGlow) {
                    g.setColor(glowColor);
                    g.setStroke(glowStroke);
                    g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
                }
                g.setColor(col);
                g.setStroke(stroke);
                g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
            }));
        }
    }

    private void addMotionTrail(List<DrawItem> items, Graphics2D g, int w, int h,
                                FindOrb.Orbit targetOrbit, Color color, float maxWidth) {
        double period = OrbitGeometry.orbitalPeriodDays(targetOrbit);
        double trailDays = Double.isFinite(period) ? Math.min(260.0, Math.max(4.0, period * 0.09)) : 35.0;
        double[][] trail = OrbitGeometry.trailBefore(targetOrbit, simulationJd, trailDays, 24);
        if (trail.length < 2) return;
        for (int k = 0; k < trail.length - 1; k++) {
            var seg = camera.projectSegment(trail[k], trail[k + 1], w, h);
            if (seg == null) continue;
            float frac = (k + 1f) / (trail.length - 1f);
            float ease = frac * frac;
            int alpha = clampByte((int) (210 * ease));
            float strokeW = Math.max(1.0f, maxWidth * (0.45f + 1.15f * ease));
            Color col = new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
            items.add(new DrawItem(seg.midEyeDepth() - 1e-4, () -> {
                g.setColor(col);
                g.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
            }));
        }
    }

    private void addSightline(List<DrawItem> items, Graphics2D g, int w, int h, double[] earthPos, double[] objPos) {
        var seg = camera.projectSegment(earthPos, objPos, w, h);
        if (seg == null) return;
        items.add(new DrawItem(seg.midEyeDepth(), () -> {
            g.setColor(new Color(145, 200, 255, 115));
            g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 4f}, 0f));
            g.draw(new Line2D.Double(seg.x1(), seg.y1(), seg.x2(), seg.y2()));
        }));
    }

    private void addSpecialMarkers(List<DrawItem> items, Graphics2D g, int w, int h) {
        // Perihelion marker (small crisp diamond).
        if (primaryPerihelion != null) {
            var p = camera.project(primaryPerihelion[0], primaryPerihelion[1], primaryPerihelion[2], w, h);
            if (p != null) {
                items.add(new DrawItem(p.eyeDepth() - 1e-4, () -> drawDiamond(g, p.sx(), p.sy(), 3.8, new Color(255, 224, 130, 215))));
            }
        }
        // Ascending / descending nodes where the orbit pierces the ecliptic plane.
        if (Math.abs(orbit.i()) > 0.6) {
            if (primaryAscNode != null) {
                var p = camera.project(primaryAscNode[0], primaryAscNode[1], primaryAscNode[2], w, h);
                if (p != null) {
                    items.add(new DrawItem(p.eyeDepth() - 1e-4, () -> drawNodeMarker(g, p.sx(), p.sy(), true)));
                }
            }
            if (primaryDescNode != null) {
                var p = camera.project(primaryDescNode[0], primaryDescNode[1], primaryDescNode[2], w, h);
                if (p != null) {
                    items.add(new DrawItem(p.eyeDepth() - 1e-4, () -> drawNodeMarker(g, p.sx(), p.sy(), false)));
                }
            }
        }
        // Observation night markers along the orbit (highlights multi-night linked arcs).
        for (double[] pos : observationNightPositions) {
            var p = camera.project(pos[0], pos[1], pos[2], w, h);
            if (p == null) continue;
            items.add(new DrawItem(p.eyeDepth() - 1.5e-4, () -> {
                g.setColor(OBS_MARKER_COLOR);
                g.setStroke(new BasicStroke(1.25f));
                double r = 3.2;
                g.draw(new Line2D.Double(p.sx() - r, p.sy(), p.sx() + r, p.sy()));
                g.draw(new Line2D.Double(p.sx(), p.sy() - r, p.sx(), p.sy() + r));
            }));
        }
    }

    private void addSun(List<DrawItem> items, Graphics2D g, int w, int h) {
        var sun = camera.project(0, 0, 0, w, h);
        if (sun == null) return;
        float coreR = (float) Math.max(4.5, Math.min(11.0, 6.2 * Math.sqrt(sun.perspectiveScale())));
        float haloR = coreR * 4.2f;
        items.add(new DrawItem(sun.eyeDepth(), () -> {
            RadialGradientPaint corona = new RadialGradientPaint(
                    new Point2D.Double(sun.sx(), sun.sy()), haloR,
                    new float[]{0f, 0.22f, 0.48f, 1f},
                    new Color[]{
                            new Color(255, 250, 224, 255),
                            new Color(255, 214, 98, 220),
                            new Color(255, 152, 42, 76),
                            new Color(255, 120, 20, 0)
                    }, MultipleGradientPaint.CycleMethod.NO_CYCLE);
            g.setPaint(corona);
            g.fill(new Ellipse2D.Double(sun.sx() - haloR, sun.sy() - haloR, 2 * haloR, 2 * haloR));
            g.setColor(new Color(255, 252, 232));
            g.fill(new Ellipse2D.Double(sun.sx() - coreR * 0.65, sun.sy() - coreR * 0.65, coreR * 1.3, coreR * 1.3));
        }));
    }

    private void addPlanets(List<DrawItem> items, Graphics2D g, int w, int h, double[][] planetPos) {
        double viewR = camera.viewRadiusAu();
        for (int p = 0; p < OrbitGeometry.PLANET_COUNT; p++) {
            if (!shouldShowPlanet(p, viewR)) continue;
            double[] pos = planetPos[p];
            var proj = camera.project(pos[0], pos[1], pos[2], w, h);
            if (proj == null) continue;
            int idx = p;
            Color base = PLANET_COLORS[idx];
            float r = (float) Math.max(2.2, Math.min(8.5, PLANET_RADII[idx] * Math.pow(proj.perspectiveScale(), 0.35)));
            double[] sunDir = camera.sunDirectionOnScreen(pos[0], pos[1], pos[2]);
            items.add(new DrawItem(proj.eyeDepth(), () -> {
                // Soft planetary halo (stronger for Earth to anchor orientation immediately).
                float halo = idx == OrbitGeometry.EARTH_INDEX ? r * 3.0f : r * 2.1f;
                int haloAlpha = idx == OrbitGeometry.EARTH_INDEX ? 95 : 52;
                RadialGradientPaint glow = new RadialGradientPaint(
                        new Point2D.Double(proj.sx(), proj.sy()), halo,
                        new float[]{0f, 0.45f, 1f},
                        new Color[]{
                                new Color(base.getRed(), base.getGreen(), base.getBlue(), haloAlpha),
                                new Color(base.getRed(), base.getGreen(), base.getBlue(), haloAlpha / 3),
                                new Color(base.getRed(), base.getGreen(), base.getBlue(), 0)
                        });
                g.setPaint(glow);
                g.fill(new Ellipse2D.Double(proj.sx() - halo, proj.sy() - halo, 2 * halo, 2 * halo));

                // Sun-lit 3D shaded sphere.
                float focusX = (float) (proj.sx() + sunDir[0] * r * 0.42);
                float focusY = (float) (proj.sy() + sunDir[1] * r * 0.42);
                Color lit = blend(base, Color.WHITE, 0.42f);
                Color shade = blend(base, new Color(8, 12, 22), 0.58f);
                RadialGradientPaint sphere = new RadialGradientPaint(
                        new Point2D.Double(proj.sx(), proj.sy()), r,
                        new Point2D.Float(focusX, focusY),
                        new float[]{0f, 0.65f, 1f},
                        new Color[]{lit, base, shade}, MultipleGradientPaint.CycleMethod.NO_CYCLE);
                g.setPaint(sphere);
                g.fill(new Ellipse2D.Double(proj.sx() - r, proj.sy() - r, 2 * r, 2 * r));

                // Saturn's iconic tilted ring.
                if (idx == OrbitGeometry.SATURN_INDEX) {
                    Graphics2D rg = (Graphics2D) g.create();
                    try {
                        rg.translate(proj.sx(), proj.sy());
                        rg.rotate(Math.toRadians(-22));
                        rg.setColor(new Color(235, 214, 168, 175));
                        rg.setStroke(new BasicStroke(1.35f));
                        rg.draw(new Ellipse2D.Double(-r * 2.05, -r * 0.68, r * 4.1, r * 1.36));
                    } finally {
                        rg.dispose();
                    }
                }
            }));
        }
    }

    private void addTargetMarker(List<DrawItem> items, Graphics2D g, int w, int h,
                                 double[] pos, Color color, float radius, boolean primary) {
        var proj = camera.project(pos[0], pos[1], pos[2], w, h);
        if (proj == null) return;
        float r = (float) Math.max(radius * 0.85, Math.min(radius * 1.35, radius * Math.pow(proj.perspectiveScale(), 0.25)));
        float halo = r * (primary ? 3.5f : 2.6f);
        items.add(new DrawItem(proj.eyeDepth() - 2e-4, () -> {
            RadialGradientPaint glow = new RadialGradientPaint(
                    new Point2D.Double(proj.sx(), proj.sy()), halo,
                    new float[]{0f, 0.35f, 1f},
                    new Color[]{
                            new Color(255, 255, 255, primary ? 245 : 190),
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), primary ? 160 : 110),
                            new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)
                    });
            g.setPaint(glow);
            g.fill(new Ellipse2D.Double(proj.sx() - halo, proj.sy() - halo, 2 * halo, 2 * halo));
            g.setColor(primary ? new Color(235, 255, 252) : color);
            g.fill(new Ellipse2D.Double(proj.sx() - r * 0.68, proj.sy() - r * 0.68, r * 1.36, r * 1.36));
            if (primary) {
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 210));
                g.setStroke(new BasicStroke(1.2f));
                double ringR = r * 1.45;
                g.draw(new Ellipse2D.Double(proj.sx() - ringR, proj.sy() - ringR, 2 * ringR, 2 * ringR));
            }
        }));
    }

    private void drawSceneLabels(Graphics2D g, int w, int h, double[][] planetPos, double[] objPos, double[] alonePos) {
        Font labelFont = UiStyle.bodyFont().deriveFont(Math.max(10f, UiStyle.bodyFont().getSize2D() - 1.5f));
        g.setFont(labelFont);
        FontMetrics fm = g.getFontMetrics();
        List<Rectangle2D> placed = new ArrayList<>();

        // Always prioritize the primary object label first.
        if (objPos != null) {
            var p = camera.project(objPos[0], objPos[1], objPos[2], w, h);
            if (p != null) {
                String tag = objectLabel.isBlank() ? "Object" : objectLabel;
                drawNonOverlappingLabel(g, fm, tag, p.sx() + 10, p.sy() - 6, PRIMARY_ABOVE, placed, w, h, true);
            }
        }
        if (alonePos != null) {
            var p = camera.project(alonePos[0], alonePos[1], alonePos[2], w, h);
            if (p != null) {
                drawNonOverlappingLabel(g, fm, "1-night", p.sx() + 8, p.sy() + 11, ALONE_ABOVE, placed, w, h, false);
            }
        }

        // Earth gets priority among planets, followed by Mars/Jupiter/outer, then Venus/Mercury.
        int[] priorityOrder = {2, 3, 4, 5, 6, 7, 1, 0};
        double viewR = camera.viewRadiusAu();
        for (int idx : priorityOrder) {
            if (!shouldShowPlanet(idx, viewR)) continue;
            double[] pos = planetPos[idx];
            var p = camera.project(pos[0], pos[1], pos[2], w, h);
            if (p == null) continue;
            Color col = idx == OrbitGeometry.EARTH_INDEX ? new Color(135, 200, 255) : blend(PLANET_COLORS[idx], Color.WHITE, 0.25f);
            drawNonOverlappingLabel(g, fm, OrbitGeometry.ALL_PLANET_NAMES[idx], p.sx() + 8, p.sy() - 5, col, placed, w, h, false);
        }

        // Perihelion & nodes when uncluttered.
        Font smallFont = labelFont.deriveFont(Math.max(9.5f, labelFont.getSize2D() - 1.0f));
        g.setFont(smallFont);
        FontMetrics sfm = g.getFontMetrics();
        if (primaryPerihelion != null) {
            var p = camera.project(primaryPerihelion[0], primaryPerihelion[1], primaryPerihelion[2], w, h);
            if (p != null) {
                drawNonOverlappingLabel(g, sfm, "q", p.sx() + 7, p.sy() - 5, new Color(255, 224, 130, 215), placed, w, h, false);
            }
        }
        if (Math.abs(orbit.i()) > 1.0) {
            if (primaryAscNode != null) {
                var p = camera.project(primaryAscNode[0], primaryAscNode[1], primaryAscNode[2], w, h);
                if (p != null) {
                    drawNonOverlappingLabel(g, sfm, "☊", p.sx() + 7, p.sy() - 5, new Color(140, 235, 220, 200), placed, w, h, false);
                }
            }
            if (primaryDescNode != null) {
                var p = camera.project(primaryDescNode[0], primaryDescNode[1], primaryDescNode[2], w, h);
                if (p != null) {
                    drawNonOverlappingLabel(g, sfm, "☋", p.sx() + 7, p.sy() - 5, new Color(120, 185, 215, 190), placed, w, h, false);
                }
            }
        }

        // Ecliptic AU ring labels placed last through collision avoidance so they never overlap planet labels.
        if (showGridAndStems) {
            double maxGridAu = Math.min(100.0, Math.max(0.05, viewR * 2.2));
            double minGridAu = viewR * 0.14;
            Font gridFont = UiStyle.bodyFont().deriveFont(Math.max(9.5f, UiStyle.bodyFont().getSize2D() - 2.5f));
            g.setFont(gridFont);
            FontMetrics gfm = g.getFontMetrics();
            for (double r : GRID_RING_CANDIDATES) {
                if (r < minGridAu || r > maxGridAu) continue;
                var lp = camera.project(r * Math.cos(Math.toRadians(15)), r * Math.sin(Math.toRadians(15)), 0, w, h);
                if (lp != null) {
                    drawNonOverlappingLabel(g, gfm, formatAuRing(r), lp.sx() + 3, lp.sy() - 2,
                            new Color(135, 155, 190, 138), placed, w, h, false);
                }
            }
        }
    }

    private void drawNonOverlappingLabel(Graphics2D g, FontMetrics fm, String text, double x, double y,
                                         Color color, List<Rectangle2D> placed, int w, int h, boolean force) {
        int tw = fm.stringWidth(text);
        int th = fm.getAscent();
        if (x < 6 || x + tw > w - 6 || y - th < 6 || y > h - 6) return;
        Rectangle2D box = new Rectangle2D.Double(x - 2, y - th - 1, tw + 4, th + 4);
        if (!force) {
            for (Rectangle2D existing : placed) {
                if (existing.intersects(box)) return;
            }
        }
        placed.add(box);
        // Dark drop shadow keeps labels crisp over bright orbit curves.
        g.setColor(new Color(6, 9, 16, 200));
        g.drawString(text, (float) x + 1f, (float) y + 1f);
        g.setColor(color);
        g.drawString(text, (float) x, (float) y);
    }

    private void drawHud(Graphics2D g, int w, int h, double[] objPos, double[] earthPos, double[] alonePos, boolean forExport) {
        int pad = UiStyle.space(12);
        Font base = UiStyle.bodyFont();
        Font titleFont = base.deriveFont(Font.BOLD, base.getSize2D() + 1f);
        Font dateFont = new Font(Font.MONOSPACED, Font.BOLD, Math.max(11, Math.round(base.getSize2D())));
        Font smallFont = base.deriveFont(Math.max(10f, base.getSize2D() - 1.5f));

        String heading = (objectLabel.isBlank() ? "Preliminary orbit" : objectLabel)
                + " · " + OrbitGeometry.orbitClass(orbit)
                + (nightsCount > 1 ? " (" + nightsCount + " nights)" : " (preliminary)");
        String dateText = HUD_TIME.format(OrbitGeometry.jdToInstant(simulationJd));
        double deltaDays = simulationJd - orbit.epochJd();
        String offsetText = Math.abs(deltaDays) < 0.01 ? "at epoch"
                : String.format(Locale.US, "epoch %+.1f d", deltaDays);

        List<String> lines = new ArrayList<>();
        OrbitGeometry.Telemetry tel = OrbitGeometry.telemetry(objPos, earthPos, alonePos);
        if (tel != null) {
            String earthDist = formatEarthDistance(tel.geocentricAu());
            lines.add(String.format(Locale.US, "Sun r = %.3f AU   ·   Earth Δ = %s", tel.heliocentricAu(), earthDist));
            lines.add(String.format(Locale.US, "Elongation %.0f°   ·   Phase %.0f°", tel.solarElongationDeg(), tel.phaseAngleDeg()));
            if (tel.comparisonSeparationAu() != null) {
                lines.add("1-night vs revised Δpos = " + formatEarthDistance(tel.comparisonSeparationAu()));
            }
        } else {
            lines.add("Unbound orbit without perihelion epoch (path shown; time motion unavailable)");
        }

        // Measure HUD card cleanly.
        FontMetrics fmTitle = g.getFontMetrics(titleFont);
        FontMetrics fmDate = g.getFontMetrics(dateFont);
        FontMetrics fmSmall = g.getFontMetrics(smallFont);
        int cardW = Math.max(fmTitle.stringWidth(heading), fmDate.stringWidth(dateText + "   " + offsetText));
        for (String line : lines) cardW = Math.max(cardW, fmSmall.stringWidth(line));
        int innerPad = UiStyle.space(10);
        int lineGap = UiStyle.space(4);
        int cardH = innerPad * 2 + fmTitle.getHeight() + fmDate.getHeight() + lines.size() * fmSmall.getHeight()
                + (lines.size() + 1) * lineGap - 2;
        cardW += innerPad * 2;

        g.setColor(new Color(8, 12, 22, 178));
        g.fill(new RoundRectangle2D.Double(pad, pad, cardW, cardH, 10, 10));
        g.setColor(new Color(120, 155, 200, 58));
        g.setStroke(new BasicStroke(1.0f));
        g.draw(new RoundRectangle2D.Double(pad, pad, cardW, cardH, 10, 10));

        int x = pad + innerPad;
        int y = pad + innerPad + fmTitle.getAscent();
        g.setFont(titleFont);
        g.setColor(new Color(230, 240, 252));
        g.drawString(heading, x, y);

        y += fmTitle.getDescent() + lineGap + fmDate.getAscent();
        g.setFont(dateFont);
        g.setColor(PRIMARY_ABOVE);
        g.drawString(dateText, x, y);
        g.setFont(smallFont);
        g.setColor(new Color(170, 185, 210));
        g.drawString("  " + offsetText, x + fmDate.stringWidth(dateText), y);

        y += fmDate.getDescent() + lineGap;
        g.setFont(smallFont);
        g.setColor(new Color(195, 210, 232));
        for (String line : lines) {
            y += fmSmall.getAscent();
            g.drawString(line, x, y);
            y += fmSmall.getDescent() + lineGap;
        }

        // Bottom-left compact legend and interaction hint.
        Font legendFont = base.deriveFont(Math.max(10f, base.getSize2D() - 2f));
        g.setFont(legendFont);
        FontMetrics fmLeg = g.getFontMetrics();
        int bottomY = h - pad;
        if (!forExport) {
            g.setColor(new Color(145, 160, 188, 175));
            g.drawString("Drag: rotate · Scroll: zoom · Shift/Right-drag: pan · Double-click: reset", pad, bottomY);
            bottomY -= fmLeg.getHeight() + 3;
        }
        // Color legend line.
        int lx = pad;
        lx = drawLegendItem(g, fmLeg, lx, bottomY, PRIMARY_ABOVE, 0,
                alone != null ? "Revised (" + nightsCount + " nights)" : "Fitted orbit");
        if (alone != null && showSingleNight) {
            lx = drawLegendItem(g, fmLeg, lx + 14, bottomY, ALONE_ABOVE, 1, "This night alone");
        }
        if (observationNightPositions.length > 0) {
            lx = drawLegendItem(g, fmLeg, lx + 14, bottomY, OBS_MARKER_COLOR, 2,
                    observationNightPositions.length == 1 ? "Obs. night" : "Obs. nights (" + observationNightPositions.length + ")");
        }
        g.setColor(new Color(150, 165, 192, 190));
        g.drawString("  ·  Solid: above ecliptic, dashed: below", lx + 6, bottomY);
    }

    /** Draws one legend swatch (style 0 = solid line, 1 = dashed line, 2 = cross marker) and its label. */
    private static int drawLegendItem(Graphics2D g, FontMetrics fm, int x, int baselineY, Color color, int style, String label) {
        int midY = baselineY - fm.getAscent() / 2 + 1;
        g.setColor(color);
        int textOffset;
        if (style == 2) {
            g.setStroke(new BasicStroke(1.35f));
            int cx = x + 5;
            g.draw(new Line2D.Double(cx - 3.5, midY, cx + 3.5, midY));
            g.draw(new Line2D.Double(cx, midY - 3.5, cx, midY + 3.5));
            textOffset = 14;
        } else {
            g.setStroke(style == 1
                    ? new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 3f}, 0f)
                    : new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(x, midY, x + 16, midY));
            textOffset = 21;
        }
        g.setColor(new Color(208, 218, 236));
        g.drawString(label, x + textOffset, baselineY);
        return x + textOffset + fm.stringWidth(label);
    }

    private void drawDiamond(Graphics2D g, double cx, double cy, double r, Color color) {
        Path2D d = new Path2D.Double();
        d.moveTo(cx, cy - r);
        d.lineTo(cx + r, cy);
        d.lineTo(cx, cy + r);
        d.lineTo(cx - r, cy);
        d.closePath();
        g.setColor(color);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(d);
    }

    private void drawNodeMarker(Graphics2D g, double cx, double cy, boolean ascending) {
        g.setColor(ascending ? new Color(130, 235, 220, 210) : new Color(110, 175, 210, 190));
        g.setStroke(new BasicStroke(1.25f));
        double r = 3.2;
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        if (ascending) {
            g.fill(new Ellipse2D.Double(cx - 1.3, cy - 1.3, 2.6, 2.6));
        }
    }

    private void rebuildOrbitCaches() {
        if (orbit == null) {
            primaryPath = new double[0][];
            alonePath = new double[0][];
            samplePaths = List.of();
            primaryPerihelion = null;
            primaryAscNode = null;
            primaryDescNode = null;
            observationNightPositions = new double[0][];
            return;
        }
        primaryPath = OrbitGeometry.path(orbit, 260, orbitReachAu);
        alonePath = alone == null ? new double[0][] : OrbitGeometry.path(alone, 220, orbitReachAu);
        List<double[][]> clouds = new ArrayList<>(sampleOrbits.size());
        for (FindOrb.Orbit sample : sampleOrbits) {
            clouds.add(OrbitGeometry.path(sample, 110, orbitReachAu));
        }
        samplePaths = clouds;
        primaryPerihelion = OrbitGeometry.perihelion(orbit);
        primaryAscNode = OrbitGeometry.ascendingNodePosition(orbit, orbitReachAu);
        primaryDescNode = OrbitGeometry.descendingNodePosition(orbit, orbitReachAu);
        double[] nightJds = OrbitGeometry.observationNightJds(orbit);
        List<double[]> markers = new ArrayList<>();
        for (double jd : nightJds) {
            double[] p = OrbitGeometry.positionAt(orbit, jd);
            if (p != null) markers.add(p);
        }
        observationNightPositions = markers.toArray(new double[0][]);
    }

    private void updateCameraFocus() {
        if (orbit == null) {
            camera.setFocus(0, 0, 0);
            return;
        }
        double[] objPos = focusMode == FocusMode.OBJECT ? OrbitGeometry.positionAt(orbit, simulationJd) : null;
        double[] earthPos = focusMode == FocusMode.EARTH ? OrbitGeometry.planetPosition3D(OrbitGeometry.EARTH_INDEX, simulationJd) : null;
        applyFocusPosition(objPos, earthPos);
    }

    private void applyFocusPosition(double[] objPos, double[] earthPos) {
        if (focusMode == FocusMode.OBJECT && objPos != null) {
            camera.setFocus(objPos[0], objPos[1], objPos[2]);
        } else if (focusMode == FocusMode.EARTH && earthPos != null) {
            camera.setFocus(earthPos[0], earthPos[1], earthPos[2]);
        } else {
            camera.setFocus(0, 0, 0);
        }
    }

    private boolean shouldShowPlanet(int planet, double viewRadiusAu) {
        double a = OrbitGeometry.planetSemiMajorAxis(planet);
        // Hide inner planets only when zoomed so far out they collapse into the Sun,
        // and hide outer planets far outside the current view unless the orbit reaches them.
        if (a < viewRadiusAu * 0.025) return false;
        double maxVisible = Math.max(viewRadiusAu * 2.8, defaultFitRadiusAu * 1.15);
        return a <= maxVisible || planet <= OrbitGeometry.EARTH_INDEX;
    }

    private static double computeIdealFitRadius(FindOrb.Orbit orbit, FindOrb.Orbit alone) {
        if (orbit == null) return 2.6;
        if (orbit.bound()) {
            double aphelion = orbit.a() * (1.0 + orbit.e());
            // For NEOs inside Mars, frame tightly around Earth + the NEO aphelion; cap very long-period comets sensibly.
            double base = Math.max(1.25, aphelion * 1.15);
            if (alone != null && alone.bound()) {
                double aloneAphelion = alone.a() * (1.0 + alone.e());
                if (aloneAphelion < base * 2.2) base = Math.max(base, aloneAphelion * 1.05);
            }
            return Math.max(1.25, Math.min(34.0, base));
        }
        // Unbound (parabolic/hyperbolic): frame around perihelion, epoch position and inner Solar System.
        double reach = Math.max(2.2, orbit.q() * 3.2);
        double[] atEp = OrbitGeometry.positionAt(orbit, orbit.epochJd());
        if (atEp != null) {
            double rEp = Math.sqrt(atEp[0] * atEp[0] + atEp[1] * atEp[1] + atEp[2] * atEp[2]);
            reach = Math.max(reach, rEp * 1.3);
        }
        return Math.min(18.0, reach);
    }

    private static double computeOrbitReach(FindOrb.Orbit orbit, FindOrb.Orbit alone, double fitRadius) {
        double reach = Math.max(6.0, fitRadius * 3.5);
        if (orbit != null && orbit.bound()) {
            reach = Math.max(reach, orbit.a() * (1.0 + orbit.e()) * 1.2);
        }
        if (alone != null && alone.bound()) {
            reach = Math.max(reach, Math.min(50.0, alone.a() * (1.0 + alone.e()) * 1.2));
        }
        return Math.min(140.0, reach);
    }

    private void installMouseControls() {
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                dragLastX = e.getX();
                dragLastY = e.getY();
                boolean pan = SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e) || e.isShiftDown();
                draggingPan = pan;
                draggingOrbit = !pan && SwingUtilities.isLeftMouseButton(e);
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override public void mouseReleased(MouseEvent e) {
                draggingOrbit = false;
                draggingPan = false;
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override public void mouseDragged(MouseEvent e) {
                int dx = e.getX() - dragLastX;
                int dy = e.getY() - dragLastY;
                dragLastX = e.getX();
                dragLastY = e.getY();
                if (draggingOrbit) {
                    camera.orbitBy(-dx * 0.0085, dy * 0.0085);
                    repaint();
                } else if (draggingPan) {
                    camera.panByPixels(dx, dy, getWidth(), getHeight());
                    repaint();
                }
            }

            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    animateResetView();
                }
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) {
                double clicks = e.getPreciseWheelRotation();
                if (clicks != 0) {
                    camera.zoomBy(Math.pow(1.14, clicks));
                    repaint();
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    private static Star[] buildStarfield(int count) {
        Random rnd = new Random(0x5AACE1337L);
        Star[] stars = new Star[count];
        Color[] tints = {
                new Color(205, 224, 255),
                new Color(238, 244, 255),
                new Color(255, 245, 224),
                new Color(255, 224, 185)
        };
        for (int k = 0; k < count; k++) {
            double u = rnd.nextDouble() * 2.0 - 1.0;
            double phi = rnd.nextDouble() * 2.0 * Math.PI;
            double s = Math.sqrt(Math.max(0.0, 1.0 - u * u));
            float mag = rnd.nextFloat();
            int alpha = 36 + Math.round(mag * mag * 145);
            float radius = 0.65f + mag * mag * 1.05f;
            Color tint = tints[rnd.nextInt(tints.length)];
            stars[k] = new Star(s * Math.cos(phi), s * Math.sin(phi), u, radius,
                    new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), alpha));
        }
        return stars;
    }

    private static double depthBrightness(double perspectiveScale) {
        return Math.max(0.45, Math.min(1.18, 0.35 + 0.65 * perspectiveScale));
    }

    private static int clampByte(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static Color blend(Color a, Color b, float t) {
        float inv = 1f - t;
        return new Color(
                clampByte(Math.round(a.getRed() * inv + b.getRed() * t)),
                clampByte(Math.round(a.getGreen() * inv + b.getGreen() * t)),
                clampByte(Math.round(a.getBlue() * inv + b.getBlue() * t)));
    }

    private static boolean isInsideViewport(double x, double y, int w, int h, int margin) {
        return x >= margin && x <= w - margin && y >= margin && y <= h - margin;
    }

    private static String formatAuRing(double au) {
        if (au >= 1.0) return String.format(Locale.US, "%.0f AU", au);
        if (au >= 0.1) return String.format(Locale.US, "%.2g AU", au);
        return String.format(Locale.US, "%.3g AU", au);
    }

    private static String formatEarthDistance(double au) {
        if (au < 0.01) {
            double ld = au / OrbitGeometry.AU_PER_LUNAR_DISTANCE;
            long km = Math.round(au * OrbitGeometry.KM_PER_AU);
            return String.format(Locale.US, "%.4f AU (%.2f LD · %,d km)", au, ld, km);
        }
        if (au < 0.25) {
            double ld = au / OrbitGeometry.AU_PER_LUNAR_DISTANCE;
            return String.format(Locale.US, "%.3f AU (%.1f LD)", au, ld);
        }
        return String.format(Locale.US, "%.3f AU", au);
    }
}
