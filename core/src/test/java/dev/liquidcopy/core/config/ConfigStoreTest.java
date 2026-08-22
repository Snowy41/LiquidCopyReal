package dev.liquidcopy.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.liquidcopy.api.module.Module;
import dev.liquidcopy.api.module.ModuleCategory;
import dev.liquidcopy.api.module.ModuleContext;
import dev.liquidcopy.api.module.ModuleMetadata;
import dev.liquidcopy.api.module.ModuleRegistry;
import dev.liquidcopy.api.setting.BooleanSetting;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void savesAndLoadsTypedModuleState() throws Exception {
        Path profile = directory.resolve("profile.json");
        DemoModule original = new DemoModule();
        ModuleRegistry source = registry(original);
        source.setEnabled(original.id(), true, ModuleContext.EMPTY);
        original.outline.set(false);
        new ConfigStore(profile).save(source);

        DemoModule restored = new DemoModule();
        ModuleRegistry target = registry(restored);
        ConfigStore.LoadResult result = new ConfigStore(profile).load(target, ModuleContext.EMPTY);

        assertTrue(result.existingProfile());
        assertEquals(1, result.modulesLoaded());
        assertEquals(2, result.settingsLoaded()); // outline + automatically registered keybind
        assertTrue(restored.isEnabled());
        assertEquals(false, restored.outline.value());
    }

    @Test
    void preservesUnknownAddOnKeysAcrossSave() throws Exception {
        Path profile = directory.resolve("profile.json");
        Files.writeString(profile, """
            {
              "schema": 1,
              "futureRoot": "keep",
              "modules": {
                "demo": {
                  "enabled": false,
                  "futureModule": 42,
                  "settings": {"outline": true, "futureSetting": "keep"}
                },
                "third_party": {"enabled": true, "settings": {"x": 1}}
              }
            }
            """);
        ModuleRegistry registry = registry(new DemoModule());
        ConfigStore store = new ConfigStore(profile);
        store.load(registry, ModuleContext.EMPTY);
        store.save(registry);

        JsonObject root = JsonParser.parseString(Files.readString(profile)).getAsJsonObject();
        assertEquals("keep", root.get("futureRoot").getAsString());
        assertTrue(root.getAsJsonObject("modules").has("third_party"));
        JsonObject demo = root.getAsJsonObject("modules").getAsJsonObject("demo");
        assertEquals(42, demo.get("futureModule").getAsInt());
        assertEquals("keep", demo.getAsJsonObject("settings").get("futureSetting").getAsString());
    }

    private static ModuleRegistry registry(Module module) {
        ModuleRegistry registry = new ModuleRegistry((ignored, failure) -> {
            throw new AssertionError(failure);
        });
        registry.register(module);
        return registry;
    }

    private static final class DemoModule extends Module {
        private final BooleanSetting outline = setting(new BooleanSetting("outline", "Outline", true));

        private DemoModule() {
            super(new ModuleMetadata("demo", "Demo", "Test module", ModuleCategory.RENDER, false), -1);
        }
    }
}
