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

import io.github.ppissias.jplatesolve.PlateSolveResult;
import eu.startales.spacepixels.util.FitsFileInformation;

import javax.swing.table.AbstractTableModel;

public class FitsFileTableModel extends AbstractTableModel {

    // 1. Define constants for your visual columns
    public static final int COL_FILENAME = 0;
    public static final int COL_COLOR = 1;
    public static final int COL_WIDTH = 2;
    public static final int COL_HEIGHT = 3;
    public static final int COL_SOLVED = 4;

    private final FitsFileInformation[] fitsfiles;

    // "header" and "obj" are removed
    private final String[] columns = {"filename", "mono/color", "width", "length", "solved"};

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

    @Override
    public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
        if (columnIndex == COL_SOLVED && aValue instanceof PlateSolveResult) {
            fitsfiles[rowIndex].setSolveResult((PlateSolveResult) aValue);
            fireTableCellUpdated(rowIndex, columnIndex); // Tell UI to redraw this cell
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

            case COL_SOLVED:
                PlateSolveResult solveResult = file.getSolveResult();
                return (solveResult != null && solveResult.isSuccess()) ? "yes" : "no";

            default:
                return "";
        }
    }

    @Override
    public boolean isCellEditable(int row, int col) {
        return false;
    }
}