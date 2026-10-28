package eu.startales.spacepixels.fits;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import eu.startales.spacepixels.util.DebugOutput;

/**
 * Interactive and strict headless metadata loading; decisions are supplied separately from file operations. The
 * caller owns it for one import attempt and closes it to stop its metadata-reading workers.
 */
public final class FitsImportService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(FitsImportService.class.getName());
    private static final DateTimeFormatter TRACE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'").withZone(ZoneOffset.UTC);
    private final File directory;
    private final FitsBatchService batch;
    private final FitsImportInteraction interaction;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));

    /**
     * Reads {@code directory}. With {@code interaction} it may offer conversions and redirect the import
     * ({@link RedirectImportException}); null rejects input that is not detection-ready, without dialogs or copies.
     */
    public FitsImportService(File directory, FitsImportInteraction interaction) {
        this(directory, new FitsBatchService(directory, new FitsVisualizationRenderer()), interaction);
    }

    /** Owns metadata-reading workers; returned frame arrays belong to the caller and are never retained here. */
    public FitsImportService(File directory, FitsBatchService batch, FitsImportInteraction interaction) {
        this.directory = directory;
        this.batch = batch;
        this.interaction = interaction;
    }

    @Override public void close() { executor.shutdown(); }

    /** Reads and validates a fresh frame array; null interaction requires detection readiness before sequence checks. */
    public FitsFileInformation[] readFiles() throws Exception {
        File[] files = FitsInput.list(directory);
        FitsInput.Input[] inputs = FitsInput.inspect(files, executor, interaction == null);
        if (interaction == null) {
            return checkCalibration(metadata(inputs, "Headless import"));
        }
        FitsInput.Input reference = inputs[0];
        if (reference.compressed()) {
            FitsInput.FitsFormat compressedFormat = reference.format();
            boolean conversionRequired = Math.abs(reference.bitpix()) == 32
                    || (reference.bitpix() == 16 && !reference.unsigned16Bit());
            if (conversionRequired && compressedFormat != FitsInput.FitsFormat.UNSUPPORTED) {
                boolean color = reference.axes().length == 3;
                int choice = interaction.confirm(
                        "These compressed images also need conversion to standard unsigned 16-bit pixels.\n\n"
                                + "Would you like to decompress and convert this sequence in one operation?\n"
                                + (color ? "Both 16-bit luminance and color copies will be saved."
                                         : "Uncompressed 16-bit Mono copies will be saved.")
                                + "\nThe original compressed files will be kept.",
                        "Combined FITS Preparation");
                if (choice == FitsImportInteraction.YES) {
                    return standardizeAndOfferToLoad(files, color);
                }
                return new FitsFileInformation[0];
            }
            int choice = interaction.confirm(
                    "These images appear to be compressed (.fz format).\nSpacePixels requires uncompressed FITS files for optimal tracking and plate-solving.\n\n"
                            + "Would you like to automatically decompress this sequence into a new directory?",
                    "Decompression Required");
            if (choice == FitsImportInteraction.YES) {
                File newDir;
                try (FitsImportInteraction.Progress progress = interaction.progress("Decompressing FITS files...")) {
                    newDir = batch.batchDecompress(files, progress);
                }
                int loadChoice = interaction.confirm(
                        "Decompression Complete!\n\nWould you like to automatically load the new uncompressed directory?", "Success");
                if (loadChoice == FitsImportInteraction.YES && newDir != null) {
                    throw new RedirectImportException(newDir);
                }
            }
            return new FitsFileInformation[0];
        }

        // --- THE GATEKEEPER: Check the format of the first file ---
        FitsInput.FitsFormat format = reference.format();

        if ((format == FitsInput.FitsFormat.MONO_16BIT || format == FitsInput.FitsFormat.COLOR_16BIT)
                && !reference.unsigned16Bit()) {
            boolean color = format == FitsInput.FitsFormat.COLOR_16BIT;
            int choice = interaction.confirm(
                    dialogParagraphs("These 16-bit images store their pixels with BZERO " + headerNumber(reference.bzero())
                                    + " and BSCALE " + headerNumber(reference.bscale()) + ". "
                                    + "SpacePixels reads 16-bit images as unsigned values (BZERO 32768, BSCALE 1), so it would read these "
                                    + "at the wrong brightness level, which breaks the saturation, padding and void checks.",
                            color ? "Would you like to extract the Luminance (16-bit Mono) and save a standard 16-bit Color version?"
                                  : "Would you like to convert this sequence to standard 16-bit images in a new folder?"),
                    "Format Conversion Required");

            if (choice == FitsImportInteraction.YES) {
                LOGGER.info("Starting signed/scaled 16-bit to standard 16-bit conversion...");
                return standardizeAndOfferToLoad(files, color);
            }

            LOGGER.info("User declined 16-bit standardization. Clearing the active import.");
            return new FitsFileInformation[0];

        } else if (format == FitsInput.FitsFormat.MONO_32BIT_FLOAT || format == FitsInput.FitsFormat.MONO_32BIT_INT) {
            int choice = interaction.confirm(
                    "These images appear to be 32-bit.\nSpacePixels' transient detection engine is highly optimized for 16-bit monochrome files.\n\n" +
                            "Would you like to automatically standardize this sequence to 16-bit Mono?",
                    "Format Conversion Required");

            if (choice == FitsImportInteraction.YES) {
                LOGGER.info("Starting 32-bit Mono to 16-bit Mono conversion...");
                return standardizeAndOfferToLoad(files, false);
            }

            LOGGER.info("User declined 32-bit mono standardization. Clearing the active import.");
            return new FitsFileInformation[0];

        } else if (format == FitsInput.FitsFormat.COLOR_32BIT_FLOAT || format == FitsInput.FitsFormat.COLOR_32BIT_INT) {
            int choice = interaction.confirm(
                    "These images are 32-bit Color.\nSpacePixels' transient detection engine requires 16-bit monochrome files.\n\n" +
                            "Would you like to extract the Luminance (16-bit Mono) and save a 16-bit Color version?",
                    "Color Format Conversion Required");

            if (choice == FitsImportInteraction.YES) {
                LOGGER.info("Starting 32-bit Color to 16-bit Mono/Color conversion...");
                return standardizeAndOfferToLoad(files, true);
            }

            LOGGER.info("User declined 32-bit color standardization. Clearing the active import.");
            return new FitsFileInformation[0];
        } else if (format == FitsInput.FitsFormat.UNSUPPORTED) {
            interaction.error( "Unsupported FITS format detected. SpacePixels supports 16-bit and 32-bit FITS files.", "Import Error");
            return new FitsFileInformation[0];
        }


        return checkCalibration(metadata(inputs, "Interactive import"));
    }

    /** Returns validated metadata in frame order without keeping it in the importer. */
    private static FitsFileInformation[] metadata(FitsInput.Input[] inputs, String stage) throws IOException {
        FitsFileInformation[] metadata = Arrays.stream(inputs)
                .map(FitsInput.Input::information).toArray(FitsFileInformation[]::new);
        FitsInput.validateAndSort(metadata);
        DebugOutput.println("Validated and loaded metadata for " + metadata.length + " FITS files.");
        logFitsTimestampDiagnostics(stage, metadata);
        return metadata;
    }

    /** Checks sample pixels on the import worker; dismissal declines import, while unreadable samples fail it. */
    private FitsFileInformation[] checkCalibration(FitsFileInformation[] metadata) {
        if (metadata == null || metadata.length == 0) {
            return metadata;
        }
        FrameCalibrationInspector.InspectionResult inspection = FrameCalibrationInspector.inspect(metadata);
        if (inspection.hasWarning()) {
            if (interaction != null) {
                String[] options = {"Cancel Import (Recommended)", "Proceed Anyway"};
                int choice = interaction.choose(inspection.warningDialogText(), "Dataset Quality Warning", options);
                if (choice != 1) {
                    LOGGER.warning("Import cancelled by user due to dataset quality warning: " + inspection.summary());
                    return new FitsFileInformation[0];
                }
                LOGGER.info("User chose to proceed with import despite dataset quality warning: " + inspection.summary());
            } else {
                LOGGER.warning("Dataset Quality Warning: " + inspection.summary());
                DebugOutput.println("Dataset Quality Warning: " + inspection.summary());
            }
        }
        return metadata;
    }

    private static String headerNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    /** Writes final mono/color copies directly from either compressed or plain images, then offers import. */
    private FitsFileInformation[] standardizeAndOfferToLoad(File[] files, boolean isColor) throws Exception {
        ImageScaling.Summary scan;
        try (FitsImportInteraction.Progress progress = interaction.progress("Checking pixel values...")) {
            scan = ImageScaling.inspect(files, percent -> progress.onProgressUpdate(percent, "Inspecting sequence intensity range..."));
        }
        boolean keepNegative = true;
        if (scan.negativePixels() > 0) {
            int choice = interaction.choose("This sequence contains values below zero. Add a common offset to preserve the background and its noise.\n"
                    + "Setting negative values to zero discards the low side of that noise; registration margins remain zero in either case.",
                    "Pixel Values Below Zero", new String[]{"Add an Offset (Recommended)", "Set to 0", "Cancel"});
            if (choice < 0 || choice > 1) return new FitsFileInformation[0];
            keepNegative = choice == 0;
        }
        ImageScaling.Plan scaling;
        try { scaling = ImageScaling.plan(scan, keepNegative, ImageScaling.interactive(interaction)); }
        catch (ImageScaling.DecisionCancelledException declined) { return new FitsFileInformation[0]; }
        File newDir;
        try (FitsImportInteraction.Progress progress = interaction.progress("Converting to standard 16-bit...")) {
            newDir = batch.batchStandardizeTo16Bit(files, isColor, scaling, progress);
        }

        int loadChoice = interaction.confirm(
                isColor ? "Conversion Complete!\n\nWould you like to automatically load the newly created 16-bit Mono directory to run detections?"
                        : "Conversion Complete!\n\nWould you like to automatically load the newly created 16-bit directory?",
                "Success");
        if (loadChoice == FitsImportInteraction.YES && newDir != null) {
            throw new RedirectImportException(newDir);
        }
        return new FitsFileInformation[0]; // Stop the current import
    }

    /**
     * Offers to copy the largest exposure group into a subfolder and returns it, or null when declined or when there
     * is no interaction to ask.
     */
    public static File offerToImportLargestExposureGroup(MixedExposureException mixed, FitsImportInteraction interaction)
            throws IOException {
        if (interaction == null) {
            return null;
        }
        ExposureGroup largest = mixed.getLargestGroup();
        String[] options = {"Import the " + largest.describe(), "Cancel"};
        int choice = interaction.choose(mixedExposureQuestion(mixed), "Different Exposure Times", options);
        LOGGER.info(mixed.getMessage() + " Choice: " + (choice == 0 ? "import the largest group" : "cancel"));
        if (choice != 0) {
            return null;
        }

        try (FitsImportInteraction.Progress progress = interaction.progress("Copying images...")) {
            return largest.copyIntoSubfolder(percent -> progress.onProgressUpdate(percent, "Copying the " + largest.describe() + "..."));
        }
    }

    static String mixedExposureQuestion(MixedExposureException mixed) {
        ExposureGroup largest = mixed.getLargestGroup();
        StringBuilder groups = new StringBuilder();
        for (String group : MixedExposureException.describeGroups(mixed.getGroups())) {
            groups.append("<br>&bull; ").append(htmlText(group));
        }
        return dialogParagraphs(
                "These " + mixed.getImageCount() + " images do not all have the same exposure time:" + groups,
                "SpacePixels needs one exposure time for the whole sequence: the brightness of the stars and the sky, and "
                        + "the length of trails, change with the exposure, and the detection compares the frames with each other.",
                "Would you like to import only the " + largest.describe() + "? They are copied into the subfolder <b>"
                        + htmlText(largest.getFolderName()) + "</b>, and the original folder stays as it is.");
    }

    private static String htmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String dialogParagraphs(String... paragraphs) {
        StringBuilder html = new StringBuilder("<html><body style='width: 430px'>");
        for (int fileIndex = 0; fileIndex < paragraphs.length; fileIndex++) {
            html.append(fileIndex == 0 ? "<p>" : "<p style='margin-top: 8px'>").append(paragraphs[fileIndex]).append("</p>");
        }
        return html.append("</body></html>").toString();
    }

    private static void logFitsTimestampDiagnostics(String stageLabel, FitsFileInformation[] filesInfo) {
        if (!DebugOutput.isEnabled()) {
            return;
        }
        if (filesInfo == null) {
            DebugOutput.println("\n--- " + stageLabel + " FITS Timestamp Diagnostics ---");
            DebugOutput.println("No FITS metadata loaded.");
            return;
        }

        int validTimestamps = 0;
        int displayWithoutTimestamp = 0;

        DebugOutput.println("\n--- " + stageLabel + " FITS Timestamp Diagnostics ---");
        for (int fileIndex = 0; fileIndex < filesInfo.length; fileIndex++) {
            FitsFileInformation info = filesInfo[fileIndex];
            long timestamp = info.getObservationTimestamp();
            if (timestamp > 0L) {
                validTimestamps++;
            }
            if (info.hasDisplayableObservationDateWithoutUsableTimestamp()) {
                displayWithoutTimestamp++;
            }

            DebugOutput.println(String.format(
                    Locale.US,
                    "[%03d] file='%s' | parsedTimestamp=%d | parsedUtc='%s' | %s",
                    fileIndex,
                    info.getFileName(),
                    timestamp,
                    formatTraceTimestamp(timestamp),
                    info.getObservationTimestampDiagnostics()));
        }
        DebugOutput.println(String.format(
                Locale.US,
                "Timestamp diagnostics summary: %d/%d files produced a usable timestamp; %d files displayed a date/time but would pass no usable timestamp to JTransient.",
                validTimestamps,
                filesInfo.length,
                displayWithoutTimestamp));
    }

    private static String formatTraceTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "N/A";
        }
        return TRACE_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis));
    }
}
