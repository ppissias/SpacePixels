package spv.events;

import java.awt.image.BufferedImage;

public class PreviewGenerationFinishedEvent {
    private final BufferedImage originalImage;
    private final BufferedImage stretchedImage;
    private final boolean success;

    public PreviewGenerationFinishedEvent(BufferedImage originalImage, BufferedImage stretchedImage, boolean success) {
        this.originalImage = originalImage;
        this.stretchedImage = stretchedImage;
        this.success = success;
    }

    public BufferedImage getOriginalImage() { return originalImage; }
    public BufferedImage getStretchedImage() { return stretchedImage; }
    public boolean isSuccess() { return success; }
}