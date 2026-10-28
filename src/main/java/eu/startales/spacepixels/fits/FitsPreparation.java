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

    /** Writes mono and optional colour copies using the cohort's fixed transform and recorded pedestal. */
    public static void standardizeFile(File inputFile, ImageScaling.Plan plan, File monoOutput, File colorOutput) throws Exception {
        try (Fits fits = new Fits(inputFile)) {
            BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
            if (hdu == null) throw new IOException("Missing FITS image: " + inputFile.getName());
            var pixels = FitsPixelConverter.pixels(hdu.getKernel(), hdu.getHeader());
            Header header = hdu.getHeader();
            plan.record(header, pixels.floatingPoint());
            short[][] mono = FitsPixelConverter.to16BitMonochrome(pixels, plan, () -> false);
            if (colorOutput != null) write(colorOutput, FitsPixelConverter.to16BitColor(pixels, plan, () -> false), header, 0);
            write(monoOutput, mono, header, 0);
        }
    }

    /** Converts one image with a caller-supplied ADU pedestal through the common plan and staged writer. */
    public static void standardizeFile(File inputFile, int addedOffset, File monoOutput, File colorOutput) throws Exception {
        standardizeFile(inputFile, ImageScaling.fixedPedestal(ImageScaling.inspect(new File[]{inputFile}, null), addedOffset),
                monoOutput, colorOutput);
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
