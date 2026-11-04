package eu.startales.spacepixels.config;

import java.util.List;

/** Visible controls for the optional digital-tracking stage; scientific rules belong to JTransient. */
public final class FaintMoversSettings {
    public static final DetectionSetting<Boolean> ENABLE = new DetectionSetting<>(
            "enableFaintMoverSearch", "Search for faint movers", "",
            "Add an experimental digital-tracking search for point-like movers too faint in individual frames. "
                    + "Needs at least 8 kept frames with capture times in one session. Results are unverified candidates; "
                    + "the search can add several minutes. Known-object predictions are used only for comparisons.",
            false, Boolean.class, c -> c.enableFaintMoverSearch, (c, v) -> c.enableFaintMoverSearch = v);
    public static final DetectionSetting<Double> MIN_DISPLACEMENT = new DetectionSetting<>(
            "faintMoverMinDisplacementFwhm", "Slowest motion", "FWHM over the session",
            "Smallest displacement searched, in star widths over the searched session. Slower sources can disappear into the static reference.",
            false, Double.class, c -> c.faintMoverMinDisplacementFwhm, (c, v) -> c.faintMoverMinDisplacementFwhm = v);
    public static final DetectionSetting<Double> MAX_SPEED = new DetectionSetting<>(
            "faintMoverMaxSpeedPixelsPerMinute", "Fastest motion", "",
            "Largest motion searched, displayed as FWHM over the imported session using its timing span and an assumed seeing width. "
                    + "This displacement is an estimate; the stored maximum remains pixels per minute. Pixel and angular rates are shown below. "
                    + "Without usable session times the control shows its stored px/min value. Cost grows approximately with the square of that speed.",
            false, Double.class, c -> c.faintMoverMaxSpeedPixelsPerMinute, (c, v) -> c.faintMoverMaxSpeedPixelsPerMinute = v);
    public static final DetectionSetting<Double> MIN_SCORE = new DetectionSetting<>(
            "faintMoverMinScore", "Minimum score", "",
            "Higher is stricter. This is an empirical stacked-signal score, not a calibrated significance or an identity probability. "
                    + "Stellar residuals and other artifacts can still appear.",
            false, Double.class, c -> c.faintMoverMinScore, (c, v) -> c.faintMoverMinScore = v);
    public static final DetectionSetting<Double> TIME_LIMIT = new DetectionSetting<>(
            "faintMoverMaxSeconds", "Time limit (minutes)", "",
            "Maximum additional search time, displayed in minutes and stored in seconds. A stopped search reports its partial coverage; "
                    + "cancelling the whole job follows normal cancellation.",
            false, Double.class, c -> c.faintMoverMaxSeconds, (c, v) -> c.faintMoverMaxSeconds = v);

    /** Prevents construction of the fixed definitions. */
    private FaintMoversSettings() { }

    /** Returns all bindings in display order; Auto-Tune preserves these values. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE, MIN_DISPLACEMENT, MAX_SPEED, MIN_SCORE, TIME_LIMIT);
    }
}
