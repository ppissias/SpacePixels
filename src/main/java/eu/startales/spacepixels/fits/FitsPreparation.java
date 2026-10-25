package eu.startales.spacepixels.fits;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import java.io.File;
import java.io.IOException;
import java.util.Locale;
import eu.startales.spacepixels.util.StagedFile;

/** Writes prepared FITS copies through the pixel converters and staged publication, separately from input inspection. */
public final class FitsPreparation {
    /** Prevents construction of this stateless prepared-output helper. */
    private FitsPreparation() {}

    /** Converts one input through the existing pixel converters and stages each requested prepared FITS output. */
    public static void standardizeFile(File inputFile, int addedOffset, File monoOutput, File colorOutput) throws Exception {
        try (Fits source = new Fits(inputFile)) {
            BasicHDU<?> image = FitsInput.getImageHDU(source);
            if (image == null) throw new IOException("No valid image HDU found in " + inputFile.getName());
            Header header = image.getHeader();
            Object kernel = image.getKernel();
            short[][] mono;
            if (kernel instanceof short[][][] || kernel instanceof int[][][] || kernel instanceof float[][][]) {
                short[][][] color = FitsPixelConverter.standardizeTo16BitColor(kernel, header, addedOffset);
                if (colorOutput != null) write(colorOutput, color, header, addedOffset);
                mono = FitsPixelConverter.extractLuminance(color);
            } else if (kernel instanceof short[][] || kernel instanceof int[][] || kernel instanceof float[][]) {
                mono = FitsPixelConverter.standardizeTo16BitMono(kernel, header, addedOffset);
            } else {
                throw new IOException("Unsupported FITS kernel type in " + inputFile.getName() + ": "
                        + (kernel == null ? "null" : kernel.getClass().getName()));
            }
            write(monoOutput, mono, header, addedOffset);
        }
    }

    /** Stages one FITS output with the source metadata and added offset in ADU. */
    private static void write(File file, Object pixels, Header header, int addedOffset) throws Exception {
        try (Fits output = FitsPixelConverter.createFitsFromData(pixels, header, addedOffset)) {
            StagedFile.write(file, staged -> output.write(staged.toFile()));
        }
    }

    /** Derives a prepared .fit filename by stripping the image/compression suffix and appending the requested suffix. */
    public static String preparedFilename(File inputFile, String suffix) {
        String filename = inputFile.getName();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".fz")) filename = filename.substring(0, filename.length() - 3);
        String extension = FitsInput.imageExtension(filename);
        if (extension != null) filename = filename.substring(0, filename.length() - extension.length());
        return filename + suffix + ".fit";
    }
}
