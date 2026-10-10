/*
 * SpacePixels
 *
 * Copyright (c)2020-2026, Petros Pissias.
 * See the LICENSE file included in this distribution.
 *
 * author: Petros Pissias <petrospis at gmail.com>
 *
 */
package eu.startales.spacepixels.util.skycatalog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A CSV answer of a TAP service: a header line, then one line per row; fields may be quoted. */
final class CsvTable {

    private final Map<String, Integer> columns = new HashMap<>();
    private final List<String[]> rows = new ArrayList<>();

    static CsvTable parse(String text) {
        CsvTable table = new CsvTable();
        List<String[]> lines = splitLines(text);
        if (lines.isEmpty()) {
            return table;
        }
        String[] header = lines.get(0);
        for (int i = 0; i < header.length; i++) {
            table.columns.put(header[i].trim(), i);
        }
        table.rows.addAll(lines.subList(1, lines.size()));
        return table;
    }

    int size() {
        return rows.size();
    }

    boolean hasColumn(String column) {
        return columns.containsKey(column);
    }

    /** The trimmed value, or null when empty or the column is missing. */
    String text(int row, String column) {
        Integer index = columns.get(column);
        String[] fields = rows.get(row);
        if (index == null || index >= fields.length) {
            return null;
        }
        String value = fields[index].trim();
        return value.isEmpty() ? null : value;
    }

    /** The number, or null when empty or not a number. */
    Double number(int row, String column) {
        String value = text(row, column);
        if (value == null) {
            return null;
        }
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String[]> splitLines(String text) {
        List<String[]> lines = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean lineHasContent = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"') {
                quoted = true;
                lineHasContent = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                lineHasContent = true;
            } else if (c == '\n' || c == '\r') {
                if (lineHasContent || field.length() > 0) {
                    fields.add(field.toString());
                    lines.add(fields.toArray(new String[0]));
                }
                fields.clear();
                field.setLength(0);
                lineHasContent = false;
            } else {
                field.append(c);
                lineHasContent = true;
            }
        }
        if (lineHasContent || field.length() > 0) {
            fields.add(field.toString());
            lines.add(fields.toArray(new String[0]));
        }
        return lines;
    }
}
