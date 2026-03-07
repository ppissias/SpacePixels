package spv.tasks;

import com.google.common.eventbus.EventBus;
import io.github.ppissias.astrolib.PlateSolveResult;
import spv.events.LoadSolvedImageFinishedEvent;
import spv.events.LoadSolvedImageStartedEvent;
import spv.gui.ApplicationWindow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.util.logging.Level;

public class LoadSolvedImageTask implements Runnable {
    private final EventBus eventBus;
    private final PlateSolveResult result;

    public LoadSolvedImageTask(EventBus eventBus, PlateSolveResult result) {
        this.eventBus = eventBus;
        this.result = result;
    }

    @Override
    public void run() {
        eventBus.post(new LoadSolvedImageStartedEvent());

        try {
            String annotatedImageLink = result.getSolveInformation().get("annotated_image_link");
            URL annotatedImageURL;

            switch (result.getSolveInformation().get("source")) {
                case "astrometry.net":
                    annotatedImageURL = new URL(annotatedImageLink);
                    break;
                case "astap":
                    annotatedImageURL = new File(annotatedImageLink).toURI().toURL();
                    break;
                default:
                    eventBus.post(new LoadSolvedImageFinishedEvent(null, "Cannot understand solve source."));
                    return;
            }

            ApplicationWindow.logger.info("Loading image: " + annotatedImageURL.toString());
            BufferedImage image = ImageIO.read(annotatedImageURL);

            if (image == null) {
                eventBus.post(new LoadSolvedImageFinishedEvent(null, "Cannot show image: " + annotatedImageURL.toString()));
            } else {
                // Success! Pass the fully loaded image back
                eventBus.post(new LoadSolvedImageFinishedEvent(image, null));
            }

        } catch (Exception ex) {
            ApplicationWindow.logger.log(Level.SEVERE, "Error loading solved image", ex);
            eventBus.post(new LoadSolvedImageFinishedEvent(null, ex.getMessage()));
        }
    }
}