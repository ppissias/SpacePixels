package eu.startales.spacepixels.gui;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Connects an application-owned setting (its identity, presentation, typed owner and control callbacks)
 * to its Swing control without copying engine settings.
 */
record ApplicationSettingBinding<Value>(String id, String title, String unit, String description, boolean expert,
        Class<Value> type, Supplier<Value> getter, Consumer<Value> setter,
        Supplier<Value> current, Consumer<Value> display) {

    /** Requires explicit identity, presentation, ownership and control access without retaining an engine configuration. */
    ApplicationSettingBinding {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(description);
        Objects.requireNonNull(type);
        Objects.requireNonNull(getter);
        Objects.requireNonNull(setter);
        Objects.requireNonNull(current);
        Objects.requireNonNull(display);
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

    /** Applies the current control through its typed application setter. */
    void apply() {
        write(current.get());
    }

    /** Reloads the application value using the control's existing formatting and clamping policy. */
    void load() {
        display.accept(read());
    }

    /** Supplies the same guidance and units to search, labels and accessible descriptions. */
    String help() {
        return description + (unit.isEmpty() ? "" : " Unit: " + unit + ".");
    }
}
