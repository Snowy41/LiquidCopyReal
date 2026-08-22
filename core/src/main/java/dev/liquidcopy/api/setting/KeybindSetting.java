package dev.liquidcopy.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class KeybindSetting extends Setting<Integer> {
    public static final int UNBOUND = -1;

    public KeybindSetting(int defaultKeyCode) {
        super("keybind", "Keybind", defaultKeyCode);
    }

    @Override
    protected Integer normalize(Integer value) {
        return value < UNBOUND ? UNBOUND : value;
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            set(element.getAsInt());
        }
    }

    @Override
    public String format() {
        return value() == UNBOUND ? "unbound" : Integer.toString(value());
    }
}

