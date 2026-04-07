/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Holds high level information for a FITS file
 *
 */
public class FitsFileInformation {
    private static final DateTimeFormatter PIPELINE_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS 'UTC'").withZone(ZoneOffset.UTC);
    private static final Pattern COMBINED_DATE_TIME_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2})[:._-](\\d{2})[:._-](\\d{2})([.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");
    private static final Pattern COMBINED_COMPACT_TIME_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2})(\\d{2})(\\d{2})([.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$");

    private static final class ObservationTimestampParseResult {
        private final long timestampMillis;
        private final String normalizedInput;
        private final String strategy;
        private final String failureReason;

        private ObservationTimestampParseResult(long timestampMillis,
                                               String normalizedInput,
                                               String strategy,
                                               String failureReason) {
            this.timestampMillis = timestampMillis;
            this.normalizedInput = normalizedInput;
            this.strategy = strategy;
            this.failureReason = failureReason;
        }
    }

    private String filePath;
    private String fileName;
    private boolean monochrome;
    private int sizeWidth;
    private int sizeHeight;

    private final Map<String, String> fitsHeader = new HashMap<String, String>();


    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public boolean isMonochrome() {
        return monochrome;
    }

    public void setMonochrome(boolean monochrome) {
        this.monochrome = monochrome;
    }

    public int getSizeWidth() {
        return sizeWidth;
    }

    public void setSizeWidth(int sizeWidth) {
        this.sizeWidth = sizeWidth;
    }

    public int getSizeHeight() {
        return sizeHeight;
    }

    public void setSizeHeight(int sizeHeight) {
        this.sizeHeight = sizeHeight;
    }


    /**
     * @param filePath
     * @param fileName
     * @param monochrome
     * @param sizeWidth
     * @param sizeHeight
     */
    public FitsFileInformation(String filePath, String fileName, boolean monochrome, int sizeWidth, int sizeHeight) {
        super();
        this.filePath = filePath;
        this.fileName = fileName;
        this.monochrome = monochrome;
        this.sizeWidth = sizeWidth;
        this.sizeHeight = sizeHeight;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public Map<String, String> getFitsHeader() {
        return fitsHeader;
    }

    // --- NEW: Helper methods to extract display information from the FITS header map ---

    public String getRawObservationDateHeader() {
        return sanitizeHeaderValue(fitsHeader.get("DATE-OBS"));
    }

    public String getRawObservationTimeHeader() {
        return sanitizeHeaderValue(fitsHeader.get("TIME-OBS"));
    }

    public String getObservationDate() {
        ObservationTimestampParseResult parseResult = parseObservationTimestamp();
        if (parseResult.timestampMillis > 0L) {
            return formatPipelineTimestamp(parseResult.timestampMillis);
        }

        String rawDisplay = getRawObservationDisplay();
        return "N/A".equals(rawDisplay) ? "N/A" : "UNPARSEABLE: " + rawDisplay;
    }

    /**
     * Extracts and parses the ISO-8601 DATE-OBS and TIME-OBS headers.
     * @return Epoch milliseconds, or -1 if the date is missing or malformed.
     */
    public long getObservationTimestamp() {
        return parseObservationTimestamp().timestampMillis;
    }

    public boolean hasDisplayableObservationDateWithoutUsableTimestamp() {
        return !"N/A".equals(getRawObservationDisplay()) && getObservationTimestamp() <= 0L;
    }

    public String getObservationTimestampDiagnostics() {
        ObservationTimestampParseResult parseResult = parseObservationTimestamp();
        StringBuilder sb = new StringBuilder();
        sb.append("rawDATE-OBS='").append(valueOrPlaceholder(getRawObservationDateHeader())).append("'");
        sb.append(", rawTIME-OBS='").append(valueOrPlaceholder(getRawObservationTimeHeader())).append("'");
        sb.append(", rawDisplay='").append(valueOrPlaceholder(getRawObservationDisplay())).append("'");
        sb.append(", guiDisplay='").append(valueOrPlaceholder(getObservationDate())).append("'");
        sb.append(", parseStatus=").append(parseResult.timestampMillis > 0L ? "OK" : "FAILED");
        if (parseResult.strategy != null) {
            sb.append(", strategy='").append(parseResult.strategy).append("'");
        }
        if (parseResult.normalizedInput != null) {
            sb.append(", normalized='").append(parseResult.normalizedInput).append("'");
        }
        if (parseResult.timestampMillis > 0L) {
            sb.append(", timestampMillis=").append(parseResult.timestampMillis);
            sb.append(", timestampUtc='").append(formatPipelineTimestamp(parseResult.timestampMillis)).append("'");
        }
        if (parseResult.failureReason != null) {
            sb.append(", reason='").append(parseResult.failureReason).append("'");
        }
        return sb.toString();
    }

    public String getExposure() {
        String exp = fitsHeader.get("EXPTIME");
        if (exp == null) exp = fitsHeader.get("EXPOSURE"); // Fallback for older FITS standard
        
        if (exp != null) {
            exp = exp.replace("'", "").trim();
            try {
                double val = Double.parseDouble(exp);
                return String.format(java.util.Locale.US, "%.2f s", val);
            } catch (NumberFormatException e) {
                return exp + " s";
            }
        }
        return "N/A";
    }

    /**
     * Extracts and parses the EXPTIME or EXPOSURE header into milliseconds.
     * @return Exposure time in milliseconds, or -1 if missing.
     */
    public long getExposureDurationMillis() {
        String exp = fitsHeader.get("EXPTIME");
        if (exp == null) exp = fitsHeader.get("EXPOSURE"); // Fallback for older FITS standard
        
        if (exp != null) {
            exp = exp.replace("'", "").trim();
            try {
                double val = Double.parseDouble(exp);
                return (long) (val * 1000.0);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    public String getLocation() {
        String lat = fitsHeader.get("SITELAT");
        String lon = fitsHeader.get("SITELONG");
        
        if (lat != null && lon != null) {
            return lat.replace("'", "").trim() + " / " + lon.replace("'", "").trim();
        }
        
        String obs = fitsHeader.get("OBSERVAT");
        if (obs != null) {
            return obs.replace("'", "").trim();
        }
        return "N/A";
    }

    public String getGoogleEarthUrl() {
        String latitude = firstCoordinateHeader("SITELAT", "OBSGEO-B", "LAT-OBS");
        String longitude = firstCoordinateHeader("SITELONG", "SITELON", "OBSGEO-L", "LONG-OBS", "LON-OBS");
        if (latitude == null || longitude == null) {
            return null;
        }

        String searchQuery = sanitizeCoordinateSearchValue(latitude) + ", " + sanitizeCoordinateSearchValue(longitude);
        if (searchQuery.isBlank()) {
            return null;
        }
        return "https://earth.google.com/web/search/" + java.net.URLEncoder.encode(searchQuery, java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean hasGoogleEarthLocation() {
        return getGoogleEarthUrl() != null;
    }

    public boolean isWcsSolved() {
        // A FITS file is widely considered Plate Solved if it contains standard WCS coordinate mappings
        return fitsHeader.containsKey("CRVAL1") && fitsHeader.containsKey("CTYPE1");
    }

    @Override
    public String toString() {
        return "FitsFileInformation [filePath=" + filePath + ", monochrome=" + monochrome + ", sizeWidth=" + sizeWidth
                + ", sizeHeight=" + sizeHeight + ", fitsHeader=" + fitsHeader + "]";
    }

    private String getRawObservationDisplay() {
        String dateObs = getRawObservationDateHeader();
        String timeObs = getRawObservationTimeHeader();

        if (dateObs != null && !dateObs.isEmpty()) {
            if (dateObs.contains("T")) {
                return dateObs.replace("T", " ");
            }
            if (timeObs != null && !timeObs.isEmpty()) {
                return dateObs + " " + timeObs;
            }
            return dateObs;
        }
        return "N/A";
    }

    private String firstCoordinateHeader(String... keys) {
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String value = sanitizeHeaderValue(fitsHeader.get(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private ObservationTimestampParseResult parseObservationTimestamp() {
        String dateObs = getRawObservationDateHeader();
        if (dateObs == null || dateObs.isEmpty()) {
            return new ObservationTimestampParseResult(-1L, null, null, "DATE-OBS missing");
        }

        if (dateObs.contains("T")) {
            return parseCombinedDateTime(dateObs, "DATE-OBS as combined date/time");
        }

        String timeObs = getRawObservationTimeHeader();
        if (timeObs != null && !timeObs.isEmpty()) {
            return parseCombinedDateTime(dateObs + "T" + timeObs, "DATE-OBS + TIME-OBS as combined date/time");
        }

        try {
            long timestampMillis = LocalDate.parse(dateObs, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli();
            return new ObservationTimestampParseResult(timestampMillis, dateObs, "DATE-OBS as ISO_LOCAL_DATE", null);
        } catch (Exception e) {
            return new ObservationTimestampParseResult(-1L, dateObs, "DATE-OBS as ISO_LOCAL_DATE", e.getMessage());
        }
    }

    private ObservationTimestampParseResult parseCombinedDateTime(String candidateInput, String strategy) {
        String normalizedInput = normalizeCombinedDateTime(candidateInput);
        if (normalizedInput == null) {
            return new ObservationTimestampParseResult(-1L, candidateInput, strategy, "Could not normalize date/time value");
        }

        try {
            if (normalizedInput.endsWith("Z") || normalizedInput.matches(".*[+-]\\d{2}:\\d{2}$")) {
                long timestampMillis = OffsetDateTime.parse(normalizedInput, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .toInstant()
                        .toEpochMilli();
                return new ObservationTimestampParseResult(timestampMillis, normalizedInput, strategy + " via ISO_OFFSET_DATE_TIME", null);
            }

            long timestampMillis = LocalDateTime.parse(normalizedInput, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli();
            return new ObservationTimestampParseResult(timestampMillis, normalizedInput, strategy + " via ISO_LOCAL_DATE_TIME", null);
        } catch (Exception e) {
            return new ObservationTimestampParseResult(-1L, normalizedInput, strategy, e.getMessage());
        }
    }

    private static String normalizeCombinedDateTime(String candidateInput) {
        String sanitized = sanitizeHeaderValue(candidateInput);
        if (sanitized == null) {
            return null;
        }

        Matcher dottedMatcher = COMBINED_DATE_TIME_PATTERN.matcher(sanitized);
        if (dottedMatcher.matches()) {
            return buildNormalizedDateTime(
                    dottedMatcher.group(1),
                    dottedMatcher.group(2),
                    dottedMatcher.group(3),
                    dottedMatcher.group(4),
                    dottedMatcher.group(5),
                    dottedMatcher.group(6));
        }

        Matcher compactMatcher = COMBINED_COMPACT_TIME_PATTERN.matcher(sanitized);
        if (compactMatcher.matches()) {
            return buildNormalizedDateTime(
                    compactMatcher.group(1),
                    compactMatcher.group(2),
                    compactMatcher.group(3),
                    compactMatcher.group(4),
                    compactMatcher.group(5),
                    compactMatcher.group(6));
        }

        if (sanitized.matches("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}([.,]\\d+)?(Z|[+-]\\d{2}:?\\d{2})?$")) {
            sanitized = sanitized.replaceFirst(" ", "T");
        }

        return sanitized.replace(',', '.');
    }

    private static String buildNormalizedDateTime(String datePart,
                                                  String hour,
                                                  String minute,
                                                  String second,
                                                  String fraction,
                                                  String zone) {
        StringBuilder normalized = new StringBuilder();
        normalized.append(datePart)
                .append('T')
                .append(hour)
                .append(':')
                .append(minute)
                .append(':')
                .append(second);

        if (fraction != null && !fraction.isEmpty()) {
            normalized.append(fraction.replace(',', '.'));
        }
        if (zone != null && !zone.isEmpty()) {
            normalized.append(normalizeZoneSuffix(zone));
        }
        return normalized.toString();
    }

    private static String normalizeZoneSuffix(String zone) {
        if (zone == null || zone.isEmpty() || "Z".equals(zone)) {
            return zone;
        }
        if (zone.matches("[+-]\\d{2}:\\d{2}")) {
            return zone;
        }
        if (zone.matches("[+-]\\d{4}")) {
            return zone.substring(0, 3) + ":" + zone.substring(3);
        }
        return zone;
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace("'", "").trim();
        return sanitized.isEmpty() ? null : sanitized;
    }

    private static String sanitizeCoordinateSearchValue(String value) {
        String sanitized = sanitizeHeaderValue(value);
        if (sanitized == null) {
            return "";
        }
        return sanitized.toUpperCase(Locale.US).replaceAll("\\s+", " ");
    }

    private static String valueOrPlaceholder(String value) {
        return value == null || value.isEmpty() ? "N/A" : value;
    }

    private static String formatPipelineTimestamp(long timestampMillis) {
        if (timestampMillis <= 0L) {
            return "N/A";
        }
        return PIPELINE_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(timestampMillis));
    }

}
