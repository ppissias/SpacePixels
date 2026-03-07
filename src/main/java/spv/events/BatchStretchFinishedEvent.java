package spv.events;

public class BatchStretchFinishedEvent {
    private final boolean success;
    private final String errorMessage;

    public BatchStretchFinishedEvent(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}