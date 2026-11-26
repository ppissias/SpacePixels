package eu.startales.spacepixels.gui;

/**
 * Perspective 3D orbit camera for {@link OrbitCanvas3D}. Uses a gimbal-lock-free spherical basis around an arbitrary
 * target point in J2000 ecliptic AU, supports logarithmic zoom (from close NEO Earth encounters to outer Solar System
 * scales), screen-space panning, near-plane segment clipping, and smooth eased transitions between view presets.
 */
final class OrbitCamera3D {
    /** Minimum visible half-height in AU (~1.5 Lunar Distances for close NEO flybys). */
    static final double MIN_VIEW_RADIUS_AU = 0.004;
    /** Maximum visible half-height in AU (outer Solar System / long-period comets). */
    static final double MAX_VIEW_RADIUS_AU = 120.0;
    /** Vertical field of view in degrees: moderate perspective without wide-angle distortion. */
    private static final double FOV_DEGREES = 34.0;
    private static final double TAN_HALF_FOV = Math.tan(Math.toRadians(FOV_DEGREES * 0.5));
    private static final double MAX_ELEVATION = Math.PI * 0.5;
    private static final double NEAR_CLIP_FRACTION = 0.06;

    /** Default 3D camera orientation: looking down at the ecliptic from 32 degrees north. */
    static final double DEFAULT_AZIMUTH = Math.toRadians(-62.0);
    static final double DEFAULT_ELEVATION = Math.toRadians(32.0);

    /** A projected 2D screen point with camera depth (distance from eye, AU) and perspective scale factor. */
    record Projected(double sx, double sy, double eyeDepth, double perspectiveScale) { }

    /** A near-plane-clipped 2D screen segment with midpoint camera depth for painter's sorting. */
    record ProjectedSegment(double x1, double y1, double x2, double y2, double midEyeDepth, double midScale) { }

    private double azimuth = DEFAULT_AZIMUTH;
    private double elevation = DEFAULT_ELEVATION;
    private double viewRadiusAu = 2.6;
    private double panX, panY, panZ;
    private double focusX, focusY, focusZ;

    // Smooth transition state (eased cubic interpolation).
    private boolean transitioning;
    private double startAzimuth, startElevation, startLogRadius, startPanX, startPanY, startPanZ;
    private double goalAzimuth, goalElevation, goalLogRadius, goalPanX, goalPanY, goalPanZ;
    private long transitionStartNanos;
    private long transitionDurationNanos = 240_000_000L;

    // Cached orthonormal camera basis: right (screen +X), up (screen +Y), eye (from target toward camera).
    private double rx, ry, rz;
    private double ux, uy, uz;
    private double ex, ey, ez;

    OrbitCamera3D() {
        updateBasis();
    }

    /** Current camera azimuth in radians. */
    double azimuth() { return azimuth; }

    /** Current camera elevation in radians (in [-pi/2, +pi/2]). */
    double elevation() { return elevation; }

    /** Current visible half-height in AU at the camera target plane. */
    double viewRadiusAu() { return viewRadiusAu; }

    /** Distance from target to camera eye along the view axis, in AU. */
    double eyeDistanceAu() { return viewRadiusAu / TAN_HALF_FOV; }

    /** Whether a smooth view-preset transition is currently in progress. */
    boolean isTransitioning() { return transitioning; }

    /** Sets the base 3D focus point (e.g., Sun, Object, or Earth) in AU without disturbing the user's pan offset. */
    void setFocus(double x, double y, double z) {
        this.focusX = x;
        this.focusY = y;
        this.focusZ = z;
    }

    /** Sets camera orientation, zoom and pan immediately, cancelling any active transition. */
    void setImmediate(double azimuthRad, double elevationRad, double viewRadiusAu) {
        this.transitioning = false;
        this.azimuth = wrapAngle(azimuthRad);
        this.elevation = clampElevation(elevationRad);
        this.viewRadiusAu = clampRadius(viewRadiusAu);
        this.panX = 0;
        this.panY = 0;
        this.panZ = 0;
        updateBasis();
    }

