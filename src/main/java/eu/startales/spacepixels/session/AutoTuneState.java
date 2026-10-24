package eu.startales.spacepixels.session;

import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import java.time.Instant;

/** The last Auto-Tune measurement owned by one imported session. */
public final class AutoTuneState {
    public record Run(AutoTunerResult result, Instant ranAt, long elapsedSeconds, AutoTuneProfile profile) { }

    private Run run;

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
}
