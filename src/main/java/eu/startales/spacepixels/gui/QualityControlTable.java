package eu.startales.spacepixels.gui;

import java.awt.Color;
import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.table.TableCellRenderer;

/** Light quality tints across complete rows, respecting the active theme, selection and view/model order. */
final class QualityControlTable extends JTable {
    /** The colour of a Would keep verdict, used as a tint. */
    static final Color KEEP_COLOUR = new Color(60, 165, 100);
    /** The colour of a Would reject verdict, used as a tint. */
    static final Color REJECT_COLOUR = new Color(220, 80, 75);

    /** Resets reused renderers on every cell so stale/unmeasured rows never inherit a previous row's tint. */
    @Override public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
        Component component = super.prepareRenderer(renderer, row, column);
        if (!isCellSelected(row, column)) {
            Color base = getBackground();
            if (getModel() instanceof FitsFileTableModel model && model.isQualityCurrent()) {
                var quality = model.qualityAt(convertRowIndexToModel(row));
                if (quality != null) base = blend(base, quality.rejected() ? REJECT_COLOUR : KEEP_COLOUR, 12);
            }
            component.setBackground(base);
        }
        if (component instanceof JComponent cell && getModel() instanceof FitsFileTableModel model
                && convertColumnIndexToModel(column) >= FitsFileTableModel.COL_QUALITY) {
            var quality = model.qualityAt(convertRowIndexToModel(row));
            cell.setToolTipText(quality == null ? "Run quality control to measure every imported frame."
                    : (model.isQualityCurrent() ? "" : "Stale result: rerun quality control. ") + quality.reason()
                    + " — Right-click the frame for Quality details: its measurements and the session limits.");
        }
        return component;
    }

    /** Adds this percentage of the tint to the theme's normal background. */
    static Color blend(Color base, Color tint, int percent) {
        int rest = 100 - percent;
        return new Color((base.getRed() * rest + tint.getRed() * percent) / 100,
                (base.getGreen() * rest + tint.getGreen() * percent) / 100,
                (base.getBlue() * rest + tint.getBlue() * percent) / 100);
    }
}
