package eu.startales.spacepixels.events;

import java.awt.image.BufferedImage;

public class TuningPreviewFinishedEvent {
    private final boolean success;
    private final BufferedImage previewImage;
    private final String windowTitle;
    private final String errorMessage;

    public TuningPreviewFinishedEvent(boolean success, BufferedImage previewImage, String windowTitle, String errorMessage) {
        this.success = success;
        this.previewImage = previewImage;
        this.windowTitle = windowTitle;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return success; }
    public BufferedImage getPreviewImage() { return previewImage; }
    public String getWindowTitle() { return windowTitle; }
    public String getErrorMessage() { return errorMessage; }
}