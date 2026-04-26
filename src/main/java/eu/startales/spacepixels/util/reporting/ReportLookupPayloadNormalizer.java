package eu.startales.spacepixels.util.reporting;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Normalizes raw SatChecker and JPL JSON payloads into the compact structures rendered inside reports.
 */
final class ReportLookupPayloadNormalizer {

    private ReportLookupPayloadNormalizer() {
    }

    static JsonObject normalize(String provider, JsonElement payload) {
        if ("satchecker".equals(provider)) {
            return normalizeSatCheckerPayload(payload);
        }
        if ("jpl".equals(provider)) {
            return normalizeJplPayload(payload);
        }
        return new JsonObject();
    }

    private static JsonObject normalizeSatCheckerPayload(JsonElement payload) {
        JsonObject normalized = new JsonObject();
        if (payload == null || !payload.isJsonObject()) {
            return normalized;
        }

        JsonObject root = payload.getAsJsonObject();
        JsonObject data = getObject(root, "data");
        JsonObject performance = getObject(root, "performance");
        JsonObject satellites = getObject(data, "satellites");

        normalized.addProperty("source", getString(root, "source"));
        normalized.addProperty("version", getString(root, "version"));
        normalized.addProperty("totalSatellites", getInt(data, "total_satellites", satellites != null ? satellites.size() : 0));
        normalized.addProperty("totalPositionResults", getInt(data, "total_position_results", 0));
        addFiniteProperty(normalized, "totalTimeSeconds", getDouble(performance, "total_time"));
        addFiniteProperty(normalized, "calculationTimeSeconds", getDouble(performance, "calculation_time"));

        JsonArray candidates = new JsonArray();
        if (satellites != null) {
            for (Map.Entry<String, JsonElement> entry : satellites.entrySet()) {
                JsonObject satellite = asObject(entry.getValue());
                if (satellite == null) {
                    continue;
                }

                JsonArray positions = getArray(satellite, "positions");
                String firstUtc = null;
                String lastUtc = null;
                String tleEpochUtc = null;
                double minAngle = Double.NaN;
                double maxAngle = Double.NaN;
                double minRangeKm = Double.NaN;
                double maxRangeKm = Double.NaN;

                if (positions != null) {
                    for (JsonElement positionElement : positions) {
                        JsonObject position = asObject(positionElement);
                        if (position == null) {
                            continue;
                        }

                        String utc = getString(position, "date_time");
                        if (firstUtc == null && utc != null) {
                            firstUtc = utc;
                        }
                        if (utc != null) {
                            lastUtc = utc;
                        }

                        if (tleEpochUtc == null) {
                            tleEpochUtc = getString(position, "tle_epoch");
                        }

                        double angle = getDouble(position, "angle");
                        double rangeKm = getDouble(position, "range_km");
                        minAngle = minFinite(minAngle, angle);
                        maxAngle = maxFinite(maxAngle, angle);
                        minRangeKm = minFinite(minRangeKm, rangeKm);
                        maxRangeKm = maxFinite(maxRangeKm, rangeKm);
                    }
                }

                JsonObject candidate = new JsonObject();
                String displayName = getString(satellite, "name");
                if (displayName == null || displayName.isEmpty()) {
                    displayName = entry.getKey();
                }
                candidate.addProperty("displayName", displayName);

                int noradId = getInt(satellite, "norad_id", -1);
                if (noradId > 0) {
                    candidate.addProperty("noradId", noradId);
                    candidate.addProperty("n2yoUrl", "https://www.n2yo.com/satellite/?s=" + noradId);
                    candidate.addProperty("celestrakUrl", "https://celestrak.org/satcat/records.php?CATNR=" + noradId);
                }
                candidate.addProperty("positionCount", positions != null ? positions.size() : 0);
                addStringProperty(candidate, "firstUtc", firstUtc);
                addStringProperty(candidate, "lastUtc", lastUtc);
                addStringProperty(candidate, "tleEpochUtc", tleEpochUtc);
                addFiniteProperty(candidate, "minAngleDeg", minAngle);
                addFiniteProperty(candidate, "maxAngleDeg", maxAngle);
                addFiniteProperty(candidate, "minRangeKm", minRangeKm);
                addFiniteProperty(candidate, "maxRangeKm", maxRangeKm);
                candidates.add(candidate);
            }
        }

        normalized.add("candidates", candidates);
        return normalized;
    }

