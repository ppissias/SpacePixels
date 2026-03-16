package eu.startales.spacepixels.events;

public class EngineProgressUpdateEvent {
    private final int percentage;
    private final String message;

    public EngineProgressUpdateEvent(int percentage, String message) {
        this.percentage = percentage;
        this.message = message;
    }

    public int getPercentage() {
        return percentage;
    }

    public String getMessage() {
        return message;
    }
}