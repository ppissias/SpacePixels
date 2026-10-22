package eu.startales.spacepixels.util;

import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;
import nom.tam.util.Cursor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

final class FitsPreparationService {
    private FitsPreparationService() {}

    record Input(FitsFileInformation information, int[] axes, int bitpix, double bzero, double bscale,
                 boolean compressed) {
        FitsFormatChecker.FitsFormat format() {
            return FitsFormatChecker.format(bitpix, axes != null && axes.length == 3);
        }

        boolean unsigned16Bit() {
            return FitsPixelConverter.isUnsigned16Bit(bitpix, bzero, bscale);
        }

        boolean detectionReady() {
            return !compressed && axes != null && axes.length == 2
                    && Arrays.stream(axes).allMatch(axis -> axis > 0) && unsigned16Bit();
        }

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

    static Input[] inspect(File[] files, ExecutorService executor, boolean requireReady) throws Exception {
        FitsSequenceValidator.validateExtensions(files);
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
        FitsSequenceValidator.validateInputs(inputs);
        return inputs;
    }

    private static Input read(File file) throws Exception {
        try (Fits fits = new Fits(file)) {
            boolean compressed = FitsImages.isCompressedFits(fits);
            BasicHDU<?> image = FitsImages.getImageHDU(fits);
            if (image == null) throw new FitsException("No valid image HDU found in " + file.getName());
            int[] axes = image.getAxes();
            int width = axes == null || axes.length < 2 ? 0 : axes[axes.length - 1];
            int height = axes == null || axes.length < 2 ? 0 : axes[axes.length - 2];
            Header header = image.getHeader();
            FitsFileInformation information = new FitsFileInformation(file.getAbsolutePath(), file.getName(),
                    axes != null && axes.length == 2, width, height);
            Cursor<String, HeaderCard> cards = header.iterator();
            while (cards.hasNext()) {
                HeaderCard card = cards.next();
                information.getFitsHeader().put(card.getKey(), card.getValue());
            }
            return new Input(information, axes, header.getIntValue("BITPIX", 0),
                    header.getDoubleValue("BZERO", 0.0), header.getDoubleValue("BSCALE", 1.0), compressed);
        }
    }

    static void standardizeFile(File inputFile, int addedOffset, File monoOutput, File colorOutput) throws Exception {
        try (Fits source = new Fits(inputFile)) {
            BasicHDU<?> image = FitsImages.getImageHDU(source);
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

    private static void write(File file, Object pixels, Header header, int addedOffset) throws Exception {
        try (Fits output = FitsPixelConverter.createFitsFromData(pixels, header, addedOffset)) {
            StagedFile.write(file, staged -> output.write(staged.toFile()));
        }
    }

    static String preparedFilename(File inputFile, String suffix) {
        String filename = inputFile.getName();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".fz")) filename = filename.substring(0, filename.length() - 3);
        String extension = FitsSequenceValidator.imageExtension(filename);
        if (extension != null) filename = filename.substring(0, filename.length() - extension.length());
        return filename + suffix + ".fit";
    }

    private static String headerNumber(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value) : Double.toString(value);
    }
}
