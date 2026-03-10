package eu.startales.spacepixels.events;

public class BlinkFinishedEvent {
    private final boolean success;
    private final String errorMessage;

    public BlinkFinishedEvent(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}