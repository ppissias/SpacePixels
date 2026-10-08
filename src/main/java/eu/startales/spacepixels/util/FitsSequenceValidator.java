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
                boolean compressed = false;
                for (int hduIndex = 0; hduIndex < fits.getNumberOfHDUs(); hduIndex++) {
                    BasicHDU<?> originalHdu = fits.getHDU(hduIndex);
                    compressed |= originalHdu instanceof CompressedImageHDU
                            || originalHdu.getHeader().getBooleanValue("ZIMAGE", false);
                }
                if (position == 0) {
                    referenceAxes = axes;
                    referenceBitpix = bitpix;
                    referenceCompressed = compressed;
                } else if (bitpix != referenceBitpix || !Arrays.equals(axes, referenceAxes)
                        || compressed != referenceCompressed) {
                    throw new IOException("All imported FITS files must have the same type, dimensions and compression. "
                            + file.getName() + " differs from " + files[0].getName() + ".");
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
    }

    static void validateAndSort(FitsFileInformation[] metadata) throws IOException {
        boolean timed = validateTiming(metadata);
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
}
