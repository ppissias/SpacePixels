package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FITS input discovery, HDU selection, storage inspection and sequence validation. Inspection receives an optional
 * caller-owned executor and retains metadata/layout facts rather than image buffers; output conversion stays with
 * FitsPreparation and the pixel converters.
 */
public final class FitsInput {
    private static final Logger LOGGER = Logger.getLogger(FitsInput.class.getName());

    /** Prevents construction of this stateless input boundary. */
    private FitsInput() {}

    /** Discovers FITS files in stable path order, rejecting mixed extensions and empty folders. */
    static File[] list(File directory) throws IOException, FitsException {
        if (!directory.isDirectory()) {
            throw new IOException("file:" + directory.getAbsolutePath() + " is not a directory");
        }

        FitsInput.validateDirectoryExtensions(directory);
        File[] ret = directory.listFiles(file -> file.isFile()
                && FitsInput.imageExtension(file.getName()) != null
                && !".xisf".equals(FitsInput.imageExtension(file.getName())));
        if (ret == null) {
            throw new IOException("Could not list input directory: " + directory);
        }
        if (ret.length == 0) {
            throw new IOException("No fits files in directory:" + directory.getAbsolutePath());
        }

        Arrays.sort(ret, Comparator.comparing(File::getAbsolutePath));
        return ret;
    }

    /**
     * Finds the image HDU of a FITS file: the first compressed (.fz) image HDU, decompressed, or the first HDU with
     * at least two non-empty axes, so an empty primary HDU is skipped. Without either, the first HDU is returned.
     */
    public static BasicHDU<?> getImageHDU(Fits fits) throws FitsException, IOException {
        BasicHDU<?> hdu;
        BasicHDU<?> fallback = null;

        // 1. If the Fits object already read HDUs into memory, use them directly
        int numHdus = fits.getNumberOfHDUs();
        for (int hduIndex = 0; hduIndex < numHdus; hduIndex++) {
            hdu = fits.getHDU(hduIndex);
            if (fallback == null) fallback = hdu;

            if (hdu instanceof CompressedImageHDU) {
                return ((CompressedImageHDU) hdu).asImageHDU();
            }
            int[] axes = hdu.getAxes();
            if (axes != null && axes.length >= 2) {
                boolean hasData = true;
                for (int axis : axes) if (axis <= 0) hasData = false;
                if (hasData) return hdu;
            }
        }

        // 2. Otherwise, safely stream through the file to find the target HDU
        while ((hdu = fits.readHDU()) != null) {
            if (fallback == null) fallback = hdu; // Store the dummy Primary HDU just in case

            if (hdu instanceof CompressedImageHDU) {
                return ((CompressedImageHDU) hdu).asImageHDU();
            }

            int[] axes = hdu.getAxes();
            if (axes != null && axes.length >= 2) {
                boolean hasData = true;
                for (int axis : axes) {
                    if (axis <= 0) hasData = false;
                }
                if (hasData) return hdu;
            }
        }
        return fallback;
    }

