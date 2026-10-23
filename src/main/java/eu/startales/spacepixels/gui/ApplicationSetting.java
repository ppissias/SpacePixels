package eu.startales.spacepixels.gui;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** EDT-owned presentation and typed access to one application value; its callbacks share the settings panel's lifetime. */
record ApplicationSetting<Value>(String id, String title, String unit, String description, boolean expert,
        Class<Value> type, Supplier<Value> getter, Consumer<Value> setter) {
    /** Requires explicit identity, presentation and ownership without retaining an engine configuration. */
    ApplicationSetting {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(description);
        Objects.requireNonNull(type);
        Objects.requireNonNull(getter);
        Objects.requireNonNull(setter);
    }

    /** Reads the current application value, preserving its declared numeric or enum type. */
    Value read() {
        return type.cast(getter.get());
    }

    /** Applies only this application value, rejecting null or non-finite input before mutation. */
    void write(Value value) {
        Objects.requireNonNull(value, id);
        type.cast(value);
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(id + " must be finite.");
        }
        setter.accept(value);
    }

    /** Supplies the same guidance and units to search, labels and accessible descriptions. */
    String help() {
        return description + (unit.isEmpty() ? "" : " Unit: " + unit + ".");
    }
}
