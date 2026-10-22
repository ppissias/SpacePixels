package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.image.compression.hdu.CompressedImageHDU;
import java.io.IOException;

/** HDU discovery shared by import, conversion and compatibility entry points. */
final class FitsImages {
    private FitsImages() {}

    static BasicHDU<?> getImageHDU(Fits fits) throws FitsException, IOException {
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
}
