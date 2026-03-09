package eu.startales.spacepixels.events;

import java.awt.image.BufferedImage;
import java.io.File;

public class DetectionFinishedEvent {
    private final boolean success;
    private final boolean isQuickDetection;
    private final String errorMessage;

    // Payload for Quick Detection
    private final BufferedImage annotatedImage;
    private final int starCount;
    private final int streakCount;
    private final String fileName;
    private final File reportFilename;

    public DetectionFinishedEvent(File reportFilename, boolean success, boolean isQuickDetection, String errorMessage,
                                  BufferedImage annotatedImage, int starCount, int streakCount, String fileName) {
        this.success = success;
        this.isQuickDetection = isQuickDetection;
        this.errorMessage = errorMessage;
        this.annotatedImage = annotatedImage;
        this.starCount = starCount;
        this.streakCount = streakCount;
        this.fileName = fileName;
        this.reportFilename = reportFilename;
    }

    public boolean isSuccess() { return success; }
    public boolean isQuickDetection() { return isQuickDetection; }
    public String getErrorMessage() { return errorMessage; }
    public BufferedImage getAnnotatedImage() { return annotatedImage; }
    public int getStarCount() { return starCount; }
    public int getStreakCount() { return streakCount; }
    public String getFileName() { return fileName; }
    public File getReportFilename() {return reportFilename;}
}