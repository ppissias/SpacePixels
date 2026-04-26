package eu.startales.spacepixels.util;

import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.BasicHDU;

import java.io.File;

/**
 * Lightweight FITS header probe that classifies supported monochrome and color formats without
 * reading the full pixel array into memory.
 */
public class FitsFormatChecker {

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
     * Peeks at the FITS header to determine its format without loading the pixel data into RAM.
     */
    public static FitsFormat checkFormat(File fitsFile) {
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = fits.getHDU(0);
            if (hdu == null) return FitsFormat.UNSUPPORTED;

            Header header = hdu.getHeader();
            int bitpix = header.getIntValue("BITPIX", 0);
            int naxis = header.getIntValue("NAXIS", 0);

            // Default to 1 channel if NAXIS3 isn't present
            int naxis3 = header.getIntValue("NAXIS3", 1);

            boolean isColor = (naxis == 3 && naxis3 >= 3);

            if (bitpix == 16) {
                return isColor ? FitsFormat.COLOR_16BIT : FitsFormat.MONO_16BIT;
            } else if (bitpix == 32) {
                return isColor ? FitsFormat.COLOR_32BIT_INT : FitsFormat.MONO_32BIT_INT;
            } else if (bitpix == -32) {
                return isColor ? FitsFormat.COLOR_32BIT_FLOAT : FitsFormat.MONO_32BIT_FLOAT;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return FitsFormat.UNSUPPORTED;
    }
}
