package eu.startales.spacepixels.events;

public class BatchConvertFinishedEvent {
    private final boolean success;
    private final String errorMessage;

    public BatchConvertFinishedEvent(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}