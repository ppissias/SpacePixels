package eu.startales.spacepixels.gui;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** Connects an application-owned value to its existing control without copying engine settings. */
record ApplicationSettingBinding<Value>(ApplicationSetting<Value> setting, Supplier<Value> current, Consumer<Value> display) {
    /** Applies the current control through its typed application setter. */
    void apply() {
        setting.write(current.get());
    }

    /** Reloads the application value using the control's existing formatting and clamping policy. */
    void load() {
        display.accept(setting.read());
    }
}
