package dev.liquidcopy.api.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumberSetting extends Setting<Double> {
    private final double minimum;
    private final double maximum;
    private final double step;

    public NumberSetting(
        String key,
        String displayName,
        double defaultValue,
        double minimum,
        double maximum,
        double step
    ) {
        super(key, displayName, defaultValue);
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
            throw new IllegalArgumentException("Invalid numeric range");
        }
        if (!Double.isFinite(step) || step <= 0.0) {
            throw new IllegalArgumentException("Step must be positive");
        }
        this.minimum = minimum;
        this.maximum = maximum;
        this.step = step;
        set(defaultValue);
    }

    public double minimum() {
        return minimum;
    }

    public double maximum() {
        return maximum;
    }

    public double step() {
        return step;
    }

    @Override
    protected Double normalize(Double value) {
        double clamped = Math.clamp(value, minimum, maximum);
        double snapped = minimum + Math.round((clamped - minimum) / step) * step;
        return Math.clamp(snapped, minimum, maximum);
    }

    @Override
    public JsonElement toJson() {
        return new JsonPrimitive(value());
    }

    @Override
    public void fromJson(JsonElement element) {
        if (element != null && element.isJsonPrimitive()) {
            set(element.getAsDouble());
        }
    }

    @Override
    public String format() {
        return BigDecimal.valueOf(value()).setScale(3, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString();
    }
}

