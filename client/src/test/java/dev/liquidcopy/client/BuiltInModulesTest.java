package dev.liquidcopy.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.setting.KeybindSetting;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BuiltInModulesTest {
    @Test
    void registersEveryRequestedModuleExactlyOnce() {
        List<Module> modules = BuiltInModules.create();
        Set<String> ids = new HashSet<>();
        modules.forEach(module -> assertTrue(ids.add(module.id()), "duplicate module " + module.id()));

        assertEquals(Set.of(
            "player_esp", "storage_esp", "item_esp", "nametags", "sprint", "triggerbot",
            "cooldown_sync", "chams", "no_jump_delay", "fullbright", "crosshair", "hud"
        ), ids);
    }

    @Test
    void settingsAreUniqueAndOnlyHudStartsEnabled() {
        List<Module> modules = BuiltInModules.create();
        for (Module module : modules) {
            Set<String> keys = new HashSet<>();
            module.settings().forEach(setting ->
                assertTrue(keys.add(setting.key()), module.id() + " duplicate setting " + setting.key())
            );
            assertTrue(keys.contains("keybind"), module.id() + " has no keybind");
            assertEquals(KeybindSetting.UNBOUND, module.keybind().value(),
                module.id() + " should not conflict with chat input by default");
        }
        assertEquals(List.of("hud"), modules.stream()
            .filter(module -> module.metadata().defaultEnabled())
            .map(Module::id)
            .toList());
    }
}
