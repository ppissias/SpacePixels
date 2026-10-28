package eu.startales.spacepixels.fits;

import io.github.ppissias.jtransient.engine.TransientEngineProgressListener;
import nom.tam.fits.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;
import eu.startales.spacepixels.util.DebugOutput;
import eu.startales.spacepixels.util.StagedFile;

/**
 * FITS derivative operations on one folder of frames; no window, dialogs, preferences or session cache. A desktop
 * session owns the one for its folder.
 */
public final class FitsBatchService {
    private static final Logger LOGGER = Logger.getLogger(FitsBatchService.class.getName());
    private final File directory;
    private final FitsVisualizationRenderer renderer;

    /** Works on the frames in {@code directory}, stretching with {@code renderer}. */
    public FitsBatchService(File directory, FitsVisualizationRenderer renderer) {
        this.directory = directory;
        this.renderer = renderer;
    }

    /**
     * Converts all color images in the folder to monochrome and returns the generated mono directory, so the window
     * can offer to import it. With {@code stretch}, stretched mono derivatives are written beside the mono copies.
     */
    public File batchConvertToMono(boolean stretch, int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = FitsInput.list(directory);
        File generatedMonoDirectory = null;

        int total = fitsFileInformation.length;
        for (int fileIndex = 0; fileIndex < total; fileIndex++) {
            File fileInfo = fitsFileInformation[fileIndex];
            if (progressListener != null) {
                int percent = (int) (((float) fileIndex / total) * 100);
                progressListener.onProgressUpdate(percent, "Converting " + fileInfo.getName() + "...");
            }

            try (Fits originalFits = new Fits(fileInfo)) {
                BasicHDU<?> imageHDU = FitsInput.getImageHDU(originalFits);

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

    /** Writes stretched copies of all images in the folder, color or mono. */
    public void batchStretch(int stretchFactor, int iterations, StretchAlgorithm algo, TransientEngineProgressListener progressListener) throws IOException, FitsException {
        File[] fitsFileInformation = FitsInput.list(directory);

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

    private Fits convertToMono(Fits colorFITSImage) throws FitsException, IOException {
        BasicHDU<?> originalHDU = FitsInput.getImageHDU(colorFITSImage);
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
                BasicHDU<?> imageHDU = FitsInput.getImageHDU(originalFits);

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
        ImageScaling.Summary scan = ImageScaling.inspect(files, null);
        return batchStandardizeTo16Bit(files, isColor, ImageScaling.fixedPedestal(scan, addedOffset), progress);
    }

    /** Uses one already-approved scaling plan for every mono output and colour companion. */
    File batchStandardizeTo16Bit(File[] files, boolean isColor, ImageScaling.Plan scaling, TransientEngineProgressListener progress) throws Exception {
        if (files.length == 0) return null;
        File parent = files[0].getAbsoluteFile().getParentFile();
        File monoDirectory = newConversionDirectory(parent, isColor ? "_16bit_mono" : "_16bit_converted");
        File colorDirectory = isColor ? newConversionDirectory(parent, "_16bit_color") : null;
        for (int fileIndex = 0; fileIndex < files.length; fileIndex++) {
            File file = files[fileIndex];
            int percent = (int) (((float) fileIndex / files.length) * 100);
            if (progress != null) progress.onProgressUpdate(percent, "Converting " + file.getName() + "...");
            LOGGER.info("Converting file to standard 16-bit: " + file.getName());
            printFitsDebugInfo("ORIGINAL", file);

            File monoOutput = new File(monoDirectory,
                    FitsPreparation.preparedFilename(file, isColor ? "_16bit_mono" : "_16bit"));
            File colorOutput = null;
            if (isColor) {
                colorOutput = new File(colorDirectory, FitsPreparation.preparedFilename(file, "_16bit_color"));
            }
            FitsPreparation.standardizeFile(file, scaling, monoOutput, colorOutput);
            if (colorOutput != null) printFitsDebugInfo("CONVERTED 16-BIT COLOR", colorOutput);
            printFitsDebugInfo(isColor ? "CONVERTED 16-BIT LUMINANCE" : "CONVERTED 16-BIT MONO", monoOutput);
        }
        return monoDirectory;
    }

    /** Preserves an earlier conversion when a caller tries another scale or negative-value policy. */
    private File newConversionDirectory(File parent, String name) throws IOException {
        try { return Files.createDirectory(parent.toPath().resolve(name)).toFile(); }
        catch (java.nio.file.FileAlreadyExistsException existing) {
            return eu.startales.spacepixels.util.RunDirectories.create(parent.toPath(), name + "_").toFile();
        }
    }

    private void printFitsDebugInfo(String label, File fitsFile) {
        if (!DebugOutput.isEnabled()) {
            return;
        }
        try (Fits fits = new Fits(fitsFile)) {
            BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
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

            DebugOutput.println("\n--- DEBUG: " + label + " ---");
            DebugOutput.println("File:   " + fitsFile.getName());
            DebugOutput.println("BITPIX: " + bitpix);
            DebugOutput.println("BZERO:  " + bzero);
            DebugOutput.println("BSCALE: " + bscale);
            if (min != Double.MAX_VALUE) {
                DebugOutput.println("Raw Min Value: " + min);
                DebugOutput.println("Raw Max Value: " + max);
                // Calculate what the true value is after BZERO/BSCALE are applied by a viewer
                DebugOutput.println("True Visual Min: " + ((min * bscale) + bzero));
                DebugOutput.println("True Visual Max: " + ((max * bscale) + bzero));
            } else {
                DebugOutput.println("(3D Color array, skipping min/max scan for brevity)");
            }
            DebugOutput.println("-----------------------------------");

        } catch (Exception failure) {
            LOGGER.log(Level.WARNING, "Debug read failed for " + fitsFile.getName(), failure);
        }
    }

    /** Stretches one opened image in place with this service's renderer. */
    void stretchFITSImage(Fits fitsImage, int stretchFactor, int iterations, StretchAlgorithm algo) throws FitsException, IOException {
        renderer.stretchFitsImage(fitsImage, stretchFactor, iterations, algo);
    }

}
