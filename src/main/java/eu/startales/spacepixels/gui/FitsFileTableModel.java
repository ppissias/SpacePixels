/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.fits.FitsFileInformation;
import eu.startales.spacepixels.detection.QualityControlSnapshot;
import io.github.ppissias.jtransient.engine.FrameQualityResult;

import javax.swing.table.AbstractTableModel;

/** Imported metadata with an optional detached quality readout; row order and input membership never change. */
public class FitsFileTableModel extends AbstractTableModel {

    // 1. Define constants for your visual columns
    /** The frame's number, as the viewers and the reports count it. */
    public static final int COL_NUMBER = 0;
    public static final int COL_FILENAME = 1;
    public static final int COL_COLOR = 2;
    public static final int COL_WIDTH = 3;
    public static final int COL_HEIGHT = 4;
    public static final int COL_DATE = 5;
    public static final int COL_EXPOSURE = 6;
    public static final int COL_LOCATION = 7;
    public static final int COL_EARTH = 8;
    public static final int COL_SOLVED = 9;
    public static final int COL_QUALITY = 10;
    public static final int COL_FWHM = 11;
    public static final int COL_STARS = 12;
    public static final int COL_ELONGATION = 13;
    public static final int COL_QUALITY_REASON = 14;

    private QualityControlSnapshot quality;
    private String activeSettingsKey;
    private boolean qualityCurrent;

    private final FitsFileInformation[] fitsfiles;

    // Formatted with nice capitalization
    private final String[] columns = {"#", "Filename", "Mono/Color", "Width", "Height", "Time (UTC)", "Exposure", "Location", "Loc Link", "Solved",
            "Quality", "FWHM (px)", "Stars", "Elongation", "QC reason"};

    public FitsFileTableModel(FitsFileInformation[] fitsfiles) {
        this.fitsfiles = fitsfiles == null ? new FitsFileInformation[0] : fitsfiles.clone();
    }

    /** Installs a complete immutable snapshot on the EDT; preserves selection, all rows and their order. */
    public void showQuality(QualityControlSnapshot snapshot, String settingsKey) {
        quality = snapshot;
        activeSettingsKey = settingsKey;
        qualityCurrent = quality != null && quality.isCurrent(fitsfiles, activeSettingsKey);
        fireTableRowsUpdated(0, Math.max(0, getRowCount() - 1));
    }

    /** Marks old results stale when detection settings change; returning to the captured profile restores currency. */
    public void activeSettingsChanged(String settingsKey) {
        if (java.util.Objects.equals(activeSettingsKey, settingsKey)) return;
        activeSettingsKey = settingsKey;
        qualityCurrent = quality != null && quality.isCurrent(fitsfiles, activeSettingsKey);
        if (quality != null) fireTableRowsUpdated(0, Math.max(0, getRowCount() - 1));
    }

    /** Whether the complete captured cohort and settings still agree with the table. No disk reads. */
    public boolean isQualityCurrent() {
        return qualityCurrent;
    }

    /** Last completed result, including its thresholds, even when visibly stale. */
    public QualityControlSnapshot quality() { return quality; }

    /** The original file's measured quality; null for unmeasured or changed identities. */
    public FrameQualityResult.Frame qualityAt(int modelRow) {
        return quality == null ? null : quality.frame(getFitsFileAt(modelRow));
    }

    /** Finite quality scalars in fixed units; unavailable evidence is explicit. */
    private static String measured(double value, boolean available) {
        return available && Double.isFinite(value) ? String.format(java.util.Locale.US, "%.2f", value) : "Unavailable";
    }

    // --- 2. NEW EXPLICIT GETTER ---
    // This entirely replaces the need for the hidden column 6
    public FitsFileInformation getFitsFileAt(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < fitsfiles.length) {
            return fitsfiles[rowIndex];
        }
        return null;
    }

    @Override
    public int getColumnCount() {
        return columns.length;
    }

    @Override
    public int getRowCount() {
        return fitsfiles.length;
    }

    @Override
    public String getColumnName(int col) {
        return columns[col];
    }

    /** The row that shows this file object, or -1 when the table shows other files. */
    public int rowOf(FitsFileInformation file) {
        if (file == null) {
            return -1;
        }
        for (int row = 0; row < fitsfiles.length; row++) {
            if (fitsfiles[row] == file) {
                return row;
            }
        }
        for (int row = 0; row < fitsfiles.length; row++) {
            if (fitsfiles[row] != null && fitsfiles[row].getFilePath() != null
                    && fitsfiles[row].getFilePath().equals(file.getFilePath())) {
                return row;
            }
        }
        return -1;
    }

    /** Replaces the row of {@code previous} with {@code updated} and notifies listeners, returning the updated row or -1. */
    public int replaceFile(FitsFileInformation previous, FitsFileInformation updated) {
        int row = rowOf(previous);
        if (row >= 0) {
            fitsfiles[row] = updated;
            qualityCurrent = quality != null && quality.isCurrent(fitsfiles, activeSettingsKey);
            fireTableRowsUpdated(row, row);
        }
        return row;
    }

    public void refreshRow(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < fitsfiles.length) {
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    @Override
    public Object getValueAt(int row, int col) {
        if (row < 0 || row >= fitsfiles.length) return null;

        FitsFileInformation file = fitsfiles[row];
        if (col >= COL_QUALITY) {
            var metric = qualityAt(row);
            if (metric == null) return "";
            return switch (col) {
                case COL_QUALITY -> (isQualityCurrent() ? "" : "Stale · ") + (metric.rejected() ? "Would reject" : "Would keep");
                case COL_FWHM -> measured(metric.medianFwhm(), metric.fwhmStarCount() > 0);
                case COL_STARS -> Integer.toString(metric.starCount());
                case COL_ELONGATION -> measured(metric.medianElongation(), metric.usableShapeStarCount() > 0);
                case COL_QUALITY_REASON -> metric.reason();
                default -> "";
            };
        }

        switch (col) {
            case COL_NUMBER:
                return String.valueOf(row + 1);

            case COL_FILENAME:
                return file.getFileName();

            case COL_COLOR:
                return file.isMonochrome() ? "Monochrome" : "Color";

            case COL_WIDTH:
                return String.valueOf(file.getSizeWidth());

            case COL_HEIGHT:
                return String.valueOf(file.getSizeHeight());

            case COL_DATE:
                return file.getObservationDate();

            case COL_EXPOSURE:
                return file.getExposure();

            case COL_LOCATION:
                return file.getLocation();

            case COL_EARTH:
                return file.hasGoogleEarthLocation() ? "<html><u>Open</u></html>" : "";

            case COL_SOLVED:
                return file.isWcsSolved() ? "Yes" : "No";

            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return false;
    }
}
