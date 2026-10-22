package eu.startales.spacepixels.util;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import nom.tam.fits.*;
import java.io.File;
import java.io.IOException;
import java.util.logging.Logger;

/** FITS derivative operations; no window, dialogs, preferences or session cache. */
final class FitsBatchService {
    private static final Logger LOGGER = Logger.getLogger(FitsBatchService.class.getName());
    private final File directory;
    private final FitsVisualizationRenderer renderer;

    FitsBatchService(File directory, FitsVisualizationRenderer renderer) {
        this.directory = directory;
        this.renderer = renderer;
    }

    void applyWCSHeader(PlateSolveService solver, String headerFile, int stretchFactor, int iterations,
                        boolean stretch, StretchAlgorithm algorithm) throws IOException, FitsException {
        solver.applyWCSHeader(headerFile, FitsFiles.list(directory),
                (file, fits) -> writeUpdatedFITSFile(file, fits, stretchFactor, iterations, stretch, algorithm));
    }

    public File batchConvertToMono(boolean stretch, int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = FitsFiles.list(directory);
        File generatedMonoDirectory = null;

        int total = fitsFileInformation.length;
        for (int fileIndex = 0; fileIndex < total; fileIndex++) {
            File fileInfo = fitsFileInformation[fileIndex];
            if (progressListener != null) {
                int percent = (int) (((float) fileIndex / total) * 100);
                progressListener.onProgressUpdate(percent, "Converting " + fileInfo.getName() + "...");
            }

            try (Fits originalFits = new Fits(fileInfo)) {
                BasicHDU<?> imageHDU = FitsImages.getImageHDU(originalFits);

                // Check if it's already mono to save time
                int naxis = imageHDU.getHeader().getIntValue("NAXIS");
                if (naxis == 3) { // It's a color image
                    LOGGER.info("Converting color image to mono: " + fileInfo.getName());
                    Fits monochromeFits = convertToMono(originalFits);

                    // Save standard mono
                    String monoFilename = addDirectory(fileInfo, "_mono");
                    if (generatedMonoDirectory == null) {
                        generatedMonoDirectory = new File(monoFilename).getParentFile();
                    }
                    writeFitsWithSuffix(monochromeFits, monoFilename, "_mono");

                    // If stretch is enabled, stretch the mono version and save it
                    if (stretch) {
                        LOGGER.info("Stretching newly converted mono image: " + fileInfo.getName());
                        stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                        String monoStretchFilename = addDirectory(fileInfo, "_mono_stretched");
                        writeFitsWithSuffix(monochromeFits, monoStretchFilename, "_mono_stretch");
                    }
                } else {
                    LOGGER.info("Skipping mono conversion (already mono): " + fileInfo.getName());
                }
            }
        }
        return generatedMonoDirectory;
    }

    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = FitsFiles.list(directory);

