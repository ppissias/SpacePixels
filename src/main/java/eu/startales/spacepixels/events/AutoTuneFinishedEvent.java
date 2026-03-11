package eu.startales.spacepixels.events;

import io.github.ppissias.jtransient.engine.JTransientAutoTuner.AutoTunerResult;

public class AutoTuneFinishedEvent {
    private final boolean success;
    private final String message;
    private final AutoTunerResult result;

    public AutoTuneFinishedEvent(boolean success, String message, AutoTunerResult result) {
        this.success = success;
        this.message = message;
        this.result = result;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public AutoTunerResult getResult() {
        return result;
    }
}