    /** Starts a smooth eased transition to the given orientation, zoom and zeroed pan offset. */
    void animateTo(double targetAzimuthRad, double targetElevationRad, double targetViewRadiusAu, long nowNanos) {
        this.startAzimuth = this.azimuth;
        this.startElevation = this.elevation;
        this.startLogRadius = Math.log(this.viewRadiusAu);
        this.startPanX = this.panX;
        this.startPanY = this.panY;
        this.startPanZ = this.panZ;
        // Take the shortest angular path in azimuth.
        double deltaAz = wrapAngle(targetAzimuthRad - this.azimuth);
        this.goalAzimuth = this.azimuth + deltaAz;
        this.goalElevation = clampElevation(targetElevationRad);
        this.goalLogRadius = Math.log(clampRadius(targetViewRadiusAu));
        this.goalPanX = 0;
        this.goalPanY = 0;
        this.goalPanZ = 0;
        this.transitionStartNanos = nowNanos;
        this.transitioning = true;
    }

    /** Advances any active preset transition; returns true if the camera moved. */
    boolean stepTransition(long nowNanos) {
        if (!transitioning) return false;
        double raw = Math.min(1.0, Math.max(0.0, (nowNanos - transitionStartNanos) / (double) transitionDurationNanos));
        // Ease-out cubic for crisp, responsive feel.
        double inv = 1.0 - raw;
        double t = 1.0 - inv * inv * inv;
        this.azimuth = wrapAngle(startAzimuth + (goalAzimuth - startAzimuth) * t);
        this.elevation = clampElevation(startElevation + (goalElevation - startElevation) * t);
        this.viewRadiusAu = clampRadius(Math.exp(startLogRadius + (goalLogRadius - startLogRadius) * t));
        this.panX = startPanX + (goalPanX - startPanX) * t;
        this.panY = startPanY + (goalPanY - startPanY) * t;
        this.panZ = startPanZ + (goalPanZ - startPanZ) * t;
        updateBasis();
        if (raw >= 1.0) transitioning = false;
        return true;
    }

    /** Rotates the camera by screen-drag increments (radians). Cancels any active preset transition. */
    void orbitBy(double deltaAzimuthRad, double deltaElevationRad) {
        this.transitioning = false;
        this.azimuth = wrapAngle(this.azimuth + deltaAzimuthRad);
        this.elevation = clampElevation(this.elevation + deltaElevationRad);
        updateBasis();
    }

    /** Scales the visible radius multiplicatively ({@code factor < 1} zooms in, {@code factor > 1} zooms out). */
    void zoomBy(double factor) {
        if (!Double.isFinite(factor) || factor <= 0) return;
        this.transitioning = false;
        this.viewRadiusAu = clampRadius(this.viewRadiusAu * factor);
    }

    /** Pans the camera target in the current screen plane by {@code dxPixels, dyPixels}. */
    void panByPixels(double dxPixels, double dyPixels, int width, int height) {
        int halfMin = Math.max(1, Math.min(width, height) / 2);
        double auPerPixel = viewRadiusAu / halfMin;
        double shiftRight = -dxPixels * auPerPixel;
        double shiftUp = dyPixels * auPerPixel;
        this.transitioning = false;
        this.panX += rx * shiftRight + ux * shiftUp;
        this.panY += ry * shiftRight + uy * shiftUp;
        this.panZ += rz * shiftRight + uz * shiftUp;
    }

    /** Clears any manual pan offset while keeping focus, orientation and zoom. */
    void clearPan() {
        this.panX = 0;
        this.panY = 0;
        this.panZ = 0;
    }

    /**
     * Projects a 3D world point (J2000 ecliptic AU) into screen coordinates for a viewport of {@code width x height}.
     * Returns null if the point lies behind the near clipping plane.
     */
    Projected project(double x, double y, double z, int width, int height) {
        double vx = x - (focusX + panX);
        double vy = y - (focusY + panY);
        double vz = z - (focusZ + panZ);
        double xc = vx * rx + vy * ry + vz * rz;
        double yc = vx * ux + vy * uy + vz * uz;
        double zc = vx * ex + vy * ey + vz * ez;
        double eyeDist = eyeDistanceAu();
        double zEye = eyeDist - zc;
        if (zEye <= eyeDist * NEAR_CLIP_FRACTION) return null;
        double halfMin = Math.min(width, height) * 0.5;
        double persp = eyeDist / zEye;
        double pixelsPerAu = (halfMin / viewRadiusAu) * persp;
        return new Projected(width * 0.5 + xc * pixelsPerAu, height * 0.5 - yc * pixelsPerAu, zEye, persp);
    }

