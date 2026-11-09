package eu.startales.spacepixels.gui;

import io.github.ppissias.jtransient.config.DetectionConfig;
import java.util.ArrayList;
import java.util.List;

/** Describes existing frame-selection and analysis policies without reading controls or running processing. */
final class AnalysisScopeText {
    /** Prevents constructing this presentation utility. */
    private AnalysisScopeText() {}

    /** Lists configured analyses, keeping common-motion rejection a filter and photometry readiness a runtime check. */
    static String analyses(DetectionConfig config) {
        List<String> enabled = new ArrayList<>();
        enabled.add("moving objects and streaks");
        if (config.enableSlowMoverDetection) enabled.add("slow movers");
        if (config.enableFaintMoverSearch) enabled.add("faint movers (experimental)");
        if (config.enableAnomalyRescue) enabled.add("anomaly rescue");
        if (config.enableResidualTransientAnalysis && config.enableLocalRescueCandidates) enabled.add("local rescue");
        if (config.enableResidualTransientAnalysis && config.enableLocalActivityClusters) enabled.add("activity clusters");
        if (config.enableVariableStarDetection) enabled.add("variable-star photometry (readiness checked during run)");
        return String.join(", ", enabled);
    }

    /** The Auto-Tune scope line. The pool is taken from the imported sequence, not the table selection. */
    static String autoTune(int importedFrames) {
        return "Auto-Tune: pool from all " + importedFrames + " frames";
    }

    /** What the selection does for Blink and Preview. {@code frame} is the first selected frame's number, or 0. */
    static String selectionScope(int importedFrames, int selectedFrames, int frame) {
        String blink = CommandAvailability.blinksSelection(selectedFrames)
                ? "Blink: the " + selectedFrames + " selected frames"
                : importedFrames >= CommandAvailability.MIN_BLINK_FRAMES ? "Blink: all " + importedFrames + " frames"
                : "Blink: needs " + CommandAvailability.MIN_BLINK_FRAMES + " or more frames";
        return blink + " · Preview: sequence from " + (frame > 0 ? "frame " + frame : "the first frame")
                + " · " + autoTune(importedFrames);
    }

    /**
     * The caption of the blink command: Blink Selected with the count when it plays the selection, Blink All with the
     * session's count when it plays every frame, and plain Blink Selected when there is nothing to blink.
     */
    static String blinkCaption(int importedFrames, int selectedFrames) {
        if (CommandAvailability.blinksSelection(selectedFrames)) {
            return "Blink Selected (" + selectedFrames + ")";
        }
        return importedFrames >= CommandAvailability.MIN_BLINK_FRAMES ? "Blink All (" + importedFrames + ")" : "Blink Selected";
    }

    /** The caption of Preview Frame, naming the frame it starts on; {@code frame} is 0 without a selection. */
    static String previewCaption(int frame) {
        return frame > 0 ? "Preview Frame " + frame : "Preview Frame";
    }

    /** The caption of the plate-solve command, naming the one frame it solves, also among several selected. */
    static String plateSolveCaption(int frame) {
        return frame > 0 ? "Plate Solve Frame " + frame : "Plate Solve Selected";
    }

    /**
     * The settings in use and the saved auto-tune, as HTML: one line when they are the same run, so its date shows once.
     *
     * @param summary the settings in use, ending in " · run " and the date when they come from an auto-tune
     * @param saved   the saved auto-tune line, or null
     */
    static String settingsNote(String summary, String saved) {
        if (saved == null) {
            return summary;
        }
        int run = summary.lastIndexOf(RUN_MARK);
        if (run >= 0 && saved.startsWith(SAVED_MARK)) {
            String savedRun = saved.substring(SAVED_MARK.length());
            if (savedRun.startsWith(summary.substring(run + RUN_MARK.length()))) {
                return summary.substring(0, run + RUN_MARK.length()) + savedRun;
            }
        }
        return summary + "<br>" + saved;
    }

    private static final String RUN_MARK = " · run ";
    private static final String SAVED_MARK = "Auto-tune saved: run at ";
}
