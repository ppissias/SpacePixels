package eu.startales.spacepixels.events;

import eu.startales.spacepixels.util.FitsFileInformation;
import eu.startales.spacepixels.util.ImagePreprocessing;

public class FitsImportFinishedEvent {
    private final boolean success;
    private final String errorMessage;
    private final ImagePreprocessing imagePreProcessing;
    private final FitsFileInformation[] filesInformation;

    public FitsImportFinishedEvent(boolean success, String errorMessage,
                                   ImagePreprocessing imagePreProcessing,
                                   FitsFileInformation[] filesInformation) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.imagePreProcessing = imagePreProcessing;
        this.filesInformation = filesInformation;
    }

    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
    public ImagePreprocessing getImagePreProcessing() { return imagePreProcessing; }
    public FitsFileInformation[] getFilesInformation() { return filesInformation; }
}