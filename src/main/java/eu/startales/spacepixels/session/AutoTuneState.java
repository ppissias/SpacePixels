package eu.startales.spacepixels.session;

import io.github.ppissias.jtransient.engine.AutoTuneProfile;
import io.github.ppissias.jtransient.engine.AutoTunerResult;
import java.time.Instant;

/** The last Auto-Tune measurement owned by one imported session. */
public final class AutoTuneState {
    /** {@code excludedAreas} are the areas the run measured without; empty for none. */
    public record Run(AutoTunerResult result, Instant ranAt, long elapsedSeconds, AutoTuneProfile profile,
                      java.util.List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas) {
        public Run {
            excludedAreas = excludedAreas == null ? java.util.List.of() : java.util.List.copyOf(excludedAreas);
        }

        /** A run measured without excluded areas. */
        public Run(AutoTunerResult result, Instant ranAt, long elapsedSeconds, AutoTuneProfile profile) {
            this(result, ranAt, elapsedSeconds, profile, java.util.List.of());
        }
    }

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

    /** The excluded areas the run measured without; empty without a run or areas. */
    public java.util.List<io.github.ppissias.jtransient.engine.ExcludedArea> excludedAreas() {
        return run == null ? java.util.List.of() : run.excludedAreas();
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