    private static JsonObject normalizeJplPayload(JsonElement payload) {
        JsonObject normalized = new JsonObject();
        if (payload == null || !payload.isJsonObject()) {
            return normalized;
        }

        JsonObject root = payload.getAsJsonObject();
        JsonObject signature = getObject(root, "signature");
        JsonObject observer = getObject(root, "observer");

        normalized.addProperty("source", getString(signature, "source"));
        normalized.addProperty("version", getString(signature, "version"));
        addStringProperty(normalized, "observerLocation", getString(observer, "location"));
        addStringProperty(normalized, "observationUtc", getString(observer, "obs_date"));
        addStringProperty(normalized, "fovCenter", getString(observer, "fov_center"));

        JsonArray fields = null;
        JsonArray rows = null;
        String resultSetLabel = null;

        if (hasArray(root, "fields_second") && hasArray(root, "data_second_pass")) {
            fields = getArray(root, "fields_second");
            rows = getArray(root, "data_second_pass");
            resultSetLabel = "Second Pass Candidates";
        } else if (hasArray(root, "fields_first") && hasArray(root, "data_first_pass")) {
            fields = getArray(root, "fields_first");
            rows = getArray(root, "data_first_pass");
            resultSetLabel = "First Pass Candidates";
        } else if (hasArray(root, "fields_second") && hasArray(root, "elem_second_pass")) {
            fields = getArray(root, "fields_second");
            rows = getArray(root, "elem_second_pass");
            resultSetLabel = "Second Pass Elements";
        } else if (hasArray(root, "fields_first") && hasArray(root, "elem_first_pass")) {
            fields = getArray(root, "fields_first");
            rows = getArray(root, "elem_first_pass");
            resultSetLabel = "First Pass Elements";
        }

        addStringProperty(normalized, "resultSetLabel", resultSetLabel);
        normalized.addProperty("matchCount", rows != null ? rows.size() : 0);
        normalized.add("fields", fields != null ? fields.deepCopy() : new JsonArray());

        JsonArray normalizedRows = new JsonArray();
        if (rows != null) {
            for (JsonElement rowElement : rows) {
                JsonArray row = rowElement != null && rowElement.isJsonArray() ? rowElement.getAsJsonArray() : null;
                if (row == null) {
                    continue;
                }

                JsonObject rowObject = new JsonObject();
                String objectName = getString(row, 0);
                addStringProperty(rowObject, "objectName", objectName);
                rowObject.add("values", row.deepCopy());
                if (objectName != null && !objectName.isEmpty()) {
                    String lookupSearchString = normalizeSbdbLookupSearchString(objectName);
                    rowObject.addProperty(
                            "sbdbLookupUrl",
                            "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr="
                                    + URLEncoder.encode(lookupSearchString, StandardCharsets.UTF_8));
                    rowObject.addProperty(
                            "sbdbUrl",
                            "https://ssd.jpl.nasa.gov/sbdb.cgi?sstr="
                                    + URLEncoder.encode(lookupSearchString, StandardCharsets.UTF_8));
                }
                normalizedRows.add(rowObject);
            }
        }

        normalized.add("rows", normalizedRows);
        return normalized;
    }

    private static String normalizeSbdbLookupSearchString(String objectName) {
        String trimmed = trimToNull(objectName);
        if (trimmed == null) {
            return "";
        }
        if (trimmed.length() >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")")) {
            String unwrapped = trimToNull(trimmed.substring(1, trimmed.length() - 1));
            if (unwrapped != null) {
                return unwrapped;
            }
        }
        if (trimmed.matches("^\\d+\\s+.*[a-z].*$")) {
            int firstSpace = trimmed.indexOf(' ');
            if (firstSpace > 0) {
                return trimmed.substring(0, firstSpace);
            }
        }
        int lastOpenParen = trimmed.lastIndexOf('(');
        int lastCloseParen = trimmed.endsWith(")") ? trimmed.length() - 1 : -1;
        if (lastOpenParen > 0 && lastCloseParen > lastOpenParen) {
            String trailingDesignation = trimToNull(trimmed.substring(lastOpenParen + 1, lastCloseParen));
            if (trailingDesignation != null) {
                return trailingDesignation;
            }
        }
        return trimmed;
    }

    private static JsonObject getObject(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || !parent.get(fieldName).isJsonObject()) {
            return null;
        }
        return parent.getAsJsonObject(fieldName);
    }

    private static JsonArray getArray(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || !parent.get(fieldName).isJsonArray()) {
            return null;
        }
        return parent.getAsJsonArray(fieldName);
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean hasArray(JsonObject parent, String fieldName) {
        return getArray(parent, fieldName) != null;
    }

    private static String getString(JsonObject parent, String fieldName) {
        return getString(parent, fieldName, null);
    }

    private static String getString(JsonObject parent, String fieldName, String defaultValue) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return defaultValue;
        }
        try {
            return parent.get(fieldName).getAsString();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String getString(JsonArray array, int index) {
        if (array == null || index < 0 || index >= array.size() || array.get(index).isJsonNull()) {
            return null;
        }
        try {
            return array.get(index).getAsString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int getInt(JsonObject parent, String fieldName, int defaultValue) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return defaultValue;
        }
        try {
            return parent.get(fieldName).getAsInt();
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static double getDouble(JsonObject parent, String fieldName) {
        if (parent == null || fieldName == null || !parent.has(fieldName) || parent.get(fieldName).isJsonNull()) {
            return Double.NaN;
        }
        try {
            return parent.get(fieldName).getAsDouble();
        } catch (Exception ignored) {
            return Double.NaN;
        }
    }

    private static void addStringProperty(JsonObject object, String fieldName, String value) {
        if (object != null && fieldName != null && value != null && !value.isEmpty()) {
            object.addProperty(fieldName, value);
        }
    }

    private static void addFiniteProperty(JsonObject object, String fieldName, double value) {
        if (object != null && fieldName != null && Double.isFinite(value)) {
            object.addProperty(fieldName, value);
        }
    }

    private static double minFinite(double current, double candidate) {
        if (!Double.isFinite(candidate)) {
            return current;
        }
        if (!Double.isFinite(current)) {
            return candidate;
        }
        return Math.min(current, candidate);
    }

    private static double maxFinite(double current, double candidate) {
        if (!Double.isFinite(candidate)) {
            return current;
        }
        if (!Double.isFinite(current)) {
            return candidate;
        }
        return Math.max(current, candidate);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
