package eu.startales.spacepixels.events;

import java.io.File;

public class BatchConvertFinishedEvent {
    private final boolean success;
    private final String errorMessage;
    private final File generatedMonoDirectory;

    public BatchConvertFinishedEvent(boolean success, String errorMessage) {
        this(success, errorMessage, null);
    }

    public BatchConvertFinishedEvent(boolean success, String errorMessage, File generatedMonoDirectory) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.generatedMonoDirectory = generatedMonoDirectory;
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public File getGeneratedMonoDirectory() { return generatedMonoDirectory; }
}
