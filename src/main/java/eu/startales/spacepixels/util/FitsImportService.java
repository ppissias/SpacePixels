package eu.startales.spacepixels.util;

import eu.startales.spacepixels.session.SessionFrames;
import eu.startales.spacepixels.util.ImageProcessing.RedirectImportException;
import nom.tam.fits.*;
import nom.tam.util.Cursor;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/** Interactive and strict headless metadata loading; decisions are supplied separately from file operations. */
final class FitsImportService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(FitsImportService.class.getName());
    private static final DateTimeFormatter TRACE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'").withZone(ZoneOffset.UTC);
    private final File directory;
    private final SessionFrames state;
    private final FitsBatchService batch;
    private final FitsImportInteraction interaction;
    private final ExecutorService executor = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));

    FitsImportService(File directory, SessionFrames state, FitsBatchService batch, FitsImportInteraction interaction) {
        this.directory = directory;
        this.state = state;
        this.batch = batch;
        this.interaction = interaction;
    }

    @Override public void close() { executor.shutdown(); }

    public FitsFileInformation[] getFitsfileInformation() throws Exception {
        File[] fitsFileInformation = FitsFiles.list(directory);
        int numFiles = fitsFileInformation.length;

        if (numFiles == 0) return new FitsFileInformation[0];
        FitsSequenceValidator.validate(fitsFileInformation);

        // --- NEW: COMPRESSION GATEKEEPER ---
        try (Fits firstFits = new Fits(fitsFileInformation[0])) {
            if (FitsImages.isCompressedFits(firstFits)) {
                int choice = interaction.confirm(
                        "These images appear to be compressed (.fz format).\nSpacePixels requires uncompressed FITS files for optimal tracking and plate-solving.\n\n" +
                                "Would you like to automatically decompress this sequence into a new directory?",
                        "Decompression Required");

                if (choice == FitsImportInteraction.YES) {
                    LOGGER.info("Starting decompression...");
                    File newDir;
                    try (FitsImportInteraction.Progress progress = interaction.progress("Decompressing FITS files...")) {
                        newDir = batch.batchDecompress(fitsFileInformation, progress);
                    }

                    int loadChoice = interaction.confirm(
                            "Decompression Complete!\n\nWould you like to automatically load the new uncompressed directory?",
                            "Success");

                    if (loadChoice == FitsImportInteraction.YES && newDir != null) {
                        throw new RedirectImportException(newDir);
                    }
                }
                return new FitsFileInformation[0]; // Stop the current import
            }
        } catch (RedirectImportException re) {
            throw re;
        } catch (Exception failure) {
            LOGGER.warning("Failed to read first FITS for compression check: " + failure.getMessage());
        }


        // --- PRE-FLIGHT CONSISTENCY CHECK ---
        LOGGER.info("Performing pre-flight consistency check on " + numFiles + " files...");
        FitsFormatChecker.FitsFormat refFormat = FitsFormatChecker.FitsFormat.UNSUPPORTED;
        try {
            refFormat = FitsFormatChecker.checkFormat(fitsFileInformation[0]);
        } catch (Exception ignore) {}

        int tempRefWidth = -1;
        int tempRefHeight = -1;
        boolean tempRefMono = true;
        Header refHeader;

        try {
            Fits firstFits = new Fits(fitsFileInformation[0]);
            BasicHDU<?> hdu = FitsImages.getImageHDU(firstFits);
            refHeader = hdu.getHeader();
            int[] axes = hdu.getAxes();
            if (axes == null) throw new FitsException("No image axes found");

            if (axes.length == 2) {
                tempRefHeight = axes[0];
                tempRefWidth = axes[1];
                tempRefMono = true;
            } else if (axes.length == 3) {
                tempRefHeight = axes[1];
                tempRefWidth = axes[2];
                tempRefMono = false;
            } else {
                firstFits.close();
                interaction.error( "Unsupported FITS axes length: " + axes.length, "Import Error");
                return new FitsFileInformation[0];
            }
            firstFits.close();
        } catch (Exception failure) {
            interaction.error( "Failed to read the first FITS file: " + failure.getMessage(), "Import Error");
            return new FitsFileInformation[0];
        }

        final int refWidth = tempRefWidth;
        final int refHeight = tempRefHeight;
        final boolean refMono = tempRefMono;
        final FitsFormatChecker.FitsFormat finalRefFormat = refFormat;

        List<Callable<String>> validationTasks = new ArrayList<>();
        for (int fileIndex = 1; fileIndex < numFiles; fileIndex++) {
            final File candidateFile = fitsFileInformation[fileIndex];
            validationTasks.add(() -> {
                FitsFormatChecker.FitsFormat format = FitsFormatChecker.FitsFormat.UNSUPPORTED;
                try { format = FitsFormatChecker.checkFormat(candidateFile); } catch (Exception ignore) {}

                if (format != finalRefFormat) {
                    return "Inconsistent FITS formats detected!\nFile: " + candidateFile.getName() + " has format " + format + " but expected " + finalRefFormat + ".\nAll FITS files in the sequence must be of the exact same type.";
                }
                Fits fits = null;
                try {
                    fits = new Fits(candidateFile);
                    BasicHDU<?> hdu = FitsImages.getImageHDU(fits);
                    int[] axes = hdu.getAxes();
                    if (axes == null) return "No valid image HDU found in " + candidateFile.getName();

                    boolean mono = (axes.length == 2);
                    int width = -1, height = -1;
                    if (mono) {
                        height = axes[0];
                        width = axes[1];
                    } else if (axes.length == 3) {
                        height = axes[1];
                        width = axes[2];
                    }

                    if (mono != refMono || width != refWidth || height != refHeight) {
                        return "Inconsistent FITS dimensions or color-space detected!\nFile: " + candidateFile.getName() + " is " + width + "x" + height + " (Mono: " + mono + ")\nExpected: " + refWidth + "x" + refHeight + " (Mono: " + refMono + ").\nAll files must have the exact same resolution and color space.";
                    }
                } catch (Exception failure) {
                    return "Failed to read FITS file " + candidateFile.getName() + ": " + failure.getMessage();
                } finally {
                    if (fits != null) {
                        try { fits.close(); } catch (Exception ignored) {}
                    }
                }
                return null; // OK
            });
        }

        try {
            List<Future<String>> results = executor.invokeAll(validationTasks);
            for (Future<String> res : results) {
                String errorMsg = res.get();
                if (errorMsg != null) {
                    interaction.error( errorMsg, "Import Error");
                    return new FitsFileInformation[0];
                }
            }
        } catch (Exception failure) {
            throw new IOException("Multi-threaded validation failed: " + failure.getMessage(), failure);
        }
        // --- END CONSISTENCY CHECK ---

        // --- THE GATEKEEPER: Check the format of the first file ---
        FitsFormatChecker.FitsFormat format = refFormat;

        if ((format == FitsFormatChecker.FitsFormat.MONO_16BIT || format == FitsFormatChecker.FitsFormat.COLOR_16BIT)
                && !FitsPixelConverter.isUnsigned16Bit(refHeader)) {
            boolean color = format == FitsFormatChecker.FitsFormat.COLOR_16BIT;
            int choice = interaction.confirm(
                    dialogParagraphs("These 16-bit images store their pixels with BZERO " + headerNumber(refHeader.getDoubleValue("BZERO", 0.0))
                                    + " and BSCALE " + headerNumber(refHeader.getDoubleValue("BSCALE", 1.0)) + ". "
                                    + "SpacePixels reads 16-bit images as unsigned values (BZERO 32768, BSCALE 1), so it would read these "
                                    + "at the wrong brightness level, which breaks the saturation, padding and void checks.",
                            color ? "Would you like to extract the Luminance (16-bit Mono) and save a standard 16-bit Color version?"
                                  : "Would you like to convert this sequence to standard 16-bit images in a new folder?"),
                    "Format Conversion Required");

            if (choice == FitsImportInteraction.YES) {
                LOGGER.info("Starting signed/scaled 16-bit to standard 16-bit conversion...");
                return standardizeAndOfferToLoad(fitsFileInformation, color);
            }

            LOGGER.info("User declined 16-bit standardization. Clearing the active import.");
            return new FitsFileInformation[0];

        } else if (format == FitsFormatChecker.FitsFormat.MONO_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.MONO_32BIT_INT) {
            int choice = interaction.confirm(
                    "These images appear to be 32-bit.\nSpacePixels' transient detection engine is highly optimized for 16-bit monochrome files.\n\n" +
                            "Would you like to automatically standardize this sequence to 16-bit Mono?",
                    "Format Conversion Required");

            if (choice == FitsImportInteraction.YES) {
                LOGGER.info("Starting 32-bit Mono to 16-bit Mono conversion...");
                return standardizeAndOfferToLoad(fitsFileInformation, false);
            }

            LOGGER.info("User declined 32-bit mono standardization. Clearing the active import.");
            return new FitsFileInformation[0];

        } else if (format == FitsFormatChecker.FitsFormat.COLOR_32BIT_FLOAT || format == FitsFormatChecker.FitsFormat.COLOR_32BIT_INT) {
            int choice = interaction.confirm(
                    "These images are 32-bit Color.\nSpacePixels' transient detection engine requires 16-bit monochrome files.\n\n" +
                            "Would you like to extract the Luminance (16-bit Mono) and save a 16-bit Color version?",
                    "Color Format Conversion Required");

            if (choice == FitsImportInteraction.YES) {
                LOGGER.info("Starting 32-bit Color to 16-bit Mono/Color conversion...");
                return standardizeAndOfferToLoad(fitsFileInformation, true);
            }

            LOGGER.info("User declined 32-bit color standardization. Clearing the active import.");
            return new FitsFileInformation[0];
        } else if (format == FitsFormatChecker.FitsFormat.UNSUPPORTED) {
            interaction.error( "Unsupported FITS format detected. SpacePixels supports 16-bit and 32-bit FITS files.", "Import Error");
            return new FitsFileInformation[0];
        }

        DebugOutput.println("\n--- Fast-Loading Information for " + numFiles + " FITS files ---");

        List<Callable<FitsFileInformation>> tasks = new ArrayList<>();

        for (int fileIndex = 0; fileIndex < numFiles; fileIndex++) {
            final int index = fileIndex;
            final File currentFile = fitsFileInformation[fileIndex];

            tasks.add(() -> {
                Fits fitsFile = null;
                try {
                    fitsFile = new Fits(currentFile);
                    BasicHDU<?> hdu = FitsImages.getImageHDU(fitsFile);
                    Header fitsHeader = hdu.getHeader();

                    String fpath = currentFile.getAbsolutePath();
                    boolean monochromeImage;
                    int width;
                    int height;

                    int[] axes = hdu.getAxes();

                    if (axes.length == 2) {
                        monochromeImage = true;
                        height = axes[0];
                        width = axes[1];
                    } else if (axes.length == 3) {
                        monochromeImage = false;
                        height = axes[1];
                        width = axes[2];
                    } else {
                        throw new FitsException("Cannot understand file, it has axes length=" + axes.length);
                    }

                    FitsFileInformation fileInfo = new FitsFileInformation(fpath, currentFile.getName(), monochromeImage, width, height);

                    Cursor<String, HeaderCard> iter = fitsHeader.iterator();
                    while (iter.hasNext()) {
                        HeaderCard fitsHeaderCard = iter.next();
                        fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
                    }

                    return fileInfo;

                } finally {
                    if (fitsFile != null) {
                        fitsFile.close();
                    }
                }
            });
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];

        try {
            List<Future<FitsFileInformation>> futures = executor.invokeAll(tasks);
            for (int fileIndex = 0; fileIndex < futures.size(); fileIndex++) {
                ret[fileIndex] = futures.get(fileIndex).get();
            }
        } catch (Exception failure) {
            throw new IOException("Multi-threaded file loading failed: " + failure.getMessage(), failure);
        }

        FitsSequenceValidator.validateAndSort(ret);

        state.setFiles(ret);
        DebugOutput.println("Finished loading metadata for " + numFiles + " files instantly.");
        logFitsTimestampDiagnostics("Interactive import", ret);
        return ret;
    }

    public FitsFileInformation[] getFitsfileInformationHeadless() throws Exception {
        File[] fitsFiles = FitsFiles.list(directory);
        int numFiles = fitsFiles.length;

        if (numFiles == 0) {
            state.setFiles(new FitsFileInformation[0]);
            return state.getFiles();
        }

        DebugOutput.println("\n--- Validating " + numFiles + " FITS files for headless batch detection ---");

        List<Callable<FitsMetadataLoadResult>> tasks = new ArrayList<>();
        for (File currentFile : fitsFiles) {
            tasks.add(() -> loadFitsMetadataHeadless(currentFile));
        }

        FitsMetadataLoadResult[] loadedResults = new FitsMetadataLoadResult[numFiles];
        try {
            List<Future<FitsMetadataLoadResult>> futures = executor.invokeAll(tasks);
            for (int fileIndex = 0; fileIndex < futures.size(); fileIndex++) {
                loadedResults[fileIndex] = futures.get(fileIndex).get();
            }
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IOException("Headless FITS validation failed.", cause);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Headless FITS validation was interrupted.", failure);
        }

        FitsMetadataLoadResult reference = loadedResults[0];
        for (int fileIndex = 1; fileIndex < loadedResults.length; fileIndex++) {
            validateHeadlessFitsConsistency(reference, loadedResults[fileIndex]);
        }

        FitsFileInformation[] ret = new FitsFileInformation[numFiles];
        for (int fileIndex = 0; fileIndex < loadedResults.length; fileIndex++) {
            ret[fileIndex] = loadedResults[fileIndex].fileInfo;
        }

        FitsSequenceValidator.validateAndSort(ret);

        state.setFiles(ret);
        DebugOutput.println("Validated and loaded metadata for " + numFiles + " FITS files.");
        logFitsTimestampDiagnostics("Headless import", ret);
        return ret;
    }

    private FitsMetadataLoadResult loadFitsMetadataHeadless(File currentFile) throws Exception {
        Fits fitsFile = null;
        try {
            fitsFile = new Fits(currentFile);
            if (FitsImages.isCompressedFits(fitsFile)) {
                throw new IOException("Compressed FITS files are not supported in batch mode: " + currentFile.getName());
            }

            BasicHDU<?> hdu = FitsImages.getImageHDU(fitsFile);
            if (hdu == null) {
                throw new FitsException("No valid image HDU found in " + currentFile.getName());
            }

            int[] axes = hdu.getAxes();
            if (axes == null || axes.length != 2) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + currentFile.getName() + " has axes length " + (axes == null ? 0 : axes.length) + ".");
            }

            Header fitsHeader = hdu.getHeader();
            int bitpix = fitsHeader.getIntValue("BITPIX", 0);
            if (bitpix != 16) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + currentFile.getName() + " has BITPIX=" + bitpix + ".");
            }
            if (!FitsPixelConverter.isUnsigned16Bit(fitsHeader)) {
                throw new IOException("Expected unsigned 16-bit FITS files (BZERO=32768, BSCALE=1). File " + currentFile.getName()
                        + " has BZERO=" + headerNumber(fitsHeader.getDoubleValue("BZERO", 0.0))
                        + " and BSCALE=" + headerNumber(fitsHeader.getDoubleValue("BSCALE", 1.0))
                        + ", so read as they are its pixels would be at the wrong brightness level. Convert the folder first:"
                        + " import it in the SpacePixels window, which offers the conversion, or use the API with"
                        + " InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO.");
            }

            int height = axes[0];
            int width = axes[1];
            if (width <= 0 || height <= 0) {
                throw new FitsException("Invalid image dimensions in " + currentFile.getName() + ": " + width + "x" + height);
            }

            FitsFileInformation fileInfo = new FitsFileInformation(currentFile.getAbsolutePath(), currentFile.getName(), true, width, height);

            Cursor<String, HeaderCard> iter = fitsHeader.iterator();
            while (iter.hasNext()) {
                HeaderCard fitsHeaderCard = iter.next();
                fileInfo.getFitsHeader().put(fitsHeaderCard.getKey(), fitsHeaderCard.getValue());
            }

            return new FitsMetadataLoadResult(fileInfo, width, height, bitpix);
        } finally {
            if (fitsFile != null) {
                fitsFile.close();
            }
        }
    }

    private void validateHeadlessFitsConsistency(FitsMetadataLoadResult reference, FitsMetadataLoadResult candidate) throws IOException {
        if (candidate.bitpix != reference.bitpix) {
            throw new IOException("Inconsistent FITS bit depth detected. File " + candidate.fileInfo.getFileName() +
                    " has BITPIX=" + candidate.bitpix + " but " + reference.fileInfo.getFileName() + " has BITPIX=" + reference.bitpix + ".");
        }

        if (candidate.width != reference.width || candidate.height != reference.height) {
            throw new IOException("Inconsistent FITS dimensions detected. File " + candidate.fileInfo.getFileName() +
                    " is " + candidate.width + "x" + candidate.height + " but " + reference.fileInfo.getFileName() +
                    " is " + reference.width + "x" + reference.height + ".");
        }
    }

    private static String headerNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }

    private FitsFileInformation[] standardizeAndOfferToLoad(File[] files, boolean isColor) throws Exception {
        int addedOffset = chooseNegativeValueOffset(files);
        if (addedOffset < 0) {
            LOGGER.info("Conversion cancelled at the negative-value choice. Clearing the active import.");
            return new FitsFileInformation[0];
        }
        File newDir;
        try (FitsImportInteraction.Progress progress = interaction.progress("Converting to standard 16-bit...")) {
            newDir = batch.batchStandardizeTo16Bit(files, isColor, addedOffset, progress);
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

    private int chooseNegativeValueOffset(File[] files) throws Exception {
        FitsPixelConverter.NegativeValueScan scan;
        try (FitsImportInteraction.Progress progress = interaction.progress("Checking pixel values...")) {
            scan = FitsPixelConverter.scanNegativeValues(files,
                    percent -> progress.onProgressUpdate(percent, "Checking for pixel values below zero..."));
        }
        if (!scan.hasNegativeValues()) {
            return 0;
        }

        int offset = scan.offsetToKeepNegativeValues();
        String[] options = {"Add an Offset (Recommended)", "Set to 0", "Cancel"};
        int choice = interaction.choose(negativeValueQuestion(scan, offset), "Pixel Values Below Zero", options);
        LOGGER.info(scan.negativePixels() + " of " + scan.totalPixels() + " pixels below zero, lowest "
                + scan.lowest() + "; choice: " + (choice == 0 ? "add " + offset + " ADU" : choice == 1 ? "set to 0" : "cancel"));
        return choice == 0 ? offset : choice == 1 ? 0 : -1;
    }

    static String negativeValueQuestion(FitsPixelConverter.NegativeValueScan scan, int offset) {
        double percent = 100.0 * scan.negativePixels() / Math.max(1, scan.totalPixels());
        String share = percent >= 10 ? String.format(Locale.ROOT, "%.0f %%", percent)
                : percent >= 0.1 ? String.format(Locale.ROOT, "%.1f %%", percent) : "Less than 0.1 %";
        return dialogParagraphs(
                String.format(Locale.ROOT, "%s of the pixels are below zero (the lowest is %,.0f ADU). This usually means the "
                        + "frames were bias- or dark-subtracted without a pedestal. Standard 16-bit images cannot store values "
                        + "below zero.", share, scan.lowest()),
                String.format(Locale.ROOT, "<b>Add an Offset (recommended):</b> adds %,d ADU to every pixel of every frame. All "
                        + "values are kept, so the sky background and its noise stay intact. Padding left by registration stays "
                        + "at 0, and the report takes the offset off again for the sky brightness.", offset),
                "<b>Set to 0:</b> keeps the original levels, but every pixel below zero becomes 0. Where the sky is close to "
                        + "zero this cuts off the low side of the noise, so the background is measured too high and the noise "
                        + "too low. With the sky at zero, detection can fail.");
    }

    static File offerToImportLargestExposureGroup(MixedExposureException mixed, FitsImportInteraction interaction) throws IOException {
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
            System.out.println("\n--- " + stageLabel + " FITS Timestamp Diagnostics ---");
            System.out.println("No FITS metadata loaded.");
            return;
        }

        int validTimestamps = 0;
        int displayWithoutTimestamp = 0;

        System.out.println("\n--- " + stageLabel + " FITS Timestamp Diagnostics ---");
        for (int fileIndex = 0; fileIndex < filesInfo.length; fileIndex++) {
            FitsFileInformation info = filesInfo[fileIndex];
            long timestamp = info.getObservationTimestamp();
            if (timestamp > 0L) {
                validTimestamps++;
            }
            if (info.hasDisplayableObservationDateWithoutUsableTimestamp()) {
                displayWithoutTimestamp++;
            }

            System.out.println(String.format(
                    Locale.US,
                    "[%03d] file='%s' | parsedTimestamp=%d | parsedUtc='%s' | %s",
                    fileIndex,
                    info.getFileName(),
                    timestamp,
                    formatTraceTimestamp(timestamp),
                    info.getObservationTimestampDiagnostics()));
        }
        System.out.println(String.format(
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

    private static class FitsMetadataLoadResult {
        private final FitsFileInformation fileInfo;
        private final int width;
        private final int height;
        private final int bitpix;

        private FitsMetadataLoadResult(FitsFileInformation fileInfo, int width, int height, int bitpix) {
            this.fileInfo = fileInfo;
            this.width = width;
            this.height = height;
            this.bitpix = bitpix;
        }
    }
}
