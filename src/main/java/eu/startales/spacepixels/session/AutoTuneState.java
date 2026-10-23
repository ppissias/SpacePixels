package eu.startales.spacepixels.session;

import eu.startales.spacepixels.config.SpacePixelsDetectionProfile;
import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import java.time.Instant;

/** Tuning preferences and the last Auto-Tune measurement owned by one imported session. */
public final class AutoTuneState {
    public record Run(AutoTunerResult result, Instant ranAt, long elapsedSeconds, AutoTuneProfile profile) { }

    private Run run;
    private int maxCandidateFrames = SpacePixelsDetectionProfile.DEFAULT_AUTO_TUNE_MAX_CANDIDATE_FRAMES;

    public Run get() {
        return run;
    }

    public AutoTunerResult result() {
        return run == null ? null : run.result();
    }

    public Instant ranAt() {
        return run == null ? null : run.ranAt();
    }

    public AutoTuneProfile profile() {
        return run == null ? null : run.profile();
    }

    public long elapsedSeconds() {
        return run == null ? 0 : run.elapsedSeconds();
    }

    public void remember(Run run) {
        this.run = run;
    }

    public boolean isEmpty() {
        return run == null;
    }

    public void clear() {
        run = null;
    }

    public int getMaxCandidateFrames() {
        return maxCandidateFrames;
    }

    public void setMaxCandidateFrames(int frames) {
        maxCandidateFrames = SpacePixelsDetectionProfile.normalizeAutoTuneMaxCandidateFrames(frames);
    }
}
