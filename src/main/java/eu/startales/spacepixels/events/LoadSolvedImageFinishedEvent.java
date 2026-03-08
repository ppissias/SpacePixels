package eu.startales.spacepixels.events;
import java.awt.image.BufferedImage;

public class LoadSolvedImageFinishedEvent {
    private final BufferedImage image;
    private final String errorMessage;

    public LoadSolvedImageFinishedEvent(BufferedImage image, String errorMessage) {
        this.image = image;
        this.errorMessage = errorMessage;
    }

    public boolean isSuccess() { return image != null; }
    public BufferedImage getImage() { return image; }
    public String getErrorMessage() { return errorMessage; }
}