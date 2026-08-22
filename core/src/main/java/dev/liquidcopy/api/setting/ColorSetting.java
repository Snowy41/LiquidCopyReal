package dev.liquidcopy.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Locale;

/** Stores a full ARGB color in a stable hexadecimal representation. */
public final class ColorSetting extends Setting<Integer> {
    public ColorSetting(String key, String displayName, int defaultArgb) {
        super(key, displayName, defaultArgb);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(format());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }
        String raw = element.getAsString().trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (raw.length() == 6) {
            raw = "FF" + raw;
        }
        if (raw.length() == 8) {
            set((int) Long.parseUnsignedLong(raw, 16));
        }
    }

    @Override
    public String format() {
        return String.format(Locale.ROOT, "#%08X", value());
    }
}

