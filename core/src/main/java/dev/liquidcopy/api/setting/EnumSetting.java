package dev.liquidcopy.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.Arrays;
import java.util.Locale;

public final class EnumSetting<E extends Enum<E>> extends Setting<E> {
    private final Class<E> enumType;

    public EnumSetting(String key, String displayName, Class<E> enumType, E defaultValue) {
        super(key, displayName, defaultValue);
        this.enumType = enumType;
    }

    public E[] values() {
        return enumType.getEnumConstants().clone();
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value().name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return;
        }
        String name = element.getAsString();
        Arrays.stream(enumType.getEnumConstants())
            .filter(value -> value.name().equalsIgnoreCase(name))
            .findFirst()
            .ifPresent(this::set);
    }

    @Override
    public String format() {
        return value().name().toLowerCase(Locale.ROOT);
    }
}