    /** Inspects cached and remaining HDUs for compressed image markers. */
    static boolean isCompressedFits(Fits fits) throws FitsException, IOException {
        int numHdus = fits.getNumberOfHDUs();
        for (int hduIndex = 0; hduIndex < numHdus; hduIndex++) {
            if (fits.getHDU(hduIndex) instanceof CompressedImageHDU || fits.getHDU(hduIndex).getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }

        BasicHDU<?> hdu;
        while ((hdu = fits.readHDU()) != null) {
            if (hdu instanceof CompressedImageHDU || hdu.getHeader().getBooleanValue("ZIMAGE", false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Supported FITS storage layouts recognized by the import pipeline.
     */
    public enum FitsFormat {
        MONO_16BIT,
        MONO_32BIT_INT,
        MONO_32BIT_FLOAT,
        COLOR_16BIT,
        COLOR_32BIT_INT,
        COLOR_32BIT_FLOAT,
        UNSUPPORTED
    }

    /**
     * Classifies the selected FITS image HDU; a failed inspection is logged and returns UNSUPPORTED.
     */
    public static FitsFormat checkFormat(File fitsFile) {
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
            if (hdu == null) return FitsFormat.UNSUPPORTED;

            Header header = hdu.getHeader();
            int bitpix = header.getIntValue("BITPIX", 0);
            int naxis = header.getIntValue("NAXIS", 0);

            // Default to 1 channel if NAXIS3 isn't present
            int naxis3 = header.getIntValue("NAXIS3", 1);

            boolean isColor = (naxis == 3 && naxis3 >= 3);

            return format(bitpix, isColor);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to inspect FITS format of " + fitsFile, e);


        }
        return FitsFormat.UNSUPPORTED;
    }

    /** Classifies bit depth and channel layout without reading a file. */
    static FitsFormat format(int bitpix, boolean isColor) {
        if (bitpix == 16) {
            return isColor ? FitsFormat.COLOR_16BIT : FitsFormat.MONO_16BIT;
        } else if (bitpix == 32) {
            return isColor ? FitsFormat.COLOR_32BIT_INT : FitsFormat.MONO_32BIT_INT;
        } else if (bitpix == -32) {
            return isColor ? FitsFormat.COLOR_32BIT_FLOAT : FitsFormat.MONO_32BIT_FLOAT;
        }
        return FitsFormat.UNSUPPORTED;
    }

    /** Returns the recognized lowercase image suffix, preferring compressed compound suffixes. */
    static String imageExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".fits.fz", ".fit.fz", ".fts.fz", ".fits", ".fit", ".fts", ".fz", ".xisf"}) {
            if (lower.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    /** Checks extension consistency before reading candidate images. */
    static void validateDirectoryExtensions(File directory) throws IOException {
        File[] files = directory.listFiles(file -> file.isFile() && imageExtension(file.getName()) != null);
        if (files == null) {
            throw new IOException("Could not list input directory: " + directory);
        }
        validateExtensions(files);
    }

    /** Requires one image extension across the supplied files. */
    static void validateExtensions(File[] files) throws IOException {
        if (files.length == 0) {
            return;
        }
        String expected = imageExtension(files[0].getName());
        for (File file : files) {
            if (!java.util.Objects.equals(expected, imageExtension(file.getName()))) {
                throw new IOException("All imported images must have the same extension. " + files[0].getName()
                        + " and " + file.getName() + " have different extensions.");
            }
        }
    }

    /** Validates captured storage, dimensions, compression, timing and exposure without reordering inputs. */
    static void validateInputs(FitsInput.Input[] inputs) throws IOException {
        if (inputs.length == 0) return;
        FitsInput.Input reference = inputs[0];
        FitsFileInformation[] metadata = new FitsFileInformation[inputs.length];
        for (int position = 0; position < inputs.length; position++) {
            FitsInput.Input input = inputs[position];
            int[] axes = input.axes();
            if (axes == null || !(axes.length == 2 || (axes.length == 3 && axes[0] == 3))
                    || Arrays.stream(axes).anyMatch(axis -> axis <= 0)) {
                throw new IOException("Expected a monochrome or three-channel FITS image: " + input.information().getFileName());
            }
            if (input.bitpix() != reference.bitpix() || input.bzero() != reference.bzero()
                    || input.bscale() != reference.bscale() || !Arrays.equals(axes, reference.axes())
                    || input.compressed() != reference.compressed()) {
                throw new IOException("All imported FITS files must have the same type, dimensions and compression. "
                        + input.information().getFileName() + " ("
                        + describe(axes, input.bitpix(), input.bzero(), input.bscale(), input.compressed())
                        + ") differs from " + reference.information().getFileName() + " ("
                        + describe(reference.axes(), reference.bitpix(), reference.bzero(), reference.bscale(),
                        reference.compressed()) + ").");
            }
            metadata[position] = input.information();
        }
        validateTiming(metadata);
        validateExposures(metadata);
    }

    /** Describes captured storage properties in a sequence validation failure. */
    private static String describe(int[] axes, int bitpix, double bzero, double bscale, boolean compressed) {
        String size = axes[axes.length - 1] + "x" + axes[axes.length - 2] + (axes.length == 2 ? " mono" : " colour");
        return String.format(Locale.US, "%s, BITPIX %d, BZERO %s, BSCALE %s%s", size, bitpix,
                stripZeros(bzero), stripZeros(bscale), compressed ? ", compressed" : "");
    }

    /** Formats integral storage values without a decimal fraction. */
    private static String stripZeros(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    /** Validates timing/exposure consistency and sorts the caller-owned metadata array in place. */
    static void validateAndSort(FitsFileInformation[] metadata) throws IOException {
        boolean timed = validateTiming(metadata);
        validateExposures(metadata);
        Comparator<FitsFileInformation> order = Comparator.comparing(FitsFileInformation::getFileName);
        if (timed) {
            order = Comparator.comparingLong(FitsFileInformation::getObservationTimestamp).thenComparing(order);
        }
        Arrays.sort(metadata, order);
    }

    /** Requires consistent timestamp availability and returns whether the sequence is timed. */
    private static boolean validateTiming(FitsFileInformation[] metadata) throws IOException {
        if (metadata.length == 0) {
            return false;
        }
        boolean timed = metadata[0].getObservationTimestamp() != -1L;
        for (FitsFileInformation information : metadata) {
            if ((information.getObservationTimestamp() != -1L) != timed) {
                throw new IOException("Cannot import a mixture of timed and untimed images. All files must have a usable "
                        + "observation timestamp, or none may have one. " + metadata[0].getFileName()
                        + " and " + information.getFileName() + " have different timing availability.");
            }
        }
        return timed;
    }

    /** Rejects a sequence whose images do not all have the same exposure time (see {@link ExposureGroup}). */
    static void validateExposures(FitsFileInformation[] metadata) throws MixedExposureException {
        File[] files = new File[metadata.length];
        long[] exposures = new long[metadata.length];
        for (int i = 0; i < metadata.length; i++) {
            files[i] = new File(metadata[i].getFilePath());
            exposures[i] = metadata[i].getExposureDurationMillis();
        }
        List<ExposureGroup> groups = ExposureGroup.of(files, exposures);
        if (groups.size() > 1) {
            throw new MixedExposureException(groups);
        }
    }

    /** Metadata and storage facts captured once for a candidate input, without pixel buffers. */
    record Input(FitsFileInformation information, int[] axes, int bitpix, double bzero, double bscale,
                 boolean compressed) {
        /** Returns the format classification of the captured axes and bit depth. */
        FitsInput.FitsFormat format() {
            return FitsInput.format(bitpix, axes != null && axes.length == 3);
        }

        /** Recognizes the unsigned 16-bit storage layout used by detection. */
        boolean unsigned16Bit() {
            return FitsInput.isUnsigned16Bit(bitpix, bzero, bscale);
        }

        /** Whether the captured layout can be detected without conversion or decompression. */
        boolean detectionReady() {
            return !compressed && axes != null && axes.length == 2
                    && Arrays.stream(axes).allMatch(axis -> axis > 0) && unsigned16Bit();
        }

        /** Rejects an unready file in the existing headless error order. */
        void requireDetectionReady() throws IOException, FitsException {
            String filename = information.getFileName();
            if (compressed) {
                throw new IOException("Compressed FITS files are not supported in batch mode: " + filename);
            }
            if (axes == null || axes.length != 2) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + filename
                        + " has axes length " + (axes == null ? 0 : axes.length) + ".");
            }
            if (bitpix != 16) {
                throw new FitsException("Expected uncompressed 16-bit monochrome FITS files. File " + filename
                        + " has BITPIX=" + bitpix + ".");
            }
            if (!unsigned16Bit()) {
                throw new IOException("Expected unsigned 16-bit FITS files (BZERO=32768, BSCALE=1). File " + filename
                        + " has BZERO=" + headerNumber(bzero) + " and BSCALE=" + headerNumber(bscale)
                        + ", so read as they are its pixels would be at the wrong brightness level. Convert the folder first:"
                        + " import it in the SpacePixels window, which offers the conversion, or use the API with"
                        + " InputPreparationMode.AUTO_PREPARE_TO_16BIT_MONO.");
            }
            if (Arrays.stream(axes).anyMatch(axis -> axis <= 0)) {
                throw new FitsException("Invalid image dimensions in " + filename + ": "
                        + information.getSizeWidth() + "x" + information.getSizeHeight());
            }
        }
    }

    /** Reads candidate metadata on an optional caller-owned executor, checking readiness before cohort validation when requested. */
    static Input[] inspect(File[] files, ExecutorService executor, boolean requireReady) throws Exception {
        FitsInput.validateExtensions(files);
        List<Callable<Input>> tasks = new ArrayList<>();
        for (File file : files) {
            tasks.add(() -> {
                Input input = read(file);
                if (requireReady) input.requireDetectionReady();
                return input;
            });
        }
        Input[] inputs = new Input[files.length];
        if (executor == null) {
            for (int index = 0; index < tasks.size(); index++) inputs[index] = tasks.get(index).call();
        } else {
            try {
                List<Future<Input>> results = executor.invokeAll(tasks);
                for (int index = 0; index < results.size(); index++) inputs[index] = results.get(index).get();
            } catch (ExecutionException failure) {
                if (failure.getCause() instanceof Exception cause) throw cause;
                if (failure.getCause() instanceof Error cause) throw cause;
                throw new IOException("FITS metadata loading failed.", failure.getCause());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("FITS metadata loading was interrupted.", failure);
            }
        }
        FitsInput.validateInputs(inputs);
        return inputs;
    }

    /** Closes the FITS file after capturing metadata and storage facts; retains no pixel buffers. */
    private static Input read(File file) throws Exception {
        try (Fits fits = new Fits(file)) {
            boolean compressed = FitsInput.isCompressedFits(fits);
            BasicHDU<?> image = FitsInput.getImageHDU(fits);
            if (image == null) throw new FitsException("No valid image HDU found in " + file.getName());
            int[] axes = image.getAxes();
            int width = axes == null || axes.length < 2 ? 0 : axes[axes.length - 1];
            int height = axes == null || axes.length < 2 ? 0 : axes[axes.length - 2];
            Header header = image.getHeader();
            Map<String, String> headerMap = new HashMap<>();

            Cursor<String, HeaderCard> cards = header.iterator();
            while (cards.hasNext()) {
                HeaderCard card = cards.next();
                headerMap.put(card.getKey(), card.getValue());
            }
            FitsFileInformation information = new FitsFileInformation(file.getAbsolutePath(), file.getName(),
                    axes != null && axes.length == 2, width, height, headerMap);
            return new Input(information, axes, header.getIntValue("BITPIX", 0),
                    header.getDoubleValue("BZERO", 0.0), header.getDoubleValue("BSCALE", 1.0), compressed);
        }
    }

    /** True for the layout the detection reads directly: 16-bit integers stored as unsigned (BZERO 32768, BSCALE 1). */
    static boolean isUnsigned16Bit(Header header) {
        return isUnsigned16Bit(header.getIntValue("BITPIX", 0), header.getDoubleValue("BZERO", 0.0),
                header.getDoubleValue("BSCALE", 1.0));
    }

    /** Recognizes BITPIX 16 with BZERO 32768 and BSCALE 1, without tolerance or value conversion. */
    static boolean isUnsigned16Bit(int bitpix, double bzero, double bscale) {
        return bitpix == 16 && bzero == 32768.0 && bscale == 1.0;
    }

    /** Formats header values using the established diagnostic integer/scientific notation. */
    private static String headerNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }
}
