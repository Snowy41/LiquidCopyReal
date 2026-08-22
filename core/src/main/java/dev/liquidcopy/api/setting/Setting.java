package dev.liquidcopy.api.setting;

import com.google.gson.JsonElement;
import java.util.Objects;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String key;
    private final String displayName;
    private final T defaultValue;
    private final BooleanSupplier visible;
    private T value;

    protected Setting(String key, String displayName, T defaultValue) {
        this(key, displayName, defaultValue, () -> true);
    }

    protected Setting(String key, String displayName, T defaultValue, BooleanSupplier visible) {
        if (!key.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid setting key: " + key);
        }
        this.key = key;
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
        this.visible = Objects.requireNonNull(visible, "visible");
        this.value = defaultValue;
    }

    public final String key() {
        return key;
    }

    public final String displayName() {
        return displayName;
    }

    public final T defaultValue() {
        return defaultValue;
    }

    public final T value() {
        return value;
    }

    public final void set(T value) {
        this.value = normalize(Objects.requireNonNull(value, "value"));
    }

    public final void reset() {
        value = defaultValue;
    }

    public final boolean isVisible() {
        return visible.getAsBoolean();
    }

    protected T normalize(T value) {
        return value;
    }

    public abstract JsonElement toJson();

    public abstract void fromJson(JsonElement element);

    public abstract String format();
}

