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

import io.github.ppissias.jplatesolve.PlateSolveResult;

import java.util.HashMap;
import java.util.Map;

/**
 * Holds high level information for a FITS file
 *
 */
public class FitsFileInformation {

    private String filePath;
    private String fileName;
    private boolean monochrome;
    private int sizeWidth;
    private int sizeHeight;

    private final Map<String, String> fitsHeader = new HashMap<String, String>();
    private PlateSolveResult solveResult;


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

    public PlateSolveResult getSolveResult() {
        return solveResult;
    }

    public void setSolveResult(PlateSolveResult solveResult) {
        this.solveResult = solveResult;
    }

    public Map<String, String> getFitsHeader() {
        return fitsHeader;
    }

    // --- NEW: Helper methods to extract display information from the FITS header map ---

    public String getObservationDate() {
        String dateObs = fitsHeader.get("DATE-OBS");
        if (dateObs != null) dateObs = dateObs.replace("'", "").trim();
        
        String timeObs = fitsHeader.get("TIME-OBS");
        if (timeObs != null) timeObs = timeObs.replace("'", "").trim();
        
        if (dateObs != null && !dateObs.isEmpty()) {
            if (dateObs.contains("T")) {
                return dateObs.replace("T", " "); // Format ISO-8601 nicely
            } else if (timeObs != null && !timeObs.isEmpty()) {
                return dateObs + " " + timeObs;
            }
            return dateObs;
        }
        return "N/A";
    }

    /**
     * Extracts and parses the ISO-8601 DATE-OBS and TIME-OBS headers.
     * @return Epoch milliseconds, or -1 if the date is missing or malformed.
     */
    public long getObservationTimestamp() {
        String dateObs = fitsHeader.get("DATE-OBS");
        if (dateObs == null) return -1;
        dateObs = dateObs.replace("'", "").trim();

        try {
            if (dateObs.contains("T")) {
                return java.time.LocalDateTime.parse(dateObs, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                           .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
            } else {
                String timeObs = fitsHeader.get("TIME-OBS");
                if (timeObs != null) {
                    timeObs = timeObs.replace("'", "").trim();
                    String combined = dateObs + "T" + timeObs;
                    return java.time.LocalDateTime.parse(combined, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                               .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                } else {
                    return java.time.LocalDate.parse(dateObs, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
                               .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
                }
            }
        } catch (Exception e) {
            return -1;
        }
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

    public boolean isWcsSolved() {
        // A FITS file is widely considered Plate Solved if it contains standard WCS coordinate mappings
        return fitsHeader.containsKey("CRVAL1") && fitsHeader.containsKey("CTYPE1");
    }

    @Override
    public String toString() {
        return "FitsFileInformation [filePath=" + filePath + ", monochrome=" + monochrome + ", sizeWidth=" + sizeWidth
                + ", sizeHeight=" + sizeHeight + ", fitsHeader=" + fitsHeader + ", solveResult=" + solveResult + "]";
    }

}
