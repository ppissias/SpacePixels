package eu.startales.spacepixels.preparation;

import eu.startales.spacepixels.fits.FitsInput;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.Header;
import nom.tam.fits.HeaderCard;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Preserves metadata Siril would discard, using the maintainer's fixed-card corrections on private FITS copies only. */
public final class SirilHeaderCorrections {
    private static final Pattern DOTTED_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}T\\d{2})\\.(\\d{2})\\.(\\d{2}(?:\\.\\d+)?)$");
    private static final Pattern SEXAGESIMAL = Pattern.compile("^\\s*([+-]?)(\\d{1,3})[ :]+(\\d{1,2})[ :]+(\\d{1,2}(?:\\.\\d+)?)\\s*$");

    /** A local provenance entry; observing-site values must not be included in published screenshots or logs. */
    public record Correction(String keyword, String original, String corrected) { }

    /** An exact-width card edit, so pixel bytes and every HDU's position remain untouched. */
    private record Edit(long offset, String card, Correction correction) { }

    /** Stateless normalization; the caller must pass a newly copied, run-owned file, never an original or hard link. */
    private SirilHeaderCorrections() { }

    /** Patches quoted site coordinates and dotted observation times before Siril reads the run-owned copy. */
    public static List<Correction> normalizePrivateCopy(Path copy) throws Exception {
        List<Long> offsets = new ArrayList<>();
        try (Fits fits = new Fits(copy.toFile())) {
            BasicHDU<?> hdu;
            while ((hdu = fits.readHDU()) != null) offsets.add(hdu.getHeader().getFileOffset());
        }
        List<Edit> edits = new ArrayList<>();
        List<Long> checksums = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(copy.toFile(), "rw")) {
            byte[] bytes = new byte[80];
            for (long start : offsets) {
                if (start < 0) throw new IOException("Cannot locate a FITS header in the private input copy.");
                file.seek(start);
                boolean ended = false;
                for (int count = 0; count < 100_000; count++) {
                    long position = file.getFilePointer();
                    file.readFully(bytes);
                    String text = new String(bytes, StandardCharsets.US_ASCII);
                    String key = text.substring(0, 8).strip();
                    if (key.equals("END")) { ended = true; break; }
                    if (key.equals("CHECKSUM") || key.equals("DATASUM")) checksums.add(position);
                    if (!List.of("SITELAT", "SITELONG", "DATE-OBS").contains(key)) continue;
                    HeaderCard card = HeaderCard.create(text);
                    String value = card.getValue();
                    if (value == null || value.isBlank()) continue;
                    if (key.equals("DATE-OBS")) {
                        String normalized = normalizeDate(value);
                        if (!normalized.equals(value)) {
                            edits.add(new Edit(position, card(key + "= '" + normalized + "' / Exposure start"),
                                    new Correction(key, value, normalized)));
                        }
                    } else {
                        double number = siteNumber(value, key);
                        if (text.substring(10).stripLeading().startsWith("'")) {
                            String normalized = Double.toString(number);
                            edits.add(new Edit(position, card(String.format(Locale.ROOT, "%-8s= %20s / [deg] Site coordinate", key, normalized)),
                                    new Correction(key, value, normalized)));
                        }
                    }
                }
                if (!ended) throw new IOException("FITS header is too long or incomplete.");
            }
            for (Edit edit : edits) {
                file.seek(edit.offset());
                file.write(edit.card().getBytes(StandardCharsets.US_ASCII));
            }
            if (!edits.isEmpty()) for (long position : checksums) {
                file.seek(position);
                file.write(card("COMMENT Checksum removed after metadata normalization").getBytes(StandardCharsets.US_ASCII));
            }
        }
        return edits.stream().map(Edit::correction).toList();
    }

    /** Normalizes only the documented legacy HH.mm.ss form, validating its calendar value without changing timezone. */
    public static String normalizeDate(String value) throws IOException {
        Matcher match = DOTTED_TIME.matcher(value);
        if (!match.matches()) return value;
        String normalized = match.group(1) + ":" + match.group(2) + ":" + match.group(3);
        try { LocalDateTime.parse(normalized); }
        catch (DateTimeParseException invalid) { throw new IOException("Invalid DATE-OBS in the private FITS copy.", invalid); }
        return normalized;
    }

    /** Converts the maintainer's decimal or signed D:M:S site representation, preserving negative-zero degrees. */
    public static double siteNumber(String value, String keyword) throws IOException {
        double number;
        try { number = Double.parseDouble(value.strip()); }
        catch (NumberFormatException notDecimal) {
            Matcher match = SEXAGESIMAL.matcher(value);
            if (!match.matches()) throw new IOException("Unrecognized " + keyword + " coordinate in the FITS header.");
            double minutes = Double.parseDouble(match.group(3));
            double seconds = Double.parseDouble(match.group(4));
            if (minutes >= 60 || seconds >= 60) throw new IOException("Invalid " + keyword + " coordinate in the FITS header.");
            number = Double.parseDouble(match.group(2)) + minutes / 60 + seconds / 3600;
            if (match.group(1).equals("-")) number = -number;
        }
        double limit = keyword.equals("SITELAT") ? 90 : 180;
        if (!Double.isFinite(number) || Math.abs(number) > limit) throw new IOException("Out-of-range " + keyword + " coordinate in the FITS header.");
        return number;
    }

    /** Reads the selected image header, including the compressed-image HDU behind an empty primary HDU. */
    public static Header imageHeader(Path file) throws Exception {
        try (Fits fits = new Fits(file.toFile())) {
            BasicHDU<?> image = FitsInput.getImageHDU(fits);
            if (image == null) throw new IOException("No image HDU in " + file.getFileName());
            return image.getHeader();
        }
    }

    /** Refuses a replacement that would move the following FITS cards or data blocks. */
    private static String card(String text) throws IOException {
        if (text.length() > 80) throw new IOException("Replacement FITS metadata card is too long.");
        return String.format(Locale.ROOT, "%-80s", text);
    }
}
