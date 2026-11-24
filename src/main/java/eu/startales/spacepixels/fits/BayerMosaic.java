package eu.startales.spacepixels.fits;

import nom.tam.fits.Header;
import nom.tam.fits.TruncatedFileException;
import nom.tam.util.FitsFile;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The Bayer colour mosaic that a FITS header describes, read once here for preparation, import, the API and the batch
 * operations. {@code BAYERPAT}, {@code ROWORDER}, {@code XBAYROFF} and {@code YBAYROFF} are not in the FITS standard,
 * but capture programs and Siril write and read them. A raw colour mosaic is a single image plane whose header names
 * a pattern; an image with three planes is already in colour and is never a mosaic, whatever cards it carries.
 * The camera names ({@code CREATOR}, {@code INSTRUME}) identify the device for camera presets. Immutable; reading
 * pixel data is never needed.
 *
 * @param pattern    the pattern as written, upper case without spaces, such as {@code GRBG}; never blank
 * @param rowOrder   {@code TOP-DOWN}, {@code BOTTOM-UP}, another value as written in upper case, or null when absent
 * @param offsetX    the X phase offset in pixels, or null when the header has none
 * @param offsetY    the Y phase offset in pixels, or null when the header has none
 * @param creator    the {@code CREATOR} card, stripped, or null
 * @param instrument the {@code INSTRUME} card, stripped, or null
 */
public record BayerMosaic(String pattern, String rowOrder, Integer offsetX, Integer offsetY, String creator, String instrument) {
    /** The four mosaics the preparation recipe can debayer. */
    private static final Set<String> ORDINARY = Set.of("RGGB", "BGGR", "GRBG", "GBRG");

