package eu.startales.spacepixels.gui;

import java.awt.Color;
import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

/** Light quality tints across complete rows, respecting the active theme, selection and view/model order. */
final class QualityControlTable extends JTable {
    /** Resets reused renderers on every cell so stale/unmeasured rows never inherit a previous row's tint. */
    @Override public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component component = super.prepareRenderer(renderer, row, column);
        if (!isCellSelected(row, column)) {
            Color base = getBackground();
            if (getModel() instanceof FitsFileTableModel model && model.isQualityCurrent()) {
                var quality = model.qualityAt(convertRowIndexToModel(row));
                if (quality != null) base = blend(base, quality.rejected() ? new Color(220, 80, 75) : new Color(60, 165, 100));
            }
            component.setBackground(base);
        }
        if (component instanceof JComponent cell && getModel() instanceof FitsFileTableModel model
                && convertColumnIndexToModel(column) >= FitsFileTableModel.COL_QUALITY) {
            var quality = model.qualityAt(convertRowIndexToModel(row));
            cell.setToolTipText(quality == null ? "Run quality control to measure every imported frame."
                    : (model.isQualityCurrent() ? "" : "Stale result: rerun quality control. ") + quality.reason()
                    + " — Select a frame and open Quality details for measurements and session limits.");
        }
        return component;
    }

    /** Adds twelve percent of the verdict colour to the theme's normal background. */
    private static Color blend(Color base, Color tint) {
        return new Color((base.getRed() * 88 + tint.getRed() * 12) / 100,
                (base.getGreen() * 88 + tint.getGreen() * 12) / 100,
                (base.getBlue() * 88 + tint.getBlue() * 12) / 100);
    }
}
