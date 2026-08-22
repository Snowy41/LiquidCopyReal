package dev.liquidcopy.api.setting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class NumberSettingTest {
    @Test
    void clampsAndSnapsValues() {
        NumberSetting setting = new NumberSetting("range", "Range", 3.0, 1.0, 6.0, 0.5);
        setting.set(9.0);
        assertEquals(6.0, setting.value());
        setting.set(2.26);
        assertEquals(2.5, setting.value());
    }
}