    /** Requires a pattern; the other facts may be absent. */
    public BayerMosaic {
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isBlank()) throw new IllegalArgumentException("A Bayer mosaic needs a pattern.");
    }

    /** Whether the pattern is one of the four ordinary 2 × 2 mosaics, rather than X-Trans or another layout. */
    public boolean ordinary() { return ORDINARY.contains(pattern); }

    /** Whether the header states a usable row order. */
    public boolean hasRowOrder() { return "TOP-DOWN".equals(rowOrder) || "BOTTOM-UP".equals(rowOrder); }

    /** The X offset in pixels, 0 when the header has none, as Siril reads it. */
    public int effectiveOffsetX() { return offsetX == null ? 0 : offsetX; }

    /** The Y offset in pixels, 0 when the header has none, as Siril reads it. */
    public int effectiveOffsetY() { return offsetY == null ? 0 : offsetY; }

    /** Whether the header has either offset card. */
    public boolean hasOffsets() { return offsetX != null || offsetY != null; }

    /** Whether another file's mosaic is interpreted the same way: pattern, row order and offsets, ignoring the camera names. */
    public boolean sameInterpretation(BayerMosaic other) {
        return other != null && pattern.equals(other.pattern) && Objects.equals(rowOrder, other.rowOrder)
                && effectiveOffsetX() == other.effectiveOffsetX() && effectiveOffsetY() == other.effectiveOffsetY();
    }

    /** A row order for text, such as "top-down". */
    public static String rowOrderText(String rowOrder) {
        return rowOrder == null ? "none" : rowOrder.toLowerCase(Locale.ROOT);
    }

    /**
     * The mosaic of an imported frame's header cards, or null when it is not a raw colour mosaic. {@code planes} is the
     * frame's number of image planes; only a single plane can be a mosaic.
     */
    public static BayerMosaic of(Map<String, String> header, int planes) {
        if (planes != 1 || header == null) return null;
        String pattern = pattern(header.get("BAYERPAT"));
        if (pattern == null) return null;
        return new BayerMosaic(pattern, rowOrder(header.get("ROWORDER")), integer(header.get("XBAYROFF")),
                integer(header.get("YBAYROFF")), text(header.get("CREATOR")), text(header.get("INSTRUME")));
    }

    /**
     * The mosaic of an image header, with each card taken from the primary header when the image header lacks it, as
     * in a tile-compressed file whose empty primary carries the camera cards. Null when it is not a raw colour mosaic.
     */
    public static BayerMosaic of(Header image, Header primary) {
        if (image == null || planes(image) != 1) return null;
        String pattern = pattern(card(image, primary, "BAYERPAT"));
        if (pattern == null) return null;
        return new BayerMosaic(pattern, rowOrder(card(image, primary, "ROWORDER")), integer(card(image, primary, "XBAYROFF")),
                integer(card(image, primary, "YBAYROFF")), text(card(image, primary, "CREATOR")), text(card(image, primary, "INSTRUME")));
    }

    /** Reads a FITS file's headers without its data and returns its mosaic, or null when it is not one. */
    public static BayerMosaic read(File file) throws IOException {
        try (FitsFile input = new FitsFile(file, "r")) {
            Header primary = Header.readHeader(input);
            Header image = primary;
            if (primary.getIntValue("NAXIS", 0) == 0) {
                input.skipAllBytes(Math.max(0L, primary.getDataSize()));
                try {
                    Header next = Header.readHeader(input);
                    if (next != null) image = next;
                } catch (EOFException | TruncatedFileException end) { /* An empty primary with no extension keeps that header. */ }
            }
            return of(image, primary);
        }
    }

    /**
     * The first raw colour mosaic among a folder's FITS images, in path order, or null when there is none. XISF images
     * and unreadable files are left to the ordinary input checks.
     */
    public static BayerMosaic firstIn(File directory) {
        File[] files = directory == null ? null : directory.listFiles(file -> file.isFile()
                && FitsInput.imageExtension(file.getName()) != null && !".xisf".equals(FitsInput.imageExtension(file.getName())));
        if (files == null) return null;
        Arrays.sort(files, Comparator.comparing(File::getAbsolutePath));
        for (File file : files) {
            try {
                BayerMosaic mosaic = read(file);
                if (mosaic != null) return mosaic;
            } catch (IOException | RuntimeException unreadable) { /* The ordinary input checks report it. */ }
        }
        return null;
    }

    /** The first raw colour mosaic among imported frames, or null when there is none. */
    public static BayerMosaic firstOf(FitsFileInformation[] frames) {
        if (frames == null) return null;
        for (FitsFileInformation frame : frames) {
            BayerMosaic mosaic = of(frame.getFitsHeader(), frame.isMonochrome() ? 1 : 3);
            if (mosaic != null) return mosaic;
        }
        return null;
    }

    /** The interpretation every file shares, or null when the list is empty or the files differ. */
    public static BayerMosaic common(List<BayerMosaic> mosaics) {
        if (mosaics == null || mosaics.isEmpty() || mosaics.stream().anyMatch(Objects::isNull)) return null;
        BayerMosaic first = mosaics.getFirst();
        for (BayerMosaic mosaic : mosaics) if (!first.sameInterpretation(mosaic)) return null;
        return first;
    }

    /**
     * The explanation shared by the import question, the API refusal and the batch operations: what these frames are
     * and why they must be prepared first.
     */
    public String rawColourExplanation() {
        return "These are raw colour exposures: a Bayer " + pattern + " mosaic in one image plane. Detection needs them"
                + " calibrated, debayered and registered.";
    }

    /** Image planes of a header, counting a tile-compressed image from ZNAXIS. Zero when the header has no image. */
    public static int planes(Header header) {
        if (header.getBooleanValue("ZIMAGE", false)) {
            int compressed = header.getIntValue("ZNAXIS", 0);
            if (compressed >= 3) return Math.max(header.getIntValue("ZNAXIS3", 1), 1);
            if (compressed == 2) return 1;
        }
        int axes = header.getIntValue("NAXIS", 0);
        if (axes >= 3) return Math.max(header.getIntValue("NAXIS3", 1), 1);
        if (axes == 2) return 1;
        return 0;
    }

    private static String card(Header image, Header primary, String key) {
        String value = image.containsKey(key) ? image.getStringValue(key) : null;
        if (value == null && primary != null && primary != image && primary.containsKey(key)) value = primary.getStringValue(key);
        return value;
    }

    /** Upper case without spaces, underscores or hyphens, or null when blank. */
    private static String pattern(String value) {
        if (value == null) return null;
        String pattern = value.toUpperCase(Locale.ROOT).replaceAll("[\\s_-]", "");
        return pattern.isEmpty() ? null : pattern;
    }

    private static String rowOrder(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip().toUpperCase(Locale.ROOT);
    }

    /** An integer card, accepting a written decimal such as "0." and ignoring anything else. */
    private static Integer integer(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            double number = Double.parseDouble(value.strip());
            return number == Math.rint(number) ? (int) number : null;
        } catch (NumberFormatException notNumber) { return null; }
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
