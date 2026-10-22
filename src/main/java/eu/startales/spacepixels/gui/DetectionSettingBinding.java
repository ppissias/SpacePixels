package eu.startales.spacepixels.gui;

import eu.startales.spacepixels.config.DetectionSetting;
import io.github.ppissias.jtransient.config.DetectionConfig;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** EDT-owned connection between a typed setting and its existing control/model, with no copied engine state. */
record DetectionSettingBinding<Value>(DetectionSetting<Value> setting, Supplier<Value> current, Consumer<Value> display) {
    /** Applies only this control's field to the supplied live configuration, preserving hidden values. */
    void applyTo(DetectionConfig config) {
        setting.write(config, current.get());
    }

    /** Loads this field through the control's existing clamping/formatting policy. */
    void loadFrom(DetectionConfig config) {
        display.accept(setting.read(config));
    }
}
