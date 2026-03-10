package eu.startales.spacepixels.events;

import java.awt.image.BufferedImage;

public class BlinkFrameUpdateEvent {
    private final BufferedImage image;

    public BlinkFrameUpdateEvent(BufferedImage image) {
        this.image = image;
    }

    public BufferedImage getImage() {
        return image;
    }
}