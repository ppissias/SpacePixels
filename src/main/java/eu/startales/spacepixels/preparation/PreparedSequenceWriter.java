package eu.startales.spacepixels.preparation;

import eu.startales.spacepixels.fits.FitsInput;
import eu.startales.spacepixels.fits.FitsPixelConverter;
import eu.startales.spacepixels.util.StagedFile;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Plans one sequence transform and writes metadata/provenance; FitsPixelConverter owns all pixel conversion. */
public final class PreparedSequenceWriter {
    /** An output's unambiguous association with its converted source metadata. */
    public record Frame(Path original, Path metadata, Path registered) { }

    /** The common physical-value transform and final files recorded in preparation provenance. */
    public record Result(double multiplier, double addedOffset, int width, int height, List<Path> files) {
        /** Detaches the published file list from working collections. */
        public Result { files = List.copyOf(files); }
    }

    /** One borrowed image kernel; no image buffer escapes the worker's current iteration. */
    private record Image(Header header, FitsPixelConverter.Pixels pixels) { }

    /** The writer has no mutable application state. */
    private PreparedSequenceWriter() { }

    /** Chooses a common cohort transform, then delegates conversion with the ordinary importer's shared pixel engine. */
    public static Result write(List<Frame> frames, Path folder, BooleanSupplier cancelled, IntConsumer progress) throws Exception {
        if (frames.size() < 3) throw new IOException("Preparation needs at least three registered individual exposures.");
        double minimum = 0;
        double maximum = 0;
        int width = -1;
        int height = -1;
        boolean floating = true;
        for (int i = 0; i < frames.size(); i++) {
            check(cancelled);
            Image image = read(frames.get(i).registered());
            if (width < 0) { width = image.pixels().width(); height = image.pixels().height(); }
            if (width != image.pixels().width() || height != image.pixels().height()) throw new IOException("Registered frames have inconsistent dimensions.");
            floating &= image.header().getIntValue("BITPIX") < 0;
            for (int y = 0; y < height; y++) {
                if ((y & 63) == 0) check(cancelled);
                for (int x = 0; x < width; x++) {
                    double value = image.pixels().monochromeValue(y, x);
                    if (!Double.isFinite(value)) throw new IOException("Nonfinite pixels in external preparation output.");
                    minimum = Math.min(minimum, value);
                    maximum = Math.max(maximum, value);
                }
            }
            progress.accept((i + 1) * 40 / frames.size());
        }
        double offset = minimum < 0 ? -minimum : 0;
        if (!(maximum + offset > 0)) throw new IOException("The registered sequence has no usable intensity range.");
        double preferred = floating && maximum <= 10 ? 65_535 : 1;
        double multiplier = Math.min(preferred, 65_535 / (maximum + offset));
        Files.createDirectory(folder);
        List<Path> written = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            check(cancelled);
            Frame frame = frames.get(i);
            Image image = read(frame.registered());
            short[][] pixels = FitsPixelConverter.to16BitMonochrome(image.pixels(), multiplier, offset,
                    () -> cancelled.getAsBoolean() || Thread.currentThread().isInterrupted());
            Header metadata = SirilHeaderCorrections.imageHeader(frame.metadata());
            Header outputHeader = image.header();
            // The source association is explicit. Restore exact timestamps when an external writer rounds them.
            for (String key : List.of("DATE-OBS", "TIME-OBS", "EXPTIME", "EXPOSURE", "FILTER", "SITELAT", "SITELONG",
                    "LIVETIME", "EXPSTART", "EXPEND", "STACKCNT", "INSTRUME", "TELESCOP", "GAIN", "OFFSET",
                    "XBINNING", "YBINNING", "XPIXSZ", "YPIXSZ", "CCD-TEMP", "SET-TEMP", "OBSERVER", "OBJECT", "FOCALLEN")) {
                if (metadata.containsKey(key)) { outputHeader.deleteKey(key); outputHeader.addLine(metadata.findCard(key).copy()); }
            }
            List<String> remove = new ArrayList<>();
            var cards = outputHeader.iterator();
            while (cards.hasNext()) {
                String key = cards.next().getKey();
                if (key != null && (key.matches("(?:CTYPE|CUNIT|CRPIX|CRVAL|CDELT|CROTA)\\d+")
                        || key.matches("(?:CD|PC|PV|PS)\\d+_\\d+") || key.matches("(?:A|B|AP|BP)_(?:ORDER|\\d+_\\d+)")
                        || List.of("WCSAXES", "RADECSYS", "RADESYS", "EQUINOX", "LONPOLE", "LATPOLE", "BAYERPAT",
                        "XBAYROFF", "YBAYROFF", "CHECKSUM", "DATASUM", "SPOFFSET").contains(key))) remove.add(key);
            }
            remove.forEach(outputHeader::deleteKey);
            outputHeader.addValue("SPPSCALE", multiplier, "Prepared sequence common linear multiplier");
            outputHeader.addValue("SPPOFF", offset, "Image-data offset before SPPSCALE; fill stays 0");
            outputHeader.addValue("SPORIGIN", frame.original().getFileName().toString(), "Original exposure filename");
            outputHeader.insertHistory("SpacePixels preparation: RGB mean; common image-data scaling; zero registration fill preserved.");
            Path output = folder.resolve(String.format(java.util.Locale.ROOT, "frame_%06d.fit", i + 1));
            try (Fits fits = FitsPixelConverter.createFitsFromData(pixels, outputHeader)) {
                StagedFile.write(output.toFile(), staged -> fits.write(staged.toFile()));
            }
            written.add(output);
            progress.accept(40 + (i + 1) * 60 / frames.size());
        }
        return new Result(multiplier, offset, width, height, written);
    }

    /** Loads exactly one supported image; its FITS file handle closes before the kernel is used. */
    private static Image read(Path path) throws Exception {
        try (Fits fits = new Fits(path.toFile())) {
            BasicHDU<?> hdu = FitsInput.getImageHDU(fits);
            if (hdu == null) throw new IOException("Missing registered image: " + path.getFileName());
            int[] axes = hdu.getAxes();
            if (axes == null || !(axes.length == 2 || axes.length == 3 && axes[0] == 3)) {
                throw new IOException("Registered outputs must be monochrome or three-channel FITS images.");
            }
            return new Image(hdu.getHeader(), FitsPixelConverter.pixels(hdu.getKernel(), hdu.getHeader()));
        }
    }

    /** Observes cancellation between frames and bounded row batches. */
    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("Preparation cancelled.");
    }
}
