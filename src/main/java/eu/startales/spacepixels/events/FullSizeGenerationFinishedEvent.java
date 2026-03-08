package eu.startales.spacepixels.events;

import java.awt.image.BufferedImage;

public class FullSizeGenerationFinishedEvent {
    private final BufferedImage fullSizeImage;
    private final boolean success;
    private final String errorMessage;

    public FullSizeGenerationFinishedEvent(BufferedImage fullSizeImage, boolean success, String errorMessage) {
        this.fullSizeImage = fullSizeImage;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public BufferedImage getFullSizeImage() { return fullSizeImage; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}