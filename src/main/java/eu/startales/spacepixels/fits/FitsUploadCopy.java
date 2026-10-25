/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.fits;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * A copy of a FITS frame for an online solver, without the observing site or the observer. Astrometry.net's anonymous
 * login is one account shared by every anonymous user, so a "private" upload is visible to all of them. The cards
 * below are blanked in every header, and {@code CHECKSUM} too where a card was blanked; the pixels and every other
 * card, the pointing hints included, are copied byte for byte, so the solve is the same.
 */
public final class FitsUploadCopy {

    /** The site's coordinates and elevation, as SpacePixels and common capture programs write them, and the names. */
    static final Set<String> PRIVATE_KEYWORDS = Set.of(
            "SITELAT", "SITELONG", "SITELON", "SITEELEV", "SITENAME",
            "OBSGEO-B", "OBSGEO-L", "OBSGEO-H", "OBSGEO-X", "OBSGEO-Y", "OBSGEO-Z",
            "LAT-OBS", "LONG-OBS", "LON-OBS", "ALT-OBS", "ELEV-OBS", "OBSALT", "ELEVATIO",
            "OBSLAT", "OBSLONG", "OBSLON", "OBSELEV", "LATITUDE", "LONGITUD",
            "GEOLAT", "GEOLON", "GEOELEV", "OBSERVAT", "OBSERVER");

    private static final int BLOCK = 2880;
    private static final int CARD = 80;
    private static final byte[] BLANK_CARD = " ".repeat(CARD).getBytes(StandardCharsets.US_ASCII);

    private FitsUploadCopy() {
    }

    /** Writes the copy to a new temporary file with a neutral name; the caller deletes it. */
    public static File create(File source) throws IOException {
        Path copy = Files.createTempFile("spacepixels-solve-", ".fits");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(source.toPath()));
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(copy))) {
            copyWithoutPrivateCards(in, out);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(copy);
            throw failure;
        }
        return copy.toFile();
    }

    /**
     * Copies every HDU, blanking the private cards of each header. Anything that does not continue as FITS (a file
     * that is not FITS at all, or bytes after the last HDU) is copied as it is.
     */
    static void copyWithoutPrivateCards(InputStream in, OutputStream out) throws IOException {
        boolean first = true;
        byte[] block = new byte[BLOCK];
        int read;
        while ((read = in.readNBytes(block, 0, BLOCK)) > 0) {
            String start = new String(block, 0, Math.min(read, 8), StandardCharsets.US_ASCII);
            if (read < BLOCK || !start.equals(first ? "SIMPLE  " : "XTENSION")) {
                out.write(block, 0, read);
                in.transferTo(out);
                return;
            }
            first = false;
            Header header = readHeader(in, block);
            out.write(header.bytes);
            copyBytes(in, out, padded(header.dataBytes()));
        }
    }

    /** The header that starts with {@code firstBlock}, up to its END card, with the private cards blanked. */
    private static Header readHeader(InputStream in, byte[] firstBlock) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = firstBlock.clone();
        boolean ended = false;
        while (!ended) {
            bytes.write(block);
            for (int card = 0; card < BLOCK / CARD && !ended; card++) {
                ended = keyword(block, card * CARD).equals("END");
            }
            if (!ended && in.readNBytes(block, 0, BLOCK) < BLOCK) {
                throw new IOException("The FITS header ends without an END card.");
            }
        }
        byte[] cards = bytes.toByteArray();
        Header header = new Header(cards);
        boolean blanked = false;
        for (int offset = 0; offset < cards.length; offset += CARD) {
            String key = keyword(cards, offset);
            if (PRIVATE_KEYWORDS.contains(key)) {
                System.arraycopy(BLANK_CARD, 0, cards, offset, CARD);
                blanked = true;
            } else if (key.equals("END")) {
                break;
            }
        }
        if (blanked) {
            // The header changed, so its checksum no longer holds; DATASUM still does.
            for (int offset = 0; offset < cards.length; offset += CARD) {
                if (keyword(cards, offset).equals("CHECKSUM")) {
                    System.arraycopy(BLANK_CARD, 0, cards, offset, CARD);
                }
            }
        }
        return header;
    }

    private static String keyword(byte[] cards, int offset) {
        return new String(cards, offset, 8, StandardCharsets.US_ASCII).trim().toUpperCase(Locale.ROOT);
    }

    private static long padded(long bytes) {
        return (bytes + BLOCK - 1) / BLOCK * BLOCK;
    }

    /** Copies up to {@code count} bytes; a last data block written without its padding ends the copy early. */
    private static void copyBytes(InputStream in, OutputStream out, long count) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long remaining = count;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                return;
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    /** One HDU's header cards and the size of the data that follows them, from BITPIX, NAXISn, PCOUNT and GCOUNT. */
    private static final class Header {
        final byte[] bytes;
        private long bitpix;
        private long gcount = 1;
        private long pcount;
        private long[] axes = new long[0];

        Header(byte[] bytes) {
            this.bytes = bytes;
            for (int offset = 0; offset < bytes.length; offset += CARD) {
                String key = keyword(bytes, offset);
                if (key.equals("END")) {
                    break;
                }
                if (key.equals("BITPIX")) {
                    bitpix = value(offset);
                } else if (key.equals("NAXIS")) {
                    axes = new long[(int) value(offset)];
                } else if (key.matches("NAXIS\\d+")) {
                    int axis = Integer.parseInt(key.substring(5)) - 1;
                    if (axis >= 0 && axis < axes.length) {
                        axes[axis] = value(offset);
                    }
                } else if (key.equals("PCOUNT")) {
                    pcount = value(offset);
                } else if (key.equals("GCOUNT")) {
                    gcount = value(offset);
                }
            }
        }

        long dataBytes() {
            if (axes.length == 0) {
                return 0;
            }
            long elements = 1;
            for (long axis : axes) {
                elements = Math.multiplyExact(elements, axis);
            }
            return Math.multiplyExact(Math.abs(bitpix) / 8 * gcount, Math.addExact(pcount, elements));
        }

        private long value(int offset) {
            String text = new String(bytes, offset + 10, CARD - 10, StandardCharsets.US_ASCII);
            int comment = text.indexOf('/');
            return Long.parseLong((comment >= 0 ? text.substring(0, comment) : text).trim());
        }
    }
}
