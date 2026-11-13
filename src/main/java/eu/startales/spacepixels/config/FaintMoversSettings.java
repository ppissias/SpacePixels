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
            "faintMoverMinDisplacementFwhm", "Slowest motion", "",
            "Smallest rate searched, in arcsec/min when WCS and session times are available. The lower bound is 2 star widths over the session; "
                    + "slower sources can disappear into the static reference. Before a run the seeing and retained time span are estimated. "
                    + "Without WCS the rate uses px/min; without timing it uses FWHM/session. The report gives the measured search domain.",
            false, Double.class, c -> c.faintMoverMinDisplacementFwhm, (c, v) -> c.faintMoverMinDisplacementFwhm = v);
    public static final DetectionSetting<Double> MAX_SPEED = new DetectionSetting<>(
            "faintMoverMaxSpeedPixelsPerMinute", "Fastest motion", "",
            "Largest rate searched, in arcsec/min when WCS is available, otherwise px/min. When it is not above the slowest rate, the run skips the search. "
                    + "The profile preserves the pixel rate; the default is 1 px/min converted through this field's WCS. "
                    + "Cost grows approximately with the square of the maximum speed.",
            false, Double.class, c -> c.faintMoverMaxSpeedPixelsPerMinute, (c, v) -> c.faintMoverMaxSpeedPixelsPerMinute = v);
    public static final DetectionSetting<Double> MIN_SCORE = new DetectionSetting<>(
            "faintMoverMinScore", "Minimum score", "",
            "Higher is stricter. This is an empirical stacked-signal score, not a calibrated significance or an identity probability. "
                    + "Stellar residuals and other artifacts can still appear.",
            false, Double.class, c -> c.faintMoverMinScore, (c, v) -> c.faintMoverMinScore = v);
    public static final DetectionSetting<Boolean> FRAME_LIMIT = new DetectionSetting<>(
            "enableFaintMoverFrameLimit", "Reject few-frame candidates", "",
            "Reject a candidate whose score comes from a few frames: no frame may add more than the path's typical frame "
                    + "plus three frame noises, and the score must still reach the minimum. A real faint mover is about equally "
                    + "faint in every frame; faint star tails that a run of frames shares, for example from wind or a mount "
                    + "movement, are not. On by default; switch it off only to compare.",
            true, Boolean.class, c -> c.enableFaintMoverFrameLimit, (c, v) -> c.enableFaintMoverFrameLimit = v);
    public static final DetectionSetting<Integer> SEARCH_SPEED = new DetectionSetting<>(
            "faintMoverSearchSpeed", "Search speed", "",
            "How much cheaper the first pass, which tries every motion, may be. Normal adds every pixel of every frame. "
                    + "Fast (the default) adds 2 x 2 pixels when stars are wide and consecutive frames close in time, where "
                    + "little signal is lost; it can miss about one candidate in 50 to 100 close to the limits. Faster adds them "
                    + "also for narrower stars and wider time steps; it can miss more candidates close to the limits. Every "
                    + "candidate is still scored and checked on all frames at full resolution, so scores mean the same.",
            false, Integer.class, c -> c.faintMoverSearchSpeed, (c, v) -> c.faintMoverSearchSpeed = v);
    public static final DetectionSetting<Double> TIME_LIMIT = new DetectionSetting<>(
            "faintMoverMaxSeconds", "Time limit (minutes)", "",
            "Maximum additional search time, displayed in minutes and stored in seconds. A stopped search reports its partial coverage; "
                    + "cancelling the whole job follows normal cancellation.",
            false, Double.class, c -> c.faintMoverMaxSeconds, (c, v) -> c.faintMoverMaxSeconds = v);

    /** Prevents construction of the fixed definitions. */
    private FaintMoversSettings() { }

    /** Returns all bindings in display order; Auto-Tune preserves these values. */
    public static List<DetectionSetting<?>> all() {
        return List.of(ENABLE, MIN_DISPLACEMENT, MAX_SPEED, MIN_SCORE, FRAME_LIMIT, SEARCH_SPEED, TIME_LIMIT);
    }
}
