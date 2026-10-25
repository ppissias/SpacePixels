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
}
