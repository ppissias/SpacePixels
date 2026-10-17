package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class FitsSequenceValidator {

    private FitsSequenceValidator() {
    }

    static String imageExtension(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".fits.fz", ".fit.fz", ".fts.fz", ".fits", ".fit", ".fts", ".fz", ".xisf"}) {
            if (lower.endsWith(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    static void validateDirectoryExtensions(File directory) throws IOException {
        File[] files = directory.listFiles(file -> file.isFile() && imageExtension(file.getName()) != null);
        if (files == null) {
            throw new IOException("Could not list input directory: " + directory);
        }
        validateExtensions(files);
    }

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

    static void validate(File[] files) throws Exception {
        validateExtensions(files);
        FitsFileInformation[] metadata = new FitsFileInformation[files.length];
        int[] referenceAxes = null;
        int referenceBitpix = 0;
        double referenceBzero = 0.0;
        double referenceBscale = 1.0;
        boolean referenceCompressed = false;
        for (int position = 0; position < files.length; position++) {
            File file = files[position];
            try (Fits fits = new Fits(file)) {
                BasicHDU<?> hdu = ImageProcessing.getImageHDU(fits);
                int[] axes = hdu == null ? null : hdu.getAxes();
                if (axes == null || !(axes.length == 2 || (axes.length == 3 && axes[0] == 3))
                        || Arrays.stream(axes).anyMatch(axis -> axis <= 0)) {
                    throw new IOException("Expected a monochrome or three-channel FITS image: " + file.getName());
                }
                Header header = hdu.getHeader();
                int bitpix = header.getIntValue("BITPIX", 0);
                // Part of the sample type: 16-bit data with BZERO 0 is signed, with BZERO 32768 unsigned.
                double bzero = header.getDoubleValue("BZERO", 0.0);
                double bscale = header.getDoubleValue("BSCALE", 1.0);
                boolean compressed = false;
                for (int hduIndex = 0; hduIndex < fits.getNumberOfHDUs(); hduIndex++) {
                    BasicHDU<?> originalHdu = fits.getHDU(hduIndex);
                    compressed |= originalHdu instanceof CompressedImageHDU
                            || originalHdu.getHeader().getBooleanValue("ZIMAGE", false);
                }
                if (position == 0) {
                    referenceAxes = axes;
                    referenceBitpix = bitpix;
                    referenceBzero = bzero;
                    referenceBscale = bscale;
                    referenceCompressed = compressed;
                } else if (bitpix != referenceBitpix || bzero != referenceBzero || bscale != referenceBscale
                        || !Arrays.equals(axes, referenceAxes) || compressed != referenceCompressed) {
                    throw new IOException("All imported FITS files must have the same type, dimensions and compression. "
                            + file.getName() + " (" + describe(axes, bitpix, bzero, bscale, compressed) + ") differs from "
                            + files[0].getName() + " (" + describe(referenceAxes, referenceBitpix, referenceBzero,
                            referenceBscale, referenceCompressed) + ").");
                }
                FitsFileInformation information = new FitsFileInformation(file.getAbsolutePath(), file.getName(),
                        axes.length == 2, axes[axes.length - 1], axes[axes.length - 2]);
                Cursor<String, HeaderCard> cards = header.iterator();
                while (cards.hasNext()) {
                    HeaderCard card = cards.next();
                    information.getFitsHeader().put(card.getKey(), card.getValue());
                }
                metadata[position] = information;
            }
        }
        validateTiming(metadata);
        validateExposures(metadata);
    }

    private static String describe(int[] axes, int bitpix, double bzero, double bscale, boolean compressed) {
        String size = axes[axes.length - 1] + "x" + axes[axes.length - 2] + (axes.length == 2 ? " mono" : " colour");
        return String.format(Locale.US, "%s, BITPIX %d, BZERO %s, BSCALE %s%s", size, bitpix,
                stripZeros(bzero), stripZeros(bscale), compressed ? ", compressed" : "");
    }

    private static String stripZeros(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    static void validateAndSort(FitsFileInformation[] metadata) throws IOException {
        boolean timed = validateTiming(metadata);
        validateExposures(metadata);
        Comparator<FitsFileInformation> order = Comparator.comparing(FitsFileInformation::getFileName);
        if (timed) {
            order = Comparator.comparingLong(FitsFileInformation::getObservationTimestamp).thenComparing(order);
        }
        Arrays.sort(metadata, order);
    }

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
}
