package eu.startales.spacepixels.config;

import io.github.ppissias.jtransient.config.DetectionConfig;
import io.github.ppissias.jtransient.config.DetectionConfigRules;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** Application presentation and typed access for one pinned engine field; scientific validation stays in the engine. */
public record DetectionSetting<Value>(String id, String title, String unit, String description, boolean expert,
        Class<Value> type, Function<DetectionConfig, Value> getter, BiConsumer<DetectionConfig, Value> setter) {
    /** Checks the stable engine identity/type up front; accessors never capture a mutable configuration instance. */
    public DetectionSetting {
        Objects.requireNonNull(id);
        Objects.requireNonNull(title);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(description);
        Objects.requireNonNull(type);
        Objects.requireNonNull(getter);
        Objects.requireNonNull(setter);
        var metadata = DetectionConfigRules.metadata().get(id);
        Class<?> engineType = metadata == null ? null : metadata.type();
        if (!(engineType == boolean.class && type == Boolean.class
                || engineType == int.class && type == Integer.class
                || engineType == double.class && type == Double.class)) {
            throw new IllegalArgumentException("Unsupported detection setting contract: " + id + " / " + type.getSimpleName());
        }
    }

    /** Reads this field from the supplied configuration without retaining or modifying it. */
    public Value read(DetectionConfig config) {
        return type.cast(getter.apply(Objects.requireNonNull(config)));
    }

    /** Mutates only this bound field, rejecting null/non-finite input before any mutation. */
    public void write(DetectionConfig config, Value value) {
        Objects.requireNonNull(value, id);
        type.cast(value);
        if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException(id + " must be finite.");
        }
        setter.accept(Objects.requireNonNull(config), value);
    }

    /** Returns engine-owned findings involving this field without redefining ranges or changing legacy values. */
    public List<DetectionConfigRules.Issue> issues(DetectionConfig config) {
        return DetectionConfigRules.validate(config, DetectionConfigRules.Operation.CONFIGURATION).stream()
                .filter(issue -> issue.fields().contains(id)).toList();
    }

    /** Combines application guidance and units for search, labels and assistive descriptions. */
    public String help() {
        return description + (unit.isEmpty() ? "" : " Unit: " + unit + ".");
    }
}
