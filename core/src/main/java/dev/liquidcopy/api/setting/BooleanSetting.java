package dev.liquidcopy.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

public final class BooleanSetting extends Setting<Boolean> {
    public BooleanSetting(String key, String displayName, boolean defaultValue) {
        super(key, displayName, defaultValue);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            set(element.getAsBoolean());
        }
    }

    @Override
    public String format() {
        return value() ? "on" : "off";
    }
}

