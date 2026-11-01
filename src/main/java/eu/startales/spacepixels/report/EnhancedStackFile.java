/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.report;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.fits.WcsCoordinateTransformer;
import eu.startales.spacepixels.util.StagedFile;
import nom.tam.fits.BasicHDU;
import nom.tam.fits.Fits;
import nom.tam.fits.FitsException;
import nom.tam.fits.Header;
import nom.tam.fits.header.Standard;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes the report's enhanced stack (F18) as {@value #FOLDER}/{@value #FILE}: a 32-bit floating-point image in the
 * frames' ADU with NaN where no frame has data, under a new header. The header takes from the first combined frame only
 * the cards that describe the target and the instrument ({@link #COPIED}); it gives the first frame's start and the last
 * frame's end, the number of frames and their total exposure, the report's plate solution as read (a fitted correction
 * has no standard card form), and a history card on the method. Integer scaling, single-exposure cards, checksums and
 * the observing site are left out, as the report leaves the site out. The file is staged and replaces its target only
 * when complete.
 */
final class EnhancedStackFile {
    /** The folder inside the report folder. */
    static final String FOLDER = "enhanced_stack";
    /** The file name. */
    static final String FILE = "enhanced_stack.fits";
    /** The link to the file from the report. */
    static final String LINK = ReportFiles.link(FOLDER, FILE);
    /** Cards copied from the first combined frame when present. */
    static final List<String> COPIED = List.of("OBJECT", "TELESCOP", "INSTRUME", "FILTER", "FOCALLEN", "APTDIA",
            "XPIXSZ", "YPIXSZ", "XBINNING", "YBINNING", "SPOFFSET");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private EnhancedStackFile() {
    }

    /** The file inside a report folder. */
    static File in(File reportFolder) {
        return new File(reportFolder, LINK);
    }

    /**
     * Writes the stack of the context into its report folder.
     *
     * @return the written file
     * @throws IOException when the file cannot be written; the target is then absent or unchanged
     */
    static File write(DetectionReportContext context) throws IOException {
        EnhancedStack stack = context.enhancedStack;
        File target = context.file(LINK);
        try {
            BasicHDU<?> hdu = Fits.makeHDU(stack.pixels);
            describe(hdu.getHeader(), context, stack);
            StagedFile.write(target, path -> {
                try (Fits fits = new Fits()) {
                    fits.addHDU(hdu);
                    // The library's default comment gives the writing computer's local time and time zone.
                    hdu.getHeader().findCard(Standard.SIMPLE).setComment("conforms to the FITS standard");
                    fits.write(path.toFile());
                } catch (FitsException failure) {
                    throw new IOException(failure.getMessage(), failure);
                }
            });
        } catch (FitsException failure) {
            throw new IOException("The enhanced stack could not be written: " + failure.getMessage(), failure);
        }
        return target;
    }

    /** Adds the descriptive cards to the new header. */
    static void describe(Header header, DetectionReportContext context, EnhancedStack stack) throws FitsException {
        List<Integer> frames = context.keptFrameIndices;
        FitsFileInformation[] files = context.fitsFiles;
        FitsFileInformation first = frames.isEmpty() || files == null || files.length <= frames.get(0) ? null : files[frames.get(0)];
        if (first != null && first.getFitsHeader() != null) {
            Map<String, String> source = first.getFitsHeader();
            for (String key : COPIED) {
                String value = source.get(key);
                if (value != null && !value.isBlank()) {
                    addCopied(header, key, value.trim());
                }
            }
        }
        long totalExposure = 0;
        long start = Long.MAX_VALUE;
        long end = Long.MIN_VALUE;
        for (int index : frames) {
            if (files == null || index >= files.length || files[index] == null) {
                continue;
            }
            long exposure = Math.max(0, files[index].getExposureDurationMillis());
            totalExposure += exposure;
            long time = files[index].getObservationTimestamp();
            if (time > 0) {
                start = Math.min(start, time);
                end = Math.max(end, time + exposure);
            }
        }
        if (start != Long.MAX_VALUE) {
            header.addValue("DATE-OBS", ISO.format(Instant.ofEpochMilli(start)), "start of the first combined frame (UTC)");
            header.addValue("DATE-END", ISO.format(Instant.ofEpochMilli(end)), "end of the last combined frame (UTC)");
        }
        header.addValue("NCOMBINE", stack.frames, "number of frames combined");
        header.addValue("EXPTOTAL", totalExposure / 1000.0, "[s] total exposure of the combined frames");
        WcsCoordinateTransformer wcs = context.astrometryContext != null ? context.astrometryContext.getTransformer() : null;
        if (wcs != null) {
            for (Map.Entry<String, Object> card : wcs.nominalHeaderCards().entrySet()) {
                Object value = card.getValue();
                if (value instanceof String text) header.addValue(card.getKey(), text, "");
                else if (value instanceof Integer number) header.addValue(card.getKey(), number, "");
                else header.addValue(card.getKey(), ((Number) value).doubleValue(), "");
            }
            header.insertComment("WCS: the plate solution of the frames as read.");
            if (wcs.isCorrected()) {
                header.insertComment("The report's fitted correction to the stars is not included.");
            }
        }
        // Lines of at most 72 characters, so that no card is continued in the middle of a word.
        header.insertHistory("SpacePixels enhanced stack of the frames the detection kept: after");
        header.insertHistory("removing each frame's sky offset, a mean of the values within 3 sigma");
        header.insertHistory("of their frame's noise (measured from frame differences), weighted by");
        header.insertHistory("1/variance. Values in the frames' ADU; NaN where no frame has data.");
    }

    /** A copied card as a number when it reads as one, otherwise as text without its quotes. */
    private static void addCopied(Header header, String key, String value) throws FitsException {
        String text = value.startsWith("'") && value.endsWith("'") && value.length() >= 2
                ? value.substring(1, value.length() - 1).trim() : value;
        try {
            double number = Double.parseDouble(text);
            if (number == Math.rint(number) && Math.abs(number) < Integer.MAX_VALUE && !text.contains(".")) {
                header.addValue(key, (int) number, "");
            } else {
                header.addValue(key, number, "");
            }
        } catch (NumberFormatException notANumber) {
            header.addValue(key, text, "");
        }
    }
}
