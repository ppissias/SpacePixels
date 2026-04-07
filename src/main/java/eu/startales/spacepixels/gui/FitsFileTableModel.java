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

import eu.startales.spacepixels.util.FitsFileInformation;

import javax.swing.table.AbstractTableModel;

public class FitsFileTableModel extends AbstractTableModel {

    // 1. Define constants for your visual columns
    public static final int COL_FILENAME = 0;
    public static final int COL_COLOR = 1;
    public static final int COL_WIDTH = 2;
    public static final int COL_HEIGHT = 3;
    public static final int COL_DATE = 4;
    public static final int COL_EXPOSURE = 5;
    public static final int COL_LOCATION = 6;
    public static final int COL_EARTH = 7;
    public static final int COL_SOLVED = 8;

    private final FitsFileInformation[] fitsfiles;

    // Formatted with nice capitalization
    private final String[] columns = {"Filename", "Mono/Color", "Width", "Height", "Time (UTC)", "Exposure", "Location", "Loc Link", "Solved"};

    public FitsFileTableModel(FitsFileInformation[] fitsfiles) {
        this.fitsfiles = fitsfiles;
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

    public void refreshRow(int rowIndex) {
        if (rowIndex >= 0 && rowIndex < fitsfiles.length) {
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    @Override
    public Object getValueAt(int row, int col) {
        if (row < 0 || row >= fitsfiles.length) return null;

        FitsFileInformation file = fitsfiles[row];

        switch (col) {
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
