package eu.startales.spacepixels.session;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import eu.startales.spacepixels.util.AutoTunerRunner;
import io.github.ppissias.jtransient.engine.JTransientAutoTuner;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

/** Tuning preferences and measurements owned by one imported session. */
public final class AutoTuneState {
    public record Run(JTransientAutoTuner.AutoTunerResult result, Instant ranAt, long elapsedSeconds,
                      JTransientAutoTuner.AutoTuneProfile profile) { }

    private final Map<AutoTunerRunner.Algorithm, Run> runs = new EnumMap<>(AutoTunerRunner.Algorithm.class);
    private int maxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;

    public Run get(AutoTunerRunner.Algorithm algorithm) {
        return runs.get(algorithm);
    }

    public JTransientAutoTuner.AutoTunerResult result(AutoTunerRunner.Algorithm algorithm) {
        Run run = get(algorithm);
        return run == null ? null : run.result();
    }

    public Instant ranAt(AutoTunerRunner.Algorithm algorithm) {
        Run run = get(algorithm);
        return run == null ? null : run.ranAt();
    }

    public JTransientAutoTuner.AutoTuneProfile profile(AutoTunerRunner.Algorithm algorithm) {
        Run run = get(algorithm);
        return run == null ? null : run.profile();
    }

    public JTransientAutoTuner.AutoTuneProfile profileOrDefault(AutoTunerRunner.Algorithm algorithm,
                                                              JTransientAutoTuner.AutoTuneProfile fallback) {
        JTransientAutoTuner.AutoTuneProfile profile = profile(algorithm);
        return profile == null ? fallback : profile;
    }

    public long elapsedSeconds(AutoTunerRunner.Algorithm algorithm) {
        Run run = get(algorithm);
        return run == null ? 0 : run.elapsedSeconds();
    }

    public void remember(AutoTunerRunner.Algorithm algorithm, Run run) {
        runs.put(algorithm, run);
    }

    public boolean isEmpty() {
        return runs.isEmpty();
    }

    public void clear() {
        runs.clear();
    }

    public int getMaxCandidateFrames() {
        return maxCandidateFrames;
    }

    public void setMaxCandidateFrames(int frames) {
        maxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(frames);
    }
}
