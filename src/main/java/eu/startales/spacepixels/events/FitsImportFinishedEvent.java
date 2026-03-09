package eu.startales.spacepixels.events;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImageProcessing;

public class FitsImportFinishedEvent {
    private final boolean success;
    private final String errorMessage;
    private final ImageProcessing imagePreProcessing;
    private final FitsFileInformation[] filesInformation;

    public FitsImportFinishedEvent(boolean success, String errorMessage,
                                   ImageProcessing imagePreProcessing,
                                   FitsFileInformation[] filesInformation) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.imagePreProcessing = imagePreProcessing;
        this.filesInformation = filesInformation;
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public ImageProcessing getImagePreProcessing() { return imagePreProcessing; }
    public FitsFileInformation[] getFilesInformation() { return filesInformation; }
}