    /**
     * Projects a 3D world line segment with exact near-plane clipping so lines never invert or explode when zoomed
     * inside an orbit. Returns null if the entire segment is behind the near plane.
     */
    ProjectedSegment projectSegment(double[] a, double[] b, int width, int height) {
        double tx = focusX + panX, ty = focusY + panY, tz = focusZ + panZ;
        double vax = a[0] - tx, vay = a[1] - ty, vaz = a[2] - tz;
        double vbx = b[0] - tx, vby = b[1] - ty, vbz = b[2] - tz;
        double xa = vax * rx + vay * ry + vaz * rz;
        double ya = vax * ux + vay * uy + vaz * uz;
        double zca = vax * ex + vay * ey + vaz * ez;
        double xb = vbx * rx + vby * ry + vbz * rz;
        double yb = vbx * ux + vby * uy + vbz * uz;
        double zcb = vbx * ex + vby * ey + vbz * ez;
        double eyeDist = eyeDistanceAu();
        double zNear = eyeDist * NEAR_CLIP_FRACTION;
        double za = eyeDist - zca;
        double zb = eyeDist - zcb;
        if (za <= zNear && zb <= zNear) return null;
        if (za < zNear) {
            double frac = (zNear - za) / (zb - za);
            xa += (xb - xa) * frac;
            ya += (yb - ya) * frac;
            za = zNear;
        } else if (zb < zNear) {
            double frac = (zNear - zb) / (za - zb);
            xb += (xa - xb) * frac;
            yb += (ya - yb) * frac;
            zb = zNear;
        }
        double halfMin = Math.min(width, height) * 0.5;
        double baseScale = halfMin / viewRadiusAu;
        double pa = eyeDist / za;
        double pb = eyeDist / zb;
        double cx = width * 0.5, cy = height * 0.5;
        return new ProjectedSegment(
                cx + xa * baseScale * pa, cy - ya * baseScale * pa,
                cx + xb * baseScale * pb, cy - yb * baseScale * pb,
                0.5 * (za + zb), 0.5 * (pa + pb));
    }

    /**
     * Projects a unit direction vector on the celestial sphere (at infinity, zero parallax) onto the viewport, for
     * rendering a rotating deep-space starfield background. Returns null if the star is behind the camera.
     */
    double[] projectSkyDirection(double dx, double dy, double dz, int width, int height) {
        // Camera looks in direction (-ex, -ey, -ez).
        double forward = -(dx * ex + dy * ey + dz * ez);
        if (forward <= 0.12) return null;
        double right = dx * rx + dy * ry + dz * rz;
        double up = dx * ux + dy * uy + dz * uz;
        double halfMin = Math.min(width, height) * 0.5;
        double focal = halfMin / TAN_HALF_FOV;
        double sx = width * 0.5 + (right / forward) * focal;
        double sy = height * 0.5 - (up / forward) * focal;
        if (sx < -4 || sx > width + 4 || sy < -4 || sy > height + 4) return null;
        return new double[]{sx, sy};
    }

    /**
     * Computes the direction from the origin (Sun) toward a body in camera screen space (unit 2D vector), used to
     * orient sunlit shading on planet spheres.
     */
    double[] sunDirectionOnScreen(double bodyX, double bodyY, double bodyZ) {
        // Vector from body toward Sun is (-bodyX, -bodyY, -bodyZ).
        double sx = -bodyX * rx - bodyY * ry - bodyZ * rz;
        double sy = bodyX * ux + bodyY * uy + bodyZ * uz; // inverted Y for screen coordinates
        double len = Math.hypot(sx, sy);
        if (len < 1e-9) return new double[]{-0.5, -0.5};
        return new double[]{sx / len, sy / len};
    }

    private void updateBasis() {
        double cosAz = Math.cos(azimuth), sinAz = Math.sin(azimuth);
        double cosEl = Math.cos(elevation), sinEl = Math.sin(elevation);
        ex = cosEl * cosAz;
        ey = cosEl * sinAz;
        ez = sinEl;
        rx = -sinAz;
        ry = cosAz;
        rz = 0.0;
        ux = -sinEl * cosAz;
        uy = -sinEl * sinAz;
        uz = cosEl;
    }

    private static double clampElevation(double rad) {
        return Math.max(-MAX_ELEVATION, Math.min(MAX_ELEVATION, rad));
    }

    private static double clampRadius(double au) {
        return Math.max(MIN_VIEW_RADIUS_AU, Math.min(MAX_VIEW_RADIUS_AU, au));
    }

    private static double wrapAngle(double rad) {
        double wrapped = (rad + Math.PI) % (2.0 * Math.PI);
        if (wrapped < 0) wrapped += 2.0 * Math.PI;
        return wrapped - Math.PI;
    }
}