        int total = fitsFileInformation.length;
        for (int fileIndex = 0; fileIndex < total; fileIndex++) {
            File fileInfo = fitsFileInformation[fileIndex];
            if (progressListener != null) {
                int percent = (int) (((float) fileIndex / total) * 100);
                progressListener.onProgressUpdate(percent, "Stretching " + fileInfo.getName() + "...");
            }

            try (Fits originalFits = new Fits(fileInfo)) {

                LOGGER.info("Stretching image: " + fileInfo.getName());

                // Stretch the file in memory
                stretchFITSImage(originalFits, stretchFactor, iterations, algo);

                // Save the stretched version
                String stretchFilename = addDirectory(fileInfo, "_stretched");
                writeFitsWithSuffix(originalFits, stretchFilename, "_stretch");

            }
        }
    }

    void writeUpdatedFITSFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, boolean stretch, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = FitsImages.getImageHDU(originalFits).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        String newFNameSolved = addDirectory(fileInformation, "_solved");
        writeFitsWithSuffix(originalFits, newFNameSolved, "_wcs");

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
            String newFNameSolvedMono = addDirectory(fileInformation, "_solved_mono");
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMono, "_mono_wcs");
        }

        if (stretch) {
            String newFNameSolvedStretched = addDirectory(fileInformation, "_solved_stretched");
            stretchFITSImage(originalFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_wcs_stretch");

            if (isColor) {
                String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_solved_mono_stretched");
                stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
                writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_wcs_stretch");
            }
        }
    }

    private void writeOnlyStretchedFitsFile(File fileInformation, Fits originalFits, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        int naxis = FitsImages.getImageHDU(originalFits).getHeader().getIntValue("NAXIS");
        boolean isColor = false;
        if (naxis == 3) {
            isColor = true;
        }

        Fits monochromeFits = null;
        if (isColor) {
            monochromeFits = convertToMono(originalFits);
        }

        String newFNameSolvedStretched = addDirectory(fileInformation, "_stretched");
        stretchFITSImage(originalFits, stretchFactor, iterations, algo);
        writeFitsWithSuffix(originalFits, newFNameSolvedStretched, "_stretch");

        if (isColor) {
            String newFNameSolvedMonoStretch = addDirectory(fileInformation, "_mono_stretched");
            stretchFITSImage(monochromeFits, stretchFactor, iterations, algo);
            writeFitsWithSuffix(monochromeFits, newFNameSolvedMonoStretch, "_mono_stretch");
        }
    }

    private Fits convertToMono(Fits colorFITSImage) throws FitsException, IOException {
        BasicHDU<?> originalHDU = FitsImages.getImageHDU(colorFITSImage);
        Object monoKernelData = FitsPixelConverter.extractLuminance(
                FitsPixelConverter.standardizeTo16BitColor(originalHDU.getKernel(), originalHDU.getHeader()));
        return FitsPixelConverter.createFitsFromData(monoKernelData, originalHDU.getHeader());
    }

    private void writeFitsWithSuffix(Fits fitsImage, String fitsFilename, String suffix) throws IOException, FitsException {
        int lastSepPosition = fitsFilename.lastIndexOf(".");
        fitsFilename = fitsFilename.substring(0, lastSepPosition) + suffix + ".fit";
        StagedFile.write(new File(fitsFilename), staged -> fitsImage.write(staged.toFile()));
    }

    private String addDirectory(File currentFile, String directory) throws IOException {
        String newDirectory = currentFile.getParent() + File.separator + directory;
        File newDirFile = new File(newDirectory);
        if (!newDirFile.exists()) {
            newDirFile.mkdirs();
        }
        return newDirFile.getAbsolutePath() + File.separator + currentFile.getName();
    }

    File batchDecompress(File[] files, TransientEngineProgressListener progress) throws Exception {
        File targetDir = null;
        for (int fileIndex = 0; fileIndex < files.length; fileIndex++) {
            File file = files[fileIndex];
            final int percent = (int) (((float) fileIndex / files.length) * 100);
            if (progress != null) progress.onProgressUpdate(percent, "Decompressing " + file.getName() + "...");

            try (Fits originalFits = new Fits(file)) {
                BasicHDU<?> imageHDU = FitsImages.getImageHDU(originalFits);

                if (imageHDU != null) {
                    Fits decompressedFits = new Fits();
                    decompressedFits.addHDU(imageHDU);

                    String outName = addDirectory(file, "_uncompressed");
                    if (outName.toLowerCase().endsWith(".fz")) {
                        outName = outName.substring(0, outName.length() - 3);
                    }

                    if (targetDir == null) targetDir = new File(outName).getParentFile();
                    StagedFile.write(new File(outName), staged -> decompressedFits.write(staged.toFile()));
                }
            }
        }
        return targetDir;
    }

    File batchStandardizeTo16Bit(File[] files, boolean isColor, int addedOffset, TransientEngineProgressListener progress) throws Exception {
        File targetDir = null;
        for (int fileIndex = 0; fileIndex < files.length; fileIndex++) {
            File file = files[fileIndex];
            final int percent = (int) (((float) fileIndex / files.length) * 100);
            if (progress != null) progress.onProgressUpdate(percent, "Converting " + file.getName() + "...");

            LOGGER.info("Converting file to standard 16-bit: " + file.getName());

            // --- DEBUG: Print Original File ---
            printFitsDebugInfo("ORIGINAL", file);

            try (Fits originalFits = new Fits(file)) {
                BasicHDU<?> imageHDU = FitsImages.getImageHDU(originalFits);
                Header origHeader = imageHDU.getHeader();
                Object kernel = imageHDU.getKernel();

                if (isColor) {
                    // 1. Convert to 16-bit Color
                    short[][][] color16 = FitsPixelConverter.standardizeTo16BitColor(kernel, origHeader, addedOffset);
                    Fits colorFits = FitsPixelConverter.createFitsFromData(color16, origHeader, addedOffset);
                    String colorName = addDirectory(file, "_16bit_color");
                    writeFitsWithSuffix(colorFits, colorName, "_16bit_color");

                    // --- DEBUG: Print Converted Color File ---
                    String finalColorPath = colorName.substring(0, colorName.lastIndexOf(".")) + "_16bit_color.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT COLOR", new File(finalColorPath));

                    // 2. Extract Luminance to 16-bit Mono
                    short[][] mono16 = FitsPixelConverter.extractLuminance(color16);
                    Fits monoFits = FitsPixelConverter.createFitsFromData(mono16, origHeader, addedOffset);

                    // Note: No manual header hacking needed here anymore!
                    // createFitsFromData safely handles NAXIS and NAXIS3 automatically.
                    String monoName = addDirectory(file, "_16bit_mono");
                    if (targetDir == null) targetDir = new File(monoName).getParentFile();
                    writeFitsWithSuffix(monoFits, monoName, "_16bit_mono");

                    // --- DEBUG: Print Converted Luminance File ---
                    String finalLuminancePath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit_mono.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT LUMINANCE", new File(finalLuminancePath));

                } else {
                    // Convert to 16-bit Mono
                    short[][] mono16 = FitsPixelConverter.standardizeTo16BitMono(kernel, origHeader, addedOffset);
                    Fits monoFits = FitsPixelConverter.createFitsFromData(mono16, origHeader, addedOffset);
                    String monoName = addDirectory(file, "_16bit_converted");
                    if (targetDir == null) targetDir = new File(monoName).getParentFile();
                    writeFitsWithSuffix(monoFits, monoName, "_16bit");

                    // --- DEBUG: Print Converted Mono File ---
                    String finalMonoPath = monoName.substring(0, monoName.lastIndexOf(".")) + "_16bit.fit";
                    printFitsDebugInfo("CONVERTED 16-BIT MONO", new File(finalMonoPath));
                }
            }
        }
        return targetDir;
    }

    private void printFitsDebugInfo(String label, File fitsFile) {
        if (!DebugOutput.isEnabled()) {
            return;
        }
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = FitsImages.getImageHDU(fits);
            Header header = hdu.getHeader();
            Object kernel = hdu.getKernel();

            int bitpix = header.getIntValue("BITPIX", 0);
            double bzero = header.getDoubleValue("BZERO", 0.0);
            double bscale = header.getDoubleValue("BSCALE", 1.0);

            double min = Double.MAX_VALUE;
            double max = -Double.MAX_VALUE;

            // Scan 2D arrays (Mono)
            if (kernel instanceof float[][]) {
                float[][] data = (float[][]) kernel;
                for (float[] row : data) {
                    for (float val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof short[][]) {
                short[][] data = (short[][]) kernel;
                for (short[] row : data) {
                    for (short val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            } else if (kernel instanceof int[][]) {
                int[][] data = (int[][]) kernel;
                for (int[] row : data) {
                    for (int val : row) {
                        if (val < min) min = val;
                        if (val > max) max = val;
                    }
                }
            }

            System.out.println("\n--- DEBUG: " + label + " ---");
            System.out.println("File:   " + fitsFile.getName());
            System.out.println("BITPIX: " + bitpix);
            System.out.println("BZERO:  " + bzero);
            System.out.println("BSCALE: " + bscale);
            if (min != Double.MAX_VALUE) {
                System.out.println("Raw Min Value: " + min);
                System.out.println("Raw Max Value: " + max);
                // Calculate what the true value is after BZERO/BSCALE are applied by a viewer
                System.out.println("True Visual Min: " + ((min * bscale) + bzero));
                System.out.println("True Visual Max: " + ((max * bscale) + bzero));
            } else {
                System.out.println("(3D Color array, skipping min/max scan for brevity)");
            }
            System.out.println("-----------------------------------");

        } catch (Exception failure) {
            System.err.println("Debug read failed for " + fitsFile.getName() + ": " + failure.getMessage());
        }
    }

    public void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        renderer.stretchFitsImage(fitsImage, stretchFactor, iterations, algo);
    }

    public void onlyStretch(int stretchFactor, int iterations, StretchAlgorithm algo) throws IOException, FitsException {
        File[] fitsFileInformation = FitsFiles.list(directory);
        for (int fileIndex = 0; fileIndex < fitsFileInformation.length; fileIndex++) {
            try (Fits fitsFile = new Fits(fitsFileInformation[fileIndex])) {
                writeOnlyStretchedFitsFile(fitsFileInformation[fileIndex], fitsFile, stretchFactor, iterations, algo);
            }
        }
    }
}
