package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.detection.AutoTuneCandidatePoolBuilder;
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

    /** Explains the same selection/fallback rule used by the actual Auto-Tune request. */
    static String autoTune(int importedFrames, int selectedFrames) {
        if (AutoTuneCandidatePoolBuilder.usesSelectedFrames(selectedFrames)) {
            return "Auto-Tune: pool from " + selectedFrames + " selected frames";
        }
        return "Auto-Tune: pool from all " + importedFrames + " frames (selection below "
                + SpacePixelsDetectionProfile.MIN_AUTO_TUNE_MAX_CANDIDATE_FRAMES + ")";
    }

    /** What the frame selection does for Blink, Preview and Auto-Tune; {@code frame} is its first frame's number, or 0. */
    static String selectionScope(int importedFrames, int selectedFrames, int frame) {
        String blink = selectedFrames >= CommandAvailability.MIN_BLINK_FRAMES
                ? "Blink: the " + selectedFrames + " selected frames"
                : "Blink: select " + CommandAvailability.MIN_BLINK_FRAMES + " or more frames";
        return blink + " · Preview: sequence from " + (frame > 0 ? "frame " + frame : "the first frame")
                + " · " + autoTune(importedFrames, selectedFrames);
    }

    /** The caption of Blink Selected, with the number of frames once they can be blinked. */
    static String blinkCaption(int selectedFrames) {
        return selectedFrames >= CommandAvailability.MIN_BLINK_FRAMES ? "Blink Selected (" + selectedFrames + ")" : "Blink Selected";
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
