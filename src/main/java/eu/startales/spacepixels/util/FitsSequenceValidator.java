package eu.startales.spacepixels.util;

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

    static void validateInputs(FitsPreparationService.Input[] inputs) throws IOException {
        if (inputs.length == 0) return;
        FitsPreparationService.Input reference = inputs[0];
        FitsFileInformation[] metadata = new FitsFileInformation[inputs.length];
        for (int position = 0; position < inputs.length; position++) {
            FitsPreparationService.Input input = inputs[position];
